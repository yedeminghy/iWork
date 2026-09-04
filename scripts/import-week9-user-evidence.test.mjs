import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const temporaryRoot = mkdtempSync(path.join(tmpdir(), 'iwork-week9-import-'));
const candidate = 'a'.repeat(64);
const evidenceRoot = path.join(temporaryRoot, candidate);
mkdirSync(evidenceRoot, { recursive: true });

try {
  writeJson(path.join(evidenceRoot, 'compatibility-automation.json'), {
    schemaVersion: 1,
    candidateSha256: candidate,
    status: 'passed',
    skillScopes: ['BUILTIN', 'USER', 'WORKSPACE', 'MANAGED_IMPORT'],
    interpreters: ['POWERSHELL', 'PYTHON', 'JAVASCRIPT'],
    uiStatus: 'passed'
  });
  const input = validInput();
  const inputFile = path.join(temporaryRoot, 'input.json');
  writeJson(inputFile, input);

  const success = run(inputFile);
  assert.equal(success.status, 0, success.stderr || success.stdout);
  for (const name of ['compatibility-matrix.json', 'install-matrix.json', 'beta-feedback-summary.json']) {
    const evidence = JSON.parse(readFileSync(path.join(evidenceRoot, name), 'utf8'));
    assert.equal(evidence.candidateSha256, candidate);
    assert.equal(evidence.status, 'passed');
  }

  input.beta.testers[0].evidenceId = 'Bearer forbidden-value';
  writeJson(inputFile, input);
  const rejected = run(inputFile);
  assert.notEqual(rejected.status, 0, 'secret-like evidence must be rejected');
  input.beta.testers[0].evidenceId = 'todo_beta_evidence';
  writeJson(inputFile, input);
  const placeholderRejected = run(inputFile);
  assert.notEqual(placeholderRejected.status, 0, 'placeholder evidence id must be rejected');
  process.stdout.write('{"result":"passed","cases":3}\n');
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}

function run(inputFile) {
  return spawnSync(process.execPath, ['scripts/import-week9-user-evidence.mjs', inputFile, candidate, evidenceRoot], {
    cwd: root,
    encoding: 'utf8',
    windowsHide: true
  });
}

function validInput() {
  const lifecycle = { freshInstall: true, firstStart: true, upgrade: true, uninstallRetention: true, reinstallRecovery: true };
  return {
    schemaVersion: 1,
    candidateSha256: candidate,
    realSamples: {
      providers: [
        { providerId: 'deepseek', status: 'passed', evidenceId: 'provider_deepseek_001' },
        { providerId: 'qwen', status: 'passed', evidenceId: 'provider_qwen_001' }
      ],
      mcp: [
        { transport: 'STDIO', status: 'passed', replayedCalls: 0, evidenceId: 'mcp_stdio_001' },
        { transport: 'STREAMABLE_HTTP', status: 'passed', replayedCalls: 0, evidenceId: 'mcp_http_001' }
      ],
      skills: { status: 'passed', evidenceId: 'skills_real_001' }
    },
    installEnvironments: [
      { environmentId: 'env_111111111111', kind: 'CLEAN_WINDOWS_X64', lifecycle, evidenceId: 'install_clean_001' },
      { environmentId: 'env_222222222222', kind: 'CLEAN_WINDOWS_X64', lifecycle, evidenceId: 'install_clean_002' },
      { environmentId: 'env_333333333333', kind: 'MANAGED_WINDOWS_X64', lifecycle, evidenceId: 'install_managed_001' }
    ],
    beta: {
      testers: Array.from({ length: 8 }, (_, index) => ({
        testerId: `tester_${String(index + 1).repeat(12)}`,
        result: 'passed',
        evidenceId: `beta_${String(index + 1).padStart(3, '0')}`
      })),
      issues: []
    }
  };
}

function writeJson(file, value) {
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}
