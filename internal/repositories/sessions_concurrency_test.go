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

func TestUserSessionCreatedBeforePasswordResetIsDeletedByReset(t *testing.T) {
	const userID int64 = 71
	state := newLinearSessionState(userID, "old-hash")
	state.pauseSessionAfterLock = true
	state.sessions["existing-session"] = userID
	repo, closeDB := openLinearSessionRepository(t, state)
	defer closeDB()

	createDone := make(chan linearCreateResult, 1)
	go func() {
		created, err := repo.CreateUserSessionIfPasswordMatches(
			context.Background(), "in-flight-session", userID, "old-hash", time.Now().Add(time.Hour),
		)
		createDone <- linearCreateResult{created: created, err: err}
	}()
	waitLinearSignal(t, state.sessionLockAcquired, "session creation to acquire the user lock")

	resetDone := make(chan error, 1)
	go func() {
		resetDone <- repo.UpdatePasswordAndRevokeUserSessions(context.Background(), userID, "new-hash")
	}()
	waitLinearSignal(t, state.resetLockAttempted, "password reset to attempt the user lock")
	assertLinearOperationBlocked(t, resetDone, "password reset while session creation owns the user lock")

	close(state.allowSessionAfterLock)
	createResult := <-createDone
	if createResult.err != nil || !createResult.created {
		t.Fatalf("session creation result = created:%v err:%v, want created", createResult.created, createResult.err)
	}
	if err := <-resetDone; err != nil {
		t.Fatalf("UpdatePasswordAndRevokeUserSessions() error = %v", err)
	}
	if got := state.password(); got != "new-hash" {
		t.Fatalf("password hash = %q, want new-hash", got)
	}
	if got := state.sessionCount(userID); got != 0 {
		t.Fatalf("post-reset session count = %d, want 0", got)
	}
}

func TestPasswordResetBeforeUserSessionCreationRejectsOldHash(t *testing.T) {
	const userID int64 = 72
	state := newLinearSessionState(userID, "old-hash")
	state.pauseResetAfterLock = true
	state.sessions["existing-session"] = userID
	repo, closeDB := openLinearSessionRepository(t, state)
	defer closeDB()

	resetDone := make(chan error, 1)
	go func() {
		resetDone <- repo.UpdatePasswordAndRevokeUserSessions(context.Background(), userID, "new-hash")
	}()
	waitLinearSignal(t, state.resetLockAcquired, "password reset to acquire the user lock")

	createDone := make(chan linearCreateResult, 1)
	go func() {
		created, err := repo.CreateUserSessionIfPasswordMatches(
			context.Background(), "stale-session", userID, "old-hash", time.Now().Add(time.Hour),
		)
		createDone <- linearCreateResult{created: created, err: err}
	}()
	waitLinearSignal(t, state.sessionLockAttempted, "session creation to attempt the user lock")
	assertLinearOperationBlocked(t, createDone, "session creation while password reset owns the user lock")

	close(state.allowResetAfterLock)
	if err := <-resetDone; err != nil {
		t.Fatalf("UpdatePasswordAndRevokeUserSessions() error = %v", err)
	}
	createResult := <-createDone
	if createResult.err != nil {
		t.Fatalf("CreateUserSessionIfPasswordMatches() error = %v", createResult.err)
	}
	if createResult.created {
		t.Fatal("old-hash session was created after password reset committed")
	}
	if got := state.password(); got != "new-hash" {
		t.Fatalf("password hash = %q, want new-hash", got)
	}
	if got := state.sessionCount(userID); got != 0 {
		t.Fatalf("post-reset session count = %d, want 0", got)
	}
}

func TestPasswordResetBeforeSelfServiceChangePreventsStaleOverwrite(t *testing.T) {
	const userID int64 = 73
	state := newLinearSessionState(userID, "old-hash")
	state.pauseResetAfterLock = true
	state.sessions["existing-session"] = userID
	repo, closeDB := openLinearSessionRepository(t, state)
	defer closeDB()

	resetDone := make(chan error, 1)
	go func() {
		resetDone <- repo.UpdatePasswordAndRevokeUserSessions(context.Background(), userID, "administrator-reset-hash")
	}()
	waitLinearSignal(t, state.resetLockAcquired, "administrator reset to acquire the user lock")

	changeDone := make(chan linearChangeResult, 1)
	go func() {
		changed, err := repo.ChangePasswordAndRevokeUserSessions(
			context.Background(), userID, "old-hash", "stale-self-service-hash",
		)
		changeDone <- linearChangeResult{changed: changed, err: err}
	}()
	waitLinearSignal(t, state.sessionLockAttempted, "self-service change to attempt the user lock")
	assertLinearOperationBlocked(t, changeDone, "self-service change while administrator reset owns the user lock")

	close(state.allowResetAfterLock)
	if err := <-resetDone; err != nil {
		t.Fatalf("UpdatePasswordAndRevokeUserSessions() error = %v", err)
	}
	changeResult := <-changeDone
	if changeResult.err != nil {
		t.Fatalf("ChangePasswordAndRevokeUserSessions() error = %v", changeResult.err)
	}
	if changeResult.changed {
		t.Fatal("stale self-service request overwrote a completed administrator reset")
	}
	if got := state.password(); got != "administrator-reset-hash" {
		t.Fatalf("password hash = %q, want administrator-reset-hash", got)
	}
	if got := state.sessionCount(userID); got != 0 {
		t.Fatalf("post-reset session count = %d, want 0", got)
	}
}

type linearCreateResult struct {
	created bool
	err     error
}

type linearChangeResult struct {
	changed bool
	err     error
}

func waitLinearSignal(t *testing.T, signal <-chan struct{}, description string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", description)
	}
}

func assertLinearOperationBlocked[T any](t *testing.T, result <-chan T, description string) {
	t.Helper()
	select {
	case <-result:
		t.Fatalf("%s unexpectedly completed", description)
	default:
	}
}

var linearSessionDriverID uint64

func openLinearSessionRepository(t *testing.T, state *linearSessionState) (*SessionRepository, func()) {
	t.Helper()
	driverName := fmt.Sprintf("linear-session-test-%d", atomic.AddUint64(&linearSessionDriverID, 1))
	sql.Register(driverName, linearSessionDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	db.SetMaxOpenConns(4)
	return NewSessionRepository(db), func() { _ = db.Close() }
}

type linearSessionState struct {
	mu       sync.Mutex
	userID   int64
	hash     string
	active   bool
	sessions map[string]int64
	rowLock  chan struct{}

	pauseSessionAfterLock bool
	pauseResetAfterLock   bool
	allowSessionAfterLock chan struct{}
	allowResetAfterLock   chan struct{}
	sessionLockAttempted  chan struct{}
	sessionLockAcquired   chan struct{}
	resetLockAttempted    chan struct{}
	resetLockAcquired     chan struct{}
	sessionAttemptOnce    sync.Once
	sessionAcquiredOnce   sync.Once
	resetAttemptOnce      sync.Once
	resetAcquiredOnce     sync.Once
}

func newLinearSessionState(userID int64, passwordHash string) *linearSessionState {
	state := &linearSessionState{
		userID:                userID,
		hash:                  passwordHash,
		active:                true,
		sessions:              make(map[string]int64),
		rowLock:               make(chan struct{}, 1),
		allowSessionAfterLock: make(chan struct{}),
		allowResetAfterLock:   make(chan struct{}),
		sessionLockAttempted:  make(chan struct{}),
		sessionLockAcquired:   make(chan struct{}),
		resetLockAttempted:    make(chan struct{}),
		resetLockAcquired:     make(chan struct{}),
	}
	state.rowLock <- struct{}{}
	return state
}

func (state *linearSessionState) password() string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.hash
}

func (state *linearSessionState) sessionCount(userID int64) int {
	state.mu.Lock()
	defer state.mu.Unlock()
	count := 0
	for _, sessionUserID := range state.sessions {
		if sessionUserID == userID {
			count++
		}
	}
	return count
}

type linearSessionDriver struct {
	state *linearSessionState
}

func (driverInstance linearSessionDriver) Open(string) (driver.Conn, error) {
	return &linearSessionConn{state: driverInstance.state}, nil
}

type linearSessionConn struct {
	state *linearSessionState
	tx    *linearSessionTx
}

func (connection *linearSessionConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("prepared statements are not supported by the linear session test driver")
}

func (connection *linearSessionConn) Close() error { return nil }

func (connection *linearSessionConn) Begin() (driver.Tx, error) {
	return connection.BeginTx(context.Background(), driver.TxOptions{})
}

func (connection *linearSessionConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	if connection.tx != nil {
		return nil, errors.New("transaction already active")
	}
	tx := &linearSessionTx{connection: connection}
	connection.tx = tx
	return tx, nil
}

func (connection *linearSessionConn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if connection.tx == nil {
		return nil, errors.New("linear session writes require a transaction")
	}
	tx := connection.tx
	switch {
	case strings.Contains(query, "UPDATE users SET password_hash = ?"):
		if err := tx.acquireUserLock(ctx, false); err != nil {
			return nil, err
		}
		tx.pendingHash = stringLinearArgument(args[0])
		tx.hasPendingHash = true
	case strings.Contains(query, "DELETE FROM app_sessions WHERE user_id = ?"):
		tx.deleteUserID = int64LinearArgument(args[0])
		tx.hasDelete = true
	case strings.Contains(query, "INSERT INTO app_sessions"):
		tx.insertSessionID = stringLinearArgument(args[0])
		tx.insertUserID = int64LinearArgument(args[1])
		tx.hasInsert = true
	default:
		return nil, fmt.Errorf("unexpected linear-session exec: %s", query)
	}
	return linearSessionResult(1), nil
}

func (connection *linearSessionConn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if connection.tx == nil || !strings.Contains(query, "SELECT password_hash") || !strings.Contains(query, "FOR UPDATE") {
		return nil, fmt.Errorf("unexpected linear-session query: %s", query)
	}
	if err := connection.tx.acquireUserLock(ctx, true); err != nil {
		return nil, err
	}
	userID := int64LinearArgument(args[0])
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	if userID != connection.state.userID || !connection.state.active {
		return &linearSessionRows{columns: []string{"password_hash"}}, nil
	}
	return &linearSessionRows{
		columns: []string{"password_hash"},
		values:  [][]driver.Value{{connection.state.hash}},
	}, nil
}

type linearSessionTx struct {
	connection      *linearSessionConn
	holdsUserLock   bool
	pendingHash     string
	hasPendingHash  bool
	deleteUserID    int64
	hasDelete       bool
	insertSessionID string
	insertUserID    int64
	hasInsert       bool
}

func (tx *linearSessionTx) acquireUserLock(ctx context.Context, sessionCreation bool) error {
	if tx.holdsUserLock {
		return nil
	}
	state := tx.connection.state
	if sessionCreation {
		state.sessionAttemptOnce.Do(func() { close(state.sessionLockAttempted) })
	} else {
		state.resetAttemptOnce.Do(func() { close(state.resetLockAttempted) })
	}
	select {
	case <-state.rowLock:
		tx.holdsUserLock = true
	case <-ctx.Done():
		return ctx.Err()
	}
	if sessionCreation {
		state.sessionAcquiredOnce.Do(func() { close(state.sessionLockAcquired) })
		if state.pauseSessionAfterLock {
			select {
			case <-state.allowSessionAfterLock:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	} else {
		state.resetAcquiredOnce.Do(func() { close(state.resetLockAcquired) })
		if state.pauseResetAfterLock {
			select {
			case <-state.allowResetAfterLock:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	}
	return nil
}

func (tx *linearSessionTx) Commit() error {
	state := tx.connection.state
	state.mu.Lock()
	if tx.hasPendingHash {
		state.hash = tx.pendingHash
	}
	if tx.hasDelete {
		for sessionID, userID := range state.sessions {
			if userID == tx.deleteUserID {
				delete(state.sessions, sessionID)
			}
		}
	}
	if tx.hasInsert {
		state.sessions[tx.insertSessionID] = tx.insertUserID
	}
	state.mu.Unlock()
	tx.finish()
	return nil
}

func (tx *linearSessionTx) Rollback() error {
	tx.finish()
	return nil
}

func (tx *linearSessionTx) finish() {
	if tx.connection.tx != tx {
		return
	}
	tx.connection.tx = nil
	if tx.holdsUserLock {
		tx.connection.state.rowLock <- struct{}{}
		tx.holdsUserLock = false
	}
}

type linearSessionResult int64

func (result linearSessionResult) LastInsertId() (int64, error) { return int64(result), nil }
func (result linearSessionResult) RowsAffected() (int64, error) { return int64(result), nil }

type linearSessionRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func (rows *linearSessionRows) Columns() []string { return rows.columns }
func (rows *linearSessionRows) Close() error      { return nil }

func (rows *linearSessionRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}

func int64LinearArgument(arg driver.NamedValue) int64 {
	switch value := arg.Value.(type) {
	case int64:
		return value
	case int:
		return int64(value)
	default:
		panic(fmt.Sprintf("unexpected int64 argument type %T", arg.Value))
	}
}

func stringLinearArgument(arg driver.NamedValue) string {
	value, ok := arg.Value.(string)
	if !ok {
		panic(fmt.Sprintf("unexpected string argument type %T", arg.Value))
	}
	return value
}
