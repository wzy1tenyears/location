package repositories

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"familylocation/location-v3/internal/models"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestRetainedHistoryForUsersAppliesPerUserBoundAndDeduplicates(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New(): %v", err)
	}
	defer db.Close()

	boundedHistoryQuery := `(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`
	for _, userID := range []int64{7, 9} {
		mock.ExpectQuery(boundedHistoryQuery).
			WithArgs("family-a", userID, 37).
			WillReturnRows(sqlmock.NewRows([]string{"id"}))
	}

	repo := NewLocationRepository(db)
	rows, err := repo.RetainedHistoryForUsers(context.Background(), "family-a", []int64{7, 0, 7, 9}, 37)
	if err != nil {
		t.Fatalf("RetainedHistoryForUsers(): %v", err)
	}
	if len(rows) != 0 {
		t.Fatalf("len(rows) = %d, want 0", len(rows))
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("bounded queries: %v", err)
	}
}

func TestRetainedHistoryForUsersBoundedRejectsInsteadOfReturningPartialRows(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New(): %v", err)
	}
	defer db.Close()

	columns := []string{
		"id", "user_id", "username", "display_name", "group_name", "role", "latitude", "longitude",
		"altitude", "accuracy", "heading", "speed", "location_meta", "address_diagnostics", "address_mismatch",
		"encryption_mode", "encrypted_payload", "p2p_key_version", "created_at", "updated_at",
	}
	createdAt := time.Date(2026, 7, 17, 12, 0, 0, 0, time.UTC)
	rows := sqlmock.NewRows(columns)
	for id := int64(1); id <= 2; id++ {
		rows.AddRow(id, 7, "member", "Member", "family-a", "guardian", 30.0, 120.0,
			nil, nil, nil, nil, nil, nil, false, "", "", 0, createdAt, createdAt)
	}
	mock.ExpectQuery(`(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`).
		WithArgs("family-a", int64(7), 2).
		WillReturnRows(rows)

	repo := NewLocationRepository(db)
	got, err := repo.RetainedHistoryForUsersBounded(context.Background(), "family-a", []int64{7}, 37, 1, 1024*1024)
	if !errors.Is(err, ErrLocationHistorySnapshotTooLarge) {
		t.Fatalf("RetainedHistoryForUsersBounded() error = %v, want snapshot-too-large", err)
	}
	if got != nil {
		t.Fatalf("oversized snapshot returned partial rows: %#v", got)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("bounded overflow query: %v", err)
	}
}

func TestRetainedHistoryTotalScanBudgetHasExactBoundary(t *testing.T) {
	if got := retainedHistoryFetchLimit(5000, 10000, 9999); got != 2 {
		t.Fatalf("retainedHistoryFetchLimit() = %d, want remaining row plus one probe", got)
	}
	if retainedHistoryExceedsTotalLimit(10000, 9999, 1) {
		t.Fatal("exactly 10000 retained rows were rejected")
	}
	if !retainedHistoryExceedsTotalLimit(10000, 9999, 2) {
		t.Fatal("10001 retained rows were accepted")
	}
	if got := retainedHistoryFetchLimit(5000, 10000, 10000); got != 1 {
		t.Fatalf("exhausted scan budget probe = %d, want 1", got)
	}
}

func TestRetainedHistoryForUsersUsesFiniteFallback(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New(): %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(`(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`).
		WithArgs("family-a", int64(7), defaultLocationHistoryLimit).
		WillReturnRows(sqlmock.NewRows([]string{"id"}))

	repo := NewLocationRepository(db)
	if _, err := repo.RetainedHistoryForUsers(context.Background(), "family-a", []int64{7}, 0); err != nil {
		t.Fatalf("RetainedHistoryForUsers(): %v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("finite fallback query: %v", err)
	}
}

func TestRetainedHistoryForUsersBoundedSinceIncludesPreWindowAnchor(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New(): %v", err)
	}
	defer db.Close()

	since := time.Date(2026, 7, 22, 12, 0, 0, 0, time.UTC)
	inWindow := boundedHistoryModel(7, "inside")
	inWindow.ID = 70
	inWindow.CreatedAt = since.Add(time.Minute)
	inWindow.UpdatedAt = inWindow.CreatedAt
	anchor := boundedHistoryModel(7, "anchor")
	anchor.ID = 69
	anchor.CreatedAt = since.Add(-time.Minute)
	anchor.UpdatedAt = anchor.CreatedAt

	mock.ExpectQuery(`(?s)FROM locations l.*l\.created_at >= \?.*ORDER BY l\.created_at DESC, l\.id DESC.*LIMIT \?`).
		WithArgs("family-a", int64(7), since, 3).
		WillReturnRows(boundedHistoryRows(inWindow))
	mock.ExpectQuery(`(?s)FROM locations l.*l\.created_at < \?.*ORDER BY l\.created_at DESC, l\.id DESC.*LIMIT 1`).
		WithArgs("family-a", int64(7), since).
		WillReturnRows(boundedHistoryRows(anchor))

	repo := NewLocationRepository(db)
	rows, err := repo.RetainedHistoryForUsersBoundedSince(context.Background(), "family-a", []int64{7}, 3, 10, 1024*1024, since)
	if err != nil {
		t.Fatalf("RetainedHistoryForUsersBoundedSince(): %v", err)
	}
	if len(rows) != 2 || rows[0].ID != 70 || rows[1].ID != 69 {
		t.Fatalf("window rows = %#v, want in-window row followed by anchor", rows)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("window queries: %v", err)
	}
}

func TestRetainedHistorySourceByteBudgetAcceptsExactAndRejectsOneByteOver(t *testing.T) {
	fixture := boundedHistoryModel(7, strings.Repeat("x", 128))
	exactBudget := locationHistorySourceBytes(fixture)

	for _, test := range []struct {
		name      string
		budget    int
		wantError bool
	}{
		{name: "exact", budget: exactBudget},
		{name: "one byte short", budget: exactBudget - 1, wantError: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			db, mock, err := sqlmock.New()
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			mock.ExpectQuery(`(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`).
				WithArgs("family-a", int64(7), 37).
				WillReturnRows(boundedHistoryRows(fixture))

			repo := NewLocationRepository(db)
			rows, err := repo.RetainedHistoryForUsersBounded(context.Background(), "family-a", []int64{7}, 37, 100, test.budget)
			if test.wantError {
				if !errors.Is(err, ErrLocationHistorySnapshotBytesTooLarge) || rows != nil {
					t.Fatalf("bounded read = rows %#v error %v, want nil byte-limit error", rows, err)
				}
			} else if err != nil || len(rows) != 1 {
				t.Fatalf("exact-budget read = rows %#v error %v", rows, err)
			}
			if err := mock.ExpectationsWereMet(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestRetainedHistorySourceByteBudgetAccumulatesAcrossUsers(t *testing.T) {
	first := boundedHistoryModel(7, strings.Repeat("a", 64))
	second := boundedHistoryModel(9, strings.Repeat("b", 64))
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	query := `(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`
	mock.ExpectQuery(query).WithArgs("family-a", int64(7), 37).WillReturnRows(boundedHistoryRows(first))
	mock.ExpectQuery(query).WithArgs("family-a", int64(9), 37).WillReturnRows(boundedHistoryRows(second))

	repo := NewLocationRepository(db)
	budget := locationHistorySourceBytes(first) + locationHistorySourceBytes(second)
	rows, err := repo.RetainedHistoryForUsersBounded(context.Background(), "family-a", []int64{7, 9}, 37, 100, budget)
	if err != nil || len(rows) != 2 {
		t.Fatalf("cross-user exact budget = rows %#v error %v", rows, err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestRetainedHistorySourceByteOverflowStopsBeforeNextUser(t *testing.T) {
	first := boundedHistoryModel(7, strings.Repeat("a", 64))
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectQuery(`(?s)FROM locations l.*WHERE l\.group_name = \? AND l\.user_id = \? AND u\.is_active = 1.*LIMIT \?`).
		WithArgs("family-a", int64(7), 37).
		WillReturnRows(boundedHistoryRows(first))

	repo := NewLocationRepository(db)
	rows, err := repo.RetainedHistoryForUsersBounded(context.Background(), "family-a", []int64{7, 9}, 37, 100, locationHistorySourceBytes(first)-1)
	if !errors.Is(err, ErrLocationHistorySnapshotBytesTooLarge) || rows != nil {
		t.Fatalf("overflow read = rows %#v error %v", rows, err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("overflow queried a later user or missed the first query: %v", err)
	}
}

func boundedHistoryModel(userID int64, encryptedPayload string) models.Location {
	createdAt := time.Date(2026, 7, 17, 12, 0, 0, 0, time.UTC)
	return models.Location{
		ID:               userID,
		UserID:           userID,
		Username:         "member",
		DisplayName:      "Member",
		GroupName:        "family-a",
		Role:             "guardian",
		Latitude:         0,
		Longitude:        0,
		EncryptionMode:   "p2p-v1",
		EncryptedPayload: encryptedPayload,
		P2PKeyVersion:    1,
		CreatedAt:        createdAt,
		UpdatedAt:        createdAt,
	}
}

func boundedHistoryRows(location models.Location) *sqlmock.Rows {
	columns := []string{
		"id", "user_id", "username", "display_name", "group_name", "role", "latitude", "longitude",
		"altitude", "accuracy", "heading", "speed", "location_meta", "address_diagnostics", "address_mismatch",
		"encryption_mode", "encrypted_payload", "p2p_key_version", "created_at", "updated_at",
	}
	return sqlmock.NewRows(columns).AddRow(
		location.ID, location.UserID, location.Username, location.DisplayName, location.GroupName, location.Role,
		location.Latitude, location.Longitude, nil, nil, nil, nil, nil, nil, false,
		location.EncryptionMode, location.EncryptedPayload, location.P2PKeyVersion, location.CreatedAt, location.UpdatedAt,
	)
}
