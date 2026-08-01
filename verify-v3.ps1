param(
    [switch]$StaticOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$GoWorkRoot = Join-Path $Root '.go-work'
$GoPath = if ($env:LOC_GO_PATH) { $env:LOC_GO_PATH } else { Join-Path $GoWorkRoot 'go' }
$GoModCache = if ($env:LOC_GO_MOD_CACHE) { $env:LOC_GO_MOD_CACHE } else { Join-Path $GoPath 'pkg\mod' }
$GoBuildCache = if ($env:LOC_GO_BUILD_CACHE) { $env:LOC_GO_BUILD_CACHE } else { Join-Path $GoWorkRoot 'build-cache' }
$GoTmp = if ($env:LOC_GO_TMP) { $env:LOC_GO_TMP } else { Join-Path $GoWorkRoot 'tmp' }
foreach ($directory in @($GoPath, $GoModCache, $GoBuildCache, $GoTmp)) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
}
$env:GOPATH = $GoPath
$env:GOMODCACHE = $GoModCache
$env:GOCACHE = $GoBuildCache
$env:GOTMPDIR = $GoTmp
$env:GOENV = 'off'
$env:GOTOOLCHAIN = 'local'
$env:GOTELEMETRY = 'off'
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
    'internal/database/migrations/002_location_shares.sql',
    'internal/database/migrations/003_location_share_plaintext.sql',
    'internal/database/migrations/004_location_share_quota_indexes.sql',
    'internal/database/migrations/005_support_ticket_quota_indexes.sql',
    'internal/database/migrations/006_group_code_entropy.sql',
    'internal/database/migrations/007_heartbeat_log_indexes.sql',
    'internal/database/migrations/008_app_sessions_user_id_index.sql',
    'internal/database/migrations/009_location_retention_index.sql',
    'internal/database/migrations/010_environment_report_retention_index.sql',
    'internal/database/migrations/011_group_code_alias.sql',
    'internal/database/migrations/012_location_history_time_index.sql',
    'internal/database/migrations/013_disable_unbound_invites.sql',
    'deploy/family-location-go.service.sample',
    'deploy/nginx-go-backend.sample.conf',
    'internal/httpx/json.go',
    'internal/httpx/request.go',
    'internal/middleware/middleware.go',
    'internal/session/session.go',
    'internal/handlers/updates.go',
    'internal/handlers/challenge.go',
    'internal/handlers/challenge_setting_test.go',
    'internal/handlers/login.go',
    'internal/handlers/register.go',
    'internal/handlers/groups.go',
    'internal/handlers/report_location.go',
    'internal/handlers/tickets.go',
    'internal/handlers/p2p.go',
    'internal/handlers/webviews.go',
	'internal/handlers/shares.go',
    'internal/handlers/admin_manage.go',
    'internal/handlers/admin_summary.go',
    'internal/handlers/admin_heartbeat.go',
    'internal/handlers/admin_heartbeat_test.go',
    'internal/handlers/templates/history_map.html',
    'internal/handlers/templates/amap_reverse.html',
    'internal/handlers/templates/webrtc_probe.html',
	'internal/handlers/templates/location_share.html',
	'internal/handlers/templates/location_share_unlock.html',
    'internal/handlers/announcements.go',
    'internal/handlers/events.go',
    'internal/handlers/events_test.go',
    'internal/handlers/read_rate_limit.go',
    'internal/handlers/read_rate_limit_test.go',
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
    'internal/repositories/support_tickets.go',
    'internal/httpx/client.go',
    'internal/httpx/json_test.go',
    'internal/middleware/write_freeze.go',
    'internal/middleware/write_freeze_test.go',
    'internal/services/users.go',
    'internal/services/passwords.go',
    'internal/services/device_policy.go',
    'internal/services/ipgeo.go',
    'internal/models/models.go'
    'internal/session/store.go'
	'internal/repositories/sessions.go'
	'internal/repositories/shares.go'
	'internal/config/config_test.go'
	'internal/database/schema_test.go'
	'internal/httpx/request_test.go'
	'internal/session/session_test.go'
	'internal/handlers/webviews_test.go'
	'internal/handlers/shares_test.go'
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

$NginxSampleText = Get-Content -LiteralPath (Join-Path $Root 'deploy/nginx-go-backend.sample.conf') -Raw -Encoding UTF8
if ($NginxSampleText -notmatch 'include /etc/nginx/snippets/family-location-cloudflare-realip\.conf;') {
    throw 'public Nginx sample must require the external Cloudflare real-IP snippet.'
}
if ($NginxSampleText -match 'location \^~ /_ShareMapService/') {
    throw 'public Nginx sample must not include a catch-all third-party map proxy.'
}
if ($NginxSampleText -notmatch 'location = /api/events[\s\S]*proxy_set_header Upgrade \$http_upgrade;[\s\S]*proxy_set_header Connection "upgrade";') {
    throw 'public Nginx sample must document the exact authenticated WebSocket upgrade route.'
}
if ($NginxSampleText.IndexOf('location = /api/events', [StringComparison]::Ordinal) -gt $NginxSampleText.IndexOf('location ^~ /api/', [StringComparison]::Ordinal)) {
    throw 'public Nginx sample must place the exact event route before the general API route.'
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
    '/api/events',
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
    '/api/admin/heartbeat',
    '/api/admin/apk',
    '/api/geo-aliases',
    '/api/ip-probe',
    '/api/ip-geo',
    '/api/ipinfo-lite',
    '/api/cloudflare-location',
	'/api/share',
	'/share',
    '/api/'
)

foreach ($Route in $ExpectedRoutes) {
    if ($RouterText -notmatch [regex]::Escape($Route)) {
        throw "v3 router is missing route: $Route"
    }
}

$ChallengeToggleText = @(
    (Get-Content -LiteralPath (Join-Path $Root 'internal/repositories/settings.go') -Raw -Encoding UTF8),
    (Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/challenge.go') -Raw -Encoding UTF8),
    (Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/login.go') -Raw -Encoding UTF8),
    (Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/register.go') -Raw -Encoding UTF8),
    (Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/admin_manage.go') -Raw -Encoding UTF8),
    (Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/admin_summary.go') -Raw -Encoding UTF8)
) -join "`n"
foreach ($Control in @(
    'AppChallengeRequiredSettingKey',
    'AppChallengeRequired(ctx context.Context)',
    'update_cf_challenge',
    'challenge_settings',
    'appChallengeRequired(r.Context(), handler.settings)'
)) {
    if ($ChallengeToggleText -notmatch [regex]::Escape($Control)) {
        throw "v3 Cloudflare challenge toggle is missing control: $Control"
    }
}

$EventsText = Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/events.go') -Raw -Encoding UTF8
if ($EventsText -notmatch 'websocket\.Upgrader' -or $EventsText -notmatch 'handler\.scope\.requireUser' -or $EventsText -notmatch 'eventMaxConnectionsPerUser') {
    throw 'event streaming must retain WebSocket upgrade, session authentication, and per-user connection caps.'
}
$HistoryMapText = Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/templates/history_map.html') -Raw -Encoding UTF8
if ($HistoryMapText -match 'networkMarker|normalizeDiagnosticSource|diagnostics\.preferred_address') {
    throw 'history maps must not render IP/WebRTC coordinates or preferred network addresses.'
}
if ($HistoryMapText -notmatch 'const gpsSource = firstGpsSource\(diagnostics\)') {
    throw 'history map labels must resolve from GPS diagnostics only.'
}
$UserMainText = Get-Content -LiteralPath (Join-Path $Root 'android-client\src\com\familylocation\client\MainActivity.java') -Raw -Encoding UTF8
if ($UserMainText -notmatch 'mapRenderRecordsByWebView\.put\(map, recordsJson\)' -or
    $UserMainText -notmatch 'renderMapRecords\(view, latestRecordsJson == null \? recordsJson : latestRecordsJson\)' -or
    $UserMainText -notmatch 'mapRenderRecordsByWebView\.remove\(webView\)') {
    throw 'recreated map WebViews must replay their latest trajectory payload and release it during teardown.'
}

$UpdateText = Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/updates.go') -Raw -Encoding UTF8
if ($UpdateText -notmatch 'sessions\.IsAdmin') {
    throw 'admin_app_update handler must keep admin session protection'
}

$AdminHeartbeatText = Get-Content -LiteralPath (Join-Path $Root 'internal/handlers/admin_heartbeat.go') -Raw -Encoding UTF8
if ($AdminHeartbeatText -notmatch 'handler\.sessions\.IsAdmin' -or
    $RouterText -notmatch 'POST /api/admin/heartbeat') {
    throw 'admin heartbeat must require an administrator session and remain routed.'
}

$ConfigText = Get-Content -LiteralPath (Join-Path $Root 'internal/config/config.go') -Raw -Encoding UTF8
if ($ConfigText -notmatch 'LOC_APP_SIGNING_SECRET') {
    throw 'v3 config must expose a dedicated app signing secret'
}

$IPGeoQuotaPrefixes = @(
    'LOC_IPINFO_LITE',
    'LOC_IP2LOCATION',
    'LOC_IPDATA',
    'LOC_IPREGISTRY',
    'LOC_IP_API',
    'LOC_UAPIS',
    'LOC_BAIDU_IP',
    'LOC_IPING',
    'LOC_XXAPI'
)
$IPGeoQuotaSuffixes = @(
    '_QUOTA_MAX_REQUESTS',
    '_QUOTA_RESERVE_REQUESTS',
    '_QUOTA_USER_MAX_MISSES',
    '_QUOTA_WINDOW_SECONDS'
)
foreach ($Prefix in $IPGeoQuotaPrefixes) {
    if ($ConfigText -notmatch [regex]::Escape($Prefix)) {
        throw "v3 config is missing provider quota prefix: $Prefix"
    }
}
foreach ($Suffix in $IPGeoQuotaSuffixes) {
    if ($ConfigText -notmatch [regex]::Escape($Suffix)) {
        throw "v3 config is missing optional provider quota suffix: $Suffix"
    }
}

$ReadmeText = Get-Content -LiteralPath (Join-Path $Root 'README.md') -Raw -Encoding UTF8
foreach ($Suffix in $IPGeoQuotaSuffixes) {
    if ($ReadmeText -notmatch [regex]::Escape($Suffix)) {
        throw "v3 README is missing optional provider quota documentation: $Suffix"
    }
}
$ServiceSampleText = Get-Content -LiteralPath (Join-Path $Root 'deploy/family-location-go.service.sample') -Raw -Encoding UTF8
if ($ServiceSampleText -notmatch 'Provider quota overrides are optional') {
    throw 'v3 systemd sample must keep provider quota overrides optional'
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
