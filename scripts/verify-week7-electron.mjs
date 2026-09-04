import { spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const executable=process.env.AGENT_W7_EXECUTABLE;
if(!executable)throw new Error('AGENT_W7_EXECUTABLE is required');
const expectNativeExit=process.env.AGENT_W7_EXPECT_NATIVE_EXIT;
const expectedLog=process.env.AGENT_W7_EXPECT_LOG;
const smokeLog=process.env.AGENT_DESKTOP_SMOKE_LOG;
const child=spawn(executable,[],{cwd:process.cwd(),env:process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});
let requestedCode;let output='';let terminating=false;
const terminate=()=>{if(terminating)return;terminating=true;if(process.platform==='win32')spawnSync('taskkill',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore'});else child.kill('SIGKILL');};
const terminateAndExit=code=>{terminate();process.exit(code);};
const capture=chunk=>{const value=String(chunk);output+=value;process.stdout.write(value);if(!expectNativeExit){const match=output.match(/AGENT_DESKTOP_SMOKE_EXIT:(\d+)/);if(match&&requestedCode===undefined){requestedCode=Number(match[1]);setImmediate(()=>terminateAndExit(requestedCode));}}};
child.stdout.on('data',capture);child.stderr.on('data',chunk=>{output+=String(chunk);process.stderr.write(chunk);});
const timeout=setTimeout(()=>terminateAndExit(1),420_000);
const nativeCode=await new Promise(resolve=>child.once('exit',value=>resolve(value??1)));
clearTimeout(timeout);
if(expectNativeExit&&nativeCode!==Number(expectNativeExit))throw new Error(`Expected native exit ${expectNativeExit}, got ${nativeCode}`);
if(!expectNativeExit&&(requestedCode??nativeCode)!==0)throw new Error(`Week 7 Electron smoke failed with exit ${requestedCode??nativeCode}`);
const log=smokeLog?readFileSync(smokeLog,'utf8'):'';
if(expectedLog&&!log.includes(expectedLog))throw new Error(`Smoke log did not contain ${expectedLog}`);
for(const name of ['AGENT_W7_SECRET_SENTINEL','AGENT_W7_REASONING_SENTINEL']){
  const sentinel=process.env[name];if(sentinel&&(output.includes(sentinel)||log.includes(sentinel)))throw new Error(`${name} leaked to diagnostics`);
}
const hostPid=Number(log.match(/week7-main-crash-host-pid:(\d+)/)?.[1]);
if(expectNativeExit&&Number.isInteger(hostPid)){
  const deadline=Date.now()+10_000;let alive=true;
  while(Date.now()<deadline){try{process.kill(hostPid,0);await new Promise(resolve=>setTimeout(resolve,100));}catch{alive=false;break;}}
  if(alive)throw new Error('Host survived abnormal Electron Main exit');
}

