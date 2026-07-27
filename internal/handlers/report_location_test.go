package handlers

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
)

func TestPlainLocationRetentionIsScopedToUserAndGroup(t *testing.T) {
	handler, state, closeDB := newReportLocationTestHandler(t, nil)
	defer closeDB()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertPlainLocation(recorder, request, reportLocationTestScope(), validPlainLocationData(), "", false, map[string]any{"platform": "android"})

	if recorder.Code != http.StatusOK {
		t.Fatalf("insertPlainLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	assertSerializedReportInsert(t, state)
	assertScopedHistoryPrune(t, state)
	assertEmbeddedDeviceReportStoredAfterLocationCommit(t, state)
}

func TestEncryptedLocationRetentionIsScopedToUserAndGroup(t *testing.T) {
	handler, state, closeDB := newReportLocationTestHandler(t, nil)
	defer closeDB()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, map[string]any{"platform": "android"})

	if recorder.Code != http.StatusOK {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	assertSerializedReportInsert(t, state)
	assertScopedHistoryPrune(t, state)
	assertEmbeddedDeviceReportStoredAfterLocationCommit(t, state)
}

func TestEncryptedLocationHonorsMinimumReportInterval(t *testing.T) {
	recent := time.Now().Add(-time.Second)
	handler, state, closeDB := newReportLocationTestHandler(t, &recent)
	defer closeDB()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, nil)

	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusTooManyRequests, recorder.Body.String())
	}
	if state.hasExecContaining("INSERT INTO locations") {
		t.Fatal("encrypted location was inserted despite the minimum report interval")
	}
}

func TestPlainLocationHonorsMinimumReportInterval(t *testing.T) {
	recent := time.Now().Add(-time.Second)
	handler, state, closeDB := newReportLocationTestHandler(t, &recent)
	defer closeDB()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertPlainLocation(recorder, request, reportLocationTestScope(), validPlainLocationData(), "", false, nil)

	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("insertPlainLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusTooManyRequests, recorder.Body.String())
	}
	if state.hasExecContaining("INSERT INTO locations") {
		t.Fatal("plain location was inserted despite the minimum report interval")
	}
}

func TestEncryptedLocationRejectsFutureLatestTimestamp(t *testing.T) {
	future := time.Now().Add(time.Minute)
	handler, state, closeDB := newReportLocationTestHandler(t, &future)
	defer closeDB()

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, nil)

	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusTooManyRequests, recorder.Body.String())
	}
	if state.hasExecContaining("INSERT INTO locations") {
		t.Fatal("encrypted location was inserted after a future-dated latest row")
	}
}

func TestNonPositiveHistoryLimitUsesFiniteFallback(t *testing.T) {
	handler, state, closeDB := newReportLocationTestHandler(t, nil)
	defer closeDB()
	handler.cfg.Location.HistoryLimit = 0

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, nil)

	if recorder.Code != http.StatusOK {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	call, ok := state.execContaining("DELETE FROM locations")
	if !ok || len(call.args) != 6 || call.args[4] != int64(5000) {
		t.Fatalf("non-positive history limit did not use finite fallback: %#v", call)
	}
}

func TestEncryptedLocationRollsBackWhenHistoryPruneFails(t *testing.T) {
	handler, state, closeDB := newReportLocationTestHandler(t, nil)
	defer closeDB()
	state.failPrune = true

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, nil)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusInternalServerError, recorder.Body.String())
	}
	events := state.eventSnapshot()
	if eventIndexContaining(events, "ROLLBACK") < 0 || eventIndexContaining(events, "COMMIT") >= 0 {
		t.Fatalf("failed history prune did not roll back the location transaction: %#v", events)
	}
}

func TestEmbeddedDeviceReportFailureDoesNotRollbackCommittedLocation(t *testing.T) {
	handler, state, closeDB := newReportLocationTestHandler(t, nil)
	defer closeDB()
	state.failEnvironmentStore = true

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/api/report-location", nil)
	handler.insertEncryptedLocation(recorder, request, reportLocationTestScope(), `{"ciphertext":"opaque"}`, 3, map[string]any{"platform": "android"})

	if recorder.Code != http.StatusOK {
		t.Fatalf("insertEncryptedLocation() status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	events := state.eventSnapshot()
	commitIndex := eventIndexContaining(events, "COMMIT")
	rollbackIndex := eventIndexContaining(events, "ROLLBACK")
	if commitIndex < 0 || rollbackIndex < 0 || commitIndex > rollbackIndex {
		t.Fatalf("device report failure affected the location transaction boundary: %#v", events)
	}
	if state.hasExecContaining("INSERT INTO environment_reports") {
		t.Fatal("environment report was inserted despite the injected repository failure")
	}
}

func assertEmbeddedDeviceReportStoredAfterLocationCommit(t *testing.T, state *reportLocationDBState) {
	t.Helper()
	events := state.eventSnapshot()
	call, ok := state.execContaining("INSERT INTO environment_reports")
	if !ok {
		t.Fatalf("embedded device report was not stored through the daily repository: %#v", events)
	}
	if len(call.args) != 2 || call.args[0] != int64(41) {
		t.Fatalf("environment report insert args = %#v", call.args)
	}
	storedJSON, ok := call.args[1].(string)
	if !ok {
		t.Fatalf("stored environment report payload = %#v, want string", call.args[1])
	}
	var stored map[string]any
	if err := json.Unmarshal([]byte(storedJSON), &stored); err != nil {
		t.Fatalf("stored report is not JSON: %v", err)
	}
	if stored["report_kind"] != repositories.EnvironmentReportKindDeviceIntegrity || stored["location_id"] != float64(101) || stored["group_name"] != "family-a" {
		t.Fatalf("stored report metadata = %#v", stored)
	}
	pruneIndex := eventIndexContaining(events, "DELETE FROM locations")
	commitIndex := eventIndexContaining(events, "COMMIT")
	userLockIndexes := eventIndexesContaining(events, "SELECT id", "FROM users", "FOR UPDATE")
	environmentInsertIndex := eventIndexContaining(events, "INSERT INTO environment_reports")
	if pruneIndex < 0 || commitIndex < 0 || len(userLockIndexes) < 2 || environmentInsertIndex < 0 {
		t.Fatalf("location commit and daily report sequence is incomplete: %#v", events)
	}
	if pruneIndex > commitIndex || commitIndex > userLockIndexes[1] || userLockIndexes[1] > environmentInsertIndex {
		t.Fatalf("embedded device report was not stored after the location transaction committed: %#v", events)
	}
}

func reportLocationTestScope() *userScope {
	return &userScope{
		User: &models.User{ID: 41},
		Membership: &models.Membership{
			GroupName: "family-a",
			Role:      "member",
		},
	}
}

func validPlainLocationData() map[string]any {
	return map[string]any{
		"latitude":                   31.2304,
		"longitude":                  121.4737,
		"accuracy":                   10.0,
		"location_provider":          "gps",
		"location_time":              time.Now().UnixMilli(),
		"location_mock_provider":     false,
		"location_coordinate_system": "wgs84",
	}
}

func newReportLocationTestHandler(t *testing.T, latestCreatedAt *time.Time) (ReportLocationHandler, *reportLocationDBState, func()) {
	t.Helper()
	state := &reportLocationDBState{latestCreatedAt: latestCreatedAt}
	driverName := fmt.Sprintf("report-location-test-%d", atomic.AddUint64(&reportLocationDriverID, 1))
	sql.Register(driverName, reportLocationDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	handler := ReportLocationHandler{
		cfg: config.Config{Location: config.LocationConfig{
			HistoryLimit:             2,
			MinReportSeconds:         10,
			MaxAccuracyMeters:        100,
			MaxSpeedMPS:              120,
			MaxReasonableTravelMPS:   120,
			MaxLocationAgeSeconds:    60,
			MaxLocationFutureSeconds: 15,
			JumpAllowanceMeters:      100,
			MaxStationaryJumpMeters:  200,
			MaxStationarySpeedMPS:    2,
		}},
		db:        db,
		users:     repositories.NewUserRepository(db),
		locations: repositories.NewLocationRepository(db),
		reports:   repositories.NewEnvironmentReportRepository(db),
	}
	return handler, state, func() { _ = db.Close() }
}

func assertScopedHistoryPrune(t *testing.T, state *reportLocationDBState) {
	t.Helper()
	call, ok := state.execContaining("DELETE FROM locations")
	if !ok {
		t.Fatal("location insert did not prune retained history")
	}
	wantArgs := []driver.Value{"family-a", int64(41), "family-a", int64(41), int64(2), int64(500)}
	if !reflect.DeepEqual(call.args, wantArgs) {
		t.Fatalf("history prune args = %#v, want %#v", call.args, wantArgs)
	}
	if strings.Count(call.query, "group_name = ?") < 2 || strings.Count(call.query, "user_id = ?") < 2 {
		t.Fatalf("history prune is not scoped in both the delete and retained-row selection: %s", call.query)
	}
	if !strings.Contains(call.query, "ORDER BY id ASC") || !strings.Contains(call.query, "LIMIT ?") || strings.Contains(call.query, "NOT IN") {
		t.Fatalf("history prune is not deterministic and batch-bounded: %s", call.query)
	}
}

func assertSerializedReportInsert(t *testing.T, state *reportLocationDBState) {
	t.Helper()
	events := state.eventSnapshot()
	lockIndex := eventIndexContaining(events, "SELECT id", "FROM users", "FOR UPDATE")
	intervalIndex := eventIndexContaining(events, "SELECT created_at")
	insertIndex := eventIndexContaining(events, "INSERT INTO locations")
	if lockIndex < 0 || intervalIndex < 0 || insertIndex < 0 {
		t.Fatalf("report insert did not execute lock, interval check, and insert: %#v", events)
	}
	if lockIndex > intervalIndex || intervalIndex > insertIndex {
		t.Fatalf("report insert was not serialized before the interval check and insert: %#v", events)
	}
	lockCall, ok := state.queryContaining("SELECT id", "FROM users", "FOR UPDATE")
	if !ok || !strings.Contains(lockCall.query, "WHERE id = ?") || !reflect.DeepEqual(lockCall.args, []driver.Value{int64(41)}) {
		t.Fatalf("report user lock is not scoped to the authenticated user: %#v", lockCall)
	}
}

func eventIndexContaining(events []string, fragments ...string) int {
	for index, event := range events {
		matches := true
		for _, fragment := range fragments {
			if !strings.Contains(event, fragment) {
				matches = false
				break
			}
		}
		if matches {
			return index
		}
	}
	return -1
}

func eventIndexesContaining(events []string, fragments ...string) []int {
	var indexes []int
	for index, event := range events {
		matches := true
		for _, fragment := range fragments {
			if !strings.Contains(event, fragment) {
				matches = false
				break
			}
		}
		if matches {
			indexes = append(indexes, index)
		}
	}
	return indexes
}

var reportLocationDriverID uint64

type reportLocationDBCall struct {
	query string
	args  []driver.Value
}

type reportLocationDBState struct {
	mu                   sync.Mutex
	execs                []reportLocationDBCall
	queries              []reportLocationDBCall
	events               []string
	latestCreatedAt      *time.Time
	userLockCount        int
	failPrune            bool
	failEnvironmentStore bool
}

func (state *reportLocationDBState) recordExec(query string, args []driver.NamedValue) {
	state.mu.Lock()
	defer state.mu.Unlock()
	values := make([]driver.Value, len(args))
	for index, arg := range args {
		values[index] = arg.Value
	}
	state.execs = append(state.execs, reportLocationDBCall{query: query, args: values})
	state.events = append(state.events, query)
}

func (state *reportLocationDBState) recordQuery(query string, args []driver.NamedValue) {
	state.mu.Lock()
	defer state.mu.Unlock()
	values := make([]driver.Value, len(args))
	for index, arg := range args {
		values[index] = arg.Value
	}
	state.queries = append(state.queries, reportLocationDBCall{query: query, args: values})
	state.events = append(state.events, query)
}

func (state *reportLocationDBState) recordEvent(event string) {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.events = append(state.events, event)
}

func (state *reportLocationDBState) execContaining(fragment string) (reportLocationDBCall, bool) {
	state.mu.Lock()
	defer state.mu.Unlock()
	for _, call := range state.execs {
		if strings.Contains(call.query, fragment) {
			return call, true
		}
	}
	return reportLocationDBCall{}, false
}

func (state *reportLocationDBState) hasExecContaining(fragment string) bool {
	_, ok := state.execContaining(fragment)
	return ok
}

func (state *reportLocationDBState) queryContaining(fragments ...string) (reportLocationDBCall, bool) {
	state.mu.Lock()
	defer state.mu.Unlock()
	for _, call := range state.queries {
		matches := true
		for _, fragment := range fragments {
			if !strings.Contains(call.query, fragment) {
				matches = false
				break
			}
		}
		if matches {
			return call, true
		}
	}
	return reportLocationDBCall{}, false
}

func (state *reportLocationDBState) eventSnapshot() []string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return append([]string(nil), state.events...)
}

func (state *reportLocationDBState) userLockError() error {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.userLockCount++
	if state.failEnvironmentStore && state.userLockCount > 1 {
		return errors.New("injected environment report store failure")
	}
	return nil
}

func (state *reportLocationDBState) shouldFailPrune(query string) bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.failPrune && strings.Contains(query, "DELETE FROM locations")
}

type reportLocationDriver struct {
	state *reportLocationDBState
}

func (driverInstance reportLocationDriver) Open(string) (driver.Conn, error) {
	return &reportLocationConn{state: driverInstance.state}, nil
}

type reportLocationConn struct {
	state *reportLocationDBState
}

func (connection *reportLocationConn) Prepare(string) (driver.Stmt, error) {
	return nil, fmt.Errorf("prepared statements are not supported by the report-location test driver")
}

func (connection *reportLocationConn) Close() error {
	return nil
}

func (connection *reportLocationConn) Begin() (driver.Tx, error) {
	return reportLocationTx{state: connection.state}, nil
}

func (connection *reportLocationConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return reportLocationTx{state: connection.state}, nil
}

func (connection *reportLocationConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	connection.state.recordExec(query, args)
	if connection.state.shouldFailPrune(query) {
		return nil, errors.New("injected location prune failure")
	}
	return reportLocationResult{lastInsertID: 101, rowsAffected: 1}, nil
}

func (connection *reportLocationConn) QueryContext(_ context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	connection.state.recordQuery(query, args)
	if strings.Contains(query, "SELECT id") && strings.Contains(query, "FROM users") && strings.Contains(query, "FOR UPDATE") {
		if err := connection.state.userLockError(); err != nil {
			return nil, err
		}
		return &reportLocationRows{
			columns: []string{"id"},
			values:  [][]driver.Value{{int64(41)}},
		}, nil
	}
	if strings.Contains(query, "FROM user_groups") && strings.Contains(query, "FOR UPDATE") {
		return &reportLocationRows{
			columns: []string{"user_id"},
			values:  [][]driver.Value{{int64(41)}},
		}, nil
	}
	if strings.Contains(query, "SELECT created_at") {
		if connection.state.latestCreatedAt == nil {
			return &reportLocationRows{columns: []string{"created_at"}}, nil
		}
		return &reportLocationRows{
			columns: []string{"created_at"},
			values:  [][]driver.Value{{*connection.state.latestCreatedAt}},
		}, nil
	}
	if strings.Contains(query, "SELECT latitude, longitude, accuracy, created_at") {
		return &reportLocationRows{columns: []string{"latitude", "longitude", "accuracy", "created_at"}}, nil
	}
	if strings.Contains(query, "SELECT id, report_json") && strings.Contains(query, "FROM environment_reports") {
		return &reportLocationRows{columns: []string{"id", "report_json"}}, nil
	}
	if strings.Contains(query, "SELECT COUNT(*) FROM environment_reports") {
		return &reportLocationRows{
			columns: []string{"count"},
			values:  [][]driver.Value{{int64(0)}},
		}, nil
	}
	return &reportLocationRows{columns: []string{"value"}}, nil
}

type reportLocationTx struct {
	state *reportLocationDBState
}

func (tx reportLocationTx) Commit() error {
	tx.state.recordEvent("COMMIT")
	return nil
}

func (tx reportLocationTx) Rollback() error {
	tx.state.recordEvent("ROLLBACK")
	return nil
}

type reportLocationResult struct {
	lastInsertID int64
	rowsAffected int64
}

func (result reportLocationResult) LastInsertId() (int64, error) { return result.lastInsertID, nil }
func (result reportLocationResult) RowsAffected() (int64, error) { return result.rowsAffected, nil }

type reportLocationRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func (rows *reportLocationRows) Columns() []string { return rows.columns }
func (rows *reportLocationRows) Close() error      { return nil }

func (rows *reportLocationRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}
