package handlers

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"
)

type HeartbeatHandler struct {
	db    *sql.DB
	scope scopedHandler
	users repositories.UserRepository
}

func NewHeartbeatHandler(db *sql.DB, sessions session.Reader) HeartbeatHandler {
	return HeartbeatHandler{
		db:    db,
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
	}
}

const (
	heartbeatLogWindow        = 15 * time.Minute
	maxHeartbeatLogsPerUser   = 7 * 24 * 4
	maxHeartbeatLogsPerTenant = 5000
	maxHeartbeatCleanupBatch  = 500
)

type heartbeatRequest struct {
	GroupName string `json:"group_name"`
}

func (handler HeartbeatHandler) Touch(w http.ResponseWriter, r *http.Request) {
	var req heartbeatRequest
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	scope, _, err := handler.scope.requireScope(r, selectedGroupName(r, req.GroupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	groupName := ""
	if scope.Membership != nil {
		groupName = scope.Membership.GroupName
	}
	if err := handler.users.TouchPresence(r.Context(), scope.User.ID, groupName, r.UserAgent(), httpx.ClientIP(r)); err != nil {
		httpx.Error(w, err)
		return
	}
	_, _ = handler.recordLogIfDue(r.Context(), scope.User.ID, groupName, httpx.ClientIP(r), r.UserAgent(), time.Now())
	httpx.OK(w, map[string]any{
		"ok":          true,
		"server_time": time.Now().Format("2006-01-02 15:04:05"),
	})
}

func (handler HeartbeatHandler) recordLogIfDue(ctx context.Context, userID int64, groupName string, ip string, userAgent string, now time.Time) (bool, error) {
	tx, err := handler.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()

	if groupName != "" {
		var lockedGroupName string
		if err := tx.QueryRowContext(ctx, "SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE", groupName).Scan(&lockedGroupName); err != nil {
			return false, err
		}
	}
	var lockedUserID int64
	if err := tx.QueryRowContext(ctx, "SELECT id FROM users WHERE id = ? FOR UPDATE", userID).Scan(&lockedUserID); err != nil {
		return false, err
	}
	var existingID int64
	err = tx.QueryRowContext(ctx, `
SELECT id
FROM user_logs
WHERE user_id = ? AND group_name = ? AND event_type = 'online' AND message = '用户心跳' AND created_at >= ?
ORDER BY created_at DESC, id DESC
LIMIT 1`, userID, groupName, now.Add(-heartbeatLogWindow)).Scan(&existingID)
	if err != nil && err != sql.ErrNoRows {
		return false, err
	}
	if err == nil {
		return false, tx.Commit()
	}
	if _, err := tx.ExecContext(ctx, `
INSERT INTO user_logs (user_id, group_name, event_type, message, meta_json, ip, user_agent)
VALUES (?, ?, 'online', '用户心跳', NULL, ?, ?)`,
		userID,
		truncateGroupName(groupName),
		truncateString(ip, 45),
		truncateString(userAgent, 255),
	); err != nil {
		return false, err
	}
	if err := pruneHeartbeatLogsTx(ctx, tx, "user_id", userID, maxHeartbeatLogsPerUser); err != nil {
		return false, err
	}
	if groupName != "" {
		if err := pruneHeartbeatLogsTx(ctx, tx, "group_name", truncateGroupName(groupName), maxHeartbeatLogsPerTenant); err != nil {
			return false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return true, nil
}

func pruneHeartbeatLogsTx(ctx context.Context, tx *sql.Tx, column string, identity any, limit int) error {
	var selectQuery string
	var deleteQuery string
	switch column {
	case "user_id":
		selectQuery = "SELECT id FROM user_logs WHERE user_id = ? AND event_type = 'online' AND message = '用户心跳' ORDER BY id DESC LIMIT 1 OFFSET ?"
		deleteQuery = "DELETE FROM user_logs WHERE user_id = ? AND event_type = 'online' AND message = '用户心跳' AND id < ? ORDER BY id ASC LIMIT ?"
	case "group_name":
		selectQuery = "SELECT id FROM user_logs WHERE group_name = ? AND event_type = 'online' AND message = '用户心跳' ORDER BY id DESC LIMIT 1 OFFSET ?"
		deleteQuery = "DELETE FROM user_logs WHERE group_name = ? AND event_type = 'online' AND message = '用户心跳' AND id < ? ORDER BY id ASC LIMIT ?"
	default:
		return errors.New("invalid heartbeat retention scope")
	}
	var boundaryID int64
	err := tx.QueryRowContext(ctx, selectQuery, identity, limit-1).Scan(&boundaryID)
	if err == sql.ErrNoRows {
		return nil
	}
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, deleteQuery, identity, boundaryID, maxHeartbeatCleanupBatch)
	return err
}
