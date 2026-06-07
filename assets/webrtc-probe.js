const WEBRTC_STUN_SERVERS = [
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

async function probeWebRtcAddress() {
    const result = {
        type: 'webrtc',
        name: 'WebRTC 探测',
        address: '无法获取',
        city: '',
    };

    const candidates = await discoverWebRtcCandidates();
    const selected = chooseWebRtcCandidate(candidates);
    if (!selected) {
        return result;
    }

    const ip = selected.ip;
    result.ip = ip;
    result.provider = selected.serverLabel || '';
    result.source = selected.serverUrl || '';
    result.stun_server = selected.serverUrl || '';
    result.stun_label = selected.serverLabel || '';
    result.stun_scope = selected.serverScope || '';
    result.candidate_type = selected.candidateType || '';
    result.candidates = candidates
        .filter((candidate) => isPublicIp(candidate.ip))
        .slice(0, 12)
        .map((candidate) => ({
            ip: candidate.ip,
            candidate_type: candidate.candidateType || '',
            stun_server: candidate.serverUrl || '',
            stun_label: candidate.serverLabel || '',
            stun_scope: candidate.serverScope || '',
        }));

    if (typeof reuseIpProbeResultForWebRtc === 'function') {
        const reused = reuseIpProbeResultForWebRtc(result);
        if (reused && reused.reused_ip_probe) {
            return reused;
        }
    }

    if (!isPublicIp(ip)) {
        result.address = ip.endsWith('.local') ? `${ip}（mDNS 已隐藏）` : `${ip}（局域网）`;
        return result;
    }

    const geo = typeof geocodeProbeIp === 'function'
        ? await geocodeProbeIp(ip)
        : null;
    const publicIps = uniqueCandidates(candidates
        .filter((candidate) => isPublicIp(candidate.ip))
        .map((candidate) => candidate.ip));
    const otherIps = publicIps.filter((candidateIp) => candidateIp !== ip);
    result.address = geo ? geo.address : ip;
    if (otherIps.length) {
        result.address += ` / ${otherIps.slice(0, 4).join(', ')}`;
    }
    result.city = geo && geo.city ? geo.city : cityDisplayName(inferCityFromText(result.address));
    result.region = geo ? geo.region : '';
    result.country = geo ? geo.country : '';
    applyCoordinates(result, geo);
    return result;
}

async function discoverWebRtcCandidates() {
    const groups = await Promise.all(WEBRTC_STUN_SERVERS.map((server, index) => (
        probeWebRtcServer(server, index).catch(() => [])
    )));
    const candidates = [];
    const seen = new Set();

    groups.forEach((group) => {
        group.forEach((candidate) => {
            const key = `${candidate.ip}|${candidate.serverUrl}|${candidate.candidateType}`;
            if (!candidate.ip || seen.has(key)) {
                return;
            }

            seen.add(key);
            candidates.push(candidate);
        });
    });

    return candidates;
}

function probeWebRtcServer(server, serverIndex) {
    return new Promise((resolve) => {
        const PeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
        if (!PeerConnection) {
            resolve([]);
            return;
        }

        const found = [];
        const seen = new Set();
        let settled = false;
        let pc = null;

        try {
            pc = new PeerConnection({
                iceServers: [{ urls: server.urls }],
            });
        } catch (error) {
            resolve([]);
            return;
        }

        const addCandidate = (candidate) => {
            parseWebRtcCandidate(candidate).forEach((item) => {
                const key = `${item.ip}|${item.candidateType}`;
                if (seen.has(key)) {
                    return;
                }

                seen.add(key);
                found.push({
                    ...item,
                    serverUrl: server.urls,
                    serverLabel: server.label,
                    serverScope: server.scope,
                    serverIndex,
                });
            });
        };

        const finish = () => {
            if (settled) {
                return;
            }

            settled = true;
            try {
                if (pc.localDescription && pc.localDescription.sdp) {
                    addCandidate(pc.localDescription.sdp);
                }
                pc.close();
            } catch (error) {
                // Ignore close errors.
            }

            resolve(found);
        };

        const timer = window.setTimeout(finish, 4200);

        pc.onicecandidate = (event) => {
            if (event.candidate && event.candidate.candidate) {
                addCandidate(event.candidate.candidate);
            }

            if (!event.candidate) {
                window.clearTimeout(timer);
                finish();
            }
        };

        try {
            pc.createDataChannel('probe');
            pc.createOffer()
                .then((offer) => pc.setLocalDescription(offer))
                .catch(() => {
                    window.clearTimeout(timer);
                    finish();
                });
        } catch (error) {
            window.clearTimeout(timer);
            finish();
        }
    });
}

function parseWebRtcCandidate(candidateText) {
    const candidates = [];

    String(candidateText || '').split(/\r?\n/).forEach((line) => {
        const candidateLine = line.replace(/^a=/, '').trim();
        if (!candidateLine.includes('candidate:')) {
            return;
        }

        const parts = candidateLine.split(/\s+/);
        const typeIndex = parts.indexOf('typ');
        const candidateType = typeIndex >= 0 && parts[typeIndex + 1] ? parts[typeIndex + 1] : '';
        const address = parts[4] || '';
        const values = [];

        if (isWebRtcAddress(address)) {
            values.push({ ip: address, candidateType });
        }

        if (!values.length) {
            parts.forEach((part) => {
                const clean = part.trim();
                if (isWebRtcAddress(clean)) {
                    values.push({ ip: clean, candidateType });
                }
            });
        }

        const seen = new Set();
        values.forEach((item) => {
            const key = `${item.ip}|${item.candidateType}`;
            if (seen.has(key)) {
                return;
            }

            seen.add(key);
            candidates.push(item);
        });
    });

    return candidates;
}

function isWebRtcAddress(value) {
    return isIPv4Address(value)
        || isIPv6Address(value)
        || /^[a-z0-9-]+\.local$/i.test(String(value || ''));
}

function chooseWebRtcCandidate(candidates) {
    const publicCandidates = candidates.filter((candidate) => isPublicIp(candidate.ip));
    const ipv4Candidates = publicCandidates.filter((candidate) => isIPv4Address(candidate.ip));
    const ipv6Candidates = publicCandidates.filter((candidate) => isIPv6Address(candidate.ip));

    return chooseScopedWebRtcCandidate(ipv4Candidates)
        || chooseScopedWebRtcCandidate(ipv6Candidates)
        || chooseScopedWebRtcCandidate(publicCandidates)
        || null;
}

function chooseScopedWebRtcCandidate(candidates) {
    return candidates.find((candidate) => candidate.serverScope === 'cn' && candidate.candidateType === 'srflx')
        || candidates.find((candidate) => candidate.serverScope === 'cn')
        || candidates.find((candidate) => candidate.candidateType === 'srflx')
        || candidates[0]
        || null;
}
