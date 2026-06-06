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
    heartbeatTimer: null,
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
    announcement: null,
    legalDocuments: null,
    pendingLatestLocationFocus: false,
    backgroundedAt: 0,
    clipboardInviteChecked: false,
    groupReloadTimer: null,
    groupReloadToken: 0,
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
    crossBorderAccepted: document.querySelector('#crossBorderAccepted'),
    crossBorderButton: document.querySelector('#crossBorderButton'),
    registerButton: document.querySelector('#registerButton'),
    turnstileBox: document.querySelector('#turnstileBox'),
    appTitle: document.querySelector('#appTitle'),
    accountLine: document.querySelector('#accountLine'),
    ticketButton: document.querySelector('#ticketButton'),
    announcementButton: document.querySelector('#announcementButton'),
    settingsButton: document.querySelector('#settingsButton'),
    logoutButton: document.querySelector('#logoutButton'),
    reportButton: document.querySelector('#reportButton'),
    crossGroupSyncButton: document.querySelector('#crossGroupSyncButton'),
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

function scheduleIdleTask(callback, timeout = 2000) {
    if (typeof callback !== 'function') {
        return 0;
    }

    if (typeof window.requestIdleCallback === 'function') {
        return window.requestIdleCallback(callback, { timeout });
    }

    return window.setTimeout(callback, 80);
}

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

function refreshPopupSelectControls(root = document) {
    if (typeof window.refreshPopupSelects === 'function') {
        window.refreshPopupSelects(root);
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
    const [scriptPath, query = ''] = String(path).split('?');
    const url = `${API_BASE}/${scriptPath}.php${query ? `?${query}` : ''}`;
    const response = await fetch(url, {
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
    stopHeartbeat();
    clearNativeReportingState();
    clearHistory();
    renderAddressDiagnostics(null);
    if (!el.loginView || !el.mainView) {
        if (message) {
            sessionStorage.setItem('login_message', message);
        }
        window.location.href = window.location.pathname || '/';
        return;
    }
    state.user = null;
    state.selectedGroupName = '';
    state.guardianContinuousReporting = false;
    state.lastImmediateAutoReportKey = '';
    state.historyUserId = '';
    state.selectedHistoryId = null;
    el.reportButton.hidden = true;
    el.reportButton.disabled = false;
    if (el.crossGroupSyncButton) {
        el.crossGroupSyncButton.hidden = true;
    }
    el.continuousReportButton.hidden = true;
    el.continuousReportButton.disabled = false;
    updateContinuousReportButton();
    el.logoutButton.hidden = true;
    if (el.settingsButton) {
        el.settingsButton.hidden = true;
    }
    if (el.announcementButton) {
        el.announcementButton.hidden = true;
    }
    if (el.ticketButton) {
        el.ticketButton.hidden = true;
    }
    el.mainView.hidden = true;
    el.loginView.hidden = false;
    el.loginMessage.hidden = message === '';
    el.loginMessage.textContent = message;
}

function showMain(user) {
    state.user = user;
    window.__CURRENT_USER_ID__ = Number(user && user.id) || 0;
    setReportInterval(user.report_interval_seconds);
    stopWatch();
    state.pendingLatestLocationFocus = true;
    if (el.loginView) {
        el.loginView.hidden = true;
    }
    if (el.mainView) {
        el.mainView.hidden = false;
    }
    if (el.logoutButton) {
        el.logoutButton.hidden = false;
    }
    if (el.settingsButton) {
        el.settingsButton.hidden = false;
    }
    if (el.announcementButton) {
        el.announcementButton.hidden = false;
    }
    if (el.ticketButton) {
        el.ticketButton.hidden = false;
    }
    if (el.crossGroupSyncButton) {
        el.crossGroupSyncButton.hidden = false;
    }
    initMap();
    if (window.P2PLocationCrypto && typeof window.P2PLocationCrypto.warmup === 'function') {
        scheduleIdleTask(() => {
            window.P2PLocationCrypto.warmup().catch((error) => console.warn(error));
        }, 3000);
    }
    startRefresh();
    applySelectedGroup(preferredGroupName(user), false);
    refreshLocations();
    scheduleIdleTask(refreshHistory, 2500);
    syncAutoReportWatch();
    checkFineLocationPermission();
    scheduleIdleTask(uploadEnvironmentDataIfAllowed, 5000);
    scheduleIdleTask(uploadDeviceReportIfAvailable, 5000);
    scheduleIdleTask(() => refreshAnnouncement(true), 3500);
    startHeartbeat();
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

function userDisplayName(user) {
    return (user && (user.display_name || user.username)) || '';
}

function groupDisplayName(group) {
    return (group && (group.display_name || group.group_name)) || '';
}

function groupOptionText(group) {
    const name = groupDisplayName(group) || '未命名家庭组';
    const code = group && group.group_code ? group.group_code : '未生成组号';
    const role = group && group.role_label ? group.role_label : '未知类型';
    return `${name}/${code}/${role}`;
}

function applySelectedGroup(groupName, reload = true) {
    if (!state.user) {
        return;
    }

    closeMapPopup();

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
    el.accountLine.textContent = `${state.user.display_name || state.user.username} / ${group ? groupDisplayName(group) : '暂无家庭组'}`;

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
    state.pendingLatestLocationFocus = true;
    state.lastAutoReportAt = 0;
    state.lastImmediateAutoReportKey = '';
    scheduleSelectedGroupReload();
}

function scheduleSelectedGroupReload() {
    const token = state.groupReloadToken + 1;
    state.groupReloadToken = token;
    if (state.groupReloadTimer !== null) {
        window.clearTimeout(state.groupReloadTimer);
    }

    state.groupReloadTimer = window.setTimeout(() => {
        if (token !== state.groupReloadToken) {
            return;
        }

        state.groupReloadTimer = null;
        clearHistoryLayers();
        refreshLocations();
        refreshHistory();
        syncAutoReportWatch();
    }, 80);
}

function renderGroupSelect() {
    if (!el.groupSelect) {
        return;
    }

    const groups = userGroups();
    const options = groups.length
        ? groups.map((group) => new Option(groupOptionText(group), group.group_name))
        : [new Option('暂无家庭组', '')];

    const signature = options.map((option) => `${option.value}:${option.textContent}`).join('|');
    if (el.groupSelect.dataset.optionSignature !== signature) {
        el.groupSelect.replaceChildren(...options);
        el.groupSelect.dataset.optionSignature = signature;
    }
    el.groupSelect.value = state.selectedGroupName;
    el.groupSelect.disabled = groups.length <= 1;
    refreshPopupSelectControls(el.groupSelect.parentElement || document);
}

function syncUserPayload(payload, preferredGroup = '') {
    if (!payload || !payload.user) {
        return;
    }

    state.user = payload.user;
    setReportInterval(state.user.report_interval_seconds);
    renderGroupSelect();

    const groups = userGroups();
    const nextGroup = preferredGroup && groups.some((group) => group.group_name === preferredGroup)
        ? preferredGroup
        : preferredGroupName(state.user);
    applySelectedGroup(nextGroup, true);
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

async function uploadEnvironmentDataIfAllowed(force = false, showResult = false) {
    if (!state.user || !window.LocationBridge) {
        if (showResult) {
            showSimplePopup('环境上报不可用', '当前环境不支持读取环境数据。');
        }
        return;
    }

    if (!state.user.environment_data_consent) {
        if (showResult) {
            showSimplePopup('环境上报未开启', '请先在设置中同意上报环境数据。');
        }
        return;
    }

    const today = localDateKey();
    const storageKey = `environment_reported_day_${state.user.id}`;
    const hashKey = `environment_report_hash_${state.user.id}`;

    if (typeof window.LocationBridge.getEnvironmentData !== 'function') {
        if (showResult) {
            showSimplePopup('环境上报不可用', '当前 App 版本不支持读取环境数据。');
        }
        return;
    }

    try {
        const raw = window.LocationBridge.getEnvironmentData();
        const currentHash = simpleHash(raw || '');
        if (!force && window.localStorage.getItem(storageKey) === today && window.localStorage.getItem(hashKey) === currentHash) {
            if (showResult) {
                showSimplePopup('无需重复上报', '今天的环境数据已经上报过。');
            }
            return;
        }
        const report = JSON.parse(raw || '{}');
        if (environmentReportNeedsPrivacyPrompt(report)) {
            showEnvironmentPrivacyPrompt();
        }
        await api('environment_report', {
            method: 'POST',
            body: JSON.stringify({ report, force }),
        });
        window.localStorage.setItem(storageKey, today);
        window.localStorage.setItem(hashKey, currentHash);
        if (showResult) {
            showSimplePopup('上报成功', '环境数据已上报。');
        }
    } catch (error) {
        console.warn(error);
        if (showResult) {
            showSimplePopup('上报失败', error.message || '环境数据上报失败。');
        }
    }
}

function environmentReportNeedsPrivacyPrompt(report) {
    if (!report || typeof report !== 'object') {
        return false;
    }

    return report.installed_app_visibility_limited === true
        || report.installed_app_list_empty === true
        || Number(report.installed_app_empty_package_count || 0) > 0;
}

function showEnvironmentPrivacyPrompt() {
    if (!state.user) {
        return;
    }

    const today = localDateKey();
    const key = `environment_privacy_prompt_${state.user.id}`;
    if (window.localStorage.getItem(key) === today) {
        return;
    }

    window.localStorage.setItem(key, today);
    showSimplePopup('应用列表受限', [
        '系统返回的应用列表为空或包含异常包名，环境数据可能不完整。',
        '请在系统设置中确认“位置”拥有应用列表/读取应用列表相关权限，并关闭会隐藏应用列表的隐私限制。',
    ], {
        closeText: '我知道了',
    });
}

function simpleHash(value) {
    let hash = 0;
    const text = String(value || '');
    for (let index = 0; index < text.length; index += 1) {
        hash = ((hash << 5) - hash + text.charCodeAt(index)) | 0;
    }

    return String(hash >>> 0);
}

async function uploadDeviceReportIfAvailable() {
    if (!state.user || !window.LocationBridge || typeof window.LocationBridge.getDeviceIntegrityData !== 'function') {
        return;
    }

    const storageKey = `device_integrity_reported_day_${state.user.id}`;
    const today = localDateKey();
    if (window.localStorage.getItem(storageKey) === today) {
        return;
    }

    try {
        const report = JSON.parse(window.LocationBridge.getDeviceIntegrityData() || '{}');
        await api('device_report', {
            method: 'POST',
            body: JSON.stringify({ report }),
        });
        window.localStorage.setItem(storageKey, today);
    } catch (error) {
        console.warn(error);
    }
}

function deviceReportForLocation() {
    if (!window.LocationBridge || typeof window.LocationBridge.getDeviceIntegrityData !== 'function') {
        return null;
    }

    try {
        const report = JSON.parse(window.LocationBridge.getDeviceIntegrityData() || '{}');
        return report && typeof report === 'object' ? report : null;
    } catch (error) {
        console.warn(error);
        return null;
    }
}

function localDateKey(date = new Date()) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

