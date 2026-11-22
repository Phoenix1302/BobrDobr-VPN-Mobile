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

static inline int call_tun_config_cb(tun_config_callback_fn fn, const char* tun_config_json) {
    if (fn == NULL) return -1;
    return fn(tun_config_json);
}
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"openconnect-worker/auth"
	"openconnect-worker/base"
	"openconnect-worker/session"
	"openconnect-worker/tun"
	"openconnect-worker/vpn"
)

const (
	engineStateDisconnected  = 0
	engineStateConnecting    = 1
	engineStateConnected     = 2
	engineStateDisconnecting = 3
)

var (
	tunnelMu            sync.Mutex
	engineState         int = engineStateDisconnected
	isRunning           bool
	connectCtx          context.Context
	connectCancel       context.CancelFunc
	activeTunFile       *os.File
	activeCSess         *session.ConnSession
	globalProtectCb     C.protect_callback_fn
	globalStatusCb      C.status_callback_fn
	globalTunConfigCb   C.tun_config_callback_fn
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

type CamouflageConfig struct {
	Enabled            bool   `json:"enabled"`
	Secret             string `json:"secret"`
	Query              string `json:"query"`
	Path               string `json:"path"`
	ConnectUrl         string `json:"connect_url"`
	HostWithCamouflage string `json:"host_with_camouflage"`
	TlsSni             string `json:"tls_sni"`
	Dtls               bool   `json:"dtls"`
}

type ParsedConfig struct {
	Server             string            `json:"server"`
	ServerAddress      string            `json:"serverAddress"`
	Host               string            `json:"host"`
	Port               int               `json:"port"`
	ServerPort         int               `json:"serverPort"`
	URI                string            `json:"uri"`
	OriginalHost       string            `json:"original_host"`
	SNI                string            `json:"sni"`
	TlsSni             string            `json:"tls_sni"`
	Username           string            `json:"username"`
	User               string            `json:"user"`
	Password           string            `json:"password"`
	Pass               string            `json:"pass"`
	Group              string            `json:"group"`
	AuthGroup          string            `json:"authgroup"`
	Secret             string            `json:"secret"`
	SecretKey          string            `json:"secretKey"`
	Key                string            `json:"key"`
	Mode               string            `json:"mode"`
	Camouflage         *CamouflageConfig `json:"camouflage"`
	Insecure           *bool             `json:"insecure"`
	SkipVerify         *bool             `json:"skip_verify"`
	InsecureSkipVerify *bool             `json:"insecureSkipVerify"`
	NoDtls             *bool             `json:"no_dtls"`
	NoDTLS             *bool             `json:"noDtls"`
	MTU                int               `json:"mtu"`
	RawConfigJson      string            `json:"rawConfigJson"`
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
	if secondary.URI != "" && primary.URI == "" {
		primary.URI = secondary.URI
	}
	if secondary.OriginalHost != "" && primary.OriginalHost == "" {
		primary.OriginalHost = secondary.OriginalHost
	}
	if secondary.SNI != "" && primary.SNI == "" {
		primary.SNI = secondary.SNI
	}
	if secondary.TlsSni != "" && primary.TlsSni == "" {
		primary.TlsSni = secondary.TlsSni
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
	if primary.SkipVerify == nil {
		primary.SkipVerify = secondary.SkipVerify
	}
	if primary.InsecureSkipVerify == nil {
		primary.InsecureSkipVerify = secondary.InsecureSkipVerify
	}
	if primary.NoDtls == nil {
		primary.NoDtls = secondary.NoDtls
	}
	if primary.NoDTLS == nil {
		primary.NoDTLS = secondary.NoDTLS
	}
	if secondary.MTU != 0 && primary.MTU == 0 {
		primary.MTU = secondary.MTU
	}
}

func resolveSNI(parsed *ParsedConfig, parsedHost string) string {
	sni := ""
	if parsed.Camouflage != nil && parsed.Camouflage.TlsSni != "" {
		sni = parsed.Camouflage.TlsSni
	}
	if sni == "" && parsed.TlsSni != "" {
		sni = parsed.TlsSni
	}
	if sni == "" && parsed.OriginalHost != "" && net.ParseIP(parsed.OriginalHost) == nil {
		sni = parsed.OriginalHost
	}
	if sni == "" && parsed.SNI != "" {
		sni = parsed.SNI
	}
	if sni == "" && parsed.URI != "" {
		if u, err := url.Parse(parsed.URI); err == nil {
			h := u.Hostname()
			if h != "" && net.ParseIP(h) == nil {
				sni = h
			}
		}
	}
	if sni == "" && parsedHost != "" && net.ParseIP(parsedHost) == nil {
		sni = parsedHost
	}
	if sni == "" && parsed.Host != "" && net.ParseIP(parsed.Host) == nil {
		sni = parsed.Host
	}
	return sni
}

func resolveSecret(parsed *ParsedConfig, parsedSecret string) (secretKey string, group string) {
	if parsed.Secret != "" {
		secretKey = parsed.Secret
	}
	if secretKey == "" && parsed.Camouflage != nil {
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

	group = parsed.Group
	if group == "" {
		group = parsed.AuthGroup
	}
	if group == "" && secretKey != "" {
		group = secretKey
	}
	return secretKey, group
}

func prepareTunFile(fd int) (*os.File, error) {
	if fd < 0 {
		return nil, errors.New("invalid tun_fd")
	}
	dupFd, err := syscall.Dup(fd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup failed: %w", err)
	}
	syscall.CloseOnExec(dupFd)

	// Clear O_NONBLOCK flag on dupFd so tunFile.Read() blocks normally and does not fail with EAGAIN
	flags, _, errNo := syscall.Syscall(syscall.SYS_FCNTL, uintptr(dupFd), syscall.F_GETFL, 0)
	if errNo == 0 {
		syscall.Syscall(syscall.SYS_FCNTL, uintptr(dupFd), syscall.F_SETFL, flags &^ uintptr(syscall.O_NONBLOCK))
	}

	tunFile := os.NewFile(uintptr(dupFd), "/dev/tun")
	if tunFile == nil {
		_ = syscall.Close(dupFd)
		return nil, errors.New("os.NewFile returned nil")
	}
	return tunFile, nil
}

func maskToPrefix(maskStr string) int {
	if maskStr == "" {
		return 32
	}
	ip := net.ParseIP(maskStr)
	if ip != nil {
		mask := net.IPMask(ip.To4())
		if mask != nil {
			ones, _ := mask.Size()
			if ones > 0 || maskStr == "0.0.0.0" {
				return ones
			}
		}
	}
	if p, err := strconv.Atoi(maskStr); err == nil && p >= 0 && p <= 32 {
		return p
	}
	return 32
}

func normalizeCIDR(entry string) string {
	entry = strings.TrimSpace(entry)
	if entry == "" {
		return ""
	}
	if !strings.Contains(entry, "/") {
		return entry + "/32"
	}
	parts := strings.SplitN(entry, "/", 2)
	ipPart := parts[0]
	maskPart := parts[1]

	if p, err := strconv.Atoi(maskPart); err == nil && p >= 0 && p <= 32 {
		return fmt.Sprintf("%s/%d", ipPart, p)
	}

	maskIP := net.ParseIP(maskPart)
	if maskIP != nil {
		mask := net.IPMask(maskIP.To4())
		if mask != nil {
			ones, _ := mask.Size()
			return fmt.Sprintf("%s/%d", ipPart, ones)
		}
	}
	return entry
}

type TunConfig struct {
	IPv4         string   `json:"ipv4"`
	IPv4Prefix   int      `json:"ipv4_prefix"`
	MTU          int      `json:"mtu"`
	DNS          []string `json:"dns"`
	SplitInclude []string `json:"split_include"`
	SplitExclude []string `json:"split_exclude"`
}

//export start_tunnel
func start_tunnel(tun_fd C.int, config_json *C.char, cb C.protect_callback_fn, scb C.status_callback_fn) C.int {
	return start_tunnel_v2(tun_fd, config_json, cb, scb, nil)
}

//export start_tunnel_v2
func start_tunnel_v2(tun_fd C.int, config_json *C.char, cb C.protect_callback_fn, scb C.status_callback_fn, tcb C.tun_config_callback_fn) C.int {
	tunnelMu.Lock()
	if engineState != engineStateDisconnected {
		tunnelMu.Unlock()
		base.Warn("Tunnel is already running or connecting")
		return C.ENGINE_ERR_ALREADY_RUNNING
	}

	if config_json == nil || (tcb == nil && tun_fd < 0) {
		tunnelMu.Unlock()
		return C.ENGINE_ERR_INVALID_ARG
	}

	engineState = engineStateConnecting
	ctx, cancel := context.WithCancel(context.Background())
	connectCtx = ctx
	connectCancel = cancel

	cbMu.Lock()
	globalProtectCb = cb
	globalStatusCb = scb
	globalTunConfigCb = tcb
	cbMu.Unlock()
	tunnelMu.Unlock()

	failConnect := func(errCode C.int) C.int {
		tunnelMu.Lock()
		engineState = engineStateDisconnected
		connectCancel = nil
		tunnelMu.Unlock()
		return errCode
	}

	configStr := C.GoString(config_json)
	var parsed ParsedConfig
	if err := json.Unmarshal([]byte(configStr), &parsed); err != nil {
		base.Error("Failed to parse config JSON:", err)
		return failConnect(C.ENGINE_ERR_INVALID_CONFIG)
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
		return failConnect(C.ENGINE_ERR_INVALID_CONFIG)
	}

	parsedHost, parsedPort, parsedSecret := parseTarget(targetServer)
	if parsedPort == 0 || parsedPort == 443 {
		if parsed.Port != 0 {
			parsedPort = parsed.Port
		} else if parsed.ServerPort != 0 {
			parsedPort = parsed.ServerPort
		}
	}

	secretKey, group := resolveSecret(&parsed, parsedSecret)
	sni := resolveSNI(&parsed, parsedHost)

	username := parsed.Username
	if username == "" {
		username = parsed.User
	}
	password := parsed.Password
	if password == "" {
		password = parsed.Pass
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
	auth.Prof.Port = parsedPort
	auth.Prof.SNI = sni
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

	if ctx.Err() != nil {
		return failConnect(C.ENGINE_ERR_GENERIC)
	}

	reportStatus(C.STATUS_CONNECTING, "Starting AnyConnect authentication with server...")

	// Step 1: AnyConnect XML Authentication Handshake
	if err := auth.InitAuth(protectSocket); err != nil {
		base.Error("InitAuth failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("Authentication handshake failed: %v", err))
		return failConnect(C.ENGINE_ERR_HANDSHAKE)
	}

	if ctx.Err() != nil {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		return failConnect(C.ENGINE_ERR_GENERIC)
	}

	if err := auth.PasswordAuth(); err != nil {
		base.Error("PasswordAuth failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("Authentication failed: %v", err))
		return failConnect(C.ENGINE_ERR_HANDSHAKE)
	}

	if ctx.Err() != nil {
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		return failConnect(C.ENGINE_ERR_GENERIC)
	}

	reportStatus(C.STATUS_CONNECTING, "Authenticated successfully, establishing CSTP tunnel...")

	// Step 2: Establish CSTP & DTLS Channels
	cSess, err := vpn.SetupTunnel(protectSocket)
	if err != nil {
		base.Error("SetupTunnel failed:", err)
		reportStatus(C.STATUS_ERROR, fmt.Sprintf("CSTP tunnel setup failed: %v", err))
		return failConnect(C.ENGINE_ERR_SOCKET_INIT)
	}

	if ctx.Err() != nil {
		cSess.Close()
		if auth.Conn != nil {
			_ = auth.Conn.Close()
			auth.Conn = nil
		}
		return failConnect(C.ENGINE_ERR_GENERIC)
	}

	// Step 3: Wrap Android TUN File Descriptor (Two-Phase or Pre-allocated)
	var tunFile *os.File
	if tcb != nil {
		// Two-phase TUN initialization: send dynamic config to Kotlin VpnService
		prefix := maskToPrefix(cSess.VPNMask)

		var splitInc []string
		for _, s := range cSess.SplitInclude {
			if n := normalizeCIDR(s); n != "" {
				splitInc = append(splitInc, n)
			}
		}
		if splitInc == nil {
			splitInc = []string{}
		}

		var splitExc []string
		for _, s := range cSess.SplitExclude {
			if n := normalizeCIDR(s); n != "" {
				splitExc = append(splitExc, n)
			}
		}
		if splitExc == nil {
			splitExc = []string{}
		}

		dnsList := cSess.DNS
		if dnsList == nil {
			dnsList = []string{}
		}

		tunCfg := TunConfig{
			IPv4:         cSess.VPNAddress,
			IPv4Prefix:   prefix,
			MTU:          cSess.MTU,
			DNS:          dnsList,
			SplitInclude: splitInc,
			SplitExclude: splitExc,
		}

		tunCfgBytes, err := json.Marshal(tunCfg)
		if err != nil {
			base.Error("Failed to marshal TunConfig JSON:", err)
			cSess.Close()
			reportStatus(C.STATUS_ERROR, "Failed to marshal TUN config")
			return failConnect(C.ENGINE_ERR_GENERIC)
		}

		base.Info("Requesting Android TUN via tun_config_callback:", string(tunCfgBytes))
		cTunCfg := C.CString(string(tunCfgBytes))
		retTunFd := int(C.call_tun_config_cb(tcb, cTunCfg))
		C.free(unsafe.Pointer(cTunCfg))

		if retTunFd < 0 {
			base.Error("tun_config_callback failed: returned fd =", retTunFd)
			cSess.Close()
			reportStatus(C.STATUS_ERROR, fmt.Sprintf("Failed to establish TUN interface in Android (code=%d)", retTunFd))
			return failConnect(C.ENGINE_ERR_SOCKET_INIT)
		}

		tunFile, err = prepareTunFile(retTunFd)
		if err != nil {
			base.Error("Failed to prepare duplicated TUN file from callback:", err)
			cSess.Close()
			reportStatus(C.STATUS_ERROR, fmt.Sprintf("Failed to wrap callback TUN descriptor: %v", err))
			return failConnect(C.ENGINE_ERR_SOCKET_INIT)
		}
	} else {
		// Pre-created TUN descriptor mode
		tunFile, err = prepareTunFile(int(tun_fd))
		if err != nil {
			base.Error("Failed to prepare duplicated TUN file from tun_fd:", err)
			cSess.Close()
			reportStatus(C.STATUS_ERROR, "Failed to wrap Android TUN file descriptor")
			return failConnect(C.ENGINE_ERR_SOCKET_INIT)
		}
	}

	tunnelMu.Lock()
	if ctx.Err() != nil {
		tunnelMu.Unlock()
		_ = tunFile.Close()
		cSess.Close()
		return failConnect(C.ENGINE_ERR_GENERIC)
	}

	tun.ResetStats()
	tun.StartTunLoops(tunFile, cSess)

	activeTunFile = tunFile
	activeCSess = cSess
	isRunning = true
	engineState = engineStateConnected
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

	// Launch server disconnect watcher goroutine
	go func(sess *session.ConnSession, f *os.File) {
		<-sess.CloseChan
		tunnelMu.Lock()
		defer tunnelMu.Unlock()
		if activeCSess == sess && engineState == engineStateConnected {
			isRunning = false
			engineState = engineStateDisconnected
			activeCSess = nil
			activeTunFile = nil
			if f != nil {
				_ = f.Close()
			}
			if auth.Conn != nil {
				_ = auth.Conn.Close()
				auth.Conn = nil
			}
			base.Info("AnyConnect server closed connection")
			reportStatus(C.STATUS_DISCONNECTED, "Server closed connection")
		}
	}(cSess, tunFile)

	tunnelMu.Unlock()

	base.Info("Go AnyConnect VPN Engine started successfully")
	reportStatus(C.STATUS_CONNECTED, "Connected to AnyConnect server")

	return C.ENGINE_SUCCESS
}

//export stop_tunnel
func stop_tunnel() C.int {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()

	if engineState == engineStateDisconnected {
		return C.ENGINE_SUCCESS
	}

	if engineState == engineStateConnecting {
		base.Info("Cancelling in-progress AnyConnect connection...")
		if connectCancel != nil {
			connectCancel()
			connectCancel = nil
		}
		engineState = engineStateDisconnected
		reportStatus(C.STATUS_DISCONNECTED, "Connection cancelled")
		return C.ENGINE_SUCCESS
	}

	engineState = engineStateDisconnecting
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
	engineState = engineStateDisconnected
	reportStatus(C.STATUS_DISCONNECTED, "Disconnected")
	base.Info("Go AnyConnect VPN Engine stopped cleanly")

	return C.ENGINE_SUCCESS
}

//export update_network
func update_network(net_id C.int64_t) C.int {
	currentNetId.Store(int64(net_id))
	base.Warn("Go Engine cannot rebind an established CSTP/DTLS socket in place; requesting reconnect for net_id =", int64(net_id))
	return C.ENGINE_ERR_GENERIC
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
	stats.ping_ms = C.uint32_t(0) // Reset per P1-4 telemetry spec
	stats.last_handshake_timestamp = C.uint64_t(lastHandshakeTimeMs.Load())
	stats.timestamp = C.uint64_t(time.Now().UnixMilli())

	return C.ENGINE_SUCCESS
}

func main() {}
