//! Android TUN device read/write abstraction.

use crate::log_debug;
use std::io;
use std::os::raw::c_int;

pub struct TunDevice {
    fd: c_int,
}

impl TunDevice {
    /// Wraps an established Android TUN file descriptor.
    pub fn new(fd: c_int) -> Result<Self, io::Error> {
        if fd < 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Invalid TUN file descriptor"));
        }

        // Ensure non-blocking mode
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL, 0) };
        if flags >= 0 {
            unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
        }

        log_debug!("TunDevice initialized with fd={}", fd);
        Ok(Self { fd })
    }

    pub fn fd(&self) -> c_int {
        self.fd
    }

    #[inline(always)]
    pub fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        let n = unsafe {
            libc::read(
                self.fd,
                buf.as_mut_ptr() as *mut libc::c_void,
                buf.len(),
            )
        };

        if n >= 0 {
            Ok(n as usize)
        } else {
            Err(io::Error::last_os_error())
        }
    }

    #[inline(always)]
    pub fn write(&self, buf: &[u8]) -> io::Result<usize> {
        let n = unsafe {
            libc::write(
                self.fd,
                buf.as_ptr() as *const libc::c_void,
                buf.len(),
            )
        };

        if n >= 0 {
            Ok(n as usize)
        } else {
            Err(io::Error::last_os_error())
        }
    }
}
