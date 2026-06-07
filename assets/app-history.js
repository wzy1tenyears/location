let historySelectionMapFrame = 0;

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
        const groupName = data.selected_group ? data.selected_group.group_name : state.selectedGroupName;
        state.history = await decryptLocationRecords(groupName, data.history || []);
        state.historyMap = await decryptLocationRecords(groupName, data.map_history || []);
        state.historyMembers = data.members || [];
        state.historyPagination = data.pagination || null;
        state.selectedHistoryId = null;
        renderHistory();
    } catch (error) {
        renderHistoryMessage(error.message);
        clearHistoryLayers();
    }
}

let historyMapRenderTimer = 0;

async function decryptLocationRecords(groupName, records) {
    if (!window.P2PLocationCrypto || typeof window.P2PLocationCrypto.decryptRecords !== 'function') {
        return records;
    }
    return window.P2PLocationCrypto.decryptRecords(groupName, records);
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
    scheduleHistoryMapRender(historyMapRecords());
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
    const records = state.historyMap.filter(isDisplayableLocation);
    if (!state.selectedHistoryId || records.some((location) => location.id === state.selectedHistoryId)) {
        return records;
    }

    const selected = state.history.find((location) => location.id === state.selectedHistoryId);
    if (selected && isDisplayableLocation(selected)) {
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

function updateHistoryListSelection(previousId, nextId) {
    if (!el.historyList) {
        return;
    }

    const ids = [...new Set([previousId, nextId].filter((id) => id !== null && id !== undefined).map(Number))];
    ids.forEach((id) => {
        const item = historyItemElement(id);
        const location = state.history.find((record) => record.id === id);
        if (!item || !location) {
            return;
        }

        const selected = state.selectedHistoryId === id;
        item.classList.toggle('selected', selected);
        item.setAttribute('aria-expanded', selected ? 'true' : 'false');

        const oldDetails = Array.from(item.children).find((child) => child.classList && child.classList.contains('history-details'));
        if (oldDetails) {
            oldDetails.remove();
        }
        if (selected) {
            item.append(renderHistoryDetails(location));
        }
    });
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

    const altitude = Number(location.altitude);
    if (location.altitude !== null && location.altitude !== undefined && Number.isFinite(altitude)) {
        rows.splice(2, 0, ['高度', `${Math.round(altitude)}m`]);
    }

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
        const previousId = state.selectedHistoryId;
        state.selectedHistoryId = previousId === id ? null : id;
        updateHistoryListSelection(previousId, state.selectedHistoryId);
        scheduleHistorySelectionOnMap(previousId, state.selectedHistoryId);
    });
}

function selectHistory(locationId) {
    withStableHistoryScroll(locationId, () => {
        const id = Number(locationId);
        const previousId = state.selectedHistoryId;
        state.selectedHistoryId = id;
        if (previousId !== id) {
            updateHistoryListSelection(previousId, id);
        }
        scheduleHistorySelectionOnMap(previousId, id);
    });
}

function scheduleHistorySelectionOnMap(previousId, nextId) {
    if (historySelectionMapFrame) {
        window.cancelAnimationFrame(historySelectionMapFrame);
    }
    historySelectionMapFrame = window.requestAnimationFrame(() => {
        historySelectionMapFrame = 0;
        updateHistorySelectionOnMap(previousId, nextId);
    });
}

function updateHistorySelectionOnMap(previousId, nextId) {
    if (!state.map) {
        return;
    }

    if (state.selectedHistoryId && !state.historyMarkers.has(state.selectedHistoryId)) {
        renderHistoryMap(historyMapRecords(), false);
    }

    const records = historyMapRecords();
    const locationsById = new Map(records.map((location) => [location.id, location]));
    const ids = [...new Set([previousId, nextId].filter((id) => id !== null && id !== undefined).map(Number))];
    ids.forEach((id) => {
        const location = locationsById.get(id);
        if (!location) {
            return;
        }
        const marker = state.historyMarkers.get(location.id);
        if (!marker) {
            return;
        }
        const selected = state.selectedHistoryId === location.id;
        const color = userColor(location.user_id);
        if (state.mapProvider === 'amap') {
            marker.setContent(historyMarkerHtml(location, selected, color));
            if (typeof marker.setzIndex === 'function') {
                marker.setzIndex(selected ? 140 : 110);
            }
            return;
        }
        if (typeof marker.setIcon === 'function') {
            marker.setIcon(historyMarkerIcon(location, selected, color));
        }
    });

    const selected = state.selectedHistoryId ? locationsById.get(state.selectedHistoryId) : null;
    if (selected && isDisplayableLocation(selected)) {
        if (state.mapProvider === 'amap' && typeof state.map.setZoomAndCenter === 'function') {
            state.map.setZoomAndCenter(Math.max(state.map.getZoom(), 16), mapLngLat(selected));
        } else if (typeof state.map.setView === 'function') {
            state.map.setView(mapLatLng(selected), Math.max(state.map.getZoom(), 16), {
                animate: true,
            });
        }
    }
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
            const selected = state.selectedHistoryId === location.id;
            const latLng = mapLatLng(location);

            const marker = L.marker(latLng, {
                icon: historyMarkerIcon(location, selected, color),
            });

            marker.on('click', () => selectHistory(location.id));
            marker.addTo(state.historyLayer);
            state.historyMarkers.set(location.id, marker);

            if (selected) {
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

    if (adjustViewport && selectedLatLng) {
        state.map.setView(selectedLatLng, Math.max(state.map.getZoom(), 16), {
            animate: true,
        });
    }
}

function scheduleHistoryMapRender(records, adjustViewport = true) {
    if (historyMapRenderTimer) {
        window.clearTimeout(historyMapRenderTimer);
    }

    historyMapRenderTimer = window.setTimeout(() => {
        historyMapRenderTimer = 0;
        renderHistoryMap(records, adjustViewport);
    }, 80);
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
            marker.on('click', () => selectHistory(location.id));
            markerOverlays.push(marker);
            state.historyMarkers.set(location.id, marker);

            if (selected) {
                selectedPosition = position;
            }
        });
    }

    state.map.add([...lineOverlays, ...markerOverlays]);

    if (adjustViewport) {
        fitAmapToOverlays([...state.markers.values(), ...lineOverlays, ...markerOverlays], markerOverlays.length === 1 ? 16 : 15, [28, 28, 28, 28]);
    }

    if (adjustViewport && selectedPosition) {
        state.map.setZoomAndCenter(Math.max(state.map.getZoom(), 16), selectedPosition);
    }
}

function clearHistoryLayers() {
    if (state.amapInfoWindow && typeof state.amapInfoWindow.close === 'function') {
        state.amapInfoWindow.close();
    }

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

