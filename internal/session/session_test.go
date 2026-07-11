package session

import "testing"

func TestSafeSessionID(t *testing.T) {
	for _, test := range []struct {
		value string
		want  bool
	}{
		{value: "0123456789abcdef0123456789abcdef", want: true},
		{value: "short", want: false},
		{value: "../../invalid-session-id", want: false},
	} {
		if got := safeSessionID(test.value); got != test.want {
			t.Errorf("safeSessionID(%q) = %v, want %v", test.value, got, test.want)
		}
	}
}
