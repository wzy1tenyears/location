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
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>历史轨迹</title>
  <style>
    html, body, #map { margin: 0; width: 100%; height: 100%; background: #eef3f1; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    .empty { position: absolute; inset: 0; display: grid; place-items: center; color: #5c6f6a; font-size: 14px; text-align: center; padding: 18px; }
    .dot { min-width: 24px; height: 24px; padding: 0 6px; border-radius: 999px; background: var(--color, #0d5f54); color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 800; box-shadow: 0 4px 12px rgba(0,0,0,.22); border: 2px solid #fff; }
  </style>
  <script data-cfasync="false">
    const AMAP_KEY = {$safeKey};
    const AMAP_SERVICE_PATH = {$safeServicePath};
    const serviceHost = new URL(AMAP_SERVICE_PATH || '/_AMapService', window.location.origin).toString().replace(/\/$/, '');
    if (serviceHost) window._AMapSecurityConfig = { serviceHost };
    let map = null;
    let AMapReady = null;
    const colors = ['#0d5f54', '#1677ff', '#d97706', '#7c3aed', '#dc2626', '#0891b2', '#65a30d', '#be185d'];
    function loadAmap() {
      if (AMapReady) return AMapReady;
      AMapReady = new Promise((resolve, reject) => {
        if (!AMAP_KEY) {
          reject(new Error('高德 Key 未配置'));
          return;
        }
        const script = document.createElement('script');
        script.dataset.cfasync = 'false';
        script.src = `\${serviceHost}/maps?v=2.0&key=\${encodeURIComponent(AMAP_KEY)}&plugin=AMap.Scale,AMap.ToolBar`;
        script.onload = () => resolve(window.AMap);
        script.onerror = () => reject(new Error('高德地图加载失败'));
        document.head.appendChild(script);
      });
      return AMapReady;
    }
    function showEmpty(message) {
      document.body.innerHTML = '<div id="map"></div><div class="empty"></div>';
      document.querySelector('.empty').textContent = message || '暂无可显示轨迹';
    }
    function valid(record) {
      const lat = Number(record && record.latitude);
      const lng = Number(record && record.longitude);
      return Number.isFinite(lat) && Number.isFinite(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && !(lat === 0 && lng === 0);
    }
    function initial(record) {
      const name = String((record && (record.display_name || record.username)) || '位');
      return name.trim().slice(0, 2) || '位';
    }
    function userKey(record) {
      return String((record && record.user_id) || (record && record.username) || '0');
    }
    function title(record) {
      const name = String((record && (record.display_name || record.username)) || '成员');
      const time = String((record && (record.created_at || record.updated_at)) || '');
      const diag = record && record.address_diagnostics;
      const address = diag && diag.preferred_address ? `\n\${diag.preferred_address}` : '';
      return `\${name}\n\${time}\${address}`;
    }
    function grouped(records) {
      const map = new Map();
      records.forEach((record) => {
        const key = userKey(record);
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(record);
      });
      map.forEach((items) => items.sort((a, b) => String(a.created_at || '').localeCompare(String(b.created_at || ''))));
      return Array.from(map.values());
    }
    window.renderLocHistoryMap = async function(records) {
      const items = Array.isArray(records) ? records.filter(valid) : [];
      if (!items.length) {
        showEmpty('暂无可显示轨迹');
        return;
      }
      try {
        const AMap = await loadAmap();
        if (!map) {
          map = new AMap.Map('map', { zoom: 14, resizeEnable: true, viewMode: '2D' });
          map.addControl(new AMap.Scale());
          map.addControl(new AMap.ToolBar({ position: 'LT' }));
        } else {
          map.clearMap();
        }
        const overlays = [];
        grouped(items).forEach((group, groupIndex) => {
          const color = colors[groupIndex % colors.length];
          const path = group.map((record) => [Number(record.longitude), Number(record.latitude)]);
          if (path.length > 1) {
            const line = new AMap.Polyline({ path, strokeColor: color, strokeWeight: 5, strokeOpacity: 0.82, showDir: true });
            map.add(line);
            overlays.push(line);
          }
          group.forEach((record, index) => {
            const marker = new AMap.Marker({
              position: [Number(record.longitude), Number(record.latitude)],
              title: title(record),
              content: `<div class="dot" style="--color:\${color}">\${initial(record)}</div>`,
              offset: new AMap.Pixel(-14, -14),
              zIndex: 100 + index,
            });
            marker.on('click', () => {
              const info = new AMap.InfoWindow({ content: title(record).replace(/\n/g, '<br>'), offset: new AMap.Pixel(0, -18) });
              info.open(map, marker.getPosition());
            });
            map.add(marker);
            overlays.push(marker);
          });
        });
        if (overlays.length) map.setFitView(overlays, false, [24, 24, 24, 24], 16);
      } catch (error) {
        showEmpty(error && error.message ? error.message : '历史轨迹加载失败');
      }
    };
  </script>
</head>
<body><div id="map"></div></body>
</html>
HTML;
