#!/usr/bin/env python3
"""Create predictable demo users and generate actual fixture requests (30 tokens each)."""
import verify_gateway as v
v.PERIOD=v.call(v.ADMIN+'/meta')[1]['period']
for subject,tier,app in [('user-alice','L2','demo-app'),('user-bob','L1','demo-app'),('user-carol','L3','second-app')]:
    status,body,_=v.mutate('assign',dimension='user',subject=subject,tier=tier,app=app)
    assert status==200,body
    for i in range(3):
        assert v.model(subject,key='second_app' if app=='second-app' else 'app',stream=i==2)[0]==200
v.pause()
print('Seeded user-alice/user-bob/user-carol with 3 actual mock requests each; app counters include these requests.')
