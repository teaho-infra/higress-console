#!/usr/bin/env python3
"""Deploy only isolated walmart-quota-portal resources; existing gateway is read only."""
import argparse, copy, hashlib, json, os, secrets, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT/'.debug/cluster'; PREFIX='walmart-quota-portal'; NS='higress-system'; HOST='quota-portal.local'
def kube(*args,**kw): return subprocess.check_output(['kubectl','--context','higress-dev','-n',NS,*args],text=True,**kw)
def save(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True)
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'w') as f: f.write(obj if isinstance(obj,str) else json.dumps(obj,indent=2)+'\n')
def credentials():
    OUT.mkdir(parents=True,exist_ok=True); OUT.chmod(0o700)
    p=OUT/'credentials.json'
    c=json.loads(p.read_text()) if p.exists() else {k:secrets.token_urlsafe(32) for k in ['app','second_app','admin','redis','portal']}
    save(p,c)
    save(ROOT/'.env',f'PORTAL_PASSWORD={c["portal"]}\nGATEWAY_ADMIN_KEY={c["admin"]}\nGATEWAY_URL=http://127.0.0.1:18080\nGATEWAY_HOST={HOST}\nPORTAL_PORT=18890\n')
    return c
def resource(kind,name,spec=None,**kw):
    r={'apiVersion':'apps/v1' if kind=='Deployment' else 'v1','kind':kind,'metadata':{'name':name,'namespace':NS,'labels':{'app':PREFIX}},**kw}
    if spec is not None:r['spec']=spec
    return r
def prepare():
    c=credentials(); labels={'app':PREFIX}
    items=[resource('Secret',PREFIX,type='Opaque',stringData={'redis-password':c['redis']}),resource('ConfigMap',PREFIX,data={'mock.py':(ROOT/'scripts/mock_model.py').read_text()}),resource('Deployment',PREFIX,{'replicas':1,'selector':{'matchLabels':labels},'template':{'metadata':{'labels':labels},'spec':{'containers':[
        {'name':'mock','image':'python:3.12-alpine','imagePullPolicy':'IfNotPresent','command':['python3','-u','/demo/mock.py'],'ports':[{'containerPort':8080}],'volumeMounts':[{'name':'mock','mountPath':'/demo','readOnly':True}]},
        {'name':'redis','image':'higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/redis-stack-server:7.4.0-v3','imagePullPolicy':'IfNotPresent','command':['sh','-c','exec redis-server --save "" --appendonly no --requirepass "$REDISCLI_AUTH"'],'env':[{'name':'REDISCLI_AUTH','valueFrom':{'secretKeyRef':{'name':PREFIX,'key':'redis-password'}}}],'ports':[{'containerPort':6379}]},
        {'name':'wasm','image':'python:3.12-alpine','imagePullPolicy':'IfNotPresent','command':['python3','-m','http.server','8090','--directory','/wasm'],'ports':[{'containerPort':8090}],'volumeMounts':[{'name':'wasm','mountPath':'/wasm'}]}],
        'volumes':[{'name':'mock','configMap':{'name':PREFIX}},{'name':'wasm','emptyDir':{}}]}}}),resource('Service',PREFIX,{'selector':labels,'ports':[{'name':'http','port':8080},{'name':'tcp-redis','port':6379},{'name':'http-wasm','port':8090}]})]
    routes=[]
    for suffix,path in [('main','/v1/chat/completions'),('limited','/limited/v1/chat/completions')]:
        name=PREFIX+'-'+suffix;routes.append(name)
        items.append({'apiVersion':'networking.k8s.io/v1','kind':'Ingress','metadata':{'name':name,'namespace':NS,'labels':dict(labels,**{'higress.io/resource-definer':'higress'}),'annotations':{'higress.io/request-header-control-remove':'x-api-key,authorization'}},'spec':{'ingressClassName':'higress','rules':[{'host':HOST,'http':{'paths':[{'path':path,'pathType':'Prefix','backend':{'service':{'name':PREFIX,'port':{'number':8080}}}}]}}]}})
    images=json.loads((ROOT/'scripts/plugin-images.json').read_text())
    def plugin(name,phase,priority,config,targets,default=None):
        img=images[name]
        items.append({'apiVersion':'extensions.higress.io/v1alpha1','kind':'WasmPlugin','metadata':{'name':PREFIX+'-'+name,'namespace':NS,'labels':dict(labels,**{'higress.io/wasm-plugin-name':name,'higress.io/wasm-plugin-version':img['image'].rsplit(':',1)[1],'higress.io/wasm-plugin-built-in':'true','higress.io/resource-definer':'higress'})},'spec':{'url':'oci://'+img['image'].rsplit(':',1)[0]+'@'+img['manifest_digest'],'phase':phase,'priority':priority,'failStrategy':'FAIL_CLOSE','defaultConfig':default or {},'defaultConfigDisable':default is None,'matchRules':[{'ingress':[r],'config':config,'configDisable':False} for r in targets]}})
    plugin('transformer','AUTHN',410,{'reroute':False,'reqRules':[{'operate':'remove','headers':[{'key':'x-mse-consumer'}]}]},routes)
    plugin('key-auth','AUTHN',310,{'allow':['demo-app','second-app','quota-admin']},routes,{'global_auth':False,'keys':['x-api-key'],'in_header':True,'in_query':False,'consumers':[{'name':n,'credential':c[k]} for n,k in [('demo-app','app'),('second-app','second_app'),('quota-admin','admin')]]})
    redis={'service_name':PREFIX+'.'+NS+'.svc.cluster.local','service_port':6379,'password':c['redis']}
    plugin('ai-statistics','UNSPECIFIED_PHASE',900,{'attributes':[{'key':'user_id','value_source':'request_header','value':'x-codebuddy-user-id','apply_to_log':True}]},routes)
    plugin('ai-token-ratelimit','UNSPECIFIED_PHASE',600,{'rule_name':PREFIX,'rule_items':[{'limit_by_per_header':h,'limit_keys':[{'key':'*','token_per_minute':limit}]} for h,limit in [('x-codebuddy-user-id',50),('x-mse-consumer',80)]],'redis':redis,'rejected_code':429},[routes[1]])
    save(OUT/'manifests.private.json',{'apiVersion':'v1','kind':'List','items':items})
    print(kube('apply','-f',str(OUT/'manifests.private.json')))
def install(wasm, config=None):
    c=credentials(); digest=hashlib.sha256(wasm.read_bytes()).hexdigest(); filename='quota-'+digest+'.wasm'
    subprocess.check_call(['kubectl','--context','higress-dev','-n',NS,'cp',str(wasm),PREFIX_POD()+':/wasm/'+filename,'-c','wasm'])
    cfg=json.loads((config or ROOT/'scripts/money-config.json').read_text());cfg['redis']={'service_name':PREFIX+'.'+NS+'.svc.cluster.local','service_port':6379,'password':c['redis']}
    r={'apiVersion':'extensions.higress.io/v1alpha1','kind':'WasmPlugin','metadata':{'name':PREFIX+'-ai-quota-walmart','namespace':NS,'labels':{'app':PREFIX,'higress.io/wasm-plugin-name':'ai-quota-walmart','higress.io/wasm-plugin-version':'1.0.0-alpha','higress.io/wasm-plugin-built-in':'false','higress.io/resource-definer':'higress'}},'spec':{'url':'http://'+PREFIX+'.'+NS+'.svc.cluster.local:8090/'+filename,'phase':'UNSPECIFIED_PHASE','priority':750,'failStrategy':'FAIL_CLOSE','defaultConfigDisable':True,'matchRules':[{'ingress':[PREFIX+'-'+s],'config':cfg,'configDisable':False} for s in ['main','limited']]}}
    save(OUT/'custom.private.json',r);print(kube('apply','-f',str(OUT/'custom.private.json')))
def PREFIX_POD():return json.loads(kube('get','pods','-l','app='+PREFIX,'-o','json'))['items'][0]['metadata']['name']
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','install']);p.add_argument('--wasm',type=Path);p.add_argument('--config',type=Path,help='public quota configuration; default scripts/money-config.json');a=p.parse_args()
    if a.action == 'install' and (a.wasm is None or not a.wasm.is_file()): p.error('install requires --wasm pointing to an existing WASM file')
    prepare() if a.action=='prepare' else install(a.wasm,a.config)
