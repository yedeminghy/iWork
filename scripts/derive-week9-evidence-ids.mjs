#!/usr/bin/env node
// derive-week9-evidence-ids.mjs
// 用途：扫描本地留档目录，为每个留档文件派生不可伪造的证据 ID（ev_<sha256 前 12 位>），
//       并按 Week 9 用户侧证据的 9 组必需项做覆盖检查。
// 用法：node scripts/derive-week9-evidence-ids.mjs <留档目录>
// 输出：stdout 打印摘要；在留档目录内生成 evidence-id-map.json（含全部 ID 与覆盖状态）。
// 原则：本脚本只读留档文件、只写映射文件，不触碰任何证据 JSON。

import { createHash } from 'node:crypto';
import { readdirSync, statSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const archiveDir = path.resolve(process.argv[2] ?? '');
let stat;
try {
  stat = statSync(archiveDir);
} catch {
  console.error(`留档目录不存在: ${archiveDir}`);
  process.exit(1);
}
if (!stat.isDirectory()) {
  console.error(`不是目录: ${archiveDir}`);
  process.exit(1);
}

// 9 组必需项：前缀 -> 组名（beta 组单独按编号统计）
const GROUP_PREFIXES = [
  ['deepseek-', 'deepseek'],
  ['qwen-', 'qwen'],
  ['mcp-stdio-', 'mcp-stdio'],
  ['mcp-http-', 'mcp-http'],
  ['skill-', 'skill'],
  ['env-clean-1-', 'env-clean-1'],
  ['env-clean-2-', 'env-clean-2'],
  ['env-managed-1-', 'env-managed-1'],
];

function walk(dir, base = dir) {
  const out = [];
  for (const name of readdirSync(dir).sort()) {
    const full = path.join(dir, name);
    const s = statSync(full);
    if (s.isDirectory()) out.push(...walk(full, base));
    else out.push(path.relative(base, full).replaceAll('\\', '/'));
  }
  return out;
}

function sha256OfFile(file) {
  const h = createHash('sha256');
    const buf = readFileSync(file);
  h.update(buf);
  return h.digest('hex');
}

const files = walk(archiveDir);
const entries = [];
const groups = Object.fromEntries([...GROUP_PREFIXES.map(([, g]) => [g, []]), ['beta', []]]);
const betaTesterIds = new Set();

for (const rel of files) {
  const lower = path.basename(rel).toLowerCase();
  if (lower === 'evidence-id-map.json') continue; // 不给自己派 ID
  const sha = sha256OfFile(path.join(archiveDir, rel));
  const evidenceId = `ev_${sha.slice(0, 12)}`;
  let group = null;
  for (const [prefix, g] of GROUP_PREFIXES) {
    if (lower.startsWith(prefix)) { group = g; break; }
  }
  if (group === null) {
    const m = lower.match(/^beta-(\d{2})-/);
    if (m) {
      group = 'beta';
      betaTesterIds.add(m[1]);
    }
  }
  entries.push({ file: rel, sha256: sha, evidenceId, group });
  if (group) groups[group].push(rel);
}

const betaCount = betaTesterIds.size;
const coverage = {};
for (const [, g] of GROUP_PREFIXES) {
  coverage[g] = { files: groups[g].length, ok: groups[g].length >= 1 };
}
coverage.beta = { distinctTesters: betaCount, files: groups.beta.length, ok: betaCount >= 8 && betaCount <= 15 };

const missing = Object.entries(coverage).filter(([, v]) => !v.ok).map(([g]) => g);
const summary = {
  archiveDir,
  totalFiles: entries.length,
  allCovered: missing.length === 0,
  missingGroups: missing,
  coverage,
  entries,
};

writeFileSync(
  path.join(archiveDir, 'evidence-id-map.json'),
  `${JSON.stringify(summary, null, 2)}\n`,
  { encoding: 'utf8', mode: 0o600 },
);

process.stdout.write(`留档文件总数: ${entries.length}\n`);
for (const [g, v] of Object.entries(coverage)) {
  const label = g === 'beta' ? `beta（${v.distinctTesters} 人，需 8~15）` : g;
  process.stdout.write(`${v.ok ? 'OK ' : 'MISS'}  ${label}\n`);
}
if (missing.length === 0) {
  process.stdout.write('\n覆盖完整。evidence-id-map.json 已写入留档目录，把该文件路径发给我即可填写 user-evidence.json 并跑导入校验。\n');
} else {
  process.stdout.write(`\n缺少: ${missing.join(', ')}。按前缀约定补齐留档后重跑本脚本。\n`);
  process.stdout.write('命名约定: deepseek-*  qwen-*  mcp-stdio-*  mcp-http-*  skill-*  env-clean-1-*  env-clean-2-*  env-managed-1-*  beta-01-* ~ beta-15-*\n');
}
