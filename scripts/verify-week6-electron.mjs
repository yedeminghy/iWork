import { spawn, spawnSync } from 'node:child_process';

const electron=process.env.AGENT_W6_ELECTRON;if(!electron)throw new Error('AGENT_W6_ELECTRON is required');
const child=spawn(electron,['desktop-shell/dist/main.js'],{cwd:process.cwd(),env:process.env,windowsHide:true,stdio:['ignore','pipe','pipe']});
let requestedCode;let stdoutBuffer='';let terminating=false;
const terminate=()=>{if(terminating)return;terminating=true;if(process.platform==='win32')spawnSync('taskkill',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore'});else child.kill('SIGKILL');};
const terminateAndExit=code=>{terminate();process.exit(code);};
child.stdout.on('data',chunk=>{const value=String(chunk);process.stdout.write(value);stdoutBuffer+=value;const match=stdoutBuffer.match(/AGENT_DESKTOP_SMOKE_EXIT:(\d+)/);if(match&&requestedCode===undefined){requestedCode=Number(match[1]);setImmediate(()=>terminateAndExit(requestedCode));}if(stdoutBuffer.length>1024)stdoutBuffer=stdoutBuffer.slice(-1024);});
child.stderr.pipe(process.stderr);
const timeout=setTimeout(()=>terminateAndExit(1),420_000);
const code=await new Promise(resolve=>child.once('exit',value=>resolve(requestedCode??value??1)));
clearTimeout(timeout);process.exitCode=code;
