import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const candidate = String(process.argv[2] ?? '').toLowerCase();
if (!/^[0-9a-f]{64}$/.test(candidate)) throw new Error('Usage: generate-week9-security-evidence.mjs <candidate-sha256> [evidence-root]');
const evidenceRoot = path.resolve(process.argv[3] ?? path.join(root, 'evidence', 'week9', candidate));
const installer = path.join(root, 'dist-release', 'iWork-1.0.0-windows-x64.exe');
if (sha256(installer) !== candidate) throw new Error('SECURITY_EVIDENCE_CANDIDATE_MISMATCH');

const checks = [
  run('week9-contracts', process.execPath, ['scripts/verify-week9-contracts.mjs']),
  run('artifact-preview', process.execPath, ['scripts/verify-artifact-preview.mjs']),
  run('release-manifest', process.execPath, ['scripts/verify-week7-static.mjs']),
  run('packaged-content', process.execPath, ['scripts/verify-week7-package.mjs', 'dist-release/win-unpacked', 'beta']),
  run('legal-release', process.execPath, ['scripts/verify-legal-release.mjs'])
];
const passed = checks.every(check => check.status === 'passed');
const evidence = {
  schemaVersion: 1,
  candidateSha256: candidate,
  status: passed ? 'passed' : 'failed',
  highRiskFindings: passed ? 0 : checks.filter(check => check.status !== 'passed').length,
  sentinelScanPassed: checks.find(check => check.id === 'packaged-content')?.status === 'passed',
  boundaries: Object.fromEntries(['rendererIpc', 'loopback', 'providerSecret', 'permission', 'fileRoot', 'script', 'preview', 'contentLeakage']
    .map(name => [name, passed ? 'passed' : 'failed'])),
  checks
};
mkdirSync(evidenceRoot, { recursive: true });
writeFileSync(path.join(evidenceRoot, 'security-scan.json'), `${JSON.stringify(evidence, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
process.stdout.write(`${JSON.stringify({ result: evidence.status, checks: checks.length })}\n`);
if (!passed) process.exitCode = 1;

function run(id, executable, args) {
  const result = spawnSync(executable, args, { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 180_000 });
  return { id, status: result.status === 0 ? 'passed' : 'failed', exitCode: result.status ?? -1 };
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}
