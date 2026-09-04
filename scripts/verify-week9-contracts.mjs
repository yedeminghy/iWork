import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const read=relative=>readFileSync(path.join(root,relative),'utf8');
const checks=[];

const pom=read('pom.xml');
assert.match(pom,/<java\.version>21<\/java\.version>/);
assert.match(pom,/<agentscope\.version>1\.1\.0-RC2<\/agentscope\.version>/);
checks.push('java21-fixed','agentscope-1.1.0-rc2-fixed');

const catalog=JSON.parse(read('packaging/provider-catalog.json'));
assert.equal(catalog.schemaVersion,1);
assert.deepEqual(catalog.profiles.map(item=>item.providerId).sort(),['deepseek','qwen']);
assert.equal(new Set(catalog.profiles.map(item=>item.id)).size,2);
for(const profile of catalog.profiles){
  assert.ok(['OPENAI_COMPATIBLE','DASHSCOPE'].includes(profile.adapter));
  assert.ok(!('endpoint' in profile),'Provider endpoint must remain application-owned');
  assert.ok(profile.capabilities.includes('STRUCTURED_TOOL_CALL'));
}
checks.push('dual-provider-catalog-fixed','provider-endpoint-application-owned');

const migrations=readdirSync(path.join(root,'agent-persistence/src/main/resources/db/migration'))
  .filter(name=>/^V\d+__.+\.sql$/.test(name)).sort((a,b)=>Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]));
assert.equal(migrations.at(-1)?.match(/^V(\d+)/)?.[1],'8','Week 9 must not add a schema without an explicit V9 recovery design');
assert.equal(new Set(migrations.map(name=>name.match(/^V(\d+)/)[1])).size,migrations.length);
checks.push('schema-frozen-at-v8');

const preload=read('desktop-shell/src/preload.cts');
for(const forbidden of ['readFile','writeFile','executeCommand','approveAll','shell:exec','http:request','secret:get']){
  assert.ok(!preload.includes(forbidden),`Preload exposes forbidden generic capability: ${forbidden}`);
}
for(const required of ['diagnostic:preview','diagnostic:export'])assert.ok(preload.includes(required));
checks.push('renderer-ipc-capability-boundary','diagnostic-explicit-export-only');

const hostConfiguration=read('agent-host/src/main/java/dev/agentdesktop/host/HostConfiguration.java');
assert.match(hostConfiguration,/agentdesktop\.test-fixtures/);
assert.match(hostConfiguration,/PROFILE_NOT_AVAILABLE/);
assert.match(hostConfiguration,/new HarnessRunService/);
checks.push('mock-fixture-gated','harness-single-production-loop');

const gateway=read('agent-runtime/src/main/java/dev/agentdesktop/runtime/ToolGateway.java');
for(const tool of ['workspace.read_text','workspace.write_text','workspace.run_script','skill.run_script'])assert.ok(gateway.includes(`"${tool}"`));
assert.match(gateway,/APPROVAL_STALE/);
assert.match(gateway,/permit\.consume/);
checks.push('tool-gateway-unified','execution-fingerprint-revalidated','permit-single-use');

const diagnostics=read('agent-host/src/main/java/dev/agentdesktop/host/DiagnosticService.java');
for(const field of ['activeTurns','activeThreads','pendingApprovals','pendingSecretSessions','mcpConnections','mcpStdioProcesses','sseSubscribers','skillScriptProcesses','ownedChildProcesses'])assert.ok(diagnostics.includes(`"${field}"`));
for(const forbidden of ['listItems(','payloadJson','readString(','readAllLines('])assert.ok(!diagnostics.includes(forbidden));
checks.push('soak-operational-counters','diagnostic-content-exclusion');

const soakRunner=read('scripts/run-week9-soak.mjs');
assert.match(soakRunner,/const approvalThreadId = id\('thr'\);/);
assert.match(soakRunner,/startTurn\(`__week6_tool__\|mcp:\$\{serverId\}:danger\|danger\|\{\}`, approvalThreadId\)/);
assert.match(soakRunner,/AGENT_DESKTOP_HOST_READY_TIMEOUT_MS \|\|= '120000'/);
assert.match(soakRunner,/acceptanceHostRecoveryTimeoutMs\(\)/);
assert.equal(soakRunner.match(/waitForEvent\(/g)?.length,4,'SOAK_EVENT_TIMEOUT attribution depends on exactly three waitForEvent call sites');
assert.equal(soakRunner.match(/throw new Error\('SOAK_EVENT_TIMEOUT'\)/g)?.length,1);
const adjudicator=read('scripts/adjudicate-week9-soak.mjs');
assert.match(adjudicator,/Only the known event-timeout harness defect is adjudicable/);
assert.match(adjudicator,/scope: 'CONTROLLED_BETA_INTERNAL_ONLY'/);
assert.match(adjudicator,/productionRequiresFreshFullSoak: true/);
const evidenceVerifier=read('scripts/verify-week9-evidence.mjs');
assert.match(evidenceVerifier,/productionReady&&!soakAdjudicated/);
assert.match(evidenceVerifier,/Original soak changed after adjudication/);
assert.match(evidenceVerifier,/Regression smoke changed after adjudication/);
checks.push('soak-approval-decisions-isolated','soak-host-recovery-timeout-bounded','soak-timeout-attribution-fixed','soak-adjudication-beta-only','soak-adjudication-hash-bound');

const sourceHash=createHash('sha256').update(JSON.stringify({
  catalog,
  migrations,
  boundaries:[preload,hostConfiguration,gateway,diagnostics].map(value=>createHash('sha256').update(value).digest('hex'))
})).digest('hex');

process.stdout.write(JSON.stringify({schemaVersion:1,result:'passed',sourceContractSha256:sourceHash,checks})+'\n');
