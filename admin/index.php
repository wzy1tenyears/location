<?php

declare(strict_types=1);

require_once __DIR__ . '/../private/lib/bootstrap.php';

require_loc_app_page();
require_admin_path();

require_admin();

$message = '';
$error = '';

require_once __DIR__ . '/../private/admin/helpers.php';
require_once __DIR__ . '/../private/admin/controller.php';
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>后台管理 - <?= e(APP_NAME) ?></title>
    <script>
        (function () {
            try {
                var mode = window.localStorage.getItem('theme_mode') || 'system';
                if (mode === 'light' || mode === 'dark') {
                    document.documentElement.dataset.theme = mode;
                }
            } catch (error) {
            }
        })();
    </script>
    <link rel="stylesheet" href="/<?= e(admin_url_path()) ?>assets/admin.css?v=<?= (int) filemtime(__DIR__ . '/assets/admin.css') ?>">
</head>
<body>
    <header class="topbar">
        <h1>定位后台管理</h1>
        <div class="topbar-actions">
            <label class="theme-toggle" for="themeMode">
                <span>&#20027;&#39064;</span>
                <select id="themeMode" aria-label="&#20027;&#39064;">
                    <option value="system">&#36319;&#38543;&#31995;&#32479;</option>
                    <option value="light">&#26126;&#20142;</option>
                    <option value="dark">&#26263;&#33394;</option>
                </select>
            </label>
            <span class="muted">已登录：<?= e(ADMIN_USERNAME) ?></span>
            <a class="button secondary" href="logout.php">退出</a>
        </div>
    </header>

    <main class="container">
        <?php if ($message !== ''): ?>
            <div class="alert success"><?= e($message) ?></div>
        <?php endif; ?>
        <?php if ($error !== ''): ?>
            <div class="alert error"><?= e($error) ?></div>
        <?php endif; ?>

        <section class="panel presence-panel">
            <div class="section-heading">
                <h2>在线用户</h2>
                <span class="badge"><?= (int) $onlineUserCount ?> 人在线</span>
            </div>
            <?php if (!$onlineUsers): ?>
                <div class="muted">暂无在线用户，客户端会每分钟发送一次心跳。</div>
            <?php else: ?>
                <div class="compact-list">
                    <?php foreach ($onlineUsers as $presence): ?>
                        <div class="compact-row">
                            <strong><?= e((string) ($presence['display_name'] ?: $presence['username'])) ?></strong>
                            <span class="muted"><?= e((string) $presence['username']) ?></span>
                            <span class="muted"><?= e((string) $presence['last_group_name']) ?></span>
                            <span class="muted"><?= e(format_datetime((string) $presence['last_seen_at'])) ?></span>
                        </div>
                    <?php endforeach; ?>
                </div>
            <?php endif; ?>
        </section>

        <datalist id="familyGroupOptions">
            <?php foreach ($familyGroups as $group): ?>
                <option value="<?= e((string) $group['group_name']) ?>"></option>
            <?php endforeach; ?>
        </datalist>

        <div class="grid">
            <section class="panel">
                <h2>安全策略</h2>
                <form method="post">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="update_security_settings">
                    <?php
                    $securityLabels = [
                        'ban_root_users' => '禁止 Root 用户',
                        'ban_adb_users' => '禁止 ADB 用户',
                        'ban_fake_location_users' => '禁止模拟定位用户',
                        'ban_accessibility_users' => '禁止异常无障碍用户',
                        'ban_packet_capture_users' => '禁止抓包工具用户',
                    ];
                    ?>
                    <div class="check-list">
                        <?php foreach ($securityLabels as $key => $label): ?>
                            <label class="check-line">
                                <input name="<?= e($key) ?>" type="checkbox" value="1" <?= !empty($securitySettings[$key]) ? 'checked' : '' ?>>
                                <span><?= e($label) ?></span>
                            </label>
                        <?php endforeach; ?>
                    </div>
                    <p class="muted">账号启用调试模式后，会忽略这些禁用项。</p>
                    <button type="submit">保存安全策略</button>
                </form>
            </section>

            <section class="panel">
                <h2>公告管理</h2>
                <form method="post">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="save_announcement">
                    <div class="field">
                        <label for="announcement_title">公告标题</label>
                        <input id="announcement_title" name="title" value="<?= e((string) ($announcement['title'] ?? '')) ?>">
                    </div>
                    <div class="field">
                        <label for="announcement_body">公告内容</label>
                        <textarea id="announcement_body" name="body" rows="5"><?= e((string) ($announcement['body'] ?? '')) ?></textarea>
                    </div>
                    <label class="check-line">
                        <input name="is_active" type="checkbox" value="1" <?= !empty($announcement['is_active']) ? 'checked' : '' ?>>
                        <span>启用公告</span>
                    </label>
                    <button type="submit">保存公告</button>
                </form>
            </section>

            <section class="panel">
                <h2>邀请码管理</h2>
                <form class="compact-form" method="post">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_invite_code">
                    <div class="field">
                        <label for="invite_code">邀请码</label>
                        <input id="invite_code" name="code" placeholder="留空自动生成">
                    </div>
                    <div class="field">
                        <label for="invite_note">备注名</label>
                        <input id="invite_note" name="note" placeholder="仅后台可见">
                    </div>
                    <div class="field">
                        <label for="invite_type">类型</label>
                        <select id="invite_type" name="invite_type">
                            <option value="invite">纯邀请</option>
                            <option value="group_create">组创建</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="invite_max_uses">可使用次数</label>
                        <input id="invite_max_uses" name="max_uses" type="number" min="1" value="1">
                    </div>
                    <label class="check-line">
                        <input name="allow_group_owner" type="checkbox" value="1">
                        <span>组创建邀请码允许注册人成为家庭组管理员</span>
                    </label>
                    <button type="submit">添加邀请码</button>
                </form>
                <div class="group-list">
                    <?php if (!$inviteCodes): ?>
                        <div class="muted">暂无邀请码。</div>
                    <?php endif; ?>
                    <?php foreach ($inviteCodes as $invite): ?>
                        <div class="group-row">
                            <div class="group-summary">
                                <strong><?= e((string) $invite['code']) ?></strong>
                                <span class="muted">
                                    <?= ((string) $invite['invite_type'] === 'group_create') ? '组创建' : '纯邀请' ?>
                                    <?php if ((int) ($invite['allow_group_owner'] ?? 0) === 1): ?>
                                        · 可设为组管理员
                                    <?php endif; ?>
                                    · <?= (int) $invite['used_count'] ?>/<?= (int) $invite['max_uses'] ?>
                                    · <?= ((int) $invite['is_active'] === 1) ? '启用' : '停用' ?>
                                </span>
                                <?php if (!empty($invite['note'])): ?>
                                    <span class="muted">备注：<?= e((string) $invite['note']) ?></span>
                                <?php endif; ?>
                                <?php if (!empty($invite['assigned_group_name'])): ?>
                                    <span class="muted">绑定：<?= e((string) $invite['assigned_group_name']) ?></span>
                                <?php endif; ?>
                            </div>
                            <details class="row-more">
                                <summary>更多操作</summary>
                                <form class="inline-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_invite_note">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <input name="note" value="<?= e((string) ($invite['note'] ?? '')) ?>" placeholder="备注名（仅后台可见）">
                                    <button class="small secondary" type="submit">保存备注</button>
                                </form>
                                <form class="inline-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="toggle_invite_code">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <input type="hidden" name="next" value="<?= ((int) $invite['is_active'] === 1) ? 0 : 1 ?>">
                                    <button class="small secondary" type="submit"><?= ((int) $invite['is_active'] === 1) ? '停用' : '启用' ?></button>
                                </form>
                                <form class="inline-form" method="post" data-confirm="确认删除这个邀请码？">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="delete_invite_code">
                                    <input type="hidden" name="invite_id" value="<?= (int) $invite['id'] ?>">
                                    <button class="small danger" type="submit">删除</button>
                                </form>
                            </details>
                        </div>
                    <?php endforeach; ?>
                </div>
            </section>
        </div>

        <div class="grid">
            <section class="panel">
                <h2>家庭组管理</h2>
                <form class="compact-form" method="post" autocomplete="off">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_family_group">
                    <div class="field">
                        <label for="new_group_name">家庭组名称</label>
                        <input id="new_group_name" name="group_name" required>
                    </div>
                    <button type="submit">添加家庭组</button>
                </form>

                <div class="group-list">
                    <?php if (!$familyGroups): ?>
                        <div class="muted">还没有家庭组。</div>
                    <?php endif; ?>
                    <?php foreach ($familyGroups as $group): ?>
                        <?php
                        $groupMembers = $groupMembersByGroup[(string) $group['group_name']] ?? [];
                        $ownerName = '无';
                        foreach ($groupMembers as $groupMember) {
                            if ((int) $groupMember['id'] === (int) ($group['owner_user_id'] ?? 0)) {
                                $ownerName = trim((string) $groupMember['display_name']) !== ''
                                    ? (string) $groupMember['display_name']
                                    : (string) $groupMember['username'];
                                break;
                            }
                        }
                        ?>
                        <div class="group-row">
                            <div class="group-summary">
                                <strong><?= e(admin_family_group_label($group)) ?></strong>
                                <span class="muted">成员：<?= (int) $group['member_count'] ?></span>
                                <span class="muted">管理员：<?= e($ownerName) ?></span>
                            </div>
                            <details class="row-more">
                                <summary>更多操作</summary>
                                <form class="group-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_family_group">
                                    <input type="hidden" name="group_id" value="<?= (int) $group['id'] ?>">
                                    <label>
                                        <span>家庭组</span>
                                        <input name="group_name" value="<?= e((string) ($group['display_name'] ?: $group['group_name'])) ?>" required>
                                    </label>
                                    <button class="small" type="submit">保存</button>
                                </form>
                                <form class="group-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_group_owner">
                                    <input type="hidden" name="group_id" value="<?= (int) $group['id'] ?>">
                                    <label>
                                        <span>家庭组管理员</span>
                                        <select name="owner_user_id">
                                            <option value="0">无</option>
                                            <?php foreach ($groupMembers as $groupMember): ?>
                                                <?php $memberLabel = trim((string) $groupMember['display_name']) !== '' ? (string) $groupMember['display_name'] : (string) $groupMember['username']; ?>
                                                <option value="<?= (int) $groupMember['id'] ?>" <?= (int) $groupMember['id'] === (int) ($group['owner_user_id'] ?? 0) ? 'selected' : '' ?>>
                                                    <?= e($memberLabel) ?> / <?= e((string) $groupMember['username']) ?>
                                                </option>
                                            <?php endforeach; ?>
                                        </select>
                                    </label>
                                    <button class="small secondary" type="submit">保存管理员</button>
                                </form>
                                <form class="inline-form" method="post" data-confirm="确认删除这个家庭组？组内身份和定位记录会一并清除。">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="delete_family_group">
                                    <input type="hidden" name="group_id" value="<?= (int) $group['id'] ?>">
                                    <button class="small danger" type="submit">删除</button>
                                </form>
                            </details>
                        </div>
                    <?php endforeach; ?>
                </div>
            </section>

            <section class="panel">
                <h2>添加 App 账号</h2>
                <form method="post" autocomplete="off">
                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                    <input type="hidden" name="action" value="add_user">

                    <div class="field">
                        <label for="username">登录账号</label>
                        <input id="username" name="username" required>
                    </div>
                    <div class="field">
                        <label for="password">登录密码</label>
                        <input id="password" name="password" type="password" required>
                    </div>
                    <div class="field">
                        <label for="display_name">显示名称</label>
                        <input id="display_name" name="display_name" placeholder="例如：爸爸、孩子手机">
                    </div>
                    <div class="field">
                        <label for="group_name">初始家庭组</label>
                        <input id="group_name" name="group_name" list="familyGroupOptions" required placeholder="同一家庭填同一个组名">
                    </div>
                    <div class="field">
                        <label for="role">初始账号类型</label>
                        <select id="role" name="role" required>
                            <option value="monitor">监测端</option>
                            <option value="guardian">监护端</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="report_interval_seconds">上报间隔（秒）</label>
                        <input id="report_interval_seconds" name="report_interval_seconds" type="number" min="<?= (int) MIN_REPORT_INTERVAL_SECONDS ?>" max="<?= (int) MAX_REPORT_INTERVAL_SECONDS ?>" value="<?= (int) DEFAULT_REPORT_INTERVAL_SECONDS ?>" required>
                    </div>
                    <button type="submit">添加账号</button>
                </form>
            </section>
        </div>

        <section class="panel account-panel">
            <h2>账号列表</h2>
            <form class="pager-form" method="get">
                <label>
                    <span>每页数量</span>
                    <select name="user_per_page" onchange="this.form.submit()">
                        <option value="10" <?= $userPerPage === 10 ? 'selected' : '' ?>>10 条</option>
                        <option value="20" <?= $userPerPage === 20 ? 'selected' : '' ?>>20 条</option>
                        <option value="50" <?= $userPerPage === 50 ? 'selected' : '' ?>>50 条</option>
                    </select>
                </label>
                <input type="hidden" name="user_page" value="<?= (int) $userPage ?>">
                <span class="muted">第 <?= (int) $userPage ?> / <?= (int) $userTotalPages ?> 页，共 <?= (int) $userTotal ?> 个账号</span>
            </form>
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>账号</th>
                            <th>显示名称</th>
                            <th>上报间隔</th>
                            <th>状态</th>
                            <th>家庭组身份</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php if (!$users): ?>
                            <tr>
                                <td colspan="6" class="muted">还没有账号。</td>
                            </tr>
                        <?php endif; ?>
                        <?php foreach ($users as $user): ?>
                            <?php $userMemberships = $membershipsByUser[(int) $user['id']] ?? []; ?>
                            <?php $environmentReport = $environmentReportsByUser[(int) $user['id']] ?? null; ?>
                            <?php $deviceReport = $deviceReportsByUser[(int) $user['id']] ?? null; ?>
                            <?php $userDevices = $devicesByUser[(int) $user['id']] ?? []; ?>
                            <?php $installedApps = installed_apps_from_environment_report($environmentReport['report_json'] ?? null); ?>
                            <?php $installedUserAppCount = count(array_filter($installedApps, static fn (array $app): bool => empty($app['system']))); ?>
                            <?php $installedSystemAppCount = count($installedApps) - $installedUserAppCount; ?>
                            <?php $deviceInfo = device_info_from_environment_report($deviceReport['report_json'] ?? null); ?>
                            <?php $userAgreementAcceptedAt = (string) ($user['user_agreement_accepted_at'] ?? ($user['terms_accepted_at'] ?? '')); ?>
                            <?php $privacyPolicyAcceptedAt = (string) ($user['privacy_policy_accepted_at'] ?? ($user['terms_accepted_at'] ?? '')); ?>
                            <?php $crossBorderAcceptedAt = (string) ($user['cross_border_transfer_accepted_at'] ?? ''); ?>
                            <tr>
                                <td><?= e((string) $user['username']) ?></td>
                                <td><?= e((string) $user['display_name']) ?></td>
                                <td><?= membership_seconds($user) ?> 秒</td>
                                <td>
                                    <?= ((int) $user['is_active'] === 1) ? '启用' : '停用' ?>
                                    <?php if (!empty($user['debug_mode'])): ?>
                                        <span class="badge">调试</span>
                                    <?php endif; ?>
                                </td>
                                <td>
                                    <div class="membership-summary">
                                        <?php foreach ($userMemberships as $membership): ?>
                                            <span class="badge <?= e((string) $membership['role']) ?>">
                                                <?= e((string) $membership['group_name']) ?> / <?= e(role_label((string) $membership['role'])) ?>
                                            </span>
                                        <?php endforeach; ?>
                                    </div>
                                    <details class="row-more">
                                        <summary>更多操作</summary>
                                        <div class="membership-list">
                                        <?php foreach ($userMemberships as $membership): ?>
                                            <div class="membership-card">
                                                <form class="membership-form" method="post">
                                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                                    <input type="hidden" name="action" value="update_membership">
                                                    <input type="hidden" name="membership_id" value="<?= (int) $membership['id'] ?>">
                                                    <label>
                                                        <span>家庭组</span>
                                                        <input name="group_name" list="familyGroupOptions" value="<?= e((string) $membership['group_name']) ?>" required>
                                                    </label>
                                                    <label>
                                                        <span>身份</span>
                                                        <select name="role" required>
                                                            <option value="monitor" <?= normalize_role((string) $membership['role']) === 'monitor' ? 'selected' : '' ?>>监测端</option>
                                                            <option value="guardian" <?= ((string) $membership['role'] === 'guardian') ? 'selected' : '' ?>>监护端</option>
                                                        </select>
                                                    </label>
                                                    <div class="membership-location">
                                                        <?php if ($membership['location_updated_at']): ?>
                                                            <?= e((string) $membership['latitude']) ?>,
                                                            <?= e((string) $membership['longitude']) ?><br>
                                                            <span class="muted"><?= e(format_datetime((string) $membership['location_updated_at'])) ?></span>
                                                        <?php else: ?>
                                                            <span class="muted">暂无位置</span>
                                                        <?php endif; ?>
                                                    </div>
                                                    <button class="small" type="submit">保存身份</button>
                                                </form>
                                                <form class="inline-form" method="post" data-confirm="确认删除这个家庭组身份？">
                                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                                    <input type="hidden" name="action" value="delete_membership">
                                                    <input type="hidden" name="membership_id" value="<?= (int) $membership['id'] ?>">
                                                    <button class="small danger" type="submit" <?= count($userMemberships) <= 1 ? 'disabled' : '' ?>>删除身份</button>
                                                </form>
                                            </div>
                                        <?php endforeach; ?>

                                        <form class="add-membership-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="add_membership">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input name="group_name" list="familyGroupOptions" placeholder="添加家庭组" required>
                                            <select name="role" required>
                                                <option value="monitor">监测端</option>
                                                <option value="guardian">监护端</option>
                                            </select>
                                            <button class="small secondary" type="submit">添加身份</button>
                                        </form>
                                        </div>
                                    </details>
                                </td>
                                <td>
                                    <details class="row-more">
                                        <summary>更多操作</summary>
                                        <div class="actions">
                                        <form class="edit-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="update_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <label>
                                                <span>账号名称</span>
                                                <input name="username" value="<?= e((string) $user['username']) ?>" required>
                                            </label>
                                            <label>
                                                <span>显示名称</span>
                                                <input name="display_name" value="<?= e((string) $user['display_name']) ?>">
                                            </label>
                                            <label>
                                                <span>上报间隔（秒）</span>
                                                <input name="report_interval_seconds" type="number" min="<?= (int) MIN_REPORT_INTERVAL_SECONDS ?>" max="<?= (int) MAX_REPORT_INTERVAL_SECONDS ?>" value="<?= membership_seconds($user) ?>" required>
                                            </label>
                                            <label class="check-line compact-check">
                                                <input name="debug_mode" type="checkbox" value="1" <?= !empty($user['debug_mode']) ? 'checked' : '' ?>>
                                                <span>调试模式（忽略安全策略）</span>
                                            </label>
                                            <button class="small" type="submit">保存账号</button>
                                        </form>
                                        <form class="inline-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="toggle_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input type="hidden" name="next" value="<?= ((int) $user['is_active'] === 1) ? 0 : 1 ?>">
                                            <?php if ((int) $user['is_active'] === 1): ?>
                                                <input name="disabled_reason" placeholder="停用原因（可选）">
                                            <?php elseif (!empty($user['disabled_reason'])): ?>
                                                <span class="muted">停用原因：<?= e((string) $user['disabled_reason']) ?></span>
                                            <?php endif; ?>
                                            <button class="small secondary" type="submit">
                                                <?= ((int) $user['is_active'] === 1) ? '停用' : '启用' ?>
                                            </button>
                                        </form>
                                        <form class="inline-form" method="post">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="reset_password">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <input name="new_password" placeholder="新密码" required>
                                            <button class="small secondary" type="submit">重置</button>
                                        </form>
                                        <form class="inline-form" method="post" data-confirm="确认删除这个账号和定位记录？">
                                            <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                            <input type="hidden" name="action" value="delete_user">
                                            <input type="hidden" name="user_id" value="<?= (int) $user['id'] ?>">
                                            <button class="small danger" type="submit">删除账号</button>
                                        </form>
                                        <div class="environment-box">
                                            <div class="environment-status">
                                                <span>用户协议：</span>
                                                <?php if ($userAgreementAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($userAgreementAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <div class="environment-status">
                                                <span>隐私条约：</span>
                                                <?php if ($privacyPolicyAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($privacyPolicyAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <div class="environment-status">
                                                <span>跨境传输协议：</span>
                                                <?php if ($crossBorderAcceptedAt !== ''): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime($crossBorderAcceptedAt)) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <div class="environment-status">
                                                <span>设备信息：</span>
                                                <?php if ($deviceReport): ?>
                                                    <strong>已上报</strong>
                                                    <span class="muted"><?= e(format_datetime((string) $deviceReport['created_at'])) ?></span>
                                                <?php else: ?>
                                                    <strong>暂无</strong>
                                                <?php endif; ?>
                                            </div>
                                            <?php if ($deviceInfo): ?>
                                                <details class="row-more installed-apps-details">
                                                    <summary>查看设备信息</summary>
                                                    <div class="device-info-list">
                                                        <?php foreach ($deviceInfo as [$label, $value]): ?>
                                                            <div class="device-info-row">
                                                                <strong><?= e($label) ?></strong>
                                                                <span><?= e($value) ?></span>
                                                            </div>
                                                        <?php endforeach; ?>
                                                    </div>
                                                </details>
                                            <?php endif; ?>
                                            <details class="row-more installed-apps-details">
                                                <summary>设备指纹绑定（<?= count($userDevices) ?>）</summary>
                                                <?php if (!$userDevices): ?>
                                                    <div class="muted">暂无绑定设备。</div>
                                                <?php else: ?>
                                                    <div class="device-bind-list">
                                                        <?php foreach ($userDevices as $device): ?>
                                                            <div class="device-bind-card">
                                                                <strong><?= e(substr((string) $device['device_fingerprint'], 0, 16)) ?>...</strong>
                                                                <span>浏览器：<?= e((string) ($device['browser_fingerprint'] ?: '未记录')) ?></span>
                                                                <span class="muted">首次：<?= e(format_datetime((string) $device['first_seen_at'])) ?></span>
                                                                <span class="muted">最近：<?= e(format_datetime((string) $device['last_seen_at'])) ?></span>
                                                                <?php if (!empty($device['user_agent'])): ?>
                                                                    <span class="muted"><?= e((string) $device['user_agent']) ?></span>
                                                                <?php endif; ?>
                                                                <form class="inline-form" method="post" data-confirm="确认删除这个设备绑定？删除后该设备可重新绑定账号。">
                                                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                                                    <input type="hidden" name="action" value="delete_user_device">
                                                                    <input type="hidden" name="device_id" value="<?= (int) $device['id'] ?>">
                                                                    <button class="small danger" type="submit">删除绑定</button>
                                                                </form>
                                                            </div>
                                                        <?php endforeach; ?>
                                                    </div>
                                                <?php endif; ?>
                                            </details>
                                            <div class="environment-status">
                                                <span>环境数据：</span>
                                                <?php if (!empty($user['environment_data_consent_at'])): ?>
                                                    <strong>已同意</strong>
                                                    <span class="muted"><?= e(format_datetime((string) $user['environment_data_consent_at'])) ?></span>
                                                <?php else: ?>
                                                    <strong>未同意</strong>
                                                <?php endif; ?>
                                            </div>
                                            <?php if (!empty($user['environment_data_consent_at'])): ?>
                                                <details class="row-more installed-apps-details">
                                                    <summary>查看已安装软件</summary>
                                                    <?php if (!$environmentReport): ?>
                                                        <div class="muted">暂无环境数据上报。</div>
                                                    <?php elseif (!$installedApps): ?>
                                                        <div class="muted">最新环境数据没有应用列表。</div>
                                                    <?php else: ?>
                                                        <div class="muted">上报时间：<?= e(format_datetime((string) $environmentReport['created_at'])) ?>，当前显示 <span data-installed-visible-count><?= (int) $installedUserAppCount ?></span> / <?= count($installedApps) ?> 个应用</div>
                                                        <div class="installed-app-toolbar">
                                                            <label class="compact-check">
                                                                <input class="installed-app-system-toggle" type="checkbox">
                                                                <span>显示系统应用<?= $installedSystemAppCount > 0 ? '（' . (int) $installedSystemAppCount . '）' : '' ?></span>
                                                            </label>
                                                            <span class="muted">默认隐藏系统应用。</span>
                                                        </div>
                                                        <div class="installed-app-list">
                                                            <?php foreach ($installedApps as $app): ?>
                                                                <div class="installed-app-item<?= $app['system'] ? ' is-system-app' : '' ?>"<?= $app['system'] ? ' hidden' : '' ?>>
                                                                    <strong><?= e($app['package_name']) ?></strong>
                                                                    <?php if ($app['label'] !== ''): ?>
                                                                        <span><?= e($app['label']) ?></span>
                                                                    <?php endif; ?>
                                                                    <?php if ($app['version_name'] !== ''): ?>
                                                                        <span>版本：<?= e($app['version_name']) ?></span>
                                                                    <?php endif; ?>
                                                                    <?php if ($app['system']): ?>
                                                                        <span class="muted">系统应用</span>
                                                                    <?php endif; ?>
                                                                </div>
                                                            <?php endforeach; ?>
                                                        </div>
                                                    <?php endif; ?>
                                                </details>
                                            <?php endif; ?>
                                        </div>
                                        </div>
                                    </details>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                    </tbody>
                </table>
            </div>
            <div class="pager-actions">
                <?php $prevPage = max(1, (int) $userPage - 1); ?>
                <?php $nextPage = min((int) $userTotalPages, (int) $userPage + 1); ?>
                <a class="button secondary <?= $userPage <= 1 ? 'disabled-link' : '' ?>" href="?user_page=<?= $prevPage ?>&user_per_page=<?= (int) $userPerPage ?>">上一页</a>
                <a class="button secondary <?= $userPage >= $userTotalPages ? 'disabled-link' : '' ?>" href="?user_page=<?= $nextPage ?>&user_per_page=<?= (int) $userPerPage ?>">下一页</a>
            </div>
        </section>

        <section class="panel account-panel">
            <div class="section-heading">
                <h2>用户日志</h2>
                <span class="muted">第 <?= (int) $logPage ?> / <?= (int) $logTotalPages ?> 页，共 <?= (int) $logTotal ?> 条</span>
            </div>
            <form class="pager-form history-filter-form" method="get">
                <input type="hidden" name="user_page" value="<?= (int) $userPage ?>">
                <input type="hidden" name="user_per_page" value="<?= (int) $userPerPage ?>">
                <input type="hidden" name="log_page" value="1">
                <label>
                    <span>家庭组</span>
                    <select name="log_group">
                        <option value="">全部家庭组</option>
                        <?php foreach ($familyGroups as $group): ?>
                            <option value="<?= e((string) $group['group_name']) ?>" <?= hash_equals((string) $group['group_name'], $logGroup) ? 'selected' : '' ?>>
                                <?= e(admin_family_group_label($group)) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>用户</span>
                    <select name="log_user_id">
                        <option value="0">全部用户</option>
                        <?php foreach ($allUsersForFilter as $filterUser): ?>
                            <?php $filterLabel = trim((string) $filterUser['display_name']) !== '' ? (string) $filterUser['display_name'] : (string) $filterUser['username']; ?>
                            <option value="<?= (int) $filterUser['id'] ?>" <?= (int) $filterUser['id'] === (int) $logUserId ? 'selected' : '' ?>>
                                <?= e($filterLabel) ?> / <?= e((string) $filterUser['username']) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>类型</span>
                    <select name="log_type">
                        <option value="">全部类型</option>
                        <option value="session" <?= $logType === 'session' ? 'selected' : '' ?>>上线/下线</option>
                        <?php foreach ($logTypes as $type): ?>
                            <option value="<?= e($type) ?>" <?= hash_equals($type, $logType) ? 'selected' : '' ?>><?= e($type) ?></option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>每页数量</span>
                    <select name="log_per_page">
                        <option value="20" <?= $logPerPage === 20 ? 'selected' : '' ?>>20 条</option>
                        <option value="50" <?= $logPerPage === 50 ? 'selected' : '' ?>>50 条</option>
                        <option value="100" <?= $logPerPage === 100 ? 'selected' : '' ?>>100 条</option>
                    </select>
                </label>
                <button class="secondary" type="submit">筛选</button>
                <a class="button secondary" href="<?= e(admin_query(['log_group' => null, 'log_user_id' => null, 'log_type' => null, 'log_page' => null])) ?>">刷新</a>
            </form>
            <?php if (!$recentUserLogs): ?>
                <div class="muted">暂无用户日志。</div>
            <?php else: ?>
                <div class="compact-list log-list">
                    <?php foreach ($recentUserLogs as $log): ?>
                        <?php $logName = trim((string) ($log['display_name'] ?? '')) !== '' ? (string) $log['display_name'] : (string) ($log['username'] ?? '系统'); ?>
                        <div class="compact-row">
                            <strong><?= e($logName) ?></strong>
                            <span class="badge"><?= e((string) $log['event_type']) ?></span>
                            <span class="muted"><?= e((string) $log['group_name']) ?></span>
                            <span class="muted"><?= e((string) $log['message']) ?></span>
                            <time class="muted"><?= e(format_datetime((string) $log['created_at'])) ?></time>
                        </div>
                    <?php endforeach; ?>
                </div>
            <?php endif; ?>
            <div class="pager-actions">
                <?php $logPrevPage = max(1, (int) $logPage - 1); ?>
                <?php $logNextPage = min((int) $logTotalPages, (int) $logPage + 1); ?>
                <a class="button secondary <?= $logPage <= 1 ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['log_page' => $logPrevPage, 'log_per_page' => $logPerPage])) ?>">上一页</a>
                <a class="button secondary <?= $logPage >= $logTotalPages ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['log_page' => $logNextPage, 'log_per_page' => $logPerPage])) ?>">下一页</a>
            </div>
        </section>

        <section class="panel account-panel">
            <div class="section-heading">
                <h2>工单管理</h2>
                <span class="muted">最近 80 个会话</span>
            </div>
            <?php if (!$supportTickets): ?>
                <div class="muted">暂无工单。</div>
            <?php else: ?>
                <div class="ticket-admin-list">
                    <?php foreach ($supportTickets as $ticket): ?>
                        <?php
                        $ticketName = trim((string) $ticket['display_name']) !== '' ? (string) $ticket['display_name'] : (string) $ticket['username'];
                        $ticketMessages = $ticketMessagesByTicket[(int) $ticket['id']] ?? [];
                        ?>
                        <article class="ticket-admin-row">
                            <div class="ticket-admin-summary">
                                <strong><?= e((string) $ticket['subject']) ?></strong>
                                <span class="badge"><?= (string) $ticket['status'] === 'closed' ? '已关闭' : '处理中' ?></span>
                                <span class="muted"><?= e($ticketName) ?> / <?= e((string) $ticket['group_name']) ?></span>
                                <span class="muted"><?= e(format_datetime((string) $ticket['updated_at'])) ?></span>
                            </div>
                            <details class="row-more">
                                <summary>打开会话</summary>
                                <div class="ticket-admin-thread">
                                    <?php foreach ($ticketMessages as $ticketMessage): ?>
                                        <div class="ticket-admin-message <?= e((string) $ticketMessage['sender_type']) ?>">
                                            <strong><?= (string) $ticketMessage['sender_type'] === 'admin' ? '后台' : e($ticketName) ?> · <?= e(format_datetime((string) $ticketMessage['created_at'])) ?></strong>
                                            <p><?= nl2br(e((string) $ticketMessage['message'])) ?></p>
                                        </div>
                                    <?php endforeach; ?>
                                </div>
                                <form class="ticket-admin-reply" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="reply_ticket">
                                    <input type="hidden" name="ticket_id" value="<?= (int) $ticket['id'] ?>">
                                    <textarea name="reply" rows="4" placeholder="回复内容" required></textarea>
                                    <button class="small" type="submit">发送回复</button>
                                </form>
                                <form class="inline-form" method="post">
                                    <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                    <input type="hidden" name="action" value="update_ticket_status">
                                    <input type="hidden" name="ticket_id" value="<?= (int) $ticket['id'] ?>">
                                    <input type="hidden" name="status" value="<?= (string) $ticket['status'] === 'closed' ? 'open' : 'closed' ?>">
                                    <button class="small secondary" type="submit"><?= (string) $ticket['status'] === 'closed' ? '重新打开' : '关闭工单' ?></button>
                                </form>
                            </details>
                        </article>
                    <?php endforeach; ?>
                </div>
            <?php endif; ?>
        </section>

        <section class="panel account-panel">
            <div class="section-heading">
                <h2>历史定位记录</h2>
                <a class="button secondary" href="<?= e(admin_query(['history_page' => 1])) ?>">刷新</a>
            </div>
            <form class="pager-form history-filter-form" method="get">
                <input type="hidden" name="user_page" value="<?= (int) $userPage ?>">
                <input type="hidden" name="user_per_page" value="<?= (int) $userPerPage ?>">
                <label>
                    <span>家庭组</span>
                    <select name="history_group">
                        <option value="">全部家庭组</option>
                        <?php foreach ($familyGroups as $group): ?>
                            <option value="<?= e((string) $group['group_name']) ?>" <?= hash_equals((string) $group['group_name'], $historyGroup) ? 'selected' : '' ?>>
                                <?= e(admin_family_group_label($group)) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>成员</span>
                    <select name="history_user_id">
                        <option value="0">全部成员</option>
                        <?php foreach ($allUsersForFilter as $filterUser): ?>
                            <?php $filterLabel = trim((string) $filterUser['display_name']) !== '' ? (string) $filterUser['display_name'] : (string) $filterUser['username']; ?>
                            <option value="<?= (int) $filterUser['id'] ?>" <?= (int) $filterUser['id'] === (int) $historyUserId ? 'selected' : '' ?>>
                                <?= e($filterLabel) ?> / <?= e((string) $filterUser['username']) ?>
                            </option>
                        <?php endforeach; ?>
                    </select>
                </label>
                <label>
                    <span>每页数量</span>
                    <select name="history_per_page">
                        <option value="20" <?= $historyPerPage === 20 ? 'selected' : '' ?>>20 条</option>
                        <option value="50" <?= $historyPerPage === 50 ? 'selected' : '' ?>>50 条</option>
                        <option value="100" <?= $historyPerPage === 100 ? 'selected' : '' ?>>100 条</option>
                    </select>
                </label>
                <input type="hidden" name="history_page" value="1">
                <button class="secondary" type="submit">筛选</button>
                <span class="muted">第 <?= (int) $historyPage ?> / <?= (int) $historyTotalPages ?> 页，共 <?= (int) $historyTotal ?> 条</span>
            </form>

            <div class="history-record-list">
                <?php if (!$historyLocations): ?>
                    <div class="history-record-empty muted">暂无历史定位记录。</div>
                <?php endif; ?>
                <?php foreach ($historyLocations as $location): ?>
                    <?php
                    $displayName = trim((string) $location['display_name']) !== '' ? (string) $location['display_name'] : (string) $location['username'];
                    $addressSummary = location_address_summary((string) ($location['address_diagnostics'] ?? ''));
                    $diagnosticSources = location_diagnostics_sources((string) ($location['address_diagnostics'] ?? ''));
                    $coordinateText = (string) $location['latitude'] . ', ' . (string) $location['longitude'];
                    $altitudeText = $location['altitude'] === null ? '' : (string) round((float) $location['altitude']) . 'm';
                    $accuracyText = $location['accuracy'] === null ? '未知' : (string) round((float) $location['accuracy']) . 'm';
                    $headingText = $location['heading'] === null ? '' : (string) round((float) $location['heading']) . '°';
                    $speedText = $location['speed'] === null ? '' : number_format((float) $location['speed'], 2) . ' m/s';
                    $statusText = ((int) $location['address_mismatch'] === 1) ? '位置信息不一致' : '正常';
                    $locationMeta = !empty($location['location_meta']) ? json_decode((string) $location['location_meta'], true) : [];
                    $locationMeta = is_array($locationMeta) ? $locationMeta : [];
                    $isP2pLocation = (string) ($location['encryption_mode'] ?? '') === 'p2p-v1';
                    ?>
                    <article class="history-record-card">
                        <div class="history-record-head">
                            <div class="history-record-main">
                                <div class="history-person-line">
                                    <strong><?= e((string) $location['username']) ?></strong>
                                    <span><?= e($displayName) ?></span>
                                </div>
                                <div class="history-meta-line">
                                    <span class="history-group-pill"><?= e((string) $location['group_name']) ?></span>
                                    <time class="history-time"><?= e(format_datetime((string) $location['created_at'])) ?></time>
                                </div>
                            </div>
                            <form class="inline-form history-delete-form history-card-delete-form" method="post" data-confirm="确认删除这条历史定位记录？">
                                <input type="hidden" name="csrf_token" value="<?= e(csrf_token()) ?>">
                                <input type="hidden" name="action" value="delete_location">
                                <input type="hidden" name="location_id" value="<?= (int) $location['id'] ?>">
                                <button class="small danger compact-danger" type="submit">删除</button>
                            </form>
                        </div>
                        <div class="history-record-actions">
                            <?php if ($isP2pLocation): ?>
                            <div class="history-encrypted-note">p2p定位记录</div>
                            <?php else: ?>
                            <details class="row-more history-more">
                                <summary>更多信息</summary>
                                <div class="history-detail-grid">
                                    <div class="history-detail-item">
                                        <span>账号类型</span>
                                        <strong><?= e(role_label((string) $location['role'])) ?></strong>
                                    </div>
                                    <div class="history-detail-item">
                                        <span>状态</span>
                                        <strong><?= e($statusText) ?></strong>
                                    </div>
                                    <div class="history-detail-item full">
                                        <span>坐标</span>
                                        <strong><?= e($coordinateText) ?></strong>
                                    </div>
                                    <?php if ($altitudeText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>高度</span>
                                        <strong><?= e($altitudeText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <div class="history-detail-item">
                                        <span>精度</span>
                                        <strong><?= e($accuracyText) ?></strong>
                                    </div>
                                    <?php if ($headingText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>方向</span>
                                        <strong><?= e($headingText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if ($speedText !== ''): ?>
                                    <div class="history-detail-item">
                                        <span>速度</span>
                                        <strong><?= e($speedText) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if (!empty($locationMeta['provider'])): ?>
                                    <div class="history-detail-item">
                                        <span>定位来源</span>
                                        <strong><?= e((string) $locationMeta['provider']) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php if (!empty($locationMeta['location_time'])): ?>
                                    <div class="history-detail-item">
                                        <span>定位时间戳</span>
                                        <strong><?= e((string) $locationMeta['location_time']) ?></strong>
                                    </div>
                                    <?php endif; ?>
                                    <?php foreach ([
                                        'vertical_accuracy' => '垂直精度',
                                        'bearing_accuracy' => '方向精度',
                                        'speed_accuracy' => '速度精度',
                                    ] as $metaKey => $metaLabel): ?>
                                        <?php if (isset($locationMeta[$metaKey]) && is_numeric($locationMeta[$metaKey])): ?>
                                        <div class="history-detail-item">
                                            <span><?= e($metaLabel) ?></span>
                                            <strong><?= e((string) round((float) $locationMeta[$metaKey], 2)) ?></strong>
                                        </div>
                                        <?php endif; ?>
                                    <?php endforeach; ?>
                                    <div class="history-detail-item full">
                                        <span>地址</span>
                                        <strong><?= $addressSummary === '' ? '暂无' : e($addressSummary) ?></strong>
                                    </div>
                                    <?php foreach ($diagnosticSources as $source): ?>
                                    <div class="history-detail-item full">
                                        <span><?= e((string) $source['label']) ?></span>
                                        <strong><?= e((string) $source['address']) ?> / 城市：<?= e((string) $source['city']) ?> / 来源：<?= e((string) $source['provider']) ?></strong>
                                        <?php if (!empty($source['response_rows']) && is_array($source['response_rows'])): ?>
                                        <table class="history-source-table">
                                            <tbody>
                                                <?php foreach ($source['response_rows'] as $row): ?>
                                                <tr>
                                                    <th><?= e((string) ($row['label'] ?? '响应')) ?></th>
                                                    <td><?= e((string) ($row['value'] ?? '')) ?></td>
                                                </tr>
                                                <?php endforeach; ?>
                                            </tbody>
                                        </table>
                                        <?php endif; ?>
                                    </div>
                                    <?php endforeach; ?>
                                </div>
                            </details>
                            <?php endif; ?>
                        </div>
                    </article>
                <?php endforeach; ?>
            </div>

            <div class="pager-actions">
                <?php $historyPrevPage = max(1, (int) $historyPage - 1); ?>
                <?php $historyNextPage = min((int) $historyTotalPages, (int) $historyPage + 1); ?>
                <a class="button secondary <?= $historyPage <= 1 ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['history_page' => $historyPrevPage])) ?>">上一页</a>
                <a class="button secondary <?= $historyPage >= $historyTotalPages ? 'disabled-link' : '' ?>" href="<?= e(admin_query(['history_page' => $historyNextPage])) ?>">下一页</a>
            </div>
        </section>
    </main>
    <script src="/<?= e(admin_url_path()) ?>assets/admin-theme.js?v=<?= (int) filemtime(__DIR__ . '/assets/admin-theme.js') ?>" defer></script>
    <script src="/assets/popup-select.js" defer></script>
</body>
</html>

