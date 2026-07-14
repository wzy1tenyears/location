package app

import (
	"database/sql"
	"net/http"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/handlers"
	"familylocation/location-v3/internal/middleware"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"
)

func NewRouter(cfg config.Config, db *sql.DB) http.Handler {
	mux := http.NewServeMux()

	sessionRepo := repositories.NewSessionRepository(db)
	sessions := session.Reader{CookieName: cfg.App.SessionCookieName, Repo: sessionRepo}
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
	settings := handlers.NewSettingsHandler(db, sessions, cfg.App.SessionLifetime)
	locations := handlers.NewLocationsHandler(cfg, db, sessions)
	history := handlers.NewHistoryHandler(cfg, db, sessions)
	legal := handlers.NewLegalDocumentsHandler()
	sessionHandler := handlers.NewSessionHandler(db, sessions)
	heartbeat := handlers.NewHeartbeatHandler(db, sessions)
	environment := handlers.NewEnvironmentHandler(db, sessions)
	diagnostics := handlers.NewDiagnosticHandler(cfg, db, sessions)
	downloads := handlers.NewDownloadHandler(cfg, sessions)
	shares := handlers.NewShareHandler(cfg, db, sessions)

	appOnly := middleware.RequireAppUserAgent(cfg.App)
	mux.Handle("/api/app-challenge", http.HandlerFunc(challenge.ServeHTTP))
	mux.Handle("GET /api/app-update", middleware.Chain(http.HandlerFunc(updates.AppUpdate), appOnly))
	mux.Handle("POST /api/login", middleware.Chain(http.HandlerFunc(login.Login), appOnly))
	mux.Handle("POST /api/register", middleware.Chain(http.HandlerFunc(register.Register), appOnly))
	mux.Handle("GET /api/admin-app-update", middleware.Chain(http.HandlerFunc(updates.AdminAppUpdate), appOnly))
	mux.Handle("GET /api/announcement", middleware.Chain(http.HandlerFunc(announcements.Latest), appOnly))
	mux.Handle("GET /api/invite-check", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("POST /api/invite-check", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("GET /api/me", middleware.Chain(http.HandlerFunc(me.Show), appOnly))
	mux.Handle("GET /api/settings", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("POST /api/settings", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("GET /api/locations", middleware.Chain(http.HandlerFunc(locations.Latest), appOnly))
	mux.Handle("GET /api/history", middleware.Chain(http.HandlerFunc(history.List), appOnly))
	mux.Handle("POST /api/history", middleware.Chain(http.HandlerFunc(history.List), appOnly))
	mux.Handle("GET /api/legal-documents", middleware.Chain(http.HandlerFunc(legal.Show), appOnly))
	mux.Handle("POST /api/groups", middleware.Chain(http.HandlerFunc(groupsHandler.Handle), appOnly))
	mux.Handle("POST /api/report-location", middleware.Chain(http.HandlerFunc(reportLocation.Report), appOnly))
	mux.Handle("GET /api/tickets", middleware.Chain(http.HandlerFunc(tickets.Handle), appOnly))
	mux.Handle("POST /api/tickets", middleware.Chain(http.HandlerFunc(tickets.Handle), appOnly))
	mux.Handle("POST /api/p2p-crypto", middleware.Chain(http.HandlerFunc(p2p.Handle), appOnly))
	mux.Handle("GET /api/history-map", middleware.Chain(http.HandlerFunc(webviews.HistoryMap), appOnly))
	mux.Handle("GET /api/amap-reverse", middleware.Chain(http.HandlerFunc(webviews.AMapReverse), appOnly))
	mux.Handle("GET /api/webrtc-probe", middleware.Chain(http.HandlerFunc(webviews.WebRTCProbe), appOnly))
	mux.Handle("POST /api/admin/manage", middleware.Chain(http.HandlerFunc(adminManage.Handle), appOnly))
	mux.Handle("GET /api/admin/summary", middleware.Chain(http.HandlerFunc(adminSummary.Summary), appOnly))
	mux.Handle("POST /api/logout", middleware.Chain(http.HandlerFunc(sessionHandler.Logout), appOnly))
	mux.Handle("GET /api/logout", middleware.Chain(http.HandlerFunc(sessionHandler.Logout), appOnly))
	mux.Handle("POST /api/heartbeat", middleware.Chain(http.HandlerFunc(heartbeat.Touch), appOnly))
	mux.Handle("POST /api/environment-report", middleware.Chain(http.HandlerFunc(environment.EnvironmentReport), appOnly))
	mux.Handle("POST /api/device-report", middleware.Chain(http.HandlerFunc(environment.DeviceReport), appOnly))
	mux.Handle("GET /api/admin/apk", http.HandlerFunc(downloads.AdminAPK))
	mux.Handle("GET /api/geo-aliases", middleware.Chain(http.HandlerFunc(diagnostics.GeoAliases), appOnly))
	mux.Handle("GET /api/ip-probe", middleware.Chain(http.HandlerFunc(diagnostics.IPProbe), appOnly))
	mux.Handle("POST /api/ip-geo", middleware.Chain(http.HandlerFunc(diagnostics.IPGeo), appOnly))
	mux.Handle("POST /api/ipinfo-lite", middleware.Chain(http.HandlerFunc(diagnostics.IPInfoLite), appOnly))
	mux.Handle("GET /api/cloudflare-location", middleware.Chain(http.HandlerFunc(diagnostics.CloudflareLocation), appOnly))
	mux.Handle("GET /api/share", middleware.Chain(http.HandlerFunc(shares.List), appOnly))
	mux.Handle("POST /api/share", middleware.Chain(http.HandlerFunc(shares.Create), appOnly))
	mux.Handle("GET /share", http.HandlerFunc(shares.PublicPage))
	mux.Handle("POST /share", http.HandlerFunc(shares.PublicPage))
	mux.Handle("GET /share/", http.HandlerFunc(shares.PublicPage))
	mux.Handle("POST /share/", http.HandlerFunc(shares.PublicPage))

	return middleware.Chain(mux, middleware.Recover, middleware.AccessLog)
}
