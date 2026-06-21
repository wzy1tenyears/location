param()

$ErrorActionPreference = "Stop"

function Fail {
    param([string]$Message)
    throw "verify-public failed: $Message"
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ScriptPath = (Resolve-Path -LiteralPath $MyInvocation.MyCommand.Path).Path
$UserMain = Join-Path $Root "android-client\src\com\familylocation\client\MainActivity.java"
$UserServerUrl = Join-Path $Root "android-client\assets\server-url.txt"
$AdminServerUrl = Join-Path $Root "android-admin-client\assets\server-url.txt"

foreach ($path in @($UserMain, $UserServerUrl, $AdminServerUrl)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Fail "missing required file: $path"
    }
}

$userMainText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserMain
if ($userMainText -notmatch 'private JSONArray installedAppsSummary\(\)\s*\{\s*return new JSONArray\(\);\s*\}') {
    Fail "public user app must not upload installed app package lists."
}
if ($userMainText -match 'app\.put\("package_name"') {
    Fail "public user app must not serialize installed app package_name values."
}

$forbiddenPatterns = @(
    'loc\.mtmt\.top',
    '82\.158\.231\.148',
    '162\.141\.136\.28',
    'zWbXt9p6jysX',
    'fHR3TaWIlRBl9c8k',
    'command0block'
)
$textFiles = Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object {
    $_.FullName -notmatch '\\\.git\\' -and
    $_.FullName -ne $ScriptPath -and
    $_.FullName -notmatch '\\build\\' -and
    $_.FullName -notmatch '\\bin\\' -and
    $_.Extension -notin @('.apk', '.jks', '.keystore')
}
foreach ($file in $textFiles) {
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName -ErrorAction SilentlyContinue
    if ($null -eq $content) {
        continue
    }
    foreach ($pattern in $forbiddenPatterns) {
        if ($content -match $pattern) {
            Fail "public repository contains private token or endpoint in $($file.FullName)"
        }
    }
}

if ((Get-Content -Raw -Encoding UTF8 -LiteralPath $UserServerUrl).Trim() -ne "") {
    Fail "public user server-url.txt must be empty by default."
}
if ((Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminServerUrl).Trim() -ne "") {
    Fail "public admin server-url.txt must be empty by default."
}

Write-Host "verify-public OK"
