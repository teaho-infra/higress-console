#!/usr/bin/env python3
"""Real HTTP verification of isolated CNY budgets; no paid provider calls."""
import concurrent.futures
import datetime
import hashlib
import json
import time
import uuid
from pathlib import Path
import verify_gateway as v

EVIDENCE = v.ROOT / 'docs/evidence-money'
REPORT = {'verified_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'checks': []}

def check(name, condition, detail=None):
    REPORT['checks'].append({'name': name, 'passed': bool(condition), 'detail': detail})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    if not condition:
        raise AssertionError(name + ': ' + str(detail))

def mutate(action, **data):
    return v.mutate(action, **data)

def assign(subject, tier='inherit', department=None, dimension='user'):
    fields = dict(dimension=dimension, subject=subject, tier=tier)
    if department is not None:
        fields['department'] = department
    code, body, _ = mutate('assign', **fields)
    check('Assign ' + dimension + ' ' + subject, code == 200, body if code != 200 else None)

def account(subject, dimension='user'):
    code, body, _ = v.call(v.ADMIN + '/?dimension=' + dimension + '&subject=' + subject)
    if code != 200:
        raise AssertionError(body)
    return body.get('account', body)

def model(user, model='demo-model', **extra):
    return v.call(v.BASE, 'POST', {'model': model, 'messages': [{'role':'user','content':'local money fixture'}], **extra}, key='app', user=user)

def balance(user, expected):
    for _ in range(20):
        result = account(user)
        if result['balance'] == expected:
            return result
        time.sleep(.1)
    raise AssertionError({'expected_balance':expected, 'actual':result})

def run():
    code, meta, _ = v.call(v.ADMIN + '/meta')
    check('Money policy enabled', code == 200 and meta.get('budget_policy') == 'personal_department_money', meta)
    check('Currency and integer unit', meta.get('currency') == 'CNY' and meta.get('amount_unit') == 'micro_yuan')
    v.PERIOD = meta['period']
    prices = meta['prices']
    tiers = meta['tiers']
    l1, l2, l3 = (tiers[t]['quota'] for t in ['L1','L2','L3'])
    suffix = uuid.uuid4().hex[:8]
    dept, alice, bob, carol = [x+'-'+suffix for x in ['finance','money-personal','money-inherit-a','money-inherit-b']]
    check('No API key denied', v.call(v.ADMIN+'/meta',key=None)[0] == 401)
    check('Forged consumer cannot administer', v.call(v.ADMIN+'/meta',key='app',spoof='quota-admin')[0] == 403)
    check('Missing trusted user denied', model(None)[0] == 401)
    check('Unconfigured user denied', model('unconfigured-'+suffix)[0] == 403)
    unconfigured='money-unconfigured-'+suffix
    check('Unconfigured addition cannot provision identity', mutate('delta',dimension='user',subject=unconfigured,value=1000000)[0]==200 and model(unconfigured)[0]==403)
    check('Unconfigured temporary tier cannot provision identity', mutate('tier',dimension='user',subject=unconfigured,tier='L2')[0]==200 and model(unconfigured)[0]==403)
    check('Missing department cannot be inherited', mutate('assign',dimension='user',subject='bad-dept-'+suffix,tier='inherit',department='no-such-dept-'+suffix)[0]==400)
    check('App cannot change prices', v.call(v.ADMIN+'/prices','POST',{'prices':{},'period':v.PERIOD,'ticket_id':'denied-'+suffix},key='app')[0]==403)
    assign(dept, 'L3', dimension='department')
    assign(alice, 'L1', dept)
    assign(bob, department=dept)
    assign(carol, department=dept)
    a,b,c = account(alice),account(bob),account(carol)
    check('Personal L1 overrides department L3', a['tier']=='L1' and a['limit']==l1 and a['quota_source']=='personal',a)
    check('Unassigned personal tier inherits department', b['tier']=='L3' and b['limit']==l3 and b['quota_source']=='department',b)
    check('Each inherited user has full independent budget', b['balance']==c['balance']==l3)
    check('Department has no shared balance', 'balance' not in account(dept,'department'))
    code,body,hs=model(bob)
    check('Priced model succeeds',code==200,body)
    check('Application key stripped upstream',hs.get('x-mock-credentials-leaked')=='false')
    cheap=(10*prices['demo-model']['input_price_micros']+20*prices['demo-model']['output_price_micros']+999999)//1000000
    b=balance(bob,l3-cheap)
    check('Input/output prices charge exact amount',b['spent']==cheap and b['usage']==30 and b['input_tokens']==10 and b['output_tokens']==20,b)
    check('Other member balance untouched',account(carol)['balance']==l3)
    check('Personal requester always own payer',b['effective_dimension']=='user' and b['effective_subject']==bob)
    before=b['balance']
    expensive=(10*prices['demo-model-pro']['input_price_micros']+20*prices['demo-model-pro']['output_price_micros']+999999)//1000000
    check('Second model succeeds',model(bob,'demo-model-pro')[0]==200)
    b=balance(bob,before-expensive)
    check('Different model rate changes charge',expensive!=cheap and b['spent']==cheap+expensive)
    before=b['balance']
    check('SSE request succeeds',model(bob,stream=True,stream_options={'include_usage':True})[0]==200)
    b=balance(bob,before-cheap)
    check('SSE charges once',b['usage']==90 and b['spent']==cheap*2+expensive,b)
    before=b['balance']
    check('Unpriced model denied',model(bob,'missing-price')[0]==403)
    check('Missing price leaves balance intact',account(bob)['balance']==before)
    ticket='money-delta-'+suffix
    check('Add budget',mutate('delta',dimension='user',subject=bob,value=123456,ticket_id=ticket)[0]==200)
    check('Repeat addition idempotent',mutate('delta',dimension='user',subject=bob,value=123456,ticket_id=ticket)[0]==200 and account(bob)['balance']==before+123456)
    check('Conflicting ticket rejected',mutate('delta',dimension='user',subject=bob,value=1,ticket_id=ticket)[0]==409)
    check('Old period rejected',mutate('delta',dimension='user',subject=bob,value=1,period='2026-01-05')[0]==409)
    check('Department cannot receive pooled additions',mutate('delta',dimension='department',subject=dept,value=1)[0]==400)
    b=account(bob)
    assign(dept,'L2',dimension='department')
    check('Department change preserves existing initialized budget',account(bob)['limit']==b['limit'])
    new='money-new-'+suffix
    assign(new,department=dept)
    check('New member receives updated department template',account(new)['limit']==l2)
    assign(bob,'L1',dept)
    after=account(bob)
    check('Personal reassignment preserves spend and addon',after['limit']==l1+123456 and after['spent']==b['spent'],after)
    assign(bob,'inherit',dept)
    after=account(bob)
    check('Return to inheritance preserves spend/addon',after['limit']==l2+123456 and after['spent']==b['spent'] and after['quota_source']=='department',after)
    check('Refresh does not refill budget',mutate('refresh',dimension='user',subject=bob)[0]==200 and account(bob)['balance']==after['balance'])
    check('Zero personal balance can be configured',mutate('delta',dimension='user',subject=alice,value=-account(alice)['balance'])[0]==200)
    check('Exhaustion403 instead of department/app fallback',model(alice)[0]==403)
    check('Other member still works after sibling exhaustion',model(carol)[0]==200)
    check('Internal settlement route unavailable',v.call(v.ADMIN+'/settle','POST',{'value':-1})[0] in (404,405))
    for field,val in [('department',{}),('cost_micros',-1),('payer_dimension','app'),('period_start_unix',0)]:
        payload=dict(dimension='user',subject=bob,value=1)
        payload[field]=val
        before=account(bob)['balance']
        code,body,_=mutate('delta',**payload)
        check('Reject forged field '+field,code==400,body)
        check('Rejected field leaves ledger unchanged '+field,account(bob)['balance']==before)
    try:
        custom=dict(prices)
        custom['rounding-model']={'input_price_micros':1,'output_price_micros':1}
        custom['free-model']={'input_price_micros':0,'output_price_micros':0}
        check('Price configuration saved',mutate('prices',prices=custom)[0]==200)
        before=account(bob)
        check('Sub-micro combined price request',model(bob,'rounding-model')[0]==200)
        after=balance(bob,before['balance']-1)
        check('Combined rounding is one micro not two',after['spent']==before['spent']+1)
        before=after
        check('Explicitly free model allowed with budget',model(bob,'free-model')[0]==200)
        time.sleep(.4)
        after=account(bob)
        check('Free usage counted with zero charge',after['usage']==before['usage']+30 and after['balance']==before['balance'])
        check('Exhausted account still blocked on free model',model(alice,'free-model')[0]==403)
        changed=dict(custom)
        changed['demo-model']={'input_price_micros':6000000,'output_price_micros':12000000}
        before=account(bob)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            pending=pool.submit(model,bob,demo_delay_ms=1500)
            time.sleep(.6)
            check('Reprice during in-flight request',mutate('prices',prices=changed)[0]==200)
            check('In-flight model request completed',pending.result()[0]==200)
        after=balance(bob,before['balance']-cheap)
        check('In-flight request retains admission prices',after['spent']==before['spent']+cheap)
        check('Next request uses changed price',model(bob)[0]==200)
        after=balance(bob,after['balance']-300)
        check('Prior consumption not repriced',after['spent']==before['spent']+cheap+300)
        invalid=dict(changed);invalid['demo-model']={'input_price_micros':-1,'output_price_micros':1}
        check('Negative price rejected',mutate('prices',prices=invalid)[0]==400)
        check('Invalid price leaves active prices intact',v.call(v.ADMIN+'/prices')[1].get('prices',{}).get('demo-model')==changed['demo-model'])
    finally:
        code,body,_=mutate('prices',prices=prices)
        if code!=200:raise AssertionError(('Restore prices failed',body))
    for key in v.kube('exec','deploy/'+v.PREFIX,'-c','redis','--','redis-cli','--raw','KEYS','higress-token-ratelimit:{'+v.PREFIX+'}:*').splitlines():
        if key:v.kube('exec','deploy/'+v.PREFIX,'-c','redis','--','redis-cli','DEL',key)
    window='money-window-'+suffix
    assign(window,department=dept)
    check('First limited request',v.model(window,limited=True)[0]==200)
    time.sleep(.3)
    check('Second limited request',v.model(window,limited=True)[0]==200)
    time.sleep(.3)
    check('Token window remains429',v.model(window,limited=True)[0]==429)
    code,audit,_=v.call(v.ADMIN+'/audit?limit=100')
    check('Money changes audited',code==200 and any(r.get('ticket_id')==ticket for r in audit.get('records',[])))
    v.kube('exec','deploy/'+v.PREFIX,'-c','redis','--','redis-cli','CLIENT','PAUSE','3500','ALL')
    check('Redis outage fails closed503',model(bob)[0]==503)
    time.sleep(4)
    check('Redis recovered',model(bob)[0]==200)
    REPORT['passed']=True

if __name__=='__main__':
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    try:
        run()
    finally:
        REPORT['responses']=v.REPORT['responses']
        REPORT['passed']=bool(REPORT.get('passed'))
        wasm=Path(__file__).resolve().parents[3]/'higress/plugins/wasm-go/extensions/ai-quota-walmart/plugin.wasm'
        if wasm.exists():REPORT['wasm_sha256']=hashlib.sha256(wasm.read_bytes()).hexdigest()
        (EVIDENCE/'gateway-report.json').write_text(json.dumps(REPORT,ensure_ascii=False,indent=2)+'\n')
