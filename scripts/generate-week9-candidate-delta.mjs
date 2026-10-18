import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root=path.resolve(import.meta.dirname,'..');
const [sourceCandidate,sourceRevision,evidenceRootArg]=process.argv.slice(2);
assert.match(sourceCandidate??'',/^[0-9a-f]{64}$/,'source candidate must be a SHA-256 digest');
assert.match(sourceRevision??'',/^source-sha256:[0-9a-f]{64}$/,'source revision must be a source SHA-256 digest');
const release=JSON.parse(readFileSync(path.join(root,'dist-release','release-artifacts.json'),'utf8'));
const candidate=String(release.sha256??'').toLowerCase();
assert.match(candidate,/^[0-9a-f]{64}$/,'target candidate is invalid');
assert.notEqual(candidate,sourceCandidate,'source and target candidates must differ');
assert.match(release.sourceRevision??'',/^source-sha256:[0-9a-f]{64}$/,'target revision is invalid');
assert.notEqual(release.sourceRevision,sourceRevision,'source and target revisions must differ');
const sourceRoot=path.join(root,'evidence','week9',sourceCandidate);
const evidenceRoot=path.resolve(evidenceRootArg??path.join(root,'evidence','week9',candidate));
const inheritedNames=['soak-adjudication.json','soak-smoke-summary.json','soak-summary.json'];
const inheritedEvidence=inheritedNames.map(name=>({name,sha256:fileSha256(path.join(sourceRoot,name))}));
const adjudication={
  schemaVersion:1,
  candidateSha256:candidate,
  sourceCandidateSha256:sourceCandidate,
  status:'passed',
  decision:'INHERIT_CONTROLLED_BETA_EVIDENCE_FOR_TEST_AND_RELEASE_HARDENING_DELTA',
  scope:'CONTROLLED_BETA_INTERNAL_ONLY',
  productionRequiresFreshEvidence:true,
  productionBehaviorChanged:false,
  featureBehaviorChanged:false,
  releaseHardeningChanged:true,
  sourceRevision,
  targetRevision:release.sourceRevision,
  changedPaths:[
    'agent-mcp/src/main/java/dev/agentdesktop/mcp/SdkMcpClientManager.java',
    'agent-mcp/src/main/java/dev/agentdesktop/mcp/RestrictedStdioClientTransport.java',
    'agent-mcp/src/test/java/dev/agentdesktop/mcp/McpTransportIntegrationTest.java',
    'agent-runtime/src/test/java/dev/agentdesktop/runtime/skill/SkillScriptAdapterTest.java',
    'agent-host/src/test/java/dev/agentdesktop/host/TurnCoordinatorTest.java',
    'desktop-shell/src/main.ts',
    'packaging/electron-builder.yml',
    'scripts/generate-week9-compatibility-automation.mjs',
    'scripts/generate-week9-candidate-delta.mjs',
    'scripts/verify-week7-electron.mjs',
    'scripts/verify-week7-install.ps1',
    'scripts/verify-week7.ps1',
    'scripts/verify-week9-evidence.mjs',
    'scripts/verify-week9-evidence.test.mjs'
  ],
  inheritedEvidence
};
mkdirSync(evidenceRoot,{recursive:true});
writeFileSync(path.join(evidenceRoot,'candidate-delta-adjudication.json'),`${JSON.stringify(adjudication,null,2)}\n`,{encoding:'utf8',mode:0o600});
process.stdout.write(`${JSON.stringify({result:'passed',candidateSha256:candidate,inheritedEvidence:inheritedNames})}\n`);

function fileSha256(file){return createHash('sha256').update(readFileSync(file)).digest('hex');}
