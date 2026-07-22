package handlers

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/session"

	"github.com/gorilla/websocket"
)

const (
	eventClientQueueSize       = 16
	eventMaxConnectionsPerUser = 3
	eventPongTimeout           = 75 * time.Second
	eventPingInterval          = 25 * time.Second
)

type eventClient struct {
	userID    int64
	groups    map[string]struct{}
	queue     chan []byte
	closed    chan struct{}
	closeOnce sync.Once
}

func (client *eventClient) close() {
	client.closeOnce.Do(func() { close(client.closed) })
}

type EventHub struct {
	mu      sync.RWMutex
	clients map[*eventClient]struct{}
}

func NewEventHub() *EventHub {
	return &EventHub{clients: make(map[*eventClient]struct{})}
}

func (hub *EventHub) register(userID int64, memberships []models.Membership) (*eventClient, error) {
	groups := make(map[string]struct{}, len(memberships))
	for _, membership := range memberships {
		groups[membership.GroupName] = struct{}{}
	}
	client := &eventClient{userID: userID, groups: groups, queue: make(chan []byte, eventClientQueueSize), closed: make(chan struct{})}
	hub.mu.Lock()
	defer hub.mu.Unlock()
	connections := 0
	for existing := range hub.clients {
		if existing.userID == userID {
			connections++
		}
	}
	if connections >= eventMaxConnectionsPerUser {
		return nil, errors.New("too many event connections")
	}
	hub.clients[client] = struct{}{}
	return client, nil
}

func (hub *EventHub) unregister(client *eventClient) {
	if client == nil {
		return
	}
	hub.mu.Lock()
	delete(hub.clients, client)
	hub.mu.Unlock()
	client.close()
}

func (hub *EventHub) broadcast(event map[string]any, groupName string) {
	payload, err := json.Marshal(event)
	if err != nil {
		return
	}
	hub.mu.RLock()
	defer hub.mu.RUnlock()
	for client := range hub.clients {
		if groupName != "" {
			if _, allowed := client.groups[groupName]; !allowed {
				continue
			}
		}
		select {
		case client.queue <- payload:
		default:
			client.close()
		}
	}
}

func (hub *EventHub) BroadcastAnnouncement(id int64, version int, active bool) {
	hub.broadcast(map[string]any{"type": "announcement", "id": id, "version": version, "active": active}, "")
}

type EventStreamHandler struct {
	scope    scopedHandler
	hub      *EventHub
	upgrader websocket.Upgrader
}

func NewEventStreamHandler(db *sql.DB, sessions session.Reader, hub *EventHub) EventStreamHandler {
	return EventStreamHandler{
		scope: newScopedHandler(db, sessions),
		hub:   hub,
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				origin := strings.TrimSpace(r.Header.Get("Origin"))
				if origin == "" {
					return true
				}
				parsed, err := url.Parse(origin)
				return err == nil &&
					parsed.IsAbs() &&
					(strings.EqualFold(parsed.Scheme, "https") || strings.EqualFold(parsed.Scheme, "http")) &&
					parsed.User == nil &&
					parsed.Path == "" &&
					parsed.RawQuery == "" &&
					!parsed.ForceQuery &&
					parsed.Fragment == "" &&
					strings.EqualFold(parsed.Host, r.Host)
			},
		},
	}
}

func (handler EventStreamHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	scope, _, err := handler.scope.requireUser(r)
	if err != nil {
		httpx.Error(w, err)
		return
	}
	client, err := handler.hub.register(scope.User.ID, scope.Groups)
	if err != nil {
		httpx.Error(w, httpx.APIError{Status: http.StatusTooManyRequests, Message: "事件连接过多，请稍后重试。"})
		return
	}
	connection, err := handler.upgrader.Upgrade(w, r, nil)
	if err != nil {
		handler.hub.unregister(client)
		return
	}
	defer connection.Close()
	defer handler.hub.unregister(client)
	connection.SetReadLimit(2048)
	_ = connection.SetReadDeadline(time.Now().Add(eventPongTimeout))
	connection.SetPongHandler(func(string) error {
		return connection.SetReadDeadline(time.Now().Add(eventPongTimeout))
	})

	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for {
			if _, _, err := connection.ReadMessage(); err != nil {
				return
			}
			_ = connection.SetReadDeadline(time.Now().Add(eventPongTimeout))
		}
	}()

	ready, _ := json.Marshal(map[string]any{"type": "ready", "user_id": scope.User.ID})
	if err := connection.WriteMessage(websocket.TextMessage, ready); err != nil {
		return
	}
	ticker := time.NewTicker(eventPingInterval)
	defer ticker.Stop()
	for {
		select {
		case payload := <-client.queue:
			if err := connection.WriteMessage(websocket.TextMessage, payload); err != nil {
				return
			}
		case <-ticker.C:
			if err := connection.WriteControl(websocket.PingMessage, []byte("ping"), time.Now().Add(5*time.Second)); err != nil {
				return
			}
		case <-client.closed:
			return
		case <-readDone:
			return
		case <-r.Context().Done():
			return
		}
	}
}
