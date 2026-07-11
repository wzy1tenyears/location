package handlers

import (
	"database/sql"
	"fmt"
	"net/http"
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
}

func NewTicketsHandler(db *sql.DB, sessions session.Reader) TicketsHandler {
	return TicketsHandler{
		db:    db,
		scope: newScopedHandler(db, sessions),
		users: repositories.NewUserRepository(db),
	}
}

func (handler TicketsHandler) Handle(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireUser(r)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	if r.Method == http.MethodGet {
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
		handler.create(w, r, scope, req.GroupName, req.Subject, req.Message)
	case "reply":
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
LEFT JOIN (
	SELECT m1.*
	FROM support_ticket_messages m1
	INNER JOIN (
		SELECT ticket_id, MAX(id) AS latest_id
		FROM support_ticket_messages
		GROUP BY ticket_id
	) latest ON latest.latest_id = m1.id
) last_message ON last_message.ticket_id = t.id
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
	if membership, _ := handler.scope.groups.MembershipForUser(r.Context(), scope.User.ID, truncateGroupName(groupName)); membership != nil {
		ticketGroupName = membership.GroupName
	}
	tx, err := handler.db.BeginTx(r.Context(), nil)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
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
ORDER BY created_at ASC, id ASC`, ticketID)
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
	return messages, rows.Err()
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
