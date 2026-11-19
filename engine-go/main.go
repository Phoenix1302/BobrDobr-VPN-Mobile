package main

/*
#define CGO_ENGINE_INTERNAL
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include "engine_api.h"

static inline bool call_protect_cb(protect_callback_fn fn, int socket_fd) {
    if (fn == NULL) return true;
    return fn(socket_fd);
}

static inline void call_status_cb(status_callback_fn fn, int status_code, const char* message) {
    if (fn != NULL) {
        fn(status_code, message);
    }
}
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"openconnect-worker/auth"
	"openconnect-worker/base"
	"openconnect-worker/session"
	"openconnect-worker/tun"
	"openconnect-worker/vpn"
)

var (
	tunnelMu            sync.Mutex
	isRunning           bool
	activeTunFile       *os.File
	activeCSess         *session.ConnSession
	globalProtectCb     C.protect_callback_fn
	globalStatusCb      C.status_callback_fn
	cbMu                sync.RWMutex

	lastHandshakeTimeMs atomic.Uint64
	currentNetId        atomic.Int64

	// Speed calculation state
	speedMu             sync.Mutex
	lastSpeedCalcTime   time.Time
	lastRxBytes         uint64
	lastTxBytes         uint64
	currentRxSpeedBps   uint64
	currentTxSpeedBps   uint64
)

func protectSocket(socketFd int) bool {
	cbMu.RLock()
	fn := globalProtectCb
	cbMu.RUnlock()
	if fn == nil {
		return true
	}
	return bool(C.call_protect_cb(fn, C.int(socketFd)))
}

func reportStatus(statusCode int, message string) {
	cbMu.RLock()
	fn := globalStatusCb
	cbMu.RUnlock()
	if fn == nil {
		return
	}
	cMsg := C.CString(message)
	defer C.free(unsafe.Pointer(cMsg))
	C.call_status_cb(fn, C.int(statusCode), cMsg)
}

type ParsedConfig struct {
	Server             string `json:"server"`
	ServerAddress      string `json:"serverAddress"`
	Host               string `json:"host"`
	Port               int    `json:"port"`
	ServerPort         int    `json:"serverPort"`
	Username           string `json:"username"`
	User               string `json:"user"`
	Password           string `json:"password"`
	Pass               string `json:"pass"`
	Group              string `json:"group"`
	AuthGroup          string `json:"authgroup"`
	Secret             string `json:"secret"`
	SecretKey          string `json:"secretKey"`
	Key                string `json:"key"`
	Mode               string `json:"mode"`
	Camouflage         *struct {
		Enabled            bool   `json:"enabled"`
		Secret             string `json:"secret"`
		Query              string `json:"query"`
		Path               string `json:"path"`
		ConnectUrl         string `json:"connect_url"`
		HostWithCamouflage string `json:"host_with_camouflage"`
		TlsSni             string `json:"tls_sni"`
		Dtls               bool   `json:"dtls"`
	} `json:"camouflage"`
	Insecure           *bool  `json:"insecure"`
	SkipVerify         *bool  `json:"skip_verify"`
	InsecureSkipVerify *bool  `json:"insecureSkipVerify"`
	NoDtls             *bool  `json:"no_dtls"`
	NoDTLS             *bool  `json:"noDtls"`
	MTU                int    `json:"mtu"`
	RawConfigJson      string `json:"rawConfigJson"`
}

func parseTarget(target string) (host string, port int, secret string) {
	raw := strings.TrimSpace(target)
	if strings.Contains(raw, "://") {
		u, err := url.Parse(raw)
		if err == nil {
			host = u.Hostname()
			if p := u.Port(); p != "" {
				port, _ = strconv.Atoi(p)
			}
			secret = u.RawQuery
			if secret == "" && u.Path != "" && u.Path != "/" {
				secret = strings.Trim(u.Path, "/")
			}
			return
		}
	}

	if strings.Contains(raw, "?") {
		parts := strings.SplitN(raw, "?", 2)
		raw = parts[0]
		secret = parts[1]
	}

	raw = strings.TrimRight(raw, "/")
	if strings.Contains(raw, "/") {
		parts := strings.SplitN(raw, "/", 2)
		raw = parts[0]
		if secret == "" {
			secret = parts[1]
		}
	}

	if strings.Contains(raw, ":") {
		parts := strings.SplitN(raw, ":", 2)
		host = parts[0]
		port, _ = strconv.Atoi(parts[1])
	} else {
		host = raw
		port = 443
	}

	if strings.Contains(secret, ":") {
		secParts := strings.SplitN(secret, ":", 2)
		secret = secParts[0]
		if port == 443 {
			if p, err := strconv.Atoi(secParts[1]); err == nil && p > 0 {
				port = p
			}
		}
	}

	return
}

func mergeConfigs(primary *ParsedConfig, secondary *ParsedConfig) {
	if secondary.Server != "" && primary.Server == "" {
		primary.Server = secondary.Server
	}
	if secondary.ServerAddress != "" && primary.ServerAddress == "" {
		primary.ServerAddress = secondary.ServerAddress
	}
	if secondary.Host != "" && primary.Host == "" {
		primary.Host = secondary.Host
	}
	if secondary.Port != 0 && primary.Port == 0 {
		primary.Port = secondary.Port
	}
	if secondary.ServerPort != 0 && primary.ServerPort == 0 {
		primary.ServerPort = secondary.ServerPort
	}
	if secondary.Username != "" && primary.Username == "" {
		primary.Username = secondary.Username
	}
	if secondary.User != "" && primary.User == "" {
		primary.User = secondary.User
	}
	if secondary.Password != "" && primary.Password == "" {
		primary.Password = secondary.Password
	}
	if secondary.Pass != "" && primary.Pass == "" {
		primary.Pass = secondary.Pass
	}
	if secondary.Group != "" && primary.Group == "" {
		primary.Group = secondary.Group
	}
	if secondary.AuthGroup != "" && primary.AuthGroup == "" {
		primary.AuthGroup = secondary.AuthGroup
	}
	if secondary.Secret != "" && primary.Secret == "" {
		primary.Secret = secondary.Secret
	}
	if secondary.SecretKey != "" && primary.SecretKey == "" {
		primary.SecretKey = secondary.SecretKey
	}
	if secondary.Key != "" && primary.Key == "" {
		primary.Key = secondary.Key
	}
	if secondary.Mode != "" && primary.Mode == "" {
		primary.Mode = secondary.Mode
	}
	if secondary.Camouflage != nil && primary.Camouflage == nil {
		primary.Camouflage = secondary.Camouflage
	}
	if primary.Insecure == nil {
		primary.Insecure = secondary.Insecure
	}
	if primary.NoDtls == nil {
		primary.NoDtls = secondary.NoDtls
	}
	if secondary.MTU != 0 && primary.MTU == 0 {
		primary.MTU = secondary.MTU
	}
}

//export start_tunnel
func start_tunnel(tun_fd C.int, config_json *C.char, cb C.protect_callback_fn, scb C.status_callback_fn) C.int {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()

	if isRunning {
		base.Warn("Tunnel is already running")
		return C.ENGINE_ERR_ALREADY_RUNNING
	}

	if tun_fd < 0 || config_json == nil {
		return C.ENGINE_ERR_INVALID_ARG
	}

	cbMu.Lock()
	globalProtectCb = cb
	globalStatusCb = scb
	cbMu.Unlock()

	configStr := C.GoString(config_json)
	var parsed ParsedConfig
	if err := json.Unmarshal([]byte(configStr), &parsed); err != nil {
		base.Error("Failed to parse config JSON:", err)
		return C.ENGINE_ERR_INVALID_CONFIG
	}

	// If rawConfigJson is embedded, merge it
	if parsed.RawConfigJson != "" && parsed.RawConfigJson != "{}" {
		var rawParsed ParsedConfig
		if err := json.Unmarshal([]byte(parsed.RawConfigJson), &rawParsed); err == nil {
			mergeConfigs(&parsed, &rawParsed)
		}
	}

	// Resolve server address & port
	targetServer := parsed.Server
	if targetServer == "" {
		targetServer = parsed.ServerAddress
	}
	if targetServer == "" {
		targetServer = parsed.Host
	}
	if targetServer == "" {
		base.Error("Config missing server destination")
		return C.ENGINE_ERR_INVALID_CONFIG
	}

	parsedHost, parsedPort, parsedSecret := parseTarget(targetServer)
	if parsedPort == 0 || parsedPort == 443 {
		if parsed.Port != 0 {
			parsedPort = parsed.Port
		} else if parsed.ServerPort != 0 {
			parsedPort = parsed.ServerPort
		}
	}

	secretKey := parsed.Secret
	if parsed.Camouflage != nil {
		if parsed.Camouflage.Secret != "" {
			secretKey = parsed.Camouflage.Secret
		} else if parsed.Camouflage.Query != "" {
			secretKey = strings.TrimPrefix(parsed.Camouflage.Query, "?")
		} else if parsed.Camouflage.Path != "" {
			secretKey = strings.TrimPrefix(parsed.Camouflage.Path, "/?")
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

	// If secretKey contains query parameters (e.g. "kurwa&username=Egor_oc1")
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

	username := parsed.Username
	if username == "" {
		username = parsed.User
	}
	password := parsed.Password
	if password == "" {
		password = parsed.Pass
	}
	group := parsed.Group
	if group == "" {
		group = parsed.AuthGroup
	}
	if group == "" && secretKey != "" {
		group = secretKey
	}

	insecure := true
	if parsed.Insecure != nil {
		insecure = *parsed.Insecure
	} else if parsed.SkipVerify != nil {
		insecure = *parsed.SkipVerify
	} else if parsed.InsecureSkipVerify != nil {
		insecure = *parsed.InsecureSkipVerify
	}

	noDtls := false
	if parsed.NoDtls != nil {
		noDtls = *parsed.NoDtls
	} else if parsed.NoDTLS != nil {
		noDtls = *parsed.NoDTLS
	}

	base.InitCfg()
	base.InitLog()
	base.Cfg.InsecureSkipVerify = insecure
	base.Cfg.NoDTLS = noDtls

	auth.ResetProfile()
	auth.Prof.Host = parsedHost
	if parsedPort != 0 && parsedPort != 443 {
		auth.Prof.HostWithPort = fmt.Sprintf("%s:%d", parsedHost, parsedPort)
	} else if strings.Contains(parsedHost, ":") {
		auth.Prof.HostWithPort = parsedHost
	} else {
		auth.Prof.HostWithPort = fmt.Sprintf("%s:443", parsedHost)
	}
	auth.Prof.Username = username
	auth.Prof.Password = password
	auth.Prof.Group = group
	auth.Prof.SecretKey = strings.TrimLeft(secretKey, "/?")

	reportStatus(C.STATUS_CONNECTING, "Starting AnyConnect authentication with server...")

	// Step 1: AnyConnect XML Authentication Handshake
	if err := auth.InitAuth(protectSocket); err != nil {
		base.Error("InitAuth failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("Authentication handshake failed: %v", err))
		return C.ENGINE_ERR_HANDSHAKE
	}

	if err := auth.PasswordAuth(); err != nil {
		base.Error("PasswordAuth failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("Authentication failed: %v", err))
		return C.ENGINE_ERR_HANDSHAKE
	}

	reportStatus(C.STATUS_CONNECTING, "Authenticated successfully, establishing CSTP tunnel...")

	// Step 2: Establish CSTP & DTLS Channels
	cSess, err := vpn.SetupTunnel(protectSocket)
	if err != nil {
		base.Error("SetupTunnel failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("CSTP tunnel setup failed: %v", err))
		return C.ENGINE_ERR_SOCKET_INIT
	}

	// Step 3: Wrap Android TUN File Descriptor and Start Packet IO
	tunFile := os.NewFile(uintptr(tun_fd), "/dev/tun")
	if tunFile == nil {
		base.Error("Failed to wrap tun_fd into os.File")
		cSess.Close()
		reportStatus(C.STATUS_ERROR, "Failed to wrap Android TUN file descriptor")
		return C.ENGINE_ERR_GENERIC
	}

	tun.ResetStats()
	tun.StartTunLoops(tunFile, cSess)

	activeTunFile = tunFile
	activeCSess = cSess
	isRunning = true
	nowMs := uint64(time.Now().UnixMilli())
	lastHandshakeTimeMs.Store(nowMs)

	// Initialize speed calculation baseline
	speedMu.Lock()
	lastSpeedCalcTime = time.Now()
	lastRxBytes = 0
	lastTxBytes = 0
	currentRxSpeedBps = 0
	currentTxSpeedBps = 0
	speedMu.Unlock()

	base.Info("Go AnyConnect VPN Engine started successfully")
	reportStatus(C.STATUS_CONNECTED, "Connected to AnyConnect server")

	return C.ENGINE_SUCCESS
}

//export stop_tunnel
func stop_tunnel() C.int {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()

	if !isRunning {
		return C.ENGINE_SUCCESS
	}

	reportStatus(C.STATUS_DISCONNECTING, "Terminating AnyConnect tunnel...")
	base.Info("Stopping Go AnyConnect VPN Engine (graceful disconnect)...")

	// Trigger CSTP BYE packet and close session
	if activeCSess != nil {
		activeCSess.Sess.ActiveClose = true
		if auth.Conn != nil {
			activeCSess.SendBye(auth.Conn)
		}
		activeCSess.Close()
		activeCSess = nil
	}

	if auth.Conn != nil {
		_ = auth.Conn.Close()
		auth.Conn = nil
	}

	if activeTunFile != nil {
		_ = activeTunFile.Close()
		activeTunFile = nil
	}

	isRunning = false
	reportStatus(C.STATUS_DISCONNECTED, "Disconnected")
	base.Info("Go AnyConnect VPN Engine stopped cleanly")

	return C.ENGINE_SUCCESS
}

//export update_network
func update_network(net_id C.int64_t) C.int {
	currentNetId.Store(int64(net_id))
	base.Info("Go Engine received physical network update: net_id =", int64(net_id))
	return C.ENGINE_SUCCESS
}

//export get_tunnel_stats
func get_tunnel_stats(stats *C.tunnel_stats_t) C.int {
	if stats == nil {
		return C.ENGINE_ERR_INVALID_ARG
	}

	rxB := tun.StatsRxBytes.Load()
	txB := tun.StatsTxBytes.Load()
	rxP := tun.StatsRxPackets.Load()
	txP := tun.StatsTxPackets.Load()

	// Calculate instantaneous speeds
	speedMu.Lock()
	now := time.Now()
	elapsed := now.Sub(lastSpeedCalcTime)
	if elapsed >= 500*time.Millisecond {
		deltaRx := rxB - lastRxBytes
		deltaTx := txB - lastTxBytes
		seconds := elapsed.Seconds()
		if seconds > 0 {
			currentRxSpeedBps = uint64(float64(deltaRx*8) / seconds)
			currentTxSpeedBps = uint64(float64(deltaTx*8) / seconds)
		}
		lastSpeedCalcTime = now
		lastRxBytes = rxB
		lastTxBytes = txB
	}
	rxSpeed := currentRxSpeedBps
	txSpeed := currentTxSpeedBps
	speedMu.Unlock()

	stats.rx_bytes = C.uint64_t(rxB)
	stats.tx_bytes = C.uint64_t(txB)
	stats.rx_packets = C.uint64_t(rxP)
	stats.tx_packets = C.uint64_t(txP)
	stats.rx_speed_bps = C.uint64_t(rxSpeed)
	stats.tx_speed_bps = C.uint64_t(txSpeed)
	stats.ping_ms = C.uint32_t(25) // Baseline RTT estimation
	stats.last_handshake_timestamp = C.uint64_t(lastHandshakeTimeMs.Load())
	stats.timestamp = C.uint64_t(time.Now().UnixMilli())

	return C.ENGINE_SUCCESS
}

func main() {}
