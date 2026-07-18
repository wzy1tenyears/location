package services

import (
	"database/sql"
	"encoding/json"
	"math"
	"net"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"familylocation/location-v3/internal/models"
)

const DefaultStayRadiusMeters = 25.0

const (
	earthRadiusMeters              = 6371008.8
	distanceComparisonEpsilonMeter = 0.000001
)

type locationHistoryPartition struct {
	groupName string
	userID    int64
}

// MergeLocationHistory builds a read-only stay view. It never removes or
// rewrites the raw location rows retained by the repository.
func MergeLocationHistory(rows []models.Location, radiusMeters float64) []models.Location {
	if len(rows) == 0 {
		return []models.Location{}
	}
	if radiusMeters <= 0 || math.IsNaN(radiusMeters) || math.IsInf(radiusMeters, 0) {
		radiusMeters = DefaultStayRadiusMeters
	}

	partitions := make(map[locationHistoryPartition][]models.Location)
	for _, row := range rows {
		key := locationHistoryPartition{groupName: row.GroupName, userID: row.UserID}
		partitions[key] = append(partitions[key], row)
	}

	merged := make([]models.Location, 0, len(rows))
	for _, partition := range partitions {
		sort.SliceStable(partition, func(i, j int) bool {
			if partition[i].CreatedAt.Equal(partition[j].CreatedAt) {
				return partition[i].ID < partition[j].ID
			}
			return partition[i].CreatedAt.Before(partition[j].CreatedAt)
		})

		anchor := partition[0]
		stay := newLocationStay(anchor)
		for _, row := range partition[1:] {
			if locationsShareStay(anchor, row, radiusMeters) {
				stay = extendLocationStay(stay, row)
				continue
			}
			merged = append(merged, stay)
			anchor = row
			stay = newLocationStay(row)
		}
		merged = append(merged, stay)
	}

	sort.SliceStable(merged, func(i, j int) bool {
		if merged[i].LastReportedAt.Equal(merged[j].LastReportedAt) {
			if merged[i].ID != merged[j].ID {
				return merged[i].ID > merged[j].ID
			}
			if merged[i].GroupName != merged[j].GroupName {
				return merged[i].GroupName < merged[j].GroupName
			}
			return merged[i].UserID < merged[j].UserID
		}
		return merged[i].LastReportedAt.After(merged[j].LastReportedAt)
	})
	return merged
}

func newLocationStay(row models.Location) models.Location {
	row.FirstReportedAt = row.CreatedAt
	row.LastReportedAt = row.CreatedAt
	row.StayDurationSeconds = 0
	row.ReportCount = 1
	return row
}

func extendLocationStay(stay models.Location, latest models.Location) models.Location {
	firstReportedAt := stay.FirstReportedAt
	reportCount := stay.ReportCount + 1
	latest.AddressDiagnostics, latest.AddressMismatch = mergeStayAddressDiagnostics(
		stay.AddressDiagnostics,
		stay.AddressMismatch,
		latest.AddressDiagnostics,
		latest.AddressMismatch,
	)

	latest.FirstReportedAt = firstReportedAt
	latest.LastReportedAt = latest.CreatedAt
	latest.StayDurationSeconds = int64(latest.LastReportedAt.Sub(firstReportedAt).Seconds())
	if latest.StayDurationSeconds < 0 {
		latest.StayDurationSeconds = 0
	}
	latest.ReportCount = reportCount
	return latest
}

func mergeStayAddressDiagnostics(
	previous sql.NullString,
	previousMismatch bool,
	latest sql.NullString,
	latestMismatch bool,
) (sql.NullString, bool) {
	previousMap, previousOK := jsonMap(previous)
	latestMap, latestOK := jsonMap(latest)
	if !latestOK {
		if previousOK {
			return previous, previousMismatch
		}
		return latest, latestMismatch
	}
	if !previousOK {
		return latest, latestMismatch
	}
	if !diagnosticValuePresent(latestMap) {
		return previous, previousMismatch
	}

	usePreviousPreferred := diagnosticPreferredPackageBetter(previousMap, latestMap)
	merged := mergeDiagnosticMaps(previousMap, latestMap)
	encoded, err := json.Marshal(merged)
	if err != nil {
		return latest, latestMismatch
	}
	if usePreviousPreferred {
		return sql.NullString{String: string(encoded), Valid: true}, previousMismatch
	}
	return sql.NullString{String: string(encoded), Valid: true}, latestMismatch
}

func mergeDiagnosticMaps(previous, latest map[string]any) map[string]any {
	merged := cloneDiagnosticMap(latest)
	preferredKeys := diagnosticKeySet(preferredDiagnosticKeys())
	for key, value := range previous {
		if key == "sources" {
			continue
		}
		if _, isPreferred := preferredKeys[key]; isPreferred {
			continue
		}
		if !diagnosticValuePresent(merged[key]) && diagnosticValuePresent(value) {
			merged[key] = cloneDiagnosticValue(value)
		}
	}

	preferred := latest
	if diagnosticPreferredPackageBetter(previous, latest) {
		preferred = previous
	}
	for key := range preferredKeys {
		delete(merged, key)
		if value, exists := preferred[key]; exists && diagnosticValuePresent(value) {
			merged[key] = cloneDiagnosticValue(value)
		}
	}
	sources := mergeDiagnosticSources(previous["sources"], latest["sources"])
	if len(sources) > 0 {
		merged["sources"] = sources
	}
	return merged
}

func mergeDiagnosticSources(previous, latest any) []any {
	selected := make(map[string]map[string]any)
	mergeSources := func(value any) {
		for sourceIndex, source := range diagnosticSourceMaps(value) {
			sourceType := strings.ToLower(strings.TrimSpace(historyStringValue(source["type"])))
			switch sourceType {
			case "gps", "ip", "webrtc":
			default:
				continue
			}
			source["type"] = sourceType
			identity := diagnosticSourceIdentity(source, sourceType, sourceIndex)
			if current, exists := selected[identity]; exists {
				selected[identity] = mergeDiagnosticSource(current, source, sourceType)
			} else {
				selected[identity] = cloneDiagnosticMap(source)
			}
		}
	}
	mergeSources(previous)
	mergeSources(latest)

	identities := make([]string, 0, len(selected))
	for identity := range selected {
		identities = append(identities, identity)
	}
	sort.Slice(identities, func(i, j int) bool {
		leftType := strings.ToLower(strings.TrimSpace(historyStringValue(selected[identities[i]]["type"])))
		rightType := strings.ToLower(strings.TrimSpace(historyStringValue(selected[identities[j]]["type"])))
		leftRank := diagnosticSourceTypeRank(leftType)
		rightRank := diagnosticSourceTypeRank(rightType)
		if leftRank != rightRank {
			return leftRank < rightRank
		}
		return identities[i] < identities[j]
	})

	merged := make([]any, 0, len(identities))
	for _, identity := range identities {
		merged = append(merged, selected[identity])
	}
	return merged
}

func mergeDiagnosticSource(previous, latest map[string]any, sourceType string) map[string]any {
	selected := latest
	if diagnosticPackageBetter(previous, latest) {
		selected = previous
	}
	merged := cloneDiagnosticMap(selected)
	for _, nestedKey := range []string{"variants", "candidates"} {
		nested := mergeDiagnosticEvidence(previous[nestedKey], latest[nestedKey], sourceType, nestedKey)
		if len(nested) == 0 {
			delete(merged, nestedKey)
			continue
		}
		merged[nestedKey] = nested
	}
	return merged
}

func mergeDiagnosticEvidence(previous, latest any, sourceType, nestedKey string) []any {
	selected := make(map[string]map[string]any)
	mergeItems := func(value any) {
		for itemIndex, rawItem := range diagnosticArray(value) {
			item, ok := rawItem.(map[string]any)
			if !ok {
				continue
			}
			identity := diagnosticEvidenceIdentity(item, sourceType, nestedKey, itemIndex)
			if current, exists := selected[identity]; !exists || !diagnosticPackageBetter(current, item) {
				selected[identity] = cloneDiagnosticMap(item)
			}
		}
	}
	mergeItems(previous)
	mergeItems(latest)

	identities := make([]string, 0, len(selected))
	for identity := range selected {
		identities = append(identities, identity)
	}
	sort.Strings(identities)
	merged := make([]any, 0, len(identities))
	for _, identity := range identities {
		merged = append(merged, selected[identity])
	}
	return merged
}

func diagnosticSourceIdentity(source map[string]any, sourceType string, fallbackIndex int) string {
	networkIdentity := diagnosticNetworkIdentity(source)
	stunServer := ""
	if sourceType == "webrtc" {
		stunServer = diagnosticIdentityText(source["stun_server"])
	}
	sourceIdentity := firstDiagnosticIdentityText(source, "source", "provider", "name")
	if networkIdentity != "" || stunServer != "" || sourceIdentity != "" {
		return frameDiagnosticIdentity("source", sourceType, networkIdentity, stunServer, sourceIdentity)
	}
	return frameDiagnosticIdentity("source-anonymous", sourceType, diagnosticAnonymousIdentity(source, fallbackIndex))
}

func diagnosticEvidenceIdentity(item map[string]any, sourceType, nestedKey string, fallbackIndex int) string {
	networkIdentity := diagnosticNetworkIdentity(item)
	stunServer := diagnosticIdentityText(item["stun_server"])
	sourceIdentity := firstDiagnosticIdentityText(item, "source", "provider", "label")
	if networkIdentity != "" || stunServer != "" || sourceIdentity != "" {
		return frameDiagnosticIdentity("evidence", sourceType, nestedKey, networkIdentity, stunServer, sourceIdentity)
	}
	return frameDiagnosticIdentity("evidence-anonymous", sourceType, nestedKey, diagnosticAnonymousIdentity(item, fallbackIndex))
}

func frameDiagnosticIdentity(parts ...string) string {
	var framed strings.Builder
	for _, part := range parts {
		framed.WriteString(strconv.Itoa(len(part)))
		framed.WriteByte(':')
		framed.WriteString(part)
	}
	return framed.String()
}

func diagnosticNetworkIdentity(value map[string]any) string {
	for _, key := range []string{"ip", "server_ip", "ipv4", "ipv6"} {
		candidate := strings.TrimSpace(historyStringValue(value[key]))
		if candidate == "" {
			continue
		}
		trimmed := strings.Trim(candidate, "[]")
		if parsed := net.ParseIP(trimmed); parsed != nil {
			return strings.ToLower(parsed.String())
		}
		return strings.ToLower(candidate)
	}
	return ""
}

func firstDiagnosticIdentityText(value map[string]any, keys ...string) string {
	for _, key := range keys {
		if text := diagnosticIdentityText(value[key]); text != "" {
			return text
		}
	}
	return ""
}

func diagnosticIdentityText(value any) string {
	return strings.ToLower(strings.TrimSpace(historyStringValue(value)))
}

func diagnosticAnonymousIdentity(value map[string]any, fallbackIndex int) string {
	withoutEvidence := cloneDiagnosticMap(value)
	delete(withoutEvidence, "variants")
	delete(withoutEvidence, "candidates")
	encoded, err := json.Marshal(withoutEvidence)
	if err != nil || len(encoded) == 0 {
		return strconv.Itoa(fallbackIndex)
	}
	return string(encoded)
}

func diagnosticSourceTypeRank(sourceType string) int {
	switch sourceType {
	case "gps":
		return 0
	case "ip":
		return 1
	case "webrtc":
		return 2
	default:
		return 3
	}
}

func diagnosticPackageBetter(candidate, selected map[string]any) bool {
	candidatePrecision := diagnosticDirectPlacePrecision(candidate)
	selectedPrecision := diagnosticDirectPlacePrecision(selected)
	if candidatePrecision != selectedPrecision {
		return candidatePrecision > selectedPrecision
	}
	candidateFields := diagnosticDirectPlaceFieldCount(candidate)
	selectedFields := diagnosticDirectPlaceFieldCount(selected)
	if candidateFields != selectedFields {
		return candidateFields > selectedFields
	}
	candidateCoordinates := validDiagnosticCoordinatePair(candidate)
	selectedCoordinates := validDiagnosticCoordinatePair(selected)
	if candidateCoordinates != selectedCoordinates {
		return candidateCoordinates
	}
	candidateAccuracy, candidateAccuracyOK := diagnosticAccuracy(candidate)
	selectedAccuracy, selectedAccuracyOK := diagnosticAccuracy(selected)
	if candidateAccuracyOK != selectedAccuracyOK {
		return candidateAccuracyOK
	}
	if candidateAccuracyOK && candidateAccuracy != selectedAccuracy {
		return candidateAccuracy < selectedAccuracy
	}
	candidateLength := diagnosticDirectPlaceTextLength(candidate, "")
	selectedLength := diagnosticDirectPlaceTextLength(selected, "")
	if candidateLength != selectedLength {
		return candidateLength > selectedLength
	}
	return false
}

func diagnosticPreferredPackageBetter(candidate, selected map[string]any) bool {
	candidatePrecision := diagnosticPlacePrecision(candidate, true)
	selectedPrecision := diagnosticPlacePrecision(selected, true)
	if candidatePrecision != selectedPrecision {
		return candidatePrecision > selectedPrecision
	}
	candidateFields := diagnosticPreferredPlaceFieldCount(candidate)
	selectedFields := diagnosticPreferredPlaceFieldCount(selected)
	if candidateFields != selectedFields {
		return candidateFields > selectedFields
	}
	candidateCoordinates := validPreferredDiagnosticCoordinatePair(candidate)
	selectedCoordinates := validPreferredDiagnosticCoordinatePair(selected)
	if candidateCoordinates != selectedCoordinates {
		return candidateCoordinates
	}
	candidateLength := diagnosticDirectPlaceTextLength(candidate, "preferred_")
	selectedLength := diagnosticDirectPlaceTextLength(selected, "preferred_")
	if candidateLength != selectedLength {
		return candidateLength > selectedLength
	}
	return false
}

func diagnosticPreferredPlaceFieldCount(value map[string]any) int {
	count := 0
	for _, key := range []string{
		"preferred_country", "preferred_region", "preferred_city", "preferred_district", "preferred_address",
		"preferred_detail", "preferred_poi", "preferred_street", "preferred_postal_code",
	} {
		if diagnosticValuePresent(value[key]) {
			count++
		}
	}
	return count
}

func validPreferredDiagnosticCoordinatePair(value map[string]any) bool {
	latitude, latitudeOK := diagnosticNumber(value["preferred_latitude"])
	longitude, longitudeOK := diagnosticNumber(value["preferred_longitude"])
	return latitudeOK && longitudeOK && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 &&
		(latitude != 0 || longitude != 0)
}

func diagnosticAccuracy(value map[string]any) (float64, bool) {
	accuracy, ok := diagnosticNumber(value["accuracy"])
	return accuracy, ok && accuracy >= 0
}

func diagnosticSourceMaps(value any) []map[string]any {
	var sources []map[string]any
	switch typed := value.(type) {
	case []any:
		for _, item := range typed {
			if source, ok := item.(map[string]any); ok {
				sources = append(sources, source)
			}
		}
	case []map[string]any:
		sources = append(sources, typed...)
	}
	return sources
}

func diagnosticPlacePrecision(value any, preferredOnly bool) int {
	object, ok := value.(map[string]any)
	if !ok {
		return 0
	}
	prefix := ""
	if preferredOnly {
		prefix = "preferred_"
	}
	precision := diagnosticDirectPlacePrecisionWithPrefix(object, prefix)
	if preferredOnly {
		return precision
	}
	for _, key := range []string{"variants", "candidates"} {
		if nestedPrecision := diagnosticNestedPlacePrecision(object[key]); nestedPrecision > precision {
			precision = nestedPrecision
		}
	}
	return precision
}

func diagnosticDirectPlacePrecision(value map[string]any) int {
	return diagnosticDirectPlacePrecisionWithPrefix(value, "")
}

func diagnosticDirectPlaceFieldCount(value map[string]any) int {
	count := 0
	for _, key := range []string{"country", "region", "city", "district", "address", "detail", "poi", "street", "postal_code"} {
		if diagnosticValuePresent(value[key]) {
			count++
		}
	}
	return count
}

func diagnosticDirectPlaceTextLength(value map[string]any, prefix string) int {
	length := 0
	for _, key := range []string{"country", "region", "city", "district", "address", "detail", "poi", "street", "postal_code"} {
		length += len([]rune(strings.TrimSpace(historyStringValue(value[prefix+key]))))
	}
	return length
}

func diagnosticDirectPlacePrecisionWithPrefix(object map[string]any, prefix string) int {
	precision := 0
	for level, keys := range [][]string{
		{prefix + "country", prefix + "region"},
		{prefix + "city"},
		{prefix + "district"},
		{prefix + "address"},
		{prefix + "detail", prefix + "poi", prefix + "street", prefix + "postal_code"},
	} {
		for _, key := range keys {
			if diagnosticValuePresent(object[key]) && level+1 > precision {
				precision = level + 1
			}
		}
	}
	if prefix == "" && precision == 0 && validDiagnosticCoordinatePair(object) {
		return 1
	}
	return precision
}

func diagnosticNestedPlacePrecision(value any) int {
	precision := 0
	for _, item := range diagnosticArray(value) {
		if itemPrecision := diagnosticPlacePrecision(item, false); itemPrecision > precision {
			precision = itemPrecision
		}
	}
	return precision
}

func diagnosticArray(value any) []any {
	switch typed := value.(type) {
	case []any:
		return typed
	case []map[string]any:
		items := make([]any, len(typed))
		for index := range typed {
			items[index] = typed[index]
		}
		return items
	default:
		return nil
	}
}

func validDiagnosticCoordinatePair(value map[string]any) bool {
	latitude, latitudeOK := diagnosticNumber(value["latitude"])
	longitude, longitudeOK := diagnosticNumber(value["longitude"])
	return latitudeOK && longitudeOK && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 &&
		(latitude != 0 || longitude != 0)
}

func diagnosticNumber(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, !math.IsNaN(typed) && !math.IsInf(typed, 0)
	case float32:
		number := float64(typed)
		return number, !math.IsNaN(number) && !math.IsInf(number, 0)
	case int:
		return float64(typed), true
	case int64:
		return float64(typed), true
	case json.Number:
		number, err := typed.Float64()
		return number, err == nil && !math.IsNaN(number) && !math.IsInf(number, 0)
	default:
		return 0, false
	}
}

func diagnosticValuePresent(value any) bool {
	switch typed := value.(type) {
	case nil:
		return false
	case string:
		return strings.TrimSpace(typed) != ""
	case map[string]any:
		for _, nested := range typed {
			if diagnosticValuePresent(nested) {
				return true
			}
		}
		return false
	case []any:
		for _, nested := range typed {
			if diagnosticValuePresent(nested) {
				return true
			}
		}
		return false
	case []map[string]any:
		for _, nested := range typed {
			if diagnosticValuePresent(nested) {
				return true
			}
		}
		return false
	default:
		return true
	}
}

func cloneDiagnosticMap(value map[string]any) map[string]any {
	cloned := make(map[string]any, len(value))
	for key, nested := range value {
		cloned[key] = cloneDiagnosticValue(nested)
	}
	return cloned
}

func cloneDiagnosticValue(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		return cloneDiagnosticMap(typed)
	case []any:
		cloned := make([]any, len(typed))
		for index := range typed {
			cloned[index] = cloneDiagnosticValue(typed[index])
		}
		return cloned
	case []map[string]any:
		cloned := make([]any, len(typed))
		for index := range typed {
			cloned[index] = cloneDiagnosticMap(typed[index])
		}
		return cloned
	default:
		return value
	}
}

func preferredDiagnosticKeys() []string {
	return []string{
		"preferred_source",
		"preferred_address",
		"preferred_detail",
		"preferred_poi",
		"preferred_district",
		"preferred_street",
		"preferred_city",
		"preferred_region",
		"preferred_country",
		"preferred_postal_code",
		"preferred_latitude",
		"preferred_longitude",
		"preferred_coordinate_system",
	}
}

func diagnosticKeySet(keys []string) map[string]struct{} {
	result := make(map[string]struct{}, len(keys))
	for _, key := range keys {
		result[key] = struct{}{}
	}
	return result
}

func locationsShareStay(anchor models.Location, candidate models.Location, radiusMeters float64) bool {
	if !mergeableLocation(anchor) || !mergeableLocation(candidate) {
		return false
	}
	anchorSystem := locationCoordinateSystem(anchor)
	candidateSystem := locationCoordinateSystem(candidate)
	if anchorSystem == "" || candidateSystem == "" || anchorSystem != candidateSystem {
		return false
	}
	return haversineMeters(anchor.Latitude, anchor.Longitude, candidate.Latitude, candidate.Longitude) <= radiusMeters+distanceComparisonEpsilonMeter
}

func mergeableLocation(location models.Location) bool {
	if strings.EqualFold(strings.TrimSpace(location.EncryptionMode), "p2p-v1") || strings.TrimSpace(location.EncryptedPayload) != "" {
		return false
	}
	latitude := location.Latitude
	longitude := location.Longitude
	if math.IsNaN(latitude) || math.IsInf(latitude, 0) || math.IsNaN(longitude) || math.IsInf(longitude, 0) {
		return false
	}
	if latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		return false
	}
	return latitude != 0 || longitude != 0
}

func locationCoordinateSystem(location models.Location) string {
	if meta, ok := jsonMap(location.LocationMeta); ok {
		if raw, exists := meta["coordinate_system"]; exists && strings.TrimSpace(historyStringValue(raw)) != "" {
			return normalizeCoordinateSystem(historyStringValue(raw))
		}
	}
	if diagnostics, ok := jsonMap(location.AddressDiagnostics); ok {
		if sources, ok := diagnostics["sources"].([]any); ok {
			for _, rawSource := range sources {
				source, ok := rawSource.(map[string]any)
				if !ok || !strings.EqualFold(strings.TrimSpace(historyStringValue(source["type"])), "gps") {
					continue
				}
				if raw, exists := source["coordinate_system"]; exists && strings.TrimSpace(historyStringValue(raw)) != "" {
					return normalizeCoordinateSystem(historyStringValue(raw))
				}
			}
		}
	}
	return "wgs84"
}

func normalizeCoordinateSystem(value string) string {
	cleaned := strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return -1
	}, value)
	switch cleaned {
	case "wgs84", "gps":
		return "wgs84"
	case "gcj02", "gcj", "amap", "gaode":
		return "gcj02"
	case "bd09", "baidu":
		return "bd09"
	default:
		return ""
	}
}

func haversineMeters(latitudeA, longitudeA, latitudeB, longitudeB float64) float64 {
	toRadians := math.Pi / 180
	latA := latitudeA * toRadians
	latB := latitudeB * toRadians
	deltaLat := (latitudeB - latitudeA) * toRadians
	deltaLongitude := (longitudeB - longitudeA) * toRadians

	a := math.Sin(deltaLat/2)*math.Sin(deltaLat/2) +
		math.Cos(latA)*math.Cos(latB)*math.Sin(deltaLongitude/2)*math.Sin(deltaLongitude/2)
	if a > 1 {
		a = 1
	}
	return earthRadiusMeters * 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
}

func addressDiagnosticsHavePlace(value sql.NullString) bool {
	if !value.Valid || strings.TrimSpace(value.String) == "" {
		return false
	}
	var decoded map[string]any
	return json.Unmarshal([]byte(value.String), &decoded) == nil && addressDiagnosticsContainPlace(decoded)
}

func addressDiagnosticsContainPlace(value any) bool {
	addressFields := map[string]struct{}{
		"preferred_address":     {},
		"preferred_detail":      {},
		"preferred_poi":         {},
		"preferred_district":    {},
		"preferred_street":      {},
		"preferred_city":        {},
		"preferred_region":      {},
		"preferred_country":     {},
		"preferred_postal_code": {},
		"address":               {},
		"detail":                {},
		"poi":                   {},
		"district":              {},
		"street":                {},
		"city":                  {},
		"region":                {},
		"country":               {},
		"postal_code":           {},
	}
	var containsPlace func(any) bool
	containsPlace = func(candidate any) bool {
		switch typed := candidate.(type) {
		case map[string]any:
			for key, fieldValue := range typed {
				if _, ok := addressFields[key]; ok && strings.TrimSpace(historyStringValue(fieldValue)) != "" {
					return true
				}
			}
			for _, nestedKey := range []string{"sources", "variants", "candidates"} {
				if containsPlace(typed[nestedKey]) {
					return true
				}
			}
		case []any:
			for _, item := range typed {
				if containsPlace(item) {
					return true
				}
			}
		}
		return false
	}
	return containsPlace(value)
}

func validAddressDiagnostics(value sql.NullString) bool {
	_, ok := jsonMap(value)
	return ok
}

func jsonMap(value sql.NullString) (map[string]any, bool) {
	if !value.Valid || strings.TrimSpace(value.String) == "" {
		return nil, false
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(value.String), &decoded); err != nil || decoded == nil {
		return nil, false
	}
	return decoded, true
}

func historyStringValue(value any) string {
	text, _ := value.(string)
	return text
}

// PaginateLocationHistory paginates only after stays have been merged.
func PaginateLocationHistory(locations []models.Location, page int, perPage int) ([]models.Location, int, int) {
	if perPage <= 0 {
		perPage = 20
	}
	totalPages := (len(locations) + perPage - 1) / perPage
	if totalPages < 1 {
		totalPages = 1
	}
	if page < 1 {
		page = 1
	}
	if page > totalPages {
		page = totalPages
	}
	start := (page - 1) * perPage
	end := start + perPage
	if end > len(locations) {
		end = len(locations)
	}
	pageRows := make([]models.Location, end-start)
	copy(pageRows, locations[start:end])
	return pageRows, page, totalPages
}

// LimitLocationHistoryPerUser preserves global newest-first order while
// applying the map marker limit independently to every group member.
func LimitLocationHistoryPerUser(locations []models.Location, limit int) []models.Location {
	if limit <= 0 || len(locations) == 0 {
		return []models.Location{}
	}
	counts := make(map[locationHistoryPartition]int)
	limited := make([]models.Location, 0, len(locations))
	for _, location := range locations {
		key := locationHistoryPartition{groupName: location.GroupName, userID: location.UserID}
		if counts[key] >= limit {
			continue
		}
		counts[key]++
		limited = append(limited, location)
	}
	return limited
}
