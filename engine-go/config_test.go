package main

import (
	"strings"
	"testing"
)

func TestParseTarget(t *testing.T) {
	tests := []struct {
		input          string
		expectedHost   string
		expectedPort   int
		expectedSecret string
	}{
		{
			input:          "https://vpn.example.com:443/?secret_camou",
			expectedHost:   "vpn.example.com",
			expectedPort:   443,
			expectedSecret: "secret_camou",
		},
		{
			input:          "vpn.example.com:8443/?custom_token",
			expectedHost:   "vpn.example.com",
			expectedPort:   8443,
			expectedSecret: "custom_token",
		},
		{
			input:          "vpn.example.com",
			expectedHost:   "vpn.example.com",
			expectedPort:   443,
			expectedSecret: "",
		},
		{
			input:          "192.168.1.1:4443",
			expectedHost:   "192.168.1.1",
			expectedPort:   4443,
			expectedSecret: "",
		},
		{
			input:          "anyconnect://bobr.netphoenix.ru:443/?kurwa&username=Egor_oc1",
			expectedHost:   "bobr.netphoenix.ru",
			expectedPort:   443,
			expectedSecret: "kurwa&username=Egor_oc1",
		},
	}

	for _, tc := range tests {
		host, port, secret := parseTarget(tc.input)
		if host != tc.expectedHost {
			t.Errorf("input '%s': expected host '%s', got '%s'", tc.input, tc.expectedHost, host)
		}
		if port != tc.expectedPort {
			t.Errorf("input '%s': expected port %d, got %d", tc.input, tc.expectedPort, port)
		}
		if secret != tc.expectedSecret {
			t.Errorf("input '%s': expected secret '%s', got '%s'", tc.input, tc.expectedSecret, secret)
		}
	}
}

func TestSecretKeyExtraction(t *testing.T) {
	resolveSecret := func(parsed ParsedConfig, parsedSecret string) (string, string) {
		secretKey := parsed.Secret
		if secretKey == "" && parsed.Camouflage != nil {
			secretKey = parsed.Camouflage.Secret
			if secretKey == "" {
				secretKey = strings.TrimPrefix(parsed.Camouflage.Query, "?")
			}
		}
		if secretKey == "" {
			secretKey = parsed.SecretKey
		}
		if secretKey == "" {
			secretKey = parsed.Key
		}
		if secretKey == "" && parsedSecret != "" {
			secretKey = parsedSecret
		}
		if secretKey == "" && parsed.Group != "" {
			secretKey = parsed.Group
		}
		if secretKey == "" && parsed.Mode != "" {
			secretKey = parsed.Mode
		}

		if strings.Contains(secretKey, "&") {
			parts := strings.Split(secretKey, "&")
			for _, p := range parts {
				if !strings.Contains(p, "=") {
					secretKey = p
					break
				} else if strings.HasPrefix(p, "secret=") {
					secretKey = strings.TrimPrefix(p, "secret=")
					break
				}
			}
		}
		secretKey = strings.TrimLeft(secretKey, "/?")

		group := parsed.Group
		if group == "" && secretKey != "" {
			group = secretKey
		}
		return secretKey, group
	}

	// Case 1: Subscription JSON with Camouflage object
	c1 := ParsedConfig{
		Camouflage: &struct {
			Secret string `json:"secret"`
			Query  string `json:"query"`
		}{
			Secret: "kurwa",
			Query:  "?kurwa",
		},
		Group: "split",
	}
	s1, g1 := resolveSecret(c1, "")
	if s1 != "kurwa" || g1 != "split" {
		t.Errorf("Case 1 failed: secret=%s (want kurwa), group=%s (want split)", s1, g1)
	}

	// Case 2: Subscription URI with query parameters "kurwa&username=Egor_oc1"
	c2 := ParsedConfig{}
	s2, g2 := resolveSecret(c2, "kurwa&username=Egor_oc1")
	if s2 != "kurwa" || g2 != "kurwa" {
		t.Errorf("Case 2 failed: secret=%s (want kurwa), group=%s (want kurwa)", s2, g2)
	}

	// Case 3: Mode fallback
	c3 := ParsedConfig{
		Mode: "special_route",
	}
	s3, g3 := resolveSecret(c3, "")
	if s3 != "special_route" || g3 != "special_route" {
		t.Errorf("Case 3 failed: secret=%s, group=%s", s3, g3)
	}
}
