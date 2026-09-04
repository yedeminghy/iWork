import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const directory=mkdtempSync(path.join(os.tmpdir(),'agent-week9-evidence-'));
const script=path.join(path.dirname(fileURLToPath(import.meta.url)),'verify-week9-evidence.mjs');
const adjudicator=path.join(path.dirname(fileURLToPath(import.meta.url)),'adjudicate-week9-soak.mjs');
const candidate='a'.repeat(64);
const write=(name,value)=>writeFileSync(path.join(directory,name),JSON.stringify({schemaVersion:1,candidateSha256:candidate,...value}));

try{
  write('week8-verification.json',{result:'passed',releaseGate:'week8-complete'});
  write('compatibility-matrix.json',{status:'passed',providers:[
    {providerId:'deepseek',fixtureStatus:'passed',realSampleStatus:'passed'},
    {providerId:'qwen',fixtureStatus:'passed',realSampleStatus:'passed'}
  ],mcp:[
    {transport:'STDIO',fixtureStatus:'passed',realSampleStatus:'passed',replayedCalls:0},
    {transport:'STREAMABLE_HTTP',fixtureStatus:'passed',realSampleStatus:'passed',replayedCalls:0}
  ],skillScopes:['BUILTIN','USER','WORKSPACE','MANAGED_IMPORT'],interpreters:['POWERSHELL','PYTHON','JAVASCRIPT'],uiStatus:'passed'});
  write('install-matrix.json',{status:'passed',skipped:false,environments:[
    environment('env_111111111111','CLEAN_WINDOWS_X64'),environment('env_222222222222','CLEAN_WINDOWS_X64'),environment('env_333333333333','MANAGED_WINDOWS_X64')
  ]});
  write('soak-summary.json',fullSoak('passed'));
  write('security-scan.json',{status:'passed',highRiskFindings:0,sentinelScanPassed:true,boundaries:Object.fromEntries(['rendererIpc','loopback','providerSecret','permission','fileRoot','script','preview','contentLeakage'].map(name=>[name,'passed']))});
  write('beta-feedback-summary.json',{status:'passed',testerCount:8,severityCounts:{P0:0,P1:0,P2:1,P3:2},lowerSeverityDecisionsComplete:true});

  const passed=run('beta');
  assert.equal(passed.status,0,passed.stderr);
  assert.equal(JSON.parse(passed.stdout).releaseRecommendation,'GO_INTERNAL_ONLY');

  write('soak-summary.json',fullSoak('failed'));
  write('soak-smoke-summary.json',regressionSmoke());
  const adjudicated=runAdjudicator();
  assert.equal(adjudicated.status,0,adjudicated.stderr);
  write('production-readiness.json',{status:'passed',allClosed:true,signed:true,channel:'production'});
  const accepted=run('beta');
  assert.equal(accepted.status,0,accepted.stderr||accepted.stdout);
  assert.equal(JSON.parse(accepted.stdout).releaseRecommendation,'GO_INTERNAL_ONLY');
  assert.equal(JSON.parse(accepted.stdout).soakAdjudicated,true);
  assert.ok(JSON.parse(accepted.stdout).validatedFiles.includes('soak-adjudication.json'));

  const tampered=JSON.parse(readFileSync(path.join(directory,'soak-summary.json'),'utf8'));
  tampered.finalCounters.activeTurns=1;
  writeFileSync(path.join(directory,'soak-summary.json'),JSON.stringify(tampered));
  assert.notEqual(run('beta').status,0,'Changing the original soak after adjudication must fail closed');

  write('soak-summary.json',fullSoak('failed'));
  assert.equal(runAdjudicator().status,0);
  const unsafeAdjudication=JSON.parse(readFileSync(path.join(directory,'soak-adjudication.json'),'utf8'));
  unsafeAdjudication.scope='PRODUCTION';
  writeFileSync(path.join(directory,'soak-adjudication.json'),JSON.stringify(unsafeAdjudication));
  assert.notEqual(run('beta').status,0,'Production-scoped adjudication must be rejected');

  const unrelatedFailure=fullSoak('failed');
  unrelatedFailure.failureCodes=['HOST_RESTART_TIMEOUT'];
  write('soak-summary.json',unrelatedFailure);
  assert.notEqual(runAdjudicator().status,0,'An unrelated failure code must not be adjudicated');

  const failedThreshold=fullSoak('failed');
  failedThreshold.thresholds.rssPassed=false;
  write('soak-summary.json',failedThreshold);
  assert.notEqual(runAdjudicator().status,0,'A failed stability threshold must not be adjudicated');

  write('soak-summary.json',fullSoak('passed'));
  write('security-scan.json',{status:'passed',highRiskFindings:0,sentinelScanPassed:true,secret:'sk-should-never-be-evidence',boundaries:{}});
  const rejected=run('beta');
  assert.notEqual(rejected.status,0);
  assert.equal(JSON.parse(rejected.stdout).releaseRecommendation,'NO_GO');
  process.stdout.write(JSON.stringify({schemaVersion:1,result:'passed',checks:['complete-beta-evidence','internal-only-recommendation','adjudicated-soak-accepted','adjudication-source-tamper-rejected','production-adjudication-rejected','unrelated-failure-rejected','failed-threshold-rejected','sensitive-evidence-rejected']})+'\n');
}finally{rmSync(directory,{recursive:true,force:true});}

function environment(environmentId,kind){return {environmentId,kind,lifecycle:{freshInstall:true,firstStart:true,upgrade:true,uninstallRetention:true,reinstallRecovery:true}};}
function run(profile){return spawnSync(process.execPath,[script,`--profile=${profile}`,`--evidence=${directory}`,`--candidate=${candidate}`],{encoding:'utf8'});}
function runAdjudicator(){return spawnSync(process.execPath,[adjudicator,candidate,directory],{encoding:'utf8'});}

function fullSoak(status){
  return {status,developmentOnly:false,durationSeconds:28_800,completedTurns:500,
    scenarios:{chat:197,mcpStdio:51,mcpHttp:50,skillScripts:{powershell:25,python:25,javascript:25},
      approvals:{allowOnce:1,allowThread:1,deny:1,expired:1},control:{cancel:25,retry:25,steering:25},
      faults:{providerDisconnect:25,mcpExit:1,rendererReload:1,hostRestart:1}},
    thresholds:{rssPassed:true,heapPassed:true,threadsPassed:true,handlesPassed:true,cancellationPassed:true,approvalResumePassed:true,databaseIntegrityPassed:true,terminalUniquenessPassed:true},
    finalCounters:{activeTurns:0,pendingApprovals:0,pendingSecretSessions:0,sseSubscribers:0,ownedChildProcesses:0},
    failureCodes:status==='failed'?['SOAK_EVENT_TIMEOUT']:[]};
}

function regressionSmoke(){
  return {status:'passed',developmentOnly:true,durationSeconds:500,completedTurns:60,
    scenarios:{chat:21,mcpStdio:7,mcpHttp:6,skillScripts:{powershell:3,python:3,javascript:3},
      approvals:{allowOnce:1,allowThread:1,deny:1,expired:1},control:{cancel:3,retry:3,steering:3},
      faults:{providerDisconnect:3,mcpExit:1,rendererReload:1,hostRestart:1}},
    thresholds:{rssPassed:true,heapPassed:true,threadsPassed:true,handlesPassed:true,cancellationPassed:true,approvalResumePassed:true,databaseIntegrityPassed:true,terminalUniquenessPassed:true},
    finalCounters:{activeTurns:0,pendingApprovals:0,pendingSecretSessions:0,sseSubscribers:0,ownedChildProcesses:0},failureCodes:[]};
}
