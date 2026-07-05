package services

import (
	"strings"

	"golang.org/x/crypto/bcrypt"
)

func CheckPassword(password string, hash string) bool {
	hash = normalizePHPBcrypt(hash)
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}

func HashPassword(password string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return strings.Replace(string(hash), "$2a$", "$2y$", 1), nil
}

func normalizePHPBcrypt(hash string) string {
	if strings.HasPrefix(hash, "$2y$") {
		return "$2a$" + strings.TrimPrefix(hash, "$2y$")
	}
	return hash
}
