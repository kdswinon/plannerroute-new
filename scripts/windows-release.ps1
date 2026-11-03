param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = '',
    [string]$BuildToolsVersion = '35.0.0',
    [string]$KeyStore = "$env:LOCALAPPDATA\PlannerRoute\signing\release.jks",
    [switch]$CreateKey
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$KeyStore = [IO.Path]::GetFullPath($KeyStore)
$projectRoot = [IO.Path]::GetFullPath((Get-Location).Path).TrimEnd('\') + '\'
if ($KeyStore.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the signing key outside the project. Use the default location or another private folder.'
}
$tools = Join-Path $AndroidSdk "build-tools\$BuildToolsVersion"
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$aapt = Join-Path $tools 'aapt.exe'
foreach ($tool in @($zipalign, $apksigner, $aapt)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing $tool. Install Android SDK Build-Tools $BuildToolsVersion using SDK Manager." }
}
$pinPath = "$KeyStore.cert-sha256.txt"
if (-not (Test-Path -LiteralPath $KeyStore) -and -not $CreateKey) {
    throw 'Signing key missing. Restore your existing key. For the FIRST release only, run with -CreateKey. Never replace a lost production key.'
}
if (-not (Test-Path -LiteralPath $KeyStore) -and (Test-Path -LiteralPath $pinPath)) {
    throw 'A certificate record exists but its signing key is missing. Restore the original key; a replacement key was NOT created.'
}
# Reuse the already working JDK/Gradle bootstrap; release mode never runs tests or adb.
& .\tests\windows-check.ps1 -AndroidSdk $AndroidSdk -JavaHome $JavaHome -SkipTests -ReleaseUnsigned
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Build did not prepare Java.' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK keytool not found.' }
if (-not (Test-Path -LiteralPath $KeyStore)) {
    New-Item -ItemType Directory -Force (Split-Path $KeyStore -Parent) | Out-Null
    Write-Host 'FIRST RELEASE: create a private signing key. Enter a strong password at the keytool prompts.'
    Write-Host 'Do not send passwords or the keystore to chat, GitHub or recipients. Back them up privately.'
    & $keytool -genkeypair -keystore $KeyStore -storetype PKCS12 -alias plannerroute -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PlannerRoute'
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $KeyStore)) { throw 'Key creation did not finish. Do not delete or replace an existing key; check the error.' }
}
$config = Get-Content -LiteralPath '.\app\build.gradle' -Raw
$versionMatch = [regex]::Match($config, "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
$codeMatch = [regex]::Match($config, 'versionCode\s+(\d+)')
if (-not $versionMatch.Success -or -not $codeMatch.Success) { throw 'Could not read release version.' }
$version = $versionMatch.Groups[1].Value
$code = $codeMatch.Groups[1].Value
$unsigned = '.\app\build\outputs\apk\release\app-release-unsigned.apk'
$badging = @(& $aapt dump badging $unsigned)
if ($LASTEXITCODE -ne 0) { throw 'APK metadata check failed.' }
$packageLine = @($badging | Where-Object { $_ -match '^package:' })
if ($packageLine.Count -ne 1 -or $packageLine[0] -notmatch "name='com\.plannerroute\.app'" -or
    $packageLine[0] -notmatch "versionCode='$code'" -or $packageLine[0] -notmatch "versionName='$version'") { throw 'APK package or version does not match this project.' }
if ($badging -match '^application-debuggable') { throw 'Release APK is debuggable. Do not distribute it.' }
# Each run gets a new folder; failed/old artifacts cannot be mistaken for a fresh success.
$out = Join-Path '.tooling\release' ($version + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $out | Out-Null
$aligned = Join-Path $out 'aligned-unsigned.apk'
$pending = Join-Path $out 'signed-pending.apk'
$apk = Join-Path $out "PlannerRoute-$version.apk"
& $zipalign -P 16 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
Write-Host 'Signing: enter the keystore password at the apksigner prompt. It is not stored by this script.'
& $apksigner sign --ks $KeyStore --ks-key-alias plannerroute --out $pending $aligned
if ($LASTEXITCODE -ne 0) { throw 'Signing failed. No distributable APK was produced.' }
$verification = @(& $apksigner verify --verbose --print-certs $pending)
if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed. Do not distribute this file.' }
$certLines = @($verification | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$' })
if ($certLines.Count -ne 1) { throw 'Could not confirm signing certificate SHA-256.' }
$certHash = ([regex]::Match($certLines[0], '([0-9a-fA-F]{64})\s*$')).Groups[1].Value.ToLowerInvariant()
if (Test-Path -LiteralPath $pinPath) {
    $known = (Get-Content -LiteralPath $pinPath -Raw).Trim().ToLowerInvariant()
    if ($known -ne $certHash) { throw 'Signing certificate changed. Distribution stopped. Restore the original release key.' }
} else {
    Set-Content -LiteralPath $pinPath -Value $certHash -Encoding ascii
}
& $zipalign -c -P 16 4 $pending
if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
Move-Item -LiteralPath $pending -Destination $apk
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$apk.sha256" -Value "$hash  $([IO.Path]::GetFileName($apk))" -Encoding ascii
$verification | Set-Content -LiteralPath (Join-Path $out 'signature-public.txt') -Encoding utf8
Remove-Item -LiteralPath $aligned
Write-Host "SIGNED APK READY: $([IO.Path]::GetFullPath($apk))"
Write-Host "APK SHA-256: $hash"
Write-Host "Certificate SHA-256: $certHash"
Write-Host "PRIVATE KEY (never share): $KeyStore"
Write-Host 'Back up the private key and password securely. Keep using this SAME key for every future update.'
Write-Host 'This command does not install, uninstall, upload or publish anything. Existing debug apps cannot be updated with this new certificate.'
Start-Process explorer.exe -ArgumentList ('"' + [IO.Path]::GetFullPath($out) + '"')
