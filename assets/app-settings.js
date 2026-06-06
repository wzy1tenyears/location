async function refreshAnnouncement(autoShow = false) {
    if (!state.user) {
        return;
    }

    try {
        const payload = await api('announcement', { method: 'GET' });
        state.announcement = payload.announcement || null;
        if (el.announcementButton) {
            el.announcementButton.hidden = !state.announcement;
        }
        if (autoShow && state.announcement) {
            const key = `announcement_seen_${state.announcement.id}_${state.announcement.version}`;
            if (window.localStorage.getItem(key) !== '1') {
                showAnnouncementPopup();
                window.localStorage.setItem(key, '1');
            }
        }
    } catch (error) {
        console.warn(error);
    }
}

function showAnnouncementPopup() {
    if (!state.announcement) {
        showSimplePopup('公告', '暂无公告。');
        return;
    }

    showDocumentPopup(state.announcement.title || '公告', [{
        title: '',
        paragraphs: String(state.announcement.body || '').split(/\r?\n/).filter(Boolean),
    }]);
}

async function openTicketsPopup() {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card ticket-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '工单';

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body ticket-dialog-body';
    const loading = document.createElement('p');
    loading.textContent = '正在加载...';
    body.append(loading);

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const newButton = document.createElement('button');
    newButton.type = 'button';
    newButton.className = 'popup-primary-action';
    newButton.textContent = '新建工单';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button popup-secondary-action';
    closeButton.textContent = '关闭';

    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };
    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });
    newButton.addEventListener('click', () => renderTicketCreateForm(body));

    actions.append(closeButton, newButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));

    await renderTicketList(body);
}

async function renderTicketList(container) {
    container.replaceChildren();
    try {
        const payload = await api('tickets', { method: 'GET' });
        const tickets = payload.tickets || [];
        if (!tickets.length) {
            const empty = document.createElement('p');
            empty.textContent = '暂无工单。';
            container.append(empty);
            return;
        }

        const list = document.createElement('div');
        list.className = 'ticket-list';
        tickets.forEach((ticket) => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'ticket-item';
            item.innerHTML = `<strong>${escapeHtml(ticket.subject)}</strong>
                <span>${escapeHtml(ticket.status_label)} / ${escapeHtml(ticket.updated_at || ticket.created_at)}</span>
                <span>${escapeHtml(ticket.last_message || '暂无回复')}</span>`;
            item.addEventListener('click', () => renderTicketThread(container, ticket.id));
            list.append(item);
        });
        container.append(list);
    } catch (error) {
        const message = document.createElement('div');
        message.className = 'message';
        message.textContent = error.message;
        container.append(message);
    }
}

function renderTicketCreateForm(container) {
    container.replaceChildren();
    const form = document.createElement('form');
    form.className = 'ticket-form';
    const subject = document.createElement('input');
    subject.placeholder = '标题';
    subject.required = true;
    const message = document.createElement('textarea');
    message.placeholder = '描述问题';
    message.required = true;
    message.rows = 5;
    const feedback = document.createElement('div');
    feedback.className = 'message';
    feedback.hidden = true;
    const submit = document.createElement('button');
    submit.type = 'submit';
    submit.textContent = '提交';
    const back = document.createElement('button');
    back.type = 'button';
    back.className = 'subtle-button';
    back.textContent = '返回';
    back.addEventListener('click', () => renderTicketList(container));
    form.append(subject, message, feedback, submit, back);
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        submit.disabled = true;
        feedback.hidden = true;
        try {
            const payload = await api('tickets', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'create',
                    group_name: state.selectedGroupName,
                    subject: subject.value,
                    message: message.value,
                }),
            });
            await renderTicketThread(container, payload.ticket_id);
        } catch (error) {
            feedback.textContent = error.message;
            feedback.hidden = false;
        } finally {
            submit.disabled = false;
        }
    });
    container.append(form);
}

async function renderTicketThread(container, ticketId) {
    container.replaceChildren();
    const loading = document.createElement('p');
    loading.textContent = '正在加载...';
    container.append(loading);

    try {
        const payload = await api(`tickets?ticket_id=${encodeURIComponent(ticketId)}`, { method: 'GET' });
        const ticket = payload.ticket;
        const messages = payload.messages || [];
        container.replaceChildren();

        const title = document.createElement('div');
        title.className = 'ticket-thread-title';
        title.innerHTML = `<strong>${escapeHtml(ticket.subject)}</strong><span>${escapeHtml(ticket.status_label)}</span>`;
        container.append(title);

        const list = document.createElement('div');
        list.className = 'ticket-message-list';
        messages.forEach((message) => {
            const row = document.createElement('div');
            row.className = `ticket-message ${message.sender_type}`;
            row.innerHTML = `<strong>${escapeHtml(message.sender_label)} · ${escapeHtml(message.created_at)}</strong><p>${escapeHtml(message.message)}</p>`;
            list.append(row);
        });
        container.append(list);

        const form = document.createElement('form');
        form.className = 'ticket-form';
        const input = document.createElement('textarea');
        input.rows = 3;
        input.placeholder = ticket.status === 'closed' ? '工单已关闭' : '输入回复';
        input.disabled = ticket.status === 'closed';
        const feedback = document.createElement('div');
        feedback.className = 'message';
        feedback.hidden = true;
        const submit = document.createElement('button');
        submit.type = 'submit';
        submit.textContent = '发送';
        submit.disabled = ticket.status === 'closed';
        const back = document.createElement('button');
        back.type = 'button';
        back.className = 'subtle-button';
        back.textContent = '返回列表';
        back.addEventListener('click', () => renderTicketList(container));
        form.append(input, feedback, submit, back);
        form.addEventListener('submit', async (event) => {
            event.preventDefault();
            if (input.value.trim() === '') {
                return;
            }
            submit.disabled = true;
            try {
                await api('tickets', {
                    method: 'POST',
                    body: JSON.stringify({
                        action: 'reply',
                        ticket_id: ticket.id,
                        message: input.value,
                    }),
                });
                await renderTicketThread(container, ticket.id);
            } catch (error) {
                feedback.textContent = error.message;
                feedback.hidden = false;
                submit.disabled = false;
            }
        });
        container.append(form);
    } catch (error) {
        container.replaceChildren();
        const message = document.createElement('div');
        message.className = 'message';
        message.textContent = error.message;
        container.append(message);
    }
}

function openSettingsPopup() {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '设置';

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';

    const themeLabel = document.createElement('label');
    themeLabel.className = 'settings-field';
    const themeTitle = document.createElement('span');
    themeTitle.textContent = '深色模式';
    const themeSelect = document.createElement('select');
    themeSelect.dataset.themeModeSelect = '1';
    [
        ['system', '跟随系统'],
        ['light', '明亮'],
        ['dark', '暗色'],
    ].forEach(([value, label]) => {
        const option = document.createElement('option');
        option.value = value;
        option.textContent = label;
        themeSelect.append(option);
    });
    themeSelect.value = window.localStorage.getItem(THEME_STORAGE_KEY) || 'system';
    themeSelect.addEventListener('change', () => applyThemeMode(themeSelect.value));
    themeLabel.append(themeTitle, themeSelect);

    const envLabel = document.createElement('label');
    envLabel.className = 'settings-check-field';
    const envInput = document.createElement('input');
    envInput.type = 'checkbox';
    envInput.checked = !!(state.user && state.user.environment_data_consent);
    const envText = document.createElement('span');
    envText.textContent = '同意上报环境数据用于改进软件';
    const envButton = document.createElement('button');
    envButton.type = 'button';
    envButton.className = 'subtle-button';
    envButton.textContent = '立即上报环境信息';
    envButton.disabled = !envInput.checked;
    envButton.addEventListener('click', async () => {
        envButton.disabled = true;
        try {
            await uploadEnvironmentDataIfAllowed(true, true);
        } finally {
            envButton.disabled = !envInput.checked;
        }
    });
    envInput.addEventListener('change', async () => {
        const checked = envInput.checked;
        envInput.disabled = true;
        envButton.disabled = true;
        try {
            const payload = await api('settings', {
                method: 'POST',
                body: JSON.stringify({
                    group_name: state.selectedGroupName,
                    environment_data_consent: checked,
                }),
            });
            state.user = payload.user;
            if (checked) {
                uploadEnvironmentDataIfAllowed();
            }
        } catch (error) {
            envInput.checked = !checked;
            showSimplePopup('设置失败', error.message);
        } finally {
            envInput.disabled = false;
            envButton.disabled = !envInput.checked;
        }
    });
    envLabel.append(envInput, envText);
    const envSection = document.createElement('section');
    envSection.className = 'settings-field';
    const envTitle = document.createElement('span');
    envTitle.textContent = '环境数据';
    envSection.append(envTitle, envLabel, envButton);

    const passwordLabel = document.createElement('label');
    passwordLabel.className = 'settings-field';
    const passwordTitle = document.createElement('span');
    passwordTitle.textContent = '账号安全';
    const passwordRow = document.createElement('div');
    passwordRow.className = 'settings-inline-row';
    const passwordHelp = document.createElement('span');
    passwordHelp.className = 'settings-help';
    passwordHelp.textContent = '验证当前密码后修改';
    const passwordButton = document.createElement('button');
    passwordButton.type = 'button';
    passwordButton.className = 'subtle-button';
    passwordButton.textContent = '修改密码';
    passwordButton.addEventListener('click', openPasswordChangePopup);
    passwordRow.append(passwordHelp, passwordButton);
    passwordLabel.append(passwordTitle, passwordRow);

    const joinLabel = document.createElement('label');
    joinLabel.className = 'settings-field';
    const joinTitle = document.createElement('span');
    joinTitle.textContent = '通过组号加入家庭组';
    const joinRow = document.createElement('div');
    joinRow.className = 'settings-inline-row';
    const joinInput = document.createElement('input');
    joinInput.placeholder = '6 位组号';
    joinInput.maxLength = 6;
    const joinButton = document.createElement('button');
    joinButton.type = 'button';
    joinButton.className = 'subtle-button';
    joinButton.textContent = '加入';
    joinButton.addEventListener('click', async () => {
        joinButton.disabled = true;
        try {
            const payload = await api('groups', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'join_by_code',
                    group_code: joinInput.value,
                }),
            });
            state.user = payload.user;
            renderGroupSelect();
            applySelectedGroup(preferredGroupName(state.user), true);
            showSimplePopup('加入成功', '已加入家庭组。');
        } catch (error) {
            showSimplePopup('加入失败', error.message);
        } finally {
            joinButton.disabled = false;
        }
    });
    joinRow.append(joinInput, joinButton);
    joinLabel.append(joinTitle, joinRow);

    body.append(themeLabel, envSection, passwordLabel, joinLabel);

    const selectedGroup = currentGroup();
    if (selectedGroup) {
        const leaveLabel = document.createElement('section');
        leaveLabel.className = 'settings-field';
        const leaveTitle = document.createElement('span');
        leaveTitle.textContent = '当前家庭组';
        const leaveRow = document.createElement('div');
        leaveRow.className = 'settings-inline-row';
        const leaveHelp = document.createElement('span');
        leaveHelp.className = 'settings-help';
        leaveHelp.textContent = groupOptionText(selectedGroup);
        const leaveButton = document.createElement('button');
        leaveButton.type = 'button';
        leaveButton.className = 'subtle-button danger-subtle-button';
        leaveButton.textContent = '退出';
        leaveButton.addEventListener('click', () => openLeaveGroupPopup(selectedGroup, close));
        leaveRow.append(leaveHelp, leaveButton);
        leaveLabel.append(leaveTitle, leaveRow);
        body.append(leaveLabel);

    }

    const ownedGroups = userGroups().filter((group) => Number(group.owner_user_id || 0) === Number(state.user && state.user.id));
    if (ownedGroups.length) {
        const ownerTitle = document.createElement('h3');
        ownerTitle.textContent = '我的家庭组管理';
        body.append(ownerTitle);
        ownedGroups.forEach((group) => {
            const groupLabel = document.createElement('label');
            groupLabel.className = 'settings-field';
            const title = document.createElement('span');
            title.textContent = `${groupDisplayName(group)} / 组号 ${group.group_code || '未生成'}`;
            const row = document.createElement('div');
            row.className = 'settings-inline-row settings-wide-action-row';
            const input = document.createElement('input');
            input.value = groupDisplayName(group);
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'subtle-button';
            button.textContent = '改名';
            button.addEventListener('click', async () => {
                button.disabled = true;
                try {
                    const payload = await api('groups', {
                        method: 'POST',
                        body: JSON.stringify({
                            action: 'rename_group',
                            group_id: group.id,
                            group_name: input.value,
                        }),
                    });
                    state.user = payload.user;
                    renderGroupSelect();
                    applySelectedGroup(group.group_name, true);
                    showSimplePopup('保存成功', '家庭组名称已更新。');
                } catch (error) {
                    showSimplePopup('保存失败', error.message);
                } finally {
                    button.disabled = false;
                }
            });
            const membersButton = document.createElement('button');
            membersButton.type = 'button';
            membersButton.className = 'subtle-button';
            membersButton.textContent = '更多操作';
            membersButton.addEventListener('click', () => openGroupMoreActionsPopup(group));
            row.append(input, button, membersButton);
            groupLabel.append(title, row);
            body.append(groupLabel);
        });
    }

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.textContent = '关闭';
    function close() {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    }
    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });
    actions.append(closeButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    refreshPopupSelectControls();
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function openGroupMoreActionsPopup(group) {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = `${groupDisplayName(group)} 更多操作`;

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';

    const membersTitle = document.createElement('h3');
    membersTitle.textContent = '成员管理';
    body.append(membersTitle);
    appendGroupMembersList(body, group, close);

    if (window.P2PLocationCrypto && typeof window.P2PLocationCrypto.settingsElement === 'function') {
        const p2pSection = document.createElement('section');
        p2pSection.className = 'settings-field p2p-group-settings';
        p2pSection.append(window.P2PLocationCrypto.settingsElement(group.group_name, () => {
            refreshLocations();
            refreshHistory();
        }));
        body.append(p2pSection);
    } else {
        const message = document.createElement('p');
        message.className = 'settings-help';
        message.textContent = '当前环境暂不支持端到端加密设置。';
        body.append(message);
    }

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.textContent = '关闭';

    function close() {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    }

    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    actions.append(closeButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}
function openActionPopup({ title, message, confirmText = '确认', danger = false, onConfirm }) {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = title;

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';
    const text = document.createElement('p');
    text.textContent = message;
    body.append(text);

    const feedback = document.createElement('div');
    feedback.className = 'message';
    feedback.hidden = true;
    body.append(feedback);

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button popup-secondary-action';
    closeButton.textContent = '取消';
    const confirmButton = document.createElement('button');
    confirmButton.type = 'button';
    confirmButton.className = danger ? 'danger-action-button' : 'popup-primary-action';
    confirmButton.textContent = confirmText;

    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };

    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });
    confirmButton.addEventListener('click', async () => {
        confirmButton.disabled = true;
        feedback.hidden = true;
        try {
            await onConfirm();
            close();
        } catch (error) {
            feedback.textContent = error.message;
            feedback.hidden = false;
        } finally {
            confirmButton.disabled = false;
        }
    });

    actions.append(closeButton, confirmButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function openLeaveGroupPopup(group, closeSettings = null) {
    openActionPopup({
        title: '退出家庭组',
        message: `确认退出 ${groupDisplayName(group)}？退出后将无法查看这个家庭组的位置。`,
        confirmText: '退出',
        danger: true,
        onConfirm: async () => {
            const payload = await api('groups', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'leave_group',
                    group_name: group.group_name,
                }),
            });
            syncUserPayload(payload);
            if (typeof closeSettings === 'function') {
                closeSettings();
            }
            showSimplePopup('已退出', '已退出该家庭组。');
        },
    });
}

function appendGroupMembersList(container, group, closeParent = null) {
    const members = Array.isArray(group.members) ? group.members : [];
    if (!members.length) {
        const empty = document.createElement('p');
        empty.textContent = '当前没有可管理的成员。';
        container.append(empty);
        return;
    }

    members.forEach((member) => {
        const row = document.createElement('div');
        row.className = 'settings-member-row';

        const info = document.createElement('div');
        info.className = 'settings-member-info';
        const name = document.createElement('strong');
        name.textContent = userDisplayName(member) || '未命名用户';
        const meta = document.createElement('span');
        meta.textContent = `${member.username || ''} / ${member.role_label || '未知类型'}`;
        info.append(name, meta);

        const actions = document.createElement('div');
        actions.className = 'settings-member-actions';
        const isSelf = Number(member.user_id) === Number(state.user && state.user.id);

        const resetButton = document.createElement('button');
        resetButton.type = 'button';
        resetButton.className = 'subtle-button';
        resetButton.textContent = '重置密码';
        resetButton.disabled = isSelf;
        resetButton.addEventListener('click', () => openMemberPasswordResetPopup(group, member));

        const removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'subtle-button danger-subtle-button';
        removeButton.textContent = '踢出';
        removeButton.disabled = isSelf;
        removeButton.addEventListener('click', () => openRemoveMemberPopup(group, member, closeParent));

        actions.append(resetButton, removeButton);
        row.append(info, actions);
        container.append(row);
    });
}

function openGroupMembersPopup(group) {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = `${groupDisplayName(group)} 成员`;

    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';
    appendGroupMembersList(body, group, () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    });

    const dialogActions = document.createElement('div');
    dialogActions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button popup-secondary-action';
    closeButton.textContent = '关闭';
    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };
    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    dialogActions.append(closeButton);
    card.append(heading, body, dialogActions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function openRemoveMemberPopup(group, member, closeMembers = null) {
    openActionPopup({
        title: '踢出成员',
        message: `确认将 ${userDisplayName(member) || member.username} 移出 ${groupDisplayName(group)}？`,
        confirmText: '踢出',
        danger: true,
        onConfirm: async () => {
            const payload = await api('groups', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'remove_member',
                    group_name: group.group_name,
                    target_user_id: member.user_id,
                }),
            });
            syncUserPayload(payload, group.group_name);
            if (typeof closeMembers === 'function') {
                closeMembers();
            }
            showSimplePopup('已移除', '成员已移出家庭组。');
        },
    });
}

function openMemberPasswordResetPopup(group, member) {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '重置成员密码';

    const form = document.createElement('form');
    form.className = 'popup-dialog-body settings-dialog-body';

    const intro = document.createElement('p');
    intro.textContent = `为 ${userDisplayName(member) || member.username} 设置新密码。该成员属于多个家庭组时，需要走工单系统申请。`;
    form.append(intro);

    const inputs = {};
    [['new_password', '新密码'], ['new_password_confirm', '确认新密码']].forEach(([name, labelText]) => {
        const label = document.createElement('label');
        label.className = 'settings-field';
        const span = document.createElement('span');
        span.textContent = labelText;
        const input = document.createElement('input');
        input.name = name;
        input.type = 'password';
        input.placeholder = '至少 6 位';
        inputs[name] = input;
        label.append(span, input);
        form.append(label);
    });

    const confirmLabel = document.createElement('label');
    confirmLabel.className = 'settings-check-field';
    const confirmInput = document.createElement('input');
    confirmInput.type = 'checkbox';
    const confirmText = document.createElement('span');
    confirmText.textContent = '我确认要重置该成员密码';
    confirmLabel.append(confirmInput, confirmText);
    form.append(confirmLabel);

    const message = document.createElement('div');
    message.className = 'message';
    message.hidden = true;
    form.append(message);

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button popup-secondary-action';
    closeButton.textContent = '关闭';
    const submitButton = document.createElement('button');
    submitButton.type = 'button';
    submitButton.className = 'popup-primary-action';
    submitButton.textContent = '重置密码';

    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };
    closeButton.addEventListener('click', close);
    submitButton.addEventListener('click', () => form.requestSubmit());
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        message.hidden = true;
        submitButton.disabled = true;
        try {
            if (inputs.new_password.value !== inputs.new_password_confirm.value) {
                throw new Error('两次输入的新密码不一致。');
            }
            const payload = await api('groups', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'reset_member_password',
                    group_name: group.group_name,
                    target_user_id: member.user_id,
                    new_password: inputs.new_password.value,
                    new_password_confirm: inputs.new_password_confirm.value,
                    confirm: confirmInput.checked,
                }),
            });
            syncUserPayload(payload, group.group_name);
            close();
            showSimplePopup('已重置', '成员密码已更新。');
        } catch (error) {
            message.textContent = error.message;
            message.hidden = false;
        } finally {
            submitButton.disabled = false;
        }
    });

    actions.append(closeButton, submitButton);
    card.append(heading, form, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function openPasswordChangePopup() {
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '修改密码';

    const body = document.createElement('form');
    body.className = 'popup-dialog-body settings-dialog-body';

    const inputs = {};
    function addPasswordField(name, labelText, placeholder) {
        const label = document.createElement('label');
        label.className = 'settings-field';
        const span = document.createElement('span');
        span.textContent = labelText;
        const input = document.createElement('input');
        input.name = name;
        input.type = 'password';
        input.placeholder = placeholder;
        inputs[name] = input;
        label.append(span, input);
        body.append(label);
    }

    addPasswordField('current_password', '当前密码', '输入当前密码');
    addPasswordField('new_password', '新密码', '至少 6 位');
    addPasswordField('new_password_confirm', '确认新密码', '再次输入新密码');

    const message = document.createElement('div');
    message.className = 'message';
    message.hidden = true;
    body.append(message);

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button popup-secondary-action';
    closeButton.textContent = '关闭';
    const submitButton = document.createElement('button');
    submitButton.type = 'button';
    submitButton.className = 'popup-primary-action';
    submitButton.textContent = '保存';

    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };
    closeButton.addEventListener('click', close);
    submitButton.addEventListener('click', () => body.requestSubmit());
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });

    body.addEventListener('submit', async (event) => {
        event.preventDefault();
        message.hidden = true;
        submitButton.disabled = true;
        try {
            if (inputs.new_password.value !== inputs.new_password_confirm.value) {
                throw new Error('两次输入的新密码不一致。');
            }
            await api('settings', {
                method: 'POST',
                body: JSON.stringify({
                    action: 'change_password',
                    group_name: state.selectedGroupName,
                    current_password: inputs.current_password.value,
                    new_password: inputs.new_password.value,
                    new_password_confirm: inputs.new_password_confirm.value,
                }),
            });
            close();
            showSimplePopup('修改成功', '密码已更新。');
        } catch (error) {
            message.textContent = error.message;
            message.hidden = false;
        } finally {
            submitButton.disabled = false;
        }
    });

    actions.append(closeButton, submitButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

function getGuardianContinuousReportingForGroup(groupName = state.selectedGroupName) {
    const localValue = getLocalGuardianContinuousReporting(groupName);

    if (!window.LocationBridge) {
        return localValue;
    }

    try {
        if (typeof window.LocationBridge.getGuardianContinuousReportingForGroup === 'function') {
            return window.LocationBridge.getGuardianContinuousReportingForGroup(groupName) === true || localValue;
        }

        if (groupName === state.selectedGroupName) {
            return window.LocationBridge.getGuardianContinuousReporting() === true || localValue;
        }
    } catch (error) {
        console.warn(error);
    }

    return localValue;
}

function getLocalGuardianContinuousReporting(groupName = state.selectedGroupName) {
    if (!state.user || !groupName) {
        return false;
    }

    return window.localStorage.getItem(guardianContinuousStorageKey(groupName)) === '1';
}

function setGuardianContinuousReportingForGroup(groupName, enabled) {
    if (!state.user || !groupName) {
        return;
    }

    window.localStorage.setItem(guardianContinuousStorageKey(groupName), enabled ? '1' : '0');
}

function guardianContinuousStorageKey(groupName) {
    return `guardian_continuous_${state.user.id}_${encodeURIComponent(groupName)}`;
}

function crossSyncStorageKey() {
    return state.user ? `cross_group_sync_${state.user.id}` : 'cross_group_sync';
}

function selectedCrossSyncGroups() {
    if (!state.user) {
        return [];
    }

    try {
        const values = JSON.parse(window.localStorage.getItem(crossSyncStorageKey()) || '[]');
        const available = new Set(userGroups().map((group) => group.group_name));
        return Array.isArray(values) ? values.filter((groupName) => available.has(groupName)) : [];
    } catch (error) {
        return [];
    }
}

function setSelectedCrossSyncGroups(groupNames) {
    window.localStorage.setItem(crossSyncStorageKey(), JSON.stringify([...new Set(groupNames.filter(Boolean))]));
}

function openCrossGroupSyncPopup() {
    const groups = userGroups().filter((group) => group.group_name !== state.selectedGroupName);
    if (!groups.length) {
        showSimplePopup('跨组同步', '当前账号没有其他家庭组。');
        return;
    }

    const selected = new Set(selectedCrossSyncGroups());
    const overlay = document.createElement('div');
    overlay.className = 'popup-select-overlay';

    const card = document.createElement('div');
    card.className = 'popup-select-card popup-dialog-card';
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    const heading = document.createElement('h2');
    heading.textContent = '跨组同步';
    const body = document.createElement('div');
    body.className = 'popup-dialog-body settings-dialog-body';

    groups.forEach((group) => {
        const label = document.createElement('label');
        label.className = 'settings-check-field';
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.value = group.group_name;
        input.checked = selected.has(group.group_name);
        const text = document.createElement('span');
        text.textContent = `${groupDisplayName(group)} / ${group.role_label}`;
        label.append(input, text);
        body.append(label);
    });

    const actions = document.createElement('div');
    actions.className = 'popup-dialog-actions';
    const saveButton = document.createElement('button');
    saveButton.type = 'button';
    saveButton.textContent = '保存';
    const closeButton = document.createElement('button');
    closeButton.type = 'button';
    closeButton.className = 'subtle-button';
    closeButton.textContent = '关闭';
    const close = () => {
        overlay.classList.remove('is-visible');
        window.setTimeout(() => overlay.remove(), 200);
    };
    saveButton.addEventListener('click', () => {
        const checked = Array.from(body.querySelectorAll('input[type="checkbox"]:checked')).map((input) => input.value);
        setSelectedCrossSyncGroups(checked);
        close();
    });
    closeButton.addEventListener('click', close);
    overlay.addEventListener('click', (event) => {
        if (event.target === overlay) {
            close();
        }
    });
    actions.append(saveButton, closeButton);
    card.append(heading, body, actions);
    overlay.append(card);
    document.body.append(overlay);
    window.requestAnimationFrame(() => overlay.classList.add('is-visible'));
}

