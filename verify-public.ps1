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

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ScriptPath = (Resolve-Path -LiteralPath $MyInvocation.MyCommand.Path).Path
& (Join-Path $Root 'verify-invite-check.ps1')
$UserMain = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\MainActivity.java") "user MainActivity.java"
$UserManifest = Resolve-RequiredFile (Join-Path $Root "android-client\AndroidManifest.xml") "user AndroidManifest.xml"
$P2PCrypto = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\P2PCryptoSupport.java") "P2PCryptoSupport.java"
$P2PPolicy = Resolve-RequiredFile (Join-Path $Root "android-client\src\com\familylocation\client\P2PRecordMergePolicy.java") "P2PRecordMergePolicy.java"
$P2PPolicyTest = Resolve-RequiredFile (Join-Path $Root "android-client\tests\com\familylocation\client\P2PRecordMergePolicyTest.java") "P2PRecordMergePolicyTest.java"
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
$AdminUpdateApi = Resolve-RequiredFile (Join-Path $Root "api\admin_app_update.php") "admin update API"
$AdminApkApi = Resolve-RequiredFile (Join-Path $Root "api\admin_apk.php") "admin APK API"

$UserMainText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserMain
$UserManifestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserManifest
$P2PCryptoText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PCrypto
$P2PPolicyText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PPolicy
$P2PPolicyTestText = Get-Content -Raw -Encoding UTF8 -LiteralPath $P2PPolicyTest
$UserBuildText = Get-Content -Raw -Encoding UTF8 -LiteralPath $UserBuild
$AdminBuildText = Get-Content -Raw -Encoding UTF8 -LiteralPath $AdminBuild
$NginxText = Get-Content -Raw -Encoding UTF8 -LiteralPath $NginxConfig
$BootstrapText = Get-Content -Raw -Encoding UTF8 -LiteralPath $Bootstrap
$PrivateConfigText = Get-Content -Raw -Encoding UTF8 -LiteralPath $PrivateConfig
$InstallSqlText = Get-Content -Raw -Encoding UTF8 -LiteralPath $InstallSql
$RegisterApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $RegisterApi
$GroupsApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $GroupsApi
$InviteCheckApiText = Get-Content -Raw -Encoding UTF8 -LiteralPath $InviteCheckApi
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

$forbiddenPatterns = @(
    'loc\.mtmt\.top',
    '82\.158\.231\.148',
    '162\.141\.136\.28',
    'command0block'
)
$textExtensions = @('', '.conf', '.css', '.env', '.gradle', '.java', '.js', '.json', '.md', '.php', '.pro', '.properties', '.ps1', '.sql', '.txt', '.xml', '.yaml', '.yml')
$textFiles = Get-ChildItem -LiteralPath $Root -Recurse -File | Where-Object {
    $_.FullName -notmatch '\\.git(?:\\|$)' -and
    $_.FullName -ne $ScriptPath -and
    $_.FullName -notmatch '\\build\\' -and
    $_.FullName -notmatch '\\bin\\' -and
    ($textExtensions -contains $_.Extension.ToLowerInvariant())
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
Assert-MatchCount 'User app source' $UserMainText '\^\[0-9a-f\]\{32\}\$' 2 'registration and join flows must enforce 32-character lowercase hexadecimal group codes.'
Assert-NotContains 'User app source' $UserMainText '\^\[0-9a-z\]\{6\}\$' 'legacy six-character group-code validation must be absent.'

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

$javac = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
$java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
if (-not $javac -or -not $java) {
    Fail 'javac.exe and java.exe are required to run the P2P merge policy regression test.'
}
$testOutputRoot = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'family-location-p2p-policy-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testOutputRoot | Out-Null
try {
    & $javac.Source '-encoding' 'UTF-8' '-Xlint:-options' '--release' '8' '-d' $testOutputRoot $P2PPolicy $P2PPolicyTest
    if ($LASTEXITCODE -ne 0) {
        Fail 'P2P merge policy regression test did not compile.'
    }
    $policyTestOutput = & $java.Source '-cp' $testOutputRoot 'com.familylocation.client.P2PRecordMergePolicyTest' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "P2P merge policy regression test failed: $($policyTestOutput -join ' ')"
    }
} finally {
    $resolvedTestOutput = [System.IO.Path]::GetFullPath($testOutputRoot)
    $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTestOutput.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTestOutput)) {
        Remove-Item -LiteralPath $resolvedTestOutput -Recurse -Force
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

# The PHP backend must store and validate 128-bit lowercase hexadecimal group codes, including existing databases.
$generatorText = Text-BetweenMarkers $BootstrapText 'function generate_group_code' 'function ensure_family_group_codes' 'PHP group-code generator'
Assert-Contains 'PHP group-code generator' $generatorText 'bin2hex\(random_bytes\(16\)\)' 'group codes must use 128 bits of cryptographic randomness.'
Assert-NotContains 'PHP group-code generator' $generatorText 'random_int\(|strlen\(\$alphabet\)|\$index < 6' 'the legacy six-character generator must be removed.'
Assert-Contains 'PHP install schema' $InstallSqlText 'group_code[ \t]+VARCHAR\(32\)[ \t]+NULL[ \t]+UNIQUE' 'fresh databases must allocate 32 characters for group codes.'
Assert-Contains 'PHP runtime schema' $BootstrapText 'group_code[ \t]+VARCHAR\(32\)[ \t]+NULL[ \t]+UNIQUE' 'runtime schema creation must allocate 32 characters for group codes.'
Assert-Contains 'PHP runtime schema' $BootstrapText 'add_column_if_missing\(\$pdo, ''family_groups'', ''group_code'', ''VARCHAR\(32\) NULL UNIQUE''\)' 'runtime schema additions must allocate 32 characters for group codes.'
Assert-Contains 'PHP runtime schema' $BootstrapText 'ALTER[ \t]+TABLE[ \t]+family_groups[ \t]+MODIFY(?:[ \t]+COLUMN)?[ \t]+group_code[ \t]+VARCHAR\(32\)' 'existing databases must widen the group_code column before rotation.'
Assert-Contains 'PHP runtime migration' $BootstrapText '\^\[0-9a-f\]\{32\}\$' 'legacy or malformed group codes must be detected and rotated.'
Assert-Contains 'Registration API' $RegisterApiText '\^\[0-9a-f\]\{32\}\$' 'registration joins must validate 32-character lowercase hexadecimal group codes.'
Assert-Contains 'Groups API' $GroupsApiText '\^\[0-9a-f\]\{32\}\$' 'authenticated joins must validate 32-character lowercase hexadecimal group codes.'
Assert-NotContains 'PHP group-code paths' ($GeneratorText + $RegisterApiText + $GroupsApiText) '\^\[0-9a-z\]\{6\}\$' 'legacy six-character validation must be absent.';

Write-Host "verify-public OK"
