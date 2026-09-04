param(
  [ValidateSet('commit','matrix','soak','beta')][string]$Profile='commit',
  [string]$EvidenceRoot,
  [string]$CandidateSha256,
  [switch]$SkipBuild,
  [switch]$SkipDependencyInstall,
  [switch]$SkipWeek8Regression
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $root 'dist-release'
$summary=[ordered]@{schemaVersion=1;week=9;profile=$Profile;startedAt=(Get-Date).ToUniversalTime().ToString('o');candidateSha256=$null;checks=@();skips=@();releaseRecommendation='NO_GO'}

function Invoke-Checked([scriptblock]$Command,[string]$Label){& $Command;if($LASTEXITCODE-ne 0){throw "$Label failed"};$summary.checks+=$Label}

Push-Location $root
try{
  if(-not$SkipBuild){
    Invoke-Checked {mvn -B -ntp clean verify} 'maven-clean-verify'
    if(-not$SkipDependencyInstall){Invoke-Checked {npm ci} 'npm-ci'}else{$summary.skips+='dependency-install'}
    Invoke-Checked {npm run typecheck} 'typecheck'
    Invoke-Checked {npm run build} 'desktop-build'
  }else{$summary.skips+='build'}
  Invoke-Checked {node scripts\verify-week9-contracts.mjs} 'week9-static-contracts'
  Invoke-Checked {node scripts\verify-artifact-preview.mjs} 'artifact-preview-security'

  if(-not$SkipWeek8Regression){
    & powershell -ExecutionPolicy Bypass -File .\scripts\verify-week8.ps1 -Profile commit -SkipBuild -SkipDependencyInstall
    if($LASTEXITCODE-ne 0){throw 'Week 8 regression failed'}
    $summary.checks+='week8-to-week3-regression'
  }else{$summary.skips+='week8-regression'}

  if($Profile-ne'commit'){
    if(-not$EvidenceRoot){throw 'EvidenceRoot is required outside the commit profile'}
    if($CandidateSha256-notmatch'^[0-9a-fA-F]{64}$'){throw 'CandidateSha256 is required outside the commit profile'}
    $CandidateSha256=$CandidateSha256.ToLowerInvariant();$summary.candidateSha256=$CandidateSha256
    $validation=& node scripts\verify-week9-evidence.mjs "--profile=$Profile" "--evidence=$EvidenceRoot" "--candidate=$CandidateSha256"
    if($LASTEXITCODE-ne 0){Write-Output $validation;throw 'Week 9 evidence validation failed'}
    $validated=$validation|ConvertFrom-Json;$summary.releaseRecommendation=$validated.releaseRecommendation;$summary.checks+=$validated.validatedFiles
    if($Profile-eq'beta'){$summary.checks+='week8-full-inherited-gate'}
  }

  $summary.result='passed';$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o')
  $summary.releaseGate=if($summary.skips.Count-gt 0){'development-only'}elseif($Profile-eq'beta'){'week9-complete'}elseif($Profile-eq'commit'){'commit-passed'}else{"week9-$Profile"}
  Write-Output ($summary|ConvertTo-Json -Depth 8 -Compress)
}catch{
  Write-Warning $_.Exception.Message
  $summary.result='failed';$summary.errorCode='WEEK9_GATE_FAILED';$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o');$summary.releaseGate='development-only';$summary.releaseRecommendation='NO_GO'
  throw
}finally{
  if(-not(Test-Path -LiteralPath $output)){New-Item -ItemType Directory -Path $output -Force|Out-Null}
  [IO.File]::WriteAllText((Join-Path $output 'week9-verification.json'),($summary|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
  Pop-Location
}
