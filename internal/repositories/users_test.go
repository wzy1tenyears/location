package repositories

import (
	"context"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestRecordLogSerializesStructuredMeta(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectExec("INSERT INTO user_logs").
		WithArgs(int64(7), "family-a", "location_report", "上报位置", `{"accuracy":12.5,"location_id":7}`, "127.0.0.1", "loc-app/test").
		WillReturnResult(sqlmock.NewResult(1, 1))

	userID := int64(7)
	err = NewUserRepository(db).RecordLog(context.Background(), &userID, "family-a", "location_report", "上报位置", map[string]any{
		"location_id": 7,
		"accuracy":    12.5,
	}, "127.0.0.1", "loc-app/test")
	if err != nil {
		t.Fatalf("RecordLog() error = %v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
