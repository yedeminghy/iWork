import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const inputArgument = process.argv[2];
const candidate = String(process.argv[3] ?? '').toLowerCase();
if (!inputArgument || !/^[0-9a-f]{64}$/.test(candidate)) throw new Error('Usage: import-week9-user-evidence.mjs <input.json> <candidate-sha256> [evidence-root]');
const inputFile = path.resolve(inputArgument);
const evidenceRoot = path.resolve(process.argv[4] ?? path.join(root, 'evidence', 'week9', candidate));
const input = JSON.parse(readFileSync(inputFile, 'utf8'));
assert.equal(input.schemaVersion, 1, 'unsupported input schema');
assert.equal(input.candidateSha256?.toLowerCase(), candidate, 'candidate mismatch');
scanSafe(input);

assert.deepEqual(input.realSamples.providers.map(item => item.providerId).sort(), ['deepseek', 'qwen']);
assert.deepEqual(input.realSamples.mcp.map(item => item.transport).sort(), ['STDIO', 'STREAMABLE_HTTP']);
for (const item of [...input.realSamples.providers, ...input.realSamples.mcp]) {
  assert.equal(item.status, 'passed', `${item.evidenceId}: result must be passed`);
  validateEvidenceId(item.evidenceId, 'invalid evidence id');
}
for (const item of input.realSamples.mcp) assert.equal(item.replayedCalls, 0, `${item.transport}: replayed calls must be zero`);
assert.equal(input.realSamples.skills.status, 'passed', 'real Skill validation must pass');
validateEvidenceId(input.realSamples.skills.evidenceId, 'invalid Skill evidence id');

assert.ok(Array.isArray(input.installEnvironments));
assert.ok(input.installEnvironments.filter(item => item.kind === 'CLEAN_WINDOWS_X64').length >= 2, 'two clean Windows environments are required');
assert.ok(input.installEnvironments.some(item => item.kind === 'MANAGED_WINDOWS_X64'), 'one managed Windows environment is required');
assert.equal(new Set(input.installEnvironments.map(item => item.environmentId)).size, input.installEnvironments.length, 'environment ids must be unique');
for (const environment of input.installEnvironments) {
  assert.match(environment.environmentId, /^env_[0-9a-f]{12,64}$/);
  validateEvidenceId(environment.evidenceId, `${environment.environmentId}: invalid evidence id`);
  assert.ok(['CLEAN_WINDOWS_X64', 'MANAGED_WINDOWS_X64'].includes(environment.kind));
  for (const step of ['freshInstall', 'firstStart', 'upgrade', 'uninstallRetention', 'reinstallRecovery']) {
    assert.equal(environment.lifecycle?.[step], true, `${environment.environmentId}: ${step} is incomplete`);
  }
}

assert.ok(Array.isArray(input.beta.testers) && input.beta.testers.length >= 8 && input.beta.testers.length <= 15, '8..15 testers are required');
assert.equal(new Set(input.beta.testers.map(item => item.testerId)).size, input.beta.testers.length, 'tester ids must be unique');
for (const tester of input.beta.testers) {
  assert.match(tester.testerId, /^tester_[0-9a-f]{12,64}$/);
  assert.equal(tester.result, 'passed', `${tester.testerId}: beta script is incomplete`);
  validateEvidenceId(tester.evidenceId, `${tester.testerId}: invalid evidence id`);
}
const issues = input.beta.issues ?? [];
for (const issue of issues) {
  assert.ok(['P0', 'P1', 'P2', 'P3'].includes(issue.severity), `${issue.issueId}: invalid severity`);
  assert.notEqual(issue.status, 'open', `${issue.issueId}: unresolved issue`);
  if (issue.severity === 'P2' || issue.severity === 'P3') {
    assert.match(issue.ownerId ?? '', /^[a-z][a-z0-9_-]{2,63}$/i, `${issue.issueId}: owner is required`);
    assert.match(issue.targetVersion ?? '', /^[0-9][0-9A-Za-z._-]{0,31}$/, `${issue.issueId}: target version is required`);
  }
}
assert.equal(issues.filter(item => item.severity === 'P0').length, 0, 'P0 must be zero');
assert.equal(issues.filter(item => item.severity === 'P1').length, 0, 'P1 must be zero');

const automation = JSON.parse(readFileSync(path.join(evidenceRoot, 'compatibility-automation.json'), 'utf8'));
assert.equal(automation.schemaVersion, 1);
assert.equal(automation.candidateSha256?.toLowerCase(), candidate);
assert.equal(automation.status, 'passed', 'compatibility automation is incomplete');

mkdirSync(evidenceRoot, { recursive: true });
write('compatibility-matrix.json', {
  status: 'passed',
  providers: input.realSamples.providers.map(item => ({ providerId: item.providerId, fixtureStatus: 'passed', realSampleStatus: 'passed', evidenceId: item.evidenceId })),
  mcp: input.realSamples.mcp.map(item => ({ transport: item.transport, fixtureStatus: 'passed', realSampleStatus: 'passed', replayedCalls: 0, evidenceId: item.evidenceId })),
  skillScopes: automation.skillScopes,
  interpreters: automation.interpreters,
  uiStatus: automation.uiStatus
});
write('install-matrix.json', { status: 'passed', skipped: false, environments: input.installEnvironments });
write('beta-feedback-summary.json', {
  status: 'passed', testerCount: input.beta.testers.length,
  severityCounts: Object.fromEntries(['P0', 'P1', 'P2', 'P3'].map(severity => [severity, issues.filter(item => item.severity === severity).length])),
  lowerSeverityDecisionsComplete: true,
  evidenceIds: input.beta.testers.map(item => item.evidenceId)
});
process.stdout.write(`${JSON.stringify({ result: 'passed', files: ['compatibility-matrix.json', 'install-matrix.json', 'beta-feedback-summary.json'] })}\n`);

function write(name, value) {
  writeFileSync(path.join(evidenceRoot, name), `${JSON.stringify({ schemaVersion: 1, candidateSha256: candidate, ...value }, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
}

function validateEvidenceId(value, message) {
  assert.match(value ?? '', /^[a-z][a-z0-9_-]{5,63}$/i, message);
  assert.doesNotMatch(value, /^(?:todo|pending|placeholder|example)(?:_|$)/i, `${message}: placeholder is not allowed`);
}

function scanSafe(value) {
  const visit = (item, key = '') => {
    if (Array.isArray(item)) return item.forEach(entry => visit(entry, key));
    if (item && typeof item === 'object') return Object.entries(item).forEach(([childKey, child]) => {
      assert.ok(!/^(api.?key|authorization|cookie|secret|reasoning|thinking|prompt|body|content|absolute.?path|user.?name|host.?name|sid)$/i.test(childKey), `forbidden field ${childKey}`);
      visit(child, childKey);
    });
    if (typeof item === 'string') {
      assert.ok(!/Bearer\s+|(?:^|[^A-Za-z])sk-[A-Za-z0-9_-]{12,}/i.test(item), `secret-like value in ${key}`);
      assert.ok(!/(?:[A-Za-z]:\\|\\\\[^\\]+\\|\/Users\/|\/home\/)/.test(item), `private path in ${key}`);
    }
  };
  visit(value);
}
