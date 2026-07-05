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
	announcements := handlers.NewAnnouncementHandler(db, sessions)
	invites := handlers.NewInviteHandler(db)
	me := handlers.NewMeHandler(db, sessions)
	settings := handlers.NewSettingsHandler(db, sessions)
	locations := handlers.NewLocationsHandler(cfg, db, sessions)
	history := handlers.NewHistoryHandler(cfg, db, sessions)
	legacy, err := handlers.NewLegacyHandler(cfg.Legacy)
	if err != nil {
		panic(err)
	}

	appOnly := middleware.RequireAppUserAgent(cfg.App)
	mux.Handle("GET /api/app_update.php", middleware.Chain(http.HandlerFunc(updates.AppUpdate), appOnly))
	mux.Handle("GET /api/admin_app_update.php", middleware.Chain(http.HandlerFunc(updates.AdminAppUpdate), appOnly))
	mux.Handle("GET /api/announcement.php", middleware.Chain(http.HandlerFunc(announcements.Latest), appOnly))
	mux.Handle("GET /api/invite_check.php", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("POST /api/invite_check.php", middleware.Chain(http.HandlerFunc(invites.Check), appOnly))
	mux.Handle("GET /api/me.php", middleware.Chain(http.HandlerFunc(me.Show), appOnly))
	mux.Handle("GET /api/settings.php", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("POST /api/settings.php", middleware.Chain(http.HandlerFunc(settings.ShowOrUpdate), appOnly))
	mux.Handle("GET /api/locations.php", middleware.Chain(http.HandlerFunc(locations.Latest), appOnly))
	mux.Handle("POST /api/history.php", middleware.Chain(http.HandlerFunc(history.List), appOnly))
	mux.Handle("/api/", http.HandlerFunc(legacy.Proxy))

	return middleware.Chain(mux, middleware.Recover, middleware.AccessLog)
}

