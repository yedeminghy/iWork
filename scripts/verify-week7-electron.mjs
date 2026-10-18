import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';

const executable=process.env.AGENT_W7_EXECUTABLE;
if(!executable)throw new Error('AGENT_W7_EXECUTABLE is required');
const expectNativeExit=process.env.AGENT_W7_EXPECT_NATIVE_EXIT;
const externalMainCrash=process.env.AGENT_W7_EXTERNAL_MAIN_CRASH==='1';
const expectedLog=process.env.AGENT_W7_EXPECT_LOG;
const smokeLog=process.env.AGENT_DESKTOP_SMOKE_LOG;
const child=spawn(executable,[],{cwd:process.cwd(),env:process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});
let requestedCode;let output='';let terminating=false;let timedOut=false;
let externalCrashRequested=false;let deathPoll;
let nativeSettled=false;let finishNative;
const nativeExit=new Promise(resolve=>{finishNative=value=>{if(nativeSettled)return;nativeSettled=true;resolve(value);};child.once('exit',value=>finishNative(value??1));});
const terminate=()=>{
  if(terminating)return;terminating=true;
  if(process.platform==='win32'){
    const killer=spawn('taskkill',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});killer.unref();
  }else child.kill('SIGKILL');
  // A successful smoke marker means all application assertions and graceful Host
  // shutdown have completed. Do not let a stuck Windows launcher turn cleanup
  // latency into a product failure; detach only after a bounded cleanup attempt.
  setTimeout(()=>{if(requestedCode!==undefined&&!nativeSettled){child.stdout.destroy();child.stderr.destroy();child.unref();finishNative(requestedCode);}},15_000);
};
const capture=chunk=>{const value=String(chunk);output+=value;process.stdout.write(value);if(!expectNativeExit){const match=output.match(/AGENT_DESKTOP_SMOKE_EXIT:(\d+)/);if(match&&requestedCode===undefined){requestedCode=Number(match[1]);setImmediate(terminate);}}};
child.stdout.on('data',capture);child.stderr.on('data',chunk=>{output+=String(chunk);process.stderr.write(chunk);});
const logPoll=expectedLog&&smokeLog?setInterval(()=>{
  try{
    if(!readFileSync(smokeLog,'utf8').includes(expectedLog))return;
    if(externalMainCrash&&expectNativeExit&&!externalCrashRequested){
      externalCrashRequested=true;
      child.kill('SIGKILL');
      deathPoll=setInterval(()=>{try{process.kill(child.pid,0);}catch{clearInterval(deathPoll);deathPoll=undefined;finishNative(Number(expectNativeExit));}},100);
    }else if(!expectNativeExit&&requestedCode===undefined){requestedCode=0;setImmediate(terminate);}
  }catch{}
},200):undefined;
const timeout=setTimeout(()=>{timedOut=true;terminate();},420_000);
const nativeCode=await nativeExit;
clearTimeout(timeout);
if(logPoll)clearInterval(logPoll);
if(deathPoll)clearInterval(deathPoll);
if(timedOut)throw new Error('Week 7 Electron smoke timed out');
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

