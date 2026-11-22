//! High-performance Zero-Allocation TUN Packet Loop for AmneziaWG / WireGuard.

use crate::api::*;
use crate::config::AwgConfig;
use crate::crypto::aead_encrypt_in_place;
use crate::packet::*;
use crate::socket::ProtectedUdpSocket;
use crate::state::{current_timestamp_ms, SessionState};
use crate::telemetry::TelemetryTracker;
use crate::tun::TunDevice;
use crate::{log_debug, log_error, log_info, log_warn};
use std::io;
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;

pub struct TunnelHandle {
    shutdown_write_fd: c_int,
    worker_handle: Option<JoinHandle<()>>,
    telemetry: Arc<TelemetryTracker>,
    is_running: Arc<AtomicBool>,
    #[allow(dead_code)]
    socket_fd: c_int,
}

impl TunnelHandle {
    pub fn is_running(&self) -> bool {
        self.is_running.load(Ordering::SeqCst)
    }

    pub fn telemetry(&self) -> Arc<TelemetryTracker> {
        self.telemetry.clone()
    }

    pub fn update_network(&self, net_id: i64) -> Result<(), c_int> {
        log_debug!("update_network: net_id={}", net_id);
        // Outer socket protection and routing are managed by Android VpnService.protect().
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), c_int> {
        if !self.is_running.swap(false, Ordering::SeqCst) {
            return Ok(());
        }

        log_info!("Initiating tunnel shutdown...");
        // Signal worker thread via shutdown pipe
        if self.shutdown_write_fd >= 0 {
            let byte = [1u8];
            unsafe {
                libc::write(
                    self.shutdown_write_fd,
                    byte.as_ptr() as *const libc::c_void,
                    1,
                )
            };
        }

        if let Some(handle) = self.worker_handle.take() {
            let _ = handle.join();
        }

        if self.shutdown_write_fd >= 0 {
            unsafe { libc::close(self.shutdown_write_fd) };
            self.shutdown_write_fd = -1;
        }

        log_info!("Tunnel worker thread joined successfully");
        Ok(())
    }
}

pub fn spawn_tunnel(
    tun_fd: c_int,
    config: AwgConfig,
    protect_cb: ProtectCallbackFn,
    status_cb: StatusCallbackFn,
) -> Result<TunnelHandle, c_int> {
    log_info!("Starting AmneziaWG tunnel: endpoint={}", config.endpoint);
    notify_status(status_cb, STATUS_CONNECTING, "Establishing AmneziaWG connection...");

    let endpoint_addr = config.resolve_endpoint().map_err(|e| {
        log_error!("Endpoint resolution failed: {}", e);
        notify_status(status_cb, STATUS_ERROR, &format!("DNS resolution error: {}", e));
        ENGINE_ERR_INVALID_CONFIG
    })?;

    // Create and protect UDP transport socket immediately
    let udp_socket = ProtectedUdpSocket::create_and_protect(endpoint_addr, protect_cb)
        .map_err(|err_code| {
            notify_status(status_cb, STATUS_ERROR, "Socket protection or initialization failed");
            err_code
        })?;

    let socket_raw_fd = udp_socket.fd();

    // Initialize TUN abstraction
    let tun_dev = TunDevice::new(tun_fd).map_err(|e| {
        log_error!("Failed to wrap TUN fd={}: {}", tun_fd, e);
        notify_status(status_cb, STATUS_ERROR, "Invalid TUN device");
        ENGINE_ERR_GENERIC
    })?;

    // Create shutdown pipe
    let mut pipe_fds: [c_int; 2] = [-1, -1];
    if unsafe { libc::pipe(pipe_fds.as_mut_ptr()) } < 0 {
        log_error!("Failed to create shutdown pipe: {}", io::Error::last_os_error());
        return Err(ENGINE_ERR_GENERIC);
    }

    let shutdown_read_fd = pipe_fds[0];
    let shutdown_write_fd = pipe_fds[1];

    let telemetry = Arc::new(TelemetryTracker::new());
    let is_running = Arc::new(AtomicBool::new(true));

    let t_telemetry = telemetry.clone();
    let t_is_running = is_running.clone();

    let worker_handle = std::thread::Builder::new()
        .name("bobrdobr-tun-loop".to_string())
        .spawn(move || {
            run_packet_loop(
                tun_dev,
                udp_socket,
                shutdown_read_fd,
                config,
                status_cb,
                t_telemetry,
                t_is_running,
            );
        })
        .map_err(|e| {
            log_error!("Failed to spawn tunnel worker thread: {}", e);
            unsafe {
                libc::close(shutdown_read_fd);
                libc::close(shutdown_write_fd);
            }
            ENGINE_ERR_GENERIC
        })?;

    Ok(TunnelHandle {
        shutdown_write_fd,
        worker_handle: Some(worker_handle),
        telemetry,
        is_running,
        socket_fd: socket_raw_fd,
    })
}

fn run_packet_loop(
    tun_dev: TunDevice,
    udp_socket: ProtectedUdpSocket,
    shutdown_read_fd: c_int,
    config: AwgConfig,
    status_cb: StatusCallbackFn,
    telemetry: Arc<TelemetryTracker>,
    is_running: Arc<AtomicBool>,
) {
    log_info!("Packet loop started (tun_fd={}, udp_fd={})", tun_dev.fd(), udp_socket.fd());

    let mut session = SessionState::new(
        config.private_key,
        config.public_key,
        config.preshared_key,
    );

    // Step 1: Send AmneziaWG junk packets if configured (Jc > 0)
    if config.jc > 0 {
        log_info!("Sending {} AmneziaWG junk packets (range {}-{} bytes)", config.jc, config.jmin, config.jmax);
        for _ in 0..config.jc {
            let junk = create_junk_packet(config.jmin, config.jmax);
            if let Ok(n) = udp_socket.send(&junk) {
                telemetry.record_tx(n);
            }
        }
    }

    // Step 2: Send initial Handshake Initiation (with H1 header and S1 padding)
    send_handshake_initiation(&mut session, &udp_socket, &config, &telemetry);

    // Preallocated heap buffers to avoid thread stack overflow and runtime reallocations
    let mut tun_buf = vec![0u8; 65536];
    let mut udp_in_buf = vec![0u8; 65536];
    let mut udp_out_buf = vec![0u8; 65536];

    let mut poll_fds = [
        libc::pollfd { fd: shutdown_read_fd, events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: tun_dev.fd(), events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: udp_socket.fd(), events: libc::POLLIN, revents: 0 },
    ];

    let mut last_speed_check = current_timestamp_ms();

    while is_running.load(Ordering::Relaxed) {
        // Poll with 100ms timeout for responsive keepalives and rekeying
        let poll_res = unsafe { libc::poll(poll_fds.as_mut_ptr(), 3, 100) };

        if poll_res < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            log_error!("Poll syscall failed: {}", err);
            break;
        }

        let now = current_timestamp_ms();

        // Check shutdown event
        if poll_fds[0].revents & libc::POLLIN != 0 {
            log_info!("Shutdown event received in packet loop");
            break;
        }

        // Periodic maintenance: speeds and timers
        if now.saturating_sub(last_speed_check) >= 500 {
            telemetry.update_speeds();
            last_speed_check = now;
        }

        // Handshake rekeying check
        if session.should_rekey() {
            log_info!("Rekeying AmneziaWG session...");
            if config.jc > 0 {
                for _ in 0..config.jc {
                    let junk = create_junk_packet(config.jmin, config.jmax);
                    if let Ok(n) = udp_socket.send(&junk) {
                        telemetry.record_tx(n);
                    }
                }
            }
            send_handshake_initiation(&mut session, &udp_socket, &config, &telemetry);
        }

        // Keepalive check (WireGuard sends 0-byte payload encrypted)
        if config.keepalive_secs > 0 && session.current_keypair.is_some() {
            let elapsed_secs = (now.saturating_sub(session.last_tx_time)) / 1000;
            if elapsed_secs >= config.keepalive_secs {
                send_keepalive(&mut session, &udp_socket, &config, &telemetry, &mut udp_out_buf);
            }
        }

        // Handle UDP incoming packets (decrypt -> write to TUN)
        if poll_fds[2].revents & libc::POLLIN != 0 {
            loop {
                match udp_socket.recv(&mut udp_in_buf) {
                    Ok(n) if n >= 4 => {
                        let ptype = parse_packet_type(
                            &udp_in_buf[..n],
                            &config.h1,
                            &config.h2,
                            &config.h3,
                            &config.h4,
                            config.s1,
                            config.s2,
                            config.s3,
                            config.s4,
                        );
                        match ptype {
                            PacketType::Response => {
                                handle_handshake_response(
                                    &mut session,
                                    &udp_in_buf[..n],
                                    &config,
                                    &telemetry,
                                    status_cb,
                                    &udp_socket,
                                    &mut udp_out_buf,
                                );
                            }
                            PacketType::TransportData => {
                                handle_transport_data(
                                    &mut session,
                                    &tun_dev,
                                    &mut udp_in_buf[..n],
                                    &config,
                                    &telemetry,
                                );
                            }
                            PacketType::CookieReply => {
                                log_debug!("Received Cookie Reply ({} bytes)", n);
                            }
                            PacketType::Initiation => {
                                log_debug!("Ignoring peer initiation in client mode");
                            }
                            PacketType::Unknown(tag) => {
                                log_debug!("Received unknown packet type tag={} (len={})", tag, n);
                            }
                        }
                    }
                    Ok(_) => {
                        // Packet shorter than 4 bytes, ignore
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                        break; // Socket drained
                    }
                    Err(e) => {
                        log_warn!("UDP recv error: {}", e);
                        break;
                    }
                }
            }
        }

        // Handle TUN incoming packets (read from TUN -> encrypt -> send to UDP)
        if poll_fds[1].revents & libc::POLLIN != 0 {
            loop {
                match tun_dev.read(&mut tun_buf) {
                    Ok(n) if n > 0 => {
                        if let Some(ref mut kp) = session.current_keypair {
                            if kp.is_expired(now) {
                                log_debug!("Keypair expired (>180s), waiting for rekey handshake before sending TUN data (dropped {} bytes)", n);
                                continue;
                            }
                            match kp.next_send_counter() {
                                Ok(counter) => {
                                    let h4_val = config.h4.pick();
                                    TransportHeader::write_header(
                                        &mut udp_out_buf,
                                        h4_val,
                                        kp.remote_index,
                                        counter,
                                        config.s4,
                                    );

                                    let payload_offset = config.s4 + 16;
                                    udp_out_buf[payload_offset..payload_offset + n].copy_from_slice(&tun_buf[..n]);

                                    match aead_encrypt_in_place(
                                        &kp.send_key,
                                        counter,
                                        &mut udp_out_buf[payload_offset..],
                                        n,
                                        &[],
                                    ) {
                                        Ok(encrypted_len) => {
                                            let total_len = payload_offset + encrypted_len;
                                            if let Ok(sent_bytes) = udp_socket.send(&udp_out_buf[..total_len]) {
                                                telemetry.record_tx(sent_bytes);
                                                session.last_tx_time = current_timestamp_ms();
                                            }
                                        }
                                        Err(e) => {
                                            log_error!("Failed to encrypt TUN packet: {}", e);
                                        }
                                    }
                                }
                                Err(e) => {
                                    log_warn!("Transport counter expired: {}", e);
                                }
                            }
                        } else {
                            // Handshake not finished yet, packet dropped
                            log_debug!("Dropped {} bytes from TUN (waiting for handshake)", n);
                        }
                    }
                    Ok(_) => {}
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                        break; // TUN drained
                    }
                    Err(e) => {
                        log_warn!("TUN read error: {}", e);
                        break;
                    }
                }
            }
        }
    }

    unsafe { libc::close(shutdown_read_fd) };
    let was_running = is_running.swap(false, Ordering::SeqCst);
    if was_running {
        // Only notify if not already stopped via intentional stop()
        notify_status(status_cb, STATUS_DISCONNECTED, "AmneziaWG tunnel disconnected");
    }
    log_info!("Packet loop terminated cleanly");
}

fn send_handshake_initiation(
    session: &mut SessionState,
    socket: &ProtectedUdpSocket,
    config: &AwgConfig,
    telemetry: &TelemetryTracker,
) {
    let h1_val = config.h1.pick();
    match session.create_initiation(h1_val) {
        Ok(init) => {
            let init_bytes = init.serialize(h1_val, config.s1);
            match socket.send(&init_bytes) {
                Ok(n) => {
                    telemetry.record_tx(n);
                    session.last_tx_time = current_timestamp_ms();
                    log_info!("Sent Handshake Initiation: {} bytes (H1={}, S1={})", n, h1_val, config.s1);
                }
                Err(e) => {
                    log_error!("Failed to send Handshake Initiation: {}", e);
                }
            }
        }
        Err(e) => {
            log_error!("Failed to build Handshake Initiation: {}", e);
        }
    }
}

fn send_keepalive(
    session: &mut SessionState,
    socket: &ProtectedUdpSocket,
    config: &AwgConfig,
    telemetry: &TelemetryTracker,
    buf: &mut [u8],
) {
    if let Some(ref mut kp) = session.current_keypair {
        if let Ok(counter) = kp.next_send_counter() {
            let h4_val = config.h4.pick();
            TransportHeader::write_header(buf, h4_val, kp.remote_index, counter, config.s4);
            let payload_offset = config.s4 + 16;
            if let Ok(encrypted_len) = aead_encrypt_in_place(&kp.send_key, counter, &mut buf[payload_offset..], 0, &[]) {
                let total = payload_offset + encrypted_len;
                if let Ok(n) = socket.send(&buf[..total]) {
                    telemetry.record_tx(n);
                    session.last_tx_time = current_timestamp_ms();
                    log_debug!("Sent Keepalive ({} bytes)", n);
                }
            }
        }
    }
}

fn handle_handshake_response(
    session: &mut SessionState,
    buf: &[u8],
    config: &AwgConfig,
    telemetry: &TelemetryTracker,
    status_cb: StatusCallbackFn,
    udp_socket: &ProtectedUdpSocket,
    udp_out_buf: &mut [u8],
) {
    match HandshakeResponse::parse(buf, &config.h2, config.s2) {
        Ok(resp) => {
            match session.consume_response(&resp) {
                Ok(rtt_ms) => {
                    telemetry.record_handshake_success(rtt_ms);
                    session.last_rx_time = current_timestamp_ms();
                    log_info!(
                        "AmneziaWG Handshake completed! RTT = {} ms, local_index={}, remote_index={}",
                        rtt_ms, resp.receiver_index, resp.sender_index
                    );
                    notify_status(status_cb, STATUS_CONNECTED, "Connected to AmneziaWG tunnel");

                    // Immediately confirm the new session to the server by sending a keepalive transport packet
                    // (WireGuard Specification Section 5.4.6 & BoringTun).
                    // This explicitly signals the server to promote next_keypair to current_keypair
                    // and retire the previous session index, preventing packet stalls upon reconnect.
                    send_keepalive(session, udp_socket, config, telemetry, udp_out_buf);
                }
                Err(e) => {
                    log_error!("Failed to process Handshake Response: {}", e);
                }
            }
        }
        Err(e) => {
            log_error!("Malformed Handshake Response: {}", e);
        }
    }
}

fn handle_transport_data(
    session: &mut SessionState,
    tun_dev: &TunDevice,
    buf: &mut [u8],
    config: &AwgConfig,
    telemetry: &TelemetryTracker,
) {
    let min_len = config.s4 + 16 + 16;
    if buf.len() < min_len {
        return; // Too short for header + Poly1305 tag
    }

    let header = match TransportHeader::parse(buf, &config.h4, config.s4) {
        Ok(h) => h,
        Err(e) => {
            log_debug!("Transport header error: {}", e);
            return;
        }
    };

    let n = buf.len();

    // Check matching keypair
    let keypair = match session.current_keypair {
        Some(ref mut kp) if kp.local_index == header.receiver_index => kp,
        _ => match session.previous_keypair {
            Some(ref mut kp) if kp.local_index == header.receiver_index => kp,
            _ => {
                log_debug!("Unknown receiver_index in transport packet: {}", header.receiver_index);
                return;
            }
        },
    };

    // Replay attack validation
    if !keypair.replay_window.check_and_update(header.counter) {
        log_warn!("Dropped replayed or out-of-order transport packet: counter={}", header.counter);
        return;
    }

    // Decrypt payload in-place
    let payload_offset = config.s4 + 16;
    let ciphertext_len = n - payload_offset;

    match crate::crypto::aead_decrypt_in_place(
        &keypair.recv_key,
        header.counter,
        &mut buf[payload_offset..n],
        ciphertext_len,
        &[],
    ) {
        Ok(plaintext_len) => {
            session.last_rx_time = current_timestamp_ms();
            if plaintext_len == 0 {
                // Keepalive packet received
                log_debug!("Received Keepalive from peer");
            } else {
                // Raw IP packet decrypted!
                // Trim WireGuard 16B padding according to IPv4 / IPv6 header length fields
                let ip_slice = &buf[payload_offset..payload_offset + plaintext_len];
                let actual_len = if plaintext_len >= 20 && (ip_slice[0] >> 4) == 4 {
                    let total_len = u16::from_be_bytes([ip_slice[2], ip_slice[3]]) as usize;
                    if total_len > 0 && total_len <= plaintext_len {
                        total_len
                    } else {
                        plaintext_len
                    }
                } else if plaintext_len >= 40 && (ip_slice[0] >> 4) == 6 {
                    let payload_len = u16::from_be_bytes([ip_slice[4], ip_slice[5]]) as usize;
                    let total_len = 40 + payload_len;
                    if total_len > 0 && total_len <= plaintext_len {
                        total_len
                    } else {
                        plaintext_len
                    }
                } else {
                    plaintext_len
                };

                match tun_dev.write(&ip_slice[..actual_len]) {
                    Ok(written) => {
                        telemetry.record_rx(written);
                    }
                    Err(e) => {
                        log_error!("Failed to write to TUN device: {}", e);
                    }
                }
            }
        }
        Err(e) => {
            log_error!("Packet authentication failed (Poly1305 MAC invalid): {}", e);
        }
    }
}
