param([string]$JavaHome='D:\91.ProgramFiles\Java\jdk-21.0.12',[switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  # Windows environment blocks are case-insensitive, but some launchers can expose
  # both PATH and Path. PowerShell 5 Start-Process rejects that duplicate pair.
  $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
  $pathValue = $null
  foreach ($key in $processEnvironment.Keys) {
    if ([string]$key -ceq 'Path') { $pathValue = [string]$processEnvironment[$key]; break }
    if ([string]$key -ceq 'PATH') { $pathValue = [string]$processEnvironment[$key] }
  }
  [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
  [Environment]::SetEnvironmentVariable('Path', $null, 'Process')
  [Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')
  if (-not $SkipBuild) {
    & mvn clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }
    & npm run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript verification failed' }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed' }
  }
  $electron=Join-Path $root 'node_modules\electron\dist\electron.exe';$hostJar=Join-Path $root 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar';$sourceFixture=Join-Path $root 'scripts\mcp-week3-fixture.mjs';$appHome=Join-Path ([IO.Path]::GetTempPath()) ('agent-week3-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $appHome|Out-Null;$workspace=Join-Path $appHome 'workspace';New-Item -ItemType Directory -Path $workspace|Out-Null;$fixture=Join-Path $workspace 'mcp-week3-fixture.mjs';Copy-Item -LiteralPath $sourceFixture -Destination $fixture
  $bearer='w3-fixture-'+[Guid]::NewGuid().ToString('N');$env:AGENT_W3_FIXTURE_TOKEN=$bearer;$http=Start-Process -FilePath (Get-Command node).Source -ArgumentList @($fixture,'http') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $appHome 'fixture.out') -RedirectStandardError (Join-Path $appHome 'fixture.err');
  try{
    $deadline=[DateTime]::UtcNow.AddSeconds(10);do{Start-Sleep -Milliseconds 100;$ready=if(Test-Path (Join-Path $appHome 'fixture.out')){Get-Content (Join-Path $appHome 'fixture.out') -First 1}else{$null}}until($ready-or[DateTime]::UtcNow-gt$deadline);if(!$ready){throw 'HTTP MCP fixture did not start'};$port=($ready|ConvertFrom-Json).port
    $env:AGENT_DESKTOP_SMOKE='1';$env:AGENT_DESKTOP_W3_SMOKE='1';$env:AGENT_DESKTOP_TEST_FIXTURES='1';$env:AGENT_DESKTOP_HOST_READY_TIMEOUT_MS='180000';$env:AGENT_DESKTOP_APP_HOME=$appHome;$env:AGENT_DESKTOP_JAVA=Join-Path $JavaHome 'bin\java.exe';$env:AGENT_DESKTOP_HOST_JAR=$hostJar;$env:AGENT_DESKTOP_NODE=(Get-Command node).Source;$env:AGENT_DESKTOP_W3_FIXTURE=$fixture;$env:AGENT_DESKTOP_W3_HTTP_ENDPOINT="http://127.0.0.1:$port/mcp";$env:AGENT_DESKTOP_W3_BEARER=$bearer;$env:AGENT_W3_ELECTRON=$electron;$env:AGENT_DESKTOP_SMOKE_LOG=Join-Path $appHome 'smoke.log'
    node scripts\verify-week3-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 3 Electron smoke failed'};$env:AGENT_DESKTOP_W3_RESTORE='1';node scripts\verify-week3-electron.mjs;if($LASTEXITCODE-ne 0){throw 'Week 3 restart smoke failed'}
  }finally{if($http-and !$http.HasExited){Stop-Process -Id $http.Id -Force};'AGENT_W3_FIXTURE_TOKEN','AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W3_SMOKE','AGENT_DESKTOP_W3_RESTORE','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_HOST_READY_TIMEOUT_MS','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_JAVA','AGENT_DESKTOP_HOST_JAR','AGENT_DESKTOP_NODE','AGENT_DESKTOP_W3_FIXTURE','AGENT_DESKTOP_W3_HTTP_ENDPOINT','AGENT_DESKTOP_W3_BEARER','AGENT_W3_ELECTRON','AGENT_DESKTOP_SMOKE_LOG'|ForEach-Object{Remove-Item "Env:$_" -ErrorAction SilentlyContinue}}
  $forbidden = 'Authorization:\s*Bearer\s+(?!\[REDACTED\])\S+|Cookie:\s*\S+|reasoning\s*[=:]'
  $scan = Get-ChildItem agent-mcp,agent-host,agent-persistence,agent-runtime,desktop-shell,desktop-ui -File -Recurse |
    Where-Object { $_.FullName -notmatch '[\\/](node_modules|target|dist)[\\/]' } |
    Where-Object { $_.FullName -notmatch '[\\/]src[\\/]test[\\/]' } |
    Select-String -Pattern $forbidden -ErrorAction SilentlyContinue
  if ($scan) { throw 'Sensitive-data scan failed' };$diskLeak=Get-ChildItem $appHome -File -Recurse|Select-String -SimpleMatch $bearer -ErrorAction SilentlyContinue;if($diskLeak){throw 'Plain MCP bearer token was persisted'}
  Write-Host 'Week 3 verification passed: Java, TypeScript, desktop build and sensitive-data scan.'
} finally { if($appHome-and(Test-Path $appHome)){Remove-Item $appHome -Recurse -Force -ErrorAction SilentlyContinue};Pop-Location }
