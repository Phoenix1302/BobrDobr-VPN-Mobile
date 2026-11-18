//! Protected UDP Transport Socket with Android routing loop prevention.

use crate::api::*;
use crate::{log_debug, log_error, log_info};
use std::io;
use std::net::{SocketAddr, SocketAddrV4, SocketAddrV6};
use std::os::raw::c_int;

pub struct ProtectedUdpSocket {
    fd: c_int,
    endpoint: SocketAddr,
}

impl ProtectedUdpSocket {
    /// Creates, protects via Android callback, and connects a UDP socket.
    pub fn create_and_protect(
        endpoint: SocketAddr,
        protect_cb: ProtectCallbackFn,
    ) -> Result<Self, c_int> {
        let domain = match endpoint {
            SocketAddr::V4(_) => libc::AF_INET,
            SocketAddr::V6(_) => libc::AF_INET6,
        };

        // 1. Create UDP socket
        let fd = unsafe { libc::socket(domain, libc::SOCK_DGRAM, 0) };
        if fd < 0 {
            log_error!("Failed to create UDP socket: {}", io::Error::last_os_error());
            return Err(ENGINE_ERR_SOCKET_INIT);
        }

        // 2. CRITICAL: Invoke protect_callback immediately before connecting or sending
        if let Some(cb) = protect_cb {
            let protected = unsafe { cb(fd) };
            if !protected {
                log_error!("Socket protection callback failed for fd={}", fd);
                unsafe { libc::close(fd) };
                return Err(ENGINE_ERR_PROTECT_FAILED);
            }
            log_info!("Socket fd={} successfully protected via VpnService.protect()", fd);
        } else {
            log_error!("protect_callback_fn is NULL! Aborting to avoid VPN routing loops.");
            unsafe { libc::close(fd) };
            return Err(ENGINE_ERR_PROTECT_FAILED);
        }

        // 3. Set non-blocking
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL, 0) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            log_error!("Failed to set O_NONBLOCK on socket: {}", io::Error::last_os_error());
            unsafe { libc::close(fd) };
            return Err(ENGINE_ERR_SOCKET_INIT);
        }

        // 4. Optimize socket buffers (2 MB for smooth video and high throughput)
        let buf_size: libc::c_int = 2 * 1024 * 1024;
        unsafe {
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_RCVBUF,
                &buf_size as *const _ as *const libc::c_void,
                std::mem::size_of_val(&buf_size) as libc::socklen_t,
            );
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_SNDBUF,
                &buf_size as *const _ as *const libc::c_void,
                std::mem::size_of_val(&buf_size) as libc::socklen_t,
            );
        }

        // 5. Connect to endpoint
        let connect_res = match endpoint {
            SocketAddr::V4(v4) => Self::connect_v4(fd, &v4),
            SocketAddr::V6(v6) => Self::connect_v6(fd, &v6),
        };

        if let Err(e) = connect_res {
            log_error!("Failed to connect UDP socket to {}: {}", endpoint, e);
            unsafe { libc::close(fd) };
            return Err(ENGINE_ERR_SOCKET_INIT);
        }

        log_info!("UDP transport socket (fd={}) connected to {}", fd, endpoint);
        Ok(Self { fd, endpoint })
    }

    fn connect_v4(fd: c_int, addr: &SocketAddrV4) -> io::Result<()> {
        let mut sa: libc::sockaddr_in = unsafe { std::mem::zeroed() };
        sa.sin_family = libc::AF_INET as libc::sa_family_t;
        sa.sin_port = addr.port().to_be();
        sa.sin_addr.s_addr = u32::from_ne_bytes(addr.ip().octets());

        let res = unsafe {
            libc::connect(
                fd,
                &sa as *const _ as *const libc::sockaddr,
                std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t,
            )
        };

        if res == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    fn connect_v6(fd: c_int, addr: &SocketAddrV6) -> io::Result<()> {
        let mut sa: libc::sockaddr_in6 = unsafe { std::mem::zeroed() };
        sa.sin6_family = libc::AF_INET6 as libc::sa_family_t;
        sa.sin6_port = addr.port().to_be();
        sa.sin6_addr.s6_addr = addr.ip().octets();
        sa.sin6_scope_id = addr.scope_id();

        let res = unsafe {
            libc::connect(
                fd,
                &sa as *const _ as *const libc::sockaddr,
                std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
            )
        };

        if res == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }

    pub fn fd(&self) -> c_int {
        self.fd
    }

    pub fn endpoint(&self) -> SocketAddr {
        self.endpoint
    }

    /// Update network association (e.g. Wi-Fi <-> Cellular handoff)
    pub fn update_network(&self, net_id: i64) -> Result<(), c_int> {
        log_info!("Updating network binding for UDP socket fd={} to net_id={}", self.fd, net_id);
        if net_id == 0 {
            return Ok(());
        }

        // Try SO_MARK or android_setsocknetwork
        let net_id_32 = net_id as u32;
        let res = unsafe {
            libc::setsockopt(
                self.fd,
                libc::SOL_SOCKET,
                36, // SO_MARK on Linux
                &net_id_32 as *const _ as *const libc::c_void,
                std::mem::size_of_val(&net_id_32) as libc::socklen_t,
            )
        };

        if res < 0 {
            log_debug!("setsockopt SO_MARK returned: {}", io::Error::last_os_error());
        }
        Ok(())
    }

    #[inline(always)]
    pub fn send(&self, buf: &[u8]) -> io::Result<usize> {
        let n = unsafe {
            libc::send(
                self.fd,
                buf.as_ptr() as *const libc::c_void,
                buf.len(),
                0,
            )
        };
        if n >= 0 {
            Ok(n as usize)
        } else {
            Err(io::Error::last_os_error())
        }
    }

    #[inline(always)]
    pub fn recv(&self, buf: &mut [u8]) -> io::Result<usize> {
        let n = unsafe {
            libc::recv(
                self.fd,
                buf.as_mut_ptr() as *mut libc::c_void,
                buf.len(),
                0,
            )
        };
        if n >= 0 {
            Ok(n as usize)
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

impl Drop for ProtectedUdpSocket {
    fn drop(&mut self) {
        if self.fd >= 0 {
            log_debug!("Closing protected UDP socket fd={}", self.fd);
            unsafe { libc::close(self.fd) };
        }
    }
}
