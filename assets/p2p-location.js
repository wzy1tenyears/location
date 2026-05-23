(() => {
    const API_BASE = 'api';
    const STORAGE_KEY = 'loc_p2p_rsa_keypair_v1';
    const statusCache = new Map();
    const groupKeyCache = new Map();

    async function api(path, body = {}) {
        const response = await fetch(`${API_BASE}/${path}.php`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/json',
                Accept: 'application/json',
            },
            body: JSON.stringify(body),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok || payload.ok === false) {
            throw new Error(payload.message || '请求失败。');
        }
        return payload;
    }

    function bytesToBase64(bytes) {
        let binary = '';
        bytes.forEach((byte) => {
            binary += String.fromCharCode(byte);
        });
        return btoa(binary);
    }

    function base64ToBytes(text) {
        const binary = atob(String(text || ''));
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index += 1) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
    }

    function cryptoAvailable() {
        return !!(window.crypto && window.crypto.subtle);
    }

    async function ensureKeyPair() {
        if (!cryptoAvailable()) {
            throw new Error('当前 WebView 不支持端到端加密。');
        }

        const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
        if (stored && stored.publicKey && stored.privateKey) {
            return stored;
        }

        const keyPair = await crypto.subtle.generateKey({
            name: 'RSA-OAEP',
            modulusLength: 2048,
            publicExponent: new Uint8Array([1, 0, 1]),
            hash: 'SHA-256',
        }, true, ['encrypt', 'decrypt']);
        const publicKey = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
        const privateKey = await crypto.subtle.exportKey('jwk', keyPair.privateKey);
        const next = { publicKey, privateKey, createdAt: new Date().toISOString() };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
        return next;
    }

    async function importPublicKey(jwk) {
        return crypto.subtle.importKey('jwk', jwk, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
    }

    async function importPrivateKey(jwk) {
        return crypto.subtle.importKey('jwk', jwk, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['decrypt']);
    }

    async function publishPublicKey(groupName) {
        const keyPair = await ensureKeyPair();
        statusCache.delete(groupName);
        return api('p2p_crypto', {
            action: 'publish_key',
            group_name: groupName,
            public_key_jwk: keyPair.publicKey,
        });
    }

    async function status(groupName, refresh = false) {
        if (!groupName) {
            return null;
        }
        if (!refresh && statusCache.has(groupName)) {
            return statusCache.get(groupName);
        }
        const payload = await api('p2p_crypto', {
            action: 'status',
            group_name: groupName,
        });
        statusCache.set(groupName, payload);
        return payload;
    }

    async function setConsent(groupName, consent) {
        await publishPublicKey(groupName);
        groupKeyCache.delete(groupName);
        const payload = await api('p2p_crypto', {
            action: 'consent',
            group_name: groupName,
            consent: !!consent,
        });
        statusCache.set(groupName, payload);
        return payload;
    }

    async function enableGroup(groupName) {
        await publishPublicKey(groupName);
        const current = await status(groupName, true);
        if (!current || !current.is_owner) {
            throw new Error('只有家庭组管理员可以开启。');
        }
        if (current.enabled) {
            throw new Error('该家庭组已经开启。');
        }
        const members = current.members || [];
        if (!members.length || members.some((member) => !member.consented || !member.public_key_jwk)) {
            throw new Error('需要组内所有成员先同意并生成密钥。');
        }

        const rawGroupKey = crypto.getRandomValues(new Uint8Array(32));
        const wrappedKeys = {};
        for (const member of members) {
            const publicKey = await importPublicKey(member.public_key_jwk);
            const wrapped = await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, publicKey, rawGroupKey);
            wrappedKeys[String(member.user_id)] = bytesToBase64(new Uint8Array(wrapped));
        }

        const keyVersion = Math.floor(Date.now() / 1000);
        const payload = await api('p2p_crypto', {
            action: 'enable_group',
            group_name: groupName,
            key_version: keyVersion,
            wrapped_keys: wrappedKeys,
        });
        statusCache.set(groupName, payload);
        groupKeyCache.delete(groupName);
        return payload;
    }

    async function groupKey(groupName) {
        if (groupKeyCache.has(groupName)) {
            return groupKeyCache.get(groupName);
        }

        const current = await status(groupName);
        if (!current || !current.enabled) {
            return null;
        }
        if (!current.wrapped_group_key) {
            throw new Error('当前设备没有该家庭组的解密密钥。');
        }

        const keyPair = await ensureKeyPair();
        const privateKey = await importPrivateKey(keyPair.privateKey);
        const raw = await crypto.subtle.decrypt(
            { name: 'RSA-OAEP' },
            privateKey,
            base64ToBytes(current.wrapped_group_key)
        );
        const aesKey = await crypto.subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
        const result = { key: aesKey, version: current.key_version };
        groupKeyCache.set(groupName, result);
        return result;
    }

    async function encryptReport(groupName, payload) {
        const current = await status(groupName);
        if (!current || !current.enabled) {
            return null;
        }
        const keyData = await groupKey(groupName);
        if (!keyData) {
            return null;
        }
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const plaintext = new TextEncoder().encode(JSON.stringify({
            ...payload,
            encrypted_at: new Date().toISOString(),
        }));
        const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, keyData.key, plaintext);
        return {
            key_version: keyData.version,
            payload: {
                v: 1,
                alg: 'AES-GCM',
                iv: bytesToBase64(iv),
                ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
            },
        };
    }

    async function decryptRecord(groupName, record) {
        if (!record || record.encryption_mode !== 'p2p-v1' || !record.encrypted_payload) {
            return record;
        }

        try {
            const encrypted = typeof record.encrypted_payload === 'string'
                ? JSON.parse(record.encrypted_payload)
                : record.encrypted_payload;
            const keyData = await groupKey(groupName || record.group_name);
            if (!keyData) {
                return { ...record, encrypted_unreadable: true };
            }
            const decrypted = await crypto.subtle.decrypt(
                { name: 'AES-GCM', iv: base64ToBytes(encrypted.iv) },
                keyData.key,
                base64ToBytes(encrypted.ciphertext)
            );
            const data = JSON.parse(new TextDecoder().decode(decrypted));
            return {
                ...record,
                ...data,
                encryption_mode: record.encryption_mode,
                encrypted_payload: record.encrypted_payload,
                p2p_decrypted: true,
            };
        } catch (error) {
            console.warn(error);
            return { ...record, encrypted_unreadable: true };
        }
    }

    async function decryptRecords(groupName, records) {
        if (!Array.isArray(records) || records.length === 0) {
            return [];
        }
        return Promise.all(records.map((record) => decryptRecord(groupName, record)));
    }

    function settingsElement(groupName, onChange) {
        const wrap = document.createElement('div');
        wrap.className = 'settings-field p2p-settings-field';

        const title = document.createElement('span');
        title.textContent = '端到端加密定位';
        const text = document.createElement('p');
        text.className = 'settings-help';
        text.textContent = '开启后新增定位只保存密文。开启前请先查看风险说明。';

        const detailButton = document.createElement('button');
        detailButton.type = 'button';
        detailButton.className = 'subtle-button';
        detailButton.textContent = '说明与风险';
        detailButton.addEventListener('click', () => {
            if (typeof window.showPopupDialog === 'function') {
                window.showPopupDialog({
                    title: '端到端加密定位说明',
                    closeText: '我知道了',
                    sections: [{
                        title: '功能说明',
                        paragraphs: [
                            '开启后，新增定位会在用户 App 内加密后上传。服务器和后台只保存密文、账号、家庭组、时间、密钥版本等必要元数据，无法直接查看明文经纬度和地址。',
                            '开启前已有历史定位不会自动加密。需要当前家庭组所有成员先同意并生成本地密钥，再由家庭组管理员开启；如果家庭组没有管理员，则组内任意成员可发起开启。',
                        ],
                    }, {
                        title: '重要风险',
                        paragraphs: [
                            '私钥仅保存在当前 App 本地。删除软件、清除应用数据、系统重置、换机但未迁移本地密钥，都可能导致已加密定位数据无法解密。',
                            '服务器没有私钥，后台也无法帮你恢复明文位置。开启前请确认组内成员理解这个后果。',
                        ],
                    }],
                });
                return;
            }
            alert('端到端加密开启后，服务器和后台无法解密新增定位。删除软件、清除应用数据或换机未迁移密钥后，已加密定位数据可能无法找回。');
        });

        const consentLabel = document.createElement('label');
        consentLabel.className = 'settings-check-field';
        const consentInput = document.createElement('input');
        consentInput.type = 'checkbox';
        const consentText = document.createElement('span');
        consentText.textContent = '我同意为当前家庭组启用端到端加密做准备';
        consentLabel.append(consentInput, consentText);

        const statusLine = document.createElement('div');
        statusLine.className = 'settings-help';

        const action = document.createElement('button');
        action.type = 'button';
        action.className = 'subtle-button';
        action.textContent = '开启端到端加密';

        async function refresh() {
            try {
                const current = await status(groupName, true);
                const mine = (current.members || []).find((member) => Number(member.user_id) === Number(window.__CURRENT_USER_ID__));
                consentInput.checked = !!(mine && mine.consented);
                action.hidden = !current.is_owner || current.enabled;
                action.disabled = current.enabled;
                const ready = (current.members || []).filter((member) => member.consented && member.has_public_key).length;
                statusLine.textContent = current.enabled
                    ? `已开启，密钥版本 ${current.key_version}`
                    : `准备状态：${ready}/${(current.members || []).length} 名成员已同意并生成密钥`;
            } catch (error) {
                statusLine.textContent = error.message;
            }
        }

        consentInput.addEventListener('change', async () => {
            consentInput.disabled = true;
            try {
                await setConsent(groupName, consentInput.checked);
                await refresh();
                if (typeof onChange === 'function') onChange();
            } catch (error) {
                consentInput.checked = !consentInput.checked;
                statusLine.textContent = error.message;
            } finally {
                consentInput.disabled = false;
            }
        });

        action.addEventListener('click', async () => {
            action.disabled = true;
            try {
                await enableGroup(groupName);
                await refresh();
                if (typeof onChange === 'function') onChange();
            } catch (error) {
                statusLine.textContent = error.message;
            } finally {
                action.disabled = false;
            }
        });

        wrap.append(title, text, detailButton, consentLabel, statusLine, action);
        window.setTimeout(refresh, 0);
        return wrap;
    }

    window.P2PLocationCrypto = {
        status,
        warmup: ensureKeyPair,
        encryptReport,
        decryptRecord,
        decryptRecords,
        settingsElement,
    };
})();
