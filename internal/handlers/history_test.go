package handlers

import (
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"familylocation/location-v3/internal/httpx"
	"familylocation/location-v3/internal/models"
	"familylocation/location-v3/internal/repositories"
)

func TestComposeLocationHistoryMergesBeforeTotalsPaginationAndMapLimit(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rawRows := make([]models.Location, 0, 21)
	for index := 0; index < 21; index++ {
		northMeters := float64(index * 100)
		if index == 1 {
			northMeters = 5
		}
		rawRows = append(rawRows, handlerHistoryLocation(int64(index+1), northMeters, base.Add(time.Duration(index)*time.Minute)))
	}

	view := composeLocationHistory(rawRows, 2, 20, 3)
	if view.total != 20 {
		t.Fatalf("merged total = %d, want 20 from 21 raw rows", view.total)
	}
	if view.page != 1 || view.totalPages != 1 {
		t.Fatalf("page = %d/%d, want clamped 1/1", view.page, view.totalPages)
	}
	if len(view.rows) != 20 {
		t.Fatalf("history page rows = %d, want 20", len(view.rows))
	}
	if view.rows[0].ID != 21 || view.rows[len(view.rows)-1].ID != 2 || view.rows[len(view.rows)-1].ReportCount != 2 {
		t.Fatalf("history newest/oldest merged rows = id %d / id %d count %d", view.rows[0].ID, view.rows[len(view.rows)-1].ID, view.rows[len(view.rows)-1].ReportCount)
	}
	if len(view.mapRows) != 3 || view.mapRows[0].ID != 21 || view.mapRows[2].ID != 19 {
		t.Fatalf("map rows ids = %v, want newest three [21 20 19]", handlerHistoryIDs(view.mapRows))
	}
	if len(view.clientMergeRows) != 21 || view.clientMergeRows[0].ID != 1 || view.clientMergeRows[1].ID != 2 {
		t.Fatalf("client merge snapshot is not the complete raw retention window: len=%d ids=%v", len(view.clientMergeRows), handlerHistoryIDs(view.clientMergeRows))
	}
}

func TestClientMergeSnapshotPreservesRawPlaintextAroundEncryptedBoundary(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rawRows := []models.Location{
		handlerHistoryLocation(1, 0, base),
		handlerHistoryLocation(2, 20, base.Add(time.Minute)),
		handlerHistoryLocation(3, 40, base.Add(2*time.Minute)),
	}
	rawRows[1].Latitude = 0
	rawRows[1].Longitude = 0
	rawRows[1].EncryptionMode = "p2p-v1"
	rawRows[1].EncryptedPayload = `{"ciphertext":"opaque"}`

	view := composeLocationHistory(rawRows, 1, 20, 20)
	if len(view.clientMergeRows) != 3 {
		t.Fatalf("client merge rows = %d, want all three raw records", len(view.clientMergeRows))
	}
	for index, row := range view.clientMergeRows {
		if row.ID != int64(index+1) || row.ReportCount != 0 || !row.FirstReportedAt.IsZero() {
			t.Fatalf("raw client row %d was reordered or server-merged: %#v", index, row)
		}
	}

	response := map[string]any{"ok": true}
	appendClientMergeSnapshot(response, view, true, 5000)
	payloads := response["client_merge_history"].([]map[string]any)
	if len(payloads) != 3 || payloads[0]["id"] != int64(1) || payloads[2]["id"] != int64(3) {
		t.Fatalf("raw client snapshot payloads = %#v", payloads)
	}
	for _, payload := range payloads {
		if _, exists := payload["first_reported_at"]; exists {
			t.Fatalf("raw snapshot leaked server stay fields: %#v", payload)
		}
	}
}

func TestClientMergeSnapshotIncludesP2PRowsBeyondThePaginatedPage(t *testing.T) {
	base := time.Date(2026, 7, 16, 12, 0, 0, 0, time.UTC)
	rawRows := make([]models.Location, 0, 25)
	for index := 0; index < 25; index++ {
		row := handlerHistoryLocation(int64(index+1), 0, base.Add(time.Duration(index)*time.Minute))
		row.Latitude = 0
		row.Longitude = 0
		row.EncryptionMode = "p2p-v1"
		row.EncryptedPayload = `{"ciphertext":"opaque"}`
		rawRows = append(rawRows, row)
	}

	view := composeLocationHistory(rawRows, 1, 20, 20)
	if len(view.rows) != 20 || len(view.mapRows) != 20 {
		t.Fatalf("ordinary page/map rows = %d/%d, want 20/20", len(view.rows), len(view.mapRows))
	}
	if len(view.clientMergeRows) != 25 {
		t.Fatalf("client merge rows = %d, want all 25 retained encrypted rows", len(view.clientMergeRows))
	}

	response := map[string]any{"ok": true}
	appendClientMergeSnapshot(response, view, true, 5000)
	payloads, ok := response["client_merge_history"].([]map[string]any)
	if !ok || len(payloads) != 25 {
		t.Fatalf("client_merge_history = %#v, want 25 payloads", response["client_merge_history"])
	}
	if payloads[0]["id"] != int64(1) || payloads[24]["id"] != int64(25) {
		t.Fatalf("client merge snapshot ids = %#v/%#v, want raw input order 1/25", payloads[0]["id"], payloads[24]["id"])
	}
	if response["client_merge_complete"] != true {
		t.Fatalf("client_merge_complete = %#v", response["client_merge_complete"])
	}
	retention, ok := response["client_merge_retention"].(map[string]any)
	if !ok || retention["record_shape"] != "raw" || retention["merge_owner"] != "client" || retention["per_user_raw_limit"] != 5000 || retention["complete_within_window"] != true {
		t.Fatalf("client_merge_retention = %#v", response["client_merge_retention"])
	}

	ordinary := map[string]any{"ok": true}
	appendClientMergeSnapshot(ordinary, view, false, 5000)
	if _, exists := ordinary["client_merge_history"]; exists {
		t.Fatal("ordinary history response exposed the opt-in merge snapshot")
	}
}

func TestHistoryResponseSizeLimitRejectsOversizeWithoutTruncating(t *testing.T) {
	response := map[string]any{
		"ok":                   true,
		"client_merge_history": strings.Repeat("x", maxHistoryResponseBytes),
	}
	err := validateHistoryResponseSize(response)
	var apiErr httpx.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity {
		t.Fatalf("validateHistoryResponseSize() error = %#v, want HTTP 422", err)
	}
	if apiErr.Message != historyResponseTooLargeMessage {
		t.Fatalf("oversize response message = %q", apiErr.Message)
	}
	if response["client_merge_history"] == "" {
		t.Fatal("oversized response was silently truncated")
	}
	if err := validateHistoryResponseSize(map[string]any{"ok": true, "history": []any{}}); err != nil {
		t.Fatalf("small history response was rejected: %v", err)
	}
}

func TestHistoryRepositoryLimitsMapToStableUnprocessableErrors(t *testing.T) {
	for _, test := range []struct {
		err         error
		wantMessage string
	}{
		{err: repositories.ErrLocationHistorySnapshotTooLarge, wantMessage: historyTooManyRowsMessage},
		{err: repositories.ErrLocationHistorySnapshotBytesTooLarge, wantMessage: historyResponseTooLargeMessage},
	} {
		mapped := historyRepositoryError(test.err)
		var apiErr httpx.APIError
		if !errors.As(mapped, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity || apiErr.Message != test.wantMessage {
			t.Fatalf("mapped limit error = %#v, want 422 %q", mapped, test.wantMessage)
		}
	}
}

func TestHistoryHardLimitsHaveExactBoundariesAndStableErrors(t *testing.T) {
	for _, test := range []struct {
		name        string
		atLimit     int
		overLimit   int
		validate    func(int) error
		wantMessage string
	}{
		{name: "members 64/65", atLimit: 64, overLimit: 65, validate: validateHistoryMemberCount, wantMessage: historyTooManyMembersMessage},
		{name: "map rows 2000/2001", atLimit: 2000, overLimit: 2001, validate: validateHistoryMapRowCount, wantMessage: historyTooManyMapRowsMessage},
		{name: "response body 2MiB boundary including encoder newline", atLimit: maxHistoryResponseBytes - 1, overLimit: maxHistoryResponseBytes, validate: validateHistoryResponseBytes, wantMessage: historyResponseTooLargeMessage},
	} {
		t.Run(test.name, func(t *testing.T) {
			if err := test.validate(test.atLimit); err != nil {
				t.Fatalf("exact limit %d was rejected: %v", test.atLimit, err)
			}
			err := test.validate(test.overLimit)
			var apiErr httpx.APIError
			if !errors.As(err, &apiErr) || apiErr.Status != http.StatusUnprocessableEntity || apiErr.Message != test.wantMessage {
				t.Fatalf("over limit %d error = %#v, want stable 422 %q", test.overLimit, err, test.wantMessage)
			}
		})
	}
	err := validateHistoryMemberCount(65)
	var selectionError httpx.APIError
	if !errors.As(err, &selectionError) || selectionError.Code != "history_member_selection_required" {
		t.Fatalf("member overflow error code = %#v", err)
	}
	selector, _ := selectionError.Details["member_selector"].(map[string]any)
	if selector["endpoint"] != "/api/history-members" || selector["page_size"] != maxHistoryMemberPageSize {
		t.Fatalf("member overflow selector = %#v", selector)
	}
}

func TestHistoryUserFilterKeepsLargeGroupSnapshotUsable(t *testing.T) {
	members := make([]models.GroupMember, 0, 65)
	for index := 1; index <= 65; index++ {
		members = append(members, models.GroupMember{UserID: int64(index)})
	}
	if err := validateHistoryMemberCount(len(members)); err == nil {
		t.Fatal("unfiltered 65-member snapshot was accepted")
	}
	filtered := filterMembers(members, 65)
	if len(filtered) != 1 || filtered[0].UserID != 65 {
		t.Fatalf("filterMembers() = %#v, want selected member 65", filtered)
	}
	if err := validateHistoryMemberCount(len(filtered)); err != nil {
		t.Fatalf("user_id-filtered snapshot was rejected: %v", err)
	}
}

func TestHistoryMemberPayloadContractPreservesSelectorForBoundedGroups(t *testing.T) {
	members := make([]models.GroupMember, 0, 64)
	for index := 1; index <= 64; index++ {
		members = append(members, models.GroupMember{UserID: int64(index)})
	}
	selected := filterMembers(members, 64)
	if len(selected) != 1 || len(memberPayloads(members)) != 64 {
		t.Fatalf("bounded member selector contract lost members: selected=%d response=%d", len(selected), len(memberPayloads(members)))
	}
	if len(append(members, models.GroupMember{UserID: 65})) != maxHistorySnapshotMembers+1 {
		t.Fatal("overflow probe no longer distinguishes 64 from 65 members")
	}
}

func TestHistoryMemberOverflowReturnsBoundedSelectionEntryWithoutScanningHistory(t *testing.T) {
	membership := models.Membership{UserID: 7, GroupName: "family-a", Role: "guardian", GroupCode: "abcd1234"}
	scope := &userScope{
		User:       &models.User{ID: 7, Username: "owner", DisplayName: "Owner", GroupName: "family-a", Role: "guardian", IsActive: true},
		Groups:     []models.Membership{membership},
		Membership: &membership,
	}
	members := make([]models.GroupMember, 0, maxHistorySnapshotMembers)
	for index := 1; index <= maxHistorySnapshotMembers; index++ {
		members = append(members, models.GroupMember{UserID: int64(index), Username: "member"})
	}

	recorder := httptest.NewRecorder()
	HistoryHandler{}.respondMemberSelectionRequired(recorder, scope, members, historyRequest{PerPage: 20, MapPerUser: 20, ClientMergeSnapshot: true})
	if recorder.Code != http.StatusOK {
		t.Fatalf("selection response status = %d, want 200", recorder.Code)
	}
	var payload map[string]any
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode selection response: %v", err)
	}
	if payload["selection_required"] != true || payload["selection_limited"] != true || payload["members_truncated"] != true {
		t.Fatalf("selection flags = %#v", payload)
	}
	responseMembers, _ := payload["members"].([]any)
	if len(responseMembers) != maxHistorySnapshotMembers {
		t.Fatalf("selection members = %d, want bounded %d", len(responseMembers), maxHistorySnapshotMembers)
	}
	history, _ := payload["history"].([]any)
	if len(history) != 0 {
		t.Fatalf("selection response unexpectedly scanned history: %#v", history)
	}
	if _, exists := payload["client_merge_history"]; exists {
		t.Fatal("selection response exposed a misleading complete merge snapshot")
	}
	selector, _ := payload["member_selector"].(map[string]any)
	if selector["endpoint"] != "/api/history-members" || selector["page_size"] != float64(maxHistoryMemberPageSize) {
		t.Fatalf("selection entry = %#v", selector)
	}
}

func TestNormalizeHistoryMapPerUserSupportsCompactTrails(t *testing.T) {
	for _, value := range []int{5, 10, 20, 50, 100} {
		if got := normalizeHistoryMapPerUser(value); got != value {
			t.Fatalf("normalizeHistoryMapPerUser(%d) = %d", value, got)
		}
	}
	if got := normalizeHistoryMapPerUser(7); got != 20 {
		t.Fatalf("unsupported map history size normalized to %d", got)
	}
}

func handlerHistoryLocation(id int64, northMeters float64, createdAt time.Time) models.Location {
	const earthRadiusMetersForTest = 6371008.8
	return models.Location{
		ID:                 id,
		UserID:             41,
		Username:           "member",
		GroupName:          "family-a",
		Latitude:           30 + northMeters/earthRadiusMetersForTest*180/math.Pi,
		Longitude:          120,
		LocationMeta:       sql.NullString{String: `{"coordinate_system":"wgs84"}`, Valid: true},
		CreatedAt:          createdAt,
		UpdatedAt:          createdAt,
		AddressDiagnostics: sql.NullString{},
	}
}

func handlerHistoryIDs(locations []models.Location) []int64 {
	ids := make([]int64, 0, len(locations))
	for _, location := range locations {
		ids = append(ids, location.ID)
	}
	return ids
}
