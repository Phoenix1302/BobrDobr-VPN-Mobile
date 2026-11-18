//! Configuration parser for WireGuard & AmneziaWG.
//! Supports Remnawave / Windows Client JSON format, nested configs, and WireGuard INI format.

use base64::prelude::*;
use serde::Deserialize;
use std::net::SocketAddr;

#[derive(Debug, Clone)]
pub struct AwgConfig {
    pub private_key: [u8; 32],
    pub public_key: [u8; 32],
    pub preshared_key: Option<[u8; 32]>,
    pub endpoint: String,
    pub address: String,
    pub mtu: usize,
    pub keepalive_secs: u64,
    // AmneziaWG obfuscation parameters
    pub jc: usize,
    pub jmin: usize,
    pub jmax: usize,
    pub s1: usize,
    pub s2: usize,
    pub h1: u32,
    pub h2: u32,
    pub h3: u32,
    pub h4: u32,
}

impl Default for AwgConfig {
    fn default() -> Self {
        Self {
            private_key: [0u8; 32],
            public_key: [0u8; 32],
            preshared_key: None,
            endpoint: String::new(),
            address: "10.8.0.2/32".to_string(),
            mtu: 1360,
            keepalive_secs: 25,
            jc: 0,
            jmin: 40,
            jmax: 70,
            s1: 0,
            s2: 0,
            h1: 1,
            h2: 2,
            h3: 3,
            h4: 4,
        }
    }
}

#[derive(Debug, Deserialize, Default)]
struct RawJsonConfig {
    #[serde(alias = "privateKey", alias = "PrivateKey")]
    private_key: Option<String>,
    #[serde(alias = "publicKey", alias = "PublicKey")]
    public_key: Option<String>,
    #[serde(alias = "presharedKey", alias = "PresharedKey")]
    preshared_key: Option<String>,
    #[serde(alias = "server", alias = "serverAddress")]
    endpoint: Option<String>,
    #[serde(alias = "serverPort", alias = "port")]
    endpoint_port: Option<u16>,
    address: Option<String>,
    #[allow(dead_code)]
    #[serde(alias = "allowedIps", alias = "allowed_ips")]
    allowed_ips: Option<String>,
    mtu: Option<usize>,
    #[serde(alias = "persistentKeepalive", alias = "persistent_keepalive", alias = "keepalive")]
    keepalive: Option<u64>,

    // AmneziaWG obfuscation parameters
    #[serde(alias = "Jc", alias = "JC")]
    jc: Option<usize>,
    #[serde(alias = "Jmin", alias = "JMIN")]
    jmin: Option<usize>,
    #[serde(alias = "Jmax", alias = "JMAX")]
    jmax: Option<usize>,
    #[serde(alias = "S1")]
    s1: Option<usize>,
    #[serde(alias = "S2")]
    s2: Option<usize>,
    #[serde(alias = "H1")]
    h1: Option<u32>,
    #[serde(alias = "H2")]
    h2: Option<u32>,
    #[serde(alias = "H3")]
    h3: Option<u32>,
    #[serde(alias = "H4")]
    h4: Option<u32>,

    // Nested configurations
    #[serde(alias = "amneziaConfig", alias = "amnezia_config")]
    amnezia_config: Option<Box<RawJsonConfig>>,
    #[serde(alias = "rawConfigJson")]
    raw_config_json: Option<String>,
    #[serde(alias = "configContent", alias = "config_content", alias = "config_text", alias = "configText")]
    config_content: Option<String>,
}

fn decode_key(s: &str) -> Result<[u8; 32], String> {
    let s = s.trim();
    if s.is_empty() {
        return Err("Empty key string".to_string());
    }

    if let Ok(bytes) = BASE64_STANDARD.decode(s) {
        if bytes.len() == 32 {
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&bytes);
            return Ok(arr);
        }
    }

    if let Ok(bytes) = base64::engine::general_purpose::STANDARD_NO_PAD.decode(s) {
        if bytes.len() == 32 {
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&bytes);
            return Ok(arr);
        }
    }

    // Try hex decoding fallback
    if s.len() == 64 {
        let mut arr = [0u8; 32];
        for i in 0..32 {
            if let Ok(b) = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16) {
                arr[i] = b;
            } else {
                return Err("Failed to parse hex key".to_string());
            }
        }
        return Ok(arr);
    }

    Err(format!("Invalid key length ({} bytes), expected 32 bytes base64", s.len()))
}

impl AwgConfig {
    pub fn parse(input: &str) -> Result<Self, String> {
        let trimmed = input.trim();
        if trimmed.is_empty() {
            return Err("Configuration string is empty".to_string());
        }

        // Try JSON parsing first
        if trimmed.starts_with('{') {
            if let Ok(raw) = serde_json::from_str::<RawJsonConfig>(trimmed) {
                return Self::from_raw(raw);
            }
        }

        // Try INI parsing
        Self::parse_ini(trimmed)
    }

    fn from_raw(raw: RawJsonConfig) -> Result<Self, String> {
        // If nested inside amnezia_config, prioritize nested
        if let Some(nested) = raw.amnezia_config {
            return Self::from_raw(*nested);
        }

        // If nested inside rawConfigJson or configContent, parse that
        if let Some(ref raw_json) = raw.raw_config_json {
            if !raw_json.trim().is_empty() && raw_json.trim() != "{}" {
                if let Ok(cfg) = Self::parse(raw_json) {
                    return Ok(cfg);
                }
            }
        }

        if let Some(ref content) = raw.config_content {
            if !content.trim().is_empty() {
                return Self::parse(content);
            }
        }

        let mut config = Self::default();

        let priv_key_str = raw.private_key
            .ok_or_else(|| "Missing required 'private_key' field".to_string())?;
        config.private_key = decode_key(&priv_key_str)?;

        let pub_key_str = raw.public_key
            .ok_or_else(|| "Missing required 'public_key' field".to_string())?;
        config.public_key = decode_key(&pub_key_str)?;

        if let Some(ref psk_str) = raw.preshared_key {
            if !psk_str.trim().is_empty() {
                config.preshared_key = Some(decode_key(psk_str)?);
            }
        }

        // Construct endpoint
        if let Some(endpoint) = raw.endpoint {
            let mut ep = endpoint.trim().to_string();
            if let Some(port) = raw.endpoint_port {
                if !ep.contains(':') {
                    ep = format!("{}:{}", ep, port);
                }
            }
            config.endpoint = ep;
        } else if let Some(port) = raw.endpoint_port {
            config.endpoint = format!("127.0.0.1:{}", port);
        }

        if let Some(addr) = raw.address {
            config.address = addr;
        }

        if let Some(mtu) = raw.mtu {
            if (576..=9000).contains(&mtu) {
                config.mtu = mtu;
            }
        }

        if let Some(ka) = raw.keepalive {
            config.keepalive_secs = ka;
        }

        // Obfuscation
        if let Some(jc) = raw.jc { config.jc = jc; }
        if let Some(jmin) = raw.jmin { config.jmin = jmin; }
        if let Some(jmax) = raw.jmax { config.jmax = jmax; }
        if let Some(s1) = raw.s1 { config.s1 = s1; }
        if let Some(s2) = raw.s2 { config.s2 = s2; }
        if let Some(h1) = raw.h1 { if h1 > 0 { config.h1 = h1; } }
        if let Some(h2) = raw.h2 { if h2 > 0 { config.h2 = h2; } }
        if let Some(h3) = raw.h3 { if h3 > 0 { config.h3 = h3; } }
        if let Some(h4) = raw.h4 { if h4 > 0 { config.h4 = h4; } }

        if config.jmin > config.jmax {
            std::mem::swap(&mut config.jmin, &mut config.jmax);
        }

        Ok(config)
    }

    fn parse_ini(ini_text: &str) -> Result<Self, String> {
        let mut config = Self::default();
        let mut current_section = "";

        let mut priv_key: Option<[u8; 32]> = None;
        let mut pub_key: Option<[u8; 32]> = None;

        for line in ini_text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
                continue;
            }

            if line.starts_with('[') && line.ends_with(']') {
                current_section = &line[1..line.len() - 1];
                continue;
            }

            let parts: Vec<&str> = line.splitn(2, '=').map(|s| s.trim()).collect();
            if parts.len() != 2 {
                continue;
            }

            let key = parts[0];
            let val = parts[1];

            let sec_lower = current_section.to_ascii_lowercase();
            let key_lower = key.to_ascii_lowercase();
            // Strip trailing inline comments if any
            let val_clean = val.split('#').next().unwrap_or("").split(';').next().unwrap_or("").trim();

            match (sec_lower.as_str(), key_lower.as_str()) {
                ("interface", "privatekey") => priv_key = Some(decode_key(val_clean)?),
                ("interface", "address") => {
                    if config.address.is_empty() || config.address == "10.8.0.2/32" {
                        config.address = val_clean.to_string();
                    } else {
                        config.address = format!("{}, {}", config.address, val_clean);
                    }
                }
                ("interface", "dns") => {
                    // DNS handled by Android VpnService, safely ignored
                }
                ("interface", "mtu") => if let Ok(m) = val_clean.parse() { config.mtu = m; },
                ("interface", "jc") => if let Ok(v) = val_clean.parse() { config.jc = v; },
                ("interface", "jmin") => if let Ok(v) = val_clean.parse() { config.jmin = v; },
                ("interface", "jmax") => if let Ok(v) = val_clean.parse() { config.jmax = v; },
                ("interface", "s1") => if let Ok(v) = val_clean.parse() { config.s1 = v; },
                ("interface", "s2") => if let Ok(v) = val_clean.parse() { config.s2 = v; },
                ("interface", "h1") => if let Ok(v) = val_clean.parse() { if v > 0 { config.h1 = v; } },
                ("interface", "h2") => if let Ok(v) = val_clean.parse() { if v > 0 { config.h2 = v; } },
                ("interface", "h3") => if let Ok(v) = val_clean.parse() { if v > 0 { config.h3 = v; } },
                ("interface", "h4") => if let Ok(v) = val_clean.parse() { if v > 0 { config.h4 = v; } },

                ("peer", "publickey") => pub_key = Some(decode_key(val_clean)?),
                ("peer", "presharedkey") => config.preshared_key = Some(decode_key(val_clean)?),
                ("peer", "endpoint") => config.endpoint = val_clean.to_string(),
                ("peer", "allowedips") => {
                    // AllowedIPs handled by Android VpnService routing table, safely ignored
                }
                ("peer", "persistentkeepalive") => if let Ok(v) = val_clean.parse() { config.keepalive_secs = v; },
                _ => {}
            }
        }

        config.private_key = priv_key.ok_or_else(|| "Missing PrivateKey in INI".to_string())?;
        config.public_key = pub_key.ok_or_else(|| "Missing PublicKey in INI".to_string())?;

        if config.jmin > config.jmax {
            std::mem::swap(&mut config.jmin, &mut config.jmax);
        }

        Ok(config)
    }

    pub fn resolve_endpoint(&self) -> Result<SocketAddr, String> {
        use std::net::ToSocketAddrs;
        let ep = self.endpoint.trim().trim_matches('"');
        if ep.is_empty() {
            return Err("Tunnel endpoint address is empty".to_string());
        }

        // Direct parse as SocketAddr (e.g. 176.108.243.97:443 or [2001:db8::1]:51820)
        // Bypasses DNS query which can fail/timeout if TUN is already established!
        if let Ok(addr) = ep.parse::<SocketAddr>() {
            return Ok(addr);
        }

        // Direct parse as IP address with default wireguard port 51820
        if let Ok(ip) = ep.parse::<std::net::IpAddr>() {
            return Ok(SocketAddr::new(ip, 51820));
        }

        let mut addrs = ep.to_socket_addrs()
            .map_err(|e| format!("Failed to resolve endpoint '{}': {}", ep, e))?;

        addrs.next().ok_or_else(|| format!("Could not resolve address for '{}'", ep))
    }
}
