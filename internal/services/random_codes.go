package services

import (
	"fmt"
	"io"
)

const lowerAlphaNumericAlphabet = "0123456789abcdefghijklmnopqrstuvwxyz"

// RandomLowerAlphaNumeric uses rejection sampling so every output character has
// the same probability even though the entropy source yields 256 byte values.
func RandomLowerAlphaNumeric(reader io.Reader, length int) (string, error) {
	if length <= 0 {
		return "", fmt.Errorf("random code length must be positive")
	}
	result := make([]byte, 0, length)
	var raw [1]byte
	const unbiasedLimit = 252 // Largest multiple of 36 below 256.
	for len(result) < length {
		if _, err := io.ReadFull(reader, raw[:]); err != nil {
			return "", fmt.Errorf("read random code entropy: %w", err)
		}
		if int(raw[0]) >= unbiasedLimit {
			continue
		}
		result = append(result, lowerAlphaNumericAlphabet[int(raw[0])%len(lowerAlphaNumericAlphabet)])
	}
	return string(result), nil
}
