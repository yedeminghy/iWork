import { readFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const legalDirectory = path.resolve(process.argv[2] ?? path.join(root, 'packaging', 'legal'));
const files = [
  { name: 'EULA_zh-CN.txt', required: ['iWork', '杭州烛光鱼有限公司', '最终用户许可协议'] },
  { name: 'PRIVACY_zh-CN.md', required: ['iWork', '杭州烛光鱼有限公司', '隐私政策'] }
];
const forbidden = [
  /法务审阅草案/u,
  /确认前禁止发布/u,
  /法务确认清单/u,
  /@@[A-Z0-9_]+@@/u,
  /\[(?:链接|Windows系统|待确认|待填写)\]/u
];

const failures = [];
for (const file of files) {
  let content;
  try {
    const bytes = readFileSync(path.join(legalDirectory, file.name));
    if (file.name === 'EULA_zh-CN.txt' && !bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]))) {
      failures.push(`${file.name}:UTF8_BOM_REQUIRED`);
    }
    content = bytes.toString('utf8');
  } catch {
    failures.push(`${file.name}:MISSING`);
    continue;
  }
  if (content.trim().length < 500) failures.push(`${file.name}:TOO_SHORT`);
  for (const token of file.required) {
    if (!content.includes(token)) failures.push(`${file.name}:MISSING_REQUIRED_FIELD`);
  }
  if (forbidden.some(pattern => pattern.test(content))) failures.push(`${file.name}:NOT_APPROVED`);
}

if (failures.length > 0) {
  process.stderr.write(`${JSON.stringify({ result: 'failed', code: 'LEGAL_RELEASE_NOT_APPROVED', failures })}\n`);
  process.exit(1);
}

process.stdout.write(`${JSON.stringify({ result: 'passed', files: files.map(file => file.name) })}\n`);
