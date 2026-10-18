param(
  [Parameter(Mandatory=$true)][string]$PreviousInstaller,
  [Parameter(Mandatory=$true)][string]$ExpectedPreviousSha256,
  [Parameter(Mandatory=$true)][string]$CurrentInstaller
)
$ErrorActionPreference='Stop'
if($env:AGENT_DESKTOP_INSTALL_ACCEPTANCE-ne'1'){throw 'Install acceptance requires AGENT_DESKTOP_INSTALL_ACCEPTANCE=1 in a disposable Windows user or VM'}
$currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if($env:AGENT_DESKTOP_ACCEPTANCE_USER_SID-ne$currentSid){throw 'AGENT_DESKTOP_ACCEPTANCE_USER_SID must match the disposable acceptance user SID'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$previous=[IO.Path]::GetFullPath($PreviousInstaller);$current=[IO.Path]::GetFullPath($CurrentInstaller)
if(-not(Test-Path -LiteralPath $previous)-or-not(Test-Path -LiteralPath $current)){throw 'Acceptance installer is missing'}
if((Get-FileHash -LiteralPath $previous -Algorithm SHA256).Hash.ToLowerInvariant()-ne$ExpectedPreviousSha256.ToLowerInvariant()){throw 'Previous installer hash mismatch'}
$previousVersion=[Version](Get-Content -LiteralPath (Join-Path (Split-Path $previous -Parent) 'fixture.json') -Raw -Encoding UTF8|ConvertFrom-Json).appVersion
$currentVersion=[Version](Get-Content -LiteralPath (Join-Path (Split-Path $current -Parent) 'release-artifacts.json') -Raw -Encoding UTF8|ConvertFrom-Json).appVersion
if($currentVersion-le$previousVersion){throw 'Install acceptance requires a true N to N+1 version transition'}
$previousInstallRoot=Join-Path $env:LOCALAPPDATA 'Programs\agent-desktop';$previousExecutable=Join-Path $previousInstallRoot 'agent-desktop.exe';$previousUninstaller=Join-Path $previousInstallRoot 'Uninstall agent-desktop.exe'
$currentInstallRoot=Join-Path $env:LOCALAPPDATA 'Programs\iWork';$currentExecutable=Join-Path $currentInstallRoot 'iWork.exe';$currentUninstaller=Join-Path $currentInstallRoot 'Uninstall iWork.exe'
$uninstallRegistry='HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
if((Test-Path -LiteralPath $previousInstallRoot)-or(Test-Path -LiteralPath $currentInstallRoot)){throw 'Disposable acceptance user already has an agent-desktop or iWork installation'}
$existing=Get-ChildItem -LiteralPath $uninstallRegistry -ErrorAction SilentlyContinue|Get-ItemProperty|Where-Object{$_.DisplayName-in @('agent-desktop','iWork')}
if($existing){throw 'Disposable acceptance user already has an agent-desktop or iWork uninstall registration'}
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('agent-week7-install-'+[Guid]::NewGuid().ToString('N'));$appHome=Join-Path $testRoot 'app-home';$externalRoot=Join-Path $testRoot 'external-root'
$http=$null
function Install-Package([string]$installer,[string]$expectedExecutable){$process=Start-Process -FilePath $installer -ArgumentList '/S' -WindowStyle Hidden -PassThru -Wait;if($process.ExitCode-ne 0){throw "Installer failed with exit $($process.ExitCode)"};$deadline=[DateTime]::UtcNow.AddSeconds(30);while(-not(Test-Path -LiteralPath $expectedExecutable)-and[DateTime]::UtcNow-lt$deadline){Start-Sleep -Milliseconds 200};if(-not(Test-Path -LiteralPath $expectedExecutable)){throw "Installed executable was not created: $expectedExecutable"}}
function Stop-InstalledProcesses([string]$targetExecutable){
  $processName=[IO.Path]::GetFileNameWithoutExtension($targetExecutable)
  $deadline=[DateTime]::UtcNow.AddSeconds(30)
  do{
    $processes=@(Get-Process -Name $processName -ErrorAction SilentlyContinue)
    foreach($process in $processes){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue}
    if($processes.Count-eq 0){return}
    Start-Sleep -Milliseconds 200
  }while([DateTime]::UtcNow-lt$deadline)
  throw "Installed smoke left $processName processes running"
}
function Invoke-Smoke([string]$targetExecutable,[string]$expectedLog){
  $env:AGENT_W7_EXECUTABLE=$targetExecutable;$env:AGENT_W7_EXPECT_LOG=$expectedLog
  try{& node (Join-Path $PSScriptRoot 'verify-week7-electron.mjs');$exitCode=$LASTEXITCODE}
  finally{Stop-InstalledProcesses $targetExecutable}
  if($exitCode-ne 0){throw "Installed smoke failed: $expectedLog"}
}
function Uninstall-Package([string]$targetUninstaller,[string]$targetInstallRoot){if(-not(Test-Path -LiteralPath $targetUninstaller)){throw "Uninstaller is missing: $targetUninstaller"};$process=Start-Process -FilePath $targetUninstaller -ArgumentList '/S' -WindowStyle Hidden -PassThru -Wait;if($process.ExitCode-ne 0){throw "Uninstaller failed with exit $($process.ExitCode)"};$deadline=[DateTime]::UtcNow.AddSeconds(30);while((Test-Path -LiteralPath $targetInstallRoot)-and[DateTime]::UtcNow-lt$deadline){Start-Sleep -Milliseconds 200};if(Test-Path -LiteralPath $targetInstallRoot){throw "Uninstaller did not remove installation resources: $targetInstallRoot"}}
try{
  New-Item -ItemType Directory -Path $appHome,$externalRoot -Force|Out-Null
  Set-Content -LiteralPath (Join-Path $externalRoot 'must-survive.txt') -Value 'external-root-survived' -Encoding UTF8
  $env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000';$env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
  Install-Package $previous $previousExecutable
  Invoke-Smoke $previousExecutable 'smoke-passed'

  $workspace=Join-Path $appHome 'workspace';New-Item -ItemType Directory -Path $workspace -Force|Out-Null
  $fixture=Join-Path $workspace 'mcp-week7-upgrade-fixture.mjs';Copy-Item -LiteralPath (Join-Path $root 'scripts\mcp-week3-fixture.mjs') -Destination $fixture
  $bearer='w7-upgrade-secret-'+[Guid]::NewGuid().ToString('N');$env:AGENT_W3_FIXTURE_TOKEN=$bearer
  $http=Start-Process -FilePath (Get-Command node).Source -ArgumentList @($fixture,'http') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'fixture.out') -RedirectStandardError (Join-Path $testRoot 'fixture.err')
  $deadline=[DateTime]::UtcNow.AddSeconds(10);do{Start-Sleep -Milliseconds 100;$ready=if(Test-Path (Join-Path $testRoot 'fixture.out')){Get-Content (Join-Path $testRoot 'fixture.out') -First 1}else{$null}}until($ready-or[DateTime]::UtcNow-gt$deadline);if(!$ready){throw 'Upgrade MCP fixture did not start'};$port=($ready|ConvertFrom-Json).port
  $env:AGENT_DESKTOP_W3_SMOKE='1';$env:AGENT_DESKTOP_NODE=(Get-Command node).Source;$env:AGENT_DESKTOP_W3_FIXTURE=$fixture;$env:AGENT_DESKTOP_W3_HTTP_ENDPOINT="http://127.0.0.1:$port/mcp";$env:AGENT_DESKTOP_W3_BEARER=$bearer;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'week3.log'
  Invoke-Smoke $previousExecutable 'week3-passed'
  Remove-Item Env:AGENT_DESKTOP_W3_SMOKE

  $sourceV1=Join-Path $testRoot 'skill-v1\managed-import';$sourceV2=Join-Path $testRoot 'skill-v2\managed-import';New-Item -ItemType Directory -Path (Join-Path $sourceV1 'scripts'),(Join-Path $sourceV2 'scripts') -Force|Out-Null;$nl=[Environment]::NewLine
  foreach($entry in @(@($sourceV1,'W5_VERSION_ONE'),@($sourceV2,'W5_VERSION_TWO'))){Set-Content -LiteralPath (Join-Path $entry[0] 'SKILL.md') -Encoding UTF8 -Value ('---'+$nl+'name: managed-import'+$nl+'description: Verify upgrade retention for a managed Skill.'+$nl+'license: Apache-2.0'+$nl+'---'+$nl+$entry[1]+$nl);Set-Content -LiteralPath (Join-Path $entry[0] 'scripts\safe.ps1') -Encoding UTF8 -Value "Write-Output 'SAFE'"}
  $env:AGENT_DESKTOP_W5_SMOKE='1';$env:AGENT_DESKTOP_W5_SOURCE_V1=$sourceV1;$env:AGENT_DESKTOP_W5_SOURCE_V2=$sourceV2;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'week5.log'
  Invoke-Smoke $previousExecutable 'week5-passed'
  Remove-Item Env:AGENT_DESKTOP_W5_SMOKE
  if(Get-ChildItem -LiteralPath $appHome -File -Recurse|Select-String -SimpleMatch $bearer -ErrorAction SilentlyContinue){throw 'Plain MCP bearer token was persisted before upgrade'}
  if($http-and-not$http.HasExited){Stop-Process -Id $http.Id -Force};$http=$null

  Install-Package $current $currentExecutable
  $env:AGENT_DESKTOP_W7_SMOKE='1';$env:AGENT_DESKTOP_W7_UPGRADE_VERIFY='1';$env:AGENT_DESKTOP_W7_EXPECTED_VERSION=$currentVersion.ToString();$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'upgrade.log'
  Invoke-Smoke $currentExecutable 'week7-upgrade-data-preserved'
  if(-not(Test-Path -LiteralPath (Join-Path $externalRoot 'must-survive.txt'))){throw 'Upgrade removed the external root sentinel'}
  Uninstall-Package $currentUninstaller $currentInstallRoot
  Uninstall-Package $previousUninstaller $previousInstallRoot
  if(-not(Test-Path -LiteralPath $appHome)-or-not(Test-Path -LiteralPath (Join-Path $externalRoot 'must-survive.txt'))){throw 'Uninstall removed user data'}
  Install-Package $current $currentExecutable
  $env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'reinstall.log';Invoke-Smoke $currentExecutable 'week7-upgrade-data-preserved'
  Uninstall-Package $currentUninstaller $currentInstallRoot
  if(Get-ChildItem -LiteralPath $appHome -File -Recurse|Select-String -SimpleMatch $bearer -ErrorAction SilentlyContinue){throw 'Plain MCP bearer token leaked after upgrade'}
  $result=[ordered]@{schemaVersion=1;result='passed';previousVersion=$previousVersion.ToString();currentVersion=$currentVersion.ToString();previousSha256=(Get-FileHash -LiteralPath $previous -Algorithm SHA256).Hash.ToLowerInvariant();currentSha256=(Get-FileHash -LiteralPath $current -Algorithm SHA256).Hash.ToLowerInvariant();checks=@('current-user-install','first-start-bundled-runtime','upgrade-state-retention','safe-storage-retention','safe-uninstall','reinstall-recovery')}
  Write-Output ($result|ConvertTo-Json -Compress)
}finally{
  if($http-and-not$http.HasExited){Stop-Process -Id $http.Id -Force -ErrorAction SilentlyContinue}
  'AGENT_W7_EXECUTABLE','AGENT_W7_EXPECT_LOG','AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_SMOKE_LOG','AGENT_W3_FIXTURE_TOKEN','AGENT_DESKTOP_W3_SMOKE','AGENT_DESKTOP_NODE','AGENT_DESKTOP_W3_FIXTURE','AGENT_DESKTOP_W3_HTTP_ENDPOINT','AGENT_DESKTOP_W3_BEARER','AGENT_DESKTOP_W5_SMOKE','AGENT_DESKTOP_W5_SOURCE_V1','AGENT_DESKTOP_W5_SOURCE_V2','AGENT_DESKTOP_W7_SMOKE','AGENT_DESKTOP_W7_UPGRADE_VERIFY','AGENT_DESKTOP_W7_EXPECTED_VERSION'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
  if(Test-Path -LiteralPath $testRoot){Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue}
}
