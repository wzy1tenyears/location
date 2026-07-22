package handlers

import (
	"encoding/json"
	"net/http/httptest"
	"testing"

	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/session"
)

func TestEventStreamOriginValidationUsesExactHost(t *testing.T) {
	handler := NewEventStreamHandler(nil, session.Reader{}, NewEventHub())
	for _, test := range []struct {
		name   string
		origin string
		want   bool
	}{
		{name: "same origin", origin: "https://loc.example", want: true},
		{name: "same origin host case", origin: "https://LOC.EXAMPLE", want: true},
		{name: "missing origin native client", origin: "", want: true},
		{name: "host substring attack", origin: "https://loc.example.attacker.invalid", want: false},
		{name: "userinfo attack", origin: "https://loc.example@attacker.invalid", want: false},
		{name: "same host userinfo attack", origin: "https://attacker@loc.example", want: false},
		{name: "origin path", origin: "https://loc.example/path", want: false},
		{name: "origin query", origin: "https://loc.example?next=attacker", want: false},
		{name: "origin fragment", origin: "https://loc.example#attacker", want: false},
		{name: "non HTTP origin", origin: "ftp://loc.example", want: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest("GET", "https://loc.example/api/events", nil)
			request.Host = "loc.example"
			if test.origin != "" {
				request.Header.Set("Origin", test.origin)
			}
			if got := handler.upgrader.CheckOrigin(request); got != test.want {
				t.Fatalf("CheckOrigin(%q) = %v, want %v", test.origin, got, test.want)
			}
		})
	}
}

func TestEventHubScopesGroupEventsAndBroadcastsAnnouncements(t *testing.T) {
	hub := NewEventHub()
	alpha, err := hub.register(1, []models.Membership{{GroupName: "alpha"}})
	if err != nil {
		t.Fatal(err)
	}
	beta, err := hub.register(2, []models.Membership{{GroupName: "beta"}})
	if err != nil {
		t.Fatal(err)
	}
	defer hub.unregister(alpha)
	defer hub.unregister(beta)

	hub.broadcast(map[string]any{"type": "group_status"}, "alpha")
	select {
	case <-alpha.queue:
	default:
		t.Fatal("authorized group client did not receive event")
	}
	select {
	case <-beta.queue:
		t.Fatal("unrelated group client received event")
	default:
	}

	hub.BroadcastAnnouncement(7, 3, true)
	for _, client := range []*eventClient{alpha, beta} {
		select {
		case payload := <-client.queue:
			var event map[string]any
			if err := json.Unmarshal(payload, &event); err != nil || event["type"] != "announcement" {
				t.Fatalf("unexpected announcement event: %s / %v", payload, err)
			}
		default:
			t.Fatal("announcement was not broadcast to authenticated client")
		}
	}
}

func TestEventHubLimitsConnectionsPerUser(t *testing.T) {
	hub := NewEventHub()
	clients := make([]*eventClient, 0, eventMaxConnectionsPerUser)
	for index := 0; index < eventMaxConnectionsPerUser; index++ {
		client, err := hub.register(42, nil)
		if err != nil {
			t.Fatal(err)
		}
		clients = append(clients, client)
	}
	defer func() {
		for _, client := range clients {
			hub.unregister(client)
		}
	}()
	if _, err := hub.register(42, nil); err == nil {
		t.Fatal("per-user event connection limit was not enforced")
	}
}
