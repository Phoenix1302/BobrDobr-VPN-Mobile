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
        if self.socket_fd >= 0 {
            let net_id_32 = net_id as u32;
            let res = unsafe {
                libc::setsockopt(
                    self.socket_fd,
                    libc::SOL_SOCKET,
                    36, // SO_MARK
                    &net_id_32 as *const _ as *const libc::c_void,
                    std::mem::size_of_val(&net_id_32) as libc::socklen_t,
                )
            };
            if res < 0 {
                log_debug!("update_network setsockopt returned: {}", io::Error::last_os_error());
            }
        }
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
    log_info!("Zero-allocation packet loop started (tun_fd={}, udp_fd={})", tun_dev.fd(), udp_socket.fd());

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

    // Preallocated buffers for zero allocations during packet processing
    let mut tun_buf = [0u8; 65536];
    let mut udp_in_buf = [0u8; 65536];
    let mut udp_out_buf = [0u8; 65536];

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
                        let header_type = u32::from_le_bytes([
                            udp_in_buf[0], udp_in_buf[1], udp_in_buf[2], udp_in_buf[3]
                        ]);

                        let ptype = parse_packet_type(header_type, config.h1, config.h2, config.h3, config.h4);
                        match ptype {
                            PacketType::Response => {
                                handle_handshake_response(
                                    &mut session,
                                    &udp_in_buf[..n],
                                    &config,
                                    &telemetry,
                                    status_cb,
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
                                log_debug!("Received unknown packet type tag={}", tag);
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
                            match kp.next_send_counter() {
                                Ok(counter) => {
                                    TransportHeader::write_header(
                                        &mut udp_out_buf,
                                        config.h4,
                                        kp.remote_index,
                                        counter,
                                    );

                                    udp_out_buf[16..16 + n].copy_from_slice(&tun_buf[..n]);

                                    match aead_encrypt_in_place(
                                        &kp.send_key,
                                        counter,
                                        &mut udp_out_buf[16..],
                                        n,
                                        &[],
                                    ) {
                                        Ok(encrypted_len) => {
                                            let total_len = 16 + encrypted_len;
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
    notify_status(status_cb, STATUS_DISCONNECTED, "AmneziaWG tunnel disconnected");
    log_info!("Packet loop terminated cleanly");
}

fn send_handshake_initiation(
    session: &mut SessionState,
    socket: &ProtectedUdpSocket,
    config: &AwgConfig,
    telemetry: &TelemetryTracker,
) {
    match session.create_initiation() {
        Ok(init) => {
            let init_bytes = init.serialize(config.h1, config.s1);
            match socket.send(&init_bytes) {
                Ok(n) => {
                    telemetry.record_tx(n);
                    session.last_tx_time = current_timestamp_ms();
                    log_info!("Sent Handshake Initiation: {} bytes (H1={}, S1={})", n, config.h1, config.s1);
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
    buf: &mut [u8; 65536],
) {
    if let Some(ref mut kp) = session.current_keypair {
        if let Ok(counter) = kp.next_send_counter() {
            TransportHeader::write_header(buf, config.h4, kp.remote_index, counter);
            if let Ok(encrypted_len) = aead_encrypt_in_place(&kp.send_key, counter, &mut buf[16..], 0, &[]) {
                let total = 16 + encrypted_len;
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
) {
    match HandshakeResponse::parse(buf, config.h2) {
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
    if buf.len() < 32 {
        return; // Too short for header (16) + Poly1305 tag (16)
    }

    let header = match TransportHeader::parse(buf, config.h4) {
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
    let ciphertext_len = n - 16;
    match crate::crypto::aead_decrypt_in_place(
        &keypair.recv_key,
        header.counter,
        &mut buf[16..n],
        ciphertext_len,
        &[],
    ) {
        Ok(plaintext_len) => {
            session.last_rx_time = current_timestamp_ms();
            if plaintext_len == 0 {
                // Keepalive packet received
                log_debug!("Received Keepalive from peer");
            } else {
                // Raw IP packet decrypted! Write directly to Android TUN
                match tun_dev.write(&buf[16..16 + plaintext_len]) {
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
