// HTTP contract fixture: exercises the real Java server, not a JavaScript backend.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const jar=fileURLToPath(new URL('../../backend/walmart-quota/target/walmart-quota-0.0.1-SNAPSHOT-standalone.jar',import.meta.url));
export function createPortal(config) {
  if (!config.password || config.password.length < 12 || !config.adminKey) throw new Error('Invalid test configuration');
  let process, port;
  return {
    listen(_port,host,ready) {
      process=spawn('java',['-jar',jar,'--server.port=0',`--server.address=${host}`,`--spring.web.resources.static-locations=file:${config.staticDir}/`],{
        env:{...globalThis.process.env,PORTAL_PASSWORD:config.password,GATEWAY_ADMIN_KEY:config.adminKey,GATEWAY_URL:config.gatewayUrl,GATEWAY_HOST:config.gatewayHost},stdio:['ignore','pipe','pipe']
      });
      let log=''; const timeout=setTimeout(()=>{process.kill();throw new Error('Java test server startup timed out');},45000);
      process.stdout.on('data',chunk=>{log+=chunk;const match=log.match(/Tomcat started on port\(s\): (\d+)/);if(match&&!port){port=Number(match[1]);clearTimeout(timeout);ready();}});
      process.once('exit',code=>{clearTimeout(timeout);if(!port)throw new Error(`Java server failed to start (${code}): ${log.slice(-1500)}`);});
    },
    address(){return {port};},
    close(done){process.once('exit',done);process.kill();}
  };
}
