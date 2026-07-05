package handlers

import (
	"database/sql"
	"net/http"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/services"
	"familylocation/location-v3/internal/session"
)

type LocationsHandler struct {
	cfg       config.Config
	scope     scopedHandler
	locations repositories.LocationRepository
}

func NewLocationsHandler(cfg config.Config, db *sql.DB, sessions session.Reader) LocationsHandler {
	return LocationsHandler{
		cfg:       cfg,
		scope:     newScopedHandler(db, sessions),
		locations: repositories.NewLocationRepository(db),
	}
}

func (handler LocationsHandler) Latest(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, ""))
	if err != nil {
		httpx.Error(w, err)
		return
	}

	rows, err := handler.locations.LatestForGroup(r.Context(), scope.Membership.GroupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}

	locations := make([]map[string]any, 0, len(rows))
	var mine any
	monitors := make([]map[string]any, 0)
	guardians := make([]map[string]any, 0)
	for _, row := range rows {
		payload := services.LocationPayload(row, 600)
		locations = append(locations, payload)
		if row.UserID == scope.User.ID {
			mine = payload
		}
		if services.NormalizeRole(row.Role) == "monitor" {
			monitors = append(monitors, payload)
		} else {
			guardians = append(guardians, payload)
		}
	}

	httpx.OK(w, map[string]any{
		"ok":                      true,
		"user":                    services.PublicUserPayloadForGroups(*scope.User, scope.Groups, scope.Membership),
		"selected_group":          services.GroupPayload(*scope.Membership),
		"locations":               locations,
		"mine":                    mine,
		"monitors":                monitors,
		"guardians":               guardians,
		"report_interval_seconds": services.NormalizeReportIntervalSeconds(scope.User.ReportIntervalSeconds),
		"server_time":             time.Now().Format("2006-01-02 15:04:05"),
	})
}

