param(
  [string]$JavaHome='D:\91.ProgramFiles\Java\jdk-21.0.12',
  [switch]$SkipBuild,
  [switch]$SkipWeek5Regression
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

  $appHome=Join-Path ([IO.Path]::GetTempPath()) ('agent-week6-'+[Guid]::NewGuid().ToString('N'))
  $workspace=Join-Path $appHome 'workspace';$builtin=Join-Path $appHome 'builtin-skills';$skill=Join-Path $builtin 'week6-safe-script';$scripts=Join-Path $skill 'scripts'
  $workspace,$scripts|ForEach-Object{New-Item -ItemType Directory -Path $_ -Force|Out-Null}
  $nl=[Environment]::NewLine
  Set-Content -LiteralPath (Join-Path $skill 'SKILL.md') -Encoding UTF8 -Value ('---'+$nl+'name: week6-safe-script'+$nl+'description: Run the safe Week 6 approval fixture.'+$nl+'---'+$nl+'Use scripts/safe.ps1 only for the Week 6 fixture.'+$nl)
  $scriptPath=Join-Path $scripts 'safe.ps1'
  Set-Content -LiteralPath $scriptPath -Encoding UTF8 -Value ("param([string]`$Marker)"+$nl+"Set-Content -LiteralPath `$Marker -Value 'executed'"+$nl+"Write-Output 'SAFE_SUMMARY'"+$nl)
  $mcpFixture=Join-Path $workspace 'mcp-week6-fixture.mjs';Copy-Item -LiteralPath (Join-Path $root 'scripts\mcp-week3-fixture.mjs') -Destination $mcpFixture
  $dangerMarker=Join-Path $workspace 'danger-marker.txt'

  $electron=Join-Path $root 'node_modules\electron\dist\electron.exe';$hostJar=Join-Path $root 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'
  $java=Join-Path $JavaHome 'bin\java.exe';if(-not(Test-Path $java)){$java=(Get-Command java).Source}
  $env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_W6_SMOKE='1';$env:AGENT_DESKTOP_W4_RENDERER_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000'
  $env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_JAVA=$java;$env:AGENT_DESKTOP_HOST_JAR=$hostJar;$env:AGENT_DESKTOP_BUILTIN_SKILLS=$builtin
  $env:AGENT_DESKTOP_NODE=(Get-Command node).Source;$env:AGENT_DESKTOP_W6_MCP_FIXTURE=$mcpFixture;$env:AGENT_DESKTOP_W6_SCRIPT=$scriptPath;$env:AGENT_W6_DANGER_MARKER=$dangerMarker
  $env:AGENT_W6_ELECTRON=$electron;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
  node scripts\verify-week6-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 6 Electron smoke failed'}
  if(Test-Path -LiteralPath (Join-Path $workspace 'restart-late.txt')){throw 'Pending write executed after Host abort'}
  $env:AGENT_DESKTOP_W6_RESTORE='1';node scripts\verify-week6-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 6 restart smoke failed'}
  if(Test-Path -LiteralPath (Join-Path $workspace 'restart-late.txt')){throw 'Pending write resumed after Host restart'}

  $preload=Get-Content -LiteralPath 'desktop-shell\src\preload.cts' -Raw
  foreach($forbidden in @('readFile','writeFile','executeCommand','approveAll','manager.call')){if($preload.Contains($forbidden)){throw 'Preload exposes a forbidden generic capability'}}
  if(-not $SkipWeek5Regression){
    'AGENT_DESKTOP_W6_SMOKE','AGENT_DESKTOP_W6_RESTORE','AGENT_DESKTOP_W6_MCP_FIXTURE','AGENT_DESKTOP_W6_SCRIPT','AGENT_W6_DANGER_MARKER','AGENT_W6_ELECTRON'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
    & powershell -ExecutionPolicy Bypass -File .\scripts\verify-week5.ps1 -JavaHome $JavaHome -SkipBuild
    if($LASTEXITCODE-ne 0){throw 'Week 5 regression failed'}
  }
  $regressionLabel=if($SkipWeek5Regression){'Week 5 regression skipped'}else{'Week 5 regression passed'}
  Write-Host ('Week 6 verification passed: Renderer approval, once/thread/deny, fail-closed files, Skill script pinning, MCP permission, restart invalidation; '+$regressionLabel+'.')
} finally {
  'AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W6_SMOKE','AGENT_DESKTOP_W6_RESTORE','AGENT_DESKTOP_W4_RENDERER_SMOKE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_JAVA','AGENT_DESKTOP_HOST_JAR','AGENT_DESKTOP_BUILTIN_SKILLS','AGENT_DESKTOP_NODE','AGENT_DESKTOP_W6_MCP_FIXTURE','AGENT_DESKTOP_W6_SCRIPT','AGENT_W6_DANGER_MARKER','AGENT_W6_ELECTRON','AGENT_DESKTOP_SMOKE_LOG'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
  if($appHome-and(Test-Path -LiteralPath $appHome)){Remove-Item -LiteralPath $appHome -Recurse -Force -ErrorAction SilentlyContinue}
  Pop-Location
}
