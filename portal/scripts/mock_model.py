"""Deterministic free model fixture; never calls an external provider."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, time
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'quota mock ready')
    def do_POST(self):
        raw=b''
        if self.headers.get('Transfer-Encoding','').lower()=='chunked':
            while True:
                size=int(self.rfile.readline().split(b';')[0],16)
                if size==0:
                    self.rfile.readline();break
                raw+=self.rfile.read(size);self.rfile.read(2)
        else:raw=self.rfile.read(int(self.headers.get('Content-Length',0)))
        body=json.loads(raw or '{}')
        time.sleep(min(1.5,max(0,float(body.get('demo_delay_ms',0))/1000)))
        usage={'prompt_tokens':10,'completion_tokens':20,'total_tokens':30}
        data={'id':'mock-quota','object':'chat.completion','model':body.get('model','demo-model'),'choices':[{'index':0,'message':{'role':'assistant','content':'Local mock response'},'finish_reason':'stop'}],'usage':usage}
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream' if body.get('stream') else 'application/json')
        self.send_header('x-mock-consumer',self.headers.get('x-mse-consumer',''))
        self.send_header('x-mock-credentials-leaked',str(bool(self.headers.get('x-api-key'))).lower())
        self.end_headers()
        if body.get('stream'):
            data['object']='chat.completion.chunk'; data['choices']=[]
            self.wfile.write(('data: '+json.dumps(data)+'\n\ndata: [DONE]\n\n').encode())
        else: self.wfile.write(json.dumps(data).encode())
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
