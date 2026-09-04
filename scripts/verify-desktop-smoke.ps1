param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk21.0.10',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$electron = Join-Path $projectRoot 'node_modules\electron\dist\electron.exe'
$hostJar = Join-Path $projectRoot 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path -LiteralPath $electron)) { throw "Electron runtime not found: $electron" }
if (-not (Test-Path -LiteralPath $hostJar)) { throw "Host JAR not found: $hostJar" }

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        npm run typecheck
        if ($LASTEXITCODE -ne 0) { throw "TypeScript typecheck failed with exit code $LASTEXITCODE" }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Desktop build failed with exit code $LASTEXITCODE" }
    }

    $env:AGENT_DESKTOP_SMOKE = '1'
    $env:AGENT_DESKTOP_TEST_FIXTURES = '1'
    $env:AGENT_DESKTOP_JAVA = Join-Path $JavaHome 'bin\java.exe'
    $env:AGENT_DESKTOP_HOST_JAR = $hostJar
    $runId = [Guid]::NewGuid().ToString('N')
    $appHome = Join-Path ([System.IO.Path]::GetTempPath()) "agent-desktop-smoke-home-$runId"
    New-Item -ItemType Directory -Path $appHome -Force | Out-Null
    $stdout = Join-Path ([System.IO.Path]::GetTempPath()) "agent-desktop-smoke-$runId.out.log"
    $stderr = Join-Path ([System.IO.Path]::GetTempPath()) "agent-desktop-smoke-$runId.err.log"
    $smokeLog = Join-Path ([System.IO.Path]::GetTempPath()) "agent-desktop-smoke-$runId.state.log"
    try {
        $env:AGENT_DESKTOP_SMOKE_LOG = $smokeLog
        $env:AGENT_DESKTOP_APP_HOME = $appHome
        $startParameters = @{
            FilePath=$electron; ArgumentList='desktop-shell\dist\main.js'; WorkingDirectory=$projectRoot
            WindowStyle='Hidden'; PassThru=$true
            RedirectStandardOutput=$stdout; RedirectStandardError=$stderr
        }
        $process = Start-Process @startParameters
        if (-not $process.WaitForExit(90000)) {
            $process.Kill()
            throw 'Desktop smoke verification exceeded 30 seconds'
        }
        $process.WaitForExit()
        $process.Refresh()
        $exitCode = $process.ExitCode
        if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout }
        if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr }
        if (Test-Path -LiteralPath $smokeLog) { Get-Content -LiteralPath $smokeLog }
        $smokePassed = (Test-Path -LiteralPath $smokeLog) -and
            ((Get-Content -LiteralPath $smokeLog -Raw) -match '(?m) smoke-passed\s*$')
        if (-not $smokePassed) {
            if ($null -ne $exitCode) { throw "Desktop smoke verification failed with exit code $exitCode" }
            throw 'Desktop smoke verification did not report success'
        }
        if (($null -ne $exitCode) -and ($exitCode -ne 0)) {
            throw "Desktop smoke verification failed with exit code $exitCode"
        }
    }
    finally {
        foreach ($temporaryFile in @($stdout, $stderr, $smokeLog)) {
            for ($attempt = 0; $attempt -lt 10 -and (Test-Path -LiteralPath $temporaryFile); $attempt++) {
                Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
                if (Test-Path -LiteralPath $temporaryFile) { Start-Sleep -Milliseconds 100 }
            }
        }
        if (Test-Path -LiteralPath $appHome) { Remove-Item -LiteralPath $appHome -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
finally {
    Remove-Item Env:AGENT_DESKTOP_SMOKE -ErrorAction SilentlyContinue
    Remove-Item Env:AGENT_DESKTOP_TEST_FIXTURES -ErrorAction SilentlyContinue
    Remove-Item Env:AGENT_DESKTOP_JAVA -ErrorAction SilentlyContinue
    Remove-Item Env:AGENT_DESKTOP_HOST_JAR -ErrorAction SilentlyContinue
    Remove-Item Env:AGENT_DESKTOP_SMOKE_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:AGENT_DESKTOP_APP_HOME -ErrorAction SilentlyContinue
    Pop-Location
}
