param([string]$Gradle, [string]$Java, [string]$AndroidSdk, [switch]$TestApk)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$root = Split-Path -Parent $project
if (-not $Java) { $Java = $env:JAVA_HOME }
if (-not $Java) { $Java = Join-Path $env:ProgramFiles 'Android Studio/jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $Java 'bin/java.exe'))) {
    $Java = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
}
if (-not (Test-Path -LiteralPath (Join-Path $Java 'bin/java.exe'))) { throw 'Specify -Java with JDK 17 or later.' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $Gradle) {
    $existing = Get-Command gradle -ErrorAction SilentlyContinue
    if ($existing) { $Gradle = $existing.Source }
}
if (-not $Gradle) {
    $wrapperCache = Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-9.5.0-bin'
    $Gradle = (Get-ChildItem -LiteralPath $wrapperCache -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1).FullName
}
if (-not $Gradle) { throw 'Specify -Gradle with the path to gradle.bat (Gradle 9.5.0).' }

# Android's Windows build tools require an ASCII project path.
$stage = Join-Path ([IO.Path]::GetTempPath()) 'onradio-android-build'
$stagedProject = Join-Path $stage 'android'
$env:JAVA_HOME = $Java
$env:ANDROID_HOME = $AndroidSdk
$env:GRADLE_USER_HOME = Join-Path $stage 'gradle-cache'
New-Item -ItemType Directory -Force -Path "$stagedProject/app","$stage/licenses","$stage/assets" | Out-Null
foreach ($file in @('settings.gradle', 'build.gradle', 'gradle.properties')) {
    Copy-Item -LiteralPath (Join-Path $project $file) -Destination $stagedProject -Force
}
Copy-Item -LiteralPath (Join-Path $project 'app/build.gradle') -Destination "$stagedProject/app" -Force
Copy-Item -LiteralPath (Join-Path $project 'app/src') -Destination "$stagedProject/app" -Recurse -Force
Copy-Item -LiteralPath (Join-Path $root 'catalog.json') -Destination $stage -Force
Copy-Item -LiteralPath (Join-Path $root 'assets/retro-grain.png') -Destination "$stage/assets" -Force
Copy-Item -LiteralPath (Join-Path $root 'assets/neodgm.ttf') -Destination "$stage/assets" -Force
foreach ($license in @('ShazamIO-core-MIT.txt', 'ShazamIO-MIT.txt', 'NeoDunggeunmo-OFL.txt')) {
    Copy-Item -LiteralPath (Join-Path "$root/licenses" $license) -Destination "$stage/licenses" -Force
}
$tasks = @(':app:assembleRelease', ':app:lintRelease')
if ($TestApk) { $tasks += ':app:assembleReleaseAndroidTest' }
& $Gradle --no-daemon --console=plain -p $stagedProject @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build or lint check failed.' }
$release = Join-Path $root 'release'
New-Item -ItemType Directory -Force -Path $release | Out-Null
Copy-Item -LiteralPath "$stagedProject/app/build/outputs/apk/release/app-release.apk" -Destination "$release/OnRadio-Android.apk" -Force
Write-Output "$release/OnRadio-Android.apk"
