import { app, BrowserWindow } from 'electron';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { HostSupervisor } from '../desktop-shell/dist/host-supervisor.js';

const root = path.resolve(import.meta.dirname, '..');
const emergencyLog = path.join(root, 'dist-release', 'week9-soak-error.log');
process.on('uncaughtException', error => {
  try { writeFileSync(emergencyLog, `${stableError(error)}\n`, { encoding: 'utf8', mode: 0o600 }); } catch { /* last-resort only */ }
});
process.on('unhandledRejection', error => {
  try { writeFileSync(emergencyLog, `${stableError(error)}\n`, { encoding: 'utf8', mode: 0o600 }); } catch { /* last-resort only */ }
});
const args = Object.fromEntries(process.argv.slice(1).filter(value => /^(?:--?)?[a-z][a-z0-9-]*=/i.test(value)).map(value => {
  const index = value.indexOf('=');
  const key = value.slice(0, index).replace(/^--?/, '');
  return [key, value.slice(index + 1)];
}));
const candidateSha256 = String(args.candidate ?? process.env.AGENT_WEEK9_CANDIDATE ?? '').toLowerCase();
const durationSeconds = positiveInteger(args['duration-seconds'] ?? process.env.AGENT_WEEK9_DURATION_SECONDS ?? 28_800, 'duration-seconds');
const minimumTurns = positiveInteger(args['min-turns'] ?? process.env.AGENT_WEEK9_MIN_TURNS ?? 500, 'min-turns');
const developmentSmoke = String(args['development-smoke'] ?? process.env.AGENT_WEEK9_DEVELOPMENT_SMOKE ?? 'false').toLowerCase() === 'true';
const output = safeOutput(args.output ?? process.env.AGENT_WEEK9_OUTPUT);
const nodeExecutable = executable(args.node ?? process.env.AGENT_DESKTOP_NODE, 'node');
if (!/^[0-9a-f]{64}$/.test(candidateSha256)) throw new Error('--candidate must be a SHA-256 hex digest');

const resources = path.join(root, 'dist-release', 'win-unpacked', 'resources');
const javaExecutable = path.join(resources, 'jre', 'bin', 'java.exe');
const hostJar = path.join(resources, 'agent-host.jar');
const builtinSkills = path.join(resources, 'skills');
const fixtureScript = path.join(root, 'scripts', 'mcp-week3-fixture.mjs');
const installer = path.join(root, 'dist-release', 'iWork-1.0.0-windows-x64.exe');
for (const required of [javaExecutable, hostJar, builtinSkills, fixtureScript]) {
  if (!existsSync(required)) throw new Error('Packaged soak prerequisite is missing');
}
if (!existsSync(installer) || createHash('sha256').update(readFileSync(installer)).digest('hex') !== candidateSha256) {
  throw new Error('SOAK_CANDIDATE_MISMATCH');
}

process.env.AGENT_DESKTOP_TEST_FIXTURES = '1';
process.env.AGENT_DESKTOP_W7_SMOKE = '1';
// Acceptance runs may execute under real-time scanning where a cold Spring/Flyway
// recovery exceeds the desktop default. Keep the recovery bounded but aligned
// with the already-supported Host timeout override.
process.env.AGENT_DESKTOP_HOST_READY_TIMEOUT_MS ||= '120000';
process.env.AGENT_DESKTOP_NODE = nodeExecutable;
process.env.AGENT_DESKTOP_PYTHON = executable(args.python ?? process.env.AGENT_DESKTOP_PYTHON, 'python');

const state = {
  startedAt: Date.now(), completedTurns: 0,
  scenarios: {
    chat: 0, mcpStdio: 0, mcpHttp: 0,
    skillScripts: { powershell: 0, python: 0, javascript: 0 },
    approvals: { allowOnce: 0, allowThread: 0, deny: 0, expired: 0 },
    control: { cancel: 0, retry: 0, steering: 0 },
    faults: { providerDisconnect: 0, mcpExit: 0, rendererReload: 0, hostRestart: 0 }
  },
  cancellationLatencyMs: [], approvalResumeLatencyMs: [], samples: [], failures: []
};

let supervisor;
let httpFixture;
let window;
let appHome;

app.disableHardwareAcceleration();
app.whenReady().then(run).then(code => app.exit(code)).catch(async error => {
  state.failures.push(stableError(error));
  await cleanup();
  try { writeSummary(false); } catch { /* preserve original failure */ }
  process.stderr.write(`${JSON.stringify({ result: 'failed', code: stableError(error) })}\n`);
  app.exit(1);
});

async function run() {
  appHome = mkdtempSync(path.join(os.tmpdir(), 'iwork-week9-soak-'));
  const workspace = path.join(appHome, 'workspace');
  createWorkspaceSkill(workspace);
  window = new BrowserWindow({ show: false, webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } });
  await window.loadURL('data:text/html;charset=utf-8,<title>iWork Week 9 Soak</title>');

  httpFixture = await startHttpFixture();
  supervisor = new HostSupervisor((source, level, message) => {
    if (level === 'ERROR') {
      const code = stableCode(message);
      if (code !== 'PROVIDER_STREAM_BROKEN') state.failures.push(`${source}:${code}`);
    }
  });
  await supervisor.start(javaExecutable, hostJar, appHome, builtinSkills, resources,
    path.join(root, 'desktop-shell', 'dist', 'runtime'), '1.0.0', 8, workspace);
  const mcp = await configureMcp(httpFixture.endpoint, httpFixture.token, workspace);
  const scripts = await locateWorkspaceScripts();
  const threads = Array.from({ length: 4 }, () => id('thr'));
  const targetIntervalMs = Math.max(250, Math.floor(durationSeconds * 1000 / minimumTurns));
  let nextTurnAt = Date.now();
  let nextSampleAt = Date.now();
  let injectedHostRestart = false;
  let injectedRendererReload = false;
  let injectedMcpExit = false;
  let injectedExpiredApproval = false;

  while (Date.now() - state.startedAt < durationSeconds * 1000 || state.completedTurns < minimumTurns) {
    if (Date.now() < nextTurnAt) await delay(Math.min(1_000, nextTurnAt - Date.now()));
    if (Date.now() >= nextSampleAt) {
      await sample();
      nextSampleAt = Date.now() + Math.min(300_000, Math.max(5_000, durationSeconds * 100));
    }
    const index = state.completedTurns;
    const slot = index % 20;
    const threadId = threads[index % threads.length];
    try {
      if (slot <= 5 || slot === 19) await chat(threadId, index);
      else if (slot <= 7) await mcpCall(threadId, `mcp:${mcp.stdio.id}:echo`, { text: `soak-${index}` }, 'mcpStdio');
      else if (slot <= 9) await mcpCall(threadId, `mcp:${mcp.http.id}:sum`, { a: index, b: 1 }, 'mcpHttp');
      else if (slot <= 12) await scriptCall(threadId, scripts[slot - 10], index);
      else if (slot === 13) {
        const decisions = ['ALLOW_ONCE', 'ALLOW_THREAD', 'DENY'];
        if (!injectedExpiredApproval && index >= 20) {
          injectedExpiredApproval = true;
          await approvalCall(threadId, mcp.stdio.id, 'EXPIRED');
        } else await approvalCall(threadId, mcp.stdio.id, decisions[index % decisions.length]);
      } else if (slot === 14) await cancelTurn(threadId);
      else if (slot === 15) await retryTurn(threadId);
      else if (slot === 16) await steerTurn(threadId);
      else if (slot === 17) await providerDisconnect(threadId);
      else if (!injectedMcpExit) {
        injectedMcpExit = true; await mcpExitFault(threadId, mcp.stdio); state.scenarios.faults.mcpExit++;
      } else if (!injectedRendererReload) {
        injectedRendererReload = true; await reloadRenderer(); state.scenarios.faults.rendererReload++;
      } else if (!injectedHostRestart) {
        injectedHostRestart = true; await restartHost(); state.scenarios.faults.hostRestart++;
      } else await chat(threadId, index);
    } catch (error) {
      state.failures.push(stableError(error));
    }
    state.completedTurns++;
    nextTurnAt += targetIntervalMs;
  }

  await sample();
  await cleanup();
  const passed = writeSummary(true);
  process.stdout.write(`${JSON.stringify({ result: passed ? 'passed' : 'failed', output, completedTurns: state.completedTurns })}\n`);
  return passed ? 0 : 1;
}

async function cleanup() {
  if (supervisor) await supervisor.stop().catch(() => undefined);
  if (httpFixture) await httpFixture.stop().catch(() => undefined);
  if (window && !window.isDestroyed()) window.destroy();
  if (appHome) rmSync(appHome, { recursive: true, force: true });
}

async function configureMcp(endpoint, token, workspace) {
  const stdio = await supervisor.saveMcpServer({ name: 'Week 9 STDIO soak', transport: 'STDIO', enabled: true,
    executable: nodeExecutable, arguments: [fixtureScript, 'stdio'], workingDirectory: workspace, allowedEnvironmentNames: [] });
  const http = await supervisor.saveMcpServer({ name: 'Week 9 HTTP soak', transport: 'STREAMABLE_HTTP', enabled: true, endpoint });
  await supervisor.connectMcpServer(stdio.id);
  await supervisor.connectMcpServer(http.id, token);
  return { stdio, http };
}

async function locateWorkspaceScripts() {
  await supervisor.refreshSkills('WORKSPACE');
  const skill = (await supervisor.listSkills('WORKSPACE')).items.find(item => item.name === 'week9-soak-scripts');
  if (!skill) throw new Error('SOAK_SKILL_MISSING');
  const resources = await supervisor.listSkillResources(skill.id);
  return ['powershell', 'python', 'javascript'].map((interpreter, index) => {
    const extension = ['.ps1', '.py', '.js'][index];
    const resource = resources.find(item => item.relativePath.endsWith(extension));
    if (!resource) throw new Error('SOAK_SCRIPT_RESOURCE_MISSING');
    return { interpreter, skillId: skill.id, resourceId: resource.id };
  });
}

async function chat(threadId, index) {
  await runTurn(`soak chat ${index}`, threadId);
  state.scenarios.chat++;
}

async function mcpCall(threadId, toolId, argumentsValue, counter) {
  await runTurn(`__week3_tool__|${toolId}|call|${JSON.stringify(argumentsValue)}`, threadId);
  state.scenarios[counter]++;
}

async function scriptCall(threadId, script, index) {
  const decision = index % 3 === 0 ? 'DENY' : index % 3 === 1 ? 'ALLOW_ONCE' : 'ALLOW_THREAD';
  const turn = startTurn(`__week6_tool__|skill.run_script|run|${JSON.stringify({ skillId: script.skillId, resourceId: script.resourceId, workingRootId: 'root_default', arguments: [] })}`, threadId, [script.skillId]);
  await resolveIfRequested(turn, decision);
  await turn.finished;
  state.scenarios.skillScripts[script.interpreter]++;
}

async function approvalCall(_threadId, serverId, decision) {
  // Keep explicit approval decisions isolated. Reusing one of the four load-test
  // threads after ALLOW_THREAD would correctly auto-authorize the same request,
  // so no approval.required event would be emitted for the harness to resolve.
  const approvalThreadId = id('thr');
  const turn = startTurn(`__week6_tool__|mcp:${serverId}:danger|danger|{}`, approvalThreadId);
  const event = await waitForEvent(turn.events, 'CUSTOM approval.required', 10_000);
  if (decision === 'EXPIRED') {
    await turn.finished;
    state.scenarios.approvals.expired++;
    return;
  }
  const started = Date.now();
  await supervisor.resolveApproval(String(event.value.approvalId), decision, String(event.value.requestFingerprint));
  await turn.finished;
  state.approvalResumeLatencyMs.push(Date.now() - started);
  state.scenarios.approvals[decision === 'ALLOW_ONCE' ? 'allowOnce' : decision === 'ALLOW_THREAD' ? 'allowThread' : 'deny']++;
}

async function cancelTurn(threadId) {
  const turn = startTurn('x'.repeat(2_000), threadId);
  await waitForEvent(turn.events, 'RUN_STARTED', 10_000);
  const started = Date.now();
  await supervisor.cancel(turn.turnId);
  await turn.finished.catch(() => undefined);
  state.cancellationLatencyMs.push(Date.now() - started);
  state.scenarios.control.cancel++;
}

async function retryTurn(threadId) {
  const failed = startTurn('__week9_provider_stream_broken__', threadId);
  await failed.finished.catch(() => undefined);
  const retry = await supervisor.prepareRetry(failed.turnId);
  await runTurn(retry.message, retry.threadId, [], failed.turnId);
  state.scenarios.control.retry++;
}

async function steerTurn(threadId) {
  const turn = startTurn('y'.repeat(1_000), threadId);
  await waitForEvent(turn.events, 'RUN_STARTED', 10_000);
  await supervisor.steer(turn.turnId, 'continue safely');
  await turn.finished;
  state.scenarios.control.steering++;
}

async function providerDisconnect(threadId) {
  await runTurn('__week9_provider_stream_broken__', threadId).catch(() => undefined);
  state.scenarios.faults.providerDisconnect++;
}

async function mcpExitFault(threadId, server) {
  await mcpCall(threadId, `mcp:${server.id}:echo`, { text: '__exit__' }, 'mcpStdio').catch(() => undefined);
  await delay(500);
  await supervisor.connectMcpServer(server.id);
}

async function reloadRenderer() {
  const loaded = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('RENDERER_RELOAD_TIMEOUT')), 10_000);
    window.webContents.once('did-finish-load', () => { clearTimeout(timeout); resolve(); });
  });
  window.reload();
  await loaded;
}

async function restartHost() {
  await supervisor.crashForWeek7Smoke();
  const deadline = Date.now() + acceptanceHostRecoveryTimeoutMs();
  while (Date.now() < deadline && supervisor.state.status !== 'ready') {
    if (supervisor.state.status === 'crash-loop' || supervisor.state.status === 'failed') break;
    await delay(100);
  }
  if (supervisor.state.status !== 'ready') throw new Error('HOST_RESTART_TIMEOUT');
}

function startTurn(message, threadId, explicitSkillIds = [], retryOfTurnId) {
  const turnId = id('trn');
  const events = [];
  const input = { threadId, runId: id('run'), messages: [{ id: id('msg'), role: 'user', content: message }],
    context: { turnId, workspaceId: 'ws_default', modelProfileId: 'mock', maxIterations: 10 }, explicitSkillIds, retryOfTurnId };
  return { turnId, threadId, events, finished: supervisor.run(input, event => events.push(event)) };
}

async function runTurn(message, threadId, explicitSkillIds = [], retryOfTurnId) {
  const turn = startTurn(message, threadId, explicitSkillIds, retryOfTurnId);
  await resolveIfRequested(turn, 'ALLOW_ONCE');
  await turn.finished;
  return turn;
}

async function resolveIfRequested(turn, decision) {
  const event = await waitForOptionalEvent(turn.events, 'CUSTOM approval.required', 2_000, turn.finished);
  if (!event) return;
  const started = Date.now();
  await supervisor.resolveApproval(String(event.value.approvalId), decision, String(event.value.requestFingerprint));
  state.approvalResumeLatencyMs.push(Date.now() - started);
  state.scenarios.approvals[decision === 'ALLOW_ONCE' ? 'allowOnce' : decision === 'ALLOW_THREAD' ? 'allowThread' : 'deny']++;
}

async function sample() {
  const diagnostic = await supervisor.previewDiagnostics();
  const host = processMetrics(Number(diagnostic.process.processId));
  const main = await process.getProcessMemoryInfo();
  const rendererPid = window && !window.isDestroyed() ? window.webContents.getOSProcessId() : 0;
  const rendererMetric = rendererPid > 0 ? app.getAppMetrics().find(metric => metric.pid === rendererPid) : undefined;
  const rendererRssKb = Number(rendererMetric?.memory?.workingSetSize ?? 0);
  state.samples.push({
    elapsedSeconds: Math.floor((Date.now() - state.startedAt) / 1000),
    rssBytes: (main.residentSet + rendererRssKb) * 1024 + host.rssBytes,
    heapUsedBytes: Number(diagnostic.process.heapUsedBytes),
    liveThreads: Number(diagnostic.process.liveThreads) + host.threads,
    handles: host.handles,
    activeTurns: Number(diagnostic.process.activeTurns), pendingApprovals: Number(diagnostic.process.pendingApprovals),
    pendingSecretSessions: Number(diagnostic.process.pendingSecretSessions), sseSubscribers: Number(diagnostic.process.sseSubscribers),
    ownedChildProcesses: Number(diagnostic.process.ownedChildProcesses), databaseIntegrity: String(diagnostic.database.integrity)
  });
}

function writeSummary(completed) {
  const duration = Math.floor((Date.now() - state.startedAt) / 1000);
  const first = state.samples[Math.min(state.samples.length - 1, Math.max(0, Math.floor(state.samples.length * 0.1)))];
  const last = state.samples.at(-1);
  const rssPassed = Boolean(first && last && last.rssBytes <= Math.max(first.rssBytes * 1.3, first.rssBytes + 384 * 1024 * 1024));
  const heapPassed = Boolean(first && last && last.heapUsedBytes <= Math.max(first.heapUsedBytes * 1.2, first.heapUsedBytes + 128 * 1024 * 1024));
  const threadsPassed = Boolean(first && last && last.liveThreads <= first.liveThreads + 24);
  const handlesPassed = Boolean(first && last && last.handles <= first.handles + 64);
  const finalCounters = last ? Object.fromEntries(['activeTurns', 'pendingApprovals', 'pendingSecretSessions', 'sseSubscribers', 'ownedChildProcesses'].map(key => [key, last[key]])) : {};
  const snapshotsPassed = completed ? terminalStateCheck() : false;
  const thresholds = {
    rssPassed, heapPassed, threadsPassed, handlesPassed,
    cancellationPassed: percentile(state.cancellationLatencyMs, 95) <= 2_000,
    approvalResumePassed: percentile(state.approvalResumeLatencyMs, 95) <= 2_000,
    databaseIntegrityPassed: Boolean(last && last.databaseIntegrity === 'ok'),
    terminalUniquenessPassed: snapshotsPassed
  };
  const countsPassed = developmentSmoke
    ? state.scenarios.chat > 0 && state.scenarios.mcpStdio > 0 && state.scenarios.mcpHttp > 0 &&
      Object.values(state.scenarios.skillScripts).every(value => value > 0) && Object.values(state.scenarios.approvals).every(value => value > 0) &&
      Object.values(state.scenarios.control).every(value => value > 0) && Object.values(state.scenarios.faults).every(value => value > 0)
    : state.scenarios.chat >= 150 && state.scenarios.mcpStdio >= 50 && state.scenarios.mcpHttp >= 50 &&
      Object.values(state.scenarios.skillScripts).every(value => value >= 20) && Object.values(state.scenarios.approvals).every(value => value > 0) &&
      Object.values(state.scenarios.control).every(value => value > 0) && Object.values(state.scenarios.faults).every(value => value > 0);
  const countersPassed = Object.keys(finalCounters).length === 5 && Object.values(finalCounters).every(value => value === 0);
  const fullRun = duration >= 28_800 && state.completedTurns >= 500;
  const passed = completed && (fullRun || developmentSmoke) && countsPassed && countersPassed && Object.values(thresholds).every(Boolean) && state.failures.length === 0;
  const summary = { schemaVersion: 1, candidateSha256, status: passed ? 'passed' : 'failed', developmentOnly: !fullRun,
    durationSeconds: duration, completedTurns: state.completedTurns, scenarios: state.scenarios, thresholds, finalCounters,
    sampleCount: state.samples.length, failureCodes: [...new Set(state.failures)].slice(0, 50) };
  mkdirSync(path.dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(summary, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  return passed;
}

function terminalStateCheck() {
  // Detailed item/terminal uniqueness is validated by the existing persistence regression.
  // The soak additionally requires the final diagnostics to report no active work.
  const last = state.samples.at(-1);
  return Boolean(last && last.activeTurns === 0 && last.pendingApprovals === 0 && last.sseSubscribers === 0);
}

function createWorkspaceSkill(workspace) {
  const skill = path.join(workspace, '.agent-desktop', 'skills', 'week9-soak-scripts');
  const scripts = path.join(skill, 'scripts');
  mkdirSync(scripts, { recursive: true });
  writeFileSync(path.join(skill, 'SKILL.md'), '---\nname: week9-soak-scripts\ndescription: Execute deterministic Week 9 stability scripts.\n---\nUse only the fixed scripts in this Skill during the Week 9 soak.\n');
  writeFileSync(path.join(scripts, 'soak.ps1'), "Write-Output 'SOAK_PS_OK'\n");
  writeFileSync(path.join(scripts, 'soak.py'), "print('SOAK_PY_OK')\n");
  writeFileSync(path.join(scripts, 'soak.js'), "process.stdout.write('SOAK_JS_OK\\n');\n");
}

async function startHttpFixture() {
  const token = `w9-${randomBytes(16).toString('hex')}`;
  const child = spawn(nodeExecutable, [fixtureScript, 'http'], { cwd: root, windowsHide: true,
    env: { ...process.env, AGENT_W3_FIXTURE_TOKEN: token }, stdio: ['ignore', 'pipe', 'pipe'] });
  const lines = readline.createInterface({ input: child.stdout });
  const ready = await Promise.race([
    new Promise((resolve, reject) => lines.once('line', line => { try { resolve(JSON.parse(line)); } catch { reject(new Error('HTTP_FIXTURE_INVALID')); } })),
    delay(10_000).then(() => { throw new Error('HTTP_FIXTURE_TIMEOUT'); })
  ]);
  lines.close();
  return { token, endpoint: `http://127.0.0.1:${ready.port}/mcp`, stop: async () => {
    if (child.exitCode === null) child.kill();
    await Promise.race([new Promise(resolve => child.once('exit', resolve)), delay(2_000)]);
  } };
}

function processMetrics(pid) {
  if (!Number.isInteger(pid) || pid < 1) return { rssBytes: 0, threads: 0, handles: 0 };
  const command = `Get-Process -Id ${pid} | Select-Object WorkingSet64,@{n='Threads';e={$_.Threads.Count}},HandleCount | ConvertTo-Json -Compress`;
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', command], { encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) return { rssBytes: 0, threads: 0, handles: 0 };
  const value = JSON.parse(result.stdout);
  return { rssBytes: Number(value.WorkingSet64 ?? 0), threads: Number(value.Threads ?? 0), handles: Number(value.HandleCount ?? 0) };
}

function safeOutput(value) {
  const base = path.join(root, 'evidence', 'week9');
  const resolved = path.resolve(String(value ?? path.join(base, candidateSha256 || 'pending', 'soak-summary.json')));
  const relative = path.relative(base, resolved);
  if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('--output must be inside evidence/week9');
  return resolved;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) throw new Error(`--${name} must be a positive integer`);
  return parsed;
}

function acceptanceHostRecoveryTimeoutMs() {
  const configured = Number(process.env.AGENT_DESKTOP_HOST_READY_TIMEOUT_MS ?? '120000');
  const bounded = Number.isFinite(configured) ? Math.max(30_000, Math.min(Math.trunc(configured), 180_000)) : 120_000;
  return bounded + 10_000;
}

function executable(value, command) {
  if (value && existsSync(path.resolve(String(value)))) return path.resolve(String(value));
  const located = spawnSync('where.exe', [command], { encoding: 'utf8', windowsHide: true });
  const first = located.status === 0 ? located.stdout.split(/\r?\n/).find(Boolean) : undefined;
  if (!first || !existsSync(first)) throw new Error(`SOAK_${command.toUpperCase()}_MISSING`);
  return path.resolve(first);
}

async function waitForEvent(events, type, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const event = events.find(item => item.type === type);
    if (event) return event;
    await delay(25);
  }
  throw new Error('SOAK_EVENT_TIMEOUT');
}

async function waitForOptionalEvent(events, type, timeoutMs, finished) {
  const deadline = Date.now() + timeoutMs;
  let done = false;
  void finished.then(() => { done = true; }, () => { done = true; });
  while (Date.now() < deadline && !done) {
    const event = events.find(item => item.type === type);
    if (event) return event;
    await delay(25);
  }
  return events.find(item => item.type === type);
}

function percentile(values, percentileValue) {
  if (values.length === 0) return Number.POSITIVE_INFINITY;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * percentileValue / 100) - 1)];
}

function stableError(error) {
  const value = String(error?.message ?? error)
    .replace(/Bearer\s+[A-Za-z0-9._-]+/gi, 'Bearer_[REDACTED]')
    .replace(/(?:[A-Za-z]:\\|\\\\)[^\s]*/g, '[PRIVATE_PATH]')
    .replace(/[^A-Za-z0-9:_ -]+/g, '_')
    .trim();
  return value.slice(0, 160) || 'SOAK_FAILURE';
}
function stableCode(value) {
  const upper = String(value).toUpperCase();
  const explicit = upper.match(/(?:CODE|ERRORCODE)\s*[=:]\s*([A-Z][A-Z0-9_]{2,63})/);
  if (explicit) return explicit[1];
  const symbolic = upper.match(/\b([A-Z]+(?:_[A-Z0-9]+)+)\b/);
  return symbolic?.[1] ?? 'SOAK_FAILURE';
}
function id(prefix) { return `${prefix}_${randomUUID().replaceAll('-', '')}`; }
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
