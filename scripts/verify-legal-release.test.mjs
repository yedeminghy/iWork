import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const temporary = mkdtempSync(path.join(os.tmpdir(), 'iwork-legal-release-'));
const verifier = path.resolve(import.meta.dirname, 'verify-legal-release.mjs');
const filler = '本条款用于描述软件许可、数据处理、安全措施和用户权利。'.repeat(30);
const utf8Bom = Buffer.from([0xef, 0xbb, 0xbf]);
const eula = `iWork 最终用户许可协议\n杭州烛光鱼有限公司\n${filler}`;

try {
  writeFileSync(path.join(temporary, 'EULA_zh-CN.txt'), Buffer.concat([utf8Bom, Buffer.from(eula, 'utf8')]));
  writeFileSync(path.join(temporary, 'PRIVACY_zh-CN.md'), `# iWork 隐私政策\n杭州烛光鱼有限公司\n${filler}`, 'utf8');
  assert.equal(run().status, 0, 'approved legal fixtures should pass');

  writeFileSync(path.join(temporary, 'EULA_zh-CN.txt'), eula, 'utf8');
  const missingBom = run();
  assert.notEqual(missingBom.status, 0, 'EULA without a UTF-8 BOM should fail');
  assert.match(missingBom.stderr, /UTF8_BOM_REQUIRED/);
  writeFileSync(path.join(temporary, 'EULA_zh-CN.txt'), Buffer.concat([utf8Bom, Buffer.from(eula, 'utf8')]));

  writeFileSync(path.join(temporary, 'PRIVACY_zh-CN.md'), `# 法务审阅草案 iWork 隐私政策\n杭州烛光鱼有限公司\n@@PRIVACY_CONTACT_EMAIL@@\n${filler}`, 'utf8');
  const rejected = run();
  assert.notEqual(rejected.status, 0, 'draft legal fixture should fail closed');
  assert.match(rejected.stderr, /LEGAL_RELEASE_NOT_APPROVED/);
  process.stdout.write(`${JSON.stringify({ result: 'passed', checks: ['approved-pass', 'bom-required', 'draft-rejected', 'placeholder-rejected'] })}\n`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}

function run() {
  return spawnSync(process.execPath, [verifier, temporary], { encoding: 'utf8' });
}
