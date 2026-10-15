// SPDX-License-Identifier: GPL-3.0-only
//
// Standalone Zairenkai boot-image patch backend.
// The image parsing, ramdisk decompression and repacking are provided by
// android-bootimg, the GPL-3.0 library used by KernelSU-Next's boot patch path.
// This utility deliberately writes an output image only; it never flashes a
// block device. See ../NOTICE.md and LICENSE for provenance and terms.

use android_bootimg::cpio::{Cpio, CpioEntry};
use android_bootimg::parser::{BootImage, BootImageVersion, RamdiskImage};
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, anyhow, bail, ensure};
use regex_lite::Regex;
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Write};
use std::path::{Path, PathBuf};

const MAX_BOOT_IMAGE: u64 = 256 * 1024 * 1024;
const MAX_MODULE: u64 = 64 * 1024 * 1024;
const MAX_INIT: u64 = 32 * 1024 * 1024;
const MODULE_ENTRY: &str = "zkfc.ko";
const INIT_ENTRY: &str = "init";
const ORIGINAL_INIT_ENTRY: &str = "init.real";
const SUPPORTED_KMIS: &[&str] = &[
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
];

#[derive(Default)]
struct Args {
    boot: Option<PathBuf>,
    module: Option<PathBuf>,
    init: Option<PathBuf>,
    kmi: Option<String>,
    out: Option<PathBuf>,
    detect_kmi: bool,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("- Patch Error: {error:#}");
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    let args = parse_args()?;
    if args.detect_kmi {
        let boot_path = args.boot.context("missing --boot <boot.img>")?;
        let metadata = fs::metadata(&boot_path)?;
        ensure!(metadata.len() > 0 && metadata.len() <= MAX_BOOT_IMAGE,
            "boot image size is invalid or exceeds {} MiB", MAX_BOOT_IMAGE / (1024 * 1024));
        let bytes = fs::read(&boot_path).context("failed to read boot image")?;
        let image = BootImage::parse(&bytes).context("unsupported or malformed Android boot image")?;
        match detect_boot_kmi(&image) {
            Ok(Some(kmi)) => println!("DETECTED_KMI={kmi}"),
            Ok(None) => println!("DETECTED_KMI=NONE"),
            Err(error) => {
                println!("- KMI detection unavailable: {error:#}");
                println!("DETECTED_KMI=NONE");
            }
        }
        return Ok(());
    }
    let boot_path = args.boot.context("missing --boot <boot.img>")?;
    let module_path = args.module.context("missing --module <zkfc.ko>")?;
    let init_path = args.init.context("missing --init <zkinit>")?;
    let kmi = args.kmi.context("missing --kmi <androidNN-X.Y>")?;
    let requested_output = args.out.context("missing --out <patched.img>")?;
    let output_path = absolute_path(&requested_output)?;

    ensure!(SUPPORTED_KMIS.contains(&kmi.as_str()), "unsupported KMI '{kmi}'");
    ensure!(boot_path.is_file(), "boot image does not exist: {}", boot_path.display());
    ensure!(module_path.is_file(), "ZKFC module does not exist: {}", module_path.display());
    ensure!(init_path.is_file(), "Zairenkai init wrapper does not exist: {}", init_path.display());

    let boot_meta = fs::metadata(&boot_path)?;
    let module_meta = fs::metadata(&module_path)?;
    let init_meta = fs::metadata(&init_path)?;
    ensure!(boot_meta.len() > 0 && boot_meta.len() <= MAX_BOOT_IMAGE,
        "boot image size is invalid or exceeds {} MiB", MAX_BOOT_IMAGE / (1024 * 1024));
    ensure!(module_meta.len() > 0 && module_meta.len() <= MAX_MODULE,
        "ZKFC module size is invalid or exceeds {} MiB", MAX_MODULE / (1024 * 1024));
    ensure!(init_meta.len() > 0 && init_meta.len() <= MAX_INIT,
        "Zairenkai init wrapper size is invalid or exceeds {} MiB", MAX_INIT / (1024 * 1024));

    let canonical_input = fs::canonicalize(&boot_path)?;
    let canonical_module = fs::canonicalize(&module_path)?;
    let canonical_init = fs::canonicalize(&init_path)?;
    ensure!(canonical_input != output_path, "output path must not overwrite the source boot image");
    ensure!(canonical_module != output_path, "output path must not overwrite the source ZKFC module");
    ensure!(canonical_init != output_path, "output path must not overwrite the Zairenkai init wrapper");
    ensure!(output_path.parent().is_some_and(Path::exists), "output parent directory does not exist");

    println!("- Reading boot image");
    let boot_data = fs::read(&boot_path).context("failed to read boot image")?;
    let module_data = fs::read(&module_path).context("failed to read ZKFC module")?;
    let init_data = fs::read(&init_path).context("failed to read Zairenkai init wrapper")?;
    validate_module_arch(&module_data)?;
    validate_init_arch(&init_data)?;
    let expected_init_machine = u16::from_le_bytes([init_data[18], init_data[19]]);
    let branch_verified = validate_module_kmi(&module_data, &kmi)
        .context("could not validate ZKFC module vermagic against the selected KMI")?;
    if branch_verified {
        println!("- ZKFC module vermagic confirms Android KMI branch: {kmi}");
    } else {
        let version = kmi.split_once('-')
            .map(|(_, version)| version)
            .unwrap_or("unknown");
        println!("- WARNING: ZKFC vermagic confirms Linux {version}, but does not encode the Android KMI branch.");
        println!("- The selected branch {kmi} cannot be verified from vermagic alone.");
        println!("- Continue only if this module was built specifically for {kmi}.");
    }
    let module_kmi = kmi.clone();
    println!("- Preparing boot image parser");

    let boot_image = BootImage::parse(&boot_data).context("unsupported or malformed Android boot image")?;
    match boot_image.get_header().get_version() {
        BootImageVersion::Android(version) if version < 3 => {
            bail!("boot header v{version} is unsupported; use Android boot header v3/v4 or a compatible vendor_boot image");
        }
        BootImageVersion::Android(_) | BootImageVersion::Vendor(_) => {}
        BootImageVersion::RawRamdisk => bail!("raw ramdisk input is not accepted here"),
    }
    let image_kmi = match detect_boot_kmi(&boot_image) {
        Ok(Some(image_kmi)) => {
            ensure!(image_kmi == module_kmi,
                "KMI mismatch: selected module is {module_kmi}, but the image kernel identifies {image_kmi}");
            println!("- Boot image KMI: {image_kmi}");
            Some(image_kmi)
        }
        Ok(None) => {
            println!("- Image has no kernel payload; KMI validated against zkfc.ko vermagic only");
            None
        }
        Err(error) => {
            println!("- Could not auto-detect KMI from image kernel: {error:#}");
            println!("- Continuing with the explicitly selected KMI after module vermagic validation");
            None
        }
    };
    println!("- KMI: {module_kmi}");

    println!("- Unpacking ramdisk");
    let mut patcher = BootImagePatchOption::new(&boot_image);
    let (mut cpio, vendor_ramdisk_index) = match boot_image.get_blocks().get_ramdisk() {
        Some(ramdisk_image) => extract_ramdisk(ramdisk_image)?,
        None => {
            println!("- Image has no ramdisk block; preparing a new CPIO ramdisk");
            (Cpio::new(), None)
        }
    };

    let original_init_preserved = inject_runtime_payloads(&mut cpio, module_data, init_data, &module_kmi)?;

    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio).context("failed to serialize ramdisk CPIO")?;
    if let Some(index) = vendor_ramdisk_index {
        patcher.replace_vendor_ramdisk(index, Box::new(Cursor::new(new_cpio)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }

    println!("- Repacking boot image");
    let mut new_boot = Cursor::new(Vec::<u8>::with_capacity(boot_data.len()));
    patcher.patch(&mut new_boot).context("boot image repacking failed")?;
    let patched_bytes = new_boot.into_inner();
    ensure!(patched_bytes.len() as u64 <= MAX_BOOT_IMAGE,
        "repacked image exceeds the safe output limit of {} MiB", MAX_BOOT_IMAGE / (1024 * 1024));

    println!("- Verifying patched boot image");
    verify_output(
        &patched_bytes,
        &module_kmi,
        image_kmi.as_deref(),
        expected_init_machine,
        original_init_preserved,
    )?;
    write_output_atomically(&output_path, &patched_bytes)?;
    println!("- Output file is written to");
    println!("- {}", output_path.display());
    println!("- Done!");
    Ok(())
}

fn parse_args() -> Result<Args> {
    let mut args = Args::default();
    let mut iter = std::env::args().skip(1);
    while let Some(flag) = iter.next() {
        if flag == "--help" || flag == "-h" {
            println!("Usage: zkbootpatch --boot boot.img --module zkfc.ko --init zkinit --kmi android15-6.6 --out patched.img\n       zkbootpatch --detect-kmi --boot boot.img");
            std::process::exit(0);
        }
        if flag == "--detect-kmi" {
            args.detect_kmi = true;
            continue;
        }
        let value = iter.next().with_context(|| format!("missing value for {flag}"))?;
        match flag.as_str() {
            "--boot" => args.boot = Some(PathBuf::from(value)),
            "--module" => args.module = Some(PathBuf::from(value)),
            "--init" => args.init = Some(PathBuf::from(value)),
            "--kmi" => args.kmi = Some(value),
            "--out" => args.out = Some(PathBuf::from(value)),
            _ => bail!("unknown argument '{flag}'"),
        }
    }
    Ok(args)
}

fn absolute_path(path: &Path) -> Result<PathBuf> {
    let path = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()?.join(path)
    };
    if path.exists() {
        Ok(fs::canonicalize(path)?)
    } else {
        let parent = path.parent().context("output path has no parent")?;
        Ok(fs::canonicalize(parent)?.join(path.file_name().context("output path has no filename")?))
    }
}

fn validate_module_arch(data: &[u8]) -> Result<()> {
    ensure!(data.len() >= 64, "ZKFC module is truncated");
    ensure!(&data[..4] == b"\x7fELF", "selected file is not an ELF kernel module");
    ensure!(data[4] == 2, "ZKFC module must be ELF64");
    ensure!(data[5] == 1, "ZKFC module must use little-endian ELF");
    let elf_type = u16::from_le_bytes([data[16], data[17]]);
    let machine = u16::from_le_bytes([data[18], data[19]]);
    ensure!(elf_type == 1, "selected ELF is not relocatable (ET_REL)");
    let expected_machine: u16 = if cfg!(target_arch = "aarch64") {
        183 // EM_AARCH64
    } else if cfg!(target_arch = "x86_64") {
        62 // EM_X86_64
    } else {
        bail!("unsupported helper architecture: {}", std::env::consts::ARCH);
    };
    ensure!(machine == expected_machine,
        "ZKFC module architecture does not match this device/helper ABI (ELF machine {machine}, expected {expected_machine})");
    Ok(())
}


fn validate_init_arch(data: &[u8]) -> Result<()> {
    ensure!(data.len() >= 64, "Zairenkai init wrapper is truncated");
    ensure!(&data[..4] == b"\x7fELF", "selected init wrapper is not an ELF executable");
    ensure!(data[4] == 2, "Zairenkai init wrapper must be ELF64");
    ensure!(data[5] == 1, "Zairenkai init wrapper must use little-endian ELF");
    let elf_type = u16::from_le_bytes([data[16], data[17]]);
    let machine = u16::from_le_bytes([data[18], data[19]]);
    ensure!(elf_type == 3, "Zairenkai init wrapper must be a position-independent executable (ET_DYN)");
    let expected_machine: u16 = if cfg!(target_arch = "aarch64") {
        183 // EM_AARCH64
    } else if cfg!(target_arch = "x86_64") {
        62 // EM_X86_64
    } else {
        bail!("unsupported helper architecture: {}", std::env::consts::ARCH);
    };
    ensure!(machine == expected_machine,
        "Zairenkai init wrapper architecture does not match this device/helper ABI (ELF machine {machine}, expected {expected_machine})");
    Ok(())
}

fn validate_module_kmi(data: &[u8], expected_kmi: &str) -> Result<bool> {
    let marker = b"vermagic=";
    let start = data.windows(marker.len()).position(|window| window == marker)
        .ok_or_else(|| anyhow!("missing vermagic field in module .modinfo"))?;
    let rest = &data[start + marker.len()..];
    let end = rest.iter().position(|byte| *byte == 0)
        .ok_or_else(|| anyhow!("unterminated vermagic field"))?;
    let vermagic = std::str::from_utf8(&rest[..end])
        .context("module vermagic is not ASCII/UTF-8")?;
    let release = vermagic.split_whitespace().next().unwrap_or_default();
    let release_parts: Vec<&str> = release.split('-').collect();
    let version = release_parts.first()
        .context("vermagic has no kernel release")?;

    let mut version_parts = version.split('.');
    let major = version_parts.next()
        .context("vermagic has no major version")?;
    let minor = version_parts.next()
        .context("vermagic has no minor version")?;

    ensure!(
        !major.is_empty()
            && !minor.is_empty()
            && major.bytes().all(|b| b.is_ascii_digit())
            && minor.bytes().all(|b| b.is_ascii_digit()),
        "invalid kernel version in module vermagic"
    );

    let actual_version = format!("{major}.{minor}");
    let (expected_android, expected_version) = expected_kmi
        .split_once('-')
        .context("selected KMI must use androidNN-X.Y format")?;
    let android_digits = expected_android.strip_prefix("android").unwrap_or_default();

    ensure!(
        !android_digits.is_empty()
            && android_digits.bytes().all(|b| b.is_ascii_digit()),
        "invalid Android KMI branch: {expected_android}"
    );
    ensure!(
        actual_version == expected_version,
        "module vermagic targets Linux {actual_version}, but selected KMI {expected_kmi} requires Linux {expected_version}"
    );

    let actual_android = release_parts.iter()
        .find(|part| {
            part.strip_prefix("android").is_some_and(|digits| {
                !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())
            })
        })
        .copied();

    match actual_android {
        Some(branch) => {
            ensure!(
                branch == expected_android,
                "module vermagic identifies {branch}-{actual_version}, but selected KMI is {expected_kmi}"
            );
            Ok(true)
        }
        None => Ok(false),
    }
}

/// Match KernelSU-Next's kernel-byte KMI detection so a boot.img with a kernel
/// is checked independently of the user-selected module KMI. init_boot and
/// vendor_boot images may have no kernel block, in which case only module
/// vermagic can be compared by this standalone file-patch flow.
fn detect_boot_kmi(boot_image: &BootImage<'_>) -> Result<Option<String>> {
    let Some(kernel) = boot_image.get_blocks().get_kernel() else {
        return Ok(None);
    };
    let mut kernel_bytes = Vec::<u8>::new();
    kernel.dump(&mut kernel_bytes, false).context("failed to decompress kernel payload")?;
    parse_kernel_kmi(&kernel_bytes).map(Some)
}

fn parse_kernel_kmi(buffer: &[u8]) -> Result<String> {
    // Follow the KernelSU-Next matcher: identify the Linux major.minor value
    // in the same NUL-terminated string as androidNN, then normalize it to KMI.
    let re = Regex::new(r"(\d+\.\d+)(?:\S+)?(android\d+)")
        .context("failed to compile KMI matcher")?;
    buffer.windows(4).enumerate()
        .filter(|(_, window)| {
            window[1] == b'.'
                && window[2].is_ascii_digit()
                && match window[0] {
                    b'5' => window[3].is_ascii_digit(),
                    b'6'..=b'9' => true,
                    _ => false,
                }
        })
        .find_map(|(index, _)| {
            let end = buffer.len().min(index + 100);
            let candidate = &buffer[index..end];
            let nul = candidate.iter().position(|byte| *byte == 0)?;
            let text = std::str::from_utf8(&candidate[..nul]).ok()?;
            let captures = re.captures(text)?;
            let kernel_version = captures.get(1)?.as_str();
            let android_version = captures.get(2)?.as_str();
            Some(format!("{android_version}-{kernel_version}"))
        })
        .ok_or_else(|| anyhow!("kernel payload does not contain a recognizable Android KMI string"))
}

fn inject_runtime_payloads(
    cpio: &mut Cpio,
    module_data: Vec<u8>,
    init_data: Vec<u8>,
    module_kmi: &str,
) -> Result<bool> {
    ensure!(!cpio.is_magisk_patched(),
        "this image already appears to be Magisk-patched; restore a clean image before patching with Zairenkai");
    ensure!(!cpio.exists(ORIGINAL_INIT_ENTRY),
        "ramdisk already contains init.real; it may already be patched by another installer");
    ensure!(!cpio.exists(MODULE_ENTRY) && !cpio.exists("zkfc_bootpatch.conf"),
        "ramdisk already contains Zairenkai patch markers; restore a clean image before patching again");
    let original_init_preserved = cpio.exists(INIT_ENTRY);
    if original_init_preserved {
        println!("- Preserving original init as init.real");
        cpio.mv(INIT_ENTRY, ORIGINAL_INIT_ENTRY)
            .context("could not preserve original ramdisk /init as /init.real")?;
    } else {
        println!("- No /init entry in source ramdisk; zkinit will fall back to /system/bin/init");
    }
    validate_init_arch(&init_data)?;
    validate_module_arch(&module_data)?;
    validate_module_kmi(&module_data, module_kmi)
        .context("module vermagic does not match requested boot-patch KMI")?;

    println!("- Installing Zairenkai init wrapper");
    cpio.add(INIT_ENTRY, CpioEntry::regular(0o755, Box::new(init_data)))
        .context("could not install Zairenkai init wrapper")?;

    println!("- Adding ZKFC LKM");
    cpio.add(MODULE_ENTRY, CpioEntry::regular(0o755, Box::new(module_data)))?;

    // Diagnostic metadata is not a runtime module configuration file. The
    // module itself is compiled for the selected KMI.
    let patch_manifest = format!(
        "format=1\nkmi={}\nmodule={}\ninit_wrapper=zkinit\noriginal_init={}\npatcher=zkbootpatch-0.2.0\n",
        module_kmi,
        MODULE_ENTRY,
        if original_init_preserved { "init.real" } else { "system-bin-init-fallback" },
    );
    println!("- Adding Zairenkai KMI diagnostic manifest");
    cpio.add(
        "zkfc_bootpatch.conf",
        CpioEntry::regular(0o644, Box::new(patch_manifest.into_bytes())),
    )?;
    Ok(original_init_preserved)
}

fn extract_ramdisk(ramdisk_image: &RamdiskImage<'_>) -> Result<(Cpio, Option<usize>)> {
    if ramdisk_image.is_vendor_ramdisk() {
        let (index, entry) = ramdisk_image.iter_vendor_ramdisk().enumerate()
            .find(|(_, entry)| entry.get_name_raw() == b"init_boot")
            .or_else(|| ramdisk_image.iter_vendor_ramdisk().enumerate()
                .find(|(_, entry)| entry.get_name_raw().is_empty()))
            .ok_or_else(|| anyhow!("no suitable vendor ramdisk entry found"))?;
        let mut cpio_data = Vec::<u8>::new();
        entry.dump(&mut cpio_data, false).context("failed to unpack vendor ramdisk entry")?;
        Ok((Cpio::load_from_data(&cpio_data).context("ramdisk is not a supported newc CPIO archive")?, Some(index)))
    } else {
        let mut cpio_data = Vec::<u8>::new();
        ramdisk_image.dump(&mut cpio_data, false).context("failed to decompress ramdisk")?;
        Ok((Cpio::load_from_data(&cpio_data).context("ramdisk is not a supported newc CPIO archive")?, None))
    }
}

fn verify_output(
    bytes: &[u8],
    expected_kmi: &str,
    expected_image_kmi: Option<&str>,
    expected_init_machine: u16,
    expected_original_init: bool,
) -> Result<()> {
    let image = BootImage::parse(bytes).context("repacked file is not a valid Android boot image")?;
    if let Some(expected_image_kmi) = expected_image_kmi {
        let actual_image_kmi = detect_boot_kmi(&image)
            .context("could not re-verify the boot image KMI after repacking")?
            .ok_or_else(|| anyhow!("repacked image unexpectedly lost its kernel payload"))?;
        ensure!(actual_image_kmi == expected_image_kmi,
            "repacked image kernel KMI changed from {expected_image_kmi} to {actual_image_kmi}");
    }
    let ramdisk = image.get_blocks().get_ramdisk()
        .ok_or_else(|| anyhow!("repacked image lost its ramdisk"))?;
    let (cpio, _) = extract_ramdisk(ramdisk).context("could not re-open repacked ramdisk")?;
    ensure!(cpio.exists(MODULE_ENTRY), "patched image does not contain zkfc.ko");
    let module = cpio.entry_by_name(MODULE_ENTRY).and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image contains an unreadable zkfc.ko"))?;
    validate_module_arch(module).context("patched module verification failed")?;
    validate_module_kmi(module, expected_kmi)
        .context("patched image contains a ZKFC module with incompatible vermagic")?;

    ensure!(cpio.exists(INIT_ENTRY), "patched image does not contain the Zairenkai init wrapper");
    ensure!(cpio.exists(ORIGINAL_INIT_ENTRY) == expected_original_init,
        "patched image original-init preservation state changed unexpectedly");
    let init = cpio.entry_by_name(INIT_ENTRY).and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image contains an unreadable init wrapper"))?;
    validate_init_arch(init).context("patched init wrapper verification failed")?;
    let actual_machine = u16::from_le_bytes([init[18], init[19]]);
    ensure!(actual_machine == expected_init_machine, "patched init wrapper architecture changed");

    let manifest = cpio.entry_by_name("zkfc_bootpatch.conf").and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image lost the Zairenkai patch manifest"))?;
    let manifest = std::str::from_utf8(manifest).context("patch manifest is not valid UTF-8")?;
    let expected_manifest_line = format!("kmi={expected_kmi}");
    ensure!(manifest.lines().any(|line| line == expected_manifest_line.as_str()),
        "patch manifest KMI does not match the selected KMI");
    ensure!(manifest.lines().any(|line| line == "init_wrapper=zkinit"),
        "patch manifest does not identify the injected init wrapper");
    let expected_original_init_line = if expected_original_init {
        "original_init=init.real"
    } else {
        "original_init=system-bin-init-fallback"
    };
    ensure!(manifest.lines().any(|line| line == expected_original_init_line),
        "patch manifest original-init state is inconsistent");
    ensure!(SUPPORTED_KMIS.contains(&expected_kmi), "internal KMI validation failed");
    Ok(())
}

fn write_output_atomically(path: &Path, bytes: &[u8]) -> Result<()> {
    ensure!(!path.exists(), "output already exists; select a new output filename");
    let parent = path.parent().context("output path has no parent")?;
    let name = path.file_name().context("output path has no filename")?.to_string_lossy();
    let temp_path = parent.join(format!(".{name}.zkbootpatch.tmp"));
    let mut temp = OpenOptions::new().write(true).create_new(true).open(&temp_path)
        .with_context(|| format!("could not create temporary output {}", temp_path.display()))?;
    let write_result = temp.write_all(bytes).and_then(|_| temp.sync_all());
    if let Err(error) = write_result {
        let _ = fs::remove_file(&temp_path);
        return Err(error).context("failed to write patched image");
    }
    drop(temp);
    if let Err(error) = fs::rename(&temp_path, path) {
        let _ = fs::remove_file(&temp_path);
        return Err(error).context("failed to finalize patched image");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_android_branch_when_vermagic_contains_it() {
        let module = b"vermagic=6.6.42-android15-12-gabcdef SMP preempt\0";
        assert!(validate_module_kmi(module, "android15-6.6").unwrap());
    }

    #[test]
    fn allows_missing_branch_only_when_kernel_version_matches() {
        let module = b"vermagic=5.10.198-gabc SMP preempt\0";
        assert!(!validate_module_kmi(module, "android12-5.10").unwrap());
        assert!(validate_module_kmi(module, "android13-5.15").is_err());
    }

    #[test]
    fn rejects_conflicting_android_branch() {
        let module = b"vermagic=5.10.198-android13-9-gabc SMP preempt\0";
        assert!(validate_module_kmi(module, "android12-5.10").is_err());
    }

    #[test]
    fn parses_kernel_kmi_like_kernelsu_next() {
        let kernel = b"Linux version 6.6.42-android15-12-gabcdef SMP preempt\0other data";
        assert_eq!(parse_kernel_kmi(kernel).unwrap(), "android15-6.6");
    }

    #[test]
    fn rejects_kernel_without_android_kmi_marker() {
        let kernel = b"Linux version 6.6.42-generic SMP preempt\0";
        assert!(parse_kernel_kmi(kernel).is_err());
    }

    fn synthetic_elf(elf_type: u16, machine: u16, trailer: &[u8]) -> Vec<u8> {
        let mut data = vec![0u8; 64];
        data[..4].copy_from_slice(b"\x7fELF");
        data[4] = 2; // ELF64
        data[5] = 1; // little-endian
        data[16..18].copy_from_slice(&elf_type.to_le_bytes());
        data[18..20].copy_from_slice(&machine.to_le_bytes());
        data.extend_from_slice(trailer);
        data
    }

    #[test]
    fn injection_preserves_original_init_and_adds_runtime_entries() {
        let machine = if cfg!(target_arch = "aarch64") { 183u16 } else { 62u16 };
        let module = synthetic_elf(1, machine, b"vermagic=6.6.42-android15-12 SMP preempt\0");
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        cpio.add("init", CpioEntry::regular(0o755, Box::new(b"original-init".to_vec()))).unwrap();

        let preserved = inject_runtime_payloads(&mut cpio, module, init, "android15-6.6").unwrap();
        assert!(preserved);
        assert!(cpio.exists("init"));
        assert!(cpio.exists("init.real"));
        assert!(cpio.exists("zkfc.ko"));
        assert_eq!(cpio.entry_by_name("init.real").and_then(CpioEntry::data).unwrap(), b"original-init");
        let manifest = cpio.entry_by_name("zkfc_bootpatch.conf").and_then(CpioEntry::data).unwrap();
        let manifest = std::str::from_utf8(manifest).unwrap();
        assert!(manifest.contains("kmi=android15-6.6"));
        assert!(manifest.contains("original_init=init.real"));
    }

    #[test]
    fn injection_refuses_a_preexisting_init_real() {
        let machine = if cfg!(target_arch = "aarch64") { 183u16 } else { 62u16 };
        let module = synthetic_elf(1, machine, b"vermagic=6.6.42-android15-12 SMP preempt\0");
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        cpio.add("init", CpioEntry::regular(0o755, Box::new(b"init".to_vec()))).unwrap();
        cpio.add("init.real", CpioEntry::regular(0o755, Box::new(b"other installer".to_vec()))).unwrap();
        assert!(inject_runtime_payloads(&mut cpio, module, init, "android15-6.6").is_err());
    }

    #[test]
    fn injection_can_chainload_system_init_when_source_has_no_init_entry() {
        let machine = if cfg!(target_arch = "aarch64") { 183u16 } else { 62u16 };
        let module = synthetic_elf(1, machine, b"vermagic=6.6.42-android15-12 SMP preempt\0");
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        let preserved = inject_runtime_payloads(&mut cpio, module, init, "android15-6.6").unwrap();
        assert!(!preserved);
        assert!(cpio.exists("init"));
        assert!(!cpio.exists("init.real"));
        let manifest = cpio.entry_by_name("zkfc_bootpatch.conf").and_then(CpioEntry::data).unwrap();
        let manifest = std::str::from_utf8(manifest).unwrap();
        assert!(manifest.contains("original_init=system-bin-init-fallback"));
    }
}
