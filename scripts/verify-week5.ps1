param(
  [string]$JavaHome='D:\91.ProgramFiles\Java\jdk-21.0.12',
  [switch]$SkipBuild,
  [switch]$SkipWeek4Regression,
  [string]$ExternalSkillFixture
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$appHome=$null
Push-Location $root
try {
  $processEnvironment=[Environment]::GetEnvironmentVariables('Process');$pathValue=$null
  foreach($key in $processEnvironment.Keys){if([string]$key -ceq 'Path'){$pathValue=[string]$processEnvironment[$key];break};if([string]$key -ceq 'PATH'){$pathValue=[string]$processEnvironment[$key]}}
  [Environment]::SetEnvironmentVariable('PATH',$null,'Process');[Environment]::SetEnvironmentVariable('Path',$null,'Process');[Environment]::SetEnvironmentVariable('Path',$pathValue,'Process')
  if(-not $SkipBuild){
    & mvn clean verify;if($LASTEXITCODE-ne 0){throw 'Maven verification failed'}
    & npm run typecheck;if($LASTEXITCODE-ne 0){throw 'TypeScript verification failed'}
    & npm run build;if($LASTEXITCODE-ne 0){throw 'Desktop build failed'}
  }
  $appHome=Join-Path ([IO.Path]::GetTempPath()) ('agent-week5-'+[Guid]::NewGuid().ToString('N'))
  $builtin=Join-Path $appHome 'builtin-skills';$sourceRootV1=Join-Path $appHome 'external-v1';$sourceRootV2=Join-Path $appHome 'external-v2'
  $sourceV1=Join-Path $sourceRootV1 'managed-import';$sourceV2=Join-Path $sourceRootV2 'managed-import'
  $builtin,$sourceV1,$sourceV2|ForEach-Object{New-Item -ItemType Directory -Path $_ -Force|Out-Null}
  function Write-Fixture([string]$directory,[string]$marker){
    $nl=[Environment]::NewLine
    Set-Content -LiteralPath (Join-Path $directory 'SKILL.md') -Encoding UTF8 -Value ('---'+$nl+'name: managed-import'+$nl+'description: Verify safe local Skill import and rollback.'+$nl+'license: Apache-2.0'+$nl+'---'+$nl+$marker+$nl)
    New-Item -ItemType Directory -Path (Join-Path $directory 'scripts') -Force|Out-Null
    Set-Content -LiteralPath (Join-Path $directory 'scripts\run.js') -Encoding UTF8 -Value "require('fs').writeFileSync('week5-script-marker.txt','executed')"
    New-Item -ItemType Directory -Path (Join-Path $directory 'agents') -Force|Out-Null
    Set-Content -LiteralPath (Join-Path $directory 'agents\openai.yaml') -Encoding UTF8 -Value ('dependencies:'+$nl+'  tools:'+$nl+'    - type: mcp'+$nl+'      value: unavailable-fixture')
  }
  Write-Fixture $sourceV1 'W5_VERSION_ONE';Write-Fixture $sourceV2 'W5_VERSION_TWO'
  if($ExternalSkillFixture){if(-not(Test-Path -LiteralPath (Join-Path $ExternalSkillFixture 'SKILL.md'))){throw 'External Skill fixture does not contain SKILL.md'}}

  $electron=Join-Path $root 'node_modules\electron\dist\electron.exe';$hostJar=Join-Path $root 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'
  $java=Join-Path $JavaHome 'bin\java.exe';if(-not(Test-Path $java)){$java=(Get-Command java).Source}
  $env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_W5_SMOKE='1';$env:AGENT_DESKTOP_W4_RENDERER_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000'
  $env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_JAVA=$java;$env:AGENT_DESKTOP_HOST_JAR=$hostJar;$env:AGENT_DESKTOP_BUILTIN_SKILLS=$builtin
  $env:AGENT_DESKTOP_W5_SOURCE_V1=$sourceV1;$env:AGENT_DESKTOP_W5_SOURCE_V2=$sourceV2;$env:AGENT_W5_ELECTRON=$electron;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
  node scripts\verify-week5-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 5 Electron smoke failed'}
  $env:AGENT_DESKTOP_W5_RESTORE='1';node scripts\verify-week5-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 5 restart smoke failed'}
  if(-not(Test-Path -LiteralPath (Join-Path $sourceV1 'SKILL.md'))-or-not(Test-Path -LiteralPath (Join-Path $sourceV2 'SKILL.md'))){throw 'External local source was modified or removed'}
  if(Test-Path -LiteralPath (Join-Path $root 'week5-script-marker.txt')){throw 'A Skill script was executed'}
  $leakRoots=@((Join-Path $appHome 'data'),(Join-Path $appHome 'electron'),(Join-Path $appHome 'smoke.log'))|Where-Object{Test-Path -LiteralPath $_}
  $leaks=$leakRoots|ForEach-Object{Get-ChildItem -LiteralPath $_ -File -Recurse -ErrorAction SilentlyContinue}|Select-String -SimpleMatch 'W5_VERSION_ONE','W5_VERSION_TWO','unavailable-fixture' -ErrorAction SilentlyContinue
  if($leaks){throw 'Skill body or resource content leaked into persistence, logs, or Renderer state'}
  $preload=Get-Content -LiteralPath 'desktop-shell\src\preload.cts' -Raw
  foreach($forbidden in @('selectDirectory','readFile','download(','extractArchive','installFromPath')){if($preload.Contains($forbidden)){throw 'Preload exposes a forbidden generic import capability'}}
  if(-not $SkipWeek4Regression){
    'AGENT_DESKTOP_W5_SMOKE','AGENT_DESKTOP_W5_RESTORE','AGENT_DESKTOP_W5_SOURCE_V1','AGENT_DESKTOP_W5_SOURCE_V2','AGENT_W5_ELECTRON'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
    & powershell -ExecutionPolicy Bypass -File .\scripts\verify-week4.ps1 -JavaHome $JavaHome -SkipBuild
    if($LASTEXITCODE-ne 0){throw 'Week 4 regression failed'}
  }
  $regressionLabel=if($SkipWeek4Regression){'Week 4 regression skipped'}else{'Week 4 regression passed'}
  Write-Host ('Week 5 verification passed: local import, immutable preview, replacement, rollback, restart, managed removal; '+$regressionLabel+'.')
} finally {
  'AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W5_SMOKE','AGENT_DESKTOP_W5_RESTORE','AGENT_DESKTOP_W4_RENDERER_SMOKE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_JAVA','AGENT_DESKTOP_HOST_JAR','AGENT_DESKTOP_BUILTIN_SKILLS','AGENT_DESKTOP_W5_SOURCE_V1','AGENT_DESKTOP_W5_SOURCE_V2','AGENT_W5_ELECTRON','AGENT_DESKTOP_SMOKE_LOG'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
  if($appHome-and(Test-Path -LiteralPath $appHome)){Remove-Item -LiteralPath $appHome -Recurse -Force -ErrorAction SilentlyContinue}
  Pop-Location
}
