if (el.settingsButton) {
    el.settingsButton.addEventListener('click', openSettingsPopup);
}
if (el.announcementButton) {
    el.announcementButton.addEventListener('click', showAnnouncementPopup);
}
if (el.ticketButton) {
    el.ticketButton.addEventListener('click', openTicketsPopup);
}
if (el.logoutButton) {
    el.logoutButton.addEventListener('click', async () => {
        try {
            await api('logout', { method: 'POST' });
        } finally {
            showLogin();
        }
    });
}

el.reportButton.addEventListener('click', manualReport);
if (el.crossGroupSyncButton) {
    el.crossGroupSyncButton.addEventListener('click', openCrossGroupSyncPopup);
}
el.continuousReportButton.addEventListener('click', toggleGuardianContinuousReport);
el.groupSelect.addEventListener('change', () => applySelectedGroup(el.groupSelect.value, true));
el.historyRefreshButton.addEventListener('click', () => {
    el.historyRefreshButton.disabled = true;
    window.setTimeout(() => {
        Promise.resolve(refreshHistory()).finally(() => {
            el.historyRefreshButton.disabled = false;
        });
    }, 0);
});
el.historyUserFilter.addEventListener('change', changeHistoryUserFilter);
el.historyPageSize.addEventListener('change', changeHistoryPageSize);
el.historyMapPageSize.addEventListener('change', changeHistoryMapPageSize);
el.historyPrevButton.addEventListener('click', () => changeHistoryPage(-1));
el.historyNextButton.addEventListener('click', () => changeHistoryPage(1));
window.addEventListener('online', () => {
    refreshLocations();
    scheduleIdleTask(refreshHistory, 2500);
});
window.addEventListener('visibilitychange', () => {
    if (document.hidden) {
        state.backgroundedAt = Date.now();
        if (state.foregroundRefreshTimer) {
            window.clearTimeout(state.foregroundRefreshTimer);
            state.foregroundRefreshTimer = null;
        }
        stopRefresh();
        return;
    }

    if (!document.hidden && state.user) {
        const wasBackgroundedMs = state.backgroundedAt > 0 ? Date.now() - state.backgroundedAt : 0;
        startRefresh();
        if (state.foregroundRefreshTimer) {
            window.clearTimeout(state.foregroundRefreshTimer);
        }
        state.foregroundRefreshTimer = window.setTimeout(() => {
            state.foregroundRefreshTimer = null;
            scheduleIdleTask(refreshLocations, 1200);
            scheduleIdleTask(refreshHistory, 4500);
            scheduleIdleTask(uploadEnvironmentDataIfAllowed, 9000);
        }, wasBackgroundedMs >= 5000 ? 1200 : 300);
        if (typeof window.AppWebVersion?.schedule === 'function') {
            window.AppWebVersion.schedule(5000);
        }
        if (wasBackgroundedMs >= 5000) {
            scheduleIdleTask(sendHeartbeat, 2500);
        }
    }
});

initThemeMode();
installAntiDebugGuards();
if (typeof startWebVersionWatcher === 'function') {
    startWebVersionWatcher();
}

(async function boot() {
    try {
        const payload = await api('me');
        setReportInterval(payload.report_interval_seconds);
        if (payload.user) {
            showMain(payload.user);
        } else {
            showLogin();
        }
    } catch (error) {
        showLogin(error.message);
    }
})();
