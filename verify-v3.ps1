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
    'internal/handlers/announcements.go',
    'internal/handlers/invites.go',
    'internal/handlers/me.go',
    'internal/handlers/auth.go',
    'internal/handlers/settings.go',
    'internal/handlers/locations.go',
    'internal/handlers/history.go',
    'internal/handlers/legacy.go',
    'internal/repositories/users.go',
    'internal/repositories/groups.go',
    'internal/repositories/locations.go',
    'internal/repositories/announcements.go',
    'internal/repositories/invites.go',
    'internal/services/users.go',
    'internal/services/passwords.go',
    'internal/models/models.go'
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
    '/api/admin_app_update.php',
    '/api/announcement.php',
    '/api/invite_check.php',
    '/api/me.php',
    '/api/settings.php',
    '/api/locations.php',
    '/api/history.php',
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
