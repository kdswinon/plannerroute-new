param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = "$env:ProgramFiles\Android\Android Studio\jbr",
    [switch]$Live
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path "$JavaHome\bin\java.exe")) { throw 'Install Android Studio or pass -JavaHome with JDK 17+.' }
if (-not (Test-Path "$AndroidSdk\platforms\android-35\android.jar")) { throw 'Install Android SDK Platform 35 in Android Studio SDK Manager.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:GRADLE_USER_HOME = "$PWD\.tooling\windows-gradle"
$env:ANDROID_USER_HOME = "$PWD\.tooling\windows-android"
$work = "$PWD\.tooling\windows-check"
New-Item -ItemType Directory -Force "$work\classes" | Out-Null
$json = "$work\json-20240303.jar"
if (-not (Test-Path $json)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json
}
$compileCp = "$json;$AndroidSdk\platforms\android-35\android.jar"
& "$JavaHome\bin\javac.exe" -cp $compileCp -d "$work\classes" app/src/main/java/com/plannerroute/app/Data.java app/src/main/java/com/plannerroute/app/Routing.java tests/CoreChecks.java
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed.' }
$testArgs = @()
if ($Live) { $testArgs += '--live' }
& "$JavaHome\bin\java.exe" -cp "$work\classes;$compileCp" com.plannerroute.app.CoreChecks "$work\runtime" @testArgs
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
& .\gradlew.bat :app:assembleDebug --console=plain
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
