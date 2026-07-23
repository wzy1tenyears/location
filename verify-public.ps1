param(
    [switch]$StaticOnly,
    [switch]$BuildAndroid,
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Fail([string]$Message) {
    throw "verify-public failed: $Message"
}

& (Join-Path $Root "verify-v3.ps1") -StaticOnly:$StaticOnly
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$required = @(
    "android-client\AndroidManifest.xml",
    "android-client\src\com\familylocation\client\MainActivity.java",
    "android-admin-client\src\com\familylocation\admin\AdminActivity.java",
    "android-common\src\com\familylocation\net\JsonApiClient.java",
    "android-client\res\xml\network_security_config.xml",
    "build-android.ps1"
)
foreach ($relativePath in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $relativePath) -PathType Leaf)) {
        Fail "required public source is missing: $relativePath"
    }
}

$userServer = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Root "android-client\assets\server-url.txt")).Trim()
$adminServer = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Root "android-admin-client\assets\server-url.txt")).Trim()
if ($userServer -cne "https://example.com/" -or $adminServer -cne "https://example.com/") {
    Fail "public Android assets must use the example.com server placeholder."
}
$adminSource = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Root "android-admin-client\src\com\familylocation\admin\AdminActivity.java")
if ($adminSource -notmatch 'DEFAULT_ADMIN_USERNAME\s*=\s*"admin"' -or
    $adminSource -notmatch 'DEFAULT_SERVER_URL\s*=\s*"https://example\.com/"') {
    Fail "public admin defaults must remain generic placeholders."
}
$networkConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $Root "android-client\res\xml\network_security_config.xml")
if ($networkConfig -notmatch '<base-config cleartextTrafficPermitted="false"' -or
    $networkConfig -notmatch '<domain includeSubdomains="false">ip-api\.com</domain>' -or
    [regex]::Matches($networkConfig, '<domain(?:\s|>)').Count -ne 1) {
    Fail "Android cleartext policy must allow only the exact ip-api.com host."
}

$forbiddenExtensions = @(".apk", ".aab", ".jks", ".keystore", ".pem", ".key", ".p12", ".pfx")
$candidateFiles = & git -C $Root ls-files --cached --others --exclude-standard
foreach ($relativePath in $candidateFiles) {
    if ($relativePath -like ".git/*" -or $relativePath -match '(^|/)(build|bin|tmp|\.gradle-user-home|\.android-home|\.task-tmp)/') {
        continue
    }
    if ([IO.Path]::GetExtension($relativePath).ToLowerInvariant() -in $forbiddenExtensions) {
        Fail "forbidden public artifact: $relativePath"
    }
}

if ($BuildAndroid) {
    & (Join-Path $Root "build-android.ps1") -Offline:$Offline
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host "verify-public OK"
