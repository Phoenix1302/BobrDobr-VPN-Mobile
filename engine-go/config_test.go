package main

import (
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
	// Case 1: Subscription JSON with Camouflage object
	c1 := ParsedConfig{
		Camouflage: &CamouflageConfig{
			Secret: "kurwa",
			Query:  "?kurwa",
		},
		Group: "split",
	}
	s1, g1 := resolveSecret(&c1, "")
	if s1 != "kurwa" || g1 != "split" {
		t.Errorf("Case 1 failed: secret=%s (want kurwa), group=%s (want split)", s1, g1)
	}

	// Case 2: Subscription URI with query parameters "kurwa&username=Egor_oc1"
	c2 := ParsedConfig{}
	s2, g2 := resolveSecret(&c2, "kurwa&username=Egor_oc1")
	if s2 != "kurwa" || g2 != "kurwa" {
		t.Errorf("Case 2 failed: secret=%s (want kurwa), group=%s (want kurwa)", s2, g2)
	}

	// Case 3: Mode fallback
	c3 := ParsedConfig{
		Mode: "special_route",
	}
	s3, g3 := resolveSecret(&c3, "")
	if s3 != "special_route" || g3 != "special_route" {
		t.Errorf("Case 3 failed: secret=%s, group=%s", s3, g3)
	}
}

func TestSNIExtraction(t *testing.T) {
	// Case 1: Pre-resolved IPv4 with camouflage.tls_sni
	c1 := ParsedConfig{
		Server: "176.108.243.97:443",
		Camouflage: &CamouflageConfig{
			TlsSni: "bobr.netphoenix.ru",
		},
	}
	sni1 := resolveSNI(&c1, "176.108.243.97")
	if sni1 != "bobr.netphoenix.ru" {
		t.Errorf("Case 1 failed: expected 'bobr.netphoenix.ru', got '%s'", sni1)
	}

	// Case 2: Pre-resolved IPv4 with anyconnect URI
	c2 := ParsedConfig{
		Server: "176.108.243.97:443",
		URI:    "anyconnect://bobr.netphoenix.ru:443/?kurwa&username=Egor_oc1",
	}
	sni2 := resolveSNI(&c2, "176.108.243.97")
	if sni2 != "bobr.netphoenix.ru" {
		t.Errorf("Case 2 failed: expected 'bobr.netphoenix.ru', got '%s'", sni2)
	}

	// Case 3: Pre-resolved IPv4 with original Host domain in config
	c3 := ParsedConfig{
		Server: "176.108.243.97:443",
		Host:   "bobr.netphoenix.ru",
	}
	sni3 := resolveSNI(&c3, "176.108.243.97")
	if sni3 != "bobr.netphoenix.ru" {
		t.Errorf("Case 3 failed: expected 'bobr.netphoenix.ru', got '%s'", sni3)
	}

	// Case 4: Target is domain name directly
	c4 := ParsedConfig{
		Server: "bobr.netphoenix.ru:443",
	}
	sni4 := resolveSNI(&c4, "bobr.netphoenix.ru")
	if sni4 != "bobr.netphoenix.ru" {
		t.Errorf("Case 4 failed: expected 'bobr.netphoenix.ru', got '%s'", sni4)
	}

	// Case 5: Literal IP target with no domain info -> RFC 6066 empty SNI
	c5 := ParsedConfig{
		Server: "192.168.1.1:443",
	}
	sni5 := resolveSNI(&c5, "192.168.1.1")
	if sni5 != "" {
		t.Errorf("Case 5 failed: expected empty SNI for IP literal, got '%s'", sni5)
	}
}

func TestMaskToPrefixAndCIDR(t *testing.T) {
	maskTests := []struct {
		input    string
		expected int
	}{
		{"255.255.255.0", 24},
		{"255.0.0.0", 8},
		{"255.255.255.255", 32},
		{"255.255.0.0", 16},
		{"18", 18},
		{"", 32},
	}
	for _, tc := range maskTests {
		p := maskToPrefix(tc.input)
		if p != tc.expected {
			t.Errorf("maskToPrefix('%s'): expected %d, got %d", tc.input, tc.expected, p)
		}
	}

	cidrTests := []struct {
		input    string
		expected string
	}{
		{"10.0.0.0/255.0.0.0", "10.0.0.0/8"},
		{"192.168.1.0/255.255.255.0", "192.168.1.0/24"},
		{"172.16.0.0/16", "172.16.0.0/16"},
		{"8.8.8.8", "8.8.8.8/32"},
		{"", ""},
	}
	for _, tc := range cidrTests {
		res := normalizeCIDR(tc.input)
		if res != tc.expected {
			t.Errorf("normalizeCIDR('%s'): expected '%s', got '%s'", tc.input, tc.expected, res)
		}
	}
}
