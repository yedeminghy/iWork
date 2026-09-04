param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk21.0.10',
    [string]$MavenHome = 'D:\03.Softwares\apache-maven-3.9.16',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaExe = Join-Path $JavaHome 'bin\java.exe'
$mavenExe = Join-Path $MavenHome 'bin\mvn.cmd'

if (-not (Test-Path -LiteralPath $javaExe)) { throw "JDK 21 not found: $javaExe" }
if (-not (Test-Path -LiteralPath $mavenExe)) { throw "Maven 3.9+ not found: $mavenExe" }

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$MavenHome\bin;$env:Path"

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & $mavenExe -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) { throw "Maven verification failed with exit code $LASTEXITCODE" }
    }

    $jar = Join-Path $projectRoot 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw "Host JAR not found: $jar" }

    $appHome = Join-Path ([System.IO.Path]::GetTempPath()) ("agent-desktop-verify-" + [Guid]::NewGuid().ToString('N'))
    [System.IO.Directory]::CreateDirectory($appHome) | Out-Null
    $random = New-Object byte[] 32
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $randomGenerator.GetBytes($random)
    $randomGenerator.Dispose()
    $token = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaExe
    $startInfo.Arguments = '-jar "' + $jar + '"'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo

    try {
        if (-not $process.Start()) { throw 'Unable to start Java Host' }
        $bootstrap = @{type='bootstrap'; protocolVersion=1; sessionToken=$token; appHome=$appHome} |
            ConvertTo-Json -Compress
        $process.StandardInput.WriteLine($bootstrap)
        $process.StandardInput.Close()

        $readyTask = $process.StandardOutput.ReadLineAsync()
        if (-not $readyTask.Wait(25000)) { throw 'Host did not emit ready within 25 seconds' }
        $ready = $readyTask.Result | ConvertFrom-Json
        if ($ready.type -ne 'ready' -or $ready.protocolVersion -ne 1) { throw 'Invalid ready message' }

        $headers = @{Authorization="Bearer $token"; 'X-App-Protocol'='1'}
        $baseUrl = "http://127.0.0.1:$($ready.port)"
        $health = Invoke-RestMethod -Uri "$baseUrl/internal/health" -Headers $headers
        if ($health.status -ne 'READY') { throw 'Health check did not return READY' }

        $request = @{
            threadId='thr_verify'; runId='run_verify'
            messages=@(@{id='msg_verify'; role='user'; content='hello'})
            context=@{turnId='trn_verify'; workspaceId='ws_default'; modelProfileId='mock'}
        } | ConvertTo-Json -Depth 6 -Compress
        $sseParameters = @{
            UseBasicParsing=$true; Method='Post'; Uri="$baseUrl/agui/run"; Headers=$headers
            ContentType='application/json'; Body=$request
        }
        $sse = Invoke-WebRequest @sseParameters
        foreach ($eventName in @('RUN_STARTED', 'TEXT_MESSAGE_CONTENT', 'TEXT_MESSAGE_END', 'RUN_FINISHED')) {
            if ($sse.Content -notmatch $eventName) { throw "SSE response is missing $eventName" }
        }

        $items = Invoke-RestMethod -Uri "$baseUrl/api/v1/threads/thr_verify/items" -Headers $headers
        if (@($items).Count -lt 4) { throw 'Persisted timeline is incomplete' }

        Invoke-RestMethod -Method Post -Uri "$baseUrl/internal/shutdown" -Headers $headers | Out-Null
        if (-not $process.WaitForExit(5000)) { throw 'Host did not stop gracefully within 5 seconds' }
        Write-Host 'Phase 1 host verification passed: build, bootstrap, auth, SSE, persistence, shutdown.'
    }
    finally {
        if ($process -and -not $process.HasExited) { $process.Kill() }
        if (Test-Path -LiteralPath $appHome) { Remove-Item -LiteralPath $appHome -Recurse -Force }
    }
}
finally {
    Pop-Location
}
