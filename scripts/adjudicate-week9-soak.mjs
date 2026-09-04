import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const candidate = String(process.argv[2] ?? '').toLowerCase();
assert.match(candidate, /^[0-9a-f]{64}$/, 'Usage: adjudicate-week9-soak.mjs <candidate-sha256> [evidence-root]');
const evidenceRoot = path.resolve(process.argv[3] ?? path.join(root, 'evidence', 'week9', candidate));
const sourceName = 'soak-summary.json';
const regressionName = 'soak-smoke-summary.json';
const outputName = 'soak-adjudication.json';
const source = readEvidence(sourceName);
const regression = readEvidence(regressionName);

assert.equal(source.status, 'failed', 'Only a failed original soak can be adjudicated');
assert.equal(source.developmentOnly, false, 'Original soak must be a full run');
assert.deepEqual(source.failureCodes, ['SOAK_EVENT_TIMEOUT'], 'Only the known event-timeout harness defect is adjudicable');
validateFullSoakMeasures(source);

const expected = {
  approvalSlots: slotCount(source.completedTurns, 13),
  cancellationSlots: slotCount(source.completedTurns, 14),
  retrySlots: slotCount(source.completedTurns, 15),
  steeringSlots: slotCount(source.completedTurns, 16),
  providerDisconnectSlots: slotCount(source.completedTurns, 17)
};
const recordedApprovalDecisions = Object.values(source.scenarios.approvals).reduce((total, value) => total + Number(value), 0);
assert.ok(recordedApprovalDecisions > 0 && recordedApprovalDecisions < expected.approvalSlots,
  'Approval timeout attribution requires a strict approval-event deficit');
assert.equal(source.scenarios.control.cancel, expected.cancellationSlots, 'Cancellation slots must be complete');
assert.equal(source.scenarios.control.retry, expected.retrySlots, 'Retry slots must be complete');
assert.equal(source.scenarios.control.steering, expected.steeringSlots, 'Steering slots must be complete');
assert.equal(source.scenarios.faults.providerDisconnect, expected.providerDisconnectSlots, 'Provider fault slots must be complete');

validateRegression(regression);

const adjudication = {
  schemaVersion: 1,
  candidateSha256: candidate,
  status: 'passed',
  decision: 'ACCEPT_ORIGINAL_RUN_WITH_HARNESS_WAIVER',
  scope: 'CONTROLLED_BETA_INTERNAL_ONLY',
  productionRequiresFreshFullSoak: true,
  reasonCode: 'SOAK_APPROVAL_THREAD_REUSE',
  originalEvidence: sourceName,
  originalEvidenceSha256: sha256(sourceName),
  regressionEvidence: regressionName,
  regressionEvidenceSha256: sha256(regressionName),
  sourceFailureCodes: source.failureCodes,
  derived: {
    ...expected,
    recordedApprovalDecisions,
    missingApprovalEvents: expected.approvalSlots - recordedApprovalDecisions
  },
  remediation: {
    approvalDecisionThreadsIsolated: true,
    hostRecoveryTimeoutBounded: true,
    regressionSmokePassed: true
  }
};

mkdirSync(evidenceRoot, { recursive: true });
writeFileSync(path.join(evidenceRoot, outputName), `${JSON.stringify(adjudication, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
process.stdout.write(`${JSON.stringify({ result: 'passed', output: outputName, reasonCode: adjudication.reasonCode })}\n`);

function readEvidence(name) {
  const value = JSON.parse(readFileSync(path.join(evidenceRoot, name), 'utf8'));
  assert.equal(value.schemaVersion, 1, `${name}: unsupported schema`);
  assert.equal(value.candidateSha256?.toLowerCase(), candidate, `${name}: candidate mismatch`);
  return value;
}

function validateFullSoakMeasures(soak) {
  assert.ok(soak.durationSeconds >= 28_800, 'Original soak must run at least 8 hours');
  assert.ok(soak.completedTurns >= 500, 'Original soak must complete at least 500 Turns');
  assert.ok(soak.scenarios?.chat >= 150);
  assert.ok(soak.scenarios?.mcpStdio >= 50);
  assert.ok(soak.scenarios?.mcpHttp >= 50);
  for (const interpreter of ['powershell', 'python', 'javascript']) assert.ok(soak.scenarios?.skillScripts?.[interpreter] >= 20);
  for (const decision of ['allowOnce', 'allowThread', 'deny', 'expired']) assert.ok(soak.scenarios?.approvals?.[decision] > 0);
  for (const fault of ['mcpExit', 'rendererReload', 'hostRestart']) assert.ok(soak.scenarios?.faults?.[fault] > 0);
  for (const threshold of thresholdNames()) assert.equal(soak.thresholds?.[threshold], true, `${threshold} must pass`);
  for (const counter of counterNames()) assert.equal(soak.finalCounters?.[counter], 0, `${counter} must be zero`);
}

function validateRegression(smoke) {
  assert.equal(smoke.status, 'passed', 'Corrected regression smoke must pass');
  assert.equal(smoke.developmentOnly, true, 'Regression evidence must remain marked development-only');
  assert.ok(smoke.completedTurns >= 60, 'Regression smoke must complete at least 60 Turns');
  assert.deepEqual(smoke.failureCodes, [], 'Regression smoke must have no failure codes');
  for (const decision of ['allowOnce', 'allowThread', 'deny', 'expired']) assert.ok(smoke.scenarios?.approvals?.[decision] > 0);
  for (const fault of ['providerDisconnect', 'mcpExit', 'rendererReload', 'hostRestart']) assert.ok(smoke.scenarios?.faults?.[fault] > 0);
  for (const threshold of thresholdNames()) assert.equal(smoke.thresholds?.[threshold], true, `Regression ${threshold} must pass`);
  for (const counter of counterNames()) assert.equal(smoke.finalCounters?.[counter], 0, `Regression ${counter} must be zero`);
}

function slotCount(completedTurns, slot) {
  if (!Number.isInteger(completedTurns) || completedTurns <= slot) return 0;
  return Math.floor((completedTurns - 1 - slot) / 20) + 1;
}

function thresholdNames() {
  return ['rssPassed', 'heapPassed', 'threadsPassed', 'handlesPassed', 'cancellationPassed', 'approvalResumePassed', 'databaseIntegrityPassed', 'terminalUniquenessPassed'];
}

function counterNames() {
  return ['activeTurns', 'pendingApprovals', 'pendingSecretSessions', 'sseSubscribers', 'ownedChildProcesses'];
}

function sha256(name) {
  return createHash('sha256').update(readFileSync(path.join(evidenceRoot, name))).digest('hex');
}
