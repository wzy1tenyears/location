const API_BASE = 'api';
const THEME_STORAGE_KEY = 'theme_mode';
const DEFAULT_REPORT_INTERVAL_MS = 300000;
const REFRESH_MS = 15000;
const AMAP_TILE_URL = 'https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}';
const AMAP_JS_PLUGINS = ['AMap.Scale', 'AMap.ToolBar', 'AMap.Geocoder'];
const USER_COLORS = ['#0d5f54', '#d9a441', '#3278bd', '#b4547a', '#5a7d2e', '#7b5fbd', '#c05f37', '#218a8a'];
const state = {
    user: null,
    map: null,
    mapProvider: '',
    mapLoading: null,
    amapApiLoading: null,
    AMap: null,
    amapInfoWindow: null,
    markers: new Map(),
    refreshTimer: null,
    watchId: null,
    lastAutoReportAt: 0,
    lastImmediateAutoReportKey: '',
    reportIntervalMs: DEFAULT_REPORT_INTERVAL_MS,
    lastLocations: [],
    guardianContinuousReporting: false,
    history: [],
    historyMap: [],
    historyMembers: [],
    historyLayer: null,
    historyLineLayer: null,
    historyMarkers: new Map(),
    selectedGroupName: '',
    historyUserId: '',
    selectedHistoryId: null,
    historyPage: 1,
    historyPageSize: 20,
    historyMapPageSize: 20,
    historyPagination: null,
    addressDiagnostics: null,
};

const el = {
    loginView: document.querySelector('#loginView'),
    mainView: document.querySelector('#mainView'),
    loginForm: document.querySelector('#loginForm'),
    loginMessage: document.querySelector('#loginMessage'),
    username: document.querySelector('#username'),
    password: document.querySelector('#password'),
    termsAccepted: document.querySelector('#termsAccepted'),
    termsButton: document.querySelector('#termsButton'),
    privacyButton: document.querySelector('#privacyButton'),
    appTitle: document.querySelector('#appTitle'),
    accountLine: document.querySelector('#accountLine'),
    settingsButton: document.querySelector('#settingsButton'),
    logoutButton: document.querySelector('#logoutButton'),
    reportButton: document.querySelector('#reportButton'),
    continuousReportButton: document.querySelector('#continuousReportButton'),
    groupSelect: document.querySelector('#groupSelect'),
    liveStatus: document.querySelector('#liveStatus'),
    mapEmpty: document.querySelector('#mapEmpty'),
    mineLocation: document.querySelector('#mineLocation'),
    mineTime: document.querySelector('#mineTime'),
    addressDiagnostics: document.querySelector('#addressDiagnostics'),
    monitorLocations: document.querySelector('#monitorLocations'),
    guardianLocations: document.querySelector('#guardianLocations'),
    historyRefreshButton: document.querySelector('#historyRefreshButton'),
    historyUserFilter: document.querySelector('#historyUserFilter'),
    historyPageSize: document.querySelector('#historyPageSize'),
    historyMapPageSize: document.querySelector('#historyMapPageSize'),
    historyPrevButton: document.querySelector('#historyPrevButton'),
    historyNextButton: document.querySelector('#historyNextButton'),
    historyPageInfo: document.querySelector('#historyPageInfo'),
    historyList: document.querySelector('#historyList'),
};

const systemThemeQuery = window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null;

function applyThemeMode(mode) {
    const normalized = ['system', 'light', 'dark'].includes(mode) ? mode : 'system';

    if (normalized === 'system') {
        delete document.documentElement.dataset.theme;
    } else {
        document.documentElement.dataset.theme = normalized;
    }

    window.localStorage.setItem(THEME_STORAGE_KEY, normalized);
    document.querySelectorAll('[data-theme-mode-select]').forEach((select) => {
        select.value = normalized;
    });
    updateThemeChrome(normalized);
    updateMapTheme(normalized);
    refreshPopupSelectControls();
}

function initThemeMode() {
    applyThemeMode(window.localStorage.getItem(THEME_STORAGE_KEY) || 'system');
    if (systemThemeQuery) {
        const onSystemThemeChange = () => {
            const mode = window.localStorage.getItem(THEME_STORAGE_KEY) || 'system';
            if (mode === 'system') {
                updateThemeChrome(mode);
                if (state.map) {
                    resizeMapSoon();
                }
            }
        };

        if (systemThemeQuery.addEventListener) {
            systemThemeQuery.addEventListener('change', onSystemThemeChange);
        } else if (systemThemeQuery.addListener) {
            systemThemeQuery.addListener(onSystemThemeChange);
        }
    }
}

function effectiveTheme(mode = window.localStorage.getItem(THEME_STORAGE_KEY) || 'system') {
    if (mode === 'light' || mode === 'dark') {
        return mode;
    }

    return systemThemeQuery && systemThemeQuery.matches ? 'dark' : 'light';
}

function updateThemeChrome(mode) {
    const metaThemeColor = document.querySelector('meta[name="theme-color"]');
    if (metaThemeColor) {
        metaThemeColor.setAttribute('content', effectiveTheme(mode) === 'dark' ? '#111816' : '#0d5f54');
    }
}

function refreshPopupSelectControls() {
    if (typeof window.refreshPopupSelects === 'function') {
        window.refreshPopupSelects();
    }
}

function showDocumentPopup(title, sections, options = {}) {
    if (typeof window.showPopupDialog === 'function') {
        window.showPopupDialog({ title, sections, ...options });
        return;
    }

    openInlinePopupDialog(title, sections, options);
}

function showSimplePopup(title, paragraphs, options = {}) {
    showDocumentPopup(title, [{
        title: '',
        paragraphs: Array.isArray(paragraphs) ? paragraphs : [String(paragraphs || '')],
    }], options);
}

function openInlinePopupDialog(title, sections, options = {}) {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = title;

    const body = document.createElement('div');
    body.className = 'popup-dialog-body';

    sections.forEach((section) => {
        if (section.title) {
            const sectionTitle = document.createElement('h3');
            sectionTitle.textContent = section.title;
            body.append(sectionTitle);
        }

        (section.paragraphs || []).forEach((text) => {
            const paragraph = document.createElement('p');
            paragraph.textContent = text;
            body.append(paragraph);
        });
    });

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';

    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.textContent = '关闭';

    let closed = false;
    function close() {
        if (closed) {
            return;
        }

        closed = true;
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
        if (typeof options.onClose === 'function') {
            window.setTimeout(options.onClose, 210);
        }
    }

    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    function onKeydown(event) {
        if (!document.body.contains(overlay)) {
            document.removeEventListener('keydown', onKeydown);
            return;
        }

        if (event.key === 'Escape') {
            close();
            document.removeEventListener('keydown', onKeydown);
        }
    }
    document.addEventListener('keydown', onKeydown);

    actions.append(closeButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
    closeButton.focus();
}

async function api(path, options = {}) {
    const response = await fetch(`${API_BASE}/${path}.php`, {
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            ...(options.headers || {}),
        },
        ...options,
    });

    const payload = await response.json().catch(() => ({
        ok: false,
        message: '服务器返回格式不正确。',
    }));

    if (!response.ok || payload.ok === false) {
        const error = new Error(payload.message || '请求失败。');
        error.payload = payload;
        error.status = response.status;
        throw error;
    }

    return payload;
}

function showLogin(message = '') {
    stopWatch();
    stopRefresh();
    clearNativeReportingState();
    clearHistory();
    renderAddressDiagnostics(null);
    state.user = null;
    state.selectedGroupName = '';
    state.guardianContinuousReporting = false;
    state.lastImmediateAutoReportKey = '';
    state.historyUserId = '';
    state.selectedHistoryId = null;
    el.reportButton.hidden = true;
    el.reportButton.disabled = false;
    el.continuousReportButton.hidden = true;
    el.continuousReportButton.disabled = false;
    updateContinuousReportButton();
    el.logoutButton.hidden = true;
    if (el.settingsButton) {
        el.settingsButton.hidden = true;
    }
    el.mainView.hidden = true;
    el.loginView.hidden = false;
    el.loginMessage.hidden = message === '';
    el.loginMessage.textContent = message;
}

function showMain(user) {
    state.user = user;
    setReportInterval(user.report_interval_seconds);
    stopWatch();
    el.loginView.hidden = true;
    el.mainView.hidden = false;
    el.logoutButton.hidden = false;
    if (el.settingsButton) {
        el.settingsButton.hidden = false;
    }
    initMap();
    startRefresh();
    applySelectedGroup(preferredGroupName(user), false);
    refreshLocations();
    refreshHistory();
    syncAutoReportWatch();
    checkFineLocationPermission();
    uploadEnvironmentDataIfAllowed();
}

function preferredGroupName(user) {
    const groups = userGroups(user);
    const saved = window.localStorage.getItem(`selected_group_${user.id}`) || '';

    if (groups.some((group) => group.group_name === saved)) {
        return saved;
    }

    if (groups.some((group) => group.group_name === user.group_name)) {
        return user.group_name;
    }

    return groups[0] ? groups[0].group_name : '';
}

function userGroups(user = state.user) {
    return user && Array.isArray(user.groups) ? user.groups : [];
}

function currentGroup() {
    return userGroups().find((group) => group.group_name === state.selectedGroupName) || null;
}

function applySelectedGroup(groupName, reload = true) {
    if (!state.user) {
        return;
    }

    const groups = userGroups();
    const group = groups.find((item) => item.group_name === groupName) || groups[0] || null;

    state.selectedGroupName = group ? group.group_name : '';
    if (group) {
        state.user.group_name = group.group_name;
        state.user.role = group.role;
        state.user.role_label = group.role_label;
        window.localStorage.setItem(`selected_group_${state.user.id}`, group.group_name);
    }

    renderGroupSelect();
    el.appTitle.textContent = state.user.role_label || '位置';
    el.accountLine.textContent = `${state.user.display_name || state.user.username} / ${state.selectedGroupName || '暂无家庭组'}`;

    state.guardianContinuousReporting = state.user.role === 'guardian'
        ? getGuardianContinuousReportingForGroup(state.selectedGroupName)
        : false;
    setGuardianContinuousReportingForGroup(state.selectedGroupName, state.guardianContinuousReporting);

    syncRoleControls();
    pushNativeReportingState();

    if (!reload) {
        return;
    }

    state.historyPage = 1;
    state.historyUserId = '';
    state.selectedHistoryId = null;
    state.lastAutoReportAt = 0;
    state.lastImmediateAutoReportKey = '';
    clearHistoryLayers();
    refreshLocations();
    refreshHistory();
    syncAutoReportWatch();
}

function renderGroupSelect() {
    if (!el.groupSelect) {
        return;
    }

    const groups = userGroups();
    const options = groups.length
        ? groups.map((group) => new Option(`${group.group_name} / ${group.role_label}`, group.group_name))
        : [new Option('暂无家庭组', '')];

    el.groupSelect.replaceChildren(...options);
    el.groupSelect.value = state.selectedGroupName;
    el.groupSelect.disabled = groups.length <= 1;
    refreshPopupSelectControls();
}

function shouldAutoReport() {
    return state.user
        && state.selectedGroupName !== ''
        && (state.user.role === 'monitor' || state.guardianContinuousReporting);
}

function syncAutoReportWatch() {
    if (shouldAutoReport()) {
        if (state.watchId === null) {
            startWatch();
        }
        requestImmediateAutoReport();
        return;
    }

    stopWatch();
    setStatus('等待手动上报');
}

function syncRoleControls() {
    const isGuardian = state.user && state.user.role === 'guardian';
    el.reportButton.hidden = !isGuardian;
    el.continuousReportButton.hidden = !isGuardian;

    if (!isGuardian) {
        state.guardianContinuousReporting = false;
    }

    updateContinuousReportButton();
}

function toggleGuardianContinuousReport() {
    if (!state.user || state.user.role !== 'guardian') {
        return;
    }

    state.guardianContinuousReporting = !state.guardianContinuousReporting;
    setGuardianContinuousReportingForGroup(state.selectedGroupName, state.guardianContinuousReporting);
    updateContinuousReportButton();
    syncAutoReportWatch();
    pushNativeReportingState();
}

function updateContinuousReportButton() {
    if (!el.continuousReportButton) {
        return;
    }

    el.continuousReportButton.textContent = state.guardianContinuousReporting ? '停止上报' : '持续上报';
}

function setReportInterval(seconds) {
    const parsed = Number(seconds);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        return;
    }

    state.reportIntervalMs = Math.max(60000, parsed * 1000);
    pushNativeReportingState();
}

function pushNativeReportingState() {
    if (!state.user || !window.LocationBridge) {
        return;
    }

    try {
        const groupSessions = JSON.stringify(userGroups().map((group) => ({
            group_name: group.group_name,
            role: group.role,
            continuous: group.role === 'guardian'
                ? getLocalGuardianContinuousReporting(group.group_name)
                : true,
        })));

        if (typeof window.LocationBridge.setSessionState === 'function') {
            window.LocationBridge.setSessionState(
                state.user.role,
                state.guardianContinuousReporting,
                Math.round(state.reportIntervalMs / 1000),
                state.selectedGroupName,
                groupSessions
            );
            return;
        }

        try {
            window.LocationBridge.setSession(
                state.user.role,
                state.guardianContinuousReporting,
                Math.round(state.reportIntervalMs / 1000),
                state.selectedGroupName
            );
        } catch (error) {
            window.LocationBridge.setSession(
                state.user.role,
                state.guardianContinuousReporting,
                Math.round(state.reportIntervalMs / 1000)
            );
        }
    } catch (error) {
        console.warn(error);
    }
}

function clearNativeReportingState() {
    if (!window.LocationBridge) {
        return;
    }

    try {
        window.LocationBridge.clearSession();
    } catch (error) {
        console.warn(error);
    }
}

function checkFineLocationPermission() {
    if (!window.LocationBridge || typeof window.LocationBridge.hasFineLocationPermission !== 'function') {
        return;
    }

    try {
        if (window.LocationBridge.hasFineLocationPermission() !== true) {
            showPreciseLocationRequiredPopup(true);
        }
    } catch (error) {
        console.warn(error);
    }
}

function requestFineLocationPermissionAgain() {
    if (window.LocationBridge && typeof window.LocationBridge.requestFineLocationPermission === 'function') {
        try {
            window.LocationBridge.requestFineLocationPermission();
        } catch (error) {
            console.warn(error);
        }
    }
}

function showPreciseLocationRequiredPopup(requestAgain = true) {
    showSimplePopup('需要定位权限', [
        '请开启“始终允许定位”，并启用“精确位置”。否则持续上报、手动上报和地图定位可能无法正常工作。',
        '如果系统已经拒绝过权限，请到系统设置里的应用权限中手动开启。',
    ], {
        onClose: requestAgain ? requestFineLocationPermissionAgain : null,
    });
}

async function uploadEnvironmentDataIfAllowed() {
    if (!state.user || !state.user.environment_data_consent || !window.LocationBridge) {
        return;
    }

    const today = localDateKey();
    const storageKey = `environment_reported_day_${state.user.id}`;
    if (window.localStorage.getItem(storageKey) === today) {
        return;
    }

    if (typeof window.LocationBridge.getEnvironmentData !== 'function') {
        return;
    }

    try {
        const raw = window.LocationBridge.getEnvironmentData();
        const report = JSON.parse(raw || '{}');
        await api('environment_report', {
            method: 'POST',
            body: JSON.stringify({ report }),
        });
        window.localStorage.setItem(storageKey, today);
    } catch (error) {
        console.warn(error);
    }
}

function localDateKey(date = new Date()) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function openSettingsPopup() {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '设置';

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';

    const themeLabel = document.createElement('label');
    themeLabel.className = 'settings-field';
    const themeTitle = document.createElement('span');
    themeTitle.textContent = '深色模式';
    const themeSelect = document.createElement('select');
    themeSelect.dataset.themeModeSelect = '1';
    [
        ['system', '跟随系统'],
        ['light', '明亮'],
        ['dark', '暗色'],
    ].forEach(([value, label]) => {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = label;
        themeSelect.append(option);
    });
    themeSelect.value = window.localStorage.getItem(THEME_STORAGE_KEY) || 'system';
    themeSelect.addEventListener('change', () => applyThemeMode(themeSelect.value));
    themeLabel.append(themeTitle, themeSelect);

    const envLabel = document.createElement('label');
    envLabel.className = 'settings-check-field';
    const envInput = document.createElement('input');
    envInput.type = 'checkbox';
    envInput.checked = !!(state.user && state.user.environment_data_consent);
    const envText = document.createElement('span');
    envText.textContent = '同意上报环境数据用于改进软件';
    envInput.addEventListener('change', async () => {
        const checked = envInput.checked;
        envInput.disabled = true;
        try {
            const payload = await api('settings', {
                method: 'POST',
                body: JSON.stringify({
                    group_name: state.selectedGroupName,
                    environment_data_consent: checked,
                }),
            });
            state.user = payload.user;
            if (checked) {
                uploadEnvironmentDataIfAllowed();
            }
        } catch (error) {
            envInput.checked = !checked;
            showSimplePopup('设置失败', error.message);
        } finally {
            envInput.disabled = false;
        }
    });
    envLabel.append(envInput, envText);

    body.append(themeLabel, envLabel);

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.textContent = '关闭';
    function close() {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    }
    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });
    actions.append(closeButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    refreshPopupSelectControls();
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function getGuardianContinuousReportingForGroup(groupName = state.selectedGroupName) {
    const localValue = getLocalGuardianContinuousReporting(groupName);

    if (!window.LocationBridge) {
        return localValue;
    }

    try {
        if (typeof window.LocationBridge.getGuardianContinuousReportingForGroup === 'function') {
            return window.LocationBridge.getGuardianContinuousReportingForGroup(groupName) === true || localValue;
        }

        if (groupName === state.selectedGroupName) {
            return window.LocationBridge.getGuardianContinuousReporting() === true || localValue;
        }
    } catch (error) {
        console.warn(error);
    }

    return localValue;
}

function getLocalGuardianContinuousReporting(groupName = state.selectedGroupName) {
    if (!state.user || !groupName) {
        return false;
    }

    return window.localStorage.getItem(guardianContinuousStorageKey(groupName)) === '1';
}

function setGuardianContinuousReportingForGroup(groupName, enabled) {
    if (!state.user || !groupName) {
        return;
    }

    window.localStorage.setItem(guardianContinuousStorageKey(groupName), enabled ? '1' : '0');
}

function guardianContinuousStorageKey(groupName) {
    return `guardian_continuous_${state.user.id}_${encodeURIComponent(groupName)}`;
}

function amapApiKey() {
    return String(window.AMAP_JS_API_KEY || window.AMAP_REVERSE_GEOCODE_KEY || '').trim();
}

function loadAmapApi() {
    const key = amapApiKey();
    if (!key || !window.AMapLoader) {
        return Promise.reject(new Error('AMap JS API is unavailable'));
    }

    if (!state.amapApiLoading) {
        state.amapApiLoading = window.AMapLoader.load({
            key,
            version: '2.0',
            plugins: AMAP_JS_PLUGINS,
        });
    }

    return state.amapApiLoading;
}

function initMap() {
    if (state.map) {
        resizeMapSoon();
        updateMapTheme();
        return;
    }

    if (state.mapLoading) {
        return;
    }

    if (amapApiKey() && window.AMapLoader) {
        state.mapLoading = loadAmapApi()
            .then((AMap) => createAmapMap(AMap))
            .catch(() => createLeafletMap())
            .finally(() => {
                state.mapLoading = null;
            });
        return;
    }

    createLeafletMap();
}

function createAmapMap(AMap) {
    state.AMap = AMap;
    state.mapProvider = 'amap';
    state.map = new AMap.Map('map', {
        center: [104.1954, 35.8617],
        zoom: 4,
        resizeEnable: true,
        mapStyle: effectiveTheme() === 'dark' ? 'amap://styles/dark' : 'amap://styles/normal',
    });

    try {
        state.map.addControl(new AMap.Scale());
        state.map.addControl(new AMap.ToolBar({ position: 'LT' }));
    } catch (error) {
        // Controls are optional; keep the map alive if a plugin is unavailable.
    }

    state.amapInfoWindow = new AMap.InfoWindow({
        offset: new AMap.Pixel(0, -18),
    });
    el.mapEmpty.hidden = true;

    window.setTimeout(() => {
        renderMarkers(visibleLatestLocations());
        renderHistoryMap(historyMapRecords(), true);
    }, 0);
}

function createLeafletMap() {
    if (typeof L === 'undefined') {
        el.mapEmpty.hidden = false;
        el.mapEmpty.textContent = '地图资源加载失败';
        setStatus('地图资源加载失败');
        return;
    }

    state.mapProvider = 'leaflet';
    state.map = L.map('map', {
        zoomControl: true,
        attributionControl: true,
    }).setView([35.8617, 104.1954], 4);

    L.tileLayer(AMAP_TILE_URL, {
        maxZoom: 19,
        subdomains: '1234',
        attribution: '&copy; 高德地图',
    }).addTo(state.map);
}

function resizeMapSoon() {
    window.setTimeout(() => {
        if (!state.map) {
            return;
        }

        if (state.mapProvider === 'amap' && typeof state.map.resize === 'function') {
            state.map.resize();
            return;
        }

        if (typeof state.map.invalidateSize === 'function') {
            state.map.invalidateSize();
        }
    }, 50);
}

function updateMapTheme(mode = window.localStorage.getItem(THEME_STORAGE_KEY) || 'system') {
    if (state.mapProvider === 'amap' && state.map && typeof state.map.setMapStyle === 'function') {
        state.map.setMapStyle(effectiveTheme(mode) === 'dark' ? 'amap://styles/dark' : 'amap://styles/normal');
    }
}

function startRefresh() {
    stopRefresh();
    state.refreshTimer = window.setInterval(refreshLocations, REFRESH_MS);
}

function stopRefresh() {
    if (state.refreshTimer) {
        window.clearInterval(state.refreshTimer);
        state.refreshTimer = null;
    }
}

function startWatch() {
    if (!navigator.geolocation) {
        setStatus('当前浏览器不支持定位');
        return;
    }

    stopWatch();
    setStatus('定位中');

    state.watchId = navigator.geolocation.watchPosition(
        (position) => {
            const now = Date.now();
            if (now - state.lastAutoReportAt >= state.reportIntervalMs) {
                state.lastAutoReportAt = now;
                reportPosition(position, true);
            }
        },
        (error) => {
            setStatus(locationErrorMessage(error));
            if (error.code === error.PERMISSION_DENIED) {
                showPreciseLocationRequiredPopup(true);
            }
        },
        {
            enableHighAccuracy: true,
            maximumAge: 10000,
            timeout: 20000,
        }
    );
}

function requestImmediateAutoReport() {
    if (!navigator.geolocation || !state.user || !shouldAutoReport()) {
        return;
    }

    const key = `${state.user.id}|${state.selectedGroupName}|${state.user.role}`;
    if (state.lastImmediateAutoReportKey === key) {
        return;
    }

    state.lastImmediateAutoReportKey = key;
    navigator.geolocation.getCurrentPosition(
        (position) => {
            state.lastAutoReportAt = Date.now();
            reportPosition(position, true);
        },
        (error) => {
            setStatus(locationErrorMessage(error));
            if (error.code === error.PERMISSION_DENIED) {
                showPreciseLocationRequiredPopup(true);
            }
        },
        {
            enableHighAccuracy: true,
            maximumAge: 0,
            timeout: 20000,
        }
    );
}

function stopWatch() {
    if (state.watchId !== null && navigator.geolocation) {
        navigator.geolocation.clearWatch(state.watchId);
        state.watchId = null;
    }
}

function setStatus(text) {
    el.liveStatus.textContent = text;
}

function locationErrorMessage(error) {
    if (error.code === error.PERMISSION_DENIED) {
        return '定位权限被拒绝';
    }

    if (error.code === error.POSITION_UNAVAILABLE) {
        return '定位不可用';
    }

    if (error.code === error.TIMEOUT) {
        return '定位超时';
    }

    return '定位失败';
}

async function reportPosition(position, automatic = false) {
    if (!state.user) {
        return;
    }

    const { latitude, longitude, accuracy, heading, speed } = position.coords;
    const reportGroupName = state.selectedGroupName;

    try {
        if (!automatic) {
            el.reportButton.disabled = true;
        }

        const probeSession = createAddressProbeSession(latitude, longitude);
        let locationId = null;
        let diagnosticsUploading = false;
        let queuedDiagnostics = null;
        let addressDiagnostics = probeSession.current();
        const flushDiagnostics = async () => {
            if (!locationId || diagnosticsUploading || !queuedDiagnostics) {
                return;
            }

            diagnosticsUploading = true;
            try {
                while (queuedDiagnostics) {
                    const nextDiagnostics = queuedDiagnostics;
                    queuedDiagnostics = null;
                    await api('report_location', {
                        method: 'POST',
                        body: JSON.stringify({
                            group_name: reportGroupName,
                            location_id: locationId,
                            address_diagnostics: nextDiagnostics,
                            address_mismatch: nextDiagnostics.mismatch,
                        }),
                    });
                    if (preferredMapSource({
                        latitude,
                        longitude,
                        address_diagnostics: nextDiagnostics,
                    })) {
                        await refreshLocations();
                        await refreshHistory();
                    }
                }
            } catch (error) {
                console.warn(error);
            } finally {
                diagnosticsUploading = false;
                if (queuedDiagnostics) {
                    flushDiagnostics();
                }
            }
        };
        const queueDiagnostics = (diagnostics) => {
            addressDiagnostics = normalizeAddressDiagnostics(diagnostics);
            renderAddressDiagnostics(addressDiagnostics);
            queuedDiagnostics = addressDiagnostics;
            flushDiagnostics();
        };

        probeSession.onUpdate(queueDiagnostics);
        renderAddressDiagnostics(addressDiagnostics);

        setStatus(automatic ? '正在自动上报' : '正在上报');
        const report = await api('report_location', {
            method: 'POST',
            body: JSON.stringify({
                group_name: reportGroupName,
                latitude,
                longitude,
                accuracy,
                heading,
                speed,
                address_diagnostics: addressDiagnostics,
                address_mismatch: addressDiagnostics.mismatch,
            }),
        });
        locationId = Number(report.location_id) || null;
        flushDiagnostics();

        setStatus(addressDiagnostics.complete ? '位置已上报' : '位置已上报，地址继续探测中');
        await refreshLocations();
        await refreshHistory();
    } catch (error) {
        setStatus(error.message);
    } finally {
        el.reportButton.disabled = false;
    }
}

function manualReport() {
    if (!navigator.geolocation) {
        setStatus('当前浏览器不支持定位');
        return;
    }

    el.reportButton.disabled = true;
    setStatus('定位中');

    navigator.geolocation.getCurrentPosition(
        (position) => reportPosition(position, false),
        (error) => {
            el.reportButton.disabled = false;
            setStatus(locationErrorMessage(error));
            if (error.code === error.PERMISSION_DENIED) {
                showPreciseLocationRequiredPopup(true);
            }
        },
        {
            enableHighAccuracy: true,
            maximumAge: 0,
            timeout: 20000,
        }
    );
}

async function refreshLocations() {
    if (!state.user) {
        return;
    }

    try {
        const data = await api('locations', {
            method: 'POST',
            body: JSON.stringify({ group_name: state.selectedGroupName }),
        });
        state.user = data.user;
        setReportInterval(data.report_interval_seconds);
        applySelectedGroup(data.selected_group ? data.selected_group.group_name : state.selectedGroupName, false);
        syncRoleControls();
        syncAutoReportWatch();
        state.lastLocations = data.locations || [];
        renderLocationCards(data);
        renderMarkers(visibleLatestLocations());
        setStatus(shouldAutoReport() ? '持续上报中' : '位置已同步');
    } catch (error) {
        if (/请先登录/.test(error.message)) {
            showLogin('登录已失效。');
            return;
        }

        setStatus(error.message);
    }
}

async function refreshHistory() {
    if (!state.user) {
        return;
    }

    try {
        const data = await api('history', {
            method: 'POST',
            body: JSON.stringify({
                group_name: state.selectedGroupName,
                page: state.historyPage,
                per_page: state.historyPageSize,
                map_per_user: state.historyMapPageSize,
                user_id: state.historyUserId,
            }),
        });
        state.history = data.history || [];
        state.historyMap = data.map_history || [];
        state.historyMembers = data.members || [];
        state.historyPagination = data.pagination || null;
        state.selectedHistoryId = null;
        renderHistory();
    } catch (error) {
        renderHistoryMessage(error.message);
        clearHistoryLayers();
    }
}

function clearHistory() {
    state.history = [];
    state.historyMap = [];
    state.historyMembers = [];
    state.historyPage = 1;
    state.historyPageSize = Number(el.historyPageSize ? el.historyPageSize.value : 20) || 20;
    state.historyMapPageSize = Number(el.historyMapPageSize ? el.historyMapPageSize.value : 20) || 20;
    state.historyPagination = null;
    state.historyUserId = '';
    state.selectedHistoryId = null;
    if (el.historyUserFilter) {
        el.historyUserFilter.replaceChildren(new Option('全部成员', ''));
    }
    refreshPopupSelectControls();
    renderHistoryPager();
    renderHistoryMessage('暂无历史位置');
    clearHistoryLayers();
}

function renderHistory() {
    renderHistoryFilter();
    renderHistoryPager();
    const records = filteredHistory();
    renderHistoryList(records);
    renderHistoryMap(historyMapRecords());
}

function renderHistoryFilter() {
    if (!el.historyUserFilter) {
        return;
    }

    const selected = state.historyUserId;
    const people = new Map();

    state.historyMembers.forEach((member) => {
        if (!people.has(member.user_id)) {
            people.set(member.user_id, `${member.display_name || member.username} / ${member.role_label}`);
        }
    });

    const options = [new Option('全部成员', '')];
    [...people.entries()]
        .sort((left, right) => left[1].localeCompare(right[1], 'zh-CN'))
        .forEach(([userId, label]) => {
            options.push(new Option(label, String(userId)));
        });

    if (selected && !people.has(Number(selected))) {
        state.historyUserId = '';
    }

    el.historyUserFilter.replaceChildren(...options);
    el.historyUserFilter.value = state.historyUserId;
    refreshPopupSelectControls();
}

function renderHistoryPager() {
    const pagination = state.historyPagination || {
        page: state.historyPage,
        total_pages: 1,
        total: 0,
    };

    if (el.historyPageInfo) {
        const perPage = pagination.per_page || state.historyPageSize;
        const mapPerUser = pagination.map_per_user || state.historyMapPageSize;
        el.historyPageInfo.textContent = `第 ${pagination.page} / ${pagination.total_pages} 页，共 ${pagination.total} 条，每页 ${perPage} 条，地图每人 ${mapPerUser} 条`;
    }

    if (el.historyPrevButton) {
        el.historyPrevButton.disabled = pagination.page <= 1;
    }

    if (el.historyNextButton) {
        el.historyNextButton.disabled = pagination.page >= pagination.total_pages;
    }
}

function changeHistoryPage(offset) {
    const pagination = state.historyPagination || { page: state.historyPage, total_pages: 1 };
    const nextPage = Math.min(Math.max(1, pagination.page + offset), pagination.total_pages);

    if (nextPage === pagination.page) {
        return;
    }

    state.historyPage = nextPage;
    refreshHistory();
}

function changeHistoryPageSize() {
    const selected = Number(el.historyPageSize ? el.historyPageSize.value : 20);
    state.historyPageSize = [20, 50, 100].includes(selected) ? selected : 20;
    state.historyPage = 1;
    refreshHistory();
}

function changeHistoryMapPageSize() {
    const selected = Number(el.historyMapPageSize ? el.historyMapPageSize.value : 20);
    state.historyMapPageSize = [20, 50, 100].includes(selected) ? selected : 20;
    refreshHistory();
}

function changeHistoryUserFilter() {
    state.historyUserId = el.historyUserFilter ? el.historyUserFilter.value : '';
    state.historyPage = 1;
    state.selectedHistoryId = null;
    renderMarkers(visibleLatestLocations());
    refreshHistory();
}

function filteredHistory() {
    return state.history;
}

function historyMapRecords() {
    const records = [...state.historyMap];
    if (!state.selectedHistoryId || records.some((location) => location.id === state.selectedHistoryId)) {
        return records;
    }

    const selected = state.history.find((location) => location.id === state.selectedHistoryId);
    if (selected) {
        records.push(selected);
    }

    return records;
}

function historyItemElement(locationId) {
    return el.historyList
        ? el.historyList.querySelector(`[data-history-id="${Number(locationId)}"]`)
        : null;
}

function withStableHistoryScroll(locationId, callback) {
    const beforeItem = historyItemElement(locationId);
    const beforeTop = beforeItem ? beforeItem.getBoundingClientRect().top : null;
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    const listScrollTop = el.historyList ? el.historyList.scrollTop : 0;

    callback();

    const restore = () => {
        if (el.historyList) {
            el.historyList.scrollTop = listScrollTop;
        }

        const afterItem = historyItemElement(locationId);
        if (beforeTop !== null && afterItem) {
            const delta = afterItem.getBoundingClientRect().top - beforeTop;
            if (Math.abs(delta) > 1) {
                window.scrollBy(0, delta);
            }
            return;
        }

        window.scrollTo(scrollX, scrollY);
    };

    restore();
    window.requestAnimationFrame(restore);
    window.setTimeout(restore, 0);
}

function renderHistoryList(records) {
    if (!el.historyList) {
        return;
    }

    if (!records.length) {
        renderHistoryMessage('暂无历史位置');
        return;
    }

    el.historyList.replaceChildren(
        ...records.map((location) => {
            const item = document.createElement('div');
            item.className = 'history-item';
            item.dataset.historyId = String(location.id);
            item.tabIndex = 0;
            item.setAttribute('role', 'button');
            item.setAttribute('aria-expanded', state.selectedHistoryId === location.id ? 'true' : 'false');
            item.style.setProperty('--user-color', userColor(location.user_id));

            if (state.selectedHistoryId === location.id) {
                item.classList.add('selected');
            }

            const title = document.createElement('div');
            title.className = 'history-title';

            const name = document.createElement('span');
            name.className = 'history-name';
            name.textContent = location.display_name || location.username;

            const role = document.createElement('span');
            role.className = `history-role ${location.role}`;
            role.textContent = location.role_label;

            const coord = document.createElement('div');
            coord.className = 'history-meta';
            coord.textContent = formatCoord(location);

            const time = document.createElement('div');
            time.className = 'history-meta';
            time.textContent = `上报时间：${location.created_at}`;

            title.append(name, role);
            item.append(title, coord, time);

            const statusText = locationAddressStatusText(location);
            if (statusText !== '位置信息一致或无法完整判断') {
                const mismatch = document.createElement('div');
                mismatch.className = 'history-meta';
                mismatch.textContent = statusText;
                item.append(mismatch);
            }

            if (state.selectedHistoryId === location.id) {
                item.append(renderHistoryDetails(location));
            }

            item.addEventListener('click', () => toggleHistorySelection(location.id));
            item.addEventListener('keydown', (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    toggleHistorySelection(location.id);
                }
            });

            return item;
        })
    );
}

function renderHistoryDetails(location) {
    const details = document.createElement('div');
    details.className = 'history-details';
    const diagnostics = normalizeAddressDiagnostics(location.address_diagnostics);

    const rows = [
        ['家庭组', location.group_name || '未知'],
        ['坐标', formatCoord(location)],
        ['精度', location.accuracy === null ? '未知' : `${Math.round(location.accuracy)}m`],
        ['地址状态', addressDiagnosticsStatusText(diagnostics)],
    ];

    const heading = Number(location.heading);
    const speed = Number(location.speed);
    if (location.heading !== null && location.heading !== undefined && Number.isFinite(heading)) {
        rows.push(['方向', `${Math.round(heading)}°`]);
    }
    if (location.speed !== null && location.speed !== undefined && Number.isFinite(speed)) {
        rows.push(['速度', `${speed.toFixed(2)} m/s`]);
    }

    rows.forEach(([label, value]) => {
        const row = document.createElement('div');
        row.className = 'history-detail-row';
        row.append(historyDetailLabel(label), historyDetailValue(value));
        details.append(row);
    });

    if (diagnostics && Array.isArray(diagnostics.sources)) {
        if (diagnostics.checked_at) {
            const checked = document.createElement('div');
            checked.className = 'history-detail-row';
            checked.append(historyDetailLabel('对比时间'), historyDetailValue(diagnostics.checked_at));
            details.append(checked);
        }

        diagnostics.sources.forEach((source) => {
            const row = document.createElement('div');
            row.className = 'history-detail-row wide';
            row.append(
                historyDetailLabel(source.name || source.type || '地址'),
                historyDetailValue(`${source.address || source.ip || '未知'} / 城市：${cityDisplayName(source.city || inferCityFromText(source.address || '')) || '未知'}${source.mobile_network_uncertain ? ' / 移动网络出口省份不一致' : ''}`)
            );
            details.append(row);
        });
    }

    return details;
}

function addressDiagnosticsStatusText(diagnostics) {
    const normalized = normalizeAddressDiagnostics(diagnostics);
    if (!normalized || !Array.isArray(normalized.sources)) {
        return '位置信息一致或无法完整判断';
    }

    if (normalized.mismatch) {
        return '位置信息不一致';
    }

    if (normalized.mobile_ip_uncertain) {
        return '移动网络出口省份不一致';
    }

    return '位置信息一致或无法完整判断';
}

function locationAddressStatusText(location) {
    if (!location || !location.address_diagnostics) {
        return location && location.address_mismatch ? '位置信息不一致' : '位置信息一致或无法完整判断';
    }

    return addressDiagnosticsStatusText(location.address_diagnostics);
}

function historyDetailLabel(text) {
    const label = document.createElement('span');
    label.className = 'history-detail-label';
    label.textContent = text;
    return label;
}

function historyDetailValue(text) {
    const value = document.createElement('span');
    value.className = 'history-detail-value';
    value.textContent = text;
    return value;
}

function toggleHistorySelection(locationId) {
    withStableHistoryScroll(locationId, () => {
        const id = Number(locationId);
        state.selectedHistoryId = state.selectedHistoryId === id ? null : id;
        const records = filteredHistory();
        renderHistoryList(records);
        renderHistoryMap(historyMapRecords(), true);
    });
}

function selectHistory(locationId) {
    withStableHistoryScroll(locationId, () => {
        state.selectedHistoryId = Number(locationId);
        const records = filteredHistory();
        renderHistoryList(records);
        renderHistoryMap(historyMapRecords(), true);
    });
}

function renderHistoryMessage(message) {
    if (!el.historyList) {
        return;
    }

    const empty = document.createElement('div');
    empty.className = 'history-empty';
    empty.textContent = message;
    el.historyList.replaceChildren(empty);
}

function renderHistoryMap(records, adjustViewport = true) {
    clearHistoryLayers();

    if (!state.map) {
        return;
    }

    if (state.mapProvider === 'amap') {
        renderAmapHistoryMap(records, adjustViewport);
        return;
    }

    if (typeof L === 'undefined') {
        return;
    }

    const latestLocations = visibleLatestLocations();
    el.mapEmpty.hidden = latestLocations.length > 0 || records.length > 0;

    if (!records.length) {
        if (adjustViewport) {
            fitMapToLatestLocations();
        }
        return;
    }

    state.historyLineLayer = L.layerGroup().addTo(state.map);
    state.historyLayer = L.layerGroup().addTo(state.map);
    state.historyMarkers = new Map();

    const grouped = new Map();
    records.slice().reverse().forEach((location) => {
        const key = location.user_id;
        if (!grouped.has(key)) {
            grouped.set(key, []);
        }
        grouped.get(key).push(location);
    });

    const boundsPoints = latestLocations.map((location) => mapLatLng(location));
    let selectedMarker = null;
    let selectedLatLng = null;

    for (const locations of grouped.values()) {
        const color = userColor(locations[0].user_id);
        const points = locations.map((location) => mapLatLng(location));
        boundsPoints.push(...points);

        if (points.length > 1) {
            L.polyline(points, {
                color,
                opacity: 0.62,
                weight: 3,
            }).addTo(state.historyLineLayer);
        }

        locations.forEach((location) => {
            const name = location.display_name || location.username;
            const popup = `${escapeHtml(name)}<br>${escapeHtml(location.role_label)}<br>${escapeHtml(location.created_at)}<br>${escapeHtml(formatCoord(location))}`;
            const selected = state.selectedHistoryId === location.id;
            const latLng = mapLatLng(location);

            const marker = L.marker(latLng, {
                icon: historyMarkerIcon(location, selected, color),
            }).bindPopup(popup, mapPopupOptions());

            marker.on('click', () => selectHistory(location.id));
            marker.addTo(state.historyLayer);
            state.historyMarkers.set(location.id, marker);

            if (selected) {
                selectedMarker = marker;
                selectedLatLng = latLng;
            }
        });
    }

    if (adjustViewport && boundsPoints.length > 0) {
        state.map.fitBounds(L.latLngBounds(boundsPoints), {
            maxZoom: boundsPoints.length === 1 ? 16 : 15,
            padding: [28, 28],
        });
    }

    if (selectedMarker) {
        selectedMarker.openPopup();
    }

    if (adjustViewport && selectedLatLng) {
        state.map.setView(selectedLatLng, Math.max(state.map.getZoom(), 16), {
            animate: true,
        });
    }
}

function renderAmapHistoryMap(records, adjustViewport = true) {
    const AMap = state.AMap;
    if (!AMap || !state.map) {
        return;
    }

    const latestLocations = visibleLatestLocations();
    el.mapEmpty.hidden = latestLocations.length > 0 || records.length > 0;

    if (!records.length) {
        if (adjustViewport) {
            fitMapToLatestLocations();
        }
        return;
    }

    const lineOverlays = [];
    const markerOverlays = [];
    state.historyLineLayer = lineOverlays;
    state.historyLayer = markerOverlays;
    state.historyMarkers = new Map();

    const grouped = new Map();
    records.slice().reverse().forEach((location) => {
        const key = location.user_id;
        if (!grouped.has(key)) {
            grouped.set(key, []);
        }
        grouped.get(key).push(location);
    });

    let selectedMarker = null;
    let selectedPosition = null;

    for (const locations of grouped.values()) {
        const color = userColor(locations[0].user_id);
        const points = locations.map((location) => mapLngLat(location));

        if (points.length > 1) {
            const polyline = new AMap.Polyline({
                path: points,
                strokeColor: color,
                strokeOpacity: 0.62,
                strokeWeight: 3,
            });
            lineOverlays.push(polyline);
        }

        locations.forEach((location) => {
            const selected = state.selectedHistoryId === location.id;
            const position = mapLngLat(location);
            const marker = new AMap.Marker({
                position,
                content: historyMarkerHtml(location, selected, color),
                anchor: 'center',
                title: location.display_name || location.username || '',
                zIndex: selected ? 140 : 110,
            });
            const popup = historyPopupHtml(location);
            marker.on('click', () => selectHistory(location.id));
            marker.on('mouseover', () => openAmapInfoWindow(marker, popup));
            markerOverlays.push(marker);
            state.historyMarkers.set(location.id, marker);

            if (selected) {
                selectedMarker = marker;
                selectedPosition = position;
            }
        });
    }

    state.map.add([...lineOverlays, ...markerOverlays]);

    if (adjustViewport) {
        fitAmapToOverlays([...state.markers.values(), ...lineOverlays, ...markerOverlays], markerOverlays.length === 1 ? 16 : 15, [28, 28, 28, 28]);
    }

    const selectedRecord = records.find((location) => location.id === state.selectedHistoryId);
    if (selectedMarker && selectedRecord) {
        openAmapInfoWindow(selectedMarker, historyPopupHtml(selectedRecord));
    }

    if (adjustViewport && selectedPosition) {
        state.map.setZoomAndCenter(Math.max(state.map.getZoom(), 16), selectedPosition);
    }
}

function clearHistoryLayers() {
    if (state.mapProvider === 'amap' && state.map) {
        const overlays = [
            ...(Array.isArray(state.historyLayer) ? state.historyLayer : []),
            ...(Array.isArray(state.historyLineLayer) ? state.historyLineLayer : []),
        ];
        if (overlays.length) {
            state.map.remove(overlays);
        }
        state.historyLayer = null;
        state.historyLineLayer = null;
        state.historyMarkers = new Map();
        return;
    }

    if (state.historyLayer) {
        state.historyLayer.remove();
        state.historyLayer = null;
    }

    if (state.historyLineLayer) {
        state.historyLineLayer.remove();
        state.historyLineLayer = null;
    }

    state.historyMarkers = new Map();
}

function renderLocationCards(data) {
    renderMine(data.mine);
    if (data.mine && data.mine.address_diagnostics) {
        renderAddressDiagnostics(data.mine.address_diagnostics);
    }
    renderLocationList(el.monitorLocations, data.monitors || []);
    renderLocationList(el.guardianLocations, data.guardians || []);
}

function renderMine(location) {
    if (!location) {
        el.mineLocation.textContent = '暂无';
        el.mineTime.textContent = '更新时间：暂无';
        return;
    }

    el.mineLocation.textContent = formatCoord(location);
    el.mineTime.textContent = `更新时间：${location.updated_at}`;
}

function renderLocationList(container, locations) {
    const card = container.closest('.location-card');
    if (!locations.length) {
        if (card) {
            card.hidden = true;
        }
        container.replaceChildren();
        return;
    }

    if (card) {
        card.hidden = false;
    }

    container.replaceChildren(
        ...locations.map((location) => {
            const item = document.createElement('div');
            item.className = 'location-item';

            const name = document.createElement('div');
            name.className = 'location-name';
            name.textContent = location.display_name || location.username;

            const coord = document.createElement('div');
            coord.textContent = formatCoord(location);

            const time = document.createElement('div');
            time.textContent = `更新时间：${location.updated_at}`;

            item.append(name, coord, time);

            const statusText = locationAddressStatusText(location);
            if (statusText !== '位置信息一致或无法完整判断') {
                const mismatch = document.createElement('div');
                mismatch.textContent = statusText;
                item.append(mismatch);
            }

            return item;
        })
    );
}

function createAddressProbeSession(latitude, longitude) {
    const sourceTypes = ['gps', 'ip', 'webrtc'];
    const sources = new Map();
    const listeners = [];
    let completed = 0;

    pendingAddressSources(latitude, longitude).forEach((source) => {
        sources.set(source.type, source);
    });

    const current = () => {
        const currentSources = sourceTypes.map((type) => sources.get(type)).filter(Boolean);
        const ipSource = currentSources.find((source) => source.type === 'ip');
        const webrtcIndex = currentSources.findIndex((source) => source.type === 'webrtc');
        if (ipSource && webrtcIndex >= 0) {
            const reusedWebRtc = reuseIpProbeResultForWebRtc(currentSources[webrtcIndex], ipSource);
            currentSources[webrtcIndex] = reusedWebRtc;
            sources.set('webrtc', reusedWebRtc);
        }

        return normalizeAddressDiagnostics({
            mismatch: false,
            checked_at: new Date().toLocaleString('zh-CN', { hour12: false }),
            complete: completed >= sourceTypes.length,
            sources: currentSources,
        });
    };
    const publish = () => {
        const diagnostics = current();
        listeners.forEach((listener) => listener(diagnostics));
    };
    const watch = (type, promise) => {
        Promise.resolve(promise)
            .then((source) => {
                if (source) {
                    sources.set(type, source);
                }
            })
            .catch(() => {
                sources.set(type, {
                    ...sources.get(type),
                    address: '无法获取',
                    city: '',
                });
            })
            .finally(() => {
                completed += 1;
                publish();
            });
    };

    const updateSource = (type, source) => {
        if (!source) {
            return;
        }

        sources.set(type, source);
        publish();
    };

    watch('gps', reverseGpsAddress(latitude, longitude));
    watch('ip', probeIpAddress((source) => updateSource('ip', source)));
    watch('webrtc', probeWebRtcAddress());

    return {
        current,
        onUpdate(listener) {
            listeners.push(listener);
        },
    };
}

function pendingAddressSources(latitude, longitude) {
    return [{
        type: 'gps',
        name: '定位地址',
        address: `${latitude.toFixed(6)}, ${longitude.toFixed(6)} / 继续探测中`,
        city: '',
        latitude,
        longitude,
    }, {
        type: 'ip',
        name: 'IP 探测',
        address: '继续探测中',
        city: '',
    }, {
        type: 'webrtc',
        name: 'WebRTC 探测',
        address: '继续探测中',
        city: '',
    }];
}

function fallbackAddressDiagnostics(latitude, longitude) {
    return {
        mismatch: false,
        checked_at: new Date().toLocaleString('zh-CN', { hour12: false }),
        sources: [{
            type: 'gps',
            name: '定位地址',
            address: `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`,
            city: '',
            latitude,
            longitude,
        }, {
            type: 'ip',
            name: 'IP 探测',
            address: '探测超时',
            city: '',
        }, {
            type: 'webrtc',
            name: 'WebRTC 探测',
            address: '探测超时',
            city: '',
        }],
    };
}

function reuseIpProbeResultForWebRtc(webrtcSource, ipSource = window.__latestIpProbeResult || null) {
    if (!webrtcSource || webrtcSource.type !== 'webrtc' || !ipSource) {
        return webrtcSource;
    }

    const match = findMatchingIpProbeResult(webrtcSource, ipSource);
    if (!match) {
        return webrtcSource;
    }

    const displayMatch = findDisplayIpProbeResult(webrtcSource, ipSource) || match;
    const coordinates = sourceCoordinates(displayMatch) || sourceCoordinates(match) || sourceCoordinates(ipSource) || null;
    return {
        ...webrtcSource,
        address: formatReusedWebRtcAddress(webrtcSource, displayMatch, ipSource),
        city: displayMatch.city || ipSource.city || match.city || webrtcSource.city || '',
        region: displayMatch.region || ipSource.region || match.region || webrtcSource.region || '',
        country: displayMatch.country || ipSource.country || match.country || webrtcSource.country || '',
        latitude: coordinates ? coordinates.latitude : webrtcSource.latitude,
        longitude: coordinates ? coordinates.longitude : webrtcSource.longitude,
        reused_ip_probe: true,
        ip_probe_variant_label: match.label || '',
        display_ip_probe_variant_label: displayMatch.label || '',
    };
}

function formatReusedWebRtcAddress(webrtcSource, match, ipSource) {
    const primaryAddress = match.address || ipSource.address || webrtcSource.address || webrtcSource.ip || '';
    const parts = [primaryAddress];

    const candidateText = webRtcCandidateSummary(webrtcSource, primaryAddress);
    if (candidateText) {
        parts.push(candidateText);
    }

    return parts.filter(Boolean).join(' / ');
}

function webRtcCandidateSummary(webrtcSource, existingText = '') {
    if (!Array.isArray(webrtcSource.candidates)) {
        return '';
    }

    const seen = new Set();
    const candidates = webrtcSource.candidates
        .filter((candidate) => candidate && isDisplayableWebRtcIp(candidate.ip))
        .filter((candidate) => {
            const key = String(candidate.ip || '').trim();
            if (!key || String(existingText || '').includes(key)) {
                return false;
            }
            if (seen.has(key)) {
                return false;
            }

            seen.add(key);
            return true;
        })
        .slice(0, 5)
        .map((candidate) => String(candidate.ip || '').trim());

    return candidates.length ? candidates.join(', ') : '';
}

function isDisplayableWebRtcIp(ip) {
    const value = String(ip || '').trim();
    if (!value || value.endsWith('.local')) {
        return false;
    }

    if (typeof isPublicIp === 'function') {
        return isPublicIp(value);
    }

    if (value.includes(':')) {
        const lower = value.toLowerCase();
        return !(lower === '::'
            || lower === '::1'
            || lower.startsWith('fe80:')
            || lower.startsWith('fc')
            || lower.startsWith('fd')
            || lower.startsWith('ff'));
    }

    return !/^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.)/.test(value);
}

function findMatchingIpProbeResult(webrtcSource, ipSource) {
    const webrtcIps = sourceIpValues(webrtcSource);
    if (!webrtcIps.size) {
        return null;
    }

    const variants = Array.isArray(ipSource.variants) ? ipSource.variants : [];
    const variant = variants.find((item) => item && webrtcIps.has(String(item.ip || '').trim()));
    if (variant) {
        return variant;
    }

    const directIp = ['ip', 'ipv4', 'ipv6', 'server_ip']
        .map((key) => String(ipSource[key] || '').trim())
        .find((ip) => webrtcIps.has(ip));
    return directIp ? { ...ipSource, ip: directIp } : null;
}

function findDisplayIpProbeResult(webrtcSource, ipSource) {
    const webrtcIps = sourceIpValues(webrtcSource);
    const variants = Array.isArray(ipSource.variants) ? ipSource.variants : [];
    if (!webrtcIps.size || !variants.length) {
        return null;
    }

    const ipv6Variant = variants.find((item) => (
        item
        && item.label === 'IPv6'
        && item.ip
        && webrtcIps.has(String(item.ip).trim())
        && (item.city || inferCityFromText(item.address || ''))
    ));
    if (ipv6Variant) {
        return ipv6Variant;
    }

    const displayVariant = typeof chooseDisplayProbeEntry === 'function'
        ? chooseDisplayProbeEntry(variants)
        : null;
    if (displayVariant && displayVariant.ip && webrtcIps.has(String(displayVariant.ip).trim())) {
        return displayVariant;
    }

    return null;
}

function sourceIpValues(source) {
    const values = new Set();
    const add = (value) => {
        const text = String(value || '').trim();
        if (text && (typeof isIpAddress !== 'function' || isIpAddress(text))) {
            values.add(text);
        }
    };

    ['ip', 'ipv4', 'ipv6', 'server_ip'].forEach((key) => add(source[key]));
    if (Array.isArray(source.candidates)) {
        source.candidates.forEach((candidate) => add(candidate.ip));
    }
    extractIpValues(source.address).forEach(add);

    return values;
}

function extractIpValues(text) {
    const value = String(text || '');
    const ipv4Matches = value.match(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g) || [];
    const ipv6Matches = value.match(/\b[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,}\b/gi) || [];
    return [...ipv4Matches, ...ipv6Matches];
}

function withTimeout(promise, timeoutMs, fallback) {
    let timer = null;
    const timeout = new Promise((resolve) => {
        timer = window.setTimeout(() => resolve(fallback), timeoutMs);
    });

    return Promise.race([promise, timeout]).finally(() => {
        if (timer !== null) {
            window.clearTimeout(timer);
        }
    });
}

function fetchOpen(url, options = {}) {
    return fetch(url, {
        credentials: 'omit',
        ...options,
    });
}

async function fetchJsonOpen(url, options = {}) {
    const response = await fetchOpen(url, options);
    if (!response.ok) {
        throw new Error('request failed');
    }

    return response.json();
}

async function buildAddressDiagnostics(latitude, longitude) {
    const [gps, ip, webrtc] = await Promise.all([
        reverseGpsAddress(latitude, longitude),
        probeIpAddress(),
        probeWebRtcAddress(),
    ]);
    const sources = [gps, ip, reuseIpProbeResultForWebRtc(webrtc, ip)].filter(Boolean);

    return normalizeAddressDiagnostics({
        mismatch: false,
        checked_at: new Date().toLocaleString('zh-CN', { hour12: false }),
        sources,
    });
}

async function reverseGpsAddress(latitude, longitude) {
    const fallback = {
        type: 'gps',
        name: '定位地址',
        address: `${latitude.toFixed(6)}, ${longitude.toFixed(6)}`,
        city: '',
        latitude,
        longitude,
    };

    return firstUsefulAddressResult([
        reverseGpsByAmap(latitude, longitude, fallback),
        reverseGpsByBigDataCloud(latitude, longitude, fallback),
    ], fallback);
}

function firstUsefulAddressResult(promises, fallback) {
    return new Promise((resolve) => {
        let settled = false;
        let pending = promises.length;
        let fallbackResult = null;
        const finish = (result) => {
            if (settled) {
                return;
            }
            settled = true;
            resolve(result);
        };

        promises.forEach((promise) => {
            Promise.resolve(promise)
                .then((result) => {
                    if (settled) {
                        return;
                    }

                    if (result && (result.city || result.address !== fallback.address)) {
                        finish(result);
                        return;
                    }

                    fallbackResult = fallbackResult || result;
                })
                .catch(() => {
                    // Try the other reverse-geocode source.
                })
                .finally(() => {
                    pending -= 1;
                    if (!settled && pending === 0) {
                        finish(fallbackResult || fallback);
                    }
                });
        });
    });
}

async function reverseGpsByBigDataCloud(latitude, longitude, fallback) {
    try {
        const url = `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${encodeURIComponent(latitude)}&longitude=${encodeURIComponent(longitude)}&localityLanguage=zh`;
        const data = await fetchJsonOpen(url);
        const administrative = data.localityInfo && Array.isArray(data.localityInfo.administrative)
            ? data.localityInfo.administrative
            : [];
        const informative = data.localityInfo && Array.isArray(data.localityInfo.informative)
            ? data.localityInfo.informative
            : [];
        const city = data.city || data.locality || data.principalSubdivision || inferCityFromText(administrative.map((item) => item.name).join(' '));
        const parts = [
            data.countryName,
            data.principalSubdivision,
            data.city || data.locality,
            informative[0] ? informative[0].name : '',
        ].filter(Boolean);

        return {
            ...fallback,
            address: parts.length ? parts.join(' ') : fallback.address,
            city: city || inferCityFromText(parts.join(' ')),
            region: data.principalSubdivision || '',
            country: data.countryName || '',
        };
    } catch (error) {
        return fallback;
    }
}

async function reverseGpsByAmap(latitude, longitude, fallback) {
    const jsResult = await reverseGpsByAmapJs(latitude, longitude, fallback);
    if (jsResult && (jsResult.city || jsResult.address !== fallback.address)) {
        return jsResult;
    }

    return reverseGpsByAmapRest(latitude, longitude, fallback);
}

async function reverseGpsByAmapJs(latitude, longitude, fallback) {
    try {
        const AMap = await loadAmapApi();
        const converted = wgs84ToGcj02(Number(longitude), Number(latitude));
        const geocoder = new AMap.Geocoder({
            radius: 1000,
            extensions: 'base',
            lang: 'zh_cn',
        });

        return await new Promise((resolve) => {
            geocoder.getAddress([converted.lng, converted.lat], (status, result) => {
                if (status === 'complete' && result && result.regeocode) {
                    resolve(normalizeAmapRegeo(result.regeocode, fallback));
                    return;
                }

                resolve(fallback);
            });
        });
    } catch (error) {
        return fallback;
    }
}

async function reverseGpsByAmapRest(latitude, longitude, fallback) {
    try {
        const key = String(window.AMAP_REVERSE_GEOCODE_KEY || '').trim();
        const serviceHost = String(window.AMAP_SERVICE_HOST || '').trim().replace(/\/$/, '');
        if (!key || !serviceHost) {
            return fallback;
        }

        const location = `${Number(longitude).toFixed(6)},${Number(latitude).toFixed(6)}`;
        const endpoint = `${serviceHost}/v3/geocode/regeo`;
        const url = `${endpoint}?output=json&extensions=base&location=${encodeURIComponent(location)}&key=${encodeURIComponent(key)}`;
        const data = await fetchJsonOpen(url);
        const regeo = data && data.regeocode ? data.regeocode : {};
        return normalizeAmapRegeo(regeo, fallback);
    } catch (error) {
        return fallback;
    }
}

function normalizeAmapRegeo(regeo, fallback) {
    const address = regeo && regeo.addressComponent ? regeo.addressComponent : {};
    const formatted = regeo && regeo.formattedAddress
        ? regeo.formattedAddress
        : regeo && regeo.formatted_address
            ? regeo.formatted_address
            : '';
    const cityText = Array.isArray(address.city) ? '' : address.city;
    const districtText = Array.isArray(address.district) ? '' : address.district;
    const city = cityText
        || districtText
        || address.province
        || inferCityFromText(formatted);

    return {
        ...fallback,
        address: formatted || fallback.address,
        city,
        region: address.province || '',
        country: '中国',
    };
}

function renderAddressDiagnostics(diagnostics) {
    const normalized = normalizeAddressDiagnostics(diagnostics);
    state.addressDiagnostics = normalized;

    if (!el.addressDiagnostics) {
        return;
    }

    if (!normalized || !Array.isArray(normalized.sources)) {
        el.addressDiagnostics.textContent = '等待上报后显示';
        return;
    }

    const alert = document.createElement('div');
    alert.className = `address-alert ${normalized.mismatch ? 'warn' : 'ok'}`;
    alert.textContent = addressDiagnosticsStatusText(normalized);

    const rows = normalized.sources.map((source) => {
        const row = document.createElement('div');
        row.className = 'address-row';

        const title = document.createElement('div');
        title.className = 'address-name';
        title.textContent = source.name || source.type || '地址';

        const address = document.createElement('div');
        address.textContent = source.address || source.ip || '未知';

        const city = document.createElement('div');
        city.textContent = `城市：${cityDisplayName(source.city || inferCityFromText(source.address || '')) || '未知'}`;

        row.append(title, address, city);
        if (source.mobile_network_uncertain) {
            const note = document.createElement('div');
            note.className = 'address-note';
            note.textContent = '移动网络出口省份不一致';
            row.append(note);
        }
        return row;
    });

    el.addressDiagnostics.replaceChildren(alert, ...rows);
}

function locationDisplayCoordinates(location) {
    const preferredSource = preferredMapSource(location);
    const preferredCoordinates = sourceCoordinates(preferredSource);
    if (preferredCoordinates) {
        return {
            ...preferredCoordinates,
            source: preferredSource,
        };
    }

    return {
        latitude: Number(location.latitude),
        longitude: Number(location.longitude),
        source: null,
    };
}

function formatCoord(location) {
    const coordinates = locationDisplayCoordinates(location);
    const sourceLabel = coordinates.source ? ` / ${coordinates.source.name || '探测位置'}` : '';
    const accuracy = !coordinates.source && location.accuracy !== null ? ` / 精度 ${Math.round(location.accuracy)}m` : '';
    return `${coordinates.latitude.toFixed(6)}, ${coordinates.longitude.toFixed(6)}${sourceLabel}${accuracy}`;
}

function renderMarkers(locations) {
    if (!state.map) {
        return;
    }

    if (state.mapProvider === 'amap') {
        renderAmapMarkers(locations);
        return;
    }

    const activeIds = new Set();

    locations.forEach((location) => {
        activeIds.add(location.user_id);
        const key = location.user_id;
        const latLng = mapLatLng(location);
        const popup = latestPopupHtml(location);
        const color = userColor(location.user_id);
        const iconHtml = `<div class="marker-dot ${location.role}" style="--marker-color: ${escapeHtml(color)}">${escapeHtml(markerInitial(location))}</div>`;

        if (state.markers.has(key)) {
            state.markers.get(key)
                .setLatLng(latLng)
                .setPopupContent(popup)
                .setIcon(latestMarkerIcon(location, iconHtml));
            return;
        }

        const marker = L.marker(latLng, {
            icon: latestMarkerIcon(location, iconHtml),
        }).bindPopup(popup, mapPopupOptions());

        marker.addTo(state.map);
        state.markers.set(key, marker);
    });

    for (const [key, marker] of state.markers.entries()) {
        if (!activeIds.has(key)) {
            marker.remove();
            state.markers.delete(key);
        }
    }

    el.mapEmpty.hidden = locations.length > 0 || state.history.length > 0;

    if (locations.length > 0 && state.history.length === 0) {
        fitMapToLatestLocations();
    }
}

function renderAmapMarkers(locations) {
    const AMap = state.AMap;
    if (!AMap) {
        return;
    }

    const activeIds = new Set();

    locations.forEach((location) => {
        activeIds.add(location.user_id);
        const key = location.user_id;
        const position = mapLngLat(location);
        const popup = latestPopupHtml(location);
        const content = latestMarkerHtml(location);

        if (state.markers.has(key)) {
            const marker = state.markers.get(key);
            marker.__popupHtml = popup;
            marker.setPosition(position);
            marker.setContent(content);
            marker.setTitle(location.display_name || location.username || '');
            return;
        }

        const marker = new AMap.Marker({
            position,
            content,
            anchor: 'center',
            title: location.display_name || location.username || '',
            zIndex: 130,
        });
        marker.__popupHtml = popup;
        marker.on('click', () => openAmapInfoWindow(marker, marker.__popupHtml));
        state.map.add(marker);
        state.markers.set(key, marker);
    });

    for (const [key, marker] of state.markers.entries()) {
        if (!activeIds.has(key)) {
            state.map.remove(marker);
            state.markers.delete(key);
        }
    }

    el.mapEmpty.hidden = locations.length > 0 || state.history.length > 0;

    if (locations.length > 0 && state.history.length === 0) {
        fitMapToLatestLocations();
    }
}

function latestPopupHtml(location) {
    const name = location.display_name || location.username;
    return `<div class="map-popup">
        <div class="map-popup-title">${escapeHtml(name)}</div>
        <div class="map-popup-row">${escapeHtml(location.role_label || '')}</div>
        <div class="map-popup-row">${escapeHtml(location.updated_at || '')}</div>
    </div>`;
}

function historyPopupHtml(location) {
    const name = location.display_name || location.username || '';
    return `<div class="map-popup">
        <div class="map-popup-title">${escapeHtml(name)}</div>
        <div class="map-popup-row">${escapeHtml(location.role_label || '')}</div>
        <div class="map-popup-row">${escapeHtml(location.created_at || '')}</div>
        <div class="map-popup-coord">${escapeHtml(formatCoord(location))}</div>
    </div>`;
}

function latestMarkerHtml(location) {
    return `<div class="marker-dot ${escapeHtml(location.role || '')}" style="--marker-color: ${escapeHtml(userColor(location.user_id))}">${escapeHtml(markerInitial(location))}</div>`;
}

function historyMarkerHtml(location, selected = false, color = userColor(location.user_id)) {
    return `<div class="history-map-dot${selected ? ' selected' : ''}" style="--marker-color: ${escapeHtml(color)}">${escapeHtml(markerInitial(location))}</div>`;
}

function openAmapInfoWindow(marker, html) {
    if (!state.AMap || !state.map || !state.amapInfoWindow || !marker) {
        return;
    }

    state.amapInfoWindow.setContent(`<div class="amap-popup-content">${html}</div>`);
    state.amapInfoWindow.open(state.map, marker.getPosition());
}

function mapPopupOptions() {
    return {
        className: 'location-map-popup',
        minWidth: 130,
        maxWidth: 190,
        autoPanPadding: [12, 12],
    };
}

function latestMarkerIcon(location, html = '') {
    return L.divIcon({
        className: '',
        html: html || `<div class="marker-dot ${location.role}" style="--marker-color: ${escapeHtml(userColor(location.user_id))}">${escapeHtml(markerInitial(location))}</div>`,
        iconSize: [30, 30],
        iconAnchor: [15, 15],
    });
}

function historyMarkerIcon(location, selected = false, color = userColor(location.user_id)) {
    const size = selected ? 30 : 22;
    return L.divIcon({
        className: '',
        html: `<div class="history-map-dot${selected ? ' selected' : ''}" style="--marker-color: ${escapeHtml(color)}">${escapeHtml(markerInitial(location))}</div>`,
        iconSize: [size, size],
        iconAnchor: [size / 2, size / 2],
    });
}

function markerInitial(location) {
    const name = String(location.display_name || location.username || '').trim();
    const chars = Array.from(name);
    return chars[0] || '位';
}

function fitMapToLatestLocations() {
    const locations = visibleLatestLocations();
    if (!state.map || !locations.length) {
        return;
    }

    if (state.mapProvider === 'amap') {
        fitAmapToOverlays([...state.markers.values()], locations.length === 1 ? 16 : 15, [34, 34, 34, 34]);
        return;
    }

    if (typeof L === 'undefined') {
        return;
    }

    const points = locations.map((location) => mapLatLng(location));
    state.map.fitBounds(L.latLngBounds(points), {
        maxZoom: points.length === 1 ? 16 : 15,
        padding: [34, 34],
    });
}

function fitAmapToOverlays(overlays, maxZoom = 15, padding = [34, 34, 34, 34]) {
    const usable = overlays.filter(Boolean);
    if (!state.map || state.mapProvider !== 'amap' || !usable.length) {
        return;
    }

    if (usable.length === 1 && typeof usable[0].getPosition === 'function') {
        state.map.setZoomAndCenter(maxZoom, usable[0].getPosition());
        return;
    }

    if (typeof state.map.setFitView === 'function') {
        state.map.setFitView(usable, false, padding, maxZoom);
    }
}

function visibleLatestLocations() {
    if (!state.historyUserId) {
        return state.lastLocations;
    }

    return state.lastLocations.filter((location) => String(location.user_id) === String(state.historyUserId));
}

function userColor(userId) {
    const numeric = Math.abs(Number(userId) || 0);
    return USER_COLORS[numeric % USER_COLORS.length];
}

function mapLatLng(location) {
    const position = mapPosition(location);
    return [position.lat, position.lng];
}

function mapLngLat(location) {
    const position = mapPosition(location);
    return [position.lng, position.lat];
}

function mapPosition(location) {
    const coordinates = locationDisplayCoordinates(location);
    const converted = wgs84ToGcj02(coordinates.longitude, coordinates.latitude);
    return converted;
}

function wgs84ToGcj02(lng, lat) {
    if (outOfChina(lng, lat)) {
        return { lng, lat };
    }

    let dLat = transformLat(lng - 105.0, lat - 35.0);
    let dLng = transformLng(lng - 105.0, lat - 35.0);
    const radLat = lat / 180.0 * Math.PI;
    let magic = Math.sin(radLat);
    magic = 1 - 0.00669342162296594323 * magic * magic;
    const sqrtMagic = Math.sqrt(magic);
    dLat = (dLat * 180.0) / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
    dLng = (dLng * 180.0) / ((6378245.0 / sqrtMagic) * Math.cos(radLat) * Math.PI);
    return {
        lng: lng + dLng,
        lat: lat + dLat,
    };
}

function outOfChina(lng, lat) {
    return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x, y) {
    let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
    ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
    return ret;
}

function transformLng(x, y) {
    let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
    ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
    ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
    ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
    return ret;
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

if (el.termsButton) {
    el.termsButton.addEventListener('click', () => showDocumentPopup('用户协议', window.USER_AGREEMENT_SECTIONS || []));
}
if (el.privacyButton) {
    el.privacyButton.addEventListener('click', () => showDocumentPopup('隐私条约', window.PRIVACY_POLICY_SECTIONS || []));
}
if (el.settingsButton) {
    el.settingsButton.addEventListener('click', openSettingsPopup);
}

el.loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    el.loginMessage.hidden = true;

    try {
        const payload = await api('login', {
            method: 'POST',
            body: JSON.stringify({
                username: el.username.value,
                password: el.password.value,
                terms_accepted: !!(el.termsAccepted && el.termsAccepted.checked),
            }),
        });

        el.password.value = '';
        if (!payload.user && !payload.redirect) {
            return;
        }
        if (payload.redirect) {
            window.location.href = payload.redirect;
            return;
        }

        showMain(payload.user);
    } catch (error) {
        el.loginMessage.textContent = error.message;
        el.loginMessage.hidden = false;
    }
});

el.logoutButton.addEventListener('click', async () => {
    try {
        await api('logout', { method: 'POST' });
    } finally {
        showLogin();
    }
});

el.reportButton.addEventListener('click', manualReport);
el.continuousReportButton.addEventListener('click', toggleGuardianContinuousReport);
el.groupSelect.addEventListener('change', () => applySelectedGroup(el.groupSelect.value, true));
el.historyRefreshButton.addEventListener('click', refreshHistory);
el.historyUserFilter.addEventListener('change', changeHistoryUserFilter);
el.historyPageSize.addEventListener('change', changeHistoryPageSize);
el.historyMapPageSize.addEventListener('change', changeHistoryMapPageSize);
el.historyPrevButton.addEventListener('click', () => changeHistoryPage(-1));
el.historyNextButton.addEventListener('click', () => changeHistoryPage(1));
window.addEventListener('online', () => {
    refreshLocations();
    refreshHistory();
});
window.addEventListener('visibilitychange', () => {
    if (!document.hidden && state.user) {
        refreshLocations();
        refreshHistory();
    }
});

initThemeMode();
if (typeof startWebVersionWatcher === 'function') {
    startWebVersionWatcher();
}

(async function boot() {
    try {
        const payload = await api('me');
        setReportInterval(payload.report_interval_seconds);
        if (payload.user) {
            showMain(payload.user);
        } else {
            showLogin();
        }
    } catch (error) {
        showLogin(error.message);
    }
})();
