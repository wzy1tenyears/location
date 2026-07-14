package repositories

import (
	"context"
	"regexp"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestSupportTicketCleanupIsClosedAgeScopedAndBatched(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	cutoff := time.Now().Add(-180 * 24 * time.Hour)
	query := regexp.QuoteMeta(`DELETE FROM support_tickets
WHERE status = 'closed' AND updated_at <= ?
ORDER BY updated_at ASC, id ASC
LIMIT ?`)
	mock.ExpectExec(query).
		WithArgs(cutoff, 500).
		WillReturnResult(sqlmock.NewResult(0, 500))
	mock.ExpectExec(query).
		WithArgs(cutoff, 500).
		WillReturnResult(sqlmock.NewResult(0, 12))

	deleted, err := NewSupportTicketMaintenanceRepository(db).DeleteClosedBatches(context.Background(), cutoff, 500, 10)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 512 {
		t.Fatalf("DeleteClosedBatches() deleted %d rows, want 512", deleted)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
