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
        "AmneziaWG parameters: endpoint={}, Jc={}, Jmin={}, Jmax={}, S1={}, S2={}, S3={}, S4={}, H1={:?}, H2={:?}, H3={:?}, H4={:?}",
        config.endpoint, config.jc, config.jmin, config.jmax, config.s1, config.s2, config.s3, config.s4,
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
    use config::HeaderRange;
    use packet::*;

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
            "s3": 15,
            "s4": 25,
            "h1": 1024,
            "h2": "2000-3000",
            "h3": 3072,
            "h4": 4096,
            "i1": "custom_init_prefix",
            "i2": "custom_resp_prefix"
        }"#;

        let cfg = AwgConfig::parse(json).expect("Parse JSON failed");
        assert_eq!(cfg.endpoint, "198.51.100.1:51820");
        assert_eq!(cfg.jc, 4);
        assert_eq!(cfg.jmin, 50);
        assert_eq!(cfg.jmax, 80);
        assert_eq!(cfg.s1, 45);
        assert_eq!(cfg.s2, 60);
        assert_eq!(cfg.s3, 15);
        assert_eq!(cfg.s4, 25);
        assert_eq!(cfg.h1, HeaderRange::single(1024));
        assert_eq!(cfg.h2, HeaderRange::new(2000, 3000));
        assert!(cfg.h2.matches(2500));
        assert!(!cfg.h2.matches(1999));
        assert_eq!(cfg.h3, HeaderRange::single(3072));
        assert_eq!(cfg.h4, HeaderRange::single(4096));
        assert_eq!(cfg.i1.as_deref(), Some("custom_init_prefix"));
        assert_eq!(cfg.i2.as_deref(), Some("custom_resp_prefix"));
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
S3 = 10
S4 = 20
H1 = 1111
H2 = 2222-2250
H3 = 3333
H4 = 4444
I1 = ini_init_tag

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
        assert_eq!(cfg.s3, 10);
        assert_eq!(cfg.s4, 20);
        assert_eq!(cfg.h1, HeaderRange::single(1111));
        assert_eq!(cfg.h2, HeaderRange::new(2222, 2250));
        assert!(cfg.h2.matches(2230));
        assert_eq!(cfg.h3, HeaderRange::single(3333));
        assert_eq!(cfg.h4, HeaderRange::single(4444));
        assert_eq!(cfg.i1.as_deref(), Some("ini_init_tag"));

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
    fn test_subscription_server_config_and_mac1() {
        let server_json = r#"{
            "id": 1,
            "proto": "awg",
            "name": "Egor_awg1",
            "mode": "split",
            "ip": "10.8.0.2",
            "ipv6": "fd00:8:0::2",
            "endpoint": "176.108.243.97:443",
            "dns": "10.8.0.1",
            "allowed_ips": "0.0.0.0/0, ::/0",
            "obfuscation": {
                "Jc": 4, "Jmin": 40, "Jmax": 70,
                "S1": 15, "S2": 45,
                "H1": 1000, "H2": 2000, "H3": 3000, "H4": 4000
            },
            "config_text": "[Interface]\nAddress = 10.8.0.2/24\nPrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\n",
            "publicKey": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
        }"#;

        let cfg = AwgConfig::parse(server_json).expect("Failed to parse subscription server JSON");
        assert_eq!(cfg.endpoint, "176.108.243.97:443");
        assert_eq!(cfg.address, "10.8.0.2/24");
        assert_eq!(cfg.jc, 4);
        assert_eq!(cfg.jmin, 40);
        assert_eq!(cfg.jmax, 70);
        assert_eq!(cfg.s1, 15);
        assert_eq!(cfg.s2, 45);
        assert_eq!(cfg.h1, HeaderRange::single(1000));
        assert_eq!(cfg.h2, HeaderRange::single(2000));
        assert_eq!(cfg.h3, HeaderRange::single(3000));
        assert_eq!(cfg.h4, HeaderRange::single(4000));

        let mut session = state::SessionState::new(
            cfg.private_key,
            cfg.public_key,
            cfg.preshared_key,
        );

        let h1_val = cfg.h1.pick();
        assert_eq!(h1_val, 1000);
        let init = session.create_initiation(h1_val).expect("Handshake initiation failed");
        let serialized = init.serialize(h1_val, cfg.s1);

        // AmneziaWG S1 framing: 15 bytes of random noise at 0..15, then H1 at 15..19
        assert_eq!(serialized.len(), 148 + 15);
        assert_eq!(&serialized[15..19], &1000u32.to_le_bytes());
    }

    #[test]
    fn test_full_handshake_and_transport_roundtrip() {
        // 1. Generate keys for client and server
        let (client_priv, client_pub) = crypto::generate_ephemeral_keypair();
        let (server_priv, server_pub) = crypto::generate_ephemeral_keypair();
        let psk = Some([0x77u8; 32]);

        let h1_val = 1111u32;
        let h2_val = 2222u32;
        let h4_val = 4444u32;
        let s1 = 20usize;
        let s2 = 30usize;
        let s4 = 12usize;

        let h1_range = HeaderRange::single(h1_val);
        let h2_range = HeaderRange::single(h2_val);
        let h3_range = HeaderRange::single(3333);
        let h4_range = HeaderRange::single(h4_val);

        // 2. Client initiates handshake
        let mut client_session = state::SessionState::new(
            client_priv,
            server_pub,
            psk,
        );
        let init = client_session.create_initiation(h1_val).expect("Initiation failed");
        let init_bytes = init.serialize(h1_val, s1);

        assert_eq!(init_bytes.len(), s1 + INITIATION_SIZE);
        assert_eq!(&init_bytes[s1..s1 + 4], &h1_val.to_le_bytes());

        // 3. Server receives initiation bytes and classifies packet
        let ptype = parse_packet_type(&init_bytes, &h1_range, &h2_range, &h3_range, &h4_range, s1, s2, 0, s4);
        assert_eq!(ptype, PacketType::Initiation);

        let parsed_init = HandshakeInitiation::parse(&init_bytes, &h1_range, s1).expect("Parse init failed");
        assert_eq!(parsed_init.sender_index, client_session.local_index);

        // 4. Server generates response
        let server_local_index = 9999u32;
        let (resp, server_send_key, server_recv_key) = state::simulate_server_response(
            &server_priv,
            &client_pub,
            psk,
            h2_val,
            &parsed_init,
            server_local_index,
        ).expect("Server response failed");

        let resp_bytes = resp.serialize(h2_val, s2);
        assert_eq!(resp_bytes.len(), s2 + RESPONSE_SIZE);
        assert_eq!(&resp_bytes[s2..s2 + 4], &h2_val.to_le_bytes());

        // 5. Client receives response bytes and classifies packet
        let ptype_resp = parse_packet_type(&resp_bytes, &h1_range, &h2_range, &h3_range, &h4_range, s1, s2, 0, s4);
        assert_eq!(ptype_resp, PacketType::Response);

        let parsed_resp = HandshakeResponse::parse(&resp_bytes, &h2_range, s2).expect("Parse resp failed");
        let rtt = client_session.consume_response(&parsed_resp).expect("Client consume response failed");
        assert!(rtt < 5000);

        // 6. Verify derived transport keypairs match!
        let (client_send_key, client_recv_key, client_remote_index) = {
            let kp = client_session.current_keypair.as_ref().expect("Client keypair missing");
            (kp.send_key, kp.recv_key, kp.remote_index)
        };
        assert_eq!(client_send_key, server_recv_key);
        assert_eq!(client_recv_key, server_send_key);
        assert_eq!(client_remote_index, server_local_index);

        // 7. Test Transport Data: Client sends packet to Server with S4 prepended noise
        let mut client_udp_buf = vec![0u8; 1024];
        let original_payload = b"GET /vpn HTTP/1.1\r\nHost: bobrdobr.vpn\r\n\r\n";
        let counter = client_session.current_keypair.as_mut().unwrap().next_send_counter().unwrap();

        TransportHeader::write_header(&mut client_udp_buf, h4_val, client_remote_index, counter, s4);
        let payload_offset = s4 + 16;
        client_udp_buf[payload_offset..payload_offset + original_payload.len()].copy_from_slice(original_payload);

        let encrypted_len = crypto::aead_encrypt_in_place(
            &client_send_key,
            counter,
            &mut client_udp_buf[payload_offset..],
            original_payload.len(),
            &[],
        ).expect("Client transport encryption failed");
        let total_packet_len = payload_offset + encrypted_len;

        // Server checks packet type and parses header
        let ptype_trans = parse_packet_type(&client_udp_buf[..total_packet_len], &h1_range, &h2_range, &h3_range, &h4_range, s1, s2, 0, s4);
        assert_eq!(ptype_trans, PacketType::TransportData);

        let trans_header = TransportHeader::parse(&client_udp_buf[..total_packet_len], &h4_range, s4).expect("Parse trans header failed");
        assert_eq!(trans_header.receiver_index, server_local_index);
        assert_eq!(trans_header.counter, counter);

        // Server decrypts payload using server_recv_key
        let server_decrypted_len = crypto::aead_decrypt_in_place(
            &server_recv_key,
            trans_header.counter,
            &mut client_udp_buf[payload_offset..total_packet_len],
            encrypted_len,
            &[],
        ).expect("Server transport decrypt failed");

        assert_eq!(&client_udp_buf[payload_offset..payload_offset + server_decrypted_len], original_payload);

        // 8. Test Transport Data: Server sends IPv4 packet with padding, client trims padding
        let mut server_udp_buf = vec![0u8; 1024];
        let mut ipv4_packet = vec![0u8; 40]; // 20B IPv4 header + 20B payload
        ipv4_packet[0] = 0x45; // IPv4, header len 20
        ipv4_packet[2] = 0x00; // Total length = 40 (0x0028)
        ipv4_packet[3] = 40;
        ipv4_packet[20..40].copy_from_slice(b"12345678901234567890");

        // Simulate WireGuard padding: 40B IPv4 packet + 16B padding = 56B plaintext
        let mut padded_plaintext = ipv4_packet.clone();
        padded_plaintext.extend_from_slice(&[0u8; 16]);
        assert_eq!(padded_plaintext.len(), 56);

        TransportHeader::write_header(&mut server_udp_buf, h4_val, client_session.local_index, 0, s4);
        server_udp_buf[payload_offset..payload_offset + padded_plaintext.len()].copy_from_slice(&padded_plaintext);

        let srv_enc_len = crypto::aead_encrypt_in_place(
            &server_send_key,
            0,
            &mut server_udp_buf[payload_offset..],
            padded_plaintext.len(),
            &[],
        ).expect("Server transport encryption failed");
        let srv_packet_len = payload_offset + srv_enc_len;

        // Client decrypts
        let client_decrypted_len = crypto::aead_decrypt_in_place(
            &client_recv_key,
            0,
            &mut server_udp_buf[payload_offset..srv_packet_len],
            srv_enc_len,
            &[],
        ).expect("Client decrypt failed");
        assert_eq!(client_decrypted_len, 56);

        // IPv4 trim verification
        let slice = &server_udp_buf[payload_offset..payload_offset + client_decrypted_len];
        assert_eq!(slice[0] >> 4, 4);
        let actual_len = u16::from_be_bytes([slice[2], slice[3]]) as usize;
        assert_eq!(actual_len, 40); // Successfully trimmed 16 bytes of WireGuard padding!
        assert_eq!(&slice[..actual_len], ipv4_packet.as_slice());
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
