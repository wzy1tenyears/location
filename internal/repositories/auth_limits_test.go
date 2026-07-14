package repositories

import (
	"context"
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestReserveAdminLoginAttemptAdmitsLastBudgetSlot(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	ip := "203.0.113.20"
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO admin_login_failures .*ON DUPLICATE KEY UPDATE`).
		WithArgs(ip, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT failed_count, locked_at, last_failed_at.*FOR UPDATE`).
		WithArgs(ip).
		WillReturnRows(sqlmock.NewRows([]string{"failed_count", "locked_at", "last_failed_at"}).AddRow(4, nil, time.Now().Add(-time.Minute)))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE admin_login_failures
SET failed_count = ?, locked_at = ?, last_failed_at = ?
WHERE ip = ?`)).
		WithArgs(5, sqlmock.AnyArg(), sqlmock.AnyArg(), ip).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	admitted, exhausted, err := NewAuthLimitRepository(db).ReserveAdminLoginAttempt(context.Background(), ip, 5, 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if !admitted || !exhausted {
		t.Fatalf("fifth admin attempt = admitted %v, exhausted %v; want true, true", admitted, exhausted)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestReserveGroupJoinAttemptPreservesLegitimateBudget(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	const userID int64 = 42
	ip := "203.0.113.21"
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO group_join_failures .*ON DUPLICATE KEY UPDATE`).
		WithArgs(userID, ip, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT failed_count, locked_at, last_failed_at.*FOR UPDATE`).
		WithArgs(userID, ip).
		WillReturnRows(sqlmock.NewRows([]string{"failed_count", "locked_at", "last_failed_at"}).AddRow(8, nil, time.Now().Add(-time.Minute)))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE group_join_failures
SET failed_count = ?, locked_at = ?, last_failed_at = ?
WHERE user_id = ? AND ip = ?`)).
		WithArgs(9, nil, sqlmock.AnyArg(), userID, ip).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	admitted, exhausted, err := NewAuthLimitRepository(db).ReserveGroupJoinAttempt(context.Background(), userID, ip, 10, 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if !admitted || exhausted {
		t.Fatalf("ninth group-join attempt = admitted %v, exhausted %v; want true, false", admitted, exhausted)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestReserveGroupJoinAttemptResetsExpiredWindow(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	const userID int64 = 43
	ip := "203.0.113.22"
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO group_join_failures .*ON DUPLICATE KEY UPDATE`).
		WithArgs(userID, ip, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT failed_count, locked_at, last_failed_at.*FOR UPDATE`).
		WithArgs(userID, ip).
		WillReturnRows(sqlmock.NewRows([]string{"failed_count", "locked_at", "last_failed_at"}).AddRow(10, time.Now().Add(-31*time.Minute), time.Now().Add(-31*time.Minute)))
	mock.ExpectExec(regexp.QuoteMeta(`UPDATE group_join_failures
SET failed_count = ?, locked_at = ?, last_failed_at = ?
WHERE user_id = ? AND ip = ?`)).
		WithArgs(1, nil, sqlmock.AnyArg(), userID, ip).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectCommit()

	admitted, exhausted, err := NewAuthLimitRepository(db).ReserveGroupJoinAttempt(context.Background(), userID, ip, 10, 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if !admitted || exhausted {
		t.Fatalf("fresh-window group-join attempt = admitted %v, exhausted %v; want true, false", admitted, exhausted)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestReserveAdminLoginAttemptRollsBackOnReadFailure(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	ip := "203.0.113.23"
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO admin_login_failures .*ON DUPLICATE KEY UPDATE`).
		WithArgs(ip, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT failed_count, locked_at, last_failed_at.*FOR UPDATE`).
		WithArgs(ip).
		WillReturnError(errors.New("read failed"))
	mock.ExpectRollback()

	admitted, exhausted, err := NewAuthLimitRepository(db).ReserveAdminLoginAttempt(context.Background(), ip, 5, 30*time.Minute)
	if err == nil {
		t.Fatal("database failure was ignored")
	}
	if admitted || exhausted {
		t.Fatal("database failure reported a successful reservation")
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestReserveAdminLoginAttemptRejectsBeforePasswordCheckWhenLocked(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	ip := "203.0.113.24"
	mock.ExpectBegin()
	mock.ExpectExec(`(?s)INSERT INTO admin_login_failures .*ON DUPLICATE KEY UPDATE`).
		WithArgs(ip, sqlmock.AnyArg()).
		WillReturnResult(sqlmock.NewResult(0, 0))
	mock.ExpectQuery(`(?s)SELECT failed_count, locked_at, last_failed_at.*FOR UPDATE`).
		WithArgs(ip).
		WillReturnRows(sqlmock.NewRows([]string{"failed_count", "locked_at", "last_failed_at"}).AddRow(5, time.Now(), time.Now()))
	mock.ExpectCommit()

	admitted, exhausted, err := NewAuthLimitRepository(db).ReserveAdminLoginAttempt(context.Background(), ip, 5, 30*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if admitted || !exhausted {
		t.Fatalf("locked admin attempt = admitted %v, exhausted %v; want false, true", admitted, exhausted)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
