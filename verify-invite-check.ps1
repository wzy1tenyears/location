param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Path = Join-Path $Root 'api\invite_check.php'
$RegisterPath = Join-Path $Root 'api\register.php'
$BootstrapPath = Join-Path $Root 'private\lib\bootstrap.php'
foreach ($RequiredPath in @($Path, $RegisterPath, $BootstrapPath)) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "verify-invite-check failed: required file is missing: $RequiredPath"
    }
}

$Text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
$QueriesValidity = $Text.Contains('invite_codes') -or
    $Text.Contains('SELECT ') -or
    $Text.Contains('db()') -or
    $Text.Contains('prepare' + [char] 40)
$UsesGenericGuidance = $Text.Contains("'requires_group_name' => true") -and
    $Text.Contains("'requires_group_code' => true")
if ($QueriesValidity -or -not $UsesGenericGuidance) {
    throw 'verify-invite-check failed: preflight must not query invite validity.'
}

$RegisterText = Get-Content -LiteralPath $RegisterPath -Raw -Encoding UTF8
$RegistrationStart = $RegisterText.IndexOf('$pdo->beginTransaction();', [StringComparison]::Ordinal)
$RegistrationEnd = $RegisterText.IndexOf('INSERT INTO users', $RegistrationStart, [StringComparison]::Ordinal)
if ($RegistrationStart -lt 0 -or $RegistrationEnd -le $RegistrationStart) {
    throw 'verify-invite-check failed: registration invite boundary was not found.'
}
$InviteBoundary = $RegisterText.Substring($RegistrationStart, $RegistrationEnd - $RegistrationStart)
if ([regex]::Matches($InviteBoundary, '\$rejectInviteRegistration\(\);').Count -ne 4 -or
    [regex]::Matches($InviteBoundary, 'json_response\(').Count -ne 1 -or
    $InviteBoundary.Contains(', 404)') -or
    $InviteBoundary.Contains(', 422)')) {
    throw 'verify-invite-check failed: registration pre-consumption failures must be indistinguishable.'
}

$BootstrapText = Get-Content -LiteralPath $BootstrapPath -Raw -Encoding UTF8
$RotationStart = $BootstrapText.IndexOf('function ensure_family_group_codes', [StringComparison]::Ordinal)
$RotationEnd = $BootstrapText.IndexOf('function ensure_family_group_owners', $RotationStart, [StringComparison]::Ordinal)
if ($RotationStart -lt 0 -or $RotationEnd -le $RotationStart) {
    throw 'verify-invite-check failed: group-code rotation boundary was not found.'
}
$RotationBoundary = $BootstrapText.Substring($RotationStart, $RotationEnd - $RotationStart)
foreach ($RequiredControl in @('migration_group_codes_32_v1', 'app_settings', 'beginTransaction()', 'FOR UPDATE', 'AND (', 'commit()', 'rollBack()')) {
    if (-not $RotationBoundary.Contains($RequiredControl)) {
        throw "verify-invite-check failed: group-code rotation is missing $RequiredControl"
    }
}
$SettingsSchema = $BootstrapText.IndexOf('CREATE TABLE IF NOT EXISTS app_settings', [StringComparison]::Ordinal)
$RotationCall = $BootstrapText.IndexOf('ensure_family_group_codes($pdo);', [StringComparison]::Ordinal)
if ($SettingsSchema -lt 0 -or $RotationCall -le $SettingsSchema) {
    throw 'verify-invite-check failed: group-code migration must run after its completion marker table exists.'
}

Write-Host 'verify-invite-check OK'
