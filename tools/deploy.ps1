# ============================================================================
# deploy.ps1 -- build + install + launch InkNote on the connected device
#
# Usage:
#   .\tools\deploy.ps1              # build + install + launch
#   .\tools\deploy.ps1 -SkipBuild   # install + launch only
#
# WHY THIS SCRIPT EXISTS (important):
#   Lenovo ZUI ships an "app clone" feature (com.zui.clone). This Y700 has four
#   clone user spaces (user 903/904/905/906) permanently running.
#   `adb install` / `pm install` WITHOUT --user installs for ALL users, so one
#   APK lands in all five spaces and the launcher shows five icons.
#   That is a deployment problem, not an app problem. Hence the --user 0 below.
#
# THIS FILE IS DELIBERATELY ASCII-ONLY:
#   Windows PowerShell reads .ps1 files using the ANSI code page unless the file
#   has a BOM. Non-ASCII characters in a UTF-8-no-BOM script get mojibake'd and
#   can swallow the closing quote of a string, producing parser errors.
#   Keep this file ASCII. Chinese documentation lives in the chat, not here.
# ============================================================================

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$apk  = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

# --- 1. resolve sdk.dir from local.properties to locate adb ------------------
$sdkLine = Get-Content (Join-Path $root 'local.properties') |
    Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $sdkLine) { throw 'local.properties has no sdk.dir entry' }
# value looks like  C\:\\Users\\...  -> unescape \: to : and \\ to \
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at: $adb" }

# --- 2. pick a Gradle JDK ---------------------------------------------------
# MUST be a full JDK that ships jmods.
# Android Studio's bundled JBR (G:\Program Files\Android\Android Studio\jbr) is a
# stripped runtime WITHOUT jmods. AGP's JdkImageTransform shells out to jlink,
# which needs them, and the build dies with:
#   "Failed to transform core-for-system-modules.jar ... jlink.exe"
# The bundled JBR can still RUN Android Studio; it just cannot build with AGP.
$jbr = 'G:\Program Files\Android\Android Studio\jbr'
$jdkCandidates = @(
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Java\jdk-17',
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21.0.2.13-hotspot",
    "$env:ProgramFiles\Microsoft\jdk-17.0.0.1-hotspot"
)
$gradleJdk = $jdkCandidates |
    Where-Object { Test-Path (Join-Path $_ 'jmods') } |
    Select-Object -First 1
if (-not $gradleJdk) {
    throw "No full JDK with jmods found. Bundled JBR is at $jbr but lacks jmods. Install a JDK 17/21 or set org.gradle.java.home."
}
Write-Host "Gradle JDK: $gradleJdk" -ForegroundColor DarkGray
$env:JAVA_HOME = $gradleJdk

# --- 3. build ---------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host '[1/4] building debug APK ...' -ForegroundColor Cyan
    Push-Location $root
    try {
        & .\gradlew.bat "-Dorg.gradle.java.home=$gradleJdk" :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host '[1/4] build skipped' -ForegroundColor DarkGray
}
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

# --- 4. install to the primary user ONLY ------------------------------------
# Without --user this would be installed into all five user spaces, including
# the four ZUI app-clone spaces.
Write-Host '[2/4] installing to user 0 only ...' -ForegroundColor Cyan
& $adb install --user 0 -r $apk
if ($LASTEXITCODE -ne 0) { throw "install failed (exit $LASTEXITCODE)" }

# --- 5. launch --------------------------------------------------------------
Write-Host '[3/4] launching ...' -ForegroundColor Cyan
& $adb shell am start -n com.inknote.pad/.MainActivity | Out-Null

# --- 6. clean up leftovers in the clone spaces ------------------------------
Write-Host '[4/4] checking clone spaces for leftovers ...' -ForegroundColor Cyan
$cleaned = 0
foreach ($u in 903, 904, 905, 906) {
    $found = & $adb shell cmd package list packages --user $u 2>&1 | Select-String 'inknote'
    if ($found) {
        & $adb shell pm uninstall --user $u com.inknote.pad | Out-Null
        Write-Host "      uninstalled from clone user $u" -ForegroundColor Yellow
        $cleaned++
    }
}
if ($cleaned -eq 0) { Write-Host '      no leftovers' -ForegroundColor DarkGray }

# --- 7. report --------------------------------------------------------------
Write-Host ''
Write-Host 'Done. Install state:' -ForegroundColor Green
foreach ($u in 0, 903, 904, 905, 906) {
    $r = & $adb shell cmd package list packages --user $u 2>&1 | Select-String 'inknote'
    $tag = if ($r) { 'installed' } else { 'absent' }
    Write-Host "  user ${u}: $tag"
}
