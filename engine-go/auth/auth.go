package auth

import (
	"bufio"
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"text/template"
	"time"

	tls "github.com/refraction-networking/utls"
	"openconnect-worker/base"
	"openconnect-worker/proto"
	"openconnect-worker/session"
	"openconnect-worker/utils"
)

var (
	Prof         = &Profile{Initialized: false}
	Conn         *tls.UConn
	BufR         *bufio.Reader
	reqHeaders   = make(map[string]string)
	WebVpnCookie string
)

type Profile struct {
	Host      string `json:"host"`
	Port      int    `json:"port"`
	SNI       string `json:"sni"`
	Username  string `json:"username"`
	Password  string `json:"password"`
	Group     string `json:"group"`
	SecretKey string `json:"secret"`

	Initialized bool
	AppVersion  string

	HostWithPort string
	Scheme       string
	AuthPath     string

	MacAddress  string
	TunnelGroup string
	GroupAlias  string
	ConfigHash  string

	ComputerName    string
	DeviceType      string
	PlatformVersion string
	UniqueId        string
}

const (
	tplInit = iota
	tplAuthReply
)

func init() {
	reqHeaders["X-Transcend-Version"] = "1"
	reqHeaders["X-Aggregate-Auth"] = "1"

	Prof.Scheme = "https://"

	hostname, err := os.Hostname()
	if err != nil || hostname == "" {
		hostname = "Android"
	}
	Prof.ComputerName = hostname
	Prof.UniqueId = "android-bobrdobr-vpn"
	Prof.DeviceType = "Android"
	Prof.PlatformVersion = "14"
}

// ResetProfile clears the profile state before establishing a new tunnel
func ResetProfile() {
	Prof.Initialized = false
	Prof.AuthPath = ""
	Prof.TunnelGroup = ""
	Prof.GroupAlias = ""
	Prof.ConfigHash = ""
	Prof.SNI = ""
	Prof.Port = 0
	WebVpnCookie = ""
	if Conn != nil {
		_ = Conn.Close()
		Conn = nil
	}
	BufR = nil
}

// InitAuth initiates AnyConnect XML authentication handshake and determines AuthPath
func InitAuth(protectFn utils.ProtectSocketFn) error {
	WebVpnCookie = ""
	base.Info("Connecting to AnyConnect gateway:", Prof.HostWithPort, "SNI:", Prof.SNI)

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	serverName := Prof.SNI
	if serverName == "" {
		serverName = Prof.Host
	}

	var err error
	Conn, err = utils.DialBrowserTLS(ctx, Prof.HostWithPort, serverName, base.Cfg.InsecureSkipVerify, protectFn)
	if err != nil {
		return fmt.Errorf("failed to connect: %w", err)
	}

	base.Info("Connected to HTTPS on", Prof.HostWithPort, "(SNI:", serverName, ", uTLS Chrome Auto, ALPN: http/1.1)")
	BufR = bufio.NewReader(Conn)

	dtd := new(proto.DTD)
	Prof.AppVersion = base.Cfg.AgentVersion
	Prof.MacAddress = base.LocalInterface.Mac

	err = tplPost(tplInit, "", dtd)
	if err != nil {
		return err
	}

	Prof.AuthPath = dtd.Auth.Form.Action
	if Prof.AuthPath == "" {
		Prof.AuthPath = "/"
	}
	Prof.TunnelGroup = dtd.Opaque.TunnelGroup
	Prof.GroupAlias = dtd.Opaque.GroupAlias
	Prof.ConfigHash = dtd.Opaque.ConfigHash

	gps := len(dtd.Auth.Form.Groups)
	if gps != 0 && Prof.Group != "" && !utils.InArray(dtd.Auth.Form.Groups, Prof.Group) {
		return fmt.Errorf("available user groups are: %s", strings.Join(dtd.Auth.Form.Groups, " "))
	}

	return nil
}

// PasswordAuth submits credentials, resolves 2-step auth, and acquires the session token / webvpn cookie
func PasswordAuth() error {
	dtd := new(proto.DTD)
	err := tplPost(tplAuthReply, Prof.AuthPath, dtd)
	if err != nil {
		return err
	}

	// Two-step authentication compatibility
	if dtd.Type == "auth-request" && dtd.Auth.Error.Value == "" {
		dtd = new(proto.DTD)
		err = tplPost(tplAuthReply, Prof.AuthPath, dtd)
		if err != nil {
			return err
		}
	}

	// Authentication failure validation
	if dtd.Type == "auth-request" {
		if dtd.Auth.Error.Value != "" {
			return fmt.Errorf("authentication failed: %s", dtd.Auth.Error.Value)
		}
		if dtd.Auth.Message != "" {
			return fmt.Errorf("authentication failed: %s", dtd.Auth.Message)
		}
		return errors.New("authentication failed: invalid credentials supplied")
	}

	session.Sess.SessionToken = dtd.SessionToken
	if WebVpnCookie != "" {
		session.Sess.SessionToken = WebVpnCookie
	}
	base.Debug("SessionToken acquired:", session.Sess.SessionToken)
	return nil
}

func tplPost(typ int, path string, dtd *proto.DTD) error {
	tplBuffer := new(bytes.Buffer)
	if typ == tplInit {
		t, err := template.New("init").Parse(templateInit)
		if err != nil {
			return err
		}
		_ = t.Execute(tplBuffer, Prof)
	} else {
		t, err := template.New("auth_reply").Parse(templateAuthReply)
		if err != nil {
			return err
		}
		_ = t.Execute(tplBuffer, Prof)
	}

	if base.Cfg.LogLevel == "Debug" {
		post := tplBuffer.String()
		if typ == tplAuthReply {
			post = utils.RemoveBetween(post, "<auth>", "</auth>")
		}
		base.Debug(post)
	}

	reqUrl := fmt.Sprintf("%s%s%s", Prof.Scheme, Prof.HostWithPort, path)
	if Prof.SecretKey != "" {
		if strings.Contains(reqUrl, "?") {
			reqUrl += "&" + Prof.SecretKey
		} else {
			reqUrl += "?" + Prof.SecretKey
		}
	}

	req, err := http.NewRequest("POST", reqUrl, tplBuffer)
	if err != nil {
		return err
	}
	if Prof.SNI != "" {
		if Prof.Port != 0 && Prof.Port != 443 {
			req.Host = fmt.Sprintf("%s:%d", Prof.SNI, Prof.Port)
		} else {
			req.Host = Prof.SNI
		}
	}
	base.Info("POST", reqUrl, "Host:", req.Host)

	utils.SetCommonHeader(req)
	for k, v := range reqHeaders {
		req.Header[k] = []string{v}
	}

	if Conn == nil {
		return errors.New("connection is nil")
	}

	err = req.Write(Conn)
	if err != nil {
		_ = Conn.Close()
		Conn = nil
		return err
	}

	var resp *http.Response
	resp, err = http.ReadResponse(BufR, req)
	if err != nil {
		_ = Conn.Close()
		Conn = nil
		return err
	}
	defer resp.Body.Close()
	base.Info("Got HTTP response:", resp.Status)

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		_ = Conn.Close()
		Conn = nil
		return err
	}
	if base.Cfg.LogLevel == "Debug" {
		base.Debug(string(body))
	}

	if resp.StatusCode == http.StatusOK {
		err = xml.Unmarshal(body, dtd)
		if dtd.Type == "complete" && dtd.SessionToken == "" {
			// Compatibility with ocserv
			cookies := resp.Cookies()
			for _, c := range cookies {
				if c.Name == "webvpn" {
					WebVpnCookie = c.Value
					break
				}
			}
		}
		return err
	}

	_ = Conn.Close()
	Conn = nil
	return fmt.Errorf("auth error %s", resp.Status)
}

var templateInit = `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="init" aggregate-auth-version="2">
    <version who="vpn">{{.AppVersion}}</version>
    <device-id computer-name="{{.ComputerName}}" device-type="{{.DeviceType}}" platform-version="{{.PlatformVersion}}" unique-id="{{.UniqueId}}"></device-id>
</config-auth>`

var templateAuthReply = `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="auth-reply" aggregate-auth-version="2">
    <version who="vpn">{{.AppVersion}}</version>
    <device-id computer-name="{{.ComputerName}}" device-type="{{.DeviceType}}" platform-version="{{.PlatformVersion}}" unique-id="{{.UniqueId}}"></device-id>
    <opaque is-for="sg">
        <tunnel-group>{{.TunnelGroup}}</tunnel-group>
        <group-alias>{{.GroupAlias}}</group-alias>
        <config-hash>{{.ConfigHash}}</config-hash>
    </opaque>
    <mac-address-list>
        <mac-address public-interface="true">{{.MacAddress}}</mac-address>
    </mac-address-list>
    <auth>
        <username>{{.Username}}</username>
        <password>{{.Password}}</password>
    </auth>
    <group-select>{{.Group}}</group-select>
</config-auth>`
