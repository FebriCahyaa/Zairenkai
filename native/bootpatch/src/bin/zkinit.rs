// SPDX-License-Identifier: GPL-3.0-only
//
// Zairenkai init adapter, intentionally isolated from the proprietary Android
// UI. This follows the small init-chainloading pattern used by KernelSU-Next's
// GPL-3.0 ksuinit: attempt to load the bundled LKM, then execute the original
// Android init so a module-load failure does not deliberately halt boot.
// Copyright (C) 2026 FebriCahyaa
// Derived in part from KernelSU-Next userspace/ksuinit entrypoint.

#![no_main]

use rustix::{cstr, runtime::execve, system::init_module};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::symlink;
use std::path::Path;

fn kmsg(message: &str) {
    if let Ok(mut file) = OpenOptions::new().write(true).open("/dev/kmsg") {
        let _ = writeln!(file, "<6>zairenkai-init: {message}");
    }
}

fn load_zkfc() {
    match fs::read("/zkfc.ko") {
        Ok(module) => match init_module(&module, cstr!("")) {
            Ok(()) => kmsg("zkfc.ko loaded"),
            Err(error) => kmsg(&format!("could not load zkfc.ko: {error}")),
        },
        Err(error) => kmsg(&format!("could not read /zkfc.ko: {error}")),
    }
}

/// # Safety
/// Linux invokes this exported C entry point as PID 1. The argv and envp
/// pointers are provided by the process loader and must be passed to execve
/// unchanged; they must not be retained after this function returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn main(_argc: i32, argv: *const *const u8, envp: *const *const u8) -> i32 {
    load_zkfc();

    // The original /init was moved to /init.real by the boot-image patcher.
    // If the source image did not contain an init entry, use Android's normal
    // init path instead. Never write to a boot partition from this program.
    let original_init = if Path::new("/init.real").exists() {
        "/init.real"
    } else if Path::new("/system/bin/init").exists() {
        "/system/bin/init"
    } else {
        kmsg("fatal: neither /init.real nor /system/bin/init exists");
        return 127;
    };

    let _ = fs::remove_file("/init");
    if let Err(error) = symlink(original_init, "/init") {
        kmsg(&format!("could not restore /init symlink: {error}"));
        // Try the original path directly if the symlink cannot be created.
        let direct_path = if original_init == "/init.real" {
            cstr!("/init.real")
        } else {
            cstr!("/system/bin/init")
        };
        // SAFETY: argv/envp came from the kernel's process entry point.
        let error = unsafe { execve(direct_path, argv, envp) };
        kmsg(&format!("fatal: execve of original init failed: {error}"));
        return 127;
        return 127;
    }

    // SAFETY: argv/envp came from the kernel's process entry point and are
    // passed directly to execve without changing their lifetime.
    let error = unsafe { execve(cstr!("/init"), argv, envp) };
    kmsg(&format!("execve(/init) failed: {error}; trying original init directly"));
    let direct_path = if original_init == "/init.real" {
        cstr!("/init.real")
    } else {
        cstr!("/system/bin/init")
    };
    // SAFETY: same argv/envp process-entry pointers as above.
    let fallback_error = unsafe { execve(direct_path, argv, envp) };
    kmsg(&format!("fatal: original init exec failed: {fallback_error}"));
    127
    127
}
