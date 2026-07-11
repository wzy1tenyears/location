param(
    [switch]$StaticOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RequiredFiles = @(
    'go.mod',
    'cmd/server/main.go',
    'internal/app/router.go',
    'internal/config/config.go',
    'internal/database/database.go',
    'internal/database/schema.go',
    'internal/database/schema_core.sql',
    'internal/database/china_regions_seed.sql',
    'internal/database/migrations/001_app_sessions.sql',
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
	'internal/repositories/sessions.go'
	'internal/config/config_test.go'
	'internal/database/schema_test.go'
	'internal/httpx/request_test.go'
	'internal/session/session_test.go'
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
    '/api/app-update',
    '/api/app-challenge',
    '/api/login',
    '/api/register',
    '/api/admin-app-update',
    '/api/announcement',
    '/api/invite-check',
    '/api/me',
    '/api/settings',
    '/api/locations',
    '/api/history',
    '/api/legal-documents',
    '/api/groups',
    '/api/report-location',
    '/api/tickets',
    '/api/p2p-crypto',
    '/api/history-map',
    '/api/amap-reverse',
    '/api/webrtc-probe',
    '/api/admin/manage',
    '/api/admin/summary',
    '/api/logout',
    '/api/heartbeat',
    '/api/environment-report',
    '/api/device-report',
    '/api/admin/apk',
    '/api/geo-aliases',
    '/api/ip-probe',
    '/api/ip-geo',
    '/api/ipinfo-lite',
    '/api/cloudflare-location',
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

$ConfigText = Get-Content -LiteralPath (Join-Path $Root 'internal/config/config.go') -Raw -Encoding UTF8
if ($ConfigText -notmatch 'LOC_APP_SIGNING_SECRET') {
    throw 'v3 config must expose a dedicated app signing secret'
}

foreach ($Path in @(
    'internal/handlers/updates.go',
    'internal/handlers/downloads.go',
    'internal/handlers/challenge.go'
)) {
    $Text = Get-Content -LiteralPath (Join-Path $Root $Path) -Raw -Encoding UTF8
    if ($Text -match 'cfg\.Database\.Pass') {
        throw "$Path must not sign tokens with cfg.Database.Pass"
    }
}

$SessionText = Get-Content -LiteralPath (Join-Path $Root 'internal/session/session.go') -Raw -Encoding UTF8
if ($SessionText -match 'sess_' -or $SessionText -match 'parsePHPSession' -or $SessionText -match 'SESSION_DIR') {
    throw 'session reader must not retain legacy file-session compatibility hooks'
}

$SessionRepoText = Get-Content -LiteralPath (Join-Path $Root 'internal/repositories/sessions.go') -Raw -Encoding UTF8
$MigrationText = Get-Content -LiteralPath (Join-Path $Root 'internal/database/migrations/001_app_sessions.sql') -Raw -Encoding UTF8
if ($MigrationText -notmatch 'CREATE TABLE IF NOT EXISTS app_sessions') {
    throw 'app_sessions must be provisioned by a versioned migration'
}

if ($RouterText -match 'legacy\.Proxy' -or $RouterText -match 'LOC_LEGACY_BASE_URL') {
    throw 'v3 router must not retain legacy backend fallback'
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
if ([string]::IsNullOrWhiteSpace($GoExe)) {
    $StandardGo = Join-Path $env:ProgramFiles 'Go\bin\go.exe'
    if (Test-Path -LiteralPath $StandardGo -PathType Leaf) {
        $GoExe = $StandardGo
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

if (-not $StaticOnly) {
    throw 'Go toolchain not found. Install Go, set LOC_GO_EXE, or explicitly pass -StaticOnly for non-release checks.'
}

Write-Output 'verify-v3 static checks OK (Go compile and tests were explicitly skipped)'
