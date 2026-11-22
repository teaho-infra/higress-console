#!/usr/bin/env python3
"""Demo CNY budgets and real mock consumption; never hits paid models."""
import verify_gateway as v
v.PERIOD=v.call(v.ADMIN+'/meta')[1]['period']
for dept,tier in [('研发部','L2'),('产品部','L3')]:
    code,body,_=v.mutate('assign',dimension='department',subject=dept,tier=tier)
    assert code==200,body
for subject,tier,department,app in [('user-alice','L1','研发部','demo-app'),('user-bob','inherit','研发部','demo-app'),('user-carol','inherit','产品部','second-app')]:
    code,body,_=v.mutate('assign',dimension='user',subject=subject,tier=tier,department=department,app=app)
    assert code==200,body
    for model in ['demo-model','demo-model-pro']:
        code,body,_=v.call(v.BASE,'POST',{'model':model,'messages':[{'role':'user','content':'money demo'}]},key='second_app' if app=='second-app' else 'app',user=subject)
        assert code==200,body
print('Seeded two department templates and three independently billed users using mock model requests.')
