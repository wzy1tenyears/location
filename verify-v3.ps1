Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RequiredFiles = @(
    'go.mod',
    'cmd/server/main.go',
    'internal/app/router.go',
    'internal/config/config.go',
    'internal/database/database.go',
    'internal/httpx/json.go',
    'internal/httpx/request.go',
    'internal/middleware/middleware.go',
    'internal/session/session.go',
    'internal/handlers/updates.go',
    'internal/handlers/challenge.go',
    'internal/handlers/login.go',
    'internal/handlers/register.go',
    'internal/handlers/groups.go',
    'internal/handlers/report_location.go',
    'internal/handlers/tickets.go',
    'internal/handlers/p2p.go',
    'internal/handlers/webviews.go',
    'internal/handlers/admin_manage.go',
    'internal/handlers/admin_summary.go',
    'internal/handlers/templates/history_map.html',
    'internal/handlers/templates/amap_reverse.html',
    'internal/handlers/templates/webrtc_probe.html',
    'internal/handlers/announcements.go',
    'internal/handlers/invites.go',
    'internal/handlers/me.go',
    'internal/handlers/auth.go',
    'internal/handlers/settings.go',
    'internal/handlers/locations.go',
    'internal/handlers/history.go',
    'internal/handlers/legal_documents.go',
    'internal/handlers/session.go',
    'internal/handlers/heartbeat.go',
    'internal/handlers/environment.go',
    'internal/handlers/diagnostics.go',
    'internal/handlers/downloads.go',
    'internal/repositories/users.go',
    'internal/repositories/groups.go',
    'internal/repositories/locations.go',
    'internal/repositories/geo.go',
    'internal/repositories/announcements.go',
    'internal/repositories/invites.go',
    'internal/repositories/environment_reports.go',
    'internal/repositories/auth_limits.go',
    'internal/repositories/devices.go',
    'internal/repositories/app_challenges.go',
    'internal/repositories/rate_limits.go',
    'internal/repositories/settings.go',
    'internal/httpx/client.go',
    'internal/services/users.go',
    'internal/services/passwords.go',
    'internal/services/device_policy.go',
    'internal/services/ipgeo.go',
    'internal/models/models.go'
    'internal/session/store.go'
)

foreach ($File in $RequiredFiles) {
    $Path = Join-Path $Root $File
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "missing required v3 file: $File"
    }
}

$AllText = Get-ChildItem -LiteralPath $Root -Recurse -File |
    Where-Object { $_.Extension -in @('.go', '.md', '.ps1') } |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }

$ForbiddenSecretPatterns = @(
    ('ZkfE' + '8S5M' + 'wnPk' + 'eHGM'),
    ('command' + '0block'),
    ('0x4AAAA' + 'AADP6T'),
    ('5d585' + 'e53f54256')
)

foreach ($Pattern in $ForbiddenSecretPatterns) {
    if ($AllText -match [regex]::Escape($Pattern)) {
        throw 'v3 contains a private secret-like literal.'
    }
}

$RouterText = Get-Content -LiteralPath (Join-Path $Root 'internal/app/router.go') -Raw -Encoding UTF8
$ExpectedRoutes = @(
    '/api/app_update.php',
    '/api/app_challenge.php',
    '/api/login.php',
    '/api/register.php',
    '/api/admin_app_update.php',
    '/api/announcement.php',
    '/api/invite_check.php',
    '/api/me.php',
    '/api/settings.php',
    '/api/locations.php',
    '/api/history.php',
    '/api/legal_documents.php',
    '/api/groups.php',
    '/api/report_location.php',
    '/api/tickets.php',
    '/api/p2p_crypto.php',
    '/api/history_map_webview.php',
    '/api/amap_reverse_webview.php',
    '/api/webrtc_probe_webview.php',
    '/api/admin_manage.php',
    '/api/admin_summary.php',
    '/api/logout.php',
    '/api/heartbeat.php',
    '/api/environment_report.php',
    '/api/device_report.php',
    '/api/admin_apk.php',
    '/api/geo_aliases.php',
    '/api/ip_probe.php',
    '/api/ip_geo.php',
    '/api/ipinfo_lite.php',
    '/api/cloudflare_location.php',
    '/api/'
)

foreach ($Route in $ExpectedRoutes) {
    if ($RouterText -notmatch [regex]::Escape($Route)) {
        throw "v3 router is missing route: $Route"
    }
}

$UpdateText = Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/updates.go') -Raw -Encoding UTF8
if ($UpdateText -notmatch 'sessions\.IsAdmin') {
    throw 'admin_app_update handler must keep admin session protection'
}

$SessionText = Get-Content -LiteralPath (Join-Path $Root 'internal/session/session.go') -Raw -Encoding UTF8
if ($SessionText -notmatch 'sess_' -or $SessionText -notmatch 'admin_logged_in' -or $SessionText -notmatch 'user_id') {
    throw 'session reader must retain PHP session compatibility hooks'
}

if ($RouterText -match 'legacy\.Proxy' -or $RouterText -match 'LOC_LEGACY_BASE_URL') {
    throw 'v3 router must not retain legacy PHP fallback'
}

if ($AllText -match '\(\?=') {
    throw 'v3 must not use unsupported regexp lookahead syntax'
}

$GoExe = $env:LOC_GO_EXE
if ([string]::IsNullOrWhiteSpace($GoExe)) {
    $GoCommand = Get-Command go -ErrorAction SilentlyContinue
    if ($GoCommand) {
        $GoExe = $GoCommand.Source
    }
}

if ($GoExe -and (Test-Path -LiteralPath $GoExe -PathType Leaf)) {
    Push-Location $Root
    try {
        & $GoExe test ./...
        if ($LASTEXITCODE -ne 0) {
            throw "go test failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
    Write-Output 'verify-v3 OK'
    exit 0
}

Write-Output 'verify-v3 OK (Go toolchain not found; static checks only)'
