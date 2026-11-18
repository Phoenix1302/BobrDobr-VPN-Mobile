//! Thread-safe, lock-free telemetry tracking for BobrDobr VPN.

use crate::api::TunnelStats;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub struct TelemetryTracker {
    rx_bytes: AtomicU64,
    tx_bytes: AtomicU64,
    rx_packets: AtomicU64,
    tx_packets: AtomicU64,
    rx_speed_bps: AtomicU64,
    tx_speed_bps: AtomicU64,
    ping_ms: AtomicU32,
    last_handshake_timestamp: AtomicU64,

    // Internal speed calculation state
    last_calc_time_ms: AtomicU64,
    last_rx_snapshot: AtomicU64,
    last_tx_snapshot: AtomicU64,
}

impl Default for TelemetryTracker {
    fn default() -> Self {
        Self::new()
    }
}

impl TelemetryTracker {
    pub fn new() -> Self {
        let now = now_ms();
        Self {
            rx_bytes: AtomicU64::new(0),
            tx_bytes: AtomicU64::new(0),
            rx_packets: AtomicU64::new(0),
            tx_packets: AtomicU64::new(0),
            rx_speed_bps: AtomicU64::new(0),
            tx_speed_bps: AtomicU64::new(0),
            ping_ms: AtomicU32::new(0),
            last_handshake_timestamp: AtomicU64::new(0),
            last_calc_time_ms: AtomicU64::new(now),
            last_rx_snapshot: AtomicU64::new(0),
            last_tx_snapshot: AtomicU64::new(0),
        }
    }

    #[inline(always)]
    pub fn record_rx(&self, bytes: usize) {
        self.rx_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
        self.rx_packets.fetch_add(1, Ordering::Relaxed);
    }

    #[inline(always)]
    pub fn record_tx(&self, bytes: usize) {
        self.tx_bytes.fetch_add(bytes as u64, Ordering::Relaxed);
        self.tx_packets.fetch_add(1, Ordering::Relaxed);
    }

    pub fn set_ping(&self, ping: u32) {
        self.ping_ms.store(ping, Ordering::Relaxed);
    }

    pub fn record_handshake_success(&self, ping: u32) {
        self.ping_ms.store(ping, Ordering::Relaxed);
        self.last_handshake_timestamp.store(now_ms(), Ordering::Relaxed);
    }

    pub fn update_speeds(&self) {
        let now = now_ms();
        let prev_time = self.last_calc_time_ms.swap(now, Ordering::Relaxed);
        let dt_ms = now.saturating_sub(prev_time);

        if dt_ms >= 500 {
            let current_rx = self.rx_bytes.load(Ordering::Relaxed);
            let current_tx = self.tx_bytes.load(Ordering::Relaxed);

            let prev_rx = self.last_rx_snapshot.swap(current_rx, Ordering::Relaxed);
            let prev_tx = self.last_tx_snapshot.swap(current_tx, Ordering::Relaxed);

            let delta_rx = current_rx.saturating_sub(prev_rx);
            let delta_tx = current_tx.saturating_sub(prev_tx);

            // Speed in bits per second: delta_bytes * 8 * 1000 / dt_ms
            let rx_bps = (delta_rx * 8 * 1000) / dt_ms;
            let tx_bps = (delta_tx * 8 * 1000) / dt_ms;

            self.rx_speed_bps.store(rx_bps, Ordering::Relaxed);
            self.tx_speed_bps.store(tx_bps, Ordering::Relaxed);
        }
    }

    pub fn snapshot(&self) -> TunnelStats {
        TunnelStats {
            rx_bytes: self.rx_bytes.load(Ordering::Relaxed),
            tx_bytes: self.tx_bytes.load(Ordering::Relaxed),
            rx_packets: self.rx_packets.load(Ordering::Relaxed),
            tx_packets: self.tx_packets.load(Ordering::Relaxed),
            rx_speed_bps: self.rx_speed_bps.load(Ordering::Relaxed),
            tx_speed_bps: self.tx_speed_bps.load(Ordering::Relaxed),
            ping_ms: self.ping_ms.load(Ordering::Relaxed),
            _pad: 0,
            last_handshake_timestamp: self.last_handshake_timestamp.load(Ordering::Relaxed),
            timestamp: now_ms(),
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
