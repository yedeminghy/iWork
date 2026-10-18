import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';

const args=Object.fromEntries(process.argv.slice(2).map(value=>{
  const index=value.indexOf('=');return index<0?[value.replace(/^--/,''),true]:[value.slice(2,index),value.slice(index+1)];
}));
const profile=args.profile;
const evidenceRoot=args.evidence&&path.resolve(args.evidence);
const candidate=args.candidate?.toLowerCase();
assert.ok(['matrix','soak','beta'].includes(profile),'--profile must be matrix, soak, or beta');
assert.match(candidate??'',/^[0-9a-f]{64}$/,'--candidate must be a SHA-256 hex digest');
assert.ok(evidenceRoot,'--evidence is required');

const loaded=[];
const requirePassed=(value,label)=>assert.equal(value,'passed',`${label} must be passed`);
let candidateDelta=null;
const read=name=>{
  const value=JSON.parse(readFileSync(path.join(evidenceRoot,name),'utf8'));
  assert.equal(value.schemaVersion,1,`${name}: unsupported schema`);
  const evidenceCandidate=value.candidateSha256?.toLowerCase();
  if(evidenceCandidate!==candidate){
    assert.ok(candidateDelta,`${name}: candidate mismatch`);
    assert.equal(evidenceCandidate,candidateDelta.sourceCandidateSha256,`${name}: source candidate mismatch`);
    const inherited=candidateDelta.inheritedEvidence.find(item=>item.name===name);
    assert.ok(inherited,`${name}: evidence inheritance is not approved`);
    assert.equal(inherited.sha256,fileSha256(name),`${name}: inherited evidence changed after adjudication`);
  }
  scanSafe(value,name);
  loaded.push(name);
  return value;
};
let recommendation='NO_GO';
let soakAdjudicated=false;
try{
  candidateDelta=loadCandidateDelta();
  const week8=read('week8-verification.json');
  requirePassed(week8.result,'Week 8 verification');
  assert.equal(week8.releaseGate,'week8-complete','Week 8 full inherited gate is required');

  const compatibility=read('compatibility-matrix.json');
  requirePassed(compatibility.status,'Compatibility matrix');
  assert.deepEqual([...compatibility.providers].map(item=>item.providerId).sort(),['deepseek','qwen']);
  for(const provider of compatibility.providers){
    requirePassed(provider.fixtureStatus,`${provider.providerId} fixture`);
    requirePassed(provider.realSampleStatus,`${provider.providerId} real sample`);
  }
  assert.deepEqual([...compatibility.mcp].map(item=>item.transport).sort(),['STDIO','STREAMABLE_HTTP']);
  for(const transport of compatibility.mcp){requirePassed(transport.fixtureStatus,`${transport.transport} fixture`);requirePassed(transport.realSampleStatus,`${transport.transport} real sample`);assert.equal(transport.replayedCalls,0);}
  for(const scope of ['BUILTIN','USER','WORKSPACE','MANAGED_IMPORT'])assert.ok(compatibility.skillScopes.includes(scope),`Missing Skill scope ${scope}`);
  for(const interpreter of ['POWERSHELL','PYTHON','JAVASCRIPT'])assert.ok(compatibility.interpreters.includes(interpreter),`Missing interpreter ${interpreter}`);
  requirePassed(compatibility.uiStatus,'UI matrix');

  const install=read('install-matrix.json');
  requirePassed(install.status,'Install matrix');
  assert.equal(install.skipped,false,'Install matrix cannot contain skips');
  assert.ok(install.environments.filter(item=>item.kind==='CLEAN_WINDOWS_X64').length>=2,'At least two clean Windows x64 environments are required');
  assert.ok(install.environments.some(item=>item.kind==='MANAGED_WINDOWS_X64'),'A managed Windows x64 environment is required');
  for(const environment of install.environments){
    assert.match(environment.environmentId,/^env_[0-9a-f]{12,64}$/);
    for(const step of ['freshInstall','firstStart','upgrade','uninstallRetention','reinstallRecovery'])assert.equal(environment.lifecycle?.[step],true,`${environment.environmentId}: ${step} is incomplete`);
  }

  if(profile==='soak'||profile==='beta')soakAdjudicated=validateSoak(read('soak-summary.json'));
  if(profile==='beta'){
    const security=read('security-scan.json');
    requirePassed(security.status,'Security scan');
    assert.equal(security.highRiskFindings,0);
    for(const boundary of ['rendererIpc','loopback','providerSecret','permission','fileRoot','script','preview','contentLeakage'])requirePassed(security.boundaries?.[boundary],`Security boundary ${boundary}`);
    assert.equal(security.sentinelScanPassed,true);

    const feedback=read('beta-feedback-summary.json');
    requirePassed(feedback.status,'Beta feedback');
    assert.ok(feedback.testerCount>=8&&feedback.testerCount<=15,'Beta tester count must be 8..15');
    assert.equal(feedback.severityCounts?.P0,0);assert.equal(feedback.severityCounts?.P1,0);
    assert.equal(feedback.lowerSeverityDecisionsComplete,true);

    let productionReady=false;
    try{const production=read('production-readiness.json');productionReady=production.status==='passed'&&production.allClosed===true&&production.signed===true&&production.channel==='production';}catch(error){if(error?.code!=='ENOENT')throw error;}
    recommendation=productionReady&&!soakAdjudicated&&!candidateDelta?'GO_PRODUCTION':'GO_INTERNAL_ONLY';
  }
  const result={schemaVersion:1,result:'passed',profile,candidateSha256:candidate,releaseRecommendation:recommendation,soakAdjudicated,candidateDeltaAdjudicated:Boolean(candidateDelta),validatedFiles:loaded};
  process.stdout.write(JSON.stringify(result)+'\n');
}catch(error){
  process.stdout.write(JSON.stringify({schemaVersion:1,result:'failed',profile,candidateSha256:candidate,releaseRecommendation:'NO_GO',errorCode:'WEEK9_EVIDENCE_INVALID',message:safeMessage(error)})+'\n');
  process.exitCode=1;
}

function validateSoak(soak){
  let adjudicated=false;
  if(soak.status!=='passed'){
    validateAdjudication(soak,read('soak-adjudication.json'),read('soak-smoke-summary.json'));
    adjudicated=true;
  }
  assert.equal(soak.developmentOnly,false,'Development soak cannot satisfy the release gate');
  assert.ok(soak.durationSeconds>=28_800,'Soak must run at least 8 hours');
  assert.ok(soak.completedTurns>=500,'Soak must complete at least 500 Turns');
  assert.ok(soak.scenarios?.chat>=150);
  assert.ok(soak.scenarios?.mcpStdio>=50);assert.ok(soak.scenarios?.mcpHttp>=50);
  for(const interpreter of ['powershell','python','javascript'])assert.ok(soak.scenarios?.skillScripts?.[interpreter]>=20,`${interpreter} must run at least 20 times`);
  for(const decision of ['allowOnce','allowThread','deny','expired'])assert.ok(soak.scenarios?.approvals?.[decision]>0,`Approval ${decision} is missing`);
  for(const action of ['cancel','retry','steering'])assert.ok(soak.scenarios?.control?.[action]>0,`Control action ${action} is missing`);
  for(const fault of ['providerDisconnect','mcpExit','rendererReload','hostRestart'])assert.ok(soak.scenarios?.faults?.[fault]>0,`Fault ${fault} is missing`);
  for(const threshold of ['rssPassed','heapPassed','threadsPassed','handlesPassed','cancellationPassed','approvalResumePassed','databaseIntegrityPassed','terminalUniquenessPassed'])assert.equal(soak.thresholds?.[threshold],true,`Soak threshold ${threshold} failed`);
  for(const counter of ['activeTurns','pendingApprovals','pendingSecretSessions','sseSubscribers','ownedChildProcesses'])assert.equal(soak.finalCounters?.[counter],0,`Final counter ${counter} must be zero`);
  return adjudicated;
}

function validateAdjudication(soak,adjudication,regression){
  assert.equal(soak.status,'failed','Adjudication requires an original failed soak');
  requirePassed(adjudication.status,'Soak adjudication');
  assert.equal(adjudication.decision,'ACCEPT_ORIGINAL_RUN_WITH_HARNESS_WAIVER');
  assert.equal(adjudication.scope,'CONTROLLED_BETA_INTERNAL_ONLY');
  assert.equal(adjudication.productionRequiresFreshFullSoak,true);
  assert.equal(adjudication.reasonCode,'SOAK_APPROVAL_THREAD_REUSE');
  assert.equal(adjudication.originalEvidence,'soak-summary.json');
  assert.equal(adjudication.regressionEvidence,'soak-smoke-summary.json');
  assert.equal(adjudication.originalEvidenceSha256,fileSha256('soak-summary.json'),'Original soak changed after adjudication');
  assert.equal(adjudication.regressionEvidenceSha256,fileSha256('soak-smoke-summary.json'),'Regression smoke changed after adjudication');
  assert.deepEqual(soak.failureCodes,['SOAK_EVENT_TIMEOUT']);
  assert.deepEqual(adjudication.sourceFailureCodes,soak.failureCodes);
  const expected={
    approvalSlots:slotCount(soak.completedTurns,13),cancellationSlots:slotCount(soak.completedTurns,14),
    retrySlots:slotCount(soak.completedTurns,15),steeringSlots:slotCount(soak.completedTurns,16),
    providerDisconnectSlots:slotCount(soak.completedTurns,17)
  };
  const recordedApprovalDecisions=Object.values(soak.scenarios?.approvals??{}).reduce((total,value)=>total+Number(value),0);
  assert.deepEqual(adjudication.derived,{...expected,recordedApprovalDecisions,missingApprovalEvents:expected.approvalSlots-recordedApprovalDecisions});
  assert.ok(recordedApprovalDecisions>0&&recordedApprovalDecisions<expected.approvalSlots,'Approval-event deficit is not proven');
  assert.equal(soak.scenarios?.control?.cancel,expected.cancellationSlots);
  assert.equal(soak.scenarios?.control?.retry,expected.retrySlots);
  assert.equal(soak.scenarios?.control?.steering,expected.steeringSlots);
  assert.equal(soak.scenarios?.faults?.providerDisconnect,expected.providerDisconnectSlots);
  assert.deepEqual(adjudication.remediation,{approvalDecisionThreadsIsolated:true,hostRecoveryTimeoutBounded:true,regressionSmokePassed:true});
  requirePassed(regression.status,'Corrected soak regression');
  assert.equal(regression.developmentOnly,true);
  assert.ok(regression.completedTurns>=60);
  assert.deepEqual(regression.failureCodes,[]);
  for(const decision of ['allowOnce','allowThread','deny','expired'])assert.ok(regression.scenarios?.approvals?.[decision]>0);
  for(const fault of ['providerDisconnect','mcpExit','rendererReload','hostRestart'])assert.ok(regression.scenarios?.faults?.[fault]>0);
  for(const threshold of ['rssPassed','heapPassed','threadsPassed','handlesPassed','cancellationPassed','approvalResumePassed','databaseIntegrityPassed','terminalUniquenessPassed'])assert.equal(regression.thresholds?.[threshold],true);
  for(const counter of ['activeTurns','pendingApprovals','pendingSecretSessions','sseSubscribers','ownedChildProcesses'])assert.equal(regression.finalCounters?.[counter],0);
}

function slotCount(completedTurns,slot){
  if(!Number.isInteger(completedTurns)||completedTurns<=slot)return 0;
  return Math.floor((completedTurns-1-slot)/20)+1;
}

function fileSha256(name){return createHash('sha256').update(readFileSync(path.join(evidenceRoot,name))).digest('hex');}

function loadCandidateDelta(){
  let value;
  try{value=JSON.parse(readFileSync(path.join(evidenceRoot,'candidate-delta-adjudication.json'),'utf8'));}
  catch(error){if(error?.code==='ENOENT')return null;throw error;}
  assert.equal(value.schemaVersion,1,'candidate-delta-adjudication.json: unsupported schema');
  assert.equal(value.candidateSha256?.toLowerCase(),candidate,'Candidate delta target mismatch');
  assert.match(value.sourceCandidateSha256??'',/^[0-9a-f]{64}$/,'Candidate delta source must be a SHA-256 digest');
  assert.notEqual(value.sourceCandidateSha256,candidate,'Candidate delta must link two distinct candidates');
  requirePassed(value.status,'Candidate delta adjudication');
  assert.equal(value.decision,'INHERIT_CONTROLLED_BETA_EVIDENCE_FOR_TEST_AND_RELEASE_HARDENING_DELTA');
  assert.equal(value.scope,'CONTROLLED_BETA_INTERNAL_ONLY');
  assert.equal(value.productionRequiresFreshEvidence,true);
  assert.equal(value.productionBehaviorChanged,false);
  assert.equal(value.featureBehaviorChanged,false);
  assert.equal(value.releaseHardeningChanged,true);
  assert.match(value.sourceRevision??'',/^source-sha256:[0-9a-f]{64}$/);
  assert.match(value.targetRevision??'',/^source-sha256:[0-9a-f]{64}$/);
  assert.notEqual(value.sourceRevision,value.targetRevision);
  const allowedPaths=new Set([
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
  ]);
  assert.ok(Array.isArray(value.changedPaths)&&value.changedPaths.length>0,'Candidate delta changed paths are required');
  assert.equal(new Set(value.changedPaths).size,value.changedPaths.length,'Candidate delta changed paths must be unique');
  for(const changedPath of value.changedPaths)assert.ok(allowedPaths.has(changedPath),`Candidate delta path is outside the controlled hardening scope: ${changedPath}`);
  const requiredInherited=['soak-adjudication.json','soak-smoke-summary.json','soak-summary.json'];
  assert.deepEqual([...value.inheritedEvidence].map(item=>item.name).sort(),requiredInherited);
  for(const item of value.inheritedEvidence){assert.match(item.sha256??'',/^[0-9a-f]{64}$/);}
  scanSafe(value,'candidate-delta-adjudication.json');
  loaded.push('candidate-delta-adjudication.json');
  return value;
}

function scanSafe(value,label){
  const visit=(item,key='')=>{
    if(Array.isArray(item)){item.forEach(entry=>visit(entry,key));return;}
    if(item&&typeof item==='object'){
      for(const [childKey,child] of Object.entries(item)){
        assert.ok(!/^(api.?key|authorization|cookie|secret|secretValue|reasoning|thinking|prompt|body|content|absolute.?path|user.?name|host.?name|sid)$/i.test(childKey),`${label}: forbidden evidence field ${childKey}`);
        visit(child,childKey);
      }
      return;
    }
    if(typeof item==='string'){
      assert.ok(!/Bearer\s+[A-Za-z0-9._-]+/i.test(item),`${label}: bearer value detected`);
      assert.ok(!/(^|[^A-Za-z])sk-[A-Za-z0-9_-]{12,}/i.test(item),`${label}: key-like value detected`);
      assert.ok(!/(?:[A-Za-z]:\\|\\\\[^\\]+\\|\/Users\/|\/home\/)/.test(item),`${label}: private path detected in ${key}`);
    }
  };
  visit(value);
}

function safeMessage(error){return String(error?.message??'Evidence validation failed').replace(/(?:[A-Za-z]:\\|\\\\)[^\s]*/g,'[PATH]').slice(0,512);}
