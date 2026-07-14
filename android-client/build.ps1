param(
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "" }),
    [int]$CompileSdk = 35,
    [string]$BuildToolsVersion = "35.0.0",
    [string]$OutputApk = "",
    [string]$Keystore = $(if ($env:LOC_ANDROID_USER_KEYSTORE) { $env:LOC_ANDROID_USER_KEYSTORE } else { "" }),
    [string]$KeyAlias = $(if ($env:LOC_ANDROID_USER_KEY_ALIAS) { $env:LOC_ANDROID_USER_KEY_ALIAS } else { "" }),
    [switch]$NoObfuscate
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredPath {
    param(
        [string]$Path,
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Name not found: $Path"
    }

    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-CommandPath {
    param(
        [string]$Command,
        [string]$Name
    )

    $resolved = Get-Command $Command -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "$Name not found in PATH: $Command"
    }

    return $resolved.Source
}

function Invoke-Step {
    param(
        [string]$Title,
        [scriptblock]$Action
    )

    Write-Host "==> $Title"
    & $Action
}

function Invoke-Native {
    param(
        [string]$FilePath,
        [object[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath"
    }
}

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $ScriptRoot "..")).Path

if ([string]::IsNullOrWhiteSpace($Keystore) -or
    [string]::IsNullOrWhiteSpace($KeyAlias) -or
    [string]::IsNullOrWhiteSpace($env:LOC_ANDROID_USER_STORE_PASSWORD) -or
    [string]::IsNullOrWhiteSpace($env:LOC_ANDROID_USER_KEY_PASSWORD)) {
    throw "Release signing requires LOC_ANDROID_USER_KEYSTORE, LOC_ANDROID_USER_KEY_ALIAS, LOC_ANDROID_USER_STORE_PASSWORD, and LOC_ANDROID_USER_KEY_PASSWORD."
}
$Keystore = Resolve-RequiredPath $Keystore "external user release keystore"
$ProjectRootPrefix = $ProjectRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if ($Keystore.StartsWith($ProjectRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Release keystore must be stored outside the project tree."
}
if ($KeyAlias -match "(?i)debug" -or ([System.IO.Path]::GetFileName($Keystore)) -match "(?i)debug") {
    throw "Debug signing keys are not allowed for release APKs."
}
if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "Android SDK path is required through ANDROID_HOME or -AndroidHome."
}

$AndroidHome = Resolve-RequiredPath $AndroidHome "Android SDK"
$BuildTools = Resolve-RequiredPath (Join-Path $AndroidHome "build-tools\$BuildToolsVersion") "Android build-tools"
$AndroidJar = Resolve-RequiredPath (Join-Path $AndroidHome "platforms\android-$CompileSdk\android.jar") "android.jar"

$Aapt = Resolve-RequiredPath (Join-Path $BuildTools "aapt.exe") "aapt"
$D8 = Resolve-RequiredPath (Join-Path $BuildTools "d8.bat") "d8"
$Zipalign = Resolve-RequiredPath (Join-Path $BuildTools "zipalign.exe") "zipalign"
$ApkSigner = Resolve-RequiredPath (Join-Path $BuildTools "apksigner.bat") "apksigner"
$Javac = Resolve-CommandPath "javac.exe" "javac"
$Java = Resolve-CommandPath "java.exe" "java"

$Manifest = Resolve-RequiredPath (Join-Path $ScriptRoot "AndroidManifest.xml") "AndroidManifest.xml"
$SourceDir = Resolve-RequiredPath (Join-Path $ScriptRoot "src") "src"
$ResDir = Resolve-RequiredPath (Join-Path $ScriptRoot "res") "res"
$AssetsDir = Resolve-RequiredPath (Join-Path $ScriptRoot "assets") "assets"
$ProguardRules = Resolve-RequiredPath (Join-Path $ScriptRoot "proguard-rules.pro") "proguard-rules.pro"
$R8JarCandidate = Join-Path $AndroidHome "cmdline-tools\latest\lib\r8.jar"
if (-not (Test-Path -LiteralPath $R8JarCandidate)) {
    $R8JarCandidate = Join-Path $AndroidHome "tools\proguard\lib\r8.jar"
}
$R8Jar = if ($NoObfuscate) { "" } else { Resolve-RequiredPath $R8JarCandidate "r8.jar" }
$BuildDir = Join-Path $ScriptRoot "build"
$GenDir = Join-Path $BuildDir "gen"
$ClassesDir = Join-Path $BuildDir "classes"
$DexDir = Join-Path $BuildDir "dex"

if ($OutputApk.Trim() -eq "") {
    $OutputApk = Join-Path $ProjectRoot "location-release.apk"
}
$OutputApk = [System.IO.Path]::GetFullPath($OutputApk)

Invoke-Step "Clean build directory" {
    $resolvedScriptRoot = [System.IO.Path]::GetFullPath($ScriptRoot)
    $resolvedBuildDir = [System.IO.Path]::GetFullPath($BuildDir)
    if (-not $resolvedBuildDir.StartsWith($resolvedScriptRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean outside android-client: $resolvedBuildDir"
    }

    if (Test-Path -LiteralPath $BuildDir) {
        Remove-Item -LiteralPath $BuildDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $GenDir, $ClassesDir, $DexDir | Out-Null
}

$ResourceApk = Join-Path $BuildDir "resources.apk"
$PackageApkName = "package-unsigned.apk"
$PackageApk = Join-Path $BuildDir $PackageApkName
$AlignedApk = Join-Path $BuildDir "package-aligned.apk"
$SignedApk = Join-Path $BuildDir "package-signed.apk"

Invoke-Step "Package resources" {
    Invoke-Native $Aapt @("package", "-f", "-m", "-J", $GenDir, "-M", $Manifest, "-S", $ResDir, "-A", $AssetsDir, "-I", $AndroidJar, "-F", $ResourceApk)
}

Invoke-Step "Compile Java" {
    $JavaFiles = @(
        Get-ChildItem -LiteralPath $SourceDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
        Get-ChildItem -LiteralPath $GenDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
    )

    if ($JavaFiles.Count -eq 0) {
        throw "No Java source files found."
    }

    Invoke-Native $Javac (@("-encoding", "UTF-8", "-Xlint:-options", "--release", "8", "-classpath", $AndroidJar, "-d", $ClassesDir) + $JavaFiles)
}

Invoke-Step "Build DEX" {
    $ClassFiles = @(Get-ChildItem -LiteralPath $ClassesDir -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
    if ($ClassFiles.Count -eq 0) {
        throw "No compiled .class files found."
    }

    if ($NoObfuscate) {
        Invoke-Native $D8 (@("--lib", $AndroidJar, "--output", $DexDir) + $ClassFiles)
    } else {
        Invoke-Native $Java (@(
            "-cp", $R8Jar,
            "com.android.tools.r8.R8",
            "--release",
            "--min-api", "29",
            "--lib", $AndroidJar,
            "--pg-conf", $ProguardRules,
            "--output", $DexDir
        ) + $ClassFiles)
    }
}

Invoke-Step "Create unsigned APK" {
    Copy-Item -LiteralPath $ResourceApk -Destination $PackageApk -Force
    Copy-Item -LiteralPath (Join-Path $DexDir "classes.dex") -Destination (Join-Path $BuildDir "classes.dex") -Force
    Push-Location $BuildDir
    try {
        Invoke-Native $Aapt @("add", "-f", $PackageApkName, "classes.dex")
    } finally {
        Pop-Location
    }
}

Invoke-Step "Zipalign" {
    Invoke-Native $Zipalign @("-f", "-P", "16", "4", $PackageApk, $AlignedApk)
}

Invoke-Step "Sign APK" {
    Invoke-Native $ApkSigner @(
        "sign",
        "--ks", $Keystore,
        "--ks-key-alias", $KeyAlias,
        "--ks-pass", "env:LOC_ANDROID_USER_STORE_PASSWORD",
        "--key-pass", "env:LOC_ANDROID_USER_KEY_PASSWORD",
        "--out", $SignedApk,
        $AlignedApk
    )
}

Invoke-Step "Verify APK" {
    Invoke-Native $ApkSigner @("verify", "--verbose", $SignedApk)
    $SignerInfo = & $ApkSigner "verify" "--print-certs" $SignedApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect the APK signing certificate."
    }
    if (($SignerInfo -join "`n") -match "(?i)Android Debug|androiddebugkey") {
        throw "Debug signing certificates are not allowed for release APKs."
    }
}

Invoke-Step "Write output APK" {
    Copy-Item -LiteralPath $SignedApk -Destination $OutputApk -Force
    Write-Host "APK: $OutputApk"
    & $Aapt dump badging $OutputApk | Select-String -Pattern "package:|sdkVersion|targetSdkVersion|application-label" | ForEach-Object {
        Write-Host $_.Line
    }
}
