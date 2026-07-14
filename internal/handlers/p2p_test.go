package handlers

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/services"
)

func TestPublishPublicKeyAllowsFirstRegistrationAndSameKeyRetry(t *testing.T) {
	passwordHash, err := services.HashPassword("correct-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	handler, state, closeDB := newP2PKeyTestHandler(t, passwordHash, "")
	defer closeDB()

	change, err := handler.publishPublicKey(p2pKeyTestRequest(), p2pKeyTestScope(), `{"kty":"RSA","n":"first"}`, "")
	var apiErr httpx.APIError
	if change != "" || !errors.As(err, &apiErr) || apiErr.Status != http.StatusForbidden {
		t.Fatalf("first publish without reauthentication = %q, %#v; want 403", change, err)
	}
	if state.publicKey() != "" {
		t.Fatal("unauthenticated first publication registered a key")
	}

	change, err = handler.publishPublicKey(p2pKeyTestRequest(), p2pKeyTestScope(), `{"kty":"RSA","n":"first"}`, "correct-password")
	if err != nil || change != "created" {
		t.Fatalf("reauthenticated first publish = %q, %v", change, err)
	}
	change, err = handler.publishPublicKey(p2pKeyTestRequest(), p2pKeyTestScope(), `{"kty":"RSA","n":"first"}`, "")
	if err != nil || change != "unchanged" {
		t.Fatalf("same-key publish = %q, %v", change, err)
	}
	if state.publicKey() != `{"kty":"RSA","n":"first"}` || state.wrappedKeysCleared() {
		t.Fatalf("idempotent publication changed key state: %#v", state)
	}
}

func TestPublishPublicKeyRequiresReauthenticationAndInvalidatesOldWraps(t *testing.T) {
	passwordHash, err := services.HashPassword("correct-password")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	handler, state, closeDB := newP2PKeyTestHandler(t, passwordHash, `{"kty":"RSA","n":"old"}`)
	defer closeDB()

	for _, password := range []string{"", "wrong-password"} {
		change, err := handler.publishPublicKey(p2pKeyTestRequest(), p2pKeyTestScope(), `{"kty":"RSA","n":"new"}`, password)
		var apiErr httpx.APIError
		if change != "" || !errors.As(err, &apiErr) || apiErr.Status != http.StatusForbidden {
			t.Fatalf("replacement with password %q = %q, %#v; want 403", password, change, err)
		}
		if state.publicKey() != `{"kty":"RSA","n":"old"}` || state.wrappedKeysCleared() {
			t.Fatal("unauthorized replacement mutated key state")
		}
	}

	change, err := handler.publishPublicKey(p2pKeyTestRequest(), p2pKeyTestScope(), `{"kty":"RSA","n":"new"}`, "correct-password")
	if err != nil || change != "replaced" {
		t.Fatalf("reauthenticated replacement = %q, %v", change, err)
	}
	if state.publicKey() != `{"kty":"RSA","n":"new"}` || !state.wrappedKeysCleared() {
		t.Fatal("replacement did not update the key and invalidate stale wraps")
	}
}

func p2pKeyTestRequest() *http.Request {
	return httptest.NewRequest(http.MethodPost, "/api/p2p-crypto", nil)
}

func p2pKeyTestScope() *userScope {
	return &userScope{User: &models.User{ID: 41}}
}

func newP2PKeyTestHandler(t *testing.T, passwordHash string, currentKey string) (P2PHandler, *p2pKeyDBState, func()) {
	t.Helper()
	state := &p2pKeyDBState{
		passwordHash: passwordHash,
		currentKey:   currentKey,
		hasKey:       currentKey != "",
	}
	driverName := fmt.Sprintf("p2p-key-test-%d", atomic.AddUint64(&p2pKeyDriverID, 1))
	sql.Register(driverName, p2pKeyDriver{state: state})
	db, err := sql.Open(driverName, "")
	if err != nil {
		t.Fatalf("sql.Open() error = %v", err)
	}
	return P2PHandler{db: db}, state, func() { _ = db.Close() }
}

var p2pKeyDriverID uint64

type p2pKeyDBState struct {
	mu           sync.Mutex
	passwordHash string
	currentKey   string
	hasKey       bool
	clearedWraps bool
}

func (state *p2pKeyDBState) publicKey() string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.currentKey
}

func (state *p2pKeyDBState) wrappedKeysCleared() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.clearedWraps
}

type p2pKeyDriver struct {
	state *p2pKeyDBState
}

func (driverInstance p2pKeyDriver) Open(string) (driver.Conn, error) {
	return &p2pKeyConn{state: driverInstance.state}, nil
}

type p2pKeyConn struct {
	state *p2pKeyDBState
}

func (connection *p2pKeyConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("prepared statements are not supported by the p2p key test driver")
}

func (connection *p2pKeyConn) Close() error { return nil }

func (connection *p2pKeyConn) Begin() (driver.Tx, error) {
	return p2pKeyTx{}, nil
}

func (connection *p2pKeyConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return p2pKeyTx{}, nil
}

func (connection *p2pKeyConn) QueryContext(_ context.Context, query string, _ []driver.NamedValue) (driver.Rows, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	switch {
	case strings.Contains(query, "SELECT id, password_hash"):
		return &p2pKeyRows{columns: []string{"id", "password_hash"}, values: [][]driver.Value{{int64(41), connection.state.passwordHash}}}, nil
	case strings.Contains(query, "SELECT public_key_jwk"):
		if !connection.state.hasKey {
			return &p2pKeyRows{columns: []string{"public_key_jwk"}}, nil
		}
		return &p2pKeyRows{columns: []string{"public_key_jwk"}, values: [][]driver.Value{{connection.state.currentKey}}}, nil
	default:
		return nil, fmt.Errorf("unexpected p2p key query: %s", query)
	}
}

func (connection *p2pKeyConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	connection.state.mu.Lock()
	defer connection.state.mu.Unlock()
	switch {
	case strings.Contains(query, "INSERT INTO p2p_user_keys"):
		connection.state.currentKey = args[1].Value.(string)
		connection.state.hasKey = true
	case strings.Contains(query, "UPDATE p2p_user_keys"):
		connection.state.currentKey = args[0].Value.(string)
		connection.state.hasKey = true
	case strings.Contains(query, "UPDATE p2p_group_members"):
		connection.state.clearedWraps = true
	default:
		return nil, fmt.Errorf("unexpected p2p key exec: %s", query)
	}
	return driver.RowsAffected(1), nil
}

type p2pKeyTx struct{}

func (p2pKeyTx) Commit() error   { return nil }
func (p2pKeyTx) Rollback() error { return nil }

type p2pKeyRows struct {
	columns []string
	values  [][]driver.Value
	index   int
}

func (rows *p2pKeyRows) Columns() []string { return rows.columns }
func (rows *p2pKeyRows) Close() error      { return nil }

func (rows *p2pKeyRows) Next(destination []driver.Value) error {
	if rows.index >= len(rows.values) {
		return io.EOF
	}
	copy(destination, rows.values[rows.index])
	rows.index++
	return nil
}
