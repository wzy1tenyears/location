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

    const diagnostics = normalizeAddressDiagnostics(location.address_diagnostics);
    const preferredSource = diagnostics && Array.isArray(diagnostics.sources)
        ? diagnostics.sources.find((source) => source.type === diagnostics.preferred_source) || diagnostics.sources.find((source) => source.type === 'gps')
        : null;
    const address = preferredSource && preferredSource.address ? preferredSource.address : '';
    el.mineLocation.textContent = [
        formatCoord(location),
        address ? `地址：${address}` : '',
    ].filter(Boolean).join('\n');
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

            const diagnostics = normalizeAddressDiagnostics(location.address_diagnostics);
            const preferredSource = diagnostics && Array.isArray(diagnostics.sources)
                ? diagnostics.sources.find((source) => source.type === diagnostics.preferred_source) || diagnostics.sources.find((source) => source.type === 'gps')
                : null;
            if (preferredSource && preferredSource.address) {
                const address = document.createElement('div');
                address.className = 'location-address';
                address.textContent = `地址：${preferredSource.address}`;
                item.append(address);
            }
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

    const meituanResult = await reverseGpsByMeituan(latitude, longitude, fallback);
    const amapResult = await reverseGpsByAmap(latitude, longitude, fallback);
    const bigDataCloudResult = await reverseGpsByBigDataCloud(latitude, longitude, fallback);
    return chooseMostPreciseAddressResult([
        meituanResult,
        amapResult,
        bigDataCloudResult,
    ], fallback);
}

function isUsefulAddressResult(result, fallback) {
    return Boolean(result && (result.city || result.address !== fallback.address));
}

function chooseMostPreciseAddressResult(results, fallback) {
    let best = fallback;
    let bestScore = 0;

    results.forEach((result) => {
        if (!isUsefulAddressResult(result, fallback)) {
            return;
        }

        const score = addressPrecisionScore(result);
        if (score > bestScore) {
            best = result;
            bestScore = score;
        }
    });

    return best;
}

function addressPrecisionScore(result) {
    const text = String(result && result.address ? result.address : '');
    const detail = String(result && result.detail ? result.detail : '');
    const street = String(result && result.street ? result.street : '');
    const combined = `${text}${detail}${street}`;
    let score = 0;

    if (result && result.country) score = Math.max(score, 1);
    if (result && result.region) score = Math.max(score, 2);
    if (result && result.city) score = Math.max(score, 3);
    if (result && result.district) score = Math.max(score, 4);
    if (street || /(?:街道|镇|乡|路|街|大道|巷|弄)/.test(combined)) score = Math.max(score, 5);
    if (/(?:小区|花园|家园|公寓|大厦|广场|中心|园区|学校|医院|写字楼|商务|住宅区)/.test(combined)) score = Math.max(score, 6);
    if (detail || /(?:\d+\s*号|[一二三四五六七八九十\d]+\s*(?:栋|幢|座|单元|楼|层|室)|[A-Z]\s*\d)/i.test(combined)) score = Math.max(score, 7);

    return score;
}

async function reverseGpsByMeituan(latitude, longitude, fallback) {
    try {
        const meituanLatitude = formatMeituanCoordinate(latitude);
        const meituanLongitude = formatMeituanCoordinate(longitude);
        const url = `https://apimobile.meituan.com/group/v1/city/latlng/${encodeURIComponent(meituanLatitude)},${encodeURIComponent(meituanLongitude)}?tag=0`;
        const data = await fetchJsonOpen(url);
        const result = data && data.data ? data.data : null;
        if (!result) {
            return fallback;
        }

        const detail = firstAddressValue([result.detail]);
        const detailParts = collectAddressValues([
            result.street,
            result.road,
            result.township,
            result.town,
            result.areaName,
            result.parentAreaName,
            result.zoneName,
            result.businessArea,
            result.neighborhood,
            result.community,
            result.village,
            result.building,
            result.buildingName,
            result.shopName,
            result.mallName,
            result.poiName,
            result.aoiName,
            result.name,
            result.address,
        ], 12);
        const addressDetailParts = collectAddressValues([
            ...detailParts,
            detail,
        ]);
        const street = firstAddressValue([
            result.street,
            result.road,
            result.township,
            result.town,
            result.areaName,
        ]);
        const city = result.city || result.openCityName || inferCityFromText(fallback.address);
        const displayCity = cityDisplayName(city);
        const address = composeStructuredAddress([
            result.country || '中国',
            result.province,
            displayCity || city,
            result.district,
            ...addressDetailParts,
        ], fallback.address);

        return {
            ...fallback,
            address,
            detail,
            city,
            district: result.district || '',
            street,
            region: result.province || '',
            country: result.country || '中国',
            provider: '美团',
        };
    } catch (error) {
        return fallback;
    }
}

function formatMeituanCoordinate(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number.toFixed(6) : String(value || '').trim();
}

function uniqueAddressParts(parts) {
    const selected = [];
    const seen = new Set();
    parts.forEach((part) => {
        const text = String(part || '').trim();
        const key = text.replace(/\s+/g, '');
        if (!text || seen.has(key)) {
            return;
        }
        if (selected.some((item) => item.replace(/\s+/g, '').includes(key))) {
            return;
        }

        for (let index = selected.length - 1; index >= 0; index -= 1) {
            const selectedKey = selected[index].replace(/\s+/g, '');
            if (key.includes(selectedKey)) {
                seen.delete(selectedKey);
                selected.splice(index, 1);
            }
        }

        seen.add(key);
        selected.push(text);
    });

    return selected;
}

function firstAddressValue(values) {
    for (const value of values) {
        if (Array.isArray(value)) {
            const nested = firstAddressValue(value);
            if (nested) {
                return nested;
            }
            continue;
        }

        if (value && typeof value === 'object') {
            const objectText = firstAddressValue([
                value.name,
                value.number ? `${value.street || ''}${value.number}` : '',
                value.street,
                value.address,
            ]);
            if (objectText) {
                return objectText;
            }
            continue;
        }

        const text = String(value || '').trim();
        if (text && text !== '0') {
            return text;
        }
    }

    return '';
}

function composeStructuredAddress(parts, fallbackAddress = '') {
    const structuredParts = uniqueAddressParts(parts);
    return structuredParts.length ? structuredParts.join('') : fallbackAddress;
}

function collectAddressValues(values, limit = 0) {
    const collected = [];
    for (const value of values) {
        if (Array.isArray(value)) {
            collected.push(...collectAddressValues(value, limit > 0 ? Math.max(0, limit - collected.length) : 0));
        } else if (value && typeof value === 'object') {
            collected.push(...collectAddressValues([
                value.name,
                value.address,
                value.businessArea,
                value.district,
                value.township,
                value.street,
                value.number ? `${value.street || ''}${value.number}` : '',
                value.type,
            ], limit > 0 ? Math.max(0, limit - collected.length) : 0));
        } else {
            const text = String(value || '').trim();
            if (text && text !== '0') {
                collected.push(text);
            }
        }

        if (limit > 0 && collected.length >= limit) {
            break;
        }
    }

    return uniqueAddressParts(collected).slice(0, limit > 0 ? limit : undefined);
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
        const administrativeNames = administrative.map((item) => item.name);
        const informativeNames = informative.map((item) => item.name);
        const city = data.city || data.locality || data.principalSubdivision || inferCityFromText(administrativeNames.join(' '));
        const district = data.city && data.locality && data.locality !== data.city ? data.locality : '';
        const detailParts = collectAddressValues(informativeNames, 8);
        const street = firstAddressValue(detailParts);
        const detail = detailParts.join('');
        const address = composeStructuredAddress([
            data.countryName,
            data.principalSubdivision,
            data.city || data.locality,
            district,
            ...detailParts,
        ], fallback.address);

        return {
            ...fallback,
            address,
            city: city || inferCityFromText(address),
            district,
            street,
            detail,
            region: data.principalSubdivision || '',
            country: data.countryName || '',
            provider: 'BigDataCloud',
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
            extensions: 'all',
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
        const url = `${endpoint}?output=json&extensions=all&location=${encodeURIComponent(location)}&key=${encodeURIComponent(key)}`;
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
    const country = firstAddressValue([address.country, '中国']);
    const township = firstAddressValue([address.township, address.town]);
    const streetNumber = firstAddressValue([address.streetNumber]);
    const street = firstAddressValue([
        township,
        address.street,
        address.road,
    ]);
    const detailParts = collectAddressValues([
        streetNumber,
        address.neighborhood,
        address.building,
    ], 4);
    const structured = composeStructuredAddress([
        country,
        address.province,
        cityText,
        districtText,
        street,
        ...detailParts,
    ]);
    const formattedWithCountry = formatted && country && !formatted.startsWith(country)
        ? `${country}${formatted}`
        : formatted;
    const detailedAddress = formattedWithCountry || structured || fallback.address;
    const city = cityText
        || districtText
        || address.province
        || inferCityFromText(formatted);

    return {
        ...fallback,
        address: detailedAddress,
        city,
        district: districtText,
        street,
        detail: detailParts.join(''),
        region: address.province || '',
        country,
        provider: '高德',
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
    if (location && location.encrypted_unreadable) {
        return '加密位置无法解密';
    }
    const coordinates = locationDisplayCoordinates(location);
    const sourceLabel = coordinates.source ? ` / ${coordinates.source.name || '探测位置'}` : '';
    const accuracy = !coordinates.source && location.accuracy !== null ? ` / 精度 ${Math.round(location.accuracy)}m` : '';
    const altitude = Number(location.altitude);
    const altitudeText = !coordinates.source && location.altitude !== null && location.altitude !== undefined && Number.isFinite(altitude)
        ? ` / 高度 ${Math.round(altitude)}m`
        : '';
    return `${coordinates.latitude.toFixed(6)}, ${coordinates.longitude.toFixed(6)}${sourceLabel}${altitudeText}${accuracy}`;
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

    if (locations.length > 0 && state.history.length === 0 && state.pendingLatestLocationFocus) {
        state.pendingLatestLocationFocus = !focusMostRecentLatestLocation(locations);
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

    if (locations.length > 0 && state.history.length === 0 && state.pendingLatestLocationFocus) {
        state.pendingLatestLocationFocus = !focusMostRecentLatestLocation(locations);
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

function closeMapPopup() {
    if (state.mapProvider === 'amap' && state.amapInfoWindow && typeof state.amapInfoWindow.close === 'function') {
        state.amapInfoWindow.close();
    }

    if (state.mapProvider === 'leaflet' && state.map && typeof state.map.closePopup === 'function') {
        state.map.closePopup();
    }
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

function focusMostRecentLatestLocation(locations = visibleLatestLocations()) {
    if (!state.map || !locations.length) {
        return false;
    }

    const location = mostRecentLocation(locations);
    if (!location) {
        return false;
    }

    if (state.mapProvider === 'amap') {
        const marker = state.markers.get(location.user_id);
        const position = marker && typeof marker.getPosition === 'function'
            ? marker.getPosition()
            : mapLngLat(location);
        const currentZoom = typeof state.map.getZoom === 'function' ? Number(state.map.getZoom()) : 0;
        const zoom = Math.max(Number.isFinite(currentZoom) ? currentZoom : 0, 16);

        if (typeof state.map.setZoomAndCenter === 'function') {
            state.map.setZoomAndCenter(zoom, position);
            return true;
        }

        if (typeof state.map.setCenter === 'function') {
            state.map.setCenter(position);
            if (typeof state.map.setZoom === 'function') {
                state.map.setZoom(zoom);
            }
            return true;
        }

        return false;
    }

    if (typeof L === 'undefined' || typeof state.map.setView !== 'function') {
        return false;
    }

    const currentZoom = typeof state.map.getZoom === 'function' ? Number(state.map.getZoom()) : 0;
    const zoom = Math.max(Number.isFinite(currentZoom) ? currentZoom : 0, 16);
    state.map.setView(mapLatLng(location), zoom, { animate: false });
    return true;
}

function mostRecentLocation(locations) {
    return locations.reduce((latest, location) => {
        if (!latest) {
            return location;
        }

        const left = locationTimestampValue(location);
        const right = locationTimestampValue(latest);
        return left >= right ? location : latest;
    }, null);
}

function locationTimestampValue(location) {
    const value = String(location.updated_at || location.created_at || '').replace(' ', 'T');
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : 0;
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
    const readable = state.lastLocations.filter(isDisplayableLocation);
    if (!state.historyUserId) {
        return readable;
    }

    return readable.filter((location) => String(location.user_id) === String(state.historyUserId));
}

function isDisplayableLocation(location) {
    const latitude = Number(location && location.latitude);
    const longitude = Number(location && location.longitude);
    return !location.encrypted_unreadable
        && Number.isFinite(latitude)
        && Number.isFinite(longitude)
        && !(latitude === 0 && longitude === 0 && location.encryption_mode === 'p2p-v1');
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

