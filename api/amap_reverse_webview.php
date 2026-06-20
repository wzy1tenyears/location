<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

function amap_query_float(string $key): ?float
{
    $value = $_GET[$key] ?? null;
    if (is_array($value)) {
        return null;
    }

    $value = trim((string) $value);
    if ($value === '' || !is_numeric($value)) {
        return null;
    }

    return (float) $value;
}

$latitude = amap_query_float('lat');
$longitude = amap_query_float('lng');
if ($latitude === null || $longitude === null || $latitude < -90 || $latitude > 90 || $longitude < -180 || $longitude > 180) {
    http_response_code(422);
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><meta charset="utf-8"><script>if(window.locApp){locApp.onAmapReverseError("invalid coordinate")}</script>';
    exit;
}

$key = defined('AMAP_JS_API_KEY') ? trim((string) AMAP_JS_API_KEY) : '';
$servicePath = defined('AMAP_SERVICE_PROXY_PATH') ? trim((string) AMAP_SERVICE_PROXY_PATH) : '/_AMapService';
$safeKey = json_encode($key, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
$safeServicePath = json_encode($servicePath, JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
$safeLatitude = json_encode($latitude, JSON_UNESCAPED_SLASHES);
$safeLongitude = json_encode($longitude, JSON_UNESCAPED_SLASHES);

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store, no-transform');

echo <<<HTML
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>高德逆地理</title>
  <script data-cfasync="false">
    const AMAP_KEY = {$safeKey};
    const AMAP_SERVICE_PATH = {$safeServicePath};
    const RAW_LAT = Number({$safeLatitude});
    const RAW_LNG = Number({$safeLongitude});
    const serviceHost = new URL(AMAP_SERVICE_PATH || '/_AMapService', window.location.origin).toString().replace(/\/$/, '');
    if (serviceHost) {
      window._AMapSecurityConfig = { serviceHost };
    }
    function sendResult(payload) {
      if (window.locApp && typeof window.locApp.onAmapReverse === 'function') {
        window.locApp.onAmapReverse(JSON.stringify(payload));
      }
    }
    function sendError(message) {
      if (window.locApp && typeof window.locApp.onAmapReverseError === 'function') {
        window.locApp.onAmapReverseError(String(message || '高德逆地理失败'));
      }
    }
    function firstText() {
      for (const value of arguments) {
        if (Array.isArray(value)) {
          continue;
        }
        if (value && typeof value === 'object') {
          const nested = firstText(value.name, value.number ? `\${value.street || ''}\${value.number}` : '', value.street, value.address);
          if (nested) return nested;
          continue;
        }
        const text = String(value || '').trim();
        if (text && text !== '0') return text;
      }
      return '';
    }
    function uniqueParts(parts) {
      const selected = [];
      for (const part of parts) {
        const text = String(part || '').trim();
        const key = text.replace(/\s+/g, '');
        if (!text || key === '0') continue;
        if (selected.some((item) => item.replace(/\s+/g, '').includes(key))) continue;
        for (let index = selected.length - 1; index >= 0; index -= 1) {
          if (key.includes(selected[index].replace(/\s+/g, ''))) selected.splice(index, 1);
        }
        selected.push(text);
      }
      return selected;
    }
    function wgs84ToGcj02(lng, lat) {
      const a = 6378245.0;
      const ee = 0.00669342162296594323;
      if (lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271) return { lng, lat };
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
      let dLat = transformLat(lng - 105.0, lat - 35.0);
      let dLng = transformLng(lng - 105.0, lat - 35.0);
      const radLat = lat / 180.0 * Math.PI;
      let magic = Math.sin(radLat);
      magic = 1 - ee * magic * magic;
      const sqrtMagic = Math.sqrt(magic);
      dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI);
      dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI);
      return { lat: lat + dLat, lng: lng + dLng };
    }
    function normalize(regeo) {
      const comp = regeo && regeo.addressComponent ? regeo.addressComponent : {};
      const formatted = firstText(regeo && (regeo.formattedAddress || regeo.formatted_address));
      const country = firstText(comp.country, '中国');
      const province = firstText(comp.province);
      const city = firstText(comp.city, comp.district, province);
      const district = firstText(comp.district);
      const township = firstText(comp.township, comp.town);
      const streetNumber = firstText(comp.streetNumber);
      const street = firstText(township, comp.street, comp.road);
      const detailParts = uniqueParts([streetNumber, comp.neighborhood, comp.building]);
      const structured = uniqueParts([country, province, city, district, street, ...detailParts]).join('');
      const address = formatted ? (country && !formatted.startsWith(country) ? country + formatted : formatted) : structured;
      return {
        type: 'gps',
        name: '定位地址',
        provider: '高德',
        source: 'amap',
        domestic_source: true,
        address,
        city,
        region: province,
        country,
        district,
        street,
        detail: detailParts.join(''),
        latitude: RAW_LAT,
        longitude: RAW_LNG,
      };
    }
    function start() {
      if (!AMAP_KEY) {
        sendError('amap key missing');
        return;
      }
      const script = document.createElement('script');
      script.dataset.cfasync = 'false';
      script.src = `https://webapi.amap.com/maps?v=2.0&key=\${encodeURIComponent(AMAP_KEY)}&plugin=AMap.Geocoder`;
      script.onerror = () => sendError('amap script failed');
      script.onload = () => {
        try {
          const converted = wgs84ToGcj02(RAW_LNG, RAW_LAT);
          const geocoder = new AMap.Geocoder({ radius: 1000, extensions: 'all', lang: 'zh_cn' });
          geocoder.getAddress([converted.lng, converted.lat], (status, result) => {
            if (status === 'complete' && result && result.regeocode) {
              sendResult(normalize(result.regeocode));
            } else {
              sendError('amap geocoder failed');
            }
          });
        } catch (error) {
          sendError(error && error.message ? error.message : error);
        }
      };
      document.head.appendChild(script);
    }
    window.addEventListener('load', start);
  </script>
</head>
<body>高德逆地理解析中…</body>
</html>
HTML;
