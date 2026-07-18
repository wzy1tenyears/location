package database

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestWithMySQLNamedLockReleasesAfterCallbackFailure(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	callbackErr := errors.New("callback failed")
	mock.ExpectQuery(`SELECT GET_LOCK\(\?, \?\)`).
		WithArgs("schema-lock", 7).
		WillReturnRows(sqlmock.NewRows([]string{"GET_LOCK"}).AddRow(1))
	mock.ExpectQuery(`SELECT RELEASE_LOCK\(\?\)`).
		WithArgs("schema-lock").
		WillReturnRows(sqlmock.NewRows([]string{"RELEASE_LOCK"}).AddRow(1))

	err = withMySQLNamedLock(context.Background(), db, "schema-lock", 7, func(_ *sql.Conn) error {
		return callbackErr
	})
	if !errors.Is(err, callbackErr) {
		t.Fatalf("withMySQLNamedLock() error = %v, want callback error", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestWithMySQLNamedLockJoinsCallbackAndReleaseFailures(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	callbackErr := errors.New("callback failed")
	releaseErr := errors.New("release failed")
	mock.ExpectQuery(`SELECT GET_LOCK\(\?, \?\)`).
		WithArgs("schema-lock", 7).
		WillReturnRows(sqlmock.NewRows([]string{"GET_LOCK"}).AddRow(1))
	mock.ExpectQuery(`SELECT RELEASE_LOCK\(\?\)`).
		WithArgs("schema-lock").
		WillReturnError(releaseErr)

	err = withMySQLNamedLock(context.Background(), db, "schema-lock", 7, func(_ *sql.Conn) error {
		return callbackErr
	})
	if !errors.Is(err, callbackErr) || !errors.Is(err, releaseErr) {
		t.Fatalf("withMySQLNamedLock() error = %v, want callback and release errors", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestWithMySQLNamedLockRejectsTimeoutWithoutCallingCallbackOrRelease(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectQuery(`SELECT GET_LOCK\(\?, \?\)`).
		WithArgs("schema-lock", 7).
		WillReturnRows(sqlmock.NewRows([]string{"GET_LOCK"}).AddRow(0))
	called := false
	err = withMySQLNamedLock(context.Background(), db, "schema-lock", 7, func(_ *sql.Conn) error {
		called = true
		return nil
	})
	if err == nil || called {
		t.Fatalf("withMySQLNamedLock() error = %v, callback called = %v", err, called)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestWithMySQLNamedLockRejectsLostOwnership(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectQuery(`SELECT GET_LOCK\(\?, \?\)`).
		WithArgs("schema-lock", 7).
		WillReturnRows(sqlmock.NewRows([]string{"GET_LOCK"}).AddRow(1))
	mock.ExpectQuery(`SELECT RELEASE_LOCK\(\?\)`).
		WithArgs("schema-lock").
		WillReturnRows(sqlmock.NewRows([]string{"RELEASE_LOCK"}).AddRow(0))

	err = withMySQLNamedLock(context.Background(), db, "schema-lock", 7, func(_ *sql.Conn) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "no ownership") {
		t.Fatalf("withMySQLNamedLock() error = %v, want lost-ownership failure", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
