<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

$key = defined('AMAP_JS_API_KEY') ? trim((string) AMAP_JS_API_KEY) : '';
$servicePath = defined('AMAP_SERVICE_PROXY_PATH') ? trim((string) AMAP_SERVICE_PROXY_PATH) : '/_AMapService';
$safeKey = json_encode($key, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
$safeServicePath = json_encode($servicePath, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store, no-transform');

echo <<<HTML
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>位置地图</title>
  <style>
    html, body, #map { margin: 0; width: 100%; height: 100%; overflow: hidden; background: #eef3f1; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #173b35; }
    #map { position: relative; touch-action: none; }
    .map-state { position: absolute; inset: 0; z-index: 20; display: grid; place-items: center; padding: 18px; box-sizing: border-box; text-align: center; color: #5b6f69; font-size: 13px; background: linear-gradient(180deg, rgba(244,249,247,.92), rgba(238,243,241,.76)); }
    .marker { display: inline-flex; align-items: center; justify-content: center; height: 24px; min-width: 24px; padding: 0 7px; border-radius: 999px; border: 2px solid #fff; background: var(--marker-color, #0d5f54); color: #fff; font-size: 12px; font-weight: 800; box-shadow: 0 5px 15px rgba(0,0,0,.26); white-space: nowrap; transform: translate(-50%, -50%); }
    .marker.gps.latest { height: 30px; min-width: 30px; box-shadow: 0 0 0 7px rgba(13,95,84,.16), 0 6px 18px rgba(0,0,0,.3); }
    .marker.ip { --marker-color: #d97706; }
    .marker.webrtc { --marker-color: #7c3aed; }
    .marker-label { padding: 4px 7px; border-radius: 8px; background: rgba(255,255,255,.94); color: #173b35; border: 1px solid rgba(13,95,84,.16); box-shadow: 0 4px 12px rgba(0,0,0,.16); font-size: 11px; line-height: 1.35; max-width: 210px; white-space: normal; }
    .amap-info-content { color: #173b35; font-size: 13px; line-height: 1.5; }
  </style>
  <script data-cfasync="false">
    const AMAP_KEY = {$safeKey};
    const AMAP_SERVICE_PATH = {$safeServicePath};
    const serviceHost = new URL(AMAP_SERVICE_PATH || '/_AMapService', window.location.origin).toString().replace(/\/$/, '');
    if (serviceHost) {
      window._AMapSecurityConfig = { serviceHost };
    }

    let map = null;
    let pendingRecords = null;
    let currentMarkers = [];
    let currentPolylines = [];
    let infoWindow = null;
    const colors = ['#0d5f54', '#1677ff', '#059669', '#dc2626', '#0891b2', '#be185d'];

    function state(message) {
      let node = document.querySelector('.map-state');
      if (!message) {
        if (node) node.remove();
        return;
      }
      if (!node) {
        node = document.createElement('div');
        node.className = 'map-state';
        document.body.appendChild(node);
      }
      node.textContent = message;
    }

    function validCoordinate(lat, lng) {
      return Number.isFinite(lat) && Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && !(Math.abs(lat) < 0.000001 && Math.abs(lng) < 0.000001);
    }

    function firstText() {
      for (const value of arguments) {
        if (value === null || value === undefined) continue;
        const text = String(value).trim();
        if (text) return text;
      }
      return '';
    }

    function nameOf(record) {
      return firstText(record && record.display_name, record && record.username, '成员');
    }

    function compactName(record) {
      const text = nameOf(record);
      return text.length > 2 ? text.slice(0, 2) : text;
    }

    function outOfChina(lng, lat) {
      return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    function transformLat(x, y) {
      let result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
      result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
      result += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
      result += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
      return result;
    }

    function transformLng(x, y) {
      let result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
      result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
      result += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
      result += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
      return result;
    }

    function wgs84ToGcj02(lng, lat) {
      if (outOfChina(lng, lat)) return { lng, lat };
      const semiMajorAxis = 6378245.0;
      const eccentricity = 0.00669342162296594323;
      let latitudeDelta = transformLat(lng - 105.0, lat - 35.0);
      let longitudeDelta = transformLng(lng - 105.0, lat - 35.0);
      const latitudeRadians = lat / 180.0 * Math.PI;
      let magic = Math.sin(latitudeRadians);
      magic = 1 - eccentricity * magic * magic;
      const sqrtMagic = Math.sqrt(magic);
      latitudeDelta = (latitudeDelta * 180.0) / ((semiMajorAxis * (1 - eccentricity)) / (magic * sqrtMagic) * Math.PI);
      longitudeDelta = (longitudeDelta * 180.0) / (semiMajorAxis / sqrtMagic * Math.cos(latitudeRadians) * Math.PI);
      return { lat: lat + latitudeDelta, lng: lng + longitudeDelta };
    }

    function bd09ToGcj02(lng, lat) {
      const x = lng - 0.0065;
      const y = lat - 0.006;
      const distance = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI * 3000.0 / 180.0);
      const angle = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI * 3000.0 / 180.0);
      return { lng: distance * Math.cos(angle), lat: distance * Math.sin(angle) };
    }

    function normalizedCoordinateSystem(value) {
      const text = firstText(value).toLowerCase().replace(/[^a-z0-9]/g, '');
      if (text === 'bd09' || text === 'baidu') return 'bd09';
      if (text === 'gcj02' || text === 'gcj' || text === 'amap' || text === 'gaode') return 'gcj02';
      if (text === 'wgs84' || text === 'gps') return 'wgs84';
      return '';
    }

    function firstGpsSource(diagnostics) {
      const sources = diagnostics && Array.isArray(diagnostics.sources) ? diagnostics.sources : [];
      for (const source of sources) {
        if (source && String(source.type || '').toLowerCase() === 'gps') return source;
      }
      return null;
    }

    function mapCoordinateFor(record, lng, lat, diagnostics) {
      const meta = record && record.location_meta && typeof record.location_meta === 'object' ? record.location_meta : {};
      const gpsSource = firstGpsSource(diagnostics);
      const system = normalizedCoordinateSystem(firstText(
        meta.coordinate_system,
        gpsSource && gpsSource.coordinate_system,
        diagnostics && diagnostics.preferred_coordinate_system
      )) || 'wgs84';
      if (system === 'bd09') return bd09ToGcj02(lng, lat);
      if (system === 'gcj02') return { lng, lat };
      return wgs84ToGcj02(lng, lat);
    }

    function mapCoordinateForDiagnosticSource(source, lng, lat) {
      const system = normalizedCoordinateSystem(source && source.coordinate_system) || 'wgs84';
      if (system === 'bd09') return bd09ToGcj02(lng, lat);
      if (system === 'gcj02') return { lng, lat };
      return wgs84ToGcj02(lng, lat);
    }

    function sourceLabel(type) {
      if (type === 'ip') return 'IP';
      if (type === 'webrtc') return 'WebRTC';
      return '定位';
    }

    function sourceClass(type) {
      return type === 'ip' || type === 'webrtc' ? type : 'gps';
    }

    function makeMarkerContent(type, latest, text) {
      const node = document.createElement('div');
      node.className = `marker \${sourceClass(type)}\${latest ? ' latest' : ''}`;
      node.textContent = text;
      return node;
    }

    function markerInfo(item) {
      const lines = [];
      lines.push(`\${item.name} · \${sourceLabel(item.type)}`);
      if (item.address) lines.push(`地址：\${item.address}`);
      if (item.district) lines.push(`区县：\${item.district}`);
      if (item.street) lines.push(`街道：\${item.street}`);
      if (item.detail) lines.push(`详情：\${item.detail}`);
      if (item.poi) lines.push(`POI：\${item.poi}`);
      if (item.postalCode) lines.push(`邮编：\${item.postalCode}`);
      if (item.city || item.region || item.country) lines.push([item.country, item.region, item.city].filter(Boolean).join(' '));
      if (item.provider) lines.push(`来源：\${item.provider}`);
      if (item.ip) lines.push(`IP：\${item.ip}`);
      if (item.coordinateSystem) lines.push(`坐标系：\${item.coordinateSystem}`);
      const firstReportedAt = firstText(item.firstReportedAt, item.time);
      const lastReportedAt = firstText(item.lastReportedAt, item.time, firstReportedAt);
      if (firstReportedAt || lastReportedAt) {
        const reportCount = Math.max(1, Math.floor(Number(item.reportCount) || 1));
        lines.push(`停留时间：\${firstText(firstReportedAt, lastReportedAt)} 至 \${firstText(lastReportedAt, firstReportedAt)}（\${formatStayDuration(item.stayDurationSeconds)}，\${reportCount}次上报）`);
      }
      if (Number.isFinite(item.accuracy)) lines.push(`精度：\${Math.round(item.accuracy)}m`);
      const content = document.createElement('div');
      lines.forEach((line) => {
        const row = document.createElement('div');
        row.textContent = line;
        content.appendChild(row);
      });
      return content;
    }

    function formatStayDuration(value) {
      let remaining = Math.max(0, Math.round(Number(value) || 0));
      const days = Math.floor(remaining / 86400);
      remaining %= 86400;
      const hours = Math.floor(remaining / 3600);
      remaining %= 3600;
      const minutes = Math.floor(remaining / 60);
      const seconds = remaining % 60;
      const parts = [];
      if (days) parts.push(`\${days}天`);
      if (hours) parts.push(`\${hours}小时`);
      if (minutes) parts.push(`\${minutes}分钟`);
      if (seconds || !parts.length) parts.push(`\${seconds}秒`);
      return parts.join('');
    }

    function normalizeRecord(record, index) {
      if (!record || typeof record !== 'object') return null;
      const lat = Number(record.latitude);
      const lng = Number(record.longitude);
      if (!validCoordinate(lat, lng)) return null;
      const diagnostics = record.address_diagnostics || {};
      const mapCoordinate = mapCoordinateFor(record, lng, lat, diagnostics);
      const gpsSource = firstGpsSource(diagnostics);
      const firstReportedAt = firstText(record.first_reported_at, record.created_at, record.updated_at);
      const lastReportedAt = firstText(record.last_reported_at, record.updated_at, record.created_at, firstReportedAt);
      return {
        type: 'gps',
        name: nameOf(record),
        label: compactName(record),
        lat: mapCoordinate.lat,
        lng: mapCoordinate.lng,
        address: firstText(diagnostics.preferred_address, record.address, record.location_address),
        city: firstText(diagnostics.preferred_city, record.city, gpsSource && gpsSource.city),
        region: firstText(record.region),
        country: firstText(record.country),
        provider: 'GPS',
        time: lastReportedAt,
        firstReportedAt,
        lastReportedAt,
        stayDurationSeconds: Math.max(0, Number(record.stay_duration_seconds) || 0),
        reportCount: Math.max(1, Math.floor(Number(record.report_count) || 1)),
        accuracy: Number(record.accuracy),
        userKey: firstText(record.user_id, record.username, String(index)),
        sourceIndex: 0,
      };
    }

    function diagnosticSourceAddress(source) {
      const ip = firstText(source && source.ip, source && source.server_ip, source && source.ipv4, source && source.ipv6);
      const reportedAddress = firstText(source && source.address);
      const structuredAddress = [
        source && source.country,
        source && source.region,
        source && source.city,
        source && source.district,
        source && source.street,
        source && source.detail,
        source && source.poi,
      ].map((value) => firstText(value)).filter(Boolean).join(' ');
      const address = reportedAddress && reportedAddress !== ip
        ? reportedAddress
        : firstText(structuredAddress, reportedAddress);
      const postalCode = firstText(source && source.postal_code);
      return address && postalCode && !address.includes(postalCode) ? `\${address} \${postalCode}` : address;
    }

    function diagnosticAddressPrecision(source) {
      const fields = [
        ['country', 1],
        ['region', 2],
        ['city', 4],
        ['postal_code', 8],
        ['district', 16],
        ['address', 32],
        ['street', 64],
        ['detail', 128],
        ['poi', 128],
      ];
      const ip = firstText(source && source.ip, source && source.server_ip, source && source.ipv4, source && source.ipv6);
      let score = 0;
      let populated = 0;
      fields.forEach(([field, weight]) => {
        const value = firstText(source && source[field]);
        if (!value || (field === 'address' && value === ip)) return;
        score += weight;
        populated += 1;
      });
      return {
        score,
        populated,
        addressLength: firstText(source && source.address).length,
      };
    }

    function bestDiagnosticSource(source) {
      const candidates = [source];
      ['variants', 'candidates'].forEach((field) => {
        const nested = source && Array.isArray(source[field]) ? source[field] : [];
        nested.forEach((candidate) => {
          if (candidate && typeof candidate === 'object') candidates.push(candidate);
        });
      });

      let selected = null;
      let selectedRank = null;
      candidates.forEach((candidate) => {
        if (candidate.latitude === null || candidate.latitude === undefined
            || candidate.longitude === null || candidate.longitude === undefined) return;
        const latitude = Number(candidate.latitude);
        const longitude = Number(candidate.longitude);
        if (!validCoordinate(latitude, longitude)) return;
        const rank = diagnosticAddressPrecision(candidate);
        if (!selectedRank
            || rank.score > selectedRank.score
            || (rank.score === selectedRank.score && rank.populated > selectedRank.populated)
            || (rank.score === selectedRank.score && rank.populated === selectedRank.populated
                && rank.addressLength > selectedRank.addressLength)) {
          selected = candidate;
          selectedRank = rank;
        }
      });
      return selected;
    }

    function normalizeDiagnosticSource(record, source, sourceIndex, recordIndex) {
      if (!source || typeof source !== 'object') return null;
      const type = firstText(source.type).toLowerCase();
      if (type !== 'ip' && type !== 'webrtc') return null;
      const selected = bestDiagnosticSource(source);
      if (!selected) return null;
      const lat = Number(selected.latitude);
      const lng = Number(selected.longitude);
      const mapCoordinate = mapCoordinateForDiagnosticSource(selected, lng, lat);
      const firstReportedAt = firstText(record && record.first_reported_at, record && record.created_at, record && record.updated_at);
      const lastReportedAt = firstText(record && record.last_reported_at, record && record.updated_at, record && record.created_at, firstReportedAt);
      return {
        type,
        name: nameOf(record),
        label: sourceLabel(type),
        lat: mapCoordinate.lat,
        lng: mapCoordinate.lng,
        address: diagnosticSourceAddress(selected),
        district: firstText(selected.district),
        street: firstText(selected.street),
        detail: firstText(selected.detail),
        poi: firstText(selected.poi),
        postalCode: firstText(selected.postal_code),
        city: firstText(selected.city),
        region: firstText(selected.region),
        country: firstText(selected.country),
        provider: firstText(selected.provider, selected.name, selected.source),
        ip: firstText(selected.ip, selected.server_ip, selected.ipv4, selected.ipv6),
        coordinateSystem: firstText(selected.coordinate_system, 'wgs84'),
        time: lastReportedAt,
        firstReportedAt,
        lastReportedAt,
        stayDurationSeconds: Math.max(0, Number(record && record.stay_duration_seconds) || 0),
        reportCount: Math.max(1, Math.floor(Number(record && record.report_count) || 1)),
        accuracy: Number(selected.accuracy),
        userKey: firstText(record && record.user_id, record && record.username, String(recordIndex)),
        sourceIndex: sourceIndex + 1,
      };
    }

    function expandRecords(records) {
      const items = [];
      const seen = new Set();
      (Array.isArray(records) ? records : []).forEach((record, index) => {
        const gps = normalizeRecord(record, index);
        if (gps) items.push(gps);
        const sources = record && record.address_diagnostics && Array.isArray(record.address_diagnostics.sources)
          ? record.address_diagnostics.sources
          : [];
        sources.forEach((source, sourceIndex) => {
          const item = normalizeDiagnosticSource(record, source, sourceIndex, index);
          if (item) items.push(item);
        });
      });
      return items.filter((item) => {
        const key = [item.type, item.userKey, item.time, item.lat.toFixed(6), item.lng.toFixed(6), item.sourceIndex].join('|');
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      });
    }

    function clearMap() {
      currentMarkers.forEach((marker) => marker.setMap(null));
      currentPolylines.forEach((line) => line.setMap(null));
      currentMarkers = [];
      currentPolylines = [];
      if (infoWindow) {
        infoWindow.close();
      }
    }

    function renderRecords(records) {
      pendingRecords = records;
      if (!map || !window.AMap) return;
      clearMap();
      const items = expandRecords(records);
      if (!items.length) {
        state('暂无可显示的位置');
        return;
      }
      state('');
      infoWindow = infoWindow || new AMap.InfoWindow({ offset: new AMap.Pixel(0, -18), isCustom: false });
      const gpsByUser = new Map();
      items.forEach((item) => {
        if (item.type !== 'gps') return;
        if (!gpsByUser.has(item.userKey)) gpsByUser.set(item.userKey, []);
        gpsByUser.get(item.userKey).push(item);
      });
      Array.from(gpsByUser.values()).forEach((group, groupIndex) => {
        group.sort((left, right) => String(left.time).localeCompare(String(right.time)));
        const path = group.map((item) => [item.lng, item.lat]);
        if (path.length > 1) {
          const line = new AMap.Polyline({
            path,
            strokeColor: colors[groupIndex % colors.length],
            strokeOpacity: 0.82,
            strokeWeight: 5,
            strokeStyle: 'solid',
            lineJoin: 'round',
            lineCap: 'round',
          });
          line.setMap(map);
          currentPolylines.push(line);
        }
      });
      items.forEach((item) => {
        const group = gpsByUser.get(item.userKey) || [];
        const latest = item.type === 'gps' && group[group.length - 1] === item;
        const marker = new AMap.Marker({
          position: [item.lng, item.lat],
          content: makeMarkerContent(item.type, latest, item.label),
          offset: new AMap.Pixel(0, 0),
          zIndex: item.type === 'gps' ? (latest ? 120 : 100) : 150,
        });
        marker.on('click', () => {
          infoWindow.setContent(markerInfo(item));
          infoWindow.open(map, marker.getPosition());
        });
        marker.setMap(map);
        currentMarkers.push(marker);
      });
      if (currentMarkers.length === 1) {
        map.setZoomAndCenter(16, currentMarkers[0].getPosition());
      } else {
        map.setFitView(currentMarkers, false, [34, 28, 34, 28], 17);
      }
    }

    function initMap() {
      if (!window.AMap) {
        state('高德地图脚本加载失败');
        return;
      }
      map = new AMap.Map('map', {
        zoom: 15,
        resizeEnable: true,
        viewMode: '2D',
        jogEnable: true,
        dragEnable: true,
        zoomEnable: true,
        doubleClickZoom: true,
      });
      map.on('complete', () => renderRecords(pendingRecords || []));
      if (pendingRecords) {
        renderRecords(pendingRecords);
      }
    }

    window.renderLocHistoryMap = function(records) {
      renderRecords(Array.isArray(records) ? records : []);
    };

    window.addEventListener('DOMContentLoaded', () => {
      state('正在加载高德地图…');
      if (!AMAP_KEY) {
        state('地图密钥未配置');
        return;
      }
      const script = document.createElement('script');
      script.src = `\${serviceHost}/maps?v=2.0&key=\${encodeURIComponent(AMAP_KEY)}`;
      script.async = true;
      script.onerror = () => state('高德地图脚本加载失败，请检查服务器反代规则');
      script.onload = initMap;
      document.head.appendChild(script);
    });
  </script>
</head>
<body><div id="map"></div></body>
</html>
HTML;
