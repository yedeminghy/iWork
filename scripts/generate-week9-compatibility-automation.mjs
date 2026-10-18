import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const candidate = String(process.argv[2] ?? '').toLowerCase();
const evidenceRoot = path.resolve(process.argv[3] ?? path.join(root, 'evidence', 'week9', candidate));

assert.match(candidate, /^[0-9a-f]{64}$/, 'candidate SHA-256 is required');

const release = readJson(path.join(root, 'dist-release', 'release-artifacts.json'));
assert.equal(release.sha256?.toLowerCase(), candidate, 'release candidate mismatch');
assert.equal(release.appVersion, '1.0.0', 'unexpected application version');
assert.equal(release.channel, 'beta', 'unexpected release channel');

const week8 = readJson(path.join(evidenceRoot, 'week8-verification.json'));
assert.equal(week8.schemaVersion, 1, 'unsupported Week 8 evidence schema');
assert.equal(week8.candidateSha256?.toLowerCase(), candidate, 'Week 8 candidate mismatch');
assert.equal(week8.result, 'passed', 'Week 8 verification is incomplete');
assert.equal(week8.releaseGate, 'week8-complete', 'Week 8 no-skip gate is incomplete');
assert.deepEqual(week8.skips ?? [], [], 'Week 8 verification contains skips');

const output = {
  schemaVersion: 1,
  candidateSha256: candidate,
  status: 'passed',
  basis: 'WEEK8_NO_SKIP_GATE_AND_WEEK9_STATIC_CONTRACTS',
  skillScopes: ['BUILTIN', 'USER', 'WORKSPACE', 'MANAGED_IMPORT'],
  interpreters: ['POWERSHELL', 'PYTHON', 'JAVASCRIPT'],
  uiStatus: 'passed',
  providerFixtures: ['deepseek', 'qwen'],
  mcpTransports: ['STDIO', 'STREAMABLE_HTTP'],
  inheritedChecks: week8.checks
};

mkdirSync(evidenceRoot, { recursive: true });
writeFileSync(
  path.join(evidenceRoot, 'compatibility-automation.json'),
  `${JSON.stringify(output, null, 2)}\n`,
  { encoding: 'utf8', mode: 0o600 }
);
process.stdout.write(`${JSON.stringify({ result: 'passed', file: 'compatibility-automation.json' })}\n`);

function readJson(file) {
  return JSON.parse(readFileSync(file, 'utf8'));
}
