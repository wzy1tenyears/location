package services

import (
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"

	"familylocation/location-v3/internal/config"
	"familylocation/location-v3/internal/httpx"
)

type IPGeoPayload map[string]any

func LookupIPGeo(ip string, provider string, cfg config.ExternalConfig) (IPGeoPayload, bool, error) {
	ip = strings.TrimSpace(ip)
	if net.ParseIP(ip) == nil {
		return nil, false, nil
	}

	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "ip-api":
		return lookupIPAPI(ip)
	case "ip2location":
		if strings.TrimSpace(cfg.IP2LocationKey) == "" {
			return nil, false, nil
		}
		return lookupIP2Location(ip, cfg.IP2LocationKey)
	case "ipdata":
		if strings.TrimSpace(cfg.IPDataKey) == "" {
			return nil, false, nil
		}
		return lookupIPData(ip, cfg.IPDataKey)
	case "ipregistry":
		if strings.TrimSpace(cfg.IPRegistryKey) == "" {
			return nil, false, nil
		}
		return lookupIPRegistry(ip, cfg.IPRegistryKey)
	default:
		return nil, false, nil
	}
}

func LookupIPInfoLite(ip string, token string) (IPGeoPayload, bool, error) {
	ip = strings.TrimSpace(ip)
	if net.ParseIP(ip) == nil || strings.TrimSpace(token) == "" {
		return nil, false, nil
	}

	var data map[string]any
	endpoint := "https://api.ipinfo.io/lite/" + url.PathEscape(ip) + "?token=" + url.QueryEscape(token)
	if err := httpx.GetJSON(endpoint, 4*time.Second, &data); err != nil {
		return nil, false, err
	}

	return IPGeoPayload{
		"ip":        ip,
		"country":   stringValue(data, "country", "country_code"),
		"region":    stringValue(data, "region"),
		"city":      stringValue(data, "city"),
		"latitude":  numericOrNil(data["latitude"]),
		"longitude": numericOrNil(data["longitude"]),
		"provider":  "IPinfo Lite",
	}, true, nil
}

func lookupIPAPI(ip string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "http://ip-api.com/json/" + url.PathEscape(ip) + "?lang=zh-CN&fields=status,message,country,regionName,city,lat,lon,query,isp,org,as,asname,mobile"
	if err := httpx.GetJSON(endpoint, 4*time.Second, &data); err != nil {
		return nil, false, err
	}
	if fmt.Sprint(data["status"]) != "success" {
		return nil, false, nil
	}

	return ipGeoPayload(ip, "ip-api", data["country"], data["regionName"], data["city"], data["lat"], data["lon"], map[string]any{
		"asn":            data["as"],
		"isp":            data["isp"],
		"org":            data["org"],
		"carrier":        data["asname"],
		"mobile_network": data["mobile"],
	}), true, nil
}

func lookupIP2Location(ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ip2location.io/?key=" + url.QueryEscape(key) + "&ip=" + url.QueryEscape(ip) + "&format=json"
	if err := httpx.GetJSON(endpoint, 4*time.Second, &data); err != nil {
		return nil, false, err
	}

	return ipGeoPayload(ip, "IP2Location.io", firstAny(data["country_name"], data["country_code"]), data["region_name"], data["city_name"], data["latitude"], data["longitude"], map[string]any{
		"asn": firstAny(data["asn"], data["as"]),
		"isp": data["isp"],
		"org": data["as"],
	}), true, nil
}

func lookupIPData(ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ipdata.co/" + url.PathEscape(ip) + "?api-key=" + url.QueryEscape(key)
	if err := httpx.GetJSON(endpoint, 4*time.Second, &data); err != nil {
		return nil, false, err
	}
	asn := nestedMap(data, "asn")

	return ipGeoPayload(ip, "ipdata.co", firstAny(data["country_name"], data["country_code"]), data["region"], data["city"], data["latitude"], data["longitude"], map[string]any{
		"asn": asn["asn"],
		"isp": asn["name"],
		"org": firstAny(data["organisation"], data["organization"]),
	}), true, nil
}

func lookupIPRegistry(ip string, key string) (IPGeoPayload, bool, error) {
	var data map[string]any
	endpoint := "https://api.ipregistry.co/" + url.PathEscape(ip) + "?key=" + url.QueryEscape(key)
	if err := httpx.GetJSON(endpoint, 4*time.Second, &data); err != nil {
		return nil, false, err
	}
	location := nestedMap(data, "location")
	country := nestedMap(location, "country")
	region := nestedMap(location, "region")
	connection := nestedMap(data, "connection")

	return ipGeoPayload(ip, "Ipregistry", firstAny(country["name"], country["code"]), region["name"], location["city"], location["latitude"], location["longitude"], map[string]any{
		"asn": connection["asn"],
		"isp": connection["isp"],
		"org": connection["organization"],
	}), true, nil
}

func ipGeoPayload(ip string, provider string, country any, region any, city any, latitude any, longitude any, network map[string]any) IPGeoPayload {
	asn := strings.TrimSpace(fmt.Sprint(firstAny(network["asn"], "")))
	isp := strings.TrimSpace(fmt.Sprint(firstAny(network["isp"], "")))
	org := strings.TrimSpace(fmt.Sprint(firstAny(network["org"], "")))
	carrier := strings.TrimSpace(fmt.Sprint(firstAny(network["carrier"], "")))
	networkText := strings.ToLower(asn + " " + isp + " " + org + " " + carrier)
	mobileNetwork := truthy(network["mobile_network"]) ||
		strings.Contains(networkText, "china mobile") ||
		strings.Contains(networkText, "cmnet") ||
		strings.Contains(networkText, "cmi") ||
		strings.Contains(networkText, "中国移动") ||
		strings.Contains(networkText, "移动")

	return IPGeoPayload{
		"ip":             ip,
		"provider":       provider,
		"country":        strings.TrimSpace(fmt.Sprint(firstAny(country, ""))),
		"region":         strings.TrimSpace(fmt.Sprint(firstAny(region, ""))),
		"city":           strings.TrimSpace(fmt.Sprint(firstAny(city, ""))),
		"latitude":       numericOrNil(latitude),
		"longitude":      numericOrNil(longitude),
		"asn":            asn,
		"isp":            isp,
		"org":            org,
		"carrier":        carrier,
		"mobile_network": mobileNetwork,
	}
}

func numericOrNil(value any) any {
	switch typed := value.(type) {
	case float64:
		return typed
	case float32:
		return float64(typed)
	case int:
		return float64(typed)
	case int64:
		return float64(typed)
	case jsonNumber:
		value, err := typed.Float64()
		if err != nil {
			return nil
		}
		return value
	case string:
		text := strings.TrimSpace(typed)
		if text == "" {
			return nil
		}
		value, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return nil
		}
		return value
	default:
		return nil
	}
}

type jsonNumber interface {
	Float64() (float64, error)
}

func nestedMap(data map[string]any, key string) map[string]any {
	if nested, ok := data[key].(map[string]any); ok {
		return nested
	}
	return map[string]any{}
}

func stringValue(data map[string]any, keys ...string) string {
	for _, key := range keys {
		text := strings.TrimSpace(fmt.Sprint(data[key]))
		if text != "" && text != "<nil>" {
			return text
		}
	}
	return ""
}

func firstAny(values ...any) any {
	for _, value := range values {
		if value == nil {
			continue
		}
		text := strings.TrimSpace(fmt.Sprint(value))
		if text != "" && text != "<nil>" {
			return value
		}
	}
	return ""
}
