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
      if (item.address) lines.push(item.address);
      if (item.city || item.region || item.country) lines.push([item.country, item.region, item.city].filter(Boolean).join(' '));
      if (item.provider) lines.push(`来源：\${item.provider}`);
      if (item.ip) lines.push(`IP：\${item.ip}`);
      if (item.time) lines.push(`时间：\${item.time}`);
      if (Number.isFinite(item.accuracy)) lines.push(`精度：\${Math.round(item.accuracy)}m`);
      return lines.join('<br>');
    }

    function normalizeRecord(record, index) {
      if (!record || typeof record !== 'object') return null;
      const lat = Number(record.latitude);
      const lng = Number(record.longitude);
      if (!validCoordinate(lat, lng)) return null;
      const diagnostics = record.address_diagnostics || {};
      return {
        type: 'gps',
        name: nameOf(record),
        label: compactName(record),
        lat,
        lng,
        address: firstText(diagnostics.preferred_address, record.address, record.location_address),
        city: firstText(record.city),
        region: firstText(record.region),
        country: firstText(record.country),
        provider: 'GPS',
        time: firstText(record.created_at, record.updated_at),
        accuracy: Number(record.accuracy),
        userKey: firstText(record.user_id, record.username, String(index)),
        sourceIndex: 0,
      };
    }

    function normalizeSource(record, source, sourceIndex) {
      if (!source || typeof source !== 'object') return null;
      const type = String(source.type || '').toLowerCase();
      if (type !== 'ip' && type !== 'webrtc') return null;
      const lat = Number(source.latitude);
      const lng = Number(source.longitude);
      if (!validCoordinate(lat, lng)) return null;
      return {
        type,
        name: nameOf(record),
        label: sourceLabel(type),
        lat,
        lng,
        address: firstText(source.address, source.detail, source.ip),
        city: firstText(source.city),
        region: firstText(source.region),
        country: firstText(source.country),
        provider: firstText(source.provider, source.name, source.source),
        ip: firstText(source.ip),
        time: firstText(record && record.created_at, record && record.updated_at),
        accuracy: Number.NaN,
        userKey: firstText(record && record.user_id, record && record.username, source.ip, type),
        sourceIndex,
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
          const item = normalizeSource(record, source, sourceIndex + 1);
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
