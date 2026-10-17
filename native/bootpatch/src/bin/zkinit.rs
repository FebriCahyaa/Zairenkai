// SPDX-License-Identifier: GPL-3.0-only
//
// Deprecated compatibility adapter for images patched by older Zairenkai
// builds. The current patcher never installs or packages this executable.
// Crucially, this legacy path must never load zkfc.ko as PID 1: module loading
// is deferred by the current patcher and should only happen after Android boot.
// Copyright (C) 2026 FebriCahyaa
// Derived in part from KernelSU-Next userspace/ksuinit entrypoint.

#![no_main]

use rustix::{cstr, runtime::execve};
use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;

fn kmsg(message: &str) {
    if let Ok(mut file) = OpenOptions::new().write(true).open("/dev/kmsg") {
        let _ = writeln!(file, "<6>zairenkai-init: {message}");
    }
}

/// # Safety
/// Linux invokes this exported C entry point as PID 1. The argv and envp
/// pointers are provided by the process loader and must be passed to execve
/// unchanged; they must not be retained after this function returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn main(_argc: i32, argv: *const *const u8, envp: *const *const u8) -> i32 {
    kmsg("deprecated PID-1 adapter active; module autoload intentionally disabled");

    // Legacy-only compatibility path. The current patcher never creates this
    // wrapper and never replaces /init; keep this chainloader inert in case an
    // older patched image still contains it.
    // The patcher only created this wrapper after verifying the source generic
    // ramdisk has /init and moving it to /init.real. Never fall back to
    // /system/bin/init here: /system is commonly not mounted at PID 1 startup.
    if !Path::new("/init.real").is_file() {
        kmsg("fatal: preserved /init.real is missing; refusing an unsafe fallback");
        return 127;
    }

    // SAFETY: argv/envp are the original process-entry pointers. Execute the
    // preserved init directly; no module insertion occurs in this legacy path.
    let error = unsafe { execve(cstr!("/init.real"), argv, envp) };
    kmsg(&format!("fatal: execve(/init.real) failed: {error}"));
    127
}
