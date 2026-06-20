<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

$servicePath = defined('AMAP_SERVICE_PROXY_PATH') ? trim((string) AMAP_SERVICE_PROXY_PATH) : '/_AMapService';
$safeServicePath = json_encode($servicePath, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store, no-transform');

echo str_replace('__AMAP_SERVICE_PATH__', $safeServicePath, <<<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>历史轨迹</title>
  <style>
    html, body, #map { margin: 0; width: 100%; height: 100%; overflow: hidden; background: #eef3f1; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #19342f; }
    #map { position: relative; touch-action: none; }
    .tile, .path, .marker, .empty, .controls, .info { position: absolute; }
    .tile { width: 256px; height: 256px; user-select: none; -webkit-user-drag: none; opacity: 0; transition: opacity .18s ease; }
    .tile.loaded { opacity: 1; }
    .path { inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
    .marker { min-width: 24px; height: 24px; padding: 0 6px; border-radius: 999px; background: var(--color, #0d5f54); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 800; box-shadow: 0 4px 12px rgba(0,0,0,.24); border: 2px solid #fff; transform: translate(-50%, -50%); z-index: 20; }
    .marker.latest { width: 30px; height: 30px; box-shadow: 0 0 0 6px rgba(13,95,84,.16), 0 5px 16px rgba(0,0,0,.28); }
    .empty { inset: 0; display: grid; place-items: center; color: #5c6f6a; font-size: 14px; text-align: center; padding: 18px; box-sizing: border-box; z-index: 30; }
    .controls { left: 10px; top: 10px; display: grid; gap: 6px; z-index: 40; }
    .controls button { width: 32px; height: 32px; border: 0; border-radius: 8px; background: rgba(255,255,255,.96); color: #173b35; font-size: 20px; font-weight: 800; box-shadow: 0 2px 8px rgba(0,0,0,.16); }
    .info { left: 12px; right: 12px; bottom: 12px; padding: 10px 12px; border-radius: 12px; background: rgba(255,255,255,.96); color: #19342f; font-size: 13px; line-height: 1.45; box-shadow: 0 6px 18px rgba(0,0,0,.18); z-index: 45; white-space: pre-wrap; display: none; }
    .copyright { position: absolute; right: 8px; bottom: 4px; z-index: 35; color: rgba(25,52,47,.72); background: rgba(255,255,255,.72); border-radius: 6px; padding: 2px 5px; font-size: 10px; }
  </style>
  <script data-cfasync="false">
    const AMAP_SERVICE_PATH = __AMAP_SERVICE_PATH__;
    const serviceHost = new URL(AMAP_SERVICE_PATH || '/_AMapService', window.location.origin).toString().replace(/\/$/, '');
    const tileSize = 256;
    const minZoom = 3;
    const maxZoom = 18;
    const colors = ['#0d5f54', '#1677ff', '#d97706', '#7c3aed', '#dc2626', '#0891b2', '#65a30d', '#be185d'];
    let currentRecords = [];
    let zoom = 15;
    let center = { lat: 39.904989, lng: 116.405285 };
    let dragging = null;

    function valid(record) {
      const lat = Number(record && record.latitude);
      const lng = Number(record && record.longitude);
      return Number.isFinite(lat) && Number.isFinite(lng) && lat >= -85 && lat <= 85 && lng >= -180 && lng <= 180 && !(lat === 0 && lng === 0);
    }
    function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }
    function project(lat, lng, z) {
      const sinLat = Math.sin(clamp(lat, -85.05112878, 85.05112878) * Math.PI / 180);
      const scale = tileSize * Math.pow(2, z);
      return {
        x: (lng + 180) / 360 * scale,
        y: (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale,
      };
    }
    function unproject(x, y, z) {
      const scale = tileSize * Math.pow(2, z);
      const lng = x / scale * 360 - 180;
      const n = Math.PI - 2 * Math.PI * y / scale;
      const lat = 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
      return { lat, lng };
    }
    function tileUrl(x, y, z) {
      const max = Math.pow(2, z);
      const wrappedX = ((x % max) + max) % max;
      return `${serviceHost}/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=${wrappedX}&y=${y}&z=${z}`;
    }
    function initial(record) {
      const name = String((record && (record.display_name || record.username)) || '用户').trim();
      return name.slice(0, 2) || '用户';
    }
    function userKey(record) {
      return String((record && record.user_id) || (record && record.username) || '0');
    }
    function title(record) {
      const name = String((record && (record.display_name || record.username)) || '成员');
      const time = String((record && (record.created_at || record.updated_at)) || '');
      const diag = record && record.address_diagnostics;
      const address = diag && diag.preferred_address ? `\n${diag.preferred_address}` : '';
      return `${name}\n${time}${address}`;
    }
    function grouped(records) {
      const groups = new Map();
      records.forEach((record) => {
        const key = userKey(record);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(record);
      });
      groups.forEach((items) => items.sort((a, b) => String(a.created_at || '').localeCompare(String(b.created_at || ''))));
      return Array.from(groups.values());
    }
    function fitView(records) {
      const width = Math.max(1, window.innerWidth || 320);
      const height = Math.max(1, window.innerHeight || 260);
      const lats = records.map((record) => Number(record.latitude));
      const lngs = records.map((record) => Number(record.longitude));
      const minLat = Math.min(...lats);
      const maxLat = Math.max(...lats);
      const minLng = Math.min(...lngs);
      const maxLng = Math.max(...lngs);
      center = { lat: (minLat + maxLat) / 2, lng: (minLng + maxLng) / 2 };
      if (records.length <= 1 || (Math.abs(maxLat - minLat) < 0.00001 && Math.abs(maxLng - minLng) < 0.00001)) {
        zoom = 16;
        return;
      }
      for (let z = maxZoom; z >= minZoom; z -= 1) {
        const a = project(minLat, minLng, z);
        const b = project(maxLat, maxLng, z);
        if (Math.abs(a.x - b.x) <= width - 54 && Math.abs(a.y - b.y) <= height - 54) {
          zoom = clamp(z, minZoom, maxZoom);
          return;
        }
      }
      zoom = minZoom;
    }
    function clearMap(map) {
      map.querySelectorAll('.tile,.path,.marker,.empty,.info,.controls,.copyright').forEach((node) => node.remove());
    }
    function showEmpty(message) {
      const map = document.getElementById('map');
      clearMap(map);
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = message || '暂无可显示轨迹';
      map.appendChild(empty);
    }
    function render() {
      const records = currentRecords.filter(valid);
      if (!records.length) {
        showEmpty('暂无可显示轨迹');
        return;
      }
      const map = document.getElementById('map');
      clearMap(map);
      const width = Math.max(1, map.clientWidth || window.innerWidth || 320);
      const height = Math.max(1, map.clientHeight || window.innerHeight || 260);
      const centerPx = project(center.lat, center.lng, zoom);
      const topLeft = { x: centerPx.x - width / 2, y: centerPx.y - height / 2 };
      const tileMinX = Math.floor(topLeft.x / tileSize) - 1;
      const tileMaxX = Math.floor((topLeft.x + width) / tileSize) + 1;
      const tileMinY = Math.max(0, Math.floor(topLeft.y / tileSize) - 1);
      const tileMaxY = Math.min(Math.pow(2, zoom) - 1, Math.floor((topLeft.y + height) / tileSize) + 1);
      for (let x = tileMinX; x <= tileMaxX; x += 1) {
        for (let y = tileMinY; y <= tileMaxY; y += 1) {
          const img = document.createElement('img');
          img.className = 'tile';
          img.alt = '';
          img.decoding = 'async';
          img.loading = 'eager';
          img.style.left = `${Math.round(x * tileSize - topLeft.x)}px`;
          img.style.top = `${Math.round(y * tileSize - topLeft.y)}px`;
          img.onload = () => img.classList.add('loaded');
          img.src = tileUrl(x, y, zoom);
          map.appendChild(img);
        }
      }
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('class', 'path');
      map.appendChild(svg);
      grouped(records).forEach((group, groupIndex) => {
        const color = colors[groupIndex % colors.length];
        const points = group.map((record) => {
          const px = project(Number(record.latitude), Number(record.longitude), zoom);
          return { x: px.x - topLeft.x, y: px.y - topLeft.y, record };
        });
        if (points.length > 1) {
          const line = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
          line.setAttribute('points', points.map((point) => `${point.x},${point.y}`).join(' '));
          line.setAttribute('fill', 'none');
          line.setAttribute('stroke', color);
          line.setAttribute('stroke-width', '5');
          line.setAttribute('stroke-linecap', 'round');
          line.setAttribute('stroke-linejoin', 'round');
          line.setAttribute('opacity', '0.82');
          svg.appendChild(line);
        }
        points.forEach((point, index) => {
          const marker = document.createElement('button');
          marker.type = 'button';
          marker.className = `marker${index === points.length - 1 ? ' latest' : ''}`;
          marker.style.setProperty('--color', color);
          marker.style.left = `${point.x}px`;
          marker.style.top = `${point.y}px`;
          marker.textContent = initial(point.record);
          marker.title = title(point.record);
          marker.addEventListener('click', () => showInfo(title(point.record)));
          map.appendChild(marker);
        });
      });
      const controls = document.createElement('div');
      controls.className = 'controls';
      const zoomIn = document.createElement('button');
      zoomIn.type = 'button';
      zoomIn.textContent = '+';
      zoomIn.addEventListener('click', () => { zoom = clamp(zoom + 1, minZoom, maxZoom); render(); });
      const zoomOut = document.createElement('button');
      zoomOut.type = 'button';
      zoomOut.textContent = '−';
      zoomOut.addEventListener('click', () => { zoom = clamp(zoom - 1, minZoom, maxZoom); render(); });
      controls.append(zoomIn, zoomOut);
      map.appendChild(controls);
      const info = document.createElement('div');
      info.className = 'info';
      map.appendChild(info);
      const copyright = document.createElement('div');
      copyright.className = 'copyright';
      copyright.textContent = '© 高德地图';
      map.appendChild(copyright);
    }
    function showInfo(text) {
      const info = document.querySelector('.info');
      if (!info) return;
      info.textContent = text || '';
      info.style.display = text ? 'block' : 'none';
    }
    function moveBy(dx, dy) {
      const centerPx = project(center.lat, center.lng, zoom);
      center = unproject(centerPx.x - dx, centerPx.y - dy, zoom);
      render();
    }
    function installDrag() {
      const map = document.getElementById('map');
      map.addEventListener('pointerdown', (event) => {
        if (event.target && event.target.closest && event.target.closest('button')) return;
        dragging = { id: event.pointerId, x: event.clientX, y: event.clientY };
        map.setPointerCapture(event.pointerId);
        showInfo('');
      });
      map.addEventListener('pointermove', (event) => {
        if (!dragging || dragging.id !== event.pointerId) return;
        const dx = event.clientX - dragging.x;
        const dy = event.clientY - dragging.y;
        if (Math.abs(dx) + Math.abs(dy) < 4) return;
        dragging.x = event.clientX;
        dragging.y = event.clientY;
        moveBy(dx, dy);
      });
      map.addEventListener('pointerup', () => { dragging = null; });
      map.addEventListener('pointercancel', () => { dragging = null; });
    }
    window.renderLocHistoryMap = function(records) {
      currentRecords = Array.isArray(records) ? records.filter(valid) : [];
      if (!currentRecords.length) {
        showEmpty('暂无可显示轨迹');
        return;
      }
      fitView(currentRecords);
      render();
    };
    window.addEventListener('resize', () => render());
    window.addEventListener('DOMContentLoaded', () => {
      installDrag();
      showEmpty('正在等待轨迹数据');
    });
  </script>
</head>
<body><div id="map"></div></body>
</html>
HTML);
