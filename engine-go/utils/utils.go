package utils

import (
	"crypto/rand"
	"fmt"
	"net"
	"net/http"
	"regexp"
	"runtime"
	"strings"

	"openconnect-worker/base"
	"openconnect-worker/utils/waterutil"
)

func InArray(arr []string, str string) bool {
	for _, d := range arr {
		if d == str {
			return true
		}
	}
	return false
}

func InArrayGeneric(arr []string, str string) bool {
	for _, d := range arr {
		if d != "" && strings.HasSuffix(str, d) {
			return true
		}
	}
	return false
}

// SetCommonHeader sets headers required for AnyConnect XML auth & CSTP tunnel
func SetCommonHeader(req *http.Request) {
	if base.Cfg.CiscoCompat || base.Cfg.AgentName == "" {
		base.Cfg.AgentName = "AnyConnect"
	}
	req.Header.Set("User-Agent", fmt.Sprintf("%s %s %s", base.Cfg.AgentName, FirstUpper(runtime.GOOS+"_"+runtime.GOARCH), base.Cfg.AgentVersion))
	req.Header.Set("Content-Type", "application/xml")
}

func IpMaskToCIDR(ipMask string) string {
	ips := strings.Split(ipMask, "/")
	if len(ips) != 2 {
		return ipMask
	}
	length, _ := net.IPMask(net.ParseIP(ips[1]).To4()).Size()
	return fmt.Sprintf("%s/%v", ips[0], length)
}

func ResolvePacket(packet []byte) (string, uint16, string, uint16) {
	if len(packet) < 20 {
		return "", 0, "", 0
	}
	src := waterutil.IPv4Source(packet)
	srcPort := waterutil.IPv4SourcePort(packet)
	dst := waterutil.IPv4Destination(packet)
	dstPort := waterutil.IPv4DestinationPort(packet)
	return src.String(), srcPort, dst.String(), dstPort
}

// MakeMasterSecret creates a 48-byte pre-master secret for legacy AnyConnect DTLS negotiation
func MakeMasterSecret() ([]byte, error) {
	masterSecret := make([]byte, 48)
	masterSecret[0] = 0xFE // DTLS 1.2 Major
	masterSecret[1] = 0xFD // DTLS 1.2 Minor
	_, err := rand.Read(masterSecret[2:])
	return masterSecret, err
}

func Min(init int, other ...int) int {
	minValue := init
	for _, val := range other {
		if val != 0 && val < minValue {
			minValue = val
		}
	}
	return minValue
}

func Max(init int, other ...int) int {
	maxValue := init
	for _, val := range other {
		if val > maxValue {
			maxValue = val
		}
	}
	return maxValue
}

func FirstUpper(s string) string {
	if s == "" {
		return ""
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

func RemoveBetween(input, start, end string) string {
	pattern := "(?s)" + regexp.QuoteMeta(start) + ".*?" + regexp.QuoteMeta(end)
	r := regexp.MustCompile(pattern)
	return r.ReplaceAllString(input, "")
}
