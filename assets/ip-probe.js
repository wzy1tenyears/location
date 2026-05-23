const IP_PROVIDERS = {
    address: {
        ipv4: [
            { name: 'IPW IPv4', url: 'https://4.ipw.cn', region: 'domestic', family: 'ipv4', timeoutMs: 1600 },
            { name: 'ipify IPv4', url: 'https://api.ipify.org?format=json', region: 'global', family: 'ipv4', timeoutMs: 1600 },
            { name: 'ipapi IP', url: 'https://ipapi.co/ip', region: 'global', family: 'ipv4', timeoutMs: 1600 },
            { name: 'icanhazip IPv4', url: 'https://ipv4.icanhazip.com', region: 'global', family: 'ipv4', timeoutMs: 1600 },
        ],
        ipv6: [
            { name: 'IPW IPv6', url: 'https://6.ipw.cn', region: 'domestic', family: 'ipv6', timeoutMs: 1600 },
            { name: 'ipify IPv6', url: 'https://api6.ipify.org?format=json', region: 'global', family: 'ipv6', timeoutMs: 1600 },
            { name: 'icanhazip IPv6', url: 'https://ipv6.icanhazip.com', region: 'global', family: 'ipv6', timeoutMs: 1600 },
        ],
        auto: [
            { name: 'IPW Auto', url: 'https://test.ipw.cn', region: 'domestic', family: 'auto', timeoutMs: 1600 },
            { name: 'IPIP', url: 'https://myip.ipip.net', region: 'domestic', family: 'auto', timeoutMs: 1600 },
            { name: 'Oray', url: 'https://ddns.oray.com/checkip', region: 'domestic', family: 'auto', timeoutMs: 1600 },
            { name: 'Sohu', url: 'https://pv.sohu.com/cityjson?ie=utf-8', region: 'domestic', family: 'auto', timeoutMs: 1600 },
            { name: 'ipify Auto', url: 'https://api64.ipify.org?format=json', region: 'global', family: 'auto', timeoutMs: 1600 },
            { name: 'Cloudflare Trace', url: 'https://www.cloudflare.com/cdn-cgi/trace', region: 'global', family: 'auto', timeoutMs: 1600 },
            { name: 'ipinfo IP', url: 'https://ipinfo.io/ip', region: 'global', family: 'auto', timeoutMs: 1600 },
            { name: 'checkip Amazon', url: 'https://checkip.amazonaws.com', region: 'global', family: 'auto', timeoutMs: 1600 },
        ],
    },
    geolocation: [
        {
            name: 'Cloudflare Visitor',
            lookup: async (ip) => {
                const data = await getCloudflareLocationProbe();
                if (!data || !data.available) {
                    return null;
                }

                const cloudflareIps = [data.ip, data.ipv6]
                    .map((value) => String(value || '').trim())
                    .filter((value) => value && isIpAddress(value));
                if (!cloudflareIps.length || !cloudflareIps.includes(ip)) {
                    return null;
                }

                return normalizeIpGeo({
                    ip,
                    country: data.country || data.country_code || '',
                    region: data.region || data.region_code || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'Cloudflare',
                });
            },
        },
        {
            name: 'IPinfo Lite',
            lookup: async (ip) => {
                const data = await api('ipinfo_lite', {
                    method: 'POST',
                    body: JSON.stringify({ ip }),
                });
                return normalizeIpGeo({
                    ip,
                    country: data.country || data.country_code || '',
                    region: data.region || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'IPinfo Lite',
                });
            },
        },
        {
            name: 'ip-api',
            lookup: async (ip) => serverIpGeoLookup('ip-api', ip),
        },
        {
            name: 'IP2Location.io',
            lookup: async (ip) => serverIpGeoLookup('ip2location', ip),
        },
        {
            name: 'ipdata.co',
            lookup: async (ip) => serverIpGeoLookup('ipdata', ip),
        },
        {
            name: 'Ipregistry',
            lookup: async (ip) => serverIpGeoLookup('ipregistry', ip),
        },
        {
            name: 'IP.SB',
            lookup: async (ip) => {
                const data = await fetchJson(`https://api.ip.sb/geoip/${encodeURIComponent(ip)}`, 3500);
                return normalizeIpGeo({
                    ip,
                    country: data.country || data.country_code || '',
                    region: data.region || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'IP.SB',
                });
            },
        },
        {
            name: 'ipapi',
            lookup: async (ip) => {
                const data = await fetchJson(`https://ipapi.co/${encodeURIComponent(ip)}/json/`, 3500);
                return normalizeIpGeo({
                    ip,
                    country: data.country_name || data.country || '',
                    region: data.region || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'ipapi',
                });
            },
        },
        {
            name: 'country.is',
            lookup: async (ip) => {
                const data = await fetchJson(`https://api.country.is/${encodeURIComponent(ip)}?fields=city,subdivision,location`, 3500);
                const location = data.location || {};
                return normalizeIpGeo({
                    ip,
                    country: data.country || data.countryCode || '',
                    region: data.subdivision || data.region || '',
                    city: data.city || '',
                    latitude: location.latitude || location.lat,
                    longitude: location.longitude || location.lng || location.lon,
                    provider: 'country.is',
                });
            },
        },
        {
            name: 'IPBot',
            lookup: async (ip) => {
                const data = await fetchJson(`https://api.ipbot.com/${encodeURIComponent(ip)}`, 3500);
                const location = data.location || {};
                return normalizeIpGeo({
                    ip,
                    country: data.country || data.country_name || data.countryCode || location.country || '',
                    region: data.region || data.region_name || data.state || location.region || '',
                    city: data.city || location.city || '',
                    latitude: data.latitude || data.lat || location.latitude || location.lat,
                    longitude: data.longitude || data.lon || data.lng || location.longitude || location.lon || location.lng,
                    provider: 'IPBot',
                });
            },
        },
        {
            name: 'ReallyFreeGeoIP',
            lookup: async (ip) => {
                const data = await fetchJson(`https://reallyfreegeoip.org/json/${encodeURIComponent(ip)}`, 3500);
                return normalizeIpGeo({
                    ip,
                    country: data.country_name || data.country_code || data.country || '',
                    region: data.region_name || data.region_code || data.region || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'ReallyFreeGeoIP',
                });
            },
        },
        {
            name: 'apip.cc',
            lookup: async (ip) => {
                const data = await fetchJson(`https://apip.cc/api-json/${encodeURIComponent(ip)}`, 3500);
                return normalizeIpGeo({
                    ip,
                    country: data.countryName || data.country_name || data.country || data.countryCode || '',
                    region: data.regionName || data.region_name || data.region || data.state || '',
                    city: data.cityName || data.city_name || data.city || '',
                    latitude: data.latitude || data.lat,
                    longitude: data.longitude || data.lon || data.lng,
                    provider: 'apip.cc',
                });
            },
        },
        {
            name: 'IPinfo',
            lookup: async (ip) => {
                const data = await fetchJson(`https://ipinfo.io/${encodeURIComponent(ip)}/json`, 3500);
                const [latitude, longitude] = String(data.loc || '').split(',');
                return normalizeIpGeo({
                    ip,
                    country: data.country || '',
                    region: data.region || '',
                    city: data.city || '',
                    latitude,
                    longitude,
                    provider: 'IPinfo',
                });
            },
        },
        {
            name: 'ipwho.is',
            lookup: async (ip) => {
                const data = await fetchJson(`https://ipwho.is/${encodeURIComponent(ip)}?lang=zh-CN`, 3500);
                if (data && data.success === false) {
                    return null;
                }

                return normalizeIpGeo({
                    ip,
                    country: data.country || '',
                    region: data.region || '',
                    city: data.city || '',
                    latitude: data.latitude,
                    longitude: data.longitude,
                    provider: 'ipwho.is',
                });
            },
        },
    ],
};

const IP_GEO_FAST_WAIT_MS = 900;
const IP_GEO_MIN_EARLY_RESULTS = 3;
let cloudflareLocationProbePromise = null;

async function probeIpAddress(onUpdate = null) {
    const result = {
        type: 'ip',
        name: 'IP 探测',
        address: '无法获取',
        city: '',
    };

    let serverIp = '';

    const [serverProbeResult, cloudflareProbeResult] = await Promise.allSettled([
        api('ip_probe'),
        getCloudflareLocationProbe(),
    ]);
    if (serverProbeResult.status === 'fulfilled') {
        serverIp = serverProbeResult.value.ip || '';
    }
    if (!serverIp && cloudflareProbeResult.status === 'fulfilled' && cloudflareProbeResult.value) {
        serverIp = cloudflareProbeResult.value.ip || cloudflareProbeResult.value.ipv6 || '';
    }

    const [ipv4Candidates, ipv6Candidates, autoCandidates] = await Promise.all([
        probePublicIpCandidates(IP_PROVIDERS.address.ipv4),
        probePublicIpCandidates(IP_PROVIDERS.address.ipv6),
        probePublicIpCandidates(IP_PROVIDERS.address.auto),
    ]);
    const variants = [];
    const ipv4 = chooseIpCandidate([
        ...ipv4Candidates,
        ...autoCandidates.filter((candidate) => isIPv4Address(candidate.ip)),
    ], 'ipv4');
    const ipv6 = chooseIpCandidate([
        ...ipv6Candidates,
        ...autoCandidates.filter((candidate) => isIPv6Address(candidate.ip)),
    ], 'ipv6');

    addIpVariant(variants, 'IPv4', ipv4 ? ipv4.ip : '', ipv4);
    addIpVariant(variants, 'IPv6', ipv6 ? ipv6.ip : '', ipv6);
    addIpVariant(variants, '服务端', serverIp);

    if (!variants.length) {
        return result;
    }

    const geocoded = variants.map((variant) => ({ ...variant, geo: null }));
    const emitUpdate = () => {
        const currentResult = buildIpProbeResult(result, geocoded, serverIp);
        rememberIpProbeResult(currentResult);
        if (typeof onUpdate === 'function') {
            onUpdate(currentResult);
        }
    };
    const tasks = variants.map((variant, index) => (
        geocodeIp(variant.ip, (geo) => {
            geocoded[index] = { ...variant, geo };
            emitUpdate();
        })
            .then((geo) => {
                geocoded[index] = { ...variant, geo };
                emitUpdate();
                return geocoded[index];
            })
            .catch(() => {
                geocoded[index] = { ...variant, geo: null };
                return geocoded[index];
            })
    ));

    await firstUsefulGeocodedVariant(tasks);
    const fastResult = buildIpProbeResult(result, geocoded, serverIp);
    rememberIpProbeResult(fastResult);

    Promise.allSettled(tasks).then(() => emitUpdate());
    return fastResult;
}

function buildIpProbeResult(baseResult, geocoded, serverIp) {
    const result = {
        ...baseResult,
    };
    const preferred = choosePreferredProbeEntry(geocoded);
    const preferredGeo = preferred && preferred.geo ? preferred.geo : null;
    const displayPreferred = chooseDisplayProbeEntry(geocoded) || preferred;
    const displayPreferredGeo = displayPreferred && displayPreferred.geo ? displayPreferred.geo : preferredGeo;

    result.ip = preferred ? preferred.ip : (geocoded[0] ? geocoded[0].ip : '');
    result.server_ip = serverIp;
    const ipv4Variant = geocoded.find((variant) => variant.label === 'IPv4');
    const ipv6Variant = geocoded.find((variant) => variant.label === 'IPv6');
    result.ipv4 = ipv4Variant ? ipv4Variant.ip : '';
    result.ipv6 = ipv6Variant ? ipv6Variant.ip : '';
    result.address = formatIpProbeAddress(geocoded);
    result.city = displayPreferredGeo && displayPreferredGeo.city ? displayPreferredGeo.city : cityDisplayName(inferCityFromText(result.address));
    result.region = displayPreferredGeo ? displayPreferredGeo.region : '';
    result.country = displayPreferredGeo ? displayPreferredGeo.country : '';
    applyCoordinates(result, preferredGeo);
    result.variants = geocoded.map((variant) => {
        const coordinates = variant.geo ? sourceCoordinates(variant.geo) : null;
        return {
            label: variant.label,
            ip: variant.ip,
            address: variant.geo ? variant.geo.address : variant.ip,
            city: variant.geo ? variant.geo.city : '',
            region: variant.geo ? variant.geo.region : '',
            country: variant.geo ? variant.geo.country : '',
            latitude: coordinates ? coordinates.latitude : null,
            longitude: coordinates ? coordinates.longitude : null,
            source: variant.source || '',
            domestic_source: !!variant.domestic_source,
        };
    });

    return result;
}

function rememberIpProbeResult(result) {
    if (result && typeof window !== 'undefined') {
        window.__latestIpProbeResult = result;
    }
}

function getCloudflareLocationProbe() {
    if (!cloudflareLocationProbePromise) {
        cloudflareLocationProbePromise = api('cloudflare_location').catch(() => null);
    }

    return cloudflareLocationProbePromise;
}

function firstUsefulGeocodedVariant(promises) {
    return new Promise((resolve) => {
        if (!promises.length) {
            resolve(null);
            return;
        }

        let pending = promises.length;
        let settled = false;

        promises.forEach((promise) => {
            Promise.resolve(promise)
                .then((variant) => {
                    if (!settled && variant && variant.geo) {
                        settled = true;
                        resolve(variant);
                    }
                })
                .catch(() => {
                    // Try the other probe providers.
                })
                .finally(() => {
                    pending -= 1;
                    if (!settled && pending === 0) {
                        settled = true;
                        resolve(null);
                    }
                });
        });
    });
}

async function fetchJson(url, timeoutMs = 1500) {
    const response = await fetchWithTimeout(url, { credentials: 'omit' }, timeoutMs);
    if (!response.ok) {
        throw new Error('request failed');
    }

    return response.json();
}

function ipGeoProviders() {
    return IP_PROVIDERS.geolocation
        .map((provider) => provider.lookup)
        .filter((lookup) => typeof lookup === 'function');
}

async function serverIpGeoLookup(provider, ip) {
    const data = await api('ip_geo', {
        method: 'POST',
        body: JSON.stringify({ provider, ip }),
    });
    return normalizeIpGeo({
        ip,
        country: data.country || '',
        region: data.region || '',
        city: data.city || '',
        latitude: data.latitude,
        longitude: data.longitude,
        provider: data.provider || provider,
    });
}

function geocodeIp(ip, onBetterGeo = null) {
    const providers = ipGeoProviders();
    if (!providers.length) {
        return Promise.resolve(null);
    }

    return new Promise((resolve) => {
        const results = [];
        let pending = providers.length;
        let settled = false;
        let currentGeo = null;
        let fastTimer = null;

        const settle = (geo) => {
            if (settled) {
                return;
            }

            settled = true;
            currentGeo = geo || null;
            if (fastTimer) {
                window.clearTimeout(fastTimer);
                fastTimer = null;
            }
            resolve(currentGeo);
        };

        const publishBetter = (geo) => {
            if (!geo) {
                return;
            }

            if (!currentGeo || geoPlaceKey(geo) === geoPlaceKey(currentGeo)) {
                currentGeo = geo;
                return;
            }

            currentGeo = geo;
            if (typeof onBetterGeo === 'function') {
                onBetterGeo(geo);
            }
        };

        fastTimer = window.setTimeout(() => {
            if (settled) {
                return;
            }

            const fastGeo = chooseDetailedGeo(results)
                || choosePreferredGeo(results.filter(Boolean))
                || null;
            if (fastGeo) {
                settle(fastGeo);
            }
        }, IP_GEO_FAST_WAIT_MS);

        providers.forEach((provider) => {
            Promise.resolve(provider(ip))
                .then((geo) => {
                    if (!geo) {
                        return;
                    }

                    results.push(geo);
                    const majority = results.length >= IP_GEO_MIN_EARLY_RESULTS
                        ? chooseMajorityGeo(results)
                        : null;
                    if (majority) {
                        if (!settled) {
                            settle(majority);
                        } else {
                            publishBetter(majority);
                        }
                    }
                })
                .catch(() => {
                    // Try the other IP geolocation providers.
                })
                .finally(() => {
                    pending -= 1;
                    if (pending > 0) {
                        return;
                    }

                    const finalGeo = chooseMajorityGeo(results)
                        || chooseDetailedGeo(results)
                        || choosePreferredGeo(results.filter(Boolean))
                        || null;
                    if (!settled) {
                        settle(finalGeo);
                    } else {
                        publishBetter(finalGeo);
                    }
                });
        });
    });
}

function geocodeIpStable(ip) {
    const providers = ipGeoProviders();

    return new Promise((resolve) => {
        const results = [];
        let pending = providers.length;

        providers.forEach((provider) => {
            Promise.resolve(provider(ip))
                .then((geo) => {
                    if (geo) {
                        results.push(geo);
                    }
                })
                .catch(() => {
                    // Try the other IP geolocation providers.
                })
                .finally(() => {
                    pending -= 1;
                    if (pending > 0) {
                        return;
                    }

                    resolve(chooseDetailedGeo(results));
                });
        });
    });
}

function geocodeProbeIp(ip) {
    return geocodeIpStable(ip);
}

function chooseDetailedGeo(geos) {
    const usable = geos.filter(Boolean);
    const withCity = usable.filter((geo) => normalizeCity(geo.city || inferCityFromText(geo.address || '')));

    return chooseMajorityGeo(withCity)
        || choosePreferredGeo(withCity)
        || choosePreferredGeo(usable)
        || chooseMajorityGeo(usable)
        || null;
}

function normalizeIpGeo(geo) {
    const country = displayRegionName(geo.country || '');
    const region = displayRegionName(geo.region || '');
    const city = cityDisplayName(geo.city || inferCityFromText([country, region].join(' ')) || region);
    const parts = [country, region, city].filter(Boolean);
    const address = cleanupLocalizedAddress(parts.join(' '));

    return {
        country,
        region,
        city,
        address: address ? `${address} / ${geo.ip}` : geo.ip,
        latitude: coordinateValue(geo.latitude, -90, 90),
        longitude: coordinateValue(geo.longitude, -180, 180),
        provider: geo.provider || '',
    };
}

function choosePreferredGeo(geos) {
    return geos.find((geo) => isDomesticAddressSource(geo) && normalizeCity(geo.city))
        || geos.find((geo) => isDomesticAddressSource(geo))
        || chooseMostCommonCityEntry(geos, (geo) => geo.city)
        || geos[0]
        || null;
}

function geoPlaceKey(geo) {
    if (!geo) {
        return '';
    }

    const country = countryKeyForCompare(geo);
    const region = regionKeyForCompare(geo);
    const city = normalizeCity(geo.city || inferCityFromText(geo.address || ''));
    return [country, region, city].filter(Boolean).join('|');
}

function chooseMajorityGeo(geos) {
    const usable = geos.filter((geo) => geo && geoPlaceKey(geo));
    if (usable.length < 2) {
        return null;
    }

    const counts = new Map();
    usable.forEach((geo) => {
        const key = geoPlaceKey(geo);
        const current = counts.get(key) || { count: 0, geo };
        current.count += 1;
        if (!current.geo || isDomesticAddressSource(geo)) {
            current.geo = geo;
        }
        counts.set(key, current);
    });

    let best = null;
    counts.forEach((entry) => {
        if (!best || entry.count > best.count) {
            best = entry;
        }
    });

    return best && best.count > usable.length / 2 ? best.geo : null;
}

function fetchWithTimeout(url, options = {}, timeoutMs = 1500) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeoutMs);

    return fetch(url, {
        ...options,
        signal: controller.signal,
    }).finally(() => window.clearTimeout(timer));
}

async function probePublicIpCandidates(providers) {
    const entries = Array.isArray(providers) ? providers : [providers];
    const settled = await Promise.all(entries.map((provider) => probePublicIpCandidate(provider)));

    return settled.filter(Boolean);
}

async function probePublicIpCandidate(provider) {
    try {
        const response = await fetchWithTimeout(provider.url, { credentials: 'omit' }, provider.timeoutMs || 1600);
        if (!response.ok) {
            throw new Error('request failed');
        }

        const text = await response.text();
        const ip = extractPublicIp(text, provider);
        if (!ip) {
            return null;
        }

        return {
            ...provider,
            ip,
            domestic: provider.region === 'domestic',
        };
    } catch (error) {
        return null;
    }
}

function extractPublicIp(text, provider = {}) {
    const candidates = [];

    try {
        collectIpCandidates(JSON.parse(text), candidates);
    } catch (error) {
        // Most public-IP services return plain text.
    }

    collectIpCandidates(text, candidates);

    const publicIps = uniqueCandidates(candidates)
        .map((candidate) => String(candidate || '').trim())
        .filter((candidate) => isIpAddress(candidate) && isPublicIp(candidate));
    const familyMatches = publicIps.filter((candidate) => (
        provider.family === 'ipv4' ? isIPv4Address(candidate)
            : provider.family === 'ipv6' ? isIPv6Address(candidate)
                : true
    ));

    if (provider.family === 'ipv4' || provider.family === 'ipv6') {
        return familyMatches[0] || '';
    }

    return familyMatches[0] || publicIps[0] || '';
}

function collectIpCandidates(value, candidates) {
    if (!value) {
        return;
    }

    if (typeof value === 'string') {
        scanIpCandidates(value).forEach((candidate) => candidates.push(candidate));
        return;
    }

    if (Array.isArray(value)) {
        value.forEach((item) => collectIpCandidates(item, candidates));
        return;
    }

    if (typeof value === 'object') {
        Object.values(value).forEach((item) => collectIpCandidates(item, candidates));
    }
}

function scanIpCandidates(text) {
    const source = String(text || '');
    const ipv4 = [];
    const ipv4Pattern = /(?:^|[^\d.])((?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})(?=$|[^\d.])/g;
    let match = null;

    while ((match = ipv4Pattern.exec(source)) !== null) {
        ipv4.push(match[1]);
    }

    const ipv6 = source.match(/(?:^|[^0-9a-f:])(([0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4})(?=$|[^0-9a-f:])/ig) || [];

    return [
        ...ipv4,
        ...ipv6.map((candidate) => candidate.replace(/^[^0-9a-f]+|[^0-9a-f]+$/ig, '')),
    ];
}

function chooseIpCandidate(candidates, family) {
    const usable = candidates.filter((candidate) => (
        candidate
        && isPublicIp(candidate.ip)
        && (family === 'ipv4' ? isIPv4Address(candidate.ip) : isIPv6Address(candidate.ip))
    ));
    const domestic = usable.filter((candidate) => candidate.domestic || candidate.region === 'domestic');

    return chooseMostCommonIpCandidate(domestic)
        || chooseMostCommonIpCandidate(usable)
        || null;
}

function chooseMostCommonIpCandidate(candidates) {
    if (!candidates.length) {
        return null;
    }

    const counts = new Map();
    candidates.forEach((candidate, index) => {
        const key = candidate.ip;
        const current = counts.get(key) || {
            count: 0,
            domesticCount: 0,
            firstIndex: index,
            candidate,
        };

        current.count += 1;
        current.domesticCount += (candidate.domestic || candidate.region === 'domestic') ? 1 : 0;
        if ((candidate.domestic || candidate.region === 'domestic') && !(current.candidate.domestic || current.candidate.region === 'domestic')) {
            current.candidate = candidate;
        }
        counts.set(key, current);
    });

    return [...counts.values()]
        .sort((a, b) => b.count - a.count || b.domesticCount - a.domesticCount || a.firstIndex - b.firstIndex)[0]
        .candidate;
}

function addIpVariant(variants, label, ip, candidate = null) {
    const cleanIp = String(ip || '').trim();
    if (!isIpAddress(cleanIp)) {
        return;
    }

    const existing = variants.find((variant) => variant.ip === cleanIp);
    if (existing) {
        if (candidate && (candidate.domestic || candidate.region === 'domestic') && !existing.domestic_source) {
            existing.source = candidate.name || existing.source || '';
            existing.source_region = candidate.region || existing.source_region || '';
            existing.domestic_source = true;
        }
        return;
    }

    variants.push({
        label,
        ip: cleanIp,
        family: isIPv6Address(cleanIp) ? 'ipv6' : 'ipv4',
        source: candidate ? candidate.name || '' : '',
        source_region: candidate ? candidate.region || '' : '',
        domestic_source: !!(candidate && (candidate.domestic || candidate.region === 'domestic')),
    });
}

function formatIpProbeAddress(variants) {
    const ipv4 = variants.find((variant) => variant.label === 'IPv4');
    const ipv6 = variants.find((variant) => variant.label === 'IPv6');
    const server = variants.find((variant) => variant.label === '服务端');
    const parts = [
        `IPv4：${formatProbeVariant(ipv4)}`,
        `IPv6：${formatProbeVariant(ipv6)}`,
    ];

    if (server && (!ipv4 || server.ip !== ipv4.ip) && (!ipv6 || server.ip !== ipv6.ip)) {
        parts.push(`服务端：${formatProbeVariant(server)}`);
    }

    return parts.join(' / ');
}

function formatProbeVariant(variant) {
    if (!variant) {
        return '未检测到';
    }

    if (variant.geo && variant.geo.address) {
        return variant.geo.address;
    }

    return variant.ip;
}

function choosePreferredProbeEntry(entries) {
    return entries.find((entry) => entry.label === 'IPv4' && entry.geo)
        || entries.find((entry) => entry.label === 'IPv6' && entry.geo)
        || entries.find((entry) => entry.geo && isDomesticAddressSource(entry.geo))
        || entries.find((entry) => entry.domestic_source && entry.geo)
        || chooseMostCommonCityEntry(entries.filter((entry) => entry.geo), (entry) => entry.geo.city)
        || entries.find((entry) => entry.label === 'IPv4')
        || entries.find((entry) => entry.label === 'IPv6')
        || entries[0]
        || null;
}

function chooseDisplayProbeEntry(entries) {
    return entries.find((entry) => entry.label === 'IPv6' && entry.geo && normalizeCity(entry.geo.city || inferCityFromText(entry.geo.address || '')))
        || entries.find((entry) => entry.label === 'IPv6' && entry.geo)
        || choosePreferredProbeEntry(entries);
}

function uniqueCandidates(values) {
    return [...new Set(values.filter(Boolean))];
}

function isIpAddress(value) {
    return isIPv4Address(value) || isIPv6Address(value);
}

function isIPv4Address(value) {
    const parts = String(value || '').split('.').map((part) => Number(part));
    return parts.length === 4 && parts.every((part) => Number.isInteger(part) && part >= 0 && part <= 255);
}

function isIPv6Address(value) {
    const text = String(value || '').trim();
    return text.includes(':') && /^[0-9a-f:]+$/i.test(text);
}

function isPublicIp(ip) {
    if (isIPv6Address(ip)) {
        const value = String(ip).toLowerCase();
        return !(value === '::'
            || value === '::1'
            || value.startsWith('fe80:')
            || value.startsWith('fc')
            || value.startsWith('fd')
            || value.startsWith('ff'));
    }

    const parts = String(ip).split('.').map((part) => Number(part));
    if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
        return false;
    }

    const [a, b, c] = parts;
    return !(a === 0
        || a === 10
        || a === 127
        || (a === 100 && b >= 64 && b <= 127)
        || (a === 172 && b >= 16 && b <= 31)
        || (a === 192 && b === 0)
        || (a === 192 && b === 0 && c === 2)
        || (a === 192 && b === 168)
        || (a === 169 && b === 254)
        || (a === 198 && (b === 18 || b === 19))
        || (a === 198 && b === 51 && c === 100)
        || (a === 203 && b === 0 && c === 113)
        || a === 255
        || a >= 224);
}
