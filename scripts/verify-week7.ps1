param(
  [string]$PreviousInstaller,
  [string]$ExpectedPreviousSha256,
  [ValidateSet('internal','beta','production')][string]$Channel,
  [switch]$SkipPackage,
  [switch]$SkipInstallAcceptance
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$output=Join-Path $root 'dist-release'
$releaseSummary=Join-Path $output 'release-artifacts.json'
if(-not$Channel){$Channel=if(Test-Path -LiteralPath $releaseSummary){[string](Get-Content -LiteralPath $releaseSummary -Raw -Encoding UTF8|ConvertFrom-Json).channel}else{'internal'}}
$summary=[ordered]@{schemaVersion=1;week=7;startedAt=(Get-Date).ToUniversalTime().ToString('o');packageSkipped=[bool]$SkipPackage;installAcceptanceSkipped=[bool]$SkipInstallAcceptance;checks=@()}
Push-Location $root
try{
  if(-not$PreviousInstaller){$fixture=Get-Content -LiteralPath (Join-Path $root 'packaging\previous-release\0.1.0\fixture.json') -Raw -Encoding UTF8|ConvertFrom-Json;$PreviousInstaller=Join-Path $root ('packaging\previous-release\0.1.0\'+$fixture.installer);$ExpectedPreviousSha256=$fixture.sha256}
  if(-not$ExpectedPreviousSha256){throw 'ExpectedPreviousSha256 is required when PreviousInstaller is supplied'}
  $previous=[IO.Path]::GetFullPath($PreviousInstaller)
  if(-not(Test-Path -LiteralPath $previous)){throw 'Previous-release fixture is missing'}
  if((Get-FileHash -LiteralPath $previous -Algorithm SHA256).Hash.ToLowerInvariant()-ne$ExpectedPreviousSha256.ToLowerInvariant()){throw 'Previous-release fixture hash mismatch'}
  $summary.previousReleaseSha256=$ExpectedPreviousSha256.ToLowerInvariant();$summary.checks+='previous-release-fixture'

  if(-not$SkipPackage){
    & (Join-Path $PSScriptRoot 'build-week7-package.ps1') -Channel $Channel;if($LASTEXITCODE-ne 0){throw 'Package build failed'}
    $summary.checks+=@('maven','npm-ci','typecheck','desktop-build','jre-image','manifest','nsis-package','package-content')
  }else{
    & npm run typecheck;if($LASTEXITCODE-ne 0){throw 'Typecheck failed'}
    & node (Join-Path $PSScriptRoot 'verify-week7-package.mjs') (Join-Path $output 'win-unpacked') $Channel;if($LASTEXITCODE-ne 0){throw 'Existing package verification failed'}
    $summary.checks+=@('typecheck','existing-package-content')
  }
  & node (Join-Path $PSScriptRoot 'verify-week7-static.mjs');if($LASTEXITCODE-ne 0){throw 'Static Week 7 checks failed'};$summary.checks+='release-fail-closed'
  & node (Join-Path $PSScriptRoot 'verify-parent-liveness.mjs');if($LASTEXITCODE-ne 0){throw 'Parent liveness verification failed'};$summary.checks+='parent-liveness'
  & (Join-Path $PSScriptRoot 'verify-week7-runtime.ps1') -Executable (Join-Path $output 'win-unpacked\agent-desktop.exe');if($LASTEXITCODE-ne 0){throw 'Packaged recovery verification failed'}
  $summary.checks+=@('packaged-renderer','host-recovery','main-recovery','zero-replay','crash-loop')

  $release=Get-Content -LiteralPath (Join-Path $output 'release-artifacts.json') -Raw -Encoding UTF8|ConvertFrom-Json
  $current=Join-Path $output $release.installer
  if(-not$SkipInstallAcceptance){
    & (Join-Path $PSScriptRoot 'verify-week7-install.ps1') -PreviousInstaller $previous -ExpectedPreviousSha256 $ExpectedPreviousSha256 -CurrentInstaller $current
    if($LASTEXITCODE-ne 0){throw 'Install/upgrade/uninstall acceptance failed'}
    $summary.checks+=@('current-user-install','upgrade-retention','safe-uninstall','reinstall-recovery')
  }
  & (Join-Path $PSScriptRoot 'verify-week6.ps1') -SkipBuild;if($LASTEXITCODE-ne 0){throw 'Week 6 regression failed'};$summary.checks+='week6-full-regression'
  $summary.result='passed';$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o')
  if($SkipPackage-or$SkipInstallAcceptance){$summary.releaseGate='development-only'}else{$summary.releaseGate='week7-complete'}
  Write-Output ($summary|ConvertTo-Json -Depth 5 -Compress)
}catch{
  $summary.result='failed';$summary.errorCode='WEEK7_GATE_FAILED';$summary.error=$_.Exception.Message;$summary.completedAt=(Get-Date).ToUniversalTime().ToString('o');Write-Output ($summary|ConvertTo-Json -Depth 5 -Compress);throw
}finally{
  if(-not(Test-Path -LiteralPath $output)){New-Item -ItemType Directory -Path $output -Force|Out-Null}
  [IO.File]::WriteAllText((Join-Path $output 'week7-verification.json'),($summary|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
  Pop-Location
}
