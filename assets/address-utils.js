const { CITY_ALIASES = {}, REGION_ALIASES = {} } = window.GEO_ALIASES || {};

function coordinateValue(value, min, max) {
    if (value === null || value === undefined || value === '') {
        return null;
    }

    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric < min || numeric > max) {
        return null;
    }

    return numeric;
}

function sourceCoordinates(source) {
    if (!source) {
        return null;
    }

    const latitude = coordinateValue(source.latitude, -90, 90);
    const longitude = coordinateValue(source.longitude, -180, 180);
    if (latitude === null || longitude === null) {
        return null;
    }

    return { latitude, longitude };
}

function applyCoordinates(target, source) {
    const coordinates = sourceCoordinates(source);
    if (!target || !coordinates) {
        return target;
    }

    target.latitude = coordinates.latitude;
    target.longitude = coordinates.longitude;
    return target;
}

function inferCityFromText(text) {
    const value = localizeAddressText(text);
    const patterns = [
        /([\u4e00-\u9fa5]{2,}(?:市|盟|自治州|地区))/,
        /([\u4e00-\u9fa5]{2,}(?:县|区))/,
    ];

    for (const pattern of patterns) {
        const match = value.match(pattern);
        if (match) {
            return match[1];
        }
    }

    const parts = value
        .split(/\s+/)
        .map((part) => part.trim())
        .filter(Boolean);
    return parts.find((part) => /city|prefecture|county|district/i.test(part)) || '';
}

function normalizeAliasKey(value) {
    const normalized = String(value || '')
        .replace(/市|区|县|省|特别行政区|自治州|地区/g, '')
        .replace(/['’`.\-_/;；,，:：|]/g, '')
        .replace(/\s+/g, '')
        .trim()
        .toLowerCase();

    return collapseRepeatedAliasKey(normalized);
}

function collapseRepeatedAliasKey(value) {
    const key = String(value || '');
    if (key.length < 2 || key.length % 2 !== 0) {
        return key;
    }

    const half = key.slice(0, key.length / 2);
    return half === key.slice(key.length / 2) ? half : key;
}

function cityNameForDisplay(value) {
    const name = String(value || '').trim();
    if (!name || /(?:市|盟|自治州|地区|特别行政区|自治区|省|区|县)$/.test(name)) {
        return name;
    }

    if (['香港', '澳门'].includes(name)) {
        return name;
    }

    if (/^[a-z][a-z\s.'-]*$/i.test(name)) {
        return name
            .toLowerCase()
            .replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
    }

    return /^[\u4e00-\u9fa5]+$/.test(name) ? `${name}市` : name;
}

function localizeAddressText(text) {
    const raw = String(text || '').replace(/[，,;；]/g, ' ');
    if (/[\u4e00-\u9fa5]/.test(raw) && /(?:中国|省|市|区|县|街道|镇|乡|路|街|号|栋|幢|座|小区|花园|家园|公寓|大厦|广场|中心)/.test(raw)) {
        return cleanupLocalizedAddress(raw);
    }

    let output = raw;
    const aliases = [];

    Object.entries(REGION_ALIASES).forEach(([key, value]) => {
        aliases.push([key, value]);
    });
    Object.entries(CITY_ALIASES).forEach(([key, value]) => {
        aliases.push([key, cityNameForDisplay(value)]);
    });

    aliases
        .sort((left, right) => right[0].length - left[0].length)
        .forEach(([key, value]) => {
            const isAsciiAlias = /^[\x00-\x7F]+$/.test(key);
            const pattern = isAsciiAlias
                ? new RegExp(`\\b${escapeRegExp(key)}\\b`, 'gi')
                : new RegExp(`${escapeRegExp(key)}(?!市|盟|州|地区|特别行政区|自治区|省|区|县)`, 'g');
            output = output.replace(pattern, value);
        });

    return cleanupLocalizedAddress(output);
}

function cleanupLocalizedAddress(text) {
    let output = String(text || '').replace(/\s+/g, ' ').trim();

    for (let index = 0; index < 3; index += 1) {
        output = output
            .replace(/街道县/g, '街道')
            .replace(/([\u4e00-\u9fa5]{2,})区街道/g, '$1街道')
            .replace(/(波士顿市)(?:\s*[/｜|]\s*|\s*)\1/g, '$1')
            .replace(/(普莱森顿市)(?:\s*[/｜|]\s*|\s*)\1/g, '$1')
            .replace(/(马萨诸塞州)(?:\s*[/｜|]\s*|\s*(?:麻萨诸塞州|麻省))/g, '$1')
            .replace(/(美国)(?:\s*[/｜|]\s*)\1/g, '$1')
            .replace(/([\u4e00-\u9fa5]{2,}市)(?:\s*[/｜|]\s*|\s*)\1/g, '$1')
            .replace(/([\u4e00-\u9fa5]{2,}(?:州|省|县|区))(?:\s*[/｜|]\s*|\s*)\1/g, '$1');
    }

    return output.replace(/\s+/g, ' ').trim();
}

function escapeRegExp(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function normalizeCity(city) {
    const parts = String(city || '')
        .split(/[;；,，/｜|]+/)
        .map((part) => normalizeAliasKey(part))
        .filter(Boolean);

    for (const part of parts) {
        if (CITY_ALIASES[part]) {
            return CITY_ALIASES[part];
        }
    }

    const normalized = normalizeAliasKey(city);
    return CITY_ALIASES[normalized] || normalized;
}

function cityDisplayName(city) {
    const raw = String(city || '').trim();
    const normalized = normalizeCity(raw);
    const mapped = CITY_ALIASES[normalized] || REGION_ALIASES[normalized];

    if (mapped) {
        return cityNameForDisplay(mapped);
    }

    if (/^[a-z][a-z\s.'-]*$/i.test(raw)) {
        return cityNameForDisplay(raw);
    }

    return cityNameForDisplay(normalized || '');
}

function displayRegionName(value) {
    const raw = String(value || '').trim();
    if (!raw) {
        return '';
    }

    const normalized = normalizeAliasKey(raw);
    return REGION_ALIASES[normalized]
        || cityNameForDisplay(CITY_ALIASES[normalized] || '')
        || localizeAddressText(raw);
}

function isDomesticAddressSource(source) {
    const country = displayRegionName(source.country || '');
    const address = localizeAddressText(source.address || '');
    const city = cityDisplayName(source.city || '');
    const region = displayRegionName(source.region || '');

    return country.includes('中国')
        || address.includes('中国')
        || /^(?:香港|澳门|台湾)/.test(city)
        || /^(?:香港|澳门|台湾)/.test(region)
        || /(?:省|自治区|特别行政区)$/.test(region);
}

function countryKeyForCompare(source) {
    const country = displayRegionName(source.country || '');
    if (country) {
        return normalizeAliasKey(country);
    }

    const address = localizeAddressText(source.address || '');
    if (address.includes('中国')) {
        return normalizeAliasKey('中国');
    }
    if (address.includes('美国')) {
        return normalizeAliasKey('美国');
    }
    if (address.includes('加拿大')) {
        return normalizeAliasKey('加拿大');
    }

    return '';
}

function regionKeyForCompare(source) {
    const region = displayRegionName(source.region || '');
    if (region) {
        return normalizeAliasKey(region);
    }

    const address = localizeAddressText(source.address || '');
    const countryNames = new Set(['中国', '美国', '加拿大']);
    const regions = [...new Set(Object.values(REGION_ALIASES))]
        .filter((value) => value && !countryNames.has(value))
        .sort((left, right) => right.length - left.length);
    const matched = regions.find((value) => address.includes(value));

    return matched ? normalizeAliasKey(matched) : '';
}

function hasRegionOrCountryMismatch(sources) {
    const countries = new Set(sources
        .map((source) => countryKeyForCompare(source))
        .filter(Boolean));
    if (countries.size > 1) {
        return true;
    }

    const regions = new Set(sources
        .map((source) => regionKeyForCompare(source))
        .filter(Boolean));
    return regions.size > 1;
}

function hasCityMismatch(sources) {
    const cities = sources
        .map((source) => normalizeCity(source.city || inferCityFromText(source.address || '')))
        .filter(Boolean);

    return cities.length > 1 && new Set(cities).size > 1;
}

function sourceRegionOrCountryDiffers(left, right) {
    if (!left || !right) {
        return false;
    }

    const leftCountry = countryKeyForCompare(left);
    const rightCountry = countryKeyForCompare(right);
    if (leftCountry && rightCountry && leftCountry !== rightCountry) {
        return true;
    }

    const leftRegion = regionKeyForCompare(left);
    const rightRegion = regionKeyForCompare(right);
    if (leftRegion && rightRegion && leftRegion !== rightRegion) {
        return true;
    }

    return false;
}

function mobileIpUncertain(sources) {
    const ip = sources.find((source) => source.type === 'ip');
    if (!ip) {
        return false;
    }

    const trusted = sources.filter((source) => source.type === 'gps' || source.type === 'webrtc');
    return trusted.some((source) => sourceRegionOrCountryDiffers(ip, source));
}

function ipAndWebRtcCorroborateWithinSameRegion(sources) {
    const gps = sources.find((source) => source.type === 'gps');
    const ip = sources.find((source) => source.type === 'ip');
    const webrtc = sources.find((source) => source.type === 'webrtc');
    if (!gps || !ip || !webrtc) {
        return false;
    }

    const ipCity = normalizeCity(ip.city || inferCityFromText(ip.address || ''));
    const webrtcCity = normalizeCity(webrtc.city || inferCityFromText(webrtc.address || ''));
    if (!ipCity || !webrtcCity || ipCity !== webrtcCity) {
        return false;
    }

    return !hasRegionOrCountryMismatch([gps, ip, webrtc]);
}

function chooseMostCommonCityEntry(entries, cityGetter) {
    const counts = new Map();

    entries.forEach((entry) => {
        const city = normalizeCity(cityGetter(entry));
        if (!city) {
            return;
        }

        counts.set(city, (counts.get(city) || 0) + 1);
    });

    if (!counts.size) {
        return null;
    }

    let bestCity = '';
    let bestCount = 0;
    counts.forEach((count, city) => {
        if (count > bestCount) {
            bestCity = city;
            bestCount = count;
        }
    });

    return entries.find((entry) => normalizeCity(cityGetter(entry)) === bestCity) || null;
}

function gpsMatchesProbeCity(sources) {
    const gps = sources.find((source) => source.type === 'gps');
    const gpsCity = normalizeCity(gps ? gps.city : '');
    if (!gps || !gpsCity) {
        return false;
    }

    return sources.some((source) => (
        source.type !== 'gps'
        && normalizeCity(source.city || inferCityFromText(source.address || '')) === gpsCity
    ));
}

function choosePreferredAddressSource(sources, placeMismatch = hasRegionOrCountryMismatch(sources)) {
    if (gpsMatchesProbeCity(sources)) {
        return sources.find((source) => source.type === 'gps') || sources[0] || null;
    }

    if (placeMismatch) {
        return sources.find((source) => source.type === 'webrtc')
            || sources.find((source) => source.type === 'gps')
            || sources.find((source) => source.type === 'ip')
            || sources[0]
            || null;
    }

    return sources.find((source) => source.type === 'gps')
        || sources.find((source) => source.type === 'webrtc' && isDomesticAddressSource(source))
        || sources.find((source) => source.type === 'webrtc')
        || sources.find((source) => source.type === 'ip' && isDomesticAddressSource(source))
        || sources.find((source) => source.type === 'ip')
        || sources[0]
        || null;
}

function normalizeAddressDiagnostics(diagnostics) {
    if (!diagnostics || !Array.isArray(diagnostics.sources)) {
        return diagnostics;
    }

    const sources = diagnostics.sources.map((source) => {
        const normalized = {
            ...source,
            address: localizeAddressText(source.address || ''),
            country: displayRegionName(source.country || ''),
            region: displayRegionName(source.region || ''),
            city: cityDisplayName(source.city || inferCityFromText(source.address || '') || source.region),
        };
        applyCoordinates(normalized, source);
        return normalized;
    });
    const ipUncertain = mobileIpUncertain(sources);
    const decoratedSources = sources.map((source) => (
        source.type === 'ip' && ipUncertain
            ? { ...source, mobile_network_uncertain: true }
            : source
    ));
    const trustedSources = decoratedSources.filter((source) => source.type === 'gps' || source.type === 'webrtc');
    const trustedComparableCount = trustedSources.filter((source) => (
        countryKeyForCompare(source)
        || regionKeyForCompare(source)
        || normalizeCity(source.city || inferCityFromText(source.address || ''))
    )).length;
    const placeMismatch = trustedComparableCount >= 2 && hasRegionOrCountryMismatch(trustedSources);
    const cityMismatch = trustedComparableCount >= 2
        && hasCityMismatch(trustedSources)
        && !ipAndWebRtcCorroborateWithinSameRegion(decoratedSources);
    const preferredSource = choosePreferredAddressSource(decoratedSources, placeMismatch);
    const preferredCoordinates = sourceCoordinates(preferredSource);
    const mismatch = trustedComparableCount >= 2
        ? (placeMismatch || cityMismatch)
        : false;

    return {
        ...diagnostics,
        sources: decoratedSources,
        mismatch,
        mobile_ip_uncertain: ipUncertain && !mismatch,
        preferred_place_mismatch: placeMismatch,
        preferred_source: preferredSource ? preferredSource.type : '',
        preferred_address: preferredSource ? preferredSource.address : '',
        preferred_city: preferredSource ? preferredSource.city : '',
        preferred_latitude: preferredCoordinates ? preferredCoordinates.latitude : null,
        preferred_longitude: preferredCoordinates ? preferredCoordinates.longitude : null,
    };
}

function locationHasAddressMismatch(location) {
    if (!location || !location.address_diagnostics) {
        return Boolean(location && location.address_mismatch);
    }

    return normalizeAddressDiagnostics(location.address_diagnostics).mismatch;
}

function preferredMapSource(location) {
    if (!location || !location.address_diagnostics) {
        return null;
    }

    const diagnostics = normalizeAddressDiagnostics(location.address_diagnostics);
    if (!diagnostics || !diagnostics.preferred_place_mismatch || diagnostics.preferred_source !== 'webrtc') {
        return null;
    }

    if (gpsMatchesProbeCity(diagnostics.sources)) {
        return null;
    }

    const source = diagnostics.sources.find((item) => item.type === diagnostics.preferred_source) || null;
    return source && sourceCoordinates(source) ? source : null;
}
