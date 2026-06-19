<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_app_user_agent();

header('Content-Type: text/html; charset=utf-8');

echo <<<'HTML'
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WebRTC 探测</title>
  <script>
    const STUN_SERVERS = [
      { urls: 'stun:stun.chat.bilibili.com:3478', label: 'Bilibili', scope: 'cn' },
      { urls: 'stun:stun.hitv.com:3478', label: '芒果 TV', scope: 'cn' },
      { urls: 'stun:stun.miwifi.com:3478', label: '小米 WiFi', scope: 'cn' },
      { urls: 'stun:stun.l.google.com:19302', label: 'Google', scope: 'global' },
      { urls: 'stun:stun.cloudflare.com:3478', label: 'Cloudflare', scope: 'global' },
      { urls: 'stun:global.stun.twilio.com:3478', label: 'Twilio', scope: 'global' },
      { urls: 'stun:stun.nextcloud.com:3478', label: 'NextCloud', scope: 'global' },
      { urls: 'stun:stun.voip.blackberry.com:3478', label: 'BlackBerry', scope: 'global' },
      { urls: 'stun:stun.freeswitch.org:3478', label: 'FreeSWITCH', scope: 'global' },
    ];
    function send(payload) {
      if (window.locApp && typeof window.locApp.onWebRtcProbe === 'function') {
        window.locApp.onWebRtcProbe(JSON.stringify(payload));
      }
    }
    function isIPv4(value) {
      return /^(?:\d{1,3}\.){3}\d{1,3}$/.test(String(value || ''));
    }
    function isIPv6(value) {
      return /^[0-9a-f:]+$/i.test(String(value || '')) && String(value || '').includes(':');
    }
    function isWebRtcAddress(value) {
      return isIPv4(value) || isIPv6(value) || /^[a-z0-9-]+\.local$/i.test(String(value || ''));
    }
    function parseCandidate(candidateText) {
      const candidates = [];
      String(candidateText || '').split(/\r?\n/).forEach((line) => {
        const candidateLine = line.replace(/^a=/, '').trim();
        if (!candidateLine.includes('candidate:')) return;
        const parts = candidateLine.split(/\s+/);
        const typeIndex = parts.indexOf('typ');
        const candidateType = typeIndex >= 0 && parts[typeIndex + 1] ? parts[typeIndex + 1] : '';
        const values = [];
        if (isWebRtcAddress(parts[4])) values.push(parts[4]);
        parts.forEach((part) => { if (isWebRtcAddress(part)) values.push(part); });
        Array.from(new Set(values)).forEach((ip) => candidates.push({ ip, candidate_type: candidateType }));
      });
      return candidates;
    }
    function probeServer(server) {
      return new Promise((resolve) => {
        const PeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
        if (!PeerConnection) {
          resolve([]);
          return;
        }
        let pc = null;
        let settled = false;
        const found = [];
        const seen = new Set();
        const finish = () => {
          if (settled) return;
          settled = true;
          try { if (pc) pc.close(); } catch (error) {}
          resolve(found);
        };
        const add = (text) => {
          parseCandidate(text).forEach((item) => {
            const key = `${item.ip}|${server.urls}|${item.candidate_type}`;
            if (seen.has(key)) return;
            seen.add(key);
            found.push({
              ip: item.ip,
              candidate_type: item.candidate_type,
              stun_server: server.urls,
              stun_label: server.label,
              stun_scope: server.scope,
            });
          });
        };
        try {
          pc = new PeerConnection({ iceServers: [{ urls: server.urls }] });
          pc.createDataChannel('loc');
          pc.onicecandidate = (event) => {
            if (event && event.candidate && event.candidate.candidate) add(event.candidate.candidate);
          };
          pc.createOffer()
            .then((offer) => {
              add(offer.sdp || '');
              return pc.setLocalDescription(offer);
            })
            .catch(finish);
          window.setTimeout(finish, 4200);
        } catch (error) {
          finish();
        }
      });
    }
    function publicCandidate(candidate) {
      const ip = String(candidate && candidate.ip || '');
      if (!ip || ip.endsWith('.local')) return false;
      if (isIPv4(ip)) {
        const parts = ip.split('.').map(Number);
        return !(parts[0] === 10 || parts[0] === 127 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168) || (parts[0] === 169 && parts[1] === 254));
      }
      if (isIPv6(ip)) {
        const lower = ip.toLowerCase();
        return !(lower === '::1' || lower.startsWith('fe80:') || lower.startsWith('fc') || lower.startsWith('fd'));
      }
      return false;
    }
    function choose(candidates) {
      const publicItems = candidates.filter(publicCandidate);
      const ipv4 = publicItems.filter((item) => isIPv4(item.ip));
      const ipv6 = publicItems.filter((item) => isIPv6(item.ip));
      const scoped = (items) => items.find((item) => item.stun_scope === 'cn' && item.candidate_type === 'srflx')
        || items.find((item) => item.stun_scope === 'cn')
        || items.find((item) => item.candidate_type === 'srflx')
        || items[0]
        || null;
      return scoped(ipv4) || scoped(ipv6) || scoped(publicItems) || null;
    }
    async function start() {
      try {
        const groups = await Promise.all(STUN_SERVERS.map((server) => probeServer(server).catch(() => [])));
        const seen = new Set();
        const candidates = [];
        groups.flat().forEach((item) => {
          const key = `${item.ip}|${item.stun_server}|${item.candidate_type}`;
          if (!item.ip || seen.has(key)) return;
          seen.add(key);
          candidates.push(item);
        });
        send({ ok: true, selected: choose(candidates), candidates: candidates.slice(0, 12) });
      } catch (error) {
        send({ ok: false, message: String(error && error.message || error || 'WebRTC 探测失败'), candidates: [] });
      }
    }
    window.addEventListener('load', start);
  </script>
</head>
<body>WebRTC 探测中…</body>
</html>
HTML;
