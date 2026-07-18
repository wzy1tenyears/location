package repositories

import (
	"context"
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestMembersBoundedPushesOverflowProbeIntoSQL(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectQuery(`(?s)FROM user_groups ug.*WHERE ug\.group_name = \? AND u\.is_active = 1.*LIMIT \?`).
		WithArgs("family-a", 65).
		WillReturnRows(sqlmock.NewRows([]string{"user_id", "username", "display_name", "role"}))

	repo := NewGroupRepository(db)
	rows, err := repo.MembersBounded(context.Background(), "family-a", 65)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 {
		t.Fatalf("members = %#v, want empty fixture", rows)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestMemberUsesSingleMemberQueryWithoutLoadingTheGroup(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	mock.ExpectQuery(`(?s)FROM user_groups ug.*WHERE ug\.group_name = \? AND ug\.user_id = \? AND u\.is_active = 1.*LIMIT 1`).
		WithArgs("family-a", int64(9001)).
		WillReturnRows(sqlmock.NewRows([]string{"user_id", "username", "display_name", "role"}).
			AddRow(9001, "member", "Member", "guardian"))

	repo := NewGroupRepository(db)
	member, err := repo.Member(context.Background(), "family-a", 9001)
	if err != nil {
		t.Fatal(err)
	}
	if member == nil || member.UserID != 9001 || member.Username != "member" {
		t.Fatalf("member = %#v", member)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}

func TestMembersPageAndCountStayBounded(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	mock.ExpectQuery(`(?s)SELECT COUNT\(\*\).*FROM user_groups ug.*WHERE ug\.group_name = \? AND u\.is_active = 1`).
		WithArgs("family-a").
		WillReturnRows(sqlmock.NewRows([]string{"count"}).AddRow(130))
	mock.ExpectQuery(`(?s)FROM user_groups ug.*WHERE ug\.group_name = \? AND u\.is_active = 1.*LIMIT \? OFFSET \?`).
		WithArgs("family-a", 64, 64).
		WillReturnRows(sqlmock.NewRows([]string{"user_id", "username", "display_name", "role"}).
			AddRow(65, "member65", "Member 65", "guardian"))

	repo := NewGroupRepository(db)
	count, err := repo.CountMembers(context.Background(), "family-a")
	if err != nil || count != 130 {
		t.Fatalf("count = %d, error %v", count, err)
	}
	members, err := repo.MembersPage(context.Background(), "family-a", 64, 64)
	if err != nil || len(members) != 1 || members[0].UserID != 65 {
		t.Fatalf("members page = %#v, error %v", members, err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatal(err)
	}
}
