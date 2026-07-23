param(
    [string]$GradleHome = $(if ($env:LOC_GRADLE_HOME) { $env:LOC_GRADLE_HOME } else { "F:\gradle-9.5.0" }),
    [string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "F:\android" }),
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Gradle = Join-Path $GradleHome "bin\gradle.bat"
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
    throw "Gradle 9.5.0 launcher not found: $Gradle"
}
if (-not (Test-Path -LiteralPath $AndroidHome -PathType Container)) {
    throw "Android SDK not found: $AndroidHome"
}

$workspaceEnvironment = @{
    GRADLE_USER_HOME = $(if ($env:LOC_GRADLE_USER_HOME) { $env:LOC_GRADLE_USER_HOME } else { Join-Path $Root ".gradle-user-home" })
    ANDROID_HOME = $AndroidHome
    ANDROID_SDK_ROOT = $AndroidHome
    ANDROID_USER_HOME = $(if ($env:LOC_ANDROID_USER_HOME) { $env:LOC_ANDROID_USER_HOME } else { Join-Path $Root ".android-home" })
    TEMP = Join-Path $Root ".task-tmp"
    TMP = Join-Path $Root ".task-tmp"
    GRADLE_OPTS = "-XX:-UsePerfData"
}
$originalEnvironment = @{}
try {
    foreach ($entry in $workspaceEnvironment.GetEnumerator()) {
        $originalEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        if ($entry.Key -in @("GRADLE_USER_HOME", "ANDROID_USER_HOME", "TEMP", "TMP")) {
            New-Item -ItemType Directory -Force -Path $entry.Value | Out-Null
        }
    }
    $versionOutput = (& $Gradle --version 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch '(?m)^Gradle 9\.5\.0$') {
        throw "The configured launcher is not Gradle 9.5.0: $Gradle"
    }
    $arguments = @("--no-daemon")
    if ($Offline) {
        $arguments += "--offline"
    }
    $arguments += @(
        ":android-client:assembleRelease",
        ":android-admin-client:assembleRelease"
    )
    Push-Location $Root
    try {
        & $Gradle @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Android Gradle build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($entry in $originalEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

Write-Host "Android unsigned release builds completed."
Write-Host "  user:  android-client\build\outputs\apk\release"
Write-Host "  admin: android-admin-client\build\outputs\apk\release"
