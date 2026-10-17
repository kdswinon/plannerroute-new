param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = '',
    [switch]$Live
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path "$AndroidSdk\platforms\android-35\android.jar")) { throw 'Install Android SDK Platform 35 in Android Studio SDK Manager.' }

function Test-Jdk17([string]$Folder) {
    if (-not (Test-Path "$Folder\bin\java.exe") -or -not (Test-Path "$Folder\bin\javac.exe") -or -not (Test-Path "$Folder\release")) { return $false }
    return [bool](Select-String -LiteralPath "$Folder\release" -Pattern '^JAVA_VERSION="17(\.|\")' -Quiet)
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    # Gradle 8.9 cannot run with Java 25 bundled in newer Android Studio releases.
    # Keep a verified JDK 17 inside this project; do not change system Java.
    $jdkRoot = "$PWD\.tooling\windows-jdk"
    New-Item -ItemType Directory -Force $jdkRoot | Out-Null
    $cached = @(Get-ChildItem -LiteralPath $jdkRoot -Directory | Where-Object { (Test-Path (Join-Path $_.FullName '.plannerroute-ready')) -and (Test-Jdk17 $_.FullName) })
    if ($cached.Count -gt 0) {
        $JavaHome = $cached[0].FullName
    } else {
        Write-Host 'Preparing Java 17 for this project. The first download may take several minutes.'
        $assets = @(Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse')
        if ($assets.Count -eq 0) { throw 'No Windows JDK 17 download was returned. Retry later.' }
        $package = $assets[0].binary.package
        if ($package.checksum -notmatch '^[0-9a-fA-F]{64}$' -or $package.link -notmatch '^https://') { throw 'Invalid JDK download metadata.' }
        $zip = Join-Path $jdkRoot ('jdk17-' + $package.checksum + '.zip')
        if (-not (Test-Path $zip) -or (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $package.checksum) {
            Invoke-WebRequest -UseBasicParsing -Uri $package.link -OutFile "$zip.part"
            if ((Get-FileHash -LiteralPath "$zip.part" -Algorithm SHA256).Hash -ne $package.checksum) { throw 'JDK download checksum mismatch. Retry the script.' }
            Move-Item -LiteralPath "$zip.part" -Destination $zip -Force
        }
        if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $package.checksum) { throw 'JDK checksum verification failed. Do not run this download; retry the script.' }
        $staging = Join-Path $jdkRoot ('unpack-' + [guid]::NewGuid().ToString('N'))
        Expand-Archive -LiteralPath $zip -DestinationPath $staging
        $extracted = @(Get-ChildItem -LiteralPath $staging -Directory | Where-Object { Test-Jdk17 $_.FullName })
        if ($extracted.Count -ne 1) { throw 'Java 17 extraction did not finish. Retry the script.' }
        $JavaHome = Join-Path $jdkRoot ('ready-' + [guid]::NewGuid().ToString('N'))
        Move-Item -LiteralPath $extracted[0].FullName -Destination $JavaHome
        New-Item -ItemType File -Path (Join-Path $JavaHome '.plannerroute-ready') | Out-Null
    }
}
if (-not (Test-Jdk17 $JavaHome)) { throw 'This project requires JDK 17. Omit -JavaHome to prepare it automatically, or pass a JDK 17 folder.' }
Write-Host "Using Java 17: $JavaHome"
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:GRADLE_USER_HOME = "$PWD\.tooling\windows-gradle"
$env:ANDROID_USER_HOME = "$PWD\.tooling\windows-android"
$work = "$PWD\.tooling\windows-check"
New-Item -ItemType Directory -Force "$work\classes-jdk17" | Out-Null
$json = "$work\json-20240303.jar"
$jsonHash = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if (-not (Test-Path $json) -or (Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne $jsonHash) {
    Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile "$json.part"
    if ((Get-FileHash -LiteralPath "$json.part" -Algorithm SHA256).Hash -ne $jsonHash) { throw 'Test library download checksum mismatch. Retry the script.' }
    Move-Item -LiteralPath "$json.part" -Destination $json -Force
}
$compileCp = "$json;$AndroidSdk\platforms\android-35\android.jar"
& "$JavaHome\bin\javac.exe" -encoding UTF-8 --release 17 -cp $compileCp -d "$work\classes-jdk17" app/src/main/java/com/plannerroute/app/Data.java app/src/main/java/com/plannerroute/app/Routing.java tests/CoreChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed.' }
$testArgs = @()
if ($Live) { $testArgs += '--live' }
& "$JavaHome\bin\java.exe" -cp "$work\classes-jdk17;$compileCp" com.plannerroute.app.CoreChecks "$work\runtime" @testArgs
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
& .\gradlew.bat "-Dorg.gradle.java.home=$JavaHome" :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
$adb = "$AndroidSdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw 'Install Android SDK Platform-Tools in SDK Manager.' }
$lines = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'Device connection check failed.' }
$devices = @($lines | Where-Object { $_ -match '^\S+\s+device$' })
if ($devices.Count -ne 1) { throw 'Connect exactly one Android device and allow USB debugging on the phone.' }
$serial = ($devices[0] -split '\s+')[0]
& $adb -s $serial install -r app/build/outputs/apk/debug/app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed. Existing app/data were not removed. Check signing compatibility.' }
& $adb -s $serial shell am start -W -n com.plannerroute.app/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch command failed.' }
Start-Sleep -Seconds 4
$appPid = ((& $adb -s $serial shell pidof com.plannerroute.app) -join '').Trim()
if ($appPid -notmatch '^\d+$') { throw 'App process not running. Check device crash details before continuing.' }
& $adb -s $serial logcat -d "--pid=$appPid" -t 1000 | Out-File "$work\app-log.txt" -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'App log collection failed.' }
Write-Host "Build, install and running process confirmed. App log: $work\app-log.txt"
Write-Host 'Map rendering, gestures, GPS and external navigation still need device UI checks.'
