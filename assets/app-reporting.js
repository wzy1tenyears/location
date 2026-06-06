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

function startHeartbeat() {
    stopHeartbeat();
    sendHeartbeat();
    state.heartbeatTimer = window.setInterval(sendHeartbeat, 60000);
}

function stopHeartbeat() {
    if (state.heartbeatTimer !== null) {
        window.clearInterval(state.heartbeatTimer);
        state.heartbeatTimer = null;
    }
}

async function sendHeartbeat() {
    if (!state.user) {
        return;
    }

    try {
        await api('heartbeat', {
            method: 'POST',
            body: JSON.stringify({ group_name: state.selectedGroupName || '' }),
        });
    } catch (error) {
        console.warn(error);
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

    const { latitude, longitude, altitude, accuracy, heading, speed } = position.coords;
    const reportGroupName = state.selectedGroupName;
    const extraGroupNames = automatic ? [] : selectedCrossSyncGroups().filter((groupName) => groupName !== reportGroupName);

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
                    const diagnosticsPayload = await buildLocationReportPayload(reportGroupName, {
                        group_name: reportGroupName,
                        location_id: locationId,
                        latitude,
                        longitude,
                        altitude,
                        accuracy,
                        heading,
                        speed,
                        address_diagnostics: nextDiagnostics,
                        address_mismatch: nextDiagnostics.mismatch,
                    }, null);
                    await api('report_location', {
                        method: 'POST',
                        body: JSON.stringify(diagnosticsPayload),
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

        const deviceReport = deviceReportForLocation();
        const buildReportPayload = (groupName, diagnostics) => buildLocationReportPayload(groupName, {
            group_name: groupName,
            latitude,
            longitude,
            altitude,
            accuracy,
            heading,
            speed,
            address_diagnostics: diagnostics,
            address_mismatch: diagnostics.mismatch,
        }, deviceReport);

        setStatus(automatic ? '正在自动上报' : '正在上报');
        const report = await api('report_location', {
            method: 'POST',
            body: JSON.stringify(await buildReportPayload(reportGroupName, addressDiagnostics)),
        });
        locationId = Number(report.location_id) || null;
        for (const groupName of extraGroupNames) {
            await api('report_location', {
                method: 'POST',
                body: JSON.stringify(await buildReportPayload(groupName, addressDiagnostics)),
            });
        }
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

async function buildLocationReportPayload(groupName, payload, deviceReport = null) {
    if (window.P2PLocationCrypto && typeof window.P2PLocationCrypto.encryptReport === 'function') {
        const encrypted = await window.P2PLocationCrypto.encryptReport(groupName, payload);
        if (encrypted) {
            const wrapped = {
                group_name: groupName,
                encrypted_payload: encrypted.payload,
                p2p_key_version: encrypted.key_version,
            };
            if (payload.location_id) {
                wrapped.location_id = payload.location_id;
            }
            if (deviceReport) {
                wrapped.device_report = deviceReport;
            }
            return wrapped;
        }
    }

    const plain = { ...payload, group_name: groupName };
    if (deviceReport) {
        plain.device_report = deviceReport;
    }
    return plain;
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
        const groupName = data.selected_group ? data.selected_group.group_name : state.selectedGroupName;
        state.lastLocations = await decryptLocationRecords(groupName, data.locations || []);
        data.locations = state.lastLocations;
        data.mine = state.lastLocations.find((location) => Number(location.user_id) === Number(state.user.id)) || null;
        data.monitors = state.lastLocations.filter((location) => location.role === 'monitor');
        data.guardians = state.lastLocations.filter((location) => location.role !== 'monitor');
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

