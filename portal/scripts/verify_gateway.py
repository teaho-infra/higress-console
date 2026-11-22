#!/usr/bin/env python3
"""Exercise isolated local mock gateway. Never invokes paid providers or resets shared Redis."""
import datetime,http.client,json,time,uuid
from urllib.parse import urlencode
from deploy_demo import ROOT,OUT,HOST,kube,PREFIX
C=json.loads((OUT/'credentials.json').read_text());BASE='/v1/chat/completions';ADMIN=BASE+'/quota'
REPORT={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'host':HOST,'checks':[],'responses':[]}
def call(path=BASE,method='GET',data=None,key='admin',user=None,spoof=None):
    conn=http.client.HTTPConnection('127.0.0.1',18080,timeout=30);headers={'Host':HOST,'Content-Type':'application/json'}
    if key:headers['x-api-key']=C.get(key,key)
    if user:headers['x-codebuddy-user-id']=user
    if spoof:headers['x-mse-consumer']=spoof
    conn.request(method,path,json.dumps(data) if data is not None else None,headers);r=conn.getresponse();body=r.read().decode();status=r.status;hs=dict(r.getheaders());conn.close()
    try:body=json.loads(body)
    except ValueError:pass
    REPORT['responses'].append({'path':path,'method':method,'status':status,'body':body})
    return status,body,hs
def check(name,condition,actual=None):
    REPORT['checks'].append({'name':name,'passed':bool(condition),'actual':actual});print(('PASS ' if condition else 'FAIL ')+name,flush=True)
    if not condition:raise AssertionError(name+': '+str(actual))
def account(d,s):
    code,b,_=call(ADMIN+'/?'+urlencode({'dimension':d,'subject':s}));check('Read '+d+' '+s,code==200,b);return b.get('account',b)
def mutate(action,**data):
    data.setdefault('ticket_id','verify-'+uuid.uuid4().hex);data.setdefault('period',PERIOD)
    return call(ADMIN+'/'+action,'POST',data)
def model(user=None,key='app',stream=False,limited=False,spoof=None):
    return call(('/limited' if limited else '')+BASE,'POST',{'model':'demo-model','messages':[{'role':'user','content':'fixture'}],'stream':stream,'stream_options':{'include_usage':True}},key,user,spoof)
def pause():time.sleep(.4)
def run():
    global PERIOD
    # Only expire window keys in this dedicated demo Redis; preserve quota ledgers.
    for k in kube('exec','deploy/'+PREFIX,'-c','redis','--','redis-cli','--raw','KEYS','higress-token-ratelimit:{'+PREFIX+'}:*').splitlines():
        if k:kube('exec','deploy/'+PREFIX,'-c','redis','--','redis-cli','DEL',k)
    check('Missing key rejected',call(key=None)[0]==401)
    check('Spoofed admin without key rejected',call(ADMIN+'/meta',key=None,spoof='quota-admin')[0]==401)
    check('Query string credentials disabled',call(BASE+'?x-api-key=invalid-query-key',key=None)[0]==401)
    # Never record credentials in query paths.
    REPORT['responses'][-1]['path']=BASE+'?x-api-key=REDACTED'
    check('App cannot administer with spoofed consumer',call(ADMIN+'/meta',key='app',spoof='quota-admin')[0]==403)
    code,meta,_=call(ADMIN+'/meta');check('Admin meta',code==200,meta);PERIOD=meta['period']
    check('L3 exceeds signed 32-bit',meta['tiers']['L3']['quota']>2147483647)
    suffix=uuid.uuid4().hex[:8];alice='verify-user-'+suffix;bob='verify-low-'+suffix
    for d,s,tier in [('app','demo-app','L1'),('app','second-app','L2'),('user',alice,'L2'),('user',bob,'L1')]:
        code,b,_=mutate('assign',dimension=d,subject=s,tier=tier,app='demo-app');check('Assign '+s,code==200,b)
    check('GET action override rejected',call(ADMIN+'?'+urlencode({'dimension':'user','subject':alice,'action':'settle'}))[0]==400)
    check('Internal settle endpoint inaccessible',call(ADMIN+'/settle','POST',{'dimension':'user','subject':alice,'value':-100,'period':PERIOD})[0] in (404,405))
    guarded=account('user',alice)
    for field,value in [('app',{}),('period_start_unix',0)]:
        ticket='bad-field-'+field+'-'+suffix
        code,b,_=mutate('delta',dimension='user',subject=alice,value=1,ticket_id=ticket,**{field:value})
        check('Reject reserved or invalid '+field,code==400,b)
        check('Rejected '+field+' leaves balance unchanged',account('user',alice)['balance']==guarded['balance'])
        code,b,_=mutate('delta',dimension='user',subject=alice,value=0,ticket_id=ticket)
        check('Rejected '+field+' does not consume ticket',code==200,b)
    code,b,_=mutate('assign',dimension='user',subject=alice,tier='L2',app={})
    check('Malformed assign app rejected',code==400,b)
    code,b,_=mutate('delta',dimension='user',subject=alice,value=10000000000000)
    check('Out of range delta rejected',code==400,b)
    code,b,_=mutate('tiers',tiers={'L1':{'quota':330000000,'models':['demo-model']},'L2':{'quota':1000000000,'models':['demo-model']},'L3':{'quota':10000000000000,'models':['demo-model']}})
    check('Out of range tier quota rejected',code==400,b)
    check('Invalid mutations preserve balance',account('user',alice)['balance']==guarded['balance'])
    for label,extra in [('missing',{}),('empty',{'model':''}),('nonstring',{'model':{}}),('disallowed',{'model':'not-allowed-model'})]:
        code,b,_=call(BASE,'POST',dict({'messages':[{'role':'user','content':'blocked fixture'}]},**extra),'app',alice)
        check('Model allowlist rejects '+label,code in (400,403),b)
    check('Rejected models preserve usage',account('user',alice)['usage']==guarded['usage'])
    before_app=account('app','demo-app');before_user=account('user',alice)
    code,b,hs=model(alice,spoof='quota-admin');check('Model succeeds and authenticated consumer wins',code==200 and hs.get('x-mock-consumer')=='demo-app',(code,hs));check('Credential stripped upstream',hs.get('x-mock-credentials-leaked')=='false',hs);pause()
    au=account('user',alice);aa=account('app','demo-app')
    check('Both usage counters advance',au['usage']==before_user['usage']+30 and aa['usage']==before_app['usage']+30,[au,aa])
    check('Higher user tier spends user balance',au['balance']==before_user['balance']-30 and aa['balance']==before_app['balance'],[au,aa])
    before_app=account('app','second-app');before_user=account('user',bob)
    check('Higher app tier request',model(bob,key='second_app')[0]==200);pause()
    aa=account('app','second-app');au=account('user',bob)
    check('Higher app tier spends app balance',aa['balance']==before_app['balance']-30 and au['balance']==before_user['balance'],[au,aa])
    pre=account('user',alice);ticket='upgrade-'+suffix
    code,b,_=mutate('tier',dimension='user',subject=alice,tier='L3',ticket_id=ticket);check('Upgrade immediate',code==200,b)
    post=account('user',alice);check('Upgrade preserves usage',post['usage']==pre['usage'] and post['balance']==pre['balance']+1300000000,post)
    code,b,_=mutate('tier',dimension='user',subject=alice,tier='L3',ticket_id=ticket);check('Upgrade replay idempotent',code==200,b)
    check('Replay no regrant',account('user',alice)['balance']==post['balance'])
    check('Conflicting ticket rejected',mutate('delta',dimension='user',subject=alice,value=10,ticket_id=ticket)[0]==409)
    check('Downgrade rejected',mutate('tier',dimension='user',subject=alice,tier='L1')[0]==409)
    check('Wrong period rejected',mutate('delta',dimension='user',subject=alice,value=10,period='2026-01-05')[0]==409)
    check('Refresh accepted',mutate('refresh',dimension='user',subject=alice)[0]==200)
    check('Refresh no regrant',account('user',alice)['balance']==post['balance'])
    code,b,_=model(alice,stream=True);check('SSE mock returns usage',code==200 and '"total_tokens": 30' in b and '[DONE]' in b,b);pause()
    check('SSE usage accounted',account('user',alice)['usage']==post['usage']+30)
    current=account('user',alice)
    check('Drain balance',mutate('delta',dimension='user',subject=alice,value=-current['balance'])[0]==200)
    check('Exhausted balance403',model(alice)[0]==403)
    check('Restore balance',mutate('delta',dimension='user',subject=alice,value=current['balance'])[0]==200)
    # A dedicated route enforces both user and app token windows, independent of long term budgets.
    one='window-one-'+suffix;two='window-two-'+suffix
    check('First user window request',model(one,limited=True)[0]==200);pause()
    check('Second user window request',model(one,limited=True)[0]==200);pause()
    check('User window429',model(one,limited=True)[0]==429)
    check('Independent user request',model(two,limited=True)[0]==200);pause()
    check('Aggregate app window429',model('window-three-'+suffix,limited=True)[0]==429)
    code,b,_=call(ADMIN+'/audit');check('Audit records available',code==200 and len(b.get('records',[]))>0,b)
    kube('exec','deploy/'+PREFIX,'-c','redis','--','redis-cli','CLIENT','PAUSE','3500','ALL')
    code,b,_=model(alice);check('Redis outage fails closed503',code==503,b)
    time.sleep(3.5)
    check('Redis recovery restores access',model(alice)[0]==200)
    REPORT['passed']=True
if __name__=='__main__':
    try:run()
    finally:
        REPORT.setdefault('passed',False)
        text=json.dumps(REPORT,indent=2)
        for secret in C.values():text=text.replace(secret,'REDACTED')
        (ROOT/'docs/evidence/cluster-report.json').write_text(text+'\n')
