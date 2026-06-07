<?php

declare(strict_types=1);

require_once __DIR__ . '/private/lib/bootstrap.php';

$requestPath = (string) (parse_url((string) ($_SERVER['REQUEST_URI'] ?? '/'), PHP_URL_PATH) ?: '/');
$adminBase = '/' . admin_base_path();

if (path_matches_admin_base($requestPath, $adminBase)) {
    route_admin_request($requestPath, $adminBase);
}

require_loc_app_page();
$webAssetVersion = web_asset_version(__DIR__);
$mapAssetVersion = map_asset_week_version();
$isAppLoggedIn = !empty($_SESSION['user_id']);
$assetScripts = $isAppLoggedIn
    ? [
        'assets/anti-debug.js',
        'assets/geo-aliases.js',
        'assets/address-utils.js',
        'assets/ip-probe.js',
        'assets/webrtc-probe.js',
        'assets/popup-select.js',
        'assets/web-version.js',
        'assets/p2p-location.js',
        'assets/app-core.js',
        'assets/app-settings.js',
        'assets/app-reporting.js',
        'assets/app-history.js',
        'assets/app-map-ui.js',
        'assets/app.js',
    ]
    : [
        'assets/anti-debug.js',
        'assets/popup-select.js',
        'assets/auth.js',
    ];
header('Content-Type: text/html; charset=utf-8');
$page = <<<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#0d5f54">
    <title>位置</title>
    <link rel="icon" href="/icon.png" type="image/png">
    <script>
        (() => {
            const mode = localStorage.getItem('theme_mode') || 'system';
            if (mode === 'light' || mode === 'dark') {
                document.documentElement.dataset.theme = mode;
            }
            const serverVersion = __WEB_ASSET_VERSION_JSON__;
            const params = new URLSearchParams(window.location.search);
            const version = serverVersion || params.get('_web_v') || localStorage.getItem('web_asset_version') || '';
            window.__WEB_ASSET_VERSION__ = version;
            window.__APP_LOGGED_IN__ = __APP_LOGGED_IN_JSON__;
            window.AMAP_JS_API_KEY = __AMAP_JS_API_KEY_JSON__;
            window.AMAP_REVERSE_GEOCODE_KEY = __AMAP_REVERSE_GEOCODE_KEY_JSON__;
            window.CF_TURNSTILE_SITE_KEY = __CF_TURNSTILE_SITE_KEY_JSON__;
            window.AMAP_SERVICE_HOST = new URL(__AMAP_SERVICE_PROXY_PATH_JSON__, window.location.origin).toString().replace(/\/$/, '');
            if (window.AMAP_SERVICE_HOST) {
                window._AMapSecurityConfig = {
                    serviceHost: window.AMAP_SERVICE_HOST,
                };
            }
            window.__assetUrl = (path) => version
                ? `${path}${path.includes('?') ? '&' : '?'}v=${encodeURIComponent(version)}`
                : path;
            document.write(`<link rel="manifest" href="${window.__assetUrl('assets/manifest.webmanifest')}">`);
        })();
    </script>
    __LEAFLET_CSS__
    <script>
        document.write(`<link rel="stylesheet" href="${window.__assetUrl('assets/styles.css')}">`);
    </script>
    __AMAP_LOADER_SCRIPT__
</head>
<body>
    <main class="app-shell">
        <section id="loginView" class="login-view">
            <form id="loginForm" class="login-card" autocomplete="off">
                <h1>位置</h1>
                <div id="loginMessage" class="message" hidden></div>
                <label>
                    <span>账号</span>
                    <input id="username" name="username" autocomplete="username" required>
                </label>
                <label>
                    <span>密码</span>
                    <input id="password" name="password" type="password" autocomplete="current-password" required>
                </label>
                <div class="terms-field">
                    <input id="termsAccepted" name="terms_accepted" type="checkbox" value="1" required>
                    <span>
                        我已阅读并同意
                        <button id="termsButton" class="text-link" type="button">用户协议</button>
                        和
                        <button id="privacyButton" class="text-link" type="button">隐私条约</button>
                    </span>
                </div>
                <div class="terms-field">
                    <input id="crossBorderAccepted" name="cross_border_transfer_accepted" type="checkbox" value="1" required>
                    <span>
                        我已阅读并同意
                        <button id="crossBorderButton" class="text-link" type="button">用户数据跨境加密传输协议</button>
                    </span>
                </div>
                <div id="turnstileBox" class="turnstile-box" __CF_TURNSTILE_HIDDEN__>
                    <div class="cf-turnstile" data-sitekey="__CF_TURNSTILE_SITE_KEY_ATTR__" data-callback="onTurnstileSuccess" data-expired-callback="onTurnstileExpired" data-error-callback="onTurnstileExpired"></div>
                </div>
                <button type="submit">登录</button>
                <button id="registerButton" class="subtle-button full-button" type="button">注册账号</button>
            </form>
        </section>

        <section id="mainView" class="main-view" hidden>
            <header class="app-header">
                <div>
                    <h1 id="appTitle">位置</h1>
                    <p id="accountLine"></p>
                </div>
                <div class="header-actions">
                    <button id="ticketButton" class="icon-button" type="button" aria-label="工单" hidden>工单</button>
                    <button id="announcementButton" class="icon-button" type="button" aria-label="公告" hidden>公告</button>
                    <button id="settingsButton" class="icon-button" type="button" aria-label="设置" hidden>设置</button>
                    <button id="logoutButton" class="icon-button" type="button" aria-label="退出" hidden>退出</button>
                </div>
            </header>

            <section class="group-switcher">
                <label for="groupSelect">
                    <span>家庭组</span>
                    <select id="groupSelect"></select>
                </label>
            </section>

            <section class="map-section">
                <div id="map"></div>
                <div id="mapEmpty" class="map-empty" hidden>暂无云端位置</div>
            </section>

            <section class="control-strip">
                <div class="control-actions">
                    <button id="reportButton" type="button" hidden>上报位置</button>
                    <button id="crossGroupSyncButton" class="subtle-button" type="button" hidden>跨组同步</button>
                    <button id="continuousReportButton" class="subtle-button" type="button" hidden>持续上报</button>
                </div>
                <span id="liveStatus" class="live-status">正在同步</span>
            </section>

            <section class="location-grid">
                <article class="location-card">
                    <h2>我的云端位置</h2>
                    <p id="mineLocation" class="coord">暂无</p>
                    <p id="mineTime" class="time">更新时间：暂无</p>
                </article>
                <article class="location-card">
                    <h2>监测端云端位置</h2>
                    <div id="monitorLocations" class="location-list">暂无</div>
                </article>
                <article class="location-card">
                    <h2>监护端云端位置</h2>
                    <div id="guardianLocations" class="location-list">暂无</div>
                </article>
                <article class="location-card address-card">
                    <h2>地址对比</h2>
                    <div id="addressDiagnostics" class="address-diagnostics">等待上报后显示</div>
                </article>
            </section>

            <section class="history-panel">
                <div class="section-heading">
                    <h2>历史位置</h2>
                    <button id="historyRefreshButton" class="subtle-button" type="button">刷新</button>
                </div>
                <select id="historyUserFilter" aria-label="筛选历史成员">
                    <option value="">全部成员</option>
                </select>
                <div class="history-pager">
                    <select id="historyPageSize" aria-label="每页历史条数">
                        <option value="20" selected>每页 20 条</option>
                        <option value="50">每页 50 条</option>
                        <option value="100">每页 100 条</option>
                    </select>
                    <select id="historyMapPageSize" aria-label="地图每人历史条数">
                        <option value="20" selected>地图每人 20 条</option>
                        <option value="50">地图每人 50 条</option>
                        <option value="100">地图每人 100 条</option>
                    </select>
                    <button id="historyPrevButton" class="subtle-button" type="button">上一页</button>
                    <span id="historyPageInfo">第 1 页</span>
                    <button id="historyNextButton" class="subtle-button" type="button">下一页</button>
                </div>
                <div id="historyList" class="history-list">暂无历史位置</div>
            </section>
        </section>
    </main>

    __LEAFLET_SCRIPT__
    <script>
        __ASSET_SCRIPTS_JSON__.forEach((path) => {
            document.write(`<script src="${window.__assetUrl(path)}" defer><\/script>`);
        });
    </script>
    __TURNSTILE_SCRIPT__
</body>
</html>
HTML;
$page = $isAppLoggedIn
    ? preg_replace('/\s*<section id="loginView"[\s\S]*?<\/section>\s*/', "\n", $page, 1)
    : preg_replace('/\s*<section id="mainView"[\s\S]*<\/section>\s*<\/main>/', "\n    </main>", $page, 1);

echo str_replace(
    [
        '__WEB_ASSET_VERSION_JSON__',
        '__APP_LOGGED_IN_JSON__',
        '__AMAP_JS_API_KEY_JSON__',
        '__AMAP_REVERSE_GEOCODE_KEY_JSON__',
        '__AMAP_SERVICE_PROXY_PATH_JSON__',
        '__LEAFLET_CSS__',
        '__AMAP_LOADER_SCRIPT__',
        '__LEAFLET_SCRIPT__',
        '__CF_TURNSTILE_SITE_KEY_JSON__',
        '__CF_TURNSTILE_SITE_KEY_ATTR__',
        '__CF_TURNSTILE_HIDDEN__',
        '__TURNSTILE_SCRIPT__',
        '__ASSET_SCRIPTS_JSON__',
    ],
    [
        json_encode($webAssetVersion, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
        $isAppLoggedIn ? 'true' : 'false',
        json_encode($isAppLoggedIn ? AMAP_JS_API_KEY : '', JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
        json_encode($isAppLoggedIn ? AMAP_REVERSE_GEOCODE_KEY : '', JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
        json_encode($isAppLoggedIn ? AMAP_SERVICE_PROXY_PATH : '/', JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
        $isAppLoggedIn ? '<link rel="stylesheet" href="https://cdn.bootcdn.net/ajax/libs/leaflet/1.9.4/leaflet.css">' : '',
        $isAppLoggedIn ? '<script src="https://webapi.amap.com/loader.js?v=' . rawurlencode($mapAssetVersion) . '" defer></script>' : '',
        $isAppLoggedIn ? '<script src="https://cdn.bootcdn.net/ajax/libs/leaflet/1.9.4/leaflet.js" defer></script>' : '',
        json_encode(CF_TURNSTILE_SITE_KEY, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
        e(CF_TURNSTILE_SITE_KEY),
        trim((string) CF_TURNSTILE_SITE_KEY) === '' ? 'hidden' : '',
        trim((string) CF_TURNSTILE_SITE_KEY) === '' ? '' : '<script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>',
        json_encode($assetScripts, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT),
    ],
    $page
);
exit;

function route_admin_request(string $requestPath, string $adminBase): never
{
    if ($requestPath === $adminBase) {
        $query = (string) ($_SERVER['QUERY_STRING'] ?? '');
        header('Location: ' . $adminBase . '/' . ($query !== '' ? '?' . $query : ''), true, 302);
        exit;
    }

    $adminDir = __DIR__ . '/' . admin_source_dir();
    $relative = trim(substr($requestPath, strlen($adminBase)), '/');
    if ($relative === '') {
        $relative = 'index.php';
    }

    if (str_starts_with($relative, 'assets/')) {
        serve_admin_asset($relative);
    }

    $pages = [
        'index.php' => $adminDir . '/index.php',
        'logout.php' => $adminDir . '/logout.php',
    ];

    if (!isset($pages[$relative])) {
        http_response_code(404);
        exit('Not found.');
    }

    require $pages[$relative];
    exit;
}

function serve_admin_asset(string $relative): never
{
    require_loc_app_page();

    $adminDir = __DIR__ . '/' . admin_source_dir();
    $base = realpath($adminDir . '/assets');
    $file = realpath($adminDir . '/' . $relative);
    if ($base === false || $file === false || !str_starts_with($file, $base . DIRECTORY_SEPARATOR) || !is_file($file)) {
        http_response_code(404);
        exit('Not found.');
    }

    $extension = strtolower(pathinfo($file, PATHINFO_EXTENSION));
    $types = [
        'css' => 'text/css; charset=utf-8',
        'js' => 'application/javascript; charset=utf-8',
        'png' => 'image/png',
        'svg' => 'image/svg+xml',
    ];

    header('Content-Type: ' . ($types[$extension] ?? 'application/octet-stream'));
    header('Cache-Control: no-store');
    readfile($file);
    exit;
}

function web_asset_version(string $root): string
{
    $files = [
        $root . DIRECTORY_SEPARATOR . 'index.php',
        $root . DIRECTORY_SEPARATOR . 'service-worker.js',
        $root . DIRECTORY_SEPARATOR . 'private' . DIRECTORY_SEPARATOR . 'config.php',
        $root . DIRECTORY_SEPARATOR . 'api' . DIRECTORY_SEPARATOR . 'legal_documents.php',
        $root . DIRECTORY_SEPARATOR . 'api' . DIRECTORY_SEPARATOR . 'invite_check.php',
    ];
    $patterns = [
        $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.js',
        $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.css',
        $root . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . '*.webmanifest',
    ];

    foreach ($patterns as $pattern) {
        foreach (glob($pattern) ?: [] as $file) {
            $files[] = $file;
        }
    }

    $files = array_values(array_unique(array_filter($files, 'is_file')));
    sort($files);

    $hash = hash_init('sha256');
    foreach ($files as $file) {
        clearstatcache(false, $file);
        hash_update(
            $hash,
            str_replace($root, '', $file) . '|' . (int) filemtime($file) . '|' . (int) filesize($file) . "\n"
        );
    }

    return substr(hash_final($hash), 0, 16);
}

function map_asset_week_version(): string
{
    return gmdate('o-\WW');
}
