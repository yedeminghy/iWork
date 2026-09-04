param([ValidateSet('internal','beta','production')][string]$Channel='internal',[string]$JdkHome,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
function Get-Sha256([string]$Path){
  $algorithm=[Security.Cryptography.SHA256]::Create();$stream=[IO.File]::OpenRead($Path)
  try{return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
  finally{$stream.Dispose();$algorithm.Dispose()}
}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$stage=[IO.Path]::GetFullPath((Join-Path $root 'packaging\stage'));$output=[IO.Path]::GetFullPath((Join-Path $root 'dist-release'))
if((Split-Path $output -Parent) -ne $root -or (Split-Path $stage -Parent) -ne (Join-Path $root 'packaging')){throw 'Unsafe release output path'}
$pkg=Get-Content (Join-Path $root 'package.json') -Raw -Encoding UTF8|ConvertFrom-Json
$shell=Get-Content (Join-Path $root 'desktop-shell\package.json') -Raw -Encoding UTF8|ConvertFrom-Json
$ui=Get-Content (Join-Path $root 'desktop-ui\package.json') -Raw -Encoding UTF8|ConvertFrom-Json
$pom=Get-Content (Join-Path $root 'pom.xml') -Raw -Encoding UTF8
if($pkg.version -ne $shell.version -or $pkg.version -ne $ui.version -or $pom -notmatch ('<agentdesktop.release.version>'+[regex]::Escape($pkg.version)+'</agentdesktop.release.version>')){throw 'Release versions are inconsistent'}
& node (Join-Path $PSScriptRoot 'verify-legal-release.mjs');if($LASTEXITCODE -ne 0){throw 'Legal release materials are not approved'}
Push-Location $root
try{
  if(-not $SkipBuild){
    & mvn -q clean verify;if($LASTEXITCODE -ne 0){throw 'Maven verification failed'}
    & npm ci;if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
    & npm run typecheck;if($LASTEXITCODE -ne 0){throw 'Typecheck failed'}
    foreach($buildDirectory in @((Join-Path $root 'desktop-ui\dist'),(Join-Path $root 'desktop-shell\dist'))){if(Test-Path -LiteralPath $buildDirectory){Remove-Item -LiteralPath $buildDirectory -Recurse -Force}}
    & npm run build;if($LASTEXITCODE -ne 0){throw 'Desktop build failed'}
  }
  if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force};New-Item -ItemType Directory -Path $stage|Out-Null
  Copy-Item -LiteralPath (Join-Path $root 'agent-host\target\agent-host-0.1.0-SNAPSHOT.jar') -Destination (Join-Path $stage 'agent-host.jar')
  Copy-Item -LiteralPath (Join-Path $root 'skills') -Destination (Join-Path $stage 'skills') -Recurse
  Copy-Item -LiteralPath (Join-Path $root 'packaging\provider-catalog.json') -Destination (Join-Path $stage 'provider-catalog.json')
  Copy-Item -LiteralPath (Join-Path $root 'packaging\initial-mcp-catalog.json') -Destination (Join-Path $stage 'initial-mcp-catalog.json')
  Copy-Item -LiteralPath (Join-Path $root 'packaging\THIRD_PARTY_NOTICES.txt') -Destination $stage
  & (Join-Path $PSScriptRoot 'build-jre-image.ps1') -StageDirectory $stage -JdkHome $JdkHome -Channel $Channel
  & node (Join-Path $PSScriptRoot 'generate-release-manifest.mjs') $stage $Channel;if($LASTEXITCODE -ne 0){throw 'Release manifest generation failed'}
  if(Test-Path -LiteralPath $output){Remove-Item -LiteralPath $output -Recurse -Force}
  $builderArgs=@('--config',(Join-Path $root 'packaging\electron-builder.yml'))
  if($Channel -eq 'production'){$builderArgs+='--config.win.signAndEditExecutable=true'}
  & npx electron-builder @builderArgs --dir --x64;if($LASTEXITCODE -ne 0){throw 'Unpacked application build failed'}
  & npx electron-builder @builderArgs --win nsis --x64;if($LASTEXITCODE -ne 0){throw 'NSIS build failed'}
  & node (Join-Path $PSScriptRoot 'verify-week7-package.mjs') (Join-Path $output 'win-unpacked') $Channel;if($LASTEXITCODE -ne 0){throw 'Packaged content verification failed'}
  $installer=Get-ChildItem -LiteralPath $output -Filter ('iWork-'+$pkg.version+'-windows-x64.exe')|Select-Object -First 1
  if(-not $installer){throw 'Installer was not produced'}
  $signatureStatus='Unavailable'
  try{
    $signature=Get-AuthenticodeSignature -LiteralPath $installer.FullName -ErrorAction Stop
    $signatureStatus=[string]$signature.Status
  }catch{
    if($Channel -eq 'production'){throw 'Production installer signature could not be verified'}
    Write-Warning 'Authenticode status is unavailable; non-production artifact will be marked unsigned'
  }
  if($Channel -eq 'production' -and $signatureStatus -ne 'Valid'){throw 'Production installer signature is not valid'}
  $hash=Get-Sha256 $installer.FullName
  $manifest=Get-Content -LiteralPath (Join-Path $output 'win-unpacked\resources\release-manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
  $summary=[ordered]@{schemaVersion=1;appVersion=$pkg.version;channel=$Channel;unsigned=($signatureStatus -ne 'Valid');installer=$installer.Name;sha256=$hash;sourceRevision=$manifest.sourceRevision}
  [IO.File]::WriteAllText((Join-Path $output 'release-artifacts.json'),($summary|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
  Write-Output ($summary|ConvertTo-Json -Compress)
}finally{Pop-Location}
