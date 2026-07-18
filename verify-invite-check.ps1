param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Text {
    param(
        [string] $Label,
        [string] $Text,
        [string] $Needle
    )

    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) {
        throw "verify-invite-check failed: $Label is missing $Needle"
    }
}

function Forbid-Text {
    param(
        [string] $Label,
        [string] $Text,
        [string] $Needle
    )

    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -ge 0) {
        throw "verify-invite-check failed: $Label still contains $Needle"
    }
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Paths = @{
    InviteCheck = Join-Path $Root 'api\invite_check.php'
    Register = Join-Path $Root 'api\register.php'
    Groups = Join-Path $Root 'api\groups.php'
    AdminManage = Join-Path $Root 'api\admin_manage.php'
    AdminController = Join-Path $Root 'private\admin\controller.php'
    Config = Join-Path $Root 'private\config.php'
    Bootstrap = Join-Path $Root 'private\lib\bootstrap.php'
    Install = Join-Path $Root 'private\install.sql'
    AdminIndex = Join-Path $Root 'admin\index.php'
}
foreach ($RequiredPath in $Paths.Values) {
    if (-not (Test-Path -LiteralPath $RequiredPath -PathType Leaf)) {
        throw "verify-invite-check failed: required file is missing: $RequiredPath"
    }
}

$InviteCheckText = Get-Content -LiteralPath $Paths.InviteCheck -Raw -Encoding UTF8
$QueriesValidity = $InviteCheckText.Contains('invite_codes') -or
    $InviteCheckText.Contains('SELECT ') -or
    $InviteCheckText.Contains('db()') -or
    $InviteCheckText.Contains('prepare' + [char] 40)
$UsesGenericGuidance = $InviteCheckText.Contains("'requires_group_name' => true") -and
    $InviteCheckText.Contains("'requires_group_code' => true")
if ($QueriesValidity -or -not $UsesGenericGuidance) {
    throw 'verify-invite-check failed: preflight must not query invite validity.'
}

$RegisterText = Get-Content -LiteralPath $Paths.Register -Raw -Encoding UTF8
$RegistrationStart = $RegisterText.IndexOf('$pdo->beginTransaction();', [StringComparison]::Ordinal)
$RegistrationEnd = $RegisterText.IndexOf('INSERT INTO users', $RegistrationStart, [StringComparison]::Ordinal)
if ($RegistrationStart -lt 0 -or $RegistrationEnd -le $RegistrationStart) {
    throw 'verify-invite-check failed: registration invite boundary was not found.'
}
$RegistrationBoundary = $RegisterText.Substring($RegistrationStart, $RegistrationEnd - $RegistrationStart)
if ([regex]::Matches($RegistrationBoundary, '\$rejectInviteRegistration\(\);').Count -ne 4 -or
    [regex]::Matches($RegistrationBoundary, 'json_response\(').Count -ne 1 -or
    $RegistrationBoundary.Contains(', 404)') -or
    $RegistrationBoundary.Contains(', 422)')) {
    throw 'verify-invite-check failed: registration pre-consumption failures must be indistinguishable.'
}

$BootstrapText = Get-Content -LiteralPath $Paths.Bootstrap -Raw -Encoding UTF8
$ConfigText = Get-Content -LiteralPath $Paths.Config -Raw -Encoding UTF8
$RotationStart = $BootstrapText.IndexOf('function generate_lower_alphanumeric_code', [StringComparison]::Ordinal)
$RotationEnd = $BootstrapText.IndexOf('function ensure_family_group_owners', $RotationStart, [StringComparison]::Ordinal)
if ($RotationStart -lt 0 -or $RotationEnd -le $RotationStart) {
    throw 'verify-invite-check failed: group-code compatibility boundary was not found.'
}
$RotationText = $BootstrapText.Substring($RotationStart, $RotationEnd - $RotationStart)
foreach ($Needle in @(
    'generate_lower_alphanumeric_code(8)',
    'generate_group_code_candidate',
    'bin2hex(random_bytes(16))',
    'legacy_group_code',
    'beginTransaction()',
    'ORDER BY id ASC',
    'FOR UPDATE',
    'COALESCE(group_code',
    'COALESCE(legacy_group_code',
    'pdo_duplicate_key_for',
    "'group_code'",
    'rowCount() !== 1',
    'commit()',
    'rollBack()',
    '[0-9a-z]{8}',
    '[0-9a-f]{32}',
    'group_code = ? OR legacy_group_code = ?'
)) {
    Require-Text 'group-code compatibility logic' $RotationText $Needle
}
Forbid-Text 'group-code compatibility logic' $RotationText 'migration_group_codes_32_v1'
foreach ($Needle in @(
    'LOC_GROUP_CODE_BACKFILL_ENABLED',
    'group_code_backfill_is_current($pdo)',
    'GROUP_CODE_BACKFILL_SETTING_KEY'
)) {
    Require-Text 'staged group-code rollout' $BootstrapText $Needle
}
foreach ($Needle in @(
    '$locGroupCodeBackfillEnabled = true;',
    "getenv('LOC_GROUP_CODE_BACKFILL_ENABLED')",
    '$locGroupCodeBackfillEnabled = $locGroupCodeBackfillParsed;'
)) {
    Require-Text 'staged group-code configuration' $ConfigText $Needle
}
Forbid-Text 'staged group-code configuration' $ConfigText '$locGroupCodeBackfillEnabled = false;'

$EnsureStart = $BootstrapText.IndexOf('function ensure_family_group_codes', [StringComparison]::Ordinal)
$EnsureEnd = $BootstrapText.IndexOf('function is_valid_family_group_code', $EnsureStart, [StringComparison]::Ordinal)
if ($EnsureStart -lt 0 -or $EnsureEnd -le $EnsureStart) {
    throw 'verify-invite-check failed: the transactional group-code migration was not found.'
}
$EnsureText = $BootstrapText.Substring($EnsureStart, $EnsureEnd - $EnsureStart)
foreach ($Needle in @(
    'CHAR_LENGTH(group_code) <> 8',
    'CHAR_LENGTH(legacy_group_code) <> 32',
    'group_code COLLATE utf8mb4_bin NOT REGEXP',
    'legacy_group_code COLLATE utf8mb4_bin NOT REGEXP',
    'SET legacy_group_code = ?, group_code = ?'
)) {
    Require-Text 'transactional group-code migration' $EnsureText $Needle
}

$GroupsText = Get-Content -LiteralPath $Paths.Groups -Raw -Encoding UTF8
foreach ($ApiText in @($RegisterText, $GroupsText)) {
    Require-Text 'group join API' $ApiText 'is_valid_family_group_code'
    Require-Text 'group join API' $ApiText 'find_family_group_by_code'
}

$InstallText = Get-Content -LiteralPath $Paths.Install -Raw -Encoding UTF8
foreach ($SchemaText in @($BootstrapText, $InstallText)) {
    Require-Text 'group-code schema' $SchemaText 'group_code VARCHAR(32) NULL UNIQUE'
    Require-Text 'group-code schema' $SchemaText 'legacy_group_code VARCHAR(32) NULL'
    Require-Text 'group-code schema' $SchemaText 'UNIQUE KEY uniq_family_groups_legacy_group_code (legacy_group_code)'
}

$InviteStart = $BootstrapText.IndexOf('function create_invite_code_record', [StringComparison]::Ordinal)
if ($InviteStart -lt 0 -or $InviteStart -ge $RotationEnd) {
    throw 'verify-invite-check failed: shared invite-code creation helper was not found.'
}
$InviteText = $BootstrapText.Substring($InviteStart, $RotationEnd - $InviteStart)
foreach ($Needle in @(
    'strtolower(trim($requestedCode))',
    '[0-9a-z]{4,64}',
    'generate_lower_alphanumeric_code(8)',
    'pdo_duplicate_key_for',
    "'code'"
)) {
    Require-Text 'invite-code creation helper' $InviteText $Needle
}

$AdminManageText = Get-Content -LiteralPath $Paths.AdminManage -Raw -Encoding UTF8
$AdminControllerText = Get-Content -LiteralPath $Paths.AdminController -Raw -Encoding UTF8
foreach ($AdminText in @($AdminManageText, $AdminControllerText)) {
    Require-Text 'admin invite path' $AdminText 'create_invite_code_record'
}
Forbid-Text 'admin API invite path' $AdminManageText "admin_manage_string(`$data, 'code'"
Forbid-Text 'web admin invite path' $AdminControllerText "post_string('code'"
foreach ($Needle in @('inviteErrorStatuses', '=> 422', '=> 409')) {
    Require-Text 'admin API invite validation response' $AdminManageText $Needle
}
Require-Text 'registration invite compatibility' $RegisterText '/^[0-9a-zA-Z]{1,255}$/'

$AdminIndexText = Get-Content -LiteralPath $Paths.AdminIndex -Raw -Encoding UTF8
foreach ($Needle in @(
    'placeholder=',
    'maxlength="64"',
    'pattern="[0-9A-Za-z]{4,64}"'
)) {
    Require-Text 'invite admin UI' $AdminIndexText $Needle
}

$SettingsSchema = $BootstrapText.IndexOf('CREATE TABLE IF NOT EXISTS app_settings', [StringComparison]::Ordinal)
$RotationCall = $BootstrapText.LastIndexOf('ensure_family_group_codes($pdo);', [StringComparison]::Ordinal)
$AliasColumnCall = $BootstrapText.IndexOf("legacy_group_code', 'VARCHAR(32) NULL AFTER", [StringComparison]::Ordinal)
$AliasIndexCall = $BootstrapText.IndexOf('add_unique_index_if_missing($pdo', [StringComparison]::Ordinal)
if ($SettingsSchema -lt 0 -or
    $AliasColumnCall -lt 0 -or
    $AliasIndexCall -lt 0 -or
    $RotationCall -le $SettingsSchema -or
    $RotationCall -le $AliasColumnCall -or
    $RotationCall -le $AliasIndexCall) {
    throw 'verify-invite-check failed: group-code migration must run only after the runtime schema is ready.'
}

Write-Host 'verify-invite-check OK'
