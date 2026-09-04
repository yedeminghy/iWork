param(
  [ValidateSet('commit','beta')][string]$Profile='commit',
  [string]$EvidenceRoot,
  [string]$CandidateSha256,
  [switch]$SkipBuild,
  [switch]$SkipDependencyInstall,
  [switch]$SkipWeek7Regression
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $root 'dist-release'
$summary=[ordered]@{schemaVersion=1;week=8;profile=$Profile;startedAt=(Get-Date).ToUniversalTime().ToString('o');candidateSha256=$null;checks=@();skips=@()}

function Invoke-Checked([scriptblock]$Command,[string]$Label){& $Command;if($LASTEXITCODE-ne 0){throw "$Label failed"};$summary.checks+=$Label}
function Read-Evidence([string]$Name){$file=Join-Path $EvidenceRoot $Name;if(-not(Test-Path -LiteralPath $file)){throw "$Name is missing"};return Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json}

Push-Location $root
try{
  if(-not$SkipBuild){
    Invoke-Checked {mvn -B -ntp clean verify} 'maven-clean-verify'
    if(-not$SkipDependencyInstall){Invoke-Checked {npm ci} 'npm-ci'}else{$summary.skips+='dependency-install'}
    Invoke-Checked {npm run typecheck} 'typecheck'
    Invoke-Checked {npm run build} 'desktop-build'
  }else{$summary.skips+='build'}
  Invoke-Checked {node scripts\verify-week9-contracts.mjs} 'week8-and-week9-contracts'
  Invoke-Checked {node scripts\verify-artifact-preview.mjs} 'artifact-preview-security'

  if(-not$SkipWeek7Regression){
    & powershell -ExecutionPolicy Bypass -File .\scripts\verify-week7.ps1 -SkipPackage -SkipInstallAcceptance
    if($LASTEXITCODE-ne 0){throw 'Week 7 regression failed'}
    $summary.checks+='week7-to-week3-regression'
  }else{$summary.skips+='week7-regression'}

  if($Profile-eq'beta'){
    if(-not$EvidenceRoot){throw 'EvidenceRoot is required for beta'}
    if($CandidateSha256-notmatch'^[0-9a-fA-F]{64}$'){throw 'CandidateSha256 is required for beta'}
    $CandidateSha256=$CandidateSha256.ToLowerInvariant();$summary.candidateSha256=$CandidateSha256
    $week7=Read-Evidence 'week7-verification.json'
    if($week7.result-ne'passed'-or$week7.releaseGate-ne'week7-complete'){throw 'Week 7 no-skip gate is incomplete'}
    $acceptance=Read-Evidence 'week8-acceptance.json'
    if($acceptance.schemaVersion-ne 1-or$acceptance.candidateSha256-ne$CandidateSha256-or$acceptance.status-ne'passed'){throw 'Week 8 acceptance evidence is invalid'}
    foreach($id in 1..14){$key=('W8-A{0:d2}'-f$id);if($acceptance.acceptance.$key-ne'passed'){throw "$key is incomplete"}}
    foreach($id in 1..10){$key=('W8-OA{0:d2}'-f$id);if($acceptance.optimizationAcceptance.$key-ne'passed'){throw "$key is incomplete"}}
    if($acceptance.realProviders.deepseek-ne'passed'-or$acceptance.realProviders.qwen-ne'passed'){throw 'Real Provider evidence is incomplete'}
    if($acceptance.soak.durationSeconds-lt 7200-or$acceptance.soak.completedTurns-lt 100-or$acceptance.soak.status-ne'passed'){throw 'Week 8 soak is incomplete'}
    if($acceptance.beta.testerCount-lt 3-or$acceptance.beta.testerCount-gt 5-or$acceptance.beta.P0-ne 0-or$acceptance.beta.P1-ne 0){throw 'Week 8 beta evidence is incomplete'}
    if($acceptance.skipped){throw 'Week 8 beta evidence cannot contain skips'}
    $summary.checks+=@('week7-no-skip','week8-a01-a14','week8-oa01-oa10','real-provider-minimum','two-hour-soak','controlled-beta')
  }

  $summary.result='passed';$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o')
  $summary.releaseGate=if($Profile-eq'beta'-and$summary.skips.Count-eq 0){'week8-complete'}else{'development-only'}
  Write-Output ($summary|ConvertTo-Json -Depth 8 -Compress)
}catch{
  Write-Warning $_.Exception.Message
  $summary.result='failed';$summary.errorCode='WEEK8_GATE_FAILED';$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o');$summary.releaseGate='development-only'
  throw
}finally{
  if(-not(Test-Path -LiteralPath $output)){New-Item -ItemType Directory -Path $output -Force|Out-Null}
  [IO.File]::WriteAllText((Join-Path $output 'week8-verification.json'),($summary|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
  Pop-Location
}
