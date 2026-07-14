package main

import (
	"context"
	"regexp"
	"testing"
	"time"

	"familylocation/location-v3/internal/repositories"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestAppChallengeCleanupRunsAtStartupAndStopsWithContext(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectExec(regexp.QuoteMeta("DELETE FROM app_challenges WHERE expires_at <= ? ORDER BY expires_at ASC LIMIT ?")).
		WithArgs(sqlmock.AnyArg(), appChallengeCleanupBatch).
		WillReturnResult(sqlmock.NewResult(0, 0))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		runAppChallengeCleanup(ctx, repositories.NewAppChallengeRepository(db))
		close(done)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if err := mock.ExpectationsWereMet(); err == nil {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		cancel()
		t.Fatal(err)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("challenge cleanup worker did not stop after cancellation")
	}
}

func TestSupportTicketCleanupRunsAtStartupAndStopsWithContext(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectExec(`(?s)DELETE FROM support_tickets.*status = 'closed'.*updated_at <=.*ORDER BY updated_at ASC, id ASC.*LIMIT`).
		WithArgs(sqlmock.AnyArg(), supportTicketCleanupBatch).
		WillReturnResult(sqlmock.NewResult(0, 0))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		runSupportTicketCleanup(ctx, repositories.NewSupportTicketMaintenanceRepository(db))
		close(done)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if err := mock.ExpectationsWereMet(); err == nil {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		cancel()
		t.Fatal(err)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("support ticket cleanup worker did not stop after cancellation")
	}
}

func TestLocationShareCleanupRunsAtStartupAndStopsWithContext(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectExec(`(?s)DELETE FROM location_shares.*expires_at <=.*ORDER BY expires_at.*LIMIT`).
		WithArgs(sqlmock.AnyArg(), locationShareCleanupBatch).
		WillReturnResult(sqlmock.NewResult(0, 0))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		runLocationShareCleanup(ctx, repositories.NewLocationShareRepository(db))
		close(done)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if err := mock.ExpectationsWereMet(); err == nil {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		cancel()
		t.Fatal(err)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("location share cleanup worker did not stop after cancellation")
	}
}
