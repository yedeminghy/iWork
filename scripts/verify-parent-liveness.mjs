import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import { readFile } from 'node:fs/promises';

const root = path.resolve(import.meta.dirname, '..');
const stage = path.join(root, 'packaging', 'stage');
const javaExecutable = path.join(stage, 'jre', 'bin', 'java.exe');
const appHome = await mkdtemp(path.join(root, '.tmp-parent-liveness-'));
const token = 'A'.repeat(43);
const { version: appVersion } = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const configuredReadyTimeout = Number(process.env.AGENT_DESKTOP_HOST_READY_TIMEOUT_MS ?? '150000');
const readyTimeoutMs = Number.isFinite(configuredReadyTimeout)
  ? Math.max(10_000, Math.min(Math.trunc(configuredReadyTimeout), 300_000))
  : 150_000;

let child;
let stderr = '';
try {
  child = spawn(javaExecutable, [
    '-XX:TieredStopAtLevel=1',
    '-Dagentdesktop.test-fixtures=true',
    '-Dagentdesktop.builtin-skills=skills',
    '-Dagentdesktop.install-dir=.',
    `-Dagentdesktop.app-version=${appVersion}`,
    '-Dagentdesktop.db-schema-max=8',
    '-jar', 'agent-host.jar'
  ], { cwd: stage, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true, shell: false });

  let stdout = '';
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', value => { stdout += value; });
  child.stderr.on('data', value => { stderr += value; });
  child.stdin.write(`${JSON.stringify({
    type: 'bootstrap', protocolVersion: 1, sessionToken: token, appHome
  })}\n`);

  await waitFor(() => stdout.split(/\r?\n/).some(line => {
    try { return JSON.parse(line).type === 'ready'; } catch { return false; }
  }), readyTimeoutMs, 'Host did not become ready');

  child.stdin.end();
  const exit = await waitForExit(child, 10_000);
  if (exit.signal) throw new Error(`Host was signalled instead of closing cleanly: ${exit.signal}`);
  process.stdout.write(JSON.stringify({ result: 'passed', check: 'parent-pipe-eof', exitCode: exit.code }) + '\n');
} catch (error) {
  if (child && child.exitCode === null) child.kill();
  const detail = error instanceof Error ? error.message : String(error);
  throw new Error(`${detail}; host stderr tail: ${stderr.slice(-4000)}`);
} finally {
  await rm(appHome, { recursive: true, force: true });
}

async function waitFor(predicate, timeoutMs, message) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(message);
}

function waitForExit(process, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Host did not exit after its parent pipe closed')), timeoutMs);
    process.once('exit', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
  });
}
