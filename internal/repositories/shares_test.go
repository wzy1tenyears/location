package repositories

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCreateWithinQuotaLocksScopesBeforeAtomicInsert(t *testing.T) {
	repo, state, closeDB := newLocationShareRepoTest(t, 2, 7)
	defer closeDB()
	now := time.Date(2026, 7, 14, 12, 0, 0, 0, time.UTC)

	if err := repo.CreateWithinQuota(context.Background(), locationShareRepoFixture(now), now, 20, 100); err != nil {
		t.Fatalf("CreateWithinQuota() error = %v", err)
	}
	if !state.inserted || state.commits != 1 {
		t.Fatalf("share insert was not committed: %#v", state)
	}
	calls := state.callList()
	assertShareCallOrder(t, calls,
		"QUERY SELECT group_name",
		"QUERY SELECT id",
		"QUERY SELECT id FROM user_groups",
		"QUERY SELECT COUNT(*) FROM location_shares WHERE owner_user_id",
		"QUERY SELECT COUNT(*) FROM location_shares WHERE group_name",
		"EXEC INSERT INTO location_shares",
	)
	if !strings.Contains(calls[0], "FOR UPDATE") || !strings.Contains(calls[1], "FOR UPDATE") || !strings.Contains(calls[2], "FOR UPDATE") {
		t.Fatalf("quota and authorization locks are missing FOR UPDATE: %#v", calls[:3])
	}
}

func TestCreateWithinQuotaRejectsRemovedMembershipBeforeCounts(t *testing.T) {
	repo, state, closeDB := newLocationShareRepoTest(t, 2, 7)
	defer closeDB()
	state.hasMembership = false
	now := time.Date(2026, 7, 14, 12, 0, 0, 0, time.UTC)

	err := repo.CreateWithinQuota(context.Background(), locationShareRepoFixture(now), now, 20, 100)
	if !errors.Is(err, ErrLocationShareAuthorization) {
		t.Fatalf("CreateWithinQuota() error = %v, want authorization error", err)
	}
	if state.inserted || state.commits != 0 {
		t.Fatal("removed membership still inserted or committed a share")
	}
	calls := state.callList()
	if state.callContaining("SELECT COUNT(*)") != "" || state.callContaining("INSERT INTO location_shares") != "" {
		t.Fatalf("authorization failure reached quota counts or insert: %#v", calls)
	}
}

func TestCreateWithinQuotaRejectsUserAndGroupThresholds(t *testing.T) {
	tests := []struct {
		name        string
		ownerActive int
		groupActive int
		want        error
	}{
		{name: "user", ownerActive: 20, groupActive: 7, want: ErrLocationShareUserQuota},
		{name: "group", ownerActive: 2, groupActive: 100, want: ErrLocationShareGroupQuota},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			repo, state, closeDB := newLocationShareRepoTest(t, test.ownerActive, test.groupActive)
			defer closeDB()
			now := time.Date(2026, 7, 14, 12, 0, 0, 0, time.UTC)
			err := repo.CreateWithinQuota(context.Background(), locationShareRepoFixture(now), now, 20, 100)
			if !errors.Is(err, test.want) {
				t.Fatalf("CreateWithinQuota() error = %v, want %v", err, test.want)
			}
			if state.inserted || state.commits != 0 {
				t.Fatal("quota rejection inserted or committed a share")
			}
		})
	}
}

func TestDeleteExpiredBatchesStopsAfterPartialBatchAndReturnsTotal(t *testing.T) {
	now := time.Date(2026, 7, 14, 12, 0, 0, 0, time.UTC)
	repo, state, closeDB := newLocationShareRepoTest(t, 0, 0)
	defer closeDB()
	state.cleanupResults = []int64{5, 3}
	deleted, err := repo.DeleteExpiredBatches(context.Background(), now, 5, 10)
	if err != nil {
		t.Fatalf("DeleteExpiredBatches() error = %v", err)
	}
	if deleted != 8 || state.cleanupCalls != 2 {
		t.Fatalf("DeleteExpiredBatches() = %d across %d calls, want 8 across 2", deleted, state.cleanupCalls)
	}
	cleanupCall := state.callContaining("DELETE FROM location_shares")
	if !strings.Contains(cleanupCall, "ORDER BY expires_at") || !strings.Contains(cleanupCall, "LIMIT ?") {
		t.Fatalf("cleanup is not bounded and ordered: %s", cleanupCall)
	}
}

func TestDeleteExpiredBatchesReturnsProgressWithFailure(t *testing.T) {
	now := time.Date(2026, 7, 14, 12, 0, 0, 0, time.UTC)
	repo, state, closeDB := newLocationShareRepoTest(t, 0, 0)
	defer closeDB()
	state.cleanupResults = []int64{5}
	state.failCleanupAt = 2
	deleted, err := repo.DeleteExpiredBatches(context.Background(), now, 5, 10)
	if err == nil {
		t.Fatal("DeleteExpiredBatches() did not return the cleanup failure")
	}
	if deleted != 5 || state.cleanupCalls != 2 {
		t.Fatalf("failed cleanup returned %d across %d calls, want 5 across 2", deleted, state.cleanupCalls)
	}
}

func TestDeleteExpiredBatchesRejectsInvalidBounds(t *testing.T) {
	repo, _, closeDB := newLocationShareRepoTest(t, 0, 0)
	defer closeDB()
	if _, err := repo.DeleteExpiredBatches(context.Background(), time.Now(), 0, 10); err == nil {
		t.Fatal("DeleteExpiredBatches() accepted zero batch size")
	}
	if _, err := repo.DeleteExpiredBatches(context.Background(), time.Now(), 5, 0); err == nil {
		t.Fatal("DeleteExpiredBatches() accepted zero batch count")
	}
}

func locationShareRepoFixture(now time.Time) LocationShare {
	return LocationShare{
		TokenHash:           strings.Repeat("a", 64),
		TokenPlaintext:      strings.Repeat("b", 64),
		OwnerUserID:         41,
		GroupName:           "family-a",
		LocationIDsJSON:     `[7]`,
		SnapshotJSON:        `[{"id":7}]`,
		AccessCodeHash:      "hash",
		AccessCodePlaintext: "2468",
		ExpiresAt:           now.Add(time.Hour),
	}
}

func assertShareCallOrder(t *testing.T, calls []string, fragments ...string) {
	t.Helper()
	position := -1
	for _, fragment := range fragments {
		found := -1
		for index := position + 1; index < len(calls); index++ {
			if strings.Contains(calls[index], fragment) {
				found = index
				break
			}
		}
		if found < 0 {
			t.Fatalf("call %q missing after index %d: %#v", fragment, position, calls)
		}
		position = found
	}
}

func newLocationShareRepoTest(t *testing.T, ownerActive int, groupActive int) (LocationShareRepository, *locationShareRepoState, func()) {
	t.Helper()
	state := &locationShareRepoState{ownerActive: ownerActive, groupActive: groupActive, hasMembership: true}
	driverName := fmt.Sprintf("location-share-repo-test-%d", atomic.AddUint64(&locationShareRepoDriverID, 1))
	sql.Register(driverName, locationShareRepoDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	return NewLocationShareRepository(db), state, func() { _ = db.Close() }
}

var locationShareRepoDriverID uint64

type locationShareRepoState struct {
	mu             sync.Mutex
	ownerActive    int
	groupActive    int
	hasMembership  bool
	inserted       bool
	commits        int
	cleanupResults []int64
	cleanupCalls   int
	failCleanupAt  int
	calls          []string
}

func (state *locationShareRepoState) record(kind string, query string) {
	state.calls = append(state.calls, kind+" "+strings.Join(strings.Fields(query), " "))
}

func (state *locationShareRepoState) callList() []string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return append([]string(nil), state.calls...)
}

func (state *locationShareRepoState) callContaining(fragment string) string {
	for _, call := range state.callList() {
		if strings.Contains(call, fragment) {
			return call
		}
	}
	return ""
}

type locationShareRepoDriver struct {
	state *locationShareRepoState
}

func (driverInstance locationShareRepoDriver) Open(string) (driver.Conn, error) {
	return &locationShareRepoConn{state: driverInstance.state}, nil
}

type locationShareRepoConn struct {
	state *locationShareRepoState
}

func (connection *locationShareRepoConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("prepared statements are not supported by the location share test driver")
}

func (connection *locationShareRepoConn) Close() error { return nil }

func (connection *locationShareRepoConn) Begin() (driver.Tx, error) {
	return locationShareRepoTx{state: connection.state}, nil
}

func (connection *locationShareRepoConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return locationShareRepoTx{state: connection.state}, nil
}

func (connection *locationShareRepoConn) QueryContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	connection.state.record("QUERY", query)
	switch {
	case strings.Contains(query, "SELECT group_name"):
		return &locationShareRepoRows{columns: []string{"group_name"}, values: [][]driver.Value{{"family-a"}}}, nil
	case strings.Contains(query, "FROM user_groups"):
		if !connection.state.hasMembership {
			return &locationShareRepoRows{columns: []string{"id"}}, nil
		}
		return &locationShareRepoRows{columns: []string{"id"}, values: [][]driver.Value{{int64(91)}}}, nil
	case strings.Contains(query, "SELECT id"):
		return &locationShareRepoRows{columns: []string{"id"}, values: [][]driver.Value{{int64(41)}}}, nil
	case strings.Contains(query, "SELECT COUNT(*)") && strings.Contains(query, "owner_user_id"):
		return &locationShareRepoRows{columns: []string{"count"}, values: [][]driver.Value{{int64(connection.state.ownerActive)}}}, nil
	case strings.Contains(query, "SELECT COUNT(*)") && strings.Contains(query, "group_name"):
		return &locationShareRepoRows{columns: []string{"count"}, values: [][]driver.Value{{int64(connection.state.groupActive)}}}, nil
	default:
		return nil, fmt.Errorf("unexpected location share query: %s", query)
	}
}

func (connection *locationShareRepoConn) ExecContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Result, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	connection.state.record("EXEC", query)
	switch {
	case strings.Contains(query, "INSERT INTO location_shares"):
		connection.state.inserted = true
		return driver.RowsAffected(1), nil
	case strings.Contains(query, "DELETE FROM location_shares"):
		connection.state.cleanupCalls++
		if connection.state.failCleanupAt == connection.state.cleanupCalls {
			return nil, errors.New("cleanup failed")
		}
		index := connection.state.cleanupCalls - 1
		if index < len(connection.state.cleanupResults) {
			return driver.RowsAffected(connection.state.cleanupResults[index]), nil
		}
		return driver.RowsAffected(0), nil
	default:
		return nil, fmt.Errorf("unexpected location share exec: %s", query)
	}
}

type locationShareRepoTx struct {
	state *locationShareRepoState
}

func (tx locationShareRepoTx) Commit() error {
	tx.state.mu.Lock()
	defer tx.state.mu.Unlock()
	tx.state.commits++
	return nil
}

func (locationShareRepoTx) Rollback() error { return nil }

type locationShareRepoRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func (rows *locationShareRepoRows) Columns() []string { return rows.columns }
func (rows *locationShareRepoRows) Close() error      { return nil }

func (rows *locationShareRepoRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}
