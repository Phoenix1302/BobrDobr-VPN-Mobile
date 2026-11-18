//! C-ABI types and constants corresponding strictly to engine_api.h

use std::os::raw::{c_char, c_int};

pub const STATUS_DISCONNECTED: c_int = 0;
pub const STATUS_CONNECTING: c_int = 1;
pub const STATUS_CONNECTED: c_int = 2;
pub const STATUS_DISCONNECTING: c_int = 3;
pub const STATUS_RECONNECTING: c_int = 4;
pub const STATUS_ERROR: c_int = 5;

pub const ENGINE_SUCCESS: c_int = 0;
pub const ENGINE_ERR_GENERIC: c_int = -1;
pub const ENGINE_ERR_INVALID_ARG: c_int = -2;
pub const ENGINE_ERR_PROTECT_FAILED: c_int = -3;
pub const ENGINE_ERR_SOCKET_INIT: c_int = -4;
pub const ENGINE_ERR_HANDSHAKE: c_int = -5;
pub const ENGINE_ERR_INVALID_CONFIG: c_int = -6;
pub const ENGINE_ERR_ALREADY_RUNNING: c_int = -7;
pub const ENGINE_ERR_NOT_RUNNING: c_int = -8;

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct TunnelStats {
    pub rx_bytes: u64,
    pub tx_bytes: u64,
    pub rx_packets: u64,
    pub tx_packets: u64,
    pub rx_speed_bps: u64,
    pub tx_speed_bps: u64,
    pub ping_ms: u32,
    pub _pad: u32,
    pub last_handshake_timestamp: u64,
    pub timestamp: u64,
}

const _: () = {
    assert!(std::mem::size_of::<TunnelStats>() == 72);
};

pub type ProtectCallbackFn = Option<unsafe extern "C" fn(socket_fd: c_int) -> bool>;
pub type StatusCallbackFn = Option<unsafe extern "C" fn(status_code: c_int, message: *const c_char)>;
pub type StatsCallbackFn = Option<unsafe extern "C" fn(rx_bytes: u64, tx_bytes: u64)>;

pub fn notify_status(callback: StatusCallbackFn, code: c_int, message: &str) {
    if let Some(cb) = callback {
        if let Ok(c_msg) = std::ffi::CString::new(message) {
            unsafe {
                cb(code, c_msg.as_ptr());
            }
        }
    }
}
