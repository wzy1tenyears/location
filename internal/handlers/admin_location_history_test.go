package handlers

import (
	"net/http/httptest"
	"testing"
)

func TestParseAdminLocationHistoryFilter(t *testing.T) {
	request := httptest.NewRequest("GET", "/api/admin/location-history?user_id=42&group_name=family-a&page=3&limit=75", nil)
	filter, err := parseAdminLocationHistoryFilter(request)
	if err != nil {
		t.Fatal(err)
	}
	if filter.UserID != 42 || filter.GroupName != "family-a" || filter.Page != 3 || filter.Limit != 75 {
		t.Fatalf("unexpected filter: %#v", filter)
	}
}

func TestParseAdminLocationHistoryFilterDefaultsAndRejectsInvalidValues(t *testing.T) {
	request := httptest.NewRequest("GET", "/api/admin/location-history", nil)
	filter, err := parseAdminLocationHistoryFilter(request)
	if err != nil {
		t.Fatal(err)
	}
	if filter.UserID != 0 || filter.GroupName != "" || filter.Page != 1 || filter.Limit != defaultAdminLocationHistoryLimit {
		t.Fatalf("unexpected defaults: %#v", filter)
	}
	for _, query := range []string{"user_id=0", "page=-1", "limit=101", "limit=text"} {
		request = httptest.NewRequest("GET", "/api/admin/location-history?"+query, nil)
		if _, err := parseAdminLocationHistoryFilter(request); err == nil {
			t.Fatalf("expected %q to fail", query)
		}
	}
}
