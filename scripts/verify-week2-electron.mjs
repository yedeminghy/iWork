import { spawn } from 'node:child_process';

const electron = process.env.AGENT_W2_ELECTRON;
if (!electron) throw new Error('AGENT_W2_ELECTRON is required');
const child = spawn(electron, ['desktop-shell/dist/main.js'], { cwd: process.cwd(), env: process.env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
child.stdout.pipe(process.stdout); child.stderr.pipe(process.stderr);
const timeout = setTimeout(() => child.kill(), 60_000);
const code = await new Promise(resolve => child.once('exit', value => resolve(value ?? 1)));
clearTimeout(timeout);
process.exitCode = code;
