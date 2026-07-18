param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail {
    param([string]$Message)
    throw "verify-public failed: $Message"
}

function Resolve-RequiredFile {
    param(
        [string]$Path,
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "missing required $Name`: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )

    if ($Text -notmatch $Pattern) {
        Fail "${Name}: $Message"
    }
}

function Assert-NotContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )

    if ($Text -match $Pattern) {
        Fail "${Name}: $Message"
    }
}

function Assert-MatchCount {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [int]$Expected,
        [string]$Message
    )

    $actual = [regex]::Matches($Text, $Pattern).Count
    if ($actual -ne $Expected) {
        Fail "${Name}: $Message Expected $Expected, found $actual."
    }
}

function Text-BetweenMarkers {
    param(
        [string]$Text,
        [string]$StartMarker,
        [string]$EndMarker,
        [string]$Name
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        Fail "${Name}: start marker not found."
    }
    $end = $Text.IndexOf($EndMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    if ($end -lt 0) {
        Fail "${Name}: end marker not found."
    }
    return $Text.Substring($start, $end - $start)
}

function Test-RoutableIpv4Literal {
    param([string]$Value)

    $address = $null
    if (-not [System.Net.IPAddress]::TryParse($Value, [ref]$address)) {
        return $false
    }
    if ($address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        return $false
    }

    $bytes = $address.GetAddressBytes()
    if ($bytes[0] -in @(0, 10, 127) -or $bytes[0] -ge 224) {
        return $false
    }
    if ($bytes[0] -eq 100 -and $bytes[1] -ge 64 -and $bytes[1] -le 127) {
        return $false
    }
    if ($bytes[0] -eq 169 -and $bytes[1] -eq 254) {
        return $false
    }
    if ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) {
        return $false
    }
    if ($bytes[0] -eq 192 -and $bytes[1] -eq 168) {
        return $false
    }
    if (($bytes[0] -eq 192 -and $bytes[1] -eq 0 -and $bytes[2] -eq 2) -or
        ($bytes[0] -eq 198 -and $bytes[1] -eq 51 -and $bytes[2] -eq 100) -or
        ($bytes[0] -eq 203 -and $bytes[1] -eq 0 -and $bytes[2] -eq 113)) {
        return $false
    }
    return $true
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $Root 'verify-invite-check.ps1')
$UserMain = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\MainActivity.java") "user MainActivity.java"
$UserManifest = Resolve-RequiredFile (Join-Path $Root "android-client\AndroidManifest.xml") "user AndroidManifest.xml"
$P2PCrypto = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\P2PCryptoSupport.java") "P2PCryptoSupport.java"
$P2PPolicy = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\P2PRecordMergePolicy.java") "P2PRecordMergePolicy.java"
$P2PPolicyTest = Resolve-RequiredFile (Join-Path $Root "android-client\tests\com\familylocation\client\P2PRecordMergePolicyTest.java") "P2PRecordMergePolicyTest.java"
$ReportAttemptGate = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\ReportAttemptGate.java") "ReportAttemptGate.java"
$ReportAttemptGateTest = Resolve-RequiredFile (Join-Path $Root "android-client\tests\com\familylocation\client\ReportAttemptGateTest.java") "ReportAttemptGateTest.java"
$DiagnosticSourcePolicy = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\DiagnosticSourcePolicy.java") "DiagnosticSourcePolicy.java"
$DiagnosticSourcePolicyTest = Resolve-RequiredFile (Join-Path $Root "android-client\tests\com\familylocation\client\DiagnosticSourcePolicyTest.java") "DiagnosticSourcePolicyTest.java"
$UserBuild = Resolve-RequiredFile (Join-Path $Root "android-client\build.ps1") "user build.ps1"
$AdminBuild = Resolve-RequiredFile (Join-Path $Root "android-admin-client\build.ps1") "admin build.ps1"
$UserServerUrl = Resolve-RequiredFile (Join-Path $Root "android-client\assets\server-url.txt") "user server-url.txt"
$AdminServerUrl = Resolve-RequiredFile (Join-Path $Root "android-admin-client\assets\server-url.txt") "admin server-url.txt"
$NginxConfig = Resolve-RequiredFile (Join-Path $Root "nginx-location.conf") "nginx-location.conf"
$Bootstrap = Resolve-RequiredFile (Join-Path $Root "private\lib\bootstrap.php") "PHP bootstrap"
$PrivateConfig = Resolve-RequiredFile (Join-Path $Root "private\config.php") "PHP config"
$InstallSql = Resolve-RequiredFile (Join-Path $Root "private\install.sql") "PHP install schema"
$RegisterApi = Resolve-RequiredFile (Join-Path $Root "api\register.php") "registration API"
$GroupsApi = Resolve-RequiredFile (Join-Path $Root "api\groups.php") "groups API"
$InviteCheckApi = Resolve-RequiredFile (Join-Path $Root "api\invite_check.php") "invite-check API"
$HistoryApi = Resolve-RequiredFile (Join-Path $Root "api\history.php") "history API"
$HistoryMap = Resolve-RequiredFile (Join-Path $Root "api\history_map_webview.php") "history map WebView"
$ReportLocationApi = Resolve-RequiredFile (Join-Path $Root "api\report_location.php") "location report API"
$HistoryStayPolicy = Resolve-RequiredFile (Join-Path $Root "private\lib\history_stays.php") "history stay policy"
$HistoryStayTest = Resolve-RequiredFile (Join-Path $Root "tests\php\history_stays_test.php") "history stay regression test"
$HistoryPayloadPolicyTest = Resolve-RequiredFile (Join-Path $Root "tests\php\history_payload_policy_test.php") "history payload policy regression test"
$AddressDiagnosticsTest = Resolve-RequiredFile (Join-Path $Root "tests\php\address_diagnostics_test.php") "address diagnostics regression test"
$GroupBackfillConfigTest = Resolve-RequiredFile (Join-Path $Root "tests\php\group_backfill_config_test.php") "group backfill config regression test"
$AdminIndex = Resolve-RequiredFile (Join-Path $Root "admin\index.php") "admin index"
$Readme = Resolve-RequiredFile (Join-Path $Root "README.md") "README"
$Agents = Resolve-RequiredFile (Join-Path $Root "AGENTS.md") "AGENTS"
$AdminUpdateApi = Resolve-RequiredFile (Join-Path $Root "api\admin_app_update.php") "admin update API"
$AdminApkApi = Resolve-RequiredFile (Join-Path $Root "api\admin_apk.php") "admin APK API"

$UserMainText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserMain
$UserManifestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserManifest
$P2PCryptoText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PCrypto
$P2PPolicyText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PPolicy
$P2PPolicyTestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PPolicyTest
$ReportAttemptGateText = Get-Content -Raw -Encoding UTF8 -LiteralPath $ReportAttemptGate
$ReportAttemptGateTestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $ReportAttemptGateTest
$DiagnosticSourcePolicyText = Get-Content -Raw -Encoding UTF8 -LiteralPath $DiagnosticSourcePolicy
$DiagnosticSourcePolicyTestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $DiagnosticSourcePolicyTest
$UserBuildText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserBuild
$AdminBuildText = Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminBuild
$NginxText = Get-Content -Raw -Encoding UTF8 -LiteralPath $NginxConfig
$BootstrapText = Get-Content -Raw -Encoding UTF8 -LiteralPath $Bootstrap
$PrivateConfigText = Get-Content -Raw -Encoding UTF8 -LiteralPath $PrivateConfig
$InstallSqlText = Get-Content -Raw -Encoding UTF8 -LiteralPath $InstallSql
$RegisterApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $RegisterApi
$GroupsApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $GroupsApi
$InviteCheckApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $InviteCheckApi
$HistoryApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $HistoryApi
$HistoryMapText = Get-Content -Raw -Encoding UTF8 -LiteralPath $HistoryMap
$ReportLocationApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $ReportLocationApi
$HistoryStayPolicyText = Get-Content -Raw -Encoding UTF8 -LiteralPath $HistoryStayPolicy
$HistoryStayTestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $HistoryStayTest
$AdminIndexText = Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminIndex
$ReadmeText = Get-Content -Raw -Encoding UTF8 -LiteralPath $Readme
$AgentsText = Get-Content -Raw -Encoding UTF8 -LiteralPath $Agents
$AdminUpdateApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminUpdateApi
$AdminApkApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminApkApi

# Public builds must not report installed package names or embed private endpoints.
Assert-Contains 'User manifest' $UserManifestText 'android:versionCode="86"' 'the security client release must use versionCode 86.'
Assert-Contains 'User app source' $UserMainText 'APP_VERSION_CODE = 86;' 'the client update constant must match the manifest.'
Assert-Contains 'PHP update gate' $PrivateConfigText 'ANDROID_VERSION_CODE = 86;' 'the server update gate must match the manifest.'
Assert-Contains 'Admin build' $AdminBuildText '\$OutputApk = Join-Path \$ProjectRoot "private\\location-admin-release\.apk"' 'the default build output must match the protected admin download path.'
Assert-Contains 'PHP admin update gate' $PrivateConfigText "ANDROID_ADMIN_APK_FILENAME = 'location-admin-release\.apk';" 'the admin update filename must match the build output.'
Assert-Contains 'Admin update API' $AdminUpdateApiText "'private' \. DIRECTORY_SEPARATOR \. ANDROID_ADMIN_APK_FILENAME" 'the update gate must inspect the protected admin APK path.'
Assert-Contains 'Admin APK API' $AdminApkApiText "'private' \. DIRECTORY_SEPARATOR \. ANDROID_ADMIN_APK_FILENAME" 'the download endpoint must read the protected admin APK path.'
Assert-NotContains 'User app source' $UserMainText 'installedAppsSummary\(' 'the public client must not enumerate installed applications.'
if ($UserMainText -match 'app\.put\("package_name"') {
    Fail "public user app must not serialize installed app package_name values."
}

$allowedLiteralHosts = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($hostName in @(
    'example.com',
    'schemas.android.com',
    'challenges.cloudflare.com',
    'ipinfo.io',
    'api.ipinfo.io',
    'www.ip2location.io',
    'api.ip2location.io',
    'ipdata.co',
    'api.ipdata.co',
    'ipregistry.co',
    'api.ipregistry.co',
    'ip-api.com',
    'lbs.amap.com',
    'webapi.amap.com',
    'webrd01.is.autonavi.com',
    'fmap01.amap.com',
    'restapi.amap.com',
    'apimobile.meituan.com',
    'api.bigdatacloud.net',
    'fsf.org',
    'www.gnu.org'
)) {
    [void] $allowedLiteralHosts.Add($hostName)
}
$textExtensions = @('', '.conf', '.css', '.env', '.gradle', '.java', '.js', '.json', '.md', '.php', '.pro', '.properties', '.ps1', '.sql', '.txt', '.xml', '.yaml', '.yml')
$textFiles = Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object {
    $_.FullName -notmatch '\\.git(?:\\|$)' -and
    $_.FullName -notmatch '\\build\\' -and
    $_.FullName -notmatch '\\bin\\' -and
    ($textExtensions -contains $_.Extension.ToLowerInvariant())
}
foreach ($file in $textFiles) {
    $content = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName -ErrorAction SilentlyContinue
    if ($null -eq $content) {
        continue
    }

    foreach ($urlMatch in [regex]::Matches($content, '(?i)https?://[a-z0-9.-]+(?::[0-9]+)?')) {
        $literalUri = $null
        if ([System.Uri]::TryCreate($urlMatch.Value, [System.UriKind]::Absolute, [ref]$literalUri) -and
            -not $allowedLiteralHosts.Contains($literalUri.DnsSafeHost)) {
            Fail "public repository contains a non-allowlisted literal URL host in $($file.FullName)"
        }
    }
    foreach ($ipMatch in [regex]::Matches($content, '(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])')) {
        if (Test-RoutableIpv4Literal $ipMatch.Value) {
            Fail "public repository contains a routable IPv4 literal in $($file.FullName)"
        }
    }
}

if ((Get-Content -Raw -Encoding UTF8 -LiteralPath $UserServerUrl).Trim() -ne "") {
    Fail "public user server-url.txt must be empty by default."
}
if ((Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminServerUrl).Trim() -ne "") {
    Fail "public admin server-url.txt must be empty by default."
}
foreach ($credentialName in @('DB_PASS', 'ADMIN_PASSWORD', 'APP_USER_AGENT_TOKEN')) {
    Assert-Contains 'PHP public config' $PrivateConfigText ("(?m)^const[ \t]+" + [regex]::Escape($credentialName) + "[ \t]*=[ \t]*'';[ \t]*$") "public default must be empty: $credentialName"
}
Assert-MatchCount 'PHP app-token guard' $BootstrapText '\$token === ''''' 2 'both API and WebView app-token guards must fail closed when the token is empty.'
Assert-Contains 'PHP app-token guard' $BootstrapText 'App client token is not configured\.' 'an empty app token must return a configuration error.'
Assert-Contains 'README' $ReadmeText 'App 令牌留空时接口会以 503 拒绝服务' 'the fail-closed empty-token behavior must be documented.'

foreach ($docContract in @(
    @{ Name = 'README'; Text = $ReadmeText; Value = '8 位小写英文字母或数字组号' },
    @{ Name = 'README'; Text = $ReadmeText; Value = '32 位小写十六进制组号' },
    @{ Name = 'README'; Text = $ReadmeText; Value = '默认是 `true`' },
    @{ Name = 'README'; Text = $ReadmeText; Value = '显式设置为 `false`' },
    @{ Name = 'README'; Text = $ReadmeText; Value = '不超过 25 米' },
    @{ Name = 'README'; Text = $ReadmeText; Value = 'client_merge_snapshot' },
    @{ Name = 'README'; Text = $ReadmeText; Value = 'IP 和 WebRTC' },
    @{ Name = 'AGENTS'; Text = $AgentsText; Value = 'server-url.txt' },
    @{ Name = 'AGENTS'; Text = $AgentsText; Value = 'homeMapWebView' },
    @{ Name = 'AGENTS'; Text = $AgentsText; Value = 'ReportAttemptGate' },
    @{ Name = 'AGENTS'; Text = $AgentsText; Value = 'legacy_group_code' }
)) {
    Assert-Contains $docContract.Name $docContract.Text ([regex]::Escape([string] $docContract.Value)) "missing documented contract: $($docContract.Value)"
}

foreach ($forbiddenEndpoint in @(
    (Join-Path $Root 'api\environment_report.php'),
    (Join-Path $Root 'api\device_report.php')
)) {
    if (Test-Path -LiteralPath $forbiddenEndpoint) {
        Fail "public repository contains a private reporting endpoint: $forbiddenEndpoint"
    }
}

$ReportingSurfaceFiles = @(
    foreach ($relativeRoot in @('android-client\src', 'android-admin-client\src', 'api', 'private', 'admin')) {
        $surfaceRoot = Join-Path $Root $relativeRoot
        if (Test-Path -LiteralPath $surfaceRoot) {
            Get-ChildItem -LiteralPath $surfaceRoot -Recurse -File | Where-Object {
                $_.Extension -in @('.css', '.java', '.js', '.php', '.xml')
            }
        }
    }
)
$ReportingSurfaceText = ($ReportingSurfaceFiles | ForEach-Object {
    Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
}) -join "`n"
foreach ($forbiddenReportingPattern in @(
    'environment_report',
    'environment_reports',
    'device_report',
    'installed_apps',
    'environment_data_consent',
    'QUERY_ALL_PACKAGES',
    'getInstalledPackages',
    'security_policy_settings',
    'update_security_settings',
    'ban_root_users',
    'ban_adb_users',
    'ban_fake_location_users',
    'ban_accessibility_users',
    'ban_packet_capture_users',
    'ban_suspicious_packages_users'
)) {
    if ($ReportingSurfaceText -match $forbiddenReportingPattern) {
        Fail "public source still contains the private reporting surface: $forbiddenReportingPattern"
    }
}

# Registration may read the clipboard only after an explicit click, and invite validity is checked only on submit.
$RegistrationText = Text-BetweenMarkers $UserMainText 'private void showRegister()' 'private void offerClipboardInvite' 'registration screen'
$ClipboardOfferText = Text-BetweenMarkers $UserMainText 'private void offerClipboardInvite' 'private void applyConfirmedClipboardInvite' 'clipboard invite offer'
$ClipboardConfirmedText = Text-BetweenMarkers $UserMainText 'private void applyConfirmedClipboardInvite' 'private String readClipboardText' 'confirmed clipboard invite'
$InviteGuidanceText = Text-BetweenMarkers $UserMainText 'private void checkInviteCode' 'private void register' 'local invite guidance'
Assert-Contains 'Registration screen' $RegistrationText 'pasteInvite\.setOnClickListener\(view -> offerClipboardInvite\(' 'clipboard access must require a dedicated button click.'
Assert-NotContains 'Registration screen' $RegistrationText 'readClipboardText\(' 'opening registration must not read the clipboard.'
Assert-Contains 'Clipboard invite offer' $ClipboardOfferText 'showPopupDialog\(' 'the requested clipboard value must be shown behind a confirmation.'
Assert-MatchCount 'Clipboard invite offer' $ClipboardOfferText 'readClipboardText\(' 1 'the explicit action must read the clipboard exactly once.'
Assert-Contains 'Clipboard invite offer' $ClipboardOfferText '\(\) -> applyConfirmedClipboardInvite\(code,' 'only the confirmed parsed value may enter the form.'
Assert-NotContains 'Clipboard invite offer' $ClipboardOfferText 'getJson\(|postJson\(|runBackground\(' 'clipboard content must not trigger a network request.'
Assert-Contains 'Confirmed clipboard invite' $ClipboardConfirmedText '!code\.equals\(approvedCode\)' 'the confirmed value must be revalidated without a second clipboard read.'
Assert-Contains 'Confirmed clipboard invite' $ClipboardConfirmedText 'checkInviteCode\(code,' 'the confirmed value must flow only into local guidance.'
Assert-NotContains 'User app source' $UserMainText 'api/invite[_-]check(?:\.php)?|invite_check\.php' 'the app must not call the invite-validity preflight endpoint.'
Assert-NotContains 'Local invite guidance' $InviteGuidanceText 'getJson\(|postJson\(|runBackground\(' 'invite guidance must remain local.'
Assert-Contains 'Local invite guidance' $InviteGuidanceText 'groupName\.setEnabled\(true\)' 'local guidance must keep group-name entry available.'
Assert-Contains 'Local invite guidance' $InviteGuidanceText 'groupCode\.setEnabled\(true\)' 'local guidance must keep group-code entry available.'
Assert-Contains 'User app source' $UserMainText 'postJson\("api/register\.php", payload\)' 'registration submission must remain the server-side invite validation boundary.'
$AcceptedGroupCodeText = Text-BetweenMarkers $UserMainText 'private boolean isAcceptedGroupCode' 'private void confirmLeaveCurrentGroup' 'client group-code policy'
Assert-Contains 'Client group-code policy' $AcceptedGroupCodeText 'normalizeGroupCode\(groupCode\)' 'group codes must be normalized consistently.'
Assert-Contains 'Client group-code policy' $AcceptedGroupCodeText '\^\[0-9a-z\]\{8\}\$' 'new group codes must be eight lowercase alphanumeric characters.'
Assert-Contains 'Client group-code policy' $AcceptedGroupCodeText '\^\[0-9a-f\]\{32\}\$' 'existing 32-character hexadecimal aliases must remain accepted.'
Assert-MatchCount 'User app source' $UserMainText 'isAcceptedGroupCode\(' 3 'registration, authenticated join, and the policy declaration must share one group-code validator.'
Assert-NotContains 'User app source' $UserMainText '\^\[0-9a-z\]\{6\}\$' 'legacy six-character group-code validation must be absent.'

# Plaintext history is merged server-side; P2P history is merged only from a declared complete snapshot.
foreach ($field in @('first_reported_at', 'last_reported_at', 'stay_duration_seconds', 'report_count')) {
    Assert-Contains 'History API' $HistoryApiText ([regex]::Escape("'$field'")) "history response is missing stay field: $field"
}
Assert-Contains 'History API' $HistoryApiText 'history_compose_view\(' 'server history must merge before pagination.'
Assert-Contains 'History API' $HistoryApiText 'client_merge_snapshot' 'the server must accept an explicit complete-snapshot request.'
Assert-Contains 'History API' $HistoryApiText 'client_merge_complete' 'the server must declare whether a client-merge snapshot is complete.'
Assert-Contains 'History API' $HistoryApiText 'client_merge_history' 'the server must return the complete client-merge history under a distinct field.'
Assert-Contains 'History stay policy' $HistoryStayPolicyText 'HISTORY_STAY_RADIUS_METERS = 25\.0' 'the server stay radius must be 25 metres.'
Assert-Contains 'History stay policy' $HistoryStayPolicyText 'history_locations_share_stay\(\$anchor, \$candidate' 'stay drift must be checked against the first anchor.'
Assert-Contains 'History stay policy' $HistoryStayPolicyText '\. "\\0" \. history_location_coordinate_system\(\$row\)' 'coordinate systems must form independent stay partitions.'
Assert-Contains 'History diagnostic identity policy' $HistoryStayPolicyText 'history_diagnostic_source_identity\(' 'stay diagnostics must be grouped by source identity rather than type alone.'
Assert-Contains 'History diagnostic identity policy' $HistoryStayPolicyText 'history_merge_diagnostic_evidence\(' 'nested variants and candidates must be merged by evidence identity.'
Assert-NotContains 'History diagnostic identity policy' $HistoryStayPolicyText '\$bestSources\[\$type\]' 'different provider and STUN identities must not collapse into one source type bucket.'
Assert-Contains 'History stay regression test' $HistoryStayTestText 'source packages were mixed instead of selecting the precise package' 'the PHP harness must reject cross-provider field mixing.'
Assert-Contains 'History stay regression test' $HistoryStayTestText 'distinct STUN identities were collapsed' 'the PHP harness must retain separate STUN identities.'
Assert-Contains 'Location report API' $ReportLocationApiText 'encode_address_diagnostics_or_fail\(' 'address diagnostics must pass through the bounded encoder.'
Assert-Contains 'Location report API' $ReportLocationApiText "unset\(\`$source\['variants'\], \`$source\['candidates'\]\)" 'oversized nested probe evidence must be removed structurally.'
Assert-NotContains 'Location report API' $ReportLocationApiText 'substr\(\$addressDiagnosticsJson' 'address diagnostics JSON must never be byte-truncated.'
Assert-Contains 'Location report API' $ReportLocationApiText 'MAX_P2P_ENCRYPTED_PAYLOAD_BYTES' 'P2P report acceptance must use the shared payload byte limit.'
Assert-NotContains 'Location report API' $ReportLocationApiText 'strlen\(\$json\) > 500000' 'the obsolete P2P payload limit must not remain.'
Assert-Contains 'PHP config' $PrivateConfigText 'const MAX_P2P_ENCRYPTED_PAYLOAD_BYTES = 128 \* 1024;' 'the shared P2P payload limit must remain 128 KiB.'
$HistoryRequestText = Text-BetweenMarkers $UserMainText 'private void loadHomeHistorySummary' 'private void appendHomeHistoryLoading' 'history snapshot request'
$HistoryDecryptText = Text-BetweenMarkers $UserMainText 'private void decryptHistoryResponse' 'private void applyClientMergeHistorySnapshot' 'history snapshot acceptance'
Assert-Contains 'History snapshot request' $HistoryRequestText '\.put\("client_merge_snapshot", true\)' 'the client must explicitly request a complete snapshot.'
Assert-Contains 'History snapshot acceptance' $HistoryDecryptText 'client_merge_complete' 'the client must require the complete-snapshot marker.'
Assert-Contains 'History snapshot acceptance' $HistoryDecryptText 'client_merge_history' 'the client must use the distinct complete snapshot.'
Assert-Contains 'History snapshot acceptance' $HistoryDecryptText 'client_merge_applied' 'the client must expose the local-merge result state.'
Assert-Contains 'History diagnostic merge' $UserMainText 'DiagnosticSourcePolicy\.sourceMergeKey' 'merged stays must keep distinct provider, IP, and STUN identities.'
Assert-Contains 'History diagnostic merge' $UserMainText 'mergeDiagnosticNestedEvidence\(' 'merged stays must retain variants and candidates as structured evidence.'
Assert-Contains 'Home diagnostic display' $UserMainText 'mostPreciseDiagnosticSource\(' 'the home screen must select the most precise nested diagnostic source.'
Assert-Contains 'Diagnostic source policy test' $DiagnosticSourcePolicyTestText 'different WebRTC identities stay distinct' 'the Java regression test must cover WebRTC identity separation.'
Assert-Contains 'Diagnostic source policy test' $DiagnosticSourcePolicyTestText 'provider variants remain independently selectable' 'the Java regression test must cover provider evidence separation.'
Assert-Contains 'Report attempt lifecycle' $UserMainText 'onProviderDisabled\(String providerName\)[\s\S]*finishReport\(attemptToken' 'a disabled location provider must immediately release the report attempt.'
Assert-Contains 'Report attempt lifecycle' $UserMainText 'LocationProvider\.TEMPORARILY_UNAVAILABLE[\s\S]*finishReport\(attemptToken' 'an unavailable location provider must immediately release the report attempt.'
Assert-Contains 'Report attempt lifecycle' $UserMainText 'location = bestLastKnownLocation\(manager\);[\s\S]*catch \(Throwable throwable\)[\s\S]*finishReport\(attemptToken' 'last-known location failures must release the report attempt.'

# Decrypted P2P data may update location payload fields, never authoritative identity fields.
Assert-Contains 'P2P decryption' $P2PCryptoText 'if \(P2PRecordMergePolicy\.isAllowedLocationPayloadField\(key\)\)' 'decrypted fields must pass through the explicit merge allowlist.'
$allowedPayloadFields = @(
    'latitude', 'longitude', 'altitude', 'accuracy', 'heading', 'speed',
    'location_provider', 'location_time', 'location_mock_provider', 'location_coordinate_system',
    'vertical_accuracy', 'bearing_accuracy', 'speed_accuracy', 'address_diagnostics',
    'encrypted_at'
)
$authoritativeFields = @(
    'id', 'user_id', 'member_id', 'group_id', 'group_name', 'username', 'display_name',
    'role', 'role_label', 'created_at', 'updated_at', 'encryption_mode', 'encrypted_payload',
    'p2p_key_version', 'p2p_decrypted', 'encrypted_unreadable'
)
foreach ($field in $allowedPayloadFields) {
    Assert-Contains 'P2P merge allowlist' $P2PPolicyText ([regex]::Escape('"' + $field + '"')) "missing allowed payload field: $field"
}
foreach ($field in $authoritativeFields) {
    Assert-NotContains 'P2P merge allowlist' $P2PPolicyText ([regex]::Escape('"' + $field + '"')) "authoritative field must not be mergeable: $field"
    Assert-Contains 'P2P merge policy test' $P2PPolicyTestText ([regex]::Escape('"' + $field + '"')) "test must reject authoritative field: $field"
}
Assert-Contains 'P2P merge policy test' $P2PPolicyTestText 'assertAllowed\(field\)' 'the harness must exercise every allowed field.'
Assert-Contains 'P2P merge policy test' $P2PPolicyTestText 'assertRejected\(field\)' 'the harness must exercise every authoritative field.'
Assert-Contains 'P2P merge policy' $P2PPolicyText 'coordinatePartitionKey\(point\)' 'coordinate systems must form independent client-side stay partitions.'
Assert-Contains 'P2P merge policy test' $P2PPolicyTestText 'WGS points merge across an interleaved GCJ point' 'the Java harness must cover interleaved coordinate-system records.'
Assert-Contains 'History stay regression test' $HistoryStayTestText 'WGS points did not merge across an interleaved GCJ point' 'the PHP harness must cover interleaved coordinate-system records.'

$phpSource = ''
if (-not [string]::IsNullOrWhiteSpace($env:LOC_PHP)) {
    $phpSource = Resolve-RequiredFile $env:LOC_PHP 'PHP interpreter from LOC_PHP'
} else {
    $phpCommand = Get-Command 'php.exe' -ErrorAction SilentlyContinue
    if (-not $phpCommand) {
        $phpCommand = Get-Command 'php' -ErrorAction SilentlyContinue
    }
    if ($phpCommand) {
        $phpSource = $phpCommand.Source
    }
}
if ([string]::IsNullOrWhiteSpace($phpSource)) {
    Fail 'php is required to run the address diagnostics regression test; set LOC_PHP or add php to PATH.'
}
$phpSessionRoot = Join-Path $Root ('private\runtime\verify-public-php-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $phpSessionRoot -Force | Out-Null
try {
    $phpSessionArgument = 'session.save_path=' + $phpSessionRoot
    $addressDiagnosticsTestOutput = & $phpSource '-n' '-d' $phpSessionArgument $AddressDiagnosticsTest 2>&1
    if ($LASTEXITCODE -ne 0 -or ($addressDiagnosticsTestOutput -join "`n") -notmatch 'address_diagnostics_test OK') {
        Fail "address diagnostics regression test failed: $($addressDiagnosticsTestOutput -join ' ')"
    }
    $historyPayloadPolicyOutput = & $phpSource '-n' '-d' $phpSessionArgument $HistoryPayloadPolicyTest 2>&1
    if ($LASTEXITCODE -ne 0 -or ($historyPayloadPolicyOutput -join "`n") -notmatch 'history_payload_policy_test: OK') {
        Fail "history payload policy regression test failed: $($historyPayloadPolicyOutput -join ' ')"
    }
    $historyStayTestOutput = & $phpSource '-n' '-d' $phpSessionArgument $HistoryStayTest 2>&1
    if ($LASTEXITCODE -ne 0 -or ($historyStayTestOutput -join "`n") -notmatch 'history_stays_test: OK') {
        Fail "history stay regression test failed: $($historyStayTestOutput -join ' ')"
    }
    $originalBackfillEnvironment = [Environment]::GetEnvironmentVariable('LOC_GROUP_CODE_BACKFILL_ENABLED', 'Process')
    try {
        Remove-Item Env:LOC_GROUP_CODE_BACKFILL_ENABLED -ErrorAction SilentlyContinue
        $backfillDefaultOutput = & $phpSource '-n' '-d' $phpSessionArgument $GroupBackfillConfigTest 'true' 2>&1
        if ($LASTEXITCODE -ne 0 -or ($backfillDefaultOutput -join "`n") -notmatch 'group_backfill_config_test: OK \(true\)') {
            Fail "default group backfill config regression test failed: $($backfillDefaultOutput -join ' ')"
        }
        $env:LOC_GROUP_CODE_BACKFILL_ENABLED = 'false'
        $backfillFalseOutput = & $phpSource '-n' '-d' $phpSessionArgument $GroupBackfillConfigTest 'false' 2>&1
        if ($LASTEXITCODE -ne 0 -or ($backfillFalseOutput -join "`n") -notmatch 'group_backfill_config_test: OK \(false\)') {
            Fail "explicit-false group backfill config regression test failed: $($backfillFalseOutput -join ' ')"
        }
        $env:LOC_GROUP_CODE_BACKFILL_ENABLED = 'true'
        $backfillTrueOutput = & $phpSource '-n' '-d' $phpSessionArgument $GroupBackfillConfigTest 'true' 2>&1
        if ($LASTEXITCODE -ne 0 -or ($backfillTrueOutput -join "`n") -notmatch 'group_backfill_config_test: OK \(true\)') {
            Fail "explicit-true group backfill config regression test failed: $($backfillTrueOutput -join ' ')"
        }
    } finally {
        if ($null -eq $originalBackfillEnvironment) {
            Remove-Item Env:LOC_GROUP_CODE_BACKFILL_ENABLED -ErrorAction SilentlyContinue
        } else {
            $env:LOC_GROUP_CODE_BACKFILL_ENABLED = $originalBackfillEnvironment
        }
    }
} finally {
    $resolvedPhpSessionRoot = [System.IO.Path]::GetFullPath($phpSessionRoot)
    $resolvedPrivateRuntime = [System.IO.Path]::GetFullPath((Join-Path $Root 'private\runtime')).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedPhpSessionRoot.StartsWith($resolvedPrivateRuntime, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedPhpSessionRoot)) {
        Remove-Item -LiteralPath $resolvedPhpSessionRoot -Recurse -Force
    }
}

$javac = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
$java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
if (-not $javac -or -not $java) {
    Fail 'javac.exe and java.exe are required to run the P2P merge policy regression test.'
}
$testRuntimeRoot = Join-Path $Root 'private\runtime'
$testOutputRoot = Join-Path $testRuntimeRoot ('verify-public-jvm-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testOutputRoot -Force | Out-Null
try {
    & $javac.Source '-encoding' 'UTF-8' '-Xlint:-options' '--release' '8' '-d' $testOutputRoot `
        $P2PPolicy $P2PPolicyTest `
        $ReportAttemptGate $ReportAttemptGateTest `
        $DiagnosticSourcePolicy $DiagnosticSourcePolicyTest
    if ($LASTEXITCODE -ne 0) {
        Fail 'P2P merge policy regression test did not compile.'
    }
    $policyTestOutput = & $java.Source '-cp' $testOutputRoot 'com.familylocation.client.P2PRecordMergePolicyTest' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "P2P merge policy regression test failed: $($policyTestOutput -join ' ')"
    }
    $reportGateTestOutput = & $java.Source '-cp' $testOutputRoot 'com.familylocation.client.ReportAttemptGateTest' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "Report-attempt gate regression test failed: $($reportGateTestOutput -join ' ')"
    }
    $diagnosticSourcePolicyOutput = & $java.Source '-cp' $testOutputRoot 'com.familylocation.client.DiagnosticSourcePolicyTest' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "Diagnostic-source policy regression test failed: $($diagnosticSourcePolicyOutput -join ' ')"
    }
} finally {
    $resolvedTestOutput = [System.IO.Path]::GetFullPath($testOutputRoot)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedTestOutput.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTestOutput)) {
        Remove-Item -LiteralPath $resolvedTestOutput -Recurse -Force
    }
    if ((Test-Path -LiteralPath $testRuntimeRoot -PathType Container) -and
        @(Get-ChildItem -LiteralPath $testRuntimeRoot -Force).Count -eq 0) {
        Remove-Item -LiteralPath $testRuntimeRoot -Force
    }
}

# Both Android release builds must fail closed onto protected external signing material.
$signingProfiles = @(
    @{
        Name = 'User build'
        Text = $UserBuildText
        Prefix = 'LOC_ANDROID_USER'
    },
    @{
        Name = 'Admin build'
        Text = $AdminBuildText
        Prefix = 'LOC_ANDROID_ADMIN'
    }
)
foreach ($profile in $signingProfiles) {
    $name = [string] $profile.Name
    $text = [string] $profile.Text
    $prefix = [string] $profile.Prefix
    foreach ($suffix in @('KEYSTORE', 'KEY_ALIAS', 'STORE_PASSWORD', 'KEY_PASSWORD')) {
        Assert-Contains $name $text ([regex]::Escape($prefix + '_' + $suffix)) "release signing must require $prefix`_$suffix."
    }
    Assert-Contains $name $text 'StartsWith\(\$ProjectRootPrefix, \[System\.StringComparison\]::OrdinalIgnoreCase\)' 'repository-local signing keys must be rejected.'
    Assert-Contains $name $text 'Debug signing keys are not allowed' 'debug key aliases and filenames must be rejected.'
    Assert-Contains $name $text 'Android Debug\|androiddebugkey' 'the produced signer certificate must be checked for debug identity.'
    Assert-Contains $name $text ([regex]::Escape('env:' + $prefix + '_STORE_PASSWORD')) 'apksigner must read the store password from the environment.'
    Assert-Contains $name $text ([regex]::Escape('env:' + $prefix + '_KEY_PASSWORD')) 'apksigner must read the key password from the environment.'
    Assert-NotContains $name $text '(?im)(--ks-pass|--key-pass)[^\r\n]*pass:' 'apksigner must not receive an inline pass: value.'
}

$forbiddenArtifactExtensions = @(
    '.aab', '.apk', '.apks', '.cer', '.crt', '.der', '.idsig', '.jks', '.key',
    '.keystore', '.p12', '.pem', '.pfx', '.pk8', '.pkcs12', '.store'
)
$repositoryFiles = Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object { $_.FullName -notmatch '\\.git(?:\\|$)' }
foreach ($file in $repositoryFiles) {
    $relativePath = $file.FullName.Substring($Root.Length).TrimStart('\', '/')
    if ($forbiddenArtifactExtensions -contains $file.Extension.ToLowerInvariant()) {
        Fail "public repository contains forbidden APK or signing artifact: $relativePath"
    }
    if ($file.Name -match '(?i)prefs\.xml$' -or $relativePath -match '(?i)(^|[\\/])shared_prefs([\\/]|$)') {
        Fail "public repository contains runtime SharedPreferences state: $relativePath"
    }
}

# AMap credentials stay in a root-owned include; only five exact GET proxies are allowed.
Assert-Contains 'Nginx AMap proxy' $NginxText 'include[ \t]+/etc/nginx/snippets/family-location-amap-secret\.conf;' 'AMap secret must come from the external root-managed snippet.'
Assert-NotContains 'Nginx AMap proxy' $NginxText '(?m)^[ \t]*set[ \t]+\$loc_amap_security_jscode[ \t]+' 'AMap jscode must not be assigned in the repository config.'
Assert-NotContains 'Nginx AMap proxy' $NginxText '(?m)^[ \t]*location[ \t]+(?:\^~[ \t]+)?/_AMapService(?:/|[ \t]*\{)' 'catch-all AMap proxy locations are forbidden.'
Assert-MatchCount 'Nginx AMap proxy' $NginxText '(?m)^[ \t]*location[ \t]+=[ \t]+/_AMapService/[^\s\{]+[ \t]*\{' 5 'exactly five allowlisted AMap proxy locations are required.'

$expectedAmapRoutes = [ordered]@{
    '/_AMapService/maps' = 'https://webapi.amap.com/maps'
    '/_AMapService/appmaptile' = 'https://webrd01.is.autonavi.com/appmaptile'
    '/_AMapService/v4/map/styles' = 'https://webapi.amap.com/v4/map/styles'
    '/_AMapService/v3/vectormap' = 'https://fmap01.amap.com/v3/vectormap'
    '/_AMapService/v3/geocode/regeo' = 'https://restapi.amap.com/v3/geocode/regeo'
}
$routeNames = @($expectedAmapRoutes.Keys)
for ($index = 0; $index -lt $routeNames.Count; $index += 1) {
    $route = $routeNames[$index]
    $startMarker = "location = $route {"
    $endMarker = if ($index + 1 -lt $routeNames.Count) { "location = $($routeNames[$index + 1]) {" } else { 'location /api/ {' }
    $block = Text-BetweenMarkers $NginxText $startMarker $endMarker "Nginx route $route"
    Assert-Contains "Nginx route $route" $block 'if \(\$request_method != GET\) \{ return 405; \}' 'non-GET methods must be rejected.'
    Assert-Contains "Nginx route $route" $block 'if \(\$arg_jscode != ""\) \{ return 400; \}' 'client-supplied jscode must be rejected.'
    Assert-Contains "Nginx route $route" $block 'if \(\$args ~\*' 'identity-bearing query parameters must be filtered.'
    Assert-Contains "Nginx route $route" $block 'family_location_session' 'session query parameters must be filtered.'
    Assert-Contains "Nginx route $route" $block 'access_token' 'token query parameters must be filtered.'
    Assert-Contains "Nginx route $route" $block 'proxy_pass_request_headers off;' 'incoming headers must not be forwarded.'
    Assert-Contains "Nginx route $route" $block 'proxy_pass_request_body off;' 'incoming bodies must not be forwarded.'
    foreach ($header in @('Cookie', 'Authorization', 'Proxy-Authorization', 'Forwarded', 'X-Forwarded-For', 'X-Forwarded-Host', 'X-Forwarded-Proto', 'X-Real-IP')) {
        Assert-Contains "Nginx route $route" $block ("proxy_set_header[ \t]+" + [regex]::Escape($header) + '[ \t]+"";') "identity header must be cleared: $header"
    }
    foreach ($header in @('Set-Cookie', 'Location', 'Refresh')) {
        Assert-Contains "Nginx route $route" $block ("proxy_hide_header[ \t]+" + [regex]::Escape($header) + ';') "upstream response header must be hidden: $header"
    }
    Assert-Contains "Nginx route $route" $block 'proxy_intercept_errors on;' 'upstream redirects must be intercepted.'
    Assert-Contains "Nginx route $route" $block 'error_page 301 302 303 307 308 = @amap_upstream_redirect_blocked;' 'all redirect statuses must fail closed.'
    Assert-Contains "Nginx route $route" $block 'proxy_ssl_verify on;' 'upstream TLS certificates must be verified.'
    Assert-Contains "Nginx route $route" $block 'proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates\.crt;' 'the system CA bundle must be configured.'
    Assert-Contains "Nginx route $route" $block 'proxy_ssl_verify_depth 3;' 'upstream certificate chains must have a bounded depth.'
    Assert-MatchCount "Nginx route $route" $block '(?m)^[ \t]*proxy_pass[ \t]+' 1 'each route must have exactly one fixed upstream.'
    Assert-Contains "Nginx route $route" $block ([regex]::Escape('proxy_pass ' + $expectedAmapRoutes[$route] + ';')) 'route must use its fixed HTTPS upstream.'
}
Assert-MatchCount 'Nginx AMap proxy' $NginxText 'if \(\$loc_amap_security_jscode = ""\) \{ return 503; \}' 3 'credentialed endpoints must fail closed when the external secret is absent.'
Assert-MatchCount 'Nginx AMap proxy' $NginxText 'set \$args "\$args&jscode=\$loc_amap_security_jscode";' 3 'only credentialed endpoints may append the external secret.'
Assert-Contains 'Nginx AMap proxy' $NginxText 'location @amap_upstream_redirect_blocked' 'redirect interception must terminate at a local error handler.'

# New group codes are eight lowercase alphanumeric characters; old 32-hex values remain unique aliases.
$generatorText = Text-BetweenMarkers $BootstrapText 'function generate_legacy_group_code_candidate' 'function ensure_family_group_codes' 'PHP group-code generator'
Assert-Contains 'PHP group-code generator' $generatorText 'generate_lower_alphanumeric_code\(8\)' 'new group codes must be eight lowercase alphanumeric characters.'
Assert-Contains 'PHP group-code generator' $generatorText 'bin2hex\(random_bytes\(16\)\)' 'the compatibility stage must continue writing 32-hex codes.'
Assert-Contains 'PHP group-code generator' $generatorText 'LOC_GROUP_CODE_BACKFILL_ENABLED' 'eight-character writes must be gated by the rollout state.'
Assert-Contains 'PHP group-code generator' $generatorText 'group_code_backfill_is_current\(\$pdo\)' 'a completed migration marker must keep all compatible workers on eight-character writes.'
Assert-Contains 'PHP group-code generator' $generatorText 'group_code = \? OR legacy_group_code = \?' 'generated codes must not collide with current or legacy codes.'
Assert-NotContains 'PHP group-code generator' $generatorText '\$index < 6' 'the obsolete six-character generator must be absent.'
Assert-Contains 'PHP staged rollout config' $PrivateConfigText '\$locGroupCodeBackfillEnabled = true;' 'group-code backfill must default to current eight-character codes.'
Assert-Contains 'PHP staged rollout config' $PrivateConfigText "getenv\('LOC_GROUP_CODE_BACKFILL_ENABLED'\)" 'operators must be able to opt into the first compatibility stage explicitly.'
Assert-Contains 'PHP staged rollout config' $PrivateConfigText '\$locGroupCodeBackfillEnabled = \$locGroupCodeBackfillParsed;' 'an explicit false environment value must override the default.'
Assert-NotContains 'PHP staged rollout config' $PrivateConfigText '\$locGroupCodeBackfillEnabled = false;' 'the public default must not create new 32-character group codes.'
Assert-Contains 'PHP schema fast path' $BootstrapText 'schema_runtime_state_is_current\(\$pdo\)' 'normal requests must skip the migration lock after the schema marker is current.'
Assert-Contains 'PHP schema lock' $BootstrapText 'SELECT GET_LOCK\(\?, \?\)' 'schema changes must be serialized across PHP workers.'
Assert-Contains 'PHP schema lock' $BootstrapText 'SELECT RELEASE_LOCK\(\?\)' 'the schema advisory lock must be released.'
foreach ($schemaText in @($InstallSqlText, $BootstrapText)) {
    Assert-Contains 'PHP group-code schema' $schemaText 'group_code[ \t]+VARCHAR\(32\)[ \t]+NULL[ \t]+UNIQUE' 'the current-code column must remain wide enough for migration compatibility.'
    Assert-Contains 'PHP group-code schema' $schemaText 'legacy_group_code[ \t]+VARCHAR\(32\)[ \t]+NULL' 'the legacy alias column is required.'
    Assert-Contains 'PHP group-code schema' $schemaText 'uniq_family_groups_legacy_group_code' 'legacy aliases must be unique.'
}
Assert-Contains 'PHP runtime migration' $BootstrapText 'SET[ \t]+legacy_group_code = \?, group_code = \?' 'existing 32-hex codes must move atomically into the alias column.'
Assert-Contains 'PHP runtime migration' $BootstrapText '\^\[0-9a-z\]\{8\}\$' 'current eight-character codes must be recognized.'
Assert-Contains 'PHP runtime migration' $BootstrapText '\^\[0-9a-f\]\{32\}\$' 'legacy 32-hex aliases must be recognized.'
foreach ($apiText in @($RegisterApiText, $GroupsApiText)) {
    Assert-Contains 'Group join API' $apiText 'is_valid_family_group_code' 'group joins must share the compatibility validator.'
    Assert-Contains 'Group join API' $apiText 'find_family_group_by_code' 'group joins must resolve current and legacy codes.'
}
Assert-NotContains 'PHP group-code paths' ($GeneratorText + $RegisterApiText + $GroupsApiText) '\^\[0-9a-z\]\{6\}\$' 'legacy six-character validation must be absent.';

Write-Host "verify-public OK"
