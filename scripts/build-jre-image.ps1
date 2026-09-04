param([Parameter(Mandatory=$true)][string]$StageDirectory,[string]$JdkHome,[ValidateSet('internal','beta','production')][string]$Channel='internal')
$ErrorActionPreference='Stop'
function Get-Sha256([string]$Path){
  $algorithm=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path)
  try{return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
  finally{$stream.Dispose();$algorithm.Dispose()}
}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stage=[IO.Path]::GetFullPath($StageDirectory)
$packaging=[IO.Path]::GetFullPath((Join-Path $root 'packaging'))
if(-not $stage.StartsWith($packaging,[StringComparison]::OrdinalIgnoreCase)){throw 'Stage directory must be below packaging'}
$lock=Get-Content (Join-Path $packaging 'runtime-lock.json') -Raw -Encoding UTF8|ConvertFrom-Json
if($Channel -eq 'production' -and (-not $lock.downloadUrl -or -not $lock.archiveSha256)){throw 'Production runtime lock requires archive URL and SHA-256'}
if(-not $JdkHome){$java=(Get-Command java.exe -ErrorAction Stop).Source;$JdkHome=Split-Path (Split-Path $java -Parent) -Parent}
$JdkHome=[IO.Path]::GetFullPath($JdkHome);$release=Join-Path $JdkHome 'release'
if(-not (Test-Path -LiteralPath $release)){throw 'Locked JDK release file is missing'}
$releaseText=Get-Content -LiteralPath $release -Raw
if($releaseText -notmatch ('JAVA_VERSION="'+[regex]::Escape($lock.version)+'"') -or $releaseText -notmatch ('IMPLEMENTOR="'+[regex]::Escape($lock.vendor)+'"')){throw 'JDK vendor or version does not match runtime lock'}
$releaseHash=Get-Sha256 $release
if($releaseHash -ne $lock.releaseSha256){throw 'JDK release hash does not match runtime lock'}
$jar=Join-Path $stage 'agent-host.jar';if(-not (Test-Path -LiteralPath $jar)){throw 'Staged Host JAR is missing'}
$detected=& (Join-Path $JdkHome 'bin\jdeps.exe') --multi-release 21 --ignore-missing-deps --print-module-deps $jar
if($LASTEXITCODE -ne 0){throw 'jdeps failed'}
$manual=Get-Content (Join-Path $packaging 'jre-modules.txt')|Where-Object {$_ -and -not $_.StartsWith('#')}
$modules=@($detected -split ',')+$manual|ForEach-Object {$_.Trim()}|Where-Object {$_}|Sort-Object -Unique
$runtime=Join-Path $stage 'jre';if(Test-Path -LiteralPath $runtime){Remove-Item -LiteralPath $runtime -Recurse -Force}
& (Join-Path $JdkHome 'bin\jlink.exe') --add-modules ($modules -join ',') --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
if($LASTEXITCODE -ne 0){throw 'jlink failed'}
& (Join-Path $runtime 'bin\java.exe') --list-modules|Out-Null
if($LASTEXITCODE -ne 0){throw 'Packaged runtime smoke failed'}
Write-Output ('Runtime modules: '+($modules -join ','))
