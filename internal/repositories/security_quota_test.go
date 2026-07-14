package repositories

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/models"
)

func TestEnvironmentReportStoreDailyCoalescesConcurrentEquivalentWrites(t *testing.T) {
	state := &securityQuotaDBState{}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewEnvironmentReportRepository(db)

	const writers = 12
	start := make(chan struct{})
	results := make(chan EnvironmentReportStoreResult, writers)
	errorsFound := make(chan error, writers)
	var wait sync.WaitGroup
	for index := 0; index < writers; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			result, err := repo.StoreDaily(context.Background(), 41, EnvironmentReportKindEnvironment, `{"report_kind":"device_integrity","installed_apps":[]}`)
			if err != nil {
				errorsFound <- err
				return
			}
			results <- result
		}()
	}
	close(start)
	wait.Wait()
	close(results)
	close(errorsFound)
	for err := range errorsFound {
		t.Fatalf("StoreDaily() error = %v", err)
	}

	inserted := 0
	for result := range results {
		if result == EnvironmentReportInserted {
			inserted++
		}
	}
	if inserted != 1 {
		t.Fatalf("inserted results = %d, want 1", inserted)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.environmentInserts != 1 || state.environmentUpdates != 0 || len(state.reports) != 1 {
		t.Fatalf("persistent writes = inserts:%d updates:%d rows:%d", state.environmentInserts, state.environmentUpdates, len(state.reports))
	}
	if !strings.Contains(state.reports[0].payload, `"report_kind":"environment"`) || strings.Contains(state.reports[0].payload, `"report_kind":"device_integrity"`) {
		t.Fatalf("client-controlled report kind survived canonicalization: %s", state.reports[0].payload)
	}
}

func TestEnvironmentReportStoreDailyUpdatesWithoutGrowingAndSeparatesKinds(t *testing.T) {
	state := &securityQuotaDBState{}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewEnvironmentReportRepository(db)

	if result, err := repo.StoreDaily(context.Background(), 7, EnvironmentReportKindEnvironment, `{"value":"old"}`); err != nil || result != EnvironmentReportInserted {
		t.Fatalf("first StoreDaily() = %v, %v", result, err)
	}
	if result, err := repo.StoreDaily(context.Background(), 7, EnvironmentReportKindEnvironment, `{"value":"new"}`); err != nil || result != EnvironmentReportUpdated {
		t.Fatalf("replacement StoreDaily() = %v, %v", result, err)
	}
	if result, err := repo.StoreDaily(context.Background(), 7, EnvironmentReportKindDeviceIntegrity, `{"value":"device"}`); err != nil || result != EnvironmentReportInserted {
		t.Fatalf("device StoreDaily() = %v, %v", result, err)
	}

	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.reports) != 2 || state.environmentInserts != 2 || state.environmentUpdates != 1 {
		t.Fatalf("unexpected coalescing state: rows=%d inserts=%d updates=%d", len(state.reports), state.environmentInserts, state.environmentUpdates)
	}
}

func TestEnvironmentReportNestedKindCannotCrossMatchServerClassification(t *testing.T) {
	state := &securityQuotaDBState{}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewEnvironmentReportRepository(db)

	if result, err := repo.StoreDaily(context.Background(), 17, EnvironmentReportKindEnvironment, `{"nested":{"report_kind":"device_integrity"}}`); err != nil || result != EnvironmentReportInserted {
		t.Fatalf("environment StoreDaily() = %v, %v", result, err)
	}
	if result, err := repo.StoreDaily(context.Background(), 17, EnvironmentReportKindDeviceIntegrity, `{"nested":{"report_kind":"environment"}}`); err != nil || result != EnvironmentReportInserted {
		t.Fatalf("device StoreDaily() = %v, %v", result, err)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.reports) != 2 || state.environmentInserts != 2 || state.environmentUpdates != 0 {
		t.Fatalf("nested kind crossed classifications: rows=%d inserts=%d updates=%d", len(state.reports), state.environmentInserts, state.environmentUpdates)
	}
}

func TestEnvironmentReportStoreDailyRejectsBeforeInsertAtDailyQuota(t *testing.T) {
	state := &securityQuotaDBState{}
	for index := 0; index < EnvironmentReportDailyRowLimit; index++ {
		state.reports = append(state.reports, securityQuotaEnvironmentRow{id: int64(index + 1), userID: 9, payload: fmt.Sprintf(`{"legacy":%d}`, index)})
	}
	state.nextEnvironmentID = EnvironmentReportDailyRowLimit
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()

	result, err := NewEnvironmentReportRepository(db).StoreDaily(context.Background(), 9, EnvironmentReportKindEnvironment, `{"value":"blocked"}`)
	if err != nil || result != EnvironmentReportQuotaExceeded {
		t.Fatalf("StoreDaily() = %v, %v", result, err)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.environmentInserts != 0 || len(state.reports) != EnvironmentReportDailyRowLimit {
		t.Fatalf("quota rejection still wrote a row: inserts=%d rows=%d", state.environmentInserts, len(state.reports))
	}
}

func TestEnvironmentReportRetentionIsScopedToUser(t *testing.T) {
	state := &securityQuotaDBState{nextEnvironmentID: 200}
	for index := 1; index <= 100; index++ {
		state.reports = append(state.reports, securityQuotaEnvironmentRow{id: int64(index), userID: 9, payload: fmt.Sprintf(`{"legacy":%d}`, index)})
	}
	for index := 101; index <= 103; index++ {
		state.reports = append(state.reports, securityQuotaEnvironmentRow{id: int64(index), userID: 10, payload: fmt.Sprintf(`{"other":%d}`, index)})
	}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()

	result, err := NewEnvironmentReportRepository(db).StoreDaily(context.Background(), 9, EnvironmentReportKindEnvironment, `{"value":"new"}`)
	if err != nil || result != EnvironmentReportQuotaExceeded {
		t.Fatalf("StoreDaily() = %v, %v", result, err)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	userRows := 0
	otherRows := 0
	for _, row := range state.reports {
		if row.userID == 9 {
			userRows++
		}
		if row.userID == 10 {
			otherRows++
		}
	}
	if userRows != EnvironmentReportRetentionRows || otherRows != 3 {
		t.Fatalf("retention rows = user:%d other:%d", userRows, otherRows)
	}
}

func TestEnvironmentReportLegacyBacklogCleanupIsBoundedAndConverges(t *testing.T) {
	state := &securityQuotaDBState{nextEnvironmentID: 1000}
	for index := 1; index <= 1000; index++ {
		state.reports = append(state.reports, securityQuotaEnvironmentRow{id: int64(index), userID: 19, payload: fmt.Sprintf(`{"legacy":%d}`, index)})
	}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewEnvironmentReportRepository(db)

	if result, err := repo.StoreDaily(context.Background(), 19, EnvironmentReportKindEnvironment, `{"value":"blocked"}`); err != nil || result != EnvironmentReportQuotaExceeded {
		t.Fatalf("first StoreDaily() = %v, %v", result, err)
	}
	if rows := environmentRowsForUser(state, 19); rows != 500 {
		t.Fatalf("first bounded cleanup left %d rows, want 500", rows)
	}
	if result, err := repo.StoreDaily(context.Background(), 19, EnvironmentReportKindEnvironment, `{"value":"blocked"}`); err != nil || result != EnvironmentReportQuotaExceeded {
		t.Fatalf("second StoreDaily() = %v, %v", result, err)
	}
	if rows := environmentRowsForUser(state, 19); rows != EnvironmentReportRetentionRows {
		t.Fatalf("continued cleanup left %d rows, want %d", rows, EnvironmentReportRetentionRows)
	}
}

func TestEnvironmentReportPostInsertPruneKeepsExactRetentionLimit(t *testing.T) {
	dailyRows := 0
	state := &securityQuotaDBState{
		nextEnvironmentID:            EnvironmentReportRetentionRows,
		environmentDailyRowsOverride: &dailyRows,
	}
	for index := 1; index <= EnvironmentReportRetentionRows; index++ {
		state.reports = append(state.reports, securityQuotaEnvironmentRow{id: int64(index), userID: 23, payload: fmt.Sprintf(`{"legacy":%d}`, index)})
	}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()

	result, err := NewEnvironmentReportRepository(db).StoreDaily(context.Background(), 23, EnvironmentReportKindEnvironment, `{"value":"new"}`)
	if err != nil || result != EnvironmentReportInserted {
		t.Fatalf("StoreDaily() = %v, %v", result, err)
	}
	if rows := environmentRowsForUser(state, 23); rows != EnvironmentReportRetentionRows {
		t.Fatalf("post-insert retention rows = %d, want %d", rows, EnvironmentReportRetentionRows)
	}
}

func environmentRowsForUser(state *securityQuotaDBState, userID int64) int {
	state.mu.Lock()
	defer state.mu.Unlock()
	count := 0
	for _, row := range state.reports {
		if row.userID == userID {
			count++
		}
	}
	return count
}

func TestAppChallengeInsertCleansExpiredBatchBeforeWriting(t *testing.T) {
	state := &securityQuotaDBState{}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewAppChallengeRepository(db)

	err := repo.Insert(context.Background(), models.AppChallenge{
		ID:                strings.Repeat("a", 32),
		SecretHash:        strings.Repeat("b", 64),
		DeviceFingerprint: strings.Repeat("c", 64),
		Purpose:           "login",
		ExpiresAt:         time.Now().Add(5 * time.Minute),
	})
	if err != nil {
		t.Fatalf("Insert() error = %v", err)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if len(state.calls) != 2 || !strings.HasPrefix(state.calls[0].query, "DELETE FROM app_challenges") || !strings.HasPrefix(state.calls[1].query, "INSERT INTO app_challenges") {
		t.Fatalf("challenge call order = %#v", state.calls)
	}
	if len(state.calls[0].args) != 2 || state.calls[0].args[1] != int64(expiredChallengeCleanupBatch) {
		t.Fatalf("cleanup args = %#v", state.calls[0].args)
	}
	if !strings.Contains(state.calls[0].query, "expires_at <= ?") || state.challengeInserts != 1 || state.commits != 1 {
		t.Fatalf("cleanup boundary was not committed before insert: %#v", state)
	}
}

func TestAppChallengeInsertStopsWhenCleanupFails(t *testing.T) {
	state := &securityQuotaDBState{failChallengeCleanup: true}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()

	err := NewAppChallengeRepository(db).Insert(context.Background(), models.AppChallenge{
		ID:                strings.Repeat("d", 32),
		SecretHash:        strings.Repeat("e", 64),
		DeviceFingerprint: strings.Repeat("f", 64),
		Purpose:           "register",
		ExpiresAt:         time.Now().Add(5 * time.Minute),
	})
	if err == nil {
		t.Fatal("Insert() succeeded after cleanup failure")
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.challengeInserts != 0 || state.commits != 0 {
		t.Fatalf("cleanup failure still persisted challenge: inserts=%d commits=%d", state.challengeInserts, state.commits)
	}
}

func TestAppChallengeExpiredBacklogDrainsInBoundedBatchesWithoutInsert(t *testing.T) {
	state := &securityQuotaDBState{challengeCleanupRows: []int64{500, 500, 12}}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	repo := NewAppChallengeRepository(db)

	deleted, err := repo.DeleteExpiredBatches(context.Background(), time.Now(), 500, 10)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 1012 {
		t.Fatalf("DeleteExpiredBatches() deleted %d rows, want 1012", deleted)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.challengeCleanupCalls != 3 || state.challengeInserts != 0 {
		t.Fatalf("cleanup calls=%d inserts=%d", state.challengeCleanupCalls, state.challengeInserts)
	}
}

func TestAppChallengeRequestCleanupRetriesFullBatchAndRestartsFresh(t *testing.T) {
	state := &securityQuotaDBState{challengeCleanupRows: []int64{500, 0, 0}}
	db := newSecurityQuotaTestDB(t, state)
	defer db.Close()
	now := time.Now()
	repo := NewAppChallengeRepository(db)

	if err := repo.DeleteExpiredIfDue(context.Background(), now, time.Hour, 500); err != nil {
		t.Fatal(err)
	}
	if err := repo.DeleteExpiredIfDue(context.Background(), now.Add(time.Second), time.Hour, 500); err != nil {
		t.Fatal(err)
	}
	if err := repo.DeleteExpiredIfDue(context.Background(), now.Add(2*time.Second), time.Hour, 500); err != nil {
		t.Fatal(err)
	}
	if calls := challengeCleanupCallCount(state); calls != 2 {
		t.Fatalf("full-batch continuation/throttle calls = %d, want 2", calls)
	}

	restartedRepo := NewAppChallengeRepository(db)
	if err := restartedRepo.DeleteExpiredIfDue(context.Background(), now.Add(3*time.Second), time.Hour, 500); err != nil {
		t.Fatal(err)
	}
	if calls := challengeCleanupCallCount(state); calls != 3 {
		t.Fatalf("fresh repository did not run startup-equivalent cleanup: calls=%d", calls)
	}
}

func challengeCleanupCallCount(state *securityQuotaDBState) int {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.challengeCleanupCalls
}

var securityQuotaDriverID uint64

type securityQuotaDBCall struct {
	query string
	args  []driver.Value
}

type securityQuotaEnvironmentRow struct {
	id      int64
	userID  int64
	payload string
}

type securityQuotaDBState struct {
	serial                       sync.Mutex
	mu                           sync.Mutex
	reports                      []securityQuotaEnvironmentRow
	nextEnvironmentID            int
	environmentDailyRowsOverride *int
	environmentInserts           int
	environmentUpdates           int
	challengeInserts             int
	challengeCleanupRows         []int64
	challengeCleanupCalls        int
	failChallengeCleanup         bool
	commits                      int
	calls                        []securityQuotaDBCall
}

func newSecurityQuotaTestDB(t *testing.T, state *securityQuotaDBState) *sql.DB {
	t.Helper()
	driverName := fmt.Sprintf("security-quota-test-%d", atomic.AddUint64(&securityQuotaDriverID, 1))
	sql.Register(driverName, securityQuotaDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	db.SetMaxOpenConns(32)
	return db
}

type securityQuotaDriver struct {
	state *securityQuotaDBState
}

func (testDriver securityQuotaDriver) Open(string) (driver.Conn, error) {
	return &securityQuotaConn{state: testDriver.state}, nil
}

type securityQuotaConn struct {
	state *securityQuotaDBState
	tx    *securityQuotaTx
}

func (connection *securityQuotaConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("prepared statements are not supported")
}

func (connection *securityQuotaConn) Close() error { return nil }

func (connection *securityQuotaConn) Begin() (driver.Tx, error) {
	return connection.BeginTx(context.Background(), driver.TxOptions{})
}

func (connection *securityQuotaConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	transaction := &securityQuotaTx{state: connection.state}
	connection.tx = transaction
	return transaction, nil
}

func (connection *securityQuotaConn) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	normalized := normalizeSecurityQuotaSQL(query)
	switch {
	case strings.HasPrefix(normalized, "SELECT id FROM users"):
		connection.state.serial.Lock()
		connection.tx.serialLocked = true
		return securityQuotaRows([]string{"id"}, []driver.Value{args[0].Value}), nil
	case strings.HasPrefix(normalized, "SELECT id, report_json FROM environment_reports"):
		userID := args[0].Value.(int64)
		reportKind := args[1].Value.(string)
		connection.state.mu.Lock()
		defer connection.state.mu.Unlock()
		for index := len(connection.state.reports) - 1; index >= 0; index-- {
			row := connection.state.reports[index]
			var report map[string]any
			_ = json.Unmarshal([]byte(row.payload), &report)
			if row.userID == userID && report["report_kind"] == reportKind {
				return securityQuotaRows([]string{"id", "report_json"}, []driver.Value{row.id, row.payload}), nil
			}
		}
		return &securityQuotaTestRows{columns: []string{"id", "report_json"}}, nil
	case strings.HasPrefix(normalized, "SELECT id FROM environment_reports"):
		userID := args[0].Value.(int64)
		offset := int(args[1].Value.(int64))
		connection.state.mu.Lock()
		ids := make([]int64, 0)
		for _, row := range connection.state.reports {
			if row.userID == userID {
				ids = append(ids, row.id)
			}
		}
		connection.state.mu.Unlock()
		sort.Slice(ids, func(left int, right int) bool { return ids[left] > ids[right] })
		if offset >= len(ids) {
			return &securityQuotaTestRows{columns: []string{"id"}}, nil
		}
		return securityQuotaRows([]string{"id"}, []driver.Value{ids[offset]}), nil
	case strings.HasPrefix(normalized, "SELECT COUNT(*) FROM environment_reports"):
		userID := args[0].Value.(int64)
		count := int64(0)
		connection.state.mu.Lock()
		if connection.state.environmentDailyRowsOverride != nil {
			count = int64(*connection.state.environmentDailyRowsOverride)
		} else {
			for _, row := range connection.state.reports {
				if row.userID == userID {
					count++
				}
			}
		}
		connection.state.mu.Unlock()
		return securityQuotaRows([]string{"count"}, []driver.Value{count}), nil
	default:
		return nil, fmt.Errorf("unexpected query: %s", normalized)
	}
}

func (connection *securityQuotaConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	normalized := normalizeSecurityQuotaSQL(query)
	values := securityQuotaNamedValues(args)
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	connection.state.calls = append(connection.state.calls, securityQuotaDBCall{query: normalized, args: values})
	switch {
	case strings.HasPrefix(normalized, "INSERT INTO environment_reports"):
		connection.state.nextEnvironmentID++
		connection.state.environmentInserts++
		connection.state.reports = append(connection.state.reports, securityQuotaEnvironmentRow{
			id:      int64(connection.state.nextEnvironmentID),
			userID:  args[0].Value.(int64),
			payload: args[1].Value.(string),
		})
		return securityQuotaResult{lastInsertID: int64(connection.state.nextEnvironmentID), rowsAffected: 1}, nil
	case strings.HasPrefix(normalized, "UPDATE environment_reports"):
		payload := args[0].Value.(string)
		id := args[1].Value.(int64)
		for index := range connection.state.reports {
			if connection.state.reports[index].id == id {
				connection.state.reports[index].payload = payload
			}
		}
		connection.state.environmentUpdates++
		return securityQuotaResult{rowsAffected: 1}, nil
	case strings.HasPrefix(normalized, "DELETE FROM environment_reports"):
		userID := args[0].Value.(int64)
		boundaryID := args[1].Value.(int64)
		limit := int(args[2].Value.(int64))
		candidateIDs := make([]int64, 0)
		for _, row := range connection.state.reports {
			if row.userID == userID && row.id < boundaryID {
				candidateIDs = append(candidateIDs, row.id)
			}
		}
		sort.Slice(candidateIDs, func(left int, right int) bool { return candidateIDs[left] < candidateIDs[right] })
		if len(candidateIDs) > limit {
			candidateIDs = candidateIDs[:limit]
		}
		deleteIDs := make(map[int64]struct{}, len(candidateIDs))
		for _, id := range candidateIDs {
			deleteIDs[id] = struct{}{}
		}
		retained := connection.state.reports[:0]
		deleted := int64(0)
		for _, row := range connection.state.reports {
			if _, shouldDelete := deleteIDs[row.id]; row.userID == userID && shouldDelete {
				deleted++
				continue
			}
			retained = append(retained, row)
		}
		connection.state.reports = retained
		return securityQuotaResult{rowsAffected: deleted}, nil
	case strings.HasPrefix(normalized, "DELETE FROM app_challenges"):
		if connection.state.failChallengeCleanup {
			return nil, errors.New("cleanup failed")
		}
		rowsAffected := int64(3)
		if connection.state.challengeCleanupCalls < len(connection.state.challengeCleanupRows) {
			rowsAffected = connection.state.challengeCleanupRows[connection.state.challengeCleanupCalls]
		}
		connection.state.challengeCleanupCalls++
		return securityQuotaResult{rowsAffected: rowsAffected}, nil
	case strings.HasPrefix(normalized, "INSERT INTO app_challenges"):
		connection.state.challengeInserts++
		return securityQuotaResult{rowsAffected: 1}, nil
	default:
		return nil, fmt.Errorf("unexpected exec: %s", normalized)
	}
}

type securityQuotaTx struct {
	state        *securityQuotaDBState
	serialLocked bool
	done         bool
}

func (transaction *securityQuotaTx) Commit() error {
	if transaction.done {
		return nil
	}
	transaction.done = true
	transaction.state.mu.Lock()
	transaction.state.commits++
	transaction.state.mu.Unlock()
	if transaction.serialLocked {
		transaction.state.serial.Unlock()
	}
	return nil
}

func (transaction *securityQuotaTx) Rollback() error {
	if transaction.done {
		return nil
	}
	transaction.done = true
	if transaction.serialLocked {
		transaction.state.serial.Unlock()
	}
	return nil
}

type securityQuotaResult struct {
	lastInsertID int64
	rowsAffected int64
}

func (result securityQuotaResult) LastInsertId() (int64, error) { return result.lastInsertID, nil }
func (result securityQuotaResult) RowsAffected() (int64, error) { return result.rowsAffected, nil }

type securityQuotaTestRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func securityQuotaRows(columns []string, values []driver.Value) driver.Rows {
	return &securityQuotaTestRows{columns: columns, values: [][]driver.Value{values}}
}

func (rows *securityQuotaTestRows) Columns() []string { return rows.columns }
func (rows *securityQuotaTestRows) Close() error      { return nil }
func (rows *securityQuotaTestRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}

func normalizeSecurityQuotaSQL(query string) string {
	return strings.Join(strings.Fields(query), " ")
}

func securityQuotaNamedValues(args []driver.NamedValue) []driver.Value {
	values := make([]driver.Value, len(args))
	for index, arg := range args {
		values[index] = arg.Value
	}
	return values
}
