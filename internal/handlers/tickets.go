package handlers

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/repositories"
	"familylocation/location-v3/internal/session"
)

type TicketsHandler struct {
	db    *sql.DB
	scope scopedHandler
	users repositories.UserRepository
	rates repositories.RateLimitRepository
}

func NewTicketsHandler(db *sql.DB, sessions session.Reader) TicketsHandler {
	return TicketsHandler{
		db:    db,
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
		rates: repositories.NewRateLimitRepository(db),
	}
}

const (
	ticketQueryMaxHits           = 120
	ticketQueryWindow            = 5 * time.Minute
	ticketCreateMaxHits          = 10
	ticketCreateWindow           = 24 * time.Hour
	ticketReplyMaxHits           = 60
	ticketReplyWindow            = time.Hour
	maxOpenTicketsPerUser        = 10
	maxOpenTicketsPerTenant      = 100
	maxTicketCreatesPerUserDay   = 10
	maxTicketCreatesPerTenantDay = 100
	maxTicketRepliesPerUserDay   = 100
	maxTicketRepliesPerTenantDay = 1000
	maxMessagesPerTicket         = 200
	maxTicketMessagesPerResponse = 200
)

func (handler TicketsHandler) Handle(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireUser(r)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if r.Method == http.MethodGet {
		if err := handler.enforceRate(r.Context(), "support_ticket_query", scope.User.ID, ticketQueryMaxHits, ticketQueryWindow); err != nil {
			httpx.Error(w, err)
			return
		}
		handler.get(w, r, scope)
		return
	}
	var req struct {
		Action    string `json:"action"`
		GroupName string `json:"group_name"`
		Subject   string `json:"subject"`
		Message   string `json:"message"`
		TicketID  int64  `json:"ticket_id"`
	}
	if err := httpx.DecodeJSON(r, &req); err != nil {
		httpx.Error(w, err)
		return
	}
	switch strings.TrimSpace(req.Action) {
	case "create":
		if err := handler.enforceRate(r.Context(), "support_ticket_create", scope.User.ID, ticketCreateMaxHits, ticketCreateWindow); err != nil {
			httpx.Error(w, err)
			return
		}
		handler.create(w, r, scope, req.GroupName, req.Subject, req.Message)
	case "reply":
		if err := handler.enforceRate(r.Context(), "support_ticket_reply", scope.User.ID, ticketReplyMaxHits, ticketReplyWindow); err != nil {
			httpx.Error(w, err)
			return
		}
		handler.reply(w, r, scope, req.TicketID, req.Message)
	case "close":
		handler.close(w, r, scope, req.TicketID)
	default:
		httpx.Error(w, httpx.BadRequest("Unknown action."))
	}
}

func (handler TicketsHandler) get(w http.ResponseWriter, r *http.Request, scope *userScope) {
	ticketID := int64(httpx.IntQuery(r, "ticket_id", 0))
	if ticketID > 0 {
		ticket, err := handler.ticketByID(r, ticketID, scope.User.ID)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		if ticket == nil {
			httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "工单不存在。"})
			return
		}
		messages, err := handler.ticketMessages(r, ticketID)
		if err != nil {
			httpx.Error(w, err)
			return
		}
		httpx.OK(w, map[string]any{"ok": true, "ticket": ticket, "messages": messages})
		return
	}

	rows, err := handler.db.QueryContext(r.Context(), `
SELECT
	t.id, t.group_name, t.subject, t.status, t.created_at, t.updated_at,
	COALESCE(last_message.message, ''), last_message.created_at
FROM support_tickets t
LEFT JOIN support_ticket_messages last_message ON last_message.id = (
	SELECT id
	FROM support_ticket_messages
	WHERE ticket_id = t.id
	ORDER BY id DESC
	LIMIT 1
)
WHERE t.user_id = ?
ORDER BY t.updated_at DESC, t.id DESC
LIMIT 50`, scope.User.ID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer rows.Close()

	tickets := make([]map[string]any, 0)
	for rows.Next() {
		var id int64
		var groupName string
		var subject string
		var status string
		var createdAt time.Time
		var updatedAt time.Time
		var lastMessage string
		var lastMessageAt sql.NullTime
		if err := rows.Scan(&id, &groupName, &subject, &status, &createdAt, &updatedAt, &lastMessage, &lastMessageAt); err != nil {
			httpx.Error(w, err)
			return
		}
		tickets = append(tickets, ticketPayload(id, groupName, subject, status, lastMessage, lastMessageAt, createdAt, updatedAt))
	}
	if err := rows.Err(); err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{"ok": true, "tickets": tickets})
}

func (handler TicketsHandler) create(w http.ResponseWriter, r *http.Request, scope *userScope, groupName string, subject string, message string) {
	subject = truncateString(subject, 120)
	message = truncateString(message, 2000)
	if subject == "" || message == "" {
		httpx.Error(w, httpx.Unprocessable("请填写标题和内容。"))
		return
	}
	ticketGroupName := ""
	membership, err := handler.scope.groups.MembershipForUser(r.Context(), scope.User.ID, truncateGroupName(groupName))
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if membership != nil {
		ticketGroupName = membership.GroupName
	} else if len(scope.Groups) > 0 {
		// Invalid or stale client group names must not opt the write out of the
		// tenant quota. Fall back to the server-loaded primary membership.
		ticketGroupName = scope.Groups[0].GroupName
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	if err := lockTicketQuotaScopeTx(r.Context(), tx, scope.User.ID, ticketGroupName); err != nil {
		httpx.Error(w, err)
		return
	}
	allowed, err := ticketCreateWithinQuotaTx(r.Context(), tx, scope.User.ID, ticketGroupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "工单提交已达上限，请稍后再试。"})
		return
	}
	allowed, err = ticketInitialMessageWithinQuotaTx(r.Context(), tx, scope.User.ID, ticketGroupName)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "工单消息已达上限，请稍后再试。"})
		return
	}
	result, err := tx.ExecContext(r.Context(), "INSERT INTO support_tickets (user_id, group_name, subject) VALUES (?, ?, ?)", scope.User.ID, ticketGroupName, subject)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	ticketID, _ := result.LastInsertId()
	if _, err := tx.ExecContext(r.Context(), "INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'user', ?)", ticketID, message); err != nil {
		httpx.Error(w, err)
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, ticketGroupName, "ticket_create", subject, nil, httpx.ClientIP(r), r.UserAgent())
	httpx.OK(w, map[string]any{"ok": true, "ticket_id": ticketID})
}

func (handler TicketsHandler) reply(w http.ResponseWriter, r *http.Request, scope *userScope, ticketID int64, message string) {
	message = truncateString(message, 2000)
	if ticketID <= 0 || message == "" {
		httpx.Error(w, httpx.Unprocessable("回复内容不能为空。"))
		return
	}
	ticket, err := handler.ticketByID(r, ticketID, scope.User.ID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if ticket == nil {
		httpx.Error(w, httpx.APIError{Status: http.StatusNotFound, Message: "工单不存在。"})
		return
	}
	if ticket["status"] == "closed" {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "工单已关闭。"})
		return
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	ticketGroupName, _ := ticket["group_name"].(string)
	ticketGroupName = activeTicketQuotaGroup(scope, ticketGroupName)
	if err := lockTicketQuotaScopeTx(r.Context(), tx, scope.User.ID, ticketGroupName); err != nil {
		httpx.Error(w, err)
		return
	}
	var lockedStatus string
	if err := tx.QueryRowContext(r.Context(), "SELECT status FROM support_tickets WHERE id = ? AND user_id = ? FOR UPDATE", ticketID, scope.User.ID).Scan(&lockedStatus); err != nil {
		httpx.Error(w, err)
		return
	}
	if lockedStatus == "closed" {
		httpx.Error(w, httpx.APIError{Status: http.StatusConflict, Message: "工单已关闭。"})
		return
	}
	allowed, err := ticketReplyWithinQuotaTx(r.Context(), tx, scope.User.ID, ticketGroupName, ticketID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if !allowed {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "工单回复已达上限，请稍后再试。"})
		return
	}
	if _, err := tx.ExecContext(r.Context(), "INSERT INTO support_ticket_messages (ticket_id, sender_type, message) VALUES (?, 'user', ?)", ticketID, message); err != nil {
		httpx.Error(w, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), "UPDATE support_tickets SET updated_at = NOW() WHERE id = ?", ticketID); err != nil {
		httpx.Error(w, err)
		return
	}
	if err := tx.Commit(); err != nil {
		httpx.Error(w, err)
		return
	}
	id := scope.User.ID
	_ = handler.users.RecordLog(r.Context(), &id, fmt.Sprint(ticket["group_name"]), "ticket_reply", fmt.Sprint(ticket["subject"]), nil, httpx.ClientIP(r), r.UserAgent())
	httpx.OK(w, map[string]any{"ok": true})
}

func (handler TicketsHandler) close(w http.ResponseWriter, r *http.Request, scope *userScope, ticketID int64) {
	_, err := handler.db.ExecContext(r.Context(), "UPDATE support_tickets SET status = 'closed' WHERE id = ? AND user_id = ?", ticketID, scope.User.ID)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	httpx.OK(w, map[string]any{"ok": true})
}

func (handler TicketsHandler) enforceRate(ctx context.Context, bucket string, userID int64, maxHits int, window time.Duration) error {
	allowed, err := handler.rates.Hit(ctx, bucket, strconv.FormatInt(userID, 10), maxHits, window)
	if err != nil {
		return err
	}
	if !allowed {
		return httpx.APIError{Status: http.StatusTooManyRequests, Message: "工单请求过于频繁，请稍后再试。"}
	}
	return nil
}

func lockTicketQuotaScopeTx(ctx context.Context, tx *sql.Tx, userID int64, groupName string) error {
	if groupName != "" {
		var lockedGroupName string
		if err := tx.QueryRowContext(ctx, "SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE", groupName).Scan(&lockedGroupName); err != nil {
			return err
		}
	}
	var lockedUserID int64
	return tx.QueryRowContext(ctx, "SELECT id FROM users WHERE id = ? FOR UPDATE", userID).Scan(&lockedUserID)
}

func activeTicketQuotaGroup(scope *userScope, groupName string) string {
	for _, membership := range scope.Groups {
		if membership.GroupName == groupName {
			return groupName
		}
	}
	return ""
}

func ticketCreateWithinQuotaTx(ctx context.Context, tx *sql.Tx, userID int64, groupName string) (bool, error) {
	queries := []struct {
		query string
		args  []any
		limit int
	}{
		{"SELECT COUNT(*) FROM support_tickets WHERE user_id = ? AND status = 'open'", []any{userID}, maxOpenTicketsPerUser},
		{"SELECT COUNT(*) FROM support_tickets WHERE user_id = ? AND created_at >= NOW() - INTERVAL 1 DAY", []any{userID}, maxTicketCreatesPerUserDay},
	}
	if groupName != "" {
		queries = append(queries, struct {
			query string
			args  []any
			limit int
		}{"SELECT COUNT(*) FROM support_tickets WHERE group_name = ? AND status = 'open'", []any{groupName}, maxOpenTicketsPerTenant})
		queries = append(queries, struct {
			query string
			args  []any
			limit int
		}{"SELECT COUNT(*) FROM support_tickets WHERE group_name = ? AND created_at >= NOW() - INTERVAL 1 DAY", []any{groupName}, maxTicketCreatesPerTenantDay})
	}
	return ticketCountsWithinQuotaTx(ctx, tx, queries)
}

func ticketReplyWithinQuotaTx(ctx context.Context, tx *sql.Tx, userID int64, groupName string, ticketID int64) (bool, error) {
	queries := []struct {
		query string
		args  []any
		limit int
	}{
		{`SELECT COUNT(*)
FROM support_ticket_messages m
INNER JOIN support_tickets t ON t.id = m.ticket_id
WHERE t.user_id = ? AND m.sender_type = 'user' AND m.created_at >= NOW() - INTERVAL 1 DAY`, []any{userID}, maxTicketRepliesPerUserDay},
		{"SELECT COUNT(*) FROM support_ticket_messages WHERE ticket_id = ?", []any{ticketID}, maxMessagesPerTicket},
	}
	if groupName != "" {
		queries = append(queries, struct {
			query string
			args  []any
			limit int
		}{`SELECT COUNT(*)
FROM support_ticket_messages m
INNER JOIN support_tickets t ON t.id = m.ticket_id
WHERE t.group_name = ? AND m.sender_type = 'user' AND m.created_at >= NOW() - INTERVAL 1 DAY`, []any{groupName}, maxTicketRepliesPerTenantDay})
	}
	return ticketCountsWithinQuotaTx(ctx, tx, queries)
}

func ticketInitialMessageWithinQuotaTx(ctx context.Context, tx *sql.Tx, userID int64, groupName string) (bool, error) {
	queries := []struct {
		query string
		args  []any
		limit int
	}{
		{`SELECT COUNT(*)
FROM support_ticket_messages m
INNER JOIN support_tickets t ON t.id = m.ticket_id
WHERE t.user_id = ? AND m.sender_type = 'user' AND m.created_at >= NOW() - INTERVAL 1 DAY`, []any{userID}, maxTicketRepliesPerUserDay},
	}
	if groupName != "" {
		queries = append(queries, struct {
			query string
			args  []any
			limit int
		}{`SELECT COUNT(*)
FROM support_ticket_messages m
INNER JOIN support_tickets t ON t.id = m.ticket_id
WHERE t.group_name = ? AND m.sender_type = 'user' AND m.created_at >= NOW() - INTERVAL 1 DAY`, []any{groupName}, maxTicketRepliesPerTenantDay})
	}
	return ticketCountsWithinQuotaTx(ctx, tx, queries)
}

func ticketCountsWithinQuotaTx(ctx context.Context, tx *sql.Tx, queries []struct {
	query string
	args  []any
	limit int
}) (bool, error) {
	for _, query := range queries {
		var count int
		if err := tx.QueryRowContext(ctx, query.query, query.args...).Scan(&count); err != nil {
			return false, err
		}
		if count >= query.limit {
			return false, nil
		}
	}
	return true, nil
}

func (handler TicketsHandler) ticketByID(r *http.Request, ticketID int64, userID int64) (map[string]any, error) {
	var id int64
	var groupName string
	var subject string
	var status string
	var createdAt time.Time
	var updatedAt time.Time
	err := handler.db.QueryRowContext(r.Context(), `
SELECT id, group_name, subject, status, created_at, updated_at
FROM support_tickets
WHERE id = ? AND user_id = ?
LIMIT 1`, ticketID, userID).Scan(&id, &groupName, &subject, &status, &createdAt, &updatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return ticketPayload(id, groupName, subject, status, "", sql.NullTime{}, createdAt, updatedAt), nil
}

func (handler TicketsHandler) ticketMessages(r *http.Request, ticketID int64) ([]map[string]any, error) {
	rows, err := handler.db.QueryContext(r.Context(), `
SELECT id, sender_type, message, created_at
FROM support_ticket_messages
WHERE ticket_id = ?
ORDER BY created_at DESC, id DESC
LIMIT ?`, ticketID, maxTicketMessagesPerResponse)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	messages := make([]map[string]any, 0)
	for rows.Next() {
		var id int64
		var senderType string
		var message string
		var createdAt time.Time
		if err := rows.Scan(&id, &senderType, &message, &createdAt); err != nil {
			return nil, err
		}
		senderLabel := "我"
		if senderType == "admin" {
			senderLabel = "后台"
		}
		messages = append(messages, map[string]any{
			"id":           id,
			"sender_type":  senderType,
			"sender_label": senderLabel,
			"message":      message,
			"created_at":   nowFormat(createdAt),
		})
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for left, right := 0, len(messages)-1; left < right; left, right = left+1, right-1 {
		messages[left], messages[right] = messages[right], messages[left]
	}
	return messages, nil
}

func ticketPayload(id int64, groupName string, subject string, status string, lastMessage string, lastMessageAt sql.NullTime, createdAt time.Time, updatedAt time.Time) map[string]any {
	statusLabel := "处理中"
	if status == "closed" {
		statusLabel = "已关闭"
	}
	lastMessageAtText := ""
	if lastMessageAt.Valid {
		lastMessageAtText = nowFormat(lastMessageAt.Time)
	}
	return map[string]any{
		"id":              id,
		"group_name":      groupName,
		"subject":         subject,
		"status":          status,
		"status_label":    statusLabel,
		"last_message":    lastMessage,
		"last_message_at": lastMessageAtText,
		"created_at":      nowFormat(createdAt),
		"updated_at":      nowFormat(updatedAt),
	}
}

func nowFormat(value time.Time) string {
	if value.IsZero() {
		return ""
	}
	return value.Format("2006-01-02 15:04:05")
}
