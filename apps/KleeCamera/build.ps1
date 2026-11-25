param([string]$SdkPath, [string]$JavaPath)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $SdkPath) {
    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
    $SdkPath = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms/android-36/android.jar')) } | Select-Object -First 1
}
if (-not $SdkPath) { throw 'Android SDK Platform 36 is required. Pass its SDK root with -SdkPath.' }
$platform = Join-Path $SdkPath 'platforms/android-36/android.jar'
$buildTools = Get-ChildItem (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'd8.bat')) } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools -or -not (Test-Path $platform)) { throw 'Install Android SDK Platform 36 and Build Tools.' }
$toolDir = $buildTools.FullName
if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool' }

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed ($LASTEXITCODE) for $Executable" }
}

$build = Join-Path $root 'build'
$stage = Join-Path $build ([guid]::NewGuid().ToString('n'))
$classes = Join-Path $stage 'classes'
$dex = Join-Path $stage 'dex'
$src = Join-Path $root 'app/src/main'
New-Item -Type Directory -Path $classes,$dex -Force | Out-Null
$resources = Join-Path $stage 'resources.zip'
$package = Join-Path $stage 'unsigned.apk'
Invoke-BuildTool (Join-Path $toolDir 'aapt2.exe') @('compile','--dir',(Join-Path $src 'res'),'-o',$resources)
Invoke-BuildTool (Join-Path $toolDir 'aapt2.exe') @('link','-o',$package,'-I',$platform,'--manifest',(Join-Path $src 'AndroidManifest.xml'),$resources)
$sources = @(Get-ChildItem (Join-Path $src 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-BuildTool $javac (@('--release','11','-encoding','UTF-8','-classpath',$platform,'-d',$classes) + $sources)
$classJar = Join-Path $stage 'app.jar'
$jar = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/jar.exe' } else { 'jar' }
Invoke-BuildTool $jar @('cf',$classJar,'-C',$classes,'.')
Invoke-BuildTool (Join-Path $toolDir 'd8.bat') @('--min-api','30','--lib',$platform,'--output',$dex,$classJar)
Push-Location $dex
try { Invoke-BuildTool $jar @('uf',$package,'classes.dex') } finally { Pop-Location }
$aligned = Join-Path $stage 'aligned.apk'
$signed = Join-Path $stage 'klee-camera.apk'
Invoke-BuildTool (Join-Path $toolDir 'zipalign.exe') @('-f','4',$package,$aligned)
$keystore = Join-Path $build 'debug.keystore'
if (-not (Test-Path $keystore)) {
    Invoke-BuildTool $keytool @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android',
        '-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Klee Camera Debug,O=Klee UI')
}
Invoke-BuildTool (Join-Path $toolDir 'apksigner.bat') @('sign','--ks',$keystore,'--ks-pass','pass:android','--key-pass','pass:android','--out',$signed,$aligned)
Invoke-BuildTool (Join-Path $toolDir 'apksigner.bat') @('verify',$signed)
$target = Join-Path $build 'klee-camera.apk'
Copy-Item -LiteralPath $signed -Destination $target -Force
Get-Item -LiteralPath $target | Select-Object FullName,Length
