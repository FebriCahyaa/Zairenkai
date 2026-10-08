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
pub const ZKFC_API_VERSION: u32 = (1u32 << 16) | (1u32 << 8);
pub const ZKFC_API_MIN_SUPPORTED: u32 = (1u32 << 16);
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
const DIR_WRITE: u32 = 1;

const fn ioc(dir: u32, ty: u32, nr: u32, size: u32) -> u64 {
    (((dir) << DIRSHIFT)
        | ((ty) << TYPESHIFT)
        | ((nr) << NRSHIFT)
        | ((size & ((1 << SIZEBITS) - 1)) << SIZESHIFT)) as u64
}
const fn ior(nr: u32, size: u32) -> u64 {
    ioc(DIR_READ, ZKFC_IOC_MAGIC, nr, size)
}
const fn iow(nr: u32, size: u32) -> u64 {
    ioc(DIR_WRITE, ZKFC_IOC_MAGIC, nr, size)
}

pub const ZKFC_CAP_TUNE_THERMAL: u64 = 1u64 << 3;

/// ZKFC task boost flags.
pub const ZKFC_TB_THREADS: u32 = 1 << 0;
pub const ZKFC_TB_INHERIT: u32 = 1 << 1;
pub const ZKFC_TB_RESET: u32 = 1 << 2;
pub const ZKFC_CAP_TUNE_PERF: u64 = 1u64 << 2;
pub const ZKFC_CQ_CLEAR: u32 = 1 << 0;

/// `struct zkfc_task_boost`.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct TaskBoost {
    pub pid: i32,
    pub uclamp_min: u32,
    pub uclamp_max: u32,
    pub flags: u32,
    pub applied: u32,
    pub reserved: u32,
}

/// `struct zkfc_cpufreq_qos`.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct CpufreqQos {
    pub cpu: u32,
    pub min_khz: u32,
    pub max_khz: u32,
    pub flags: u32,
}

/// `struct zkfc_input_boost`.
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct InputBoost {
    pub enabled: u32,
    pub duration_ms: u32,
    pub cluster_count: u32,
    pub reserved: u32,
    pub cluster_cpu: [u32; 8],
    pub min_khz: [u32; 8],
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
    pub fn api_compatible(&self) -> bool {
        self.api_major() == ((ZKFC_API_VERSION >> 16) & 0xff) as u8
            && self.api_min_supported <= ZKFC_API_VERSION
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
            9 => "api_incompatible",
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


/// `struct zkfc_license_payload` (136 bytes, little-endian wire layout).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct LicensePayload {
    pub magic: u32,
    pub format: u16,
    pub api_major: u16,
    pub license_id: u64,
    pub issued_at: u64,
    pub expires_at: u64,
    pub features: u32,
    pub flags: u32,
    pub binding: [u8; 32],
    pub licensee: [u8; 64],
}

/// `struct zkfc_license_token` (200 bytes).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct LicenseToken {
    pub payload: LicensePayload,
    pub signature: [u8; 64],
}

/// `struct zkfc_license_status` (288 bytes).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct LicenseStatus {
    pub state: u32,
    pub has_token: u32,
    pub owner_key_provisioned: u32,
    pub clock_trusted: u32,
    pub crl_serial: u64,
    pub owner_key_fingerprint: [u8; 32],
    pub kernel_binding: [u8; 32],
    pub token: LicenseToken,
}

fn cstr_lossy(buf: &[u8]) -> String {
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}

const IOC_GET_VERSION: u64 = ior(0x00, std::mem::size_of::<VersionInfo>() as u32);
const IOC_GET_LICENSE: u64 = ior(0x01, std::mem::size_of::<LicenseStatus>() as u32);
const IOC_TASK_BOOST: u64 = ioc(DIR_READ | DIR_WRITE, ZKFC_IOC_MAGIC, 0x10, std::mem::size_of::<TaskBoost>() as u32);
const IOC_CPUFREQ_QOS: u64 = iow(0x11, std::mem::size_of::<CpufreqQos>() as u32);
const IOC_INPUT_BOOST: u64 = iow(0x12, std::mem::size_of::<InputBoost>() as u32);

/// `struct zkfc_capability_info`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CapabilityInfo {
    pub kernel_caps: u64,
    pub runtime_caps: u64,
    pub kernel_major: u32,
    pub kernel_minor: u32,
    pub kernel_patch: u32,
    pub cpu_count: u32,
    pub page_size: u32,
    pub kernel_type: u32,
    pub hook_mode: u32,
    pub reserved: u32,
    pub kernel_release: [u8; 72],
    pub build_id: [u8; 48],
}

impl Default for CapabilityInfo {
    fn default() -> Self { unsafe { std::mem::zeroed() } }
}

impl CapabilityInfo {
    pub fn kernel_caps(&self) -> u64 { u64::from_le(self.kernel_caps) }
    pub fn runtime_caps(&self) -> u64 { u64::from_le(self.runtime_caps) }
    pub fn kernel_release(&self) -> String { cstr_lossy(&self.kernel_release) }
    pub fn build_id(&self) -> String { cstr_lossy(&self.build_id) }
}

const IOC_GET_CAPABILITIES: u64 = ior(0x05, std::mem::size_of::<CapabilityInfo>() as u32);

/// `struct zkfc_thermal_guard` (152 bytes).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ThermalGuard {
    pub enabled: u32,
    pub interval_ms: u32,
    pub limit_mdeg: i32,
    pub release_mdeg: i32,
    pub zone_count: u32,
    pub reserved: u32,
    pub zones: [[u8; 32]; 4],
}

impl Default for ThermalGuard {
    fn default() -> Self { unsafe { std::mem::zeroed() } }
}

const IOC_THERMAL_GUARD: u64 = iow(0x13, std::mem::size_of::<ThermalGuard>() as u32);

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

    /// `ZKFC_IOC_GET_CAPABILITIES`.
    pub fn capabilities(&self) -> io::Result<CapabilityInfo> {
        let mut c = CapabilityInfo::default();
        let rc = unsafe {
            libc::ioctl(self.fd, IOC_GET_CAPABILITIES as libc::c_ulong, &mut c as *mut CapabilityInfo)
        };
        if rc < 0 { return Err(io::Error::last_os_error()); }
        Ok(c)
    }

    pub fn license_status(&self) -> io::Result<LicenseStatus> {
        let mut status: LicenseStatus = unsafe { std::mem::zeroed() };
        let rc = unsafe {
            libc::ioctl(
                self.fd,
                IOC_GET_LICENSE as libc::c_ulong,
                &mut status as *mut LicenseStatus,
            )
        };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(status)
    }

    /// Convenience accessor used by mutation engines.
    pub fn license_state(&self) -> io::Result<u32> {
        Ok(self.license_status()?.state)
    }

    /// Apply a licensed per-task uclamp boost through ZKFC.
    ///
    /// The kernel owns task identity, inheritance and thermal suspension, so
    /// the daemon does not need to mutate per-thread scheduler attributes via
    /// ad-hoc sysfs writes.
    pub fn task_boost(&self, pid: i32, uclamp_min: u32, uclamp_max: u32, flags: u32) -> io::Result<u32> {
        if pid <= 0 || uclamp_min > 1024 || uclamp_max > 1024 || uclamp_min > uclamp_max
            || flags & !(ZKFC_TB_THREADS | ZKFC_TB_INHERIT | ZKFC_TB_RESET) != 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid ZKFC task boost request"));
        }
        let mut req = TaskBoost {
            pid,
            uclamp_min,
            uclamp_max,
            flags,
            applied: 0,
            reserved: 0,
        };
        let rc = unsafe { libc::ioctl(self.fd, IOC_TASK_BOOST as libc::c_ulong, &mut req as *mut TaskBoost) };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(req.applied)
    }

    /// Apply a transient cpufreq QoS constraint to one policy.
    pub fn cpufreq_qos(&self, cpu: u32, min_khz: u32, max_khz: u32, flags: u32) -> io::Result<()> {
        if min_khz != 0 && max_khz != 0 && min_khz > max_khz {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid cpufreq qos range"));
        }
        let req = CpufreqQos { cpu, min_khz, max_khz, flags };
        let rc = unsafe { libc::ioctl(self.fd, IOC_CPUFREQ_QOS as libc::c_ulong, &req as *const CpufreqQos) };
        if rc < 0 { return Err(io::Error::last_os_error()); }
        Ok(())
    }

    /// Configure the kernel input/touch boost fast path. Once configured,
    /// the kernel's input handler applies the per-cluster floors without a
    /// userspace poll loop on every touch event.
    pub fn input_boost(&self, duration_ms: u32, clusters: &[(u32, u32)]) -> io::Result<()> {
        if duration_ms < 10 || duration_ms > 5000 || clusters.len() > 8 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid ZKFC input boost request"));
        }
        let mut req = InputBoost::default();
        req.enabled = u32::from(!clusters.is_empty());
        req.duration_ms = duration_ms;
        req.cluster_count = clusters.len() as u32;
        for (i, &(cpu, min_khz)) in clusters.iter().take(8).enumerate() {
            req.cluster_cpu[i] = cpu;
            req.min_khz[i] = min_khz;
        }
        let rc = unsafe { libc::ioctl(self.fd, IOC_INPUT_BOOST as libc::c_ulong, &req as *const InputBoost) };
        if rc < 0 { return Err(io::Error::last_os_error()); }
        Ok(())
    }

    pub fn thermal_guard_config(&self, interval_ms: u32, limit_mdeg: i32, release_mdeg: i32, zones: &[String]) -> io::Result<()> {
        let mut cfg = ThermalGuard::default();
        cfg.enabled = 1;
        cfg.interval_ms = interval_ms;
        cfg.limit_mdeg = limit_mdeg;
        cfg.release_mdeg = release_mdeg;
        cfg.zone_count = zones.len().min(4) as u32;
        for (i, zone) in zones.iter().take(4).enumerate() {
            if zone.is_empty() || zone.len() >= 32 || zone.as_bytes().contains(&0) {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "thermal zone name is invalid"));
            }
            cfg.zones[i][..zone.len()].copy_from_slice(zone.as_bytes());
        }
        if cfg.zone_count == 0 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "thermal guard requires at least one zone"));
        }
        let rc = unsafe { libc::ioctl(self.fd, IOC_THERMAL_GUARD as libc::c_ulong, &cfg as *const ThermalGuard) };
        if rc < 0 { return Err(io::Error::last_os_error()); }
        Ok(())
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
    fn wire_struct_sizes_match_uapi() {
        assert_eq!(std::mem::size_of::<VersionInfo>(), 160);
        assert_eq!(std::mem::size_of::<LicensePayload>(), 136);
        assert_eq!(std::mem::size_of::<LicenseToken>(), 200);
        assert_eq!(std::mem::size_of::<LicenseStatus>(), 288);
        assert_eq!(std::mem::size_of::<CapabilityInfo>(), 168);
        assert_eq!(std::mem::size_of::<ThermalGuard>(), 152);
        assert_eq!(std::mem::size_of::<InputBoost>(), 80);
    }

    #[test]
    fn capability_ioctl_number_matches_uapi() {
        let expected: u64 = (2u64 << 30) | (168u64 << 16) | ((b'Z' as u64) << 8) | 5;
        assert_eq!(IOC_GET_CAPABILITIES, expected);
    }

    #[test]
    fn thermal_guard_ioctl_number_matches_uapi() {
        let expected: u64 = (1u64 << 30) | (152u64 << 16) | ((b'Z' as u64) << 8) | 0x13;
        assert_eq!(IOC_THERMAL_GUARD, expected);
    }

    #[test]
    fn performance_ioctl_numbers_match_uapi() {
        let task_expected: u64 = (3u64 << 30) | (24u64 << 16) | ((b'Z' as u64) << 8) | 0x10;
        let qos_expected: u64 = (1u64 << 30) | (16u64 << 16) | ((b'Z' as u64) << 8) | 0x11;
        let input_expected: u64 = (1u64 << 30) | (80u64 << 16) | ((b'Z' as u64) << 8) | 0x12;
        assert_eq!(IOC_TASK_BOOST, task_expected);
        assert_eq!(IOC_CPUFREQ_QOS, qos_expected);
        assert_eq!(IOC_INPUT_BOOST, input_expected);
    }

    #[test]
    fn license_ioctl_number_matches_uapi() {
        let expected: u64 = (2u64 << 30) | (288u64 << 16) | ((b'Z' as u64) << 8) | 1;
        assert_eq!(IOC_GET_LICENSE, expected);
    }
}

#[cfg(test)]
mod abi_tests {
    use super::{CpufreqQos, InputBoost, TaskBoost};
    use std::mem::size_of;

    #[test]
    fn performance_uapi_structs_match_kernel_layout() {
        assert_eq!(size_of::<TaskBoost>(), 24);
        assert_eq!(size_of::<CpufreqQos>(), 16);
        assert_eq!(size_of::<InputBoost>(), 80);
    }
}
