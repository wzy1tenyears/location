package handlers

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestHeartbeatLogIsSampledAndPrunedWithinStableLocks(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	now := time.Now()
	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE")).
		WithArgs("family-a").
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("family-a"))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM users WHERE id = ? FOR UPDATE")).
		WithArgs(int64(41)).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(41))
	mock.ExpectQuery(`(?s)SELECT id.*FROM user_logs.*created_at >=`).
		WithArgs(int64(41), "family-a", sqlmock.AnyArg()).
		WillReturnRows(sqlmock.NewRows([]string{"id"}))
	mock.ExpectExec(`(?s)INSERT INTO user_logs .*'online'`).
		WithArgs(int64(41), "family-a", "203.0.113.41", "loc-app/test").
		WillReturnResult(sqlmock.NewResult(101, 1))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM user_logs WHERE user_id = ? AND event_type = 'online' AND message = '用户心跳' ORDER BY id DESC LIMIT 1 OFFSET ?")).
		WithArgs(int64(41), maxHeartbeatLogsPerUser-1).
		WillReturnRows(sqlmock.NewRows([]string{"id"}))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM user_logs WHERE group_name = ? AND event_type = 'online' AND message = '用户心跳' ORDER BY id DESC LIMIT 1 OFFSET ?")).
		WithArgs("family-a", maxHeartbeatLogsPerTenant-1).
		WillReturnRows(sqlmock.NewRows([]string{"id"}))
	mock.ExpectCommit()

	handler := HeartbeatHandler{db: db}
	inserted, err := handler.recordLogIfDue(context.Background(), 41, "family-a", "203.0.113.41", "loc-app/test", now)
	if err != nil || !inserted {
		t.Fatalf("first recordLogIfDue() = %v, %v", inserted, err)
	}

	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE")).
		WithArgs("family-a").
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("family-a"))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM users WHERE id = ? FOR UPDATE")).
		WithArgs(int64(41)).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(41))
	mock.ExpectQuery(`(?s)SELECT id.*FROM user_logs.*created_at >=`).
		WithArgs(int64(41), "family-a", sqlmock.AnyArg()).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(101))
	mock.ExpectCommit()

	inserted, err = handler.recordLogIfDue(context.Background(), 41, "family-a", "203.0.113.41", "loc-app/test", now.Add(time.Minute))
	if err != nil || inserted {
		t.Fatalf("coalesced recordLogIfDue() = %v, %v", inserted, err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestHeartbeatRetentionDeletesOnlyOlderHeartbeatRowsInScope(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM user_logs WHERE user_id = ? AND event_type = 'online' AND message = '用户心跳' ORDER BY id DESC LIMIT 1 OFFSET ?")).
		WithArgs(int64(41), maxHeartbeatLogsPerUser-1).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(700))
	mock.ExpectExec(regexp.QuoteMeta("DELETE FROM user_logs WHERE user_id = ? AND event_type = 'online' AND message = '用户心跳' AND id < ? ORDER BY id ASC LIMIT ?")).
		WithArgs(int64(41), int64(700), maxHeartbeatCleanupBatch).
		WillReturnResult(sqlmock.NewResult(0, 25))
	mock.ExpectCommit()

	tx, err := db.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := pruneHeartbeatLogsTx(context.Background(), tx, "user_id", int64(41), maxHeartbeatLogsPerUser); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestTicketCreateQuotaRejectsOpenLimitBeforeCleanupOrInsert(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE")).
		WithArgs("family-a").
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("family-a"))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM users WHERE id = ? FOR UPDATE")).
		WithArgs(int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(7))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT COUNT(*) FROM support_tickets WHERE user_id = ? AND status = 'open'")).
		WithArgs(int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(maxOpenTicketsPerUser))
	mock.ExpectRollback()

	tx, err := db.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := lockTicketQuotaScopeTx(context.Background(), tx, 7, "family-a"); err != nil {
		t.Fatal(err)
	}
	allowed, err := ticketCreateWithinQuotaTx(context.Background(), tx, 7, "family-a")
	if err != nil {
		t.Fatal(err)
	}
	if allowed {
		t.Fatal("ticket create was allowed at the durable user allocation")
	}
	_ = tx.Rollback()
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestDeletedTicketGroupFallsBackToUserQuotaOnly(t *testing.T) {
	scope := &userScope{Groups: []models.Membership{{GroupName: "family-current"}}}
	if groupName := activeTicketQuotaGroup(scope, "family-deleted"); groupName != "" {
		t.Fatalf("deleted ticket group was still used as a quota lock: %q", groupName)
	}
	if groupName := activeTicketQuotaGroup(scope, "family-current"); groupName != "family-current" {
		t.Fatalf("active ticket group = %q", groupName)
	}
	if groupName := activeTicketQuotaGroup(scope, ""); groupName != "" {
		t.Fatalf("ungrouped ticket was charged to an unrelated tenant: %q", groupName)
	}
}

func TestTicketInitialMessageUsesTheSameDailyUserBudget(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(`(?s)SELECT COUNT\(\*\).*WHERE t.user_id = \? AND m.sender_type = 'user' AND m.created_at`).
		WithArgs(int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(maxTicketRepliesPerUserDay))
	mock.ExpectRollback()

	tx, err := db.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatal(err)
	}
	allowed, err := ticketInitialMessageWithinQuotaTx(context.Background(), tx, 7, "family-a")
	if err != nil {
		t.Fatal(err)
	}
	if allowed {
		t.Fatal("initial ticket message bypassed the daily user message budget")
	}
	_ = tx.Rollback()
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestTicketReplyQuotaRejectsWithoutWritingMessage(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	now := time.Now()
	mock.ExpectQuery(`(?s)SELECT id, group_name, subject, status, created_at, updated_at.*WHERE id = \? AND user_id = \?`).
		WithArgs(int64(12), int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"id", "group_name", "subject", "status", "created_at", "updated_at"}).
			AddRow(12, "family-a", "Help", "open", now, now))
	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT group_name FROM family_groups WHERE group_name = ? FOR UPDATE")).
		WithArgs("family-a").
		WillReturnRows(sqlmock.NewRows([]string{"group_name"}).AddRow("family-a"))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM users WHERE id = ? FOR UPDATE")).
		WithArgs(int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(7))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT status FROM support_tickets WHERE id = ? AND user_id = ? FOR UPDATE")).
		WithArgs(int64(12), int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"status"}).AddRow("open"))
	mock.ExpectQuery(`(?s)SELECT COUNT\(\*\).*WHERE t.user_id = \? AND m.sender_type = 'user' AND m.created_at`).
		WithArgs(int64(7)).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(0))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT COUNT(*) FROM support_ticket_messages WHERE ticket_id = ?")).
		WithArgs(int64(12)).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(maxMessagesPerTicket))
	mock.ExpectRollback()

	handler := TicketsHandler{db: db}
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/tickets", nil)
	handler.reply(recorder, request, &userScope{
		User:   &models.User{ID: 7},
		Groups: []models.Membership{{GroupName: "family-a"}},
	}, 12, "another message")
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("reply status = %d, want %d; body=%s", recorder.Code, http.StatusTooManyRequests, recorder.Body.String())
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestAdminTicketReplyHonorsAbsoluteMessageCap(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectBegin()
	mock.ExpectQuery(regexp.QuoteMeta("SELECT id FROM support_tickets WHERE id = ? FOR UPDATE")).
		WithArgs(int64(12)).
		WillReturnRows(sqlmock.NewRows([]string{"id"}).AddRow(12))
	mock.ExpectQuery(regexp.QuoteMeta("SELECT COUNT(*) FROM support_ticket_messages WHERE ticket_id = ?")).
		WithArgs(int64(12)).
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(maxMessagesPerTicket))
	mock.ExpectRollback()

	handler := AdminManageHandler{db: db}
	request := httptest.NewRequest(http.MethodPost, "/api/admin/manage", nil)
	_, err = handler.replyTicket(request, map[string]any{"ticket_id": float64(12), "reply": "admin response"})
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
		t.Fatalf("replyTicket() error = %#v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestTicketMessagesReturnsBoundedNewestRowsInChronologicalOrder(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	now := time.Now()
	mock.ExpectQuery(`(?s)SELECT id, sender_type, message, created_at.*ORDER BY created_at DESC, id DESC.*LIMIT \?`).
		WithArgs(int64(12), maxTicketMessagesPerResponse).
		WillReturnRows(sqlmock.NewRows([]string{"id", "sender_type", "message", "created_at"}).
			AddRow(203, "user", "newest", now).
			AddRow(202, "admin", "middle", now.Add(-time.Minute)).
			AddRow(201, "user", "oldest retained", now.Add(-2*time.Minute)))

	request := httptest.NewRequest(http.MethodGet, "/api/tickets?ticket_id=12", nil)
	messages, err := (TicketsHandler{db: db}).ticketMessages(request, 12)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) != 3 || messages[0]["id"] != int64(201) || messages[2]["id"] != int64(203) {
		t.Fatalf("bounded message order = %#v", messages)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestTicketRateLimitRejectsBeforeTicketOperation(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	expectPersistenceRateHit(mock, "support_ticket_create", "7", ticketCreateMaxHits)
	err = (TicketsHandler{rates: repositories.NewRateLimitRepository(db)}).enforceRate(context.Background(), "support_ticket_create", 7, ticketCreateMaxHits, ticketCreateWindow)
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusTooManyRequests {
		t.Fatalf("enforceRate() error = %#v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func expectPersistenceRateHit(mock sqlmock.Sqlmock, bucket string, identity string, currentCount int) {
	sum := sha256.Sum256([]byte(bucket + "|" + identity))
	identityHash := hex.EncodeToString(sum[:])
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO api_rate_limits .*ON DUPLICATE KEY UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT window_started_at, hit_count.*FOR UPDATE`).
		WithArgs(bucket, identityHash).
		WillReturnRows(sqlmock.NewRows([]string{"window_started_at", "hit_count"}).AddRow(time.Now(), currentCount))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE api_rate_limits
SET hit_count = ?, updated_at = NOW()
WHERE bucket = ? AND identity_hash = ?`)).
		WithArgs(currentCount+1, bucket, identityHash).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()
}
