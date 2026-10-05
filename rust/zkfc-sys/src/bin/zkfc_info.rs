// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Minimal example: print ZKFC version info as JSON.
//! Copyright (C) 2026 FebriCahyaa
use zkfc_sys::{errno_str, Zkfc};

fn main() {
    let z = match Zkfc::open() {
        Ok(z) => z,
        Err(e) => {
            println!(
                "{{\"ok\":false,\"error\":\"cannot open /dev/zkfc: {}\"}}",
                errno_str(&e)
            );
            std::process::exit(1);
        }
    };
    match z.version() {
        Ok(v) => println!(
            "{{\"ok\":true,\"api\":\"{}.{}.{}\",\"arch\":\"{}\",\"hook\":\"{}\",\"kernel_type\":\"{}\",\"kernel\":\"{}\",\"license\":\"{}\",\"build\":\"{}\"}}",
            v.api_major(), v.api_minor(), v.api_patch(), v.arch_name(), v.hook_name(),
            v.kernel_type_name(), v.kernel_release_str(), v.license_state_name(), v.build_id_str()
        ),
        Err(e) => {
            println!("{{\"ok\":false,\"error\":\"ioctl failed: {}\"}}", errno_str(&e));
            std::process::exit(1);
        }
    }
}
