(() => {
    const API_BASE = 'api';
    const legalState = { documents: null };
    const el = {
        loginForm: document.querySelector('#loginForm'),
        loginMessage: document.querySelector('#loginMessage'),
        username: document.querySelector('#username'),
        password: document.querySelector('#password'),
        termsAccepted: document.querySelector('#termsAccepted'),
        termsButton: document.querySelector('#termsButton'),
        privacyButton: document.querySelector('#privacyButton'),
        crossBorderAccepted: document.querySelector('#crossBorderAccepted'),
        crossBorderButton: document.querySelector('#crossBorderButton'),
        registerButton: document.querySelector('#registerButton'),
        turnstileBox: document.querySelector('#turnstileBox'),
    };

    async function api(path, options = {}) {
        const [scriptPath, query = ''] = String(path).split('?');
        const url = `${API_BASE}/${scriptPath}.php${query ? `?${query}` : ''}`;
        const response = await fetch(url, {
            credentials: 'same-origin',
            headers: {
                'Content-Type': 'application/json',
                Accept: 'application/json',
                ...(options.headers || {}),
            },
            ...options,
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok || payload.ok === false) {
            throw new Error(payload.message || '请求失败。');
        }
        return payload;
    }

    function showDocumentPopup(title, sections, options = {}) {
        if (typeof window.showPopupDialog === 'function') {
            window.showPopupDialog({
                title,
                sections,
                closeText: options.closeText || '关闭',
            });
            return;
        }
        alert(`${title}\n\n${sections.map((section) => [section.title, ...(section.paragraphs || [])].filter(Boolean).join('\n')).join('\n\n')}`);
    }

    function showSimplePopup(title, paragraphs, options = {}) {
        const list = Array.isArray(paragraphs) ? paragraphs : [paragraphs];
        showDocumentPopup(title, [{ paragraphs: list }], options);
    }

    async function loadLegalDocuments() {
        if (legalState.documents) {
            return legalState.documents;
        }
        const payload = await api('legal_documents', { method: 'GET' });
        legalState.documents = payload.documents || {};
        return legalState.documents;
    }

    async function showLegalDocument(type, fallbackTitle, options = {}) {
        try {
            const documents = await loadLegalDocuments();
            const documentData = documents[type] || {};
            showDocumentPopup(documentData.title || fallbackTitle, documentData.sections || [], options);
        } catch (error) {
            showSimplePopup('加载失败', error.message || '协议内容暂时无法加载。');
        }
    }

    async function showCombinedLegalDocuments() {
        try {
            const documents = await loadLegalDocuments();
            showDocumentPopup('用户协议与隐私条约', [
                ...((documents.user_agreement && documents.user_agreement.sections) || []),
                ...((documents.privacy_policy && documents.privacy_policy.sections) || []),
            ]);
        } catch (error) {
            showSimplePopup('加载失败', error.message || '协议内容暂时无法加载。');
        }
    }

    function browserFingerprint() {
        const screenInfo = window.screen
            ? `${window.screen.width}x${window.screen.height}x${window.screen.colorDepth}`
            : '';
        const parts = [
            navigator.userAgent || '',
            navigator.language || '',
            String(navigator.hardwareConcurrency || ''),
            String(navigator.deviceMemory || ''),
            screenInfo,
            Intl.DateTimeFormat().resolvedOptions().timeZone || '',
        ];
        let hash = 2166136261;
        Array.from(parts.join('|')).forEach((char) => {
            hash ^= char.charCodeAt(0);
            hash = Math.imul(hash, 16777619);
        });
        return `bf-${(hash >>> 0).toString(16)}`;
    }

    function sanitizeInviteCode(value) {
        return String(value || '').trim().toLowerCase().replace(/[^0-9a-z]/g, '').slice(0, 255);
    }

    function inviteCodeFromClipboardText(text) {
        const raw = String(text || '').trim();
        if (!raw) return '';
        if (/^[0-9a-zA-Z]{1,255}$/.test(raw)) {
            return sanitizeInviteCode(raw);
        }
        const labeled = raw.match(/(?:邀请码|invite(?:\s*code)?)[^\da-zA-Z]{0,12}([0-9a-zA-Z]{1,255})/i);
        return labeled ? sanitizeInviteCode(labeled[1]) : '';
    }

    function clipboardTextFromNative() {
        if (window.LocationBridge && typeof window.LocationBridge.getClipboardText === 'function') {
            try {
                return window.LocationBridge.getClipboardText() || '';
            } catch (error) {
                return '';
            }
        }
        return '';
    }

    function openRegisterPopup(options = {}) {
        const overlay = document.createElement('div');
        overlay.className = 'popup-select-overlay';

        const card = document.createElement('div');
        card.className = 'popup-select-card register-card';
        card.setAttribute('role', 'dialog');
        card.setAttribute('aria-modal', 'true');

        const heading = document.createElement('h2');
        heading.textContent = '注册账号';

        const body = document.createElement('form');
        body.className = 'register-form';

        const message = document.createElement('div');
        message.className = 'message neutral';
        message.hidden = true;

        const inputs = {};
        function addField(name, labelText, placeholder, type = 'text') {
            const label = document.createElement('label');
            const span = document.createElement('span');
            span.textContent = labelText;
            const input = document.createElement('input');
            input.name = name;
            input.type = type;
            input.placeholder = placeholder || '';
            input.autocomplete = 'off';
            label.append(span, input);
            body.append(label);
            inputs[name] = input;
            return input;
        }

        addField('invite_code', '邀请码', '输入邀请码');
        addField('username', '用户名', '至少 6 位，包含英文和数字');
        addField('password', '密码', '至少 6 位', 'password');
        addField('password_confirm', '确认密码', '再次输入密码', 'password');
        addField('display_name', '显示名称', '留空则显示用户名');
        addField('group_name', '家庭组名称', '按邀请码要求填写');
        addField('group_code', '家庭组号', '按邀请码要求填写');

        function addRegisterDocumentButton(text, documentType, title) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'text-link';
            button.textContent = text;
            button.addEventListener('click', () => showLegalDocument(documentType, title));
            return button;
        }

        function addRegisterAgreement(name, parts, checked = false) {
            const label = document.createElement('label');
            label.className = 'terms-field';
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.name = name;
            input.checked = checked;
            const span = document.createElement('span');
            parts.forEach((part) => span.append(part));
            label.append(input, span);
            body.append(label);
            return input;
        }

        const registerTermsAccepted = addRegisterAgreement('terms_accepted', [
            document.createTextNode('我已阅读并同意 '),
            addRegisterDocumentButton('用户协议', 'user_agreement', '用户协议'),
            document.createTextNode(' 和 '),
            addRegisterDocumentButton('隐私条约', 'privacy_policy', '隐私条约'),
        ], !!(el.termsAccepted && el.termsAccepted.checked));
        const registerCrossBorderAccepted = addRegisterAgreement('cross_border_transfer_accepted', [
            document.createTextNode('我已阅读并同意 '),
            addRegisterDocumentButton('用户数据跨境加密传输协议', 'cross_border_transfer', '用户数据跨境加密传输协议'),
        ], !!(el.crossBorderAccepted && el.crossBorderAccepted.checked));

        const requiresRegisterTurnstile = String(window.CF_TURNSTILE_SITE_KEY || '').trim() !== '';
        let registerTurnstileToken = '';
        let registerTurnstileWidgetId = null;
        const registerTurnstileBox = document.createElement('div');
        registerTurnstileBox.className = 'turnstile-box';
        if (requiresRegisterTurnstile) {
            body.append(registerTurnstileBox);
        }

        const actions = document.createElement('div');
        actions.className = 'popup-actions register-actions';
        const cancelButton = document.createElement('button');
        cancelButton.type = 'button';
        cancelButton.className = 'subtle-button';
        cancelButton.textContent = '关闭';
        const submitButton = document.createElement('button');
        submitButton.type = 'submit';
        submitButton.textContent = requiresRegisterTurnstile ? '等待验证' : '注册';
        submitButton.disabled = requiresRegisterTurnstile;
        actions.append(cancelButton, submitButton);

        let inviteCheck = null;
        let inviteCheckTimer = 0;

        function setMessage(text, type = 'neutral') {
            message.textContent = text;
            message.className = `message ${type}`;
            message.hidden = !text;
        }

        function setInviteFieldsVisibility(payload = {}) {
            const type = payload.invite_type || payload.type || '';
            inputs.group_name.closest('label').hidden = type !== 'create_group';
            inputs.group_code.closest('label').hidden = type !== 'join_group';
        }

        async function checkInviteCodeNow() {
            const code = sanitizeInviteCode(inputs.invite_code.value);
            inputs.invite_code.value = code;
            inviteCheck = null;
            setInviteFieldsVisibility({});
            if (!code) {
                setMessage('', 'neutral');
                return;
            }
            try {
                const payload = await api('invite_check', {
                    method: 'POST',
                    body: JSON.stringify({ code }),
                });
                inviteCheck = payload;
                setInviteFieldsVisibility(payload);
                setMessage('邀请码可用，请补充下方信息。', 'neutral');
            } catch (error) {
                setMessage(error.message, 'error');
            }
        }

        inputs.invite_code.addEventListener('input', () => {
            const value = sanitizeInviteCode(inputs.invite_code.value);
            inputs.invite_code.value = value;
            window.clearTimeout(inviteCheckTimer);
            if (value !== '') {
                inviteCheckTimer = window.setTimeout(checkInviteCodeNow, 420);
            } else {
                setMessage('', 'neutral');
                setInviteFieldsVisibility({});
            }
        });
        inputs.invite_code.addEventListener('blur', checkInviteCodeNow);

        function close() {
            overlay.classList.remove('is-visible');
            window.setTimeout(() => overlay.remove(), 200);
        }

        cancelButton.addEventListener('click', close);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) close();
        });

        function updateRegisterSubmitState(submitting = false) {
            const waitingTurnstile = requiresRegisterTurnstile && registerTurnstileToken === '';
            submitButton.disabled = submitting || waitingTurnstile;
            submitButton.textContent = waitingTurnstile ? '等待验证' : '注册';
        }

        body.addEventListener('submit', async (event) => {
            event.preventDefault();
            updateRegisterSubmitState(true);
            try {
                const username = inputs.username.value.trim();
                if (inputs.password.value !== inputs.password_confirm.value) {
                    throw new Error('两次输入的密码不一致。');
                }
                if (!registerTermsAccepted.checked || !registerCrossBorderAccepted.checked) {
                    throw new Error('请先同意全部协议。');
                }
                if (!inviteCheck && inputs.invite_code.value.trim() !== '') {
                    await checkInviteCodeNow();
                }

                const payload = await api('register', {
                    method: 'POST',
                    body: JSON.stringify({
                        username,
                        password: inputs.password.value,
                        password_confirm: inputs.password_confirm.value,
                        display_name: inputs.display_name.value.trim() || username,
                        invite_code: sanitizeInviteCode(inputs.invite_code.value),
                        group_name: inputs.group_name.value.trim(),
                        group_code: inputs.group_code.value.trim(),
                        terms_accepted: registerTermsAccepted.checked,
                        cross_border_transfer_accepted: registerCrossBorderAccepted.checked,
                        turnstile_token: registerTurnstileToken || turnstileToken(),
                        browser_fingerprint: browserFingerprint(),
                    }),
                });
                if (payload.user) {
                    close();
                    window.location.reload();
                }
            } catch (error) {
                setMessage(error.message, 'error');
                if (registerTurnstileWidgetId !== null && window.turnstile) {
                    window.turnstile.reset(registerTurnstileWidgetId);
                    registerTurnstileToken = '';
                } else {
                    resetTurnstile();
                }
                updateRegisterSubmitState(false);
            }
        });

        card.append(heading, body, message, actions);
        overlay.append(card);
        document.body.append(overlay);
        window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
        inputs.invite_code.focus();

        function renderRegisterTurnstile() {
            if (!document.body.contains(overlay) || !requiresRegisterTurnstile) return;
            if (!window.turnstile || typeof window.turnstile.render !== 'function') {
                window.setTimeout(renderRegisterTurnstile, 200);
                return;
            }
            if (registerTurnstileWidgetId !== null) return;
            registerTurnstileWidgetId = window.turnstile.render(registerTurnstileBox, {
                sitekey: window.CF_TURNSTILE_SITE_KEY,
                callback: (token) => {
                    registerTurnstileToken = token;
                    updateRegisterSubmitState(false);
                },
                'expired-callback': () => {
                    registerTurnstileToken = '';
                    updateRegisterSubmitState(false);
                },
                'error-callback': () => {
                    registerTurnstileToken = '';
                    updateRegisterSubmitState(false);
                },
            });
        }
        renderRegisterTurnstile();

        const initialInviteCode = sanitizeInviteCode(options.inviteCode);
        if (initialInviteCode) {
            inputs.invite_code.value = initialInviteCode;
            window.setTimeout(checkInviteCodeNow, 0);
        } else {
            setInviteFieldsVisibility({});
        }
    }

    async function checkClipboardInvite() {
        if (!window.LocationBridge) return;
        const code = inviteCodeFromClipboardText(clipboardTextFromNative());
        if (!code) return;
        try {
            await api('invite_check', {
                method: 'POST',
                body: JSON.stringify({ code }),
            });
            showSimplePopup('检测到邀请码', '剪切板中存在可用邀请码，可以直接注册。', {
                closeText: '我知道了',
            });
            openRegisterPopup({ inviteCode: code });
        } catch (error) {
            // Invalid clipboard content should not block login.
        }
    }

    const loginSubmitButton = el.loginForm ? el.loginForm.querySelector('button[type="submit"]') : null;

    function loginTurnstileRequired() {
        return !!String(window.CF_TURNSTILE_SITE_KEY || '').trim();
    }

    function updateLoginSubmitState() {
        if (!loginSubmitButton) {
            return;
        }
        const waiting = loginTurnstileRequired() && !turnstileToken();
        loginSubmitButton.disabled = waiting;
        loginSubmitButton.textContent = waiting ? '等待质询' : '登录';
        if (el.loginMessage && waiting) {
            el.loginMessage.textContent = '请等待质询完成。';
            el.loginMessage.hidden = false;
        } else if (el.loginMessage && el.loginMessage.textContent === '请等待质询完成。') {
            el.loginMessage.hidden = true;
        }
    }

    window.onTurnstileSuccess = (token) => {
        window.__turnstileToken = token;
        updateLoginSubmitState();
    };

    window.onTurnstileExpired = () => {
        window.__turnstileToken = '';
        updateLoginSubmitState();
    };

    function turnstileToken() {
        if (!String(window.CF_TURNSTILE_SITE_KEY || '').trim()) {
            return '';
        }
        if (window.turnstile && el.turnstileBox) {
            const response = window.turnstile.getResponse();
            if (response) return response;
        }
        return String(window.__turnstileToken || '');
    }

    function resetTurnstile() {
        try {
            if (window.turnstile && el.turnstileBox) {
                window.turnstile.reset();
            }
        } catch (error) {
            console.warn(error);
        }
        window.__turnstileToken = '';
        updateLoginSubmitState();
    }

    if (el.termsButton) {
        el.termsButton.addEventListener('click', () => showLegalDocument('user_agreement', '用户协议'));
    }
    if (el.privacyButton) {
        el.privacyButton.addEventListener('click', () => showLegalDocument('privacy_policy', '隐私条约'));
    }
    if (el.crossBorderButton) {
        el.crossBorderButton.addEventListener('click', () => showLegalDocument('cross_border_transfer', '用户数据跨境加密传输协议'));
    }
    if (el.registerButton) {
        el.registerButton.disabled = false;
        el.registerButton.setAttribute('aria-disabled', 'false');
        el.registerButton.textContent = '注册账号';
        el.registerButton.addEventListener('click', () => openRegisterPopup());
    }

    if (el.loginForm) {
        el.loginForm.addEventListener('submit', async (event) => {
            event.preventDefault();
            el.loginMessage.hidden = true;

            if (loginTurnstileRequired() && !turnstileToken()) {
                updateLoginSubmitState();
                return;
            }

            if (el.termsAccepted && !el.termsAccepted.checked) {
                await showCombinedLegalDocuments();
                return;
            }
            if (el.crossBorderAccepted && !el.crossBorderAccepted.checked) {
                await showLegalDocument('cross_border_transfer', '用户数据跨境加密传输协议', {
                    closeText: '我知道了',
                });
                return;
            }

            try {
                const payload = await api('login', {
                    method: 'POST',
                    body: JSON.stringify({
                        username: el.username.value,
                        password: el.password.value,
                        terms_accepted: !!(el.termsAccepted && el.termsAccepted.checked),
                        cross_border_transfer_accepted: !!(el.crossBorderAccepted && el.crossBorderAccepted.checked),
                        turnstile_token: turnstileToken(),
                        browser_fingerprint: browserFingerprint(),
                    }),
                });

                el.password.value = '';
                if (payload.redirect) {
                    window.location.href = payload.redirect;
                    return;
                }
                if (payload.user) {
                    window.location.reload();
                }
            } catch (error) {
                el.loginMessage.textContent = error.message;
                el.loginMessage.hidden = false;
                resetTurnstile();
            }
        });
    }

    const storedMessage = sessionStorage.getItem('login_message') || '';
    if (storedMessage && el.loginMessage) {
        sessionStorage.removeItem('login_message');
        el.loginMessage.textContent = storedMessage;
        el.loginMessage.hidden = false;
    }

    window.setTimeout(updateLoginSubmitState, 0);
    window.setTimeout(checkClipboardInvite, 500);
})();
