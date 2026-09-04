param(
    [string]$JavaHome = 'D:\91.ProgramFiles\Java\jdk-21.0.12',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$electron = Join-Path $projectRoot 'node_modules\electron\dist\electron.exe'
$hostJar = Join-Path $projectRoot 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $electron)) { throw "Electron runtime not found: $electron" }
if (-not (Test-Path -LiteralPath $hostJar)) { throw "Host JAR not found: $hostJar" }

function Invoke-DesktopSmoke([ValidateSet('normal','interrupt','restore')][string]$mode) {
    $runId = [Guid]::NewGuid().ToString('N')
    $stdout = Join-Path ([System.IO.Path]::GetTempPath()) "agent-w2-$runId.out.log"
    $stderr = Join-Path ([System.IO.Path]::GetTempPath()) "agent-w2-$runId.err.log"
    $state = Join-Path ([System.IO.Path]::GetTempPath()) "agent-w2-$runId.state.log"
    try {
        $env:AGENT_DESKTOP_SMOKE = '1'; $env:AGENT_DESKTOP_W2_SMOKE = if ($mode -eq 'normal') { '1' } else { '0' }
        $env:AGENT_DESKTOP_W2_RESTORE = if ($mode -eq 'restore') { '1' } else { '0' }; $env:AGENT_DESKTOP_W2_INTERRUPT = if ($mode -eq 'interrupt') { '1' } else { '0' }
        $env:AGENT_DESKTOP_TEST_FIXTURES = '1'; $env:AGENT_DESKTOP_SMOKE_LOG = $state
        $env:AGENT_W2_ELECTRON = $electron
        $previousErrorAction = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        node scripts\verify-week2-electron.mjs 1>$stdout 2>$stderr; $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorAction
        $log = if (Test-Path $state) { Get-Content -LiteralPath $state -Raw } else { '' }
        if ($exitCode -ne 0 -or $log -notmatch '(?m) smoke-passed\s*$') { throw "Week 2 desktop smoke failed (exit=$exitCode): $(Get-Content -LiteralPath $stderr -Raw)" }
        if ($mode -eq 'normal' -and $log -notmatch 'week2-cancelled') { throw 'Cancellation was not verified by the first launch' }
        if ($mode -eq 'interrupt' -and $log -notmatch 'week2-interrupted') { throw 'Host interruption was not verified by the second launch' }
    } finally { Remove-Item -LiteralPath $stdout,$stderr,$state -Force -ErrorAction SilentlyContinue }
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        mvn clean verify; if ($LASTEXITCODE -ne 0) { throw "Maven verification failed: $LASTEXITCODE" }
        npm run typecheck; if ($LASTEXITCODE -ne 0) { throw "TypeScript verification failed: $LASTEXITCODE" }
        npm run build; if ($LASTEXITCODE -ne 0) { throw "Desktop build failed: $LASTEXITCODE" }
    }
    $appHome = Join-Path ([System.IO.Path]::GetTempPath()) ("agent-week2-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $appHome | Out-Null
    try {
        $env:AGENT_DESKTOP_APP_HOME = $appHome; $env:AGENT_DESKTOP_JAVA = Join-Path $JavaHome 'bin\java.exe'; $env:AGENT_DESKTOP_HOST_JAR = $hostJar
        Invoke-DesktopSmoke normal
        Invoke-DesktopSmoke interrupt
        Invoke-DesktopSmoke restore
        $forbidden = Get-ChildItem -LiteralPath $appHome -Recurse -File | Select-String -Pattern 'Authorization\s*[:=]|reasoning\s*[:=]|api[_-]?key\s*[:=]' -CaseSensitive:$false
        if ($forbidden) { throw 'Sensitive test data was found in the Week 2 appHome' }
        Write-Host 'Week 2 verification passed: tool timeline, cancellation, host interruption recovery, build and sensitive-data scan.'
    } finally { Remove-Item -LiteralPath $appHome -Recurse -Force -ErrorAction SilentlyContinue }
} finally {
    'AGENT_DESKTOP_SMOKE','AGENT_DESKTOP_W2_SMOKE','AGENT_DESKTOP_W2_RESTORE','AGENT_DESKTOP_W2_INTERRUPT','AGENT_DESKTOP_TEST_FIXTURES','AGENT_DESKTOP_SMOKE_LOG','AGENT_DESKTOP_APP_HOME','AGENT_DESKTOP_JAVA','AGENT_DESKTOP_HOST_JAR','AGENT_W2_ELECTRON' | ForEach-Object { Remove-Item "Env:$_" -ErrorAction SilentlyContinue }
    Pop-Location
}
