package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strings"

	"familylocation/location-v3/internal/config"
)

func tokenSigningSecret(cfg config.Config) string {
	return strings.TrimSpace(cfg.App.SigningSecret)
}

func adminAPKToken(version string, expires string, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte("admin-apk|" + version + "|" + expires))
	return hex.EncodeToString(mac.Sum(nil))
}

func hmacHex(value string, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(value))
	return hex.EncodeToString(mac.Sum(nil))
}
