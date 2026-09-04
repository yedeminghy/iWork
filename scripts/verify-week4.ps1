param([string]$JavaHome='D:\91.ProgramFiles\Java\jdk-21.0.12',[switch]$SkipBuild,[switch]$SkipWeek3Regression)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
$appHome=$null
try {
  $processEnvironment=[Environment]::GetEnvironmentVariables('Process');$pathValue=$null
  foreach($key in $processEnvironment.Keys){if([string]$key -ceq 'Path'){$pathValue=[string]$processEnvironment[$key];break};if([string]$key -ceq 'PATH'){$pathValue=[string]$processEnvironment[$key]}}
  [Environment]::SetEnvironmentVariable('PATH',$null,'Process');[Environment]::SetEnvironmentVariable('Path',$null,'Process');[Environment]::SetEnvironmentVariable('Path',$pathValue,'Process')
  if(-not $SkipBuild){
    & mvn clean verify;if($LASTEXITCODE-ne 0){throw 'Maven verification failed'}
    & npm run typecheck;if($LASTEXITCODE-ne 0){throw 'TypeScript verification failed'}
    & npm run build;if($LASTEXITCODE-ne 0){throw 'Desktop build failed'}
  }
  $appHome=Join-Path ([IO.Path]::GetTempPath()) ('agent-week4-'+[Guid]::NewGuid().ToString('N'))
  $builtinSkillRoot=Join-Path $appHome 'builtin-skills';$userSkillRoot=Join-Path $appHome 'skills';$workspaceSkillRoot=Join-Path $appHome 'workspace\.agent-desktop\skills'
  $builtinSkillRoot,$userSkillRoot,$workspaceSkillRoot|ForEach-Object{New-Item -ItemType Directory -Path $_ -Force|Out-Null}
  function Write-Skill([string]$root,[string]$name,[string]$description,[string]$body){
    $directory=Join-Path $root $name;New-Item -ItemType Directory -Path $directory -Force|Out-Null
    $nl=[Environment]::NewLine
    Set-Content -LiteralPath (Join-Path $directory 'SKILL.md') -Encoding UTF8 -Value ('---'+$nl+'name: '+$name+$nl+'description: '+$description+$nl+'---'+$nl+$body+$nl)
    return $directory
  }
  Write-Skill $workspaceSkillRoot 'explicit-summary' 'Summarize content only when the user explicitly selects this Skill.' 'EXPLICIT_BODY_MARKER <img src=x onerror="window.__week4SkillExecuted=true"><script>window.__week4SkillExecuted=true</script><a href="https://invalid.example">link</a>'|Out-Null
  Write-Skill $workspaceSkillRoot 'implicit-code-review' 'Review source code when the user requests a code review.' 'IMPLICIT_BODY_MARKER'|Out-Null
  $resourceSkill=Write-Skill $workspaceSkillRoot 'resource-on-demand' 'Load one reference only when the request needs that reference.' 'Use references/needed.md only when required.'
  New-Item -ItemType Directory -Path (Join-Path $resourceSkill 'references')|Out-Null
  Set-Content -LiteralPath (Join-Path $resourceSkill 'references\needed.md') -Encoding UTF8 -Value 'RESOURCE_NEEDED_MARKER'
  Set-Content -LiteralPath (Join-Path $resourceSkill 'references\unused.md') -Encoding UTF8 -Value 'RESOURCE_UNUSED_MARKER'
  $scriptSkill=Write-Skill $workspaceSkillRoot 'script-blocked' 'Inspect a script as text without executing it.' 'The script is review-only.'
  New-Item -ItemType Directory -Path (Join-Path $scriptSkill 'scripts')|Out-Null
  Set-Content -LiteralPath (Join-Path $scriptSkill 'scripts\run.js') -Encoding UTF8 -Value "require('fs').writeFileSync('marker.txt','executed')"
  Write-Skill $builtinSkillRoot 'scope-priority' 'Built-in shadow fixture.' 'BUILTIN_SHADOW_MARKER'|Out-Null
  Write-Skill $userSkillRoot 'scope-priority' 'User shadow fixture.' 'USER_SHADOW_MARKER'|Out-Null
  Write-Skill $workspaceSkillRoot 'scope-priority' 'Workspace shadow fixture.' 'WORKSPACE_SHADOW_MARKER'|Out-Null

  $electron=Join-Path $root 'node_modules\electron\dist\electron.exe';$hostJar=Join-Path $root 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'
  $java=Join-Path $JavaHome 'bin\java.exe';if(-not(Test-Path $java)){$java=(Get-Command java).Source}
  $env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_W4_SMOKE='1';$env:AGENT_DESKTOP_W4_RENDERER_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000'
  $env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_JAVA=$java;$env:AGENT_DESKTOP_HOST_JAR=$hostJar;$env:AGENT_DESKTOP_BUILTIN_SKILLS=$builtinSkillRoot
  $env:AGENT_W4_ELECTRON=$electron;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
  node scripts\verify-week4-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 4 Electron smoke failed'}
  $env:AGENT_DESKTOP_W4_RESTORE='1';node scripts\verify-week4-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 4 restart smoke failed'}
  if(Test-Path (Join-Path $root 'marker.txt')){throw 'A Skill script was executed'}
  $persisted=Get-ChildItem (Join-Path $appHome 'data'),(Join-Path $appHome 'electron'),(Join-Path $appHome 'smoke.log') -File -Recurse -ErrorAction SilentlyContinue|Select-String -SimpleMatch 'EXPLICIT_BODY_MARKER','IMPLICIT_BODY_MARKER','RESOURCE_NEEDED_MARKER','RESOURCE_UNUSED_MARKER','BUILTIN_SHADOW_MARKER','USER_SHADOW_MARKER','WORKSPACE_SHADOW_MARKER' -ErrorAction SilentlyContinue
  if($persisted){throw 'Skill instructions or resource content leaked into persistence'}
  if(-not $SkipWeek3Regression){
    # The child regression must not inherit Week 4 fixture routing or restore mode.
    'AGENT_DESKTOP_W4_SMOKE','AGENT_DESKTOP_W4_RESTORE','AGENT_W4_ELECTRON'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
    & powershell -ExecutionPolicy Bypass -File .\scripts\verify-week3.ps1 -JavaHome $JavaHome -SkipBuild
    if($LASTEXITCODE-ne 0){throw 'Week 3 regression failed'}
  }
  Write-Host 'Week 4 verification passed: catalog, explicit/implicit activation, progressive resources and restart.'
} finally {
  'AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W4_SMOKE','AGENT_DESKTOP_W4_RENDERER_SMOKE','AGENT_DESKTOP_W4_RESTORE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_JAVA','AGENT_DESKTOP_HOST_JAR','AGENT_DESKTOP_BUILTIN_SKILLS','AGENT_W4_ELECTRON','AGENT_DESKTOP_SMOKE_LOG'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}
  if($appHome-and(Test-Path $appHome)){Remove-Item $appHome -Recurse -Force -ErrorAction SilentlyContinue}
  Pop-Location
}
