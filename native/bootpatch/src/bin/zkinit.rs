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

    // The patcher only creates this wrapper after verifying the source generic
    // ramdisk has /init and moving it to /init.real. Never fall back to
    // /system/bin/init here: /system is commonly not mounted at PID 1 startup.
    if !Path::new("/init.real").is_file() {
        kmsg("fatal: preserved /init.real is missing; refusing an unsafe fallback");
        return 127;
    }

    // SAFETY: argv/envp are the original process-entry pointers. Execute the
    // preserved init directly; no unlink/symlink operation is needed.
    let error = unsafe { execve(cstr!("/init.real"), argv, envp) };
    kmsg(&format!("fatal: execve(/init.real) failed: {error}"));
    127
}
