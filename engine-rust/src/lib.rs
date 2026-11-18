//! BobrDobr VPN Mobile Client - High-Performance Rust Engine
//! Protocol: WireGuard & AmneziaWG with Zero-Allocation TUN Loop
//! Project: БобрДобр VPN ("Центр гидробиологических инноваций")

pub mod android_log;
pub mod api;
pub mod config;
pub mod crypto;
pub mod packet;
pub mod socket;
pub mod state;
pub mod telemetry;
pub mod tun;
pub mod tunnel;

use api::*;
use config::AwgConfig;
use std::ffi::CStr;
use std::os::raw::{c_char, c_int};
use std::sync::Mutex;
use tunnel::TunnelHandle;

static ACTIVE_TUNNEL: Mutex<Option<TunnelHandle>> = Mutex::new(None);

/// Starts the VPN tunnel using the provided TUN file descriptor and JSON configuration.
///
/// # Safety
/// `config_json` must be a valid, null-terminated UTF-8 string pointer.
/// `protect_cb` is synchronously invoked on the UDP socket before any packets are sent.
#[no_mangle]
pub unsafe extern "C" fn start_tunnel(
    tun_fd: c_int,
    config_json: *const c_char,
    protect_cb: ProtectCallbackFn,
    status_cb: StatusCallbackFn,
) -> c_int {
    log_info!("start_tunnel() invoked with tun_fd={}", tun_fd);

    if tun_fd < 0 || config_json.is_null() {
        log_error!("start_tunnel: Invalid arguments (tun_fd={}, config_json={:?})", tun_fd, config_json);
        notify_status(status_cb, STATUS_ERROR, "Invalid tunnel parameters");
        return ENGINE_ERR_INVALID_ARG;
    }

    if protect_cb.is_none() {
        log_error!("start_tunnel: protect_cb is NULL! Android socket protection required.");
        notify_status(status_cb, STATUS_ERROR, "Socket protector callback required");
        return ENGINE_ERR_PROTECT_FAILED;
    }

    let mut guard = match ACTIVE_TUNNEL.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref handle) = *guard {
        if handle.is_running() {
            log_warn!("start_tunnel: Tunnel is already running");
            return ENGINE_ERR_ALREADY_RUNNING;
        }
    }

    let config_str = match CStr::from_ptr(config_json).to_str() {
        Ok(s) => s,
        Err(e) => {
            log_error!("start_tunnel: Invalid UTF-8 in config_json: {}", e);
            notify_status(status_cb, STATUS_ERROR, "Invalid config string encoding");
            return ENGINE_ERR_INVALID_CONFIG;
        }
    };

    let config = match AwgConfig::parse(config_str) {
        Ok(cfg) => cfg,
        Err(e) => {
            log_error!("start_tunnel: Configuration parse failed: {}", e);
            notify_status(status_cb, STATUS_ERROR, &format!("Config error: {}", e));
            return ENGINE_ERR_INVALID_CONFIG;
        }
    };

    log_info!(
        "AmneziaWG parameters: endpoint={}, Jc={}, Jmin={}, Jmax={}, S1={}, S2={}, H1={}, H2={}, H3={}, H4={}",
        config.endpoint, config.jc, config.jmin, config.jmax, config.s1, config.s2,
        config.h1, config.h2, config.h3, config.h4
    );

    match tunnel::spawn_tunnel(tun_fd, config, protect_cb, status_cb) {
        Ok(handle) => {
            *guard = Some(handle);
            log_info!("Tunnel spawned and initialized successfully");
            ENGINE_SUCCESS
        }
        Err(err_code) => {
            log_error!("Failed to spawn tunnel: code={}", err_code);
            err_code
        }
    }
}

/// Gracefully shuts down the VPN tunnel and releases all socket and worker resources.
#[no_mangle]
pub unsafe extern "C" fn stop_tunnel() -> c_int {
    log_info!("stop_tunnel() invoked");

    let mut guard = match ACTIVE_TUNNEL.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(mut handle) = guard.take() {
        let _ = handle.stop();
        log_info!("stop_tunnel: Tunnel shutdown complete");
        ENGINE_SUCCESS
    } else {
        log_debug!("stop_tunnel: No tunnel was active");
        ENGINE_SUCCESS
    }
}

/// Updates underlying network binding (Wi-Fi <-> Cellular handoff) without recreating TUN.
#[no_mangle]
pub unsafe extern "C" fn update_network(net_id: i64) -> c_int {
    log_info!("update_network() invoked with net_id={}", net_id);

    let guard = match ACTIVE_TUNNEL.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref handle) = *guard {
        let _ = handle.update_network(net_id);
    }
    ENGINE_SUCCESS
}

/// Populates real-time telemetry counters and operational metrics.
///
/// # Safety
/// `stats` pointer must be non-null and point to valid memory for `TunnelStats`.
#[no_mangle]
pub unsafe extern "C" fn get_tunnel_stats(stats: *mut TunnelStats) -> c_int {
    if stats.is_null() {
        return ENGINE_ERR_INVALID_ARG;
    }

    let guard = match ACTIVE_TUNNEL.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref handle) = *guard {
        *stats = handle.telemetry().snapshot();
    } else {
        *stats = TunnelStats::default();
    }

    ENGINE_SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tunnel_stats_layout() {
        assert_eq!(std::mem::size_of::<TunnelStats>(), 72);
    }

    #[test]
    fn test_config_parsing_json() {
        let json = r#"{
            "private_key": "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "public_key": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
            "endpoint": "198.51.100.1:51820",
            "jc": 4,
            "jmin": 50,
            "jmax": 80,
            "s1": 45,
            "s2": 60,
            "h1": 1024,
            "h2": 2048,
            "h3": 3072,
            "h4": 4096
        }"#;

        let cfg = AwgConfig::parse(json).expect("Parse JSON failed");
        assert_eq!(cfg.endpoint, "198.51.100.1:51820");
        assert_eq!(cfg.jc, 4);
        assert_eq!(cfg.jmin, 50);
        assert_eq!(cfg.jmax, 80);
        assert_eq!(cfg.s1, 45);
        assert_eq!(cfg.s2, 60);
        assert_eq!(cfg.h1, 1024);
        assert_eq!(cfg.h2, 2048);
        assert_eq!(cfg.h3, 3072);
        assert_eq!(cfg.h4, 4096);
    }

    #[test]
    fn test_config_parsing_ini() {
        let ini = r#"
[Interface]
PrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
Address = 10.8.0.2/24, fd00:8:0::2/64
DNS = 1.1.1.1, 8.8.8.8
Jc = 3
Jmin = 40
Jmax = 70
S1 = 15
S2 = 25
H1 = 1111
H2 = 2222
H3 = 3333
H4 = 4444

[Peer]
PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
Endpoint = 203.0.113.10:51820
AllowedIPs = 0.0.0.0/0, ::/0
"#;

        let cfg = AwgConfig::parse(ini).expect("Parse INI failed");
        assert_eq!(cfg.endpoint, "203.0.113.10:51820");
        assert_eq!(cfg.address, "10.8.0.2/24, fd00:8:0::2/64");
        assert_eq!(cfg.jc, 3);
        assert_eq!(cfg.jmin, 40);
        assert_eq!(cfg.jmax, 70);
        assert_eq!(cfg.s1, 15);
        assert_eq!(cfg.s2, 25);
        assert_eq!(cfg.h1, 1111);
        assert_eq!(cfg.h2, 2222);
        assert_eq!(cfg.h3, 3333);
        assert_eq!(cfg.h4, 4444);

        let resolved = cfg.resolve_endpoint().expect("Resolve endpoint failed");
        assert_eq!(resolved.to_string(), "203.0.113.10:51820");
    }

    #[test]
    fn test_resolve_endpoint_direct_ip() {
        let mut cfg = AwgConfig::default();
        cfg.endpoint = "176.108.243.97:443".to_string();
        let addr = cfg.resolve_endpoint().expect("Failed direct parse");
        assert_eq!(addr.to_string(), "176.108.243.97:443");

        cfg.endpoint = "10.0.0.1".to_string();
        let addr2 = cfg.resolve_endpoint().expect("Failed default port fallback");
        assert_eq!(addr2.to_string(), "10.0.0.1:51820");
    }

    #[test]
    fn test_crypto_roundtrip() {
        let key = [42u8; 32];
        let counter = 1;
        let plaintext = b"Hello BobrDobr VPN Android!";
        let ad = b"AmneziaWG";

        let ciphertext = crypto::aead_encrypt(&key, counter, plaintext, ad)
            .expect("Encryption failed");

        let decrypted = crypto::aead_decrypt(&key, counter, &ciphertext, ad)
            .expect("Decryption failed");

        assert_eq!(plaintext.as_slice(), decrypted.as_slice());
    }

    #[test]
    fn test_replay_window() {
        let mut window = state::ReplayWindow::new();
        assert!(window.check_and_update(1));
        assert!(window.check_and_update(2));
        assert!(!window.check_and_update(1)); // Duplicate!
        assert!(window.check_and_update(100));
        assert!(window.check_and_update(50));
        assert!(!window.check_and_update(50)); // Duplicate!
    }
}
