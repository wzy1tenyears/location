package app

import (
	"database/sql"
	"net/http"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/handlers"
	"familylocation/location-v3/internal/middleware"
	"familylocation/location-v3/internal/session"
)

func NewRouter(cfg config.Config, db *sql.DB) http.Handler {
	mux := http.NewServeMux()

	sessions := session.Reader{CookieName: cfg.App.SessionCookieName, SavePath: cfg.App.PHPSessionDir}
	updates := handlers.NewUpdateHandler(cfg, sessions)
	challenge := handlers.NewChallengeHandler(cfg, db)
	login := handlers.NewLoginHandler(cfg, db, sessions)
	register := handlers.NewRegisterHandler(cfg, db, sessions)
	groupsHandler := handlers.NewGroupsHandler(db, sessions)
	reportLocation := handlers.NewReportLocationHandler(cfg, db, sessions)
	tickets := handlers.NewTicketsHandler(db, sessions)
	p2p := handlers.NewP2PHandler(db, sessions)
	webviews := handlers.NewWebViewHandler(cfg)
	adminManage := handlers.NewAdminManageHandler(db, sessions)
	adminSummary := handlers.NewAdminSummaryHandler(cfg, db, sessions)
	announcements := handlers.NewAnnouncementHandler(db, sessions)
	invites := handlers.NewInviteHandler(db)
	me := handlers.NewMeHandler(db, sessions)
	settings := handlers.NewSettingsHandler(db, sessions)
	locations := handlers.NewLocationsHandler(cfg, db, sessions)
	history := handlers.NewHistoryHandler(cfg, db, sessions)
	legal := handlers.NewLegalDocumentsHandler()
	sessionHandler := handlers.NewSessionHandler(db, sessions)
	heartbeat := handlers.NewHeartbeatHandler(db, sessions)
	environment := handlers.NewEnvironmentHandler(db, sessions)
	diagnostics := handlers.NewDiagnosticHandler(cfg, db, sessions)
	downloads := handlers.NewDownloadHandler(cfg, sessions)

	appOnly := middleware.RequireAppUserAgent(cfg.App)
	mux.Handle("/api/app_challenge.php", http.HandlerFunc(challenge.ServeHTTP))
	mux.Handle("GET /api/app_update.php", middleware.Chain(http.HandlerFunc(updates.AppUpdate), appOnly))
	mux.Handle("POST /api/login.php", middleware.Chain(http.HandlerFunc(login.Login), appOnly))
	mux.Handle("POST /api/register.php", middleware.Chain(http.HandlerFunc(register.Register), appOnly))
	mux.Handle("GET /api/admin_app_update.php", middleware.Chain(http.HandlerFunc(updates.AdminAppUpdate), appOnly))
	mux.Handle("GET /api/announcement.php", middleware.Chain(http.HandlerFunc(announcements.Latest), appOnly))
	mux.Handle("GET /api/invite_check.php", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("POST /api/invite_check.php", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("GET /api/me.php", middleware.Chain(http.HandlerFunc(me.Show), appOnly))
	mux.Handle("GET /api/settings.php", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("POST /api/settings.php", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("GET /api/locations.php", middleware.Chain(http.HandlerFunc(locations.Latest), appOnly))
	mux.Handle("POST /api/history.php", middleware.Chain(http.HandlerFunc(history.List), appOnly))
	mux.Handle("GET /api/legal_documents.php", middleware.Chain(http.HandlerFunc(legal.Show), appOnly))
	mux.Handle("POST /api/groups.php", middleware.Chain(http.HandlerFunc(groupsHandler.Handle), appOnly))
	mux.Handle("POST /api/report_location.php", middleware.Chain(http.HandlerFunc(reportLocation.Report), appOnly))
	mux.Handle("GET /api/tickets.php", middleware.Chain(http.HandlerFunc(tickets.Handle), appOnly))
	mux.Handle("POST /api/tickets.php", middleware.Chain(http.HandlerFunc(tickets.Handle), appOnly))
	mux.Handle("POST /api/p2p_crypto.php", middleware.Chain(http.HandlerFunc(p2p.Handle), appOnly))
	mux.Handle("GET /api/history_map_webview.php", middleware.Chain(http.HandlerFunc(webviews.HistoryMap), appOnly))
	mux.Handle("GET /api/amap_reverse_webview.php", middleware.Chain(http.HandlerFunc(webviews.AMapReverse), appOnly))
	mux.Handle("GET /api/webrtc_probe_webview.php", middleware.Chain(http.HandlerFunc(webviews.WebRTCProbe), appOnly))
	mux.Handle("POST /api/admin_manage.php", middleware.Chain(http.HandlerFunc(adminManage.Handle), appOnly))
	mux.Handle("GET /api/admin_summary.php", middleware.Chain(http.HandlerFunc(adminSummary.Summary), appOnly))
	mux.Handle("POST /api/logout.php", middleware.Chain(http.HandlerFunc(sessionHandler.Logout), appOnly))
	mux.Handle("GET /api/logout.php", middleware.Chain(http.HandlerFunc(sessionHandler.Logout), appOnly))
	mux.Handle("POST /api/heartbeat.php", middleware.Chain(http.HandlerFunc(heartbeat.Touch), appOnly))
	mux.Handle("POST /api/environment_report.php", middleware.Chain(http.HandlerFunc(environment.EnvironmentReport), appOnly))
	mux.Handle("POST /api/device_report.php", middleware.Chain(http.HandlerFunc(environment.DeviceReport), appOnly))
	mux.Handle("GET /api/admin_apk.php", http.HandlerFunc(downloads.AdminAPK))
	mux.Handle("GET /api/geo_aliases.php", middleware.Chain(http.HandlerFunc(diagnostics.GeoAliases), appOnly))
	mux.Handle("GET /api/ip_probe.php", middleware.Chain(http.HandlerFunc(diagnostics.IPProbe), appOnly))
	mux.Handle("POST /api/ip_geo.php", middleware.Chain(http.HandlerFunc(diagnostics.IPGeo), appOnly))
	mux.Handle("POST /api/ipinfo_lite.php", middleware.Chain(http.HandlerFunc(diagnostics.IPInfoLite), appOnly))
	mux.Handle("GET /api/cloudflare_location.php", middleware.Chain(http.HandlerFunc(diagnostics.CloudflareLocation), appOnly))

	return middleware.Chain(mux, middleware.Recover, middleware.AccessLog)
}
