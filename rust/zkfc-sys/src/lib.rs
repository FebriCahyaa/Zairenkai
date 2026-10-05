// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Rust FFI bindings to the Zairenkai Kernel Framework Core (`/dev/zkfc`).
//!
//! Built on the [`libc`](https://github.com/rust-lang/libc) crate for the raw
//! `open`/`ioctl`/`close` syscalls. This mirrors the C `libzkfc`, so Rust
//! tooling (and future Rust components) can talk to ZKFC through the exact
//! same stable UAPI in `kernel/include/uapi/linux/zkfc.h`.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::ffi::CStr;
use std::io;
use std::os::unix::io::{AsRawFd, RawFd};

pub const ZKFC_DEVICE_PATH: &str = "/dev/zkfc";
const ZKFC_IOC_MAGIC: u32 = b'Z' as u32;

// asm-generic ioctl encoding (arm64 / x86_64 / riscv64).
const NRBITS: u32 = 8;
const TYPEBITS: u32 = 8;
const SIZEBITS: u32 = 14;
const NRSHIFT: u32 = 0;
const TYPESHIFT: u32 = NRSHIFT + NRBITS;
const SIZESHIFT: u32 = TYPESHIFT + TYPEBITS;
const DIRSHIFT: u32 = SIZESHIFT + SIZEBITS;
const DIR_READ: u32 = 2;

const fn ioc(dir: u32, ty: u32, nr: u32, size: u32) -> u64 {
    (((dir) << DIRSHIFT)
        | ((ty) << TYPESHIFT)
        | ((nr) << NRSHIFT)
        | ((size & ((1 << SIZEBITS) - 1)) << SIZESHIFT)) as u64
}
const fn ior(nr: u32, size: u32) -> u64 {
    ioc(DIR_READ, ZKFC_IOC_MAGIC, nr, size)
}

/// `struct zkfc_version_info` (160 bytes, fixed layout).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct VersionInfo {
    pub api_version: u32,
    pub api_min_supported: u32,
    pub arch: u32,
    pub hook_mode: u32,
    pub kernel_type: u32,
    pub features: u32,
    pub features_enabled: u32,
    pub license_state: u32,
    pub kernel_version: u32,
    pub reserved: u32,
    pub build_id: [u8; 48],
    pub kernel_release: [u8; 72],
}

impl Default for VersionInfo {
    fn default() -> Self {
        // Safe: all-zero is a valid value for a plain-data struct.
        unsafe { std::mem::zeroed() }
    }
}

impl VersionInfo {
    pub fn api_major(&self) -> u8 {
        ((self.api_version >> 16) & 0xff) as u8
    }
    pub fn api_minor(&self) -> u8 {
        ((self.api_version >> 8) & 0xff) as u8
    }
    pub fn api_patch(&self) -> u8 {
        (self.api_version & 0xff) as u8
    }
    pub fn arch_name(&self) -> &'static str {
        match self.arch {
            1 => "arm64",
            2 => "x86_64",
            3 => "riscv64",
            _ => "unknown",
        }
    }
    pub fn hook_name(&self) -> &'static str {
        match self.hook_mode {
            1 => "hybrid",
            2 => "manual",
            _ => "none",
        }
    }
    pub fn kernel_type_name(&self) -> &'static str {
        if self.kernel_type == 1 { "gki" } else { "non-gki" }
    }
    pub fn license_state_name(&self) -> &'static str {
        match self.license_state {
            1 => "valid",
            2 => "bad_signature",
            3 => "expired",
            4 => "wrong_binding",
            5 => "api_outdated",
            6 => "malformed",
            7 => "no_owner_key",
            8 => "revoked",
            _ => "missing",
        }
    }
    pub fn build_id_str(&self) -> String {
        cstr_lossy(&self.build_id)
    }
    pub fn kernel_release_str(&self) -> String {
        cstr_lossy(&self.kernel_release)
    }
}

fn cstr_lossy(buf: &[u8]) -> String {
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}

const IOC_GET_VERSION: u64 = ior(0x00, std::mem::size_of::<VersionInfo>() as u32);

/// An open handle to `/dev/zkfc`.
pub struct Zkfc {
    fd: RawFd,
}

impl Zkfc {
    /// Open `/dev/zkfc` (requires root and the ZKFC module loaded).
    pub fn open() -> io::Result<Self> {
        let path = c"/dev/zkfc";
        // SAFETY: path is a valid NUL-terminated C string.
        let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(Zkfc { fd })
    }

    /// `ZKFC_IOC_GET_VERSION`.
    pub fn version(&self) -> io::Result<VersionInfo> {
        let mut v = VersionInfo::default();
        // SAFETY: fd is valid; &mut v points at a correctly sized buffer.
        let rc = unsafe {
            libc::ioctl(self.fd, IOC_GET_VERSION as libc::c_ulong, &mut v as *mut VersionInfo)
        };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(v)
    }
}

impl AsRawFd for Zkfc {
    fn as_raw_fd(&self) -> RawFd {
        self.fd
    }
}

impl Drop for Zkfc {
    fn drop(&mut self) {
        if self.fd >= 0 {
            // SAFETY: fd was opened by us and is not used after this.
            unsafe { libc::close(self.fd) };
        }
    }
}

/// Resolve a C error number to a readable message (used by the example).
pub fn errno_str(err: &io::Error) -> String {
    match err.raw_os_error() {
        Some(e) => {
            // SAFETY: strerror returns a static NUL-terminated string.
            let s = unsafe { CStr::from_ptr(libc::strerror(e)) };
            format!("{} ({})", s.to_string_lossy(), e)
        }
        None => err.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ioctl_number_matches_uapi() {
        // _IOR('Z', 0x00, sizeof(VersionInfo=160))
        // dir=2<<30 | size=160<<16 | 'Z'<<8 | 0
        let expected: u64 = (2u64 << 30) | (160u64 << 16) | ((b'Z' as u64) << 8);
        assert_eq!(IOC_GET_VERSION, expected);
    }

    #[test]
    fn version_struct_is_160_bytes() {
        assert_eq!(std::mem::size_of::<VersionInfo>(), 160);
    }
}
