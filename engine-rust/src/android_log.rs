//! Android Logcat integration for BobrDobr VPN Rust Engine.

use std::sync::atomic::{AtomicBool, Ordering};

static LOGGING_ENABLED: AtomicBool = AtomicBool::new(true);

#[inline]
pub fn set_logging_enabled(enabled: bool) {
    LOGGING_ENABLED.store(enabled, Ordering::Relaxed);
}

#[inline]
pub fn should_log_debug() -> bool {
    LOGGING_ENABLED.load(Ordering::Relaxed)
}

#[allow(dead_code)]
pub const ANDROID_LOG_DEBUG: libc::c_int = 3;
#[allow(dead_code)]
pub const ANDROID_LOG_INFO: libc::c_int = 4;
#[allow(dead_code)]
pub const ANDROID_LOG_WARN: libc::c_int = 5;
#[allow(dead_code)]
pub const ANDROID_LOG_ERROR: libc::c_int = 6;

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    pub fn __android_log_print(
        prio: libc::c_int,
        tag: *const libc::c_char,
        fmt: *const libc::c_char,
        ...
    ) -> libc::c_int;
}

#[macro_export]
macro_rules! log_info {
    ($($arg:tt)*) => {{
        if $crate::android_log::should_log_debug() {
            let msg = format!($($arg)*);
        #[cfg(target_os = "android")]
        {
            let tag = b"BobrDobr_RustEngine\0";
            let fmt = b"%s\0";
            if let Ok(c_msg) = std::ffi::CString::new(msg) {
                unsafe {
                    $crate::android_log::__android_log_print(
                        $crate::android_log::ANDROID_LOG_INFO,
                        tag.as_ptr() as *const libc::c_char,
                        fmt.as_ptr() as *const libc::c_char,
                        c_msg.as_ptr(),
                    );
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            println!("[INFO] BobrDobr_RustEngine: {}", msg);
            }
        }
    }};
}

#[macro_export]
macro_rules! log_error {
    ($($arg:tt)*) => {{
        if $crate::android_log::should_log_debug() {
        let msg = format!($($arg)*);
        #[cfg(target_os = "android")]
        {
            let tag = b"BobrDobr_RustEngine\0";
            let fmt = b"%s\0";
            if let Ok(c_msg) = std::ffi::CString::new(msg) {
                unsafe {
                    $crate::android_log::__android_log_print(
                        $crate::android_log::ANDROID_LOG_ERROR,
                        tag.as_ptr() as *const libc::c_char,
                        fmt.as_ptr() as *const libc::c_char,
                        c_msg.as_ptr(),
                    );
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            eprintln!("[ERROR] BobrDobr_RustEngine: {}", msg);
        }
        }
    }};
}

#[macro_export]
macro_rules! log_warn {
    ($($arg:tt)*) => {{
        if $crate::android_log::should_log_debug() {
        let msg = format!($($arg)*);
        #[cfg(target_os = "android")]
        {
            let tag = b"BobrDobr_RustEngine\0";
            let fmt = b"%s\0";
            if let Ok(c_msg) = std::ffi::CString::new(msg) {
                unsafe {
                    $crate::android_log::__android_log_print(
                        $crate::android_log::ANDROID_LOG_WARN,
                        tag.as_ptr() as *const libc::c_char,
                        fmt.as_ptr() as *const libc::c_char,
                        c_msg.as_ptr(),
                    );
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            eprintln!("[WARN] BobrDobr_RustEngine: {}", msg);
        }
        }
    }};
}

#[macro_export]
macro_rules! log_debug {
    ($($arg:tt)*) => {{
        if $crate::android_log::should_log_debug() {
            let msg = format!($($arg)*);
        #[cfg(target_os = "android")]
        {
            let tag = b"BobrDobr_RustEngine\0";
            let fmt = b"%s\0";
            if let Ok(c_msg) = std::ffi::CString::new(msg) {
                unsafe {
                    $crate::android_log::__android_log_print(
                        $crate::android_log::ANDROID_LOG_DEBUG,
                        tag.as_ptr() as *const libc::c_char,
                        fmt.as_ptr() as *const libc::c_char,
                        c_msg.as_ptr(),
                    );
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            println!("[DEBUG] BobrDobr_RustEngine: {}", msg);
            }
        }
    }};
}
