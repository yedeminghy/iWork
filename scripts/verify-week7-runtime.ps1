param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolvedExecutable=[IO.Path]::GetFullPath($Executable)
if(-not(Test-Path -LiteralPath $resolvedExecutable)){throw 'Week 7 executable is missing'}
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('agent-week7-runtime-'+[Guid]::NewGuid().ToString('N'))
$passed=$false
try{
  $appHome=Join-Path $testRoot 'host-recovery';$workspace=Join-Path $appHome 'workspace';$source=Join-Path $testRoot 'week7-no-replay';$scripts=Join-Path $source 'scripts'
  $workspace,$scripts|ForEach-Object{New-Item -ItemType Directory -Path $_ -Force|Out-Null}
  $nl=[Environment]::NewLine
  Set-Content -LiteralPath (Join-Path $source 'SKILL.md') -Encoding UTF8 -Value ('---'+$nl+'name: week7-no-replay'+$nl+'description: Verify that a pending Skill script is never replayed after recovery.'+$nl+'---'+$nl+'Use scripts/safe.ps1 only for the Week 7 recovery fixture.'+$nl)
  Set-Content -LiteralPath (Join-Path $scripts 'safe.ps1') -Encoding UTF8 -Value ("param([string]`$Marker)"+$nl+"Set-Content -LiteralPath `$Marker -Value 'executed'"+$nl+"Write-Output 'W7_SAFE_SUMMARY'"+$nl)
  $fixture=Join-Path $workspace 'mcp-week7-fixture.mjs';Copy-Item -LiteralPath (Join-Path $root 'scripts\mcp-week3-fixture.mjs') -Destination $fixture
  $env:AGENT_W7_EXECUTABLE=$resolvedExecutable;$env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_W7_SMOKE='1';$env:AGENT_DESKTOP_W7_RENDERER_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000'
  $env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_NODE=(Get-Command node).Source;$env:AGENT_DESKTOP_W7_MCP_FIXTURE=$fixture;$env:AGENT_DESKTOP_W7_MCP_MARKER=Join-Path $workspace 'mcp-no-replay.txt'
  $env:AGENT_DESKTOP_W7_SKILL_SOURCE=$source;$env:AGENT_DESKTOP_W7_SCRIPT_MARKER='skill-no-replay.txt';$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
  $env:AGENT_W7_SECRET_SENTINEL='w7-secret-must-not-leak-7e1f4b';$env:AGENT_W7_REASONING_SENTINEL='w7-reasoning-must-not-leak-2d68a9';$env:AGENT_W7_EXPECT_LOG='week7-smoke-passed'
  & node (Join-Path $PSScriptRoot 'verify-week7-electron.mjs');if($LASTEXITCODE-ne 0){throw 'Week 7 Host/Renderer recovery smoke failed'}

  $mainHome=Join-Path $testRoot 'main-recovery';New-Item -ItemType Directory -Path $mainHome -Force|Out-Null
  $env:AGENT_DESKTOP_APP_HOME=$mainHome;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $mainHome 'smoke.log';$env:AGENT_DESKTOP_W7_MAIN_CRASH='1';$env:AGENT_W7_EXPECT_NATIVE_EXIT='77';$env:AGENT_W7_EXPECT_LOG='week7-main-crash-host-pid:'
  & node (Join-Path $PSScriptRoot 'verify-week7-electron.mjs');if($LASTEXITCODE-ne 0){throw 'Week 7 Main crash smoke failed'}
  Remove-Item Env:AGENT_DESKTOP_W7_MAIN_CRASH;Remove-Item Env:AGENT_W7_EXPECT_NATIVE_EXIT;$env:AGENT_DESKTOP_W7_MAIN_RESTORE='1';$env:AGENT_W7_EXPECT_LOG='week7-main-restored'
  & node (Join-Path $PSScriptRoot 'verify-week7-electron.mjs');if($LASTEXITCODE-ne 0){throw 'Week 7 Main recovery smoke failed'}
  $passed=$true
  Write-Output '{"result":"passed","checks":["renderer-recovery","host-bounded-restart","zero-replay-workspace-mcp-skill","main-parent-liveness","main-persisted-state-recovery","crash-loop"]}'
}finally{
  'AGENT_W7_EXECUTABLE','AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W7_SMOKE','AGENT_DESKTOP_W7_RENDERER_SMOKE','AGENT_DESKTOP_W7_MAIN_CRASH','AGENT_DESKTOP_W7_MAIN_RESTORE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_NODE','AGENT_DESKTOP_W7_MCP_FIXTURE','AGENT_DESKTOP_W7_MCP_MARKER','AGENT_DESKTOP_W7_SKILL_SOURCE','AGENT_DESKTOP_W7_SCRIPT_MARKER','AGENT_DESKTOP_SMOKE_LOG','AGENT_W7_SECRET_SENTINEL','AGENT_W7_REASONING_SENTINEL','AGENT_W7_EXPECT_NATIVE_EXIT','AGENT_W7_EXPECT_LOG'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
  if($passed){
    if(Test-Path -LiteralPath $testRoot){Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue}
  }else{
    Write-Warning "Week 7 runtime diagnostics preserved at $testRoot"
  }
}
