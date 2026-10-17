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
    /// Optional boot.img used only to identify the kernel backing init_boot.img/vendor_boot.img.
    kernel_reference: Option<PathBuf>,
    /// Optional vendor *.ko whose vermagic identifies the Android KMI, matching KernelSU-Next's fallback.
    kmi_reference_module: Option<PathBuf>,
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
        let boot_path = args
            .boot
            .as_ref()
            .context("missing --boot <boot.img|init_boot.img|vendor_boot.img>")?;
        validate_boot_file(boot_path)?;
        let bytes = fs::read(boot_path).context("failed to read kernel/ramdisk image")?;
        let image =
            BootImage::parse(&bytes).context("unsupported or malformed Android boot image")?;
        let identity = if image.get_blocks().get_kernel().is_some() {
            Some(kernel_identity(&image)?)
        } else if let Some(reference_path) = args.kernel_reference.as_ref() {
            validate_boot_file(reference_path)?;
            let reference_bytes =
                fs::read(reference_path).context("failed to read kernel-reference boot image")?;
            let reference = BootImage::parse(&reference_bytes)
                .context("unsupported or malformed kernel-reference boot image")?;
            Some(
                kernel_identity(&reference)
                    .context("kernel reference does not contain a readable kernel")?,
            )
        } else {
            None
        };

        let module_kmi_info = if let Some(module_path) = args.kmi_reference_module.as_ref() {
            ensure!(
                module_path.is_file(),
                "KMI reference vendor module does not exist"
            );
            let module_data =
                fs::read(module_path).context("failed to read KMI reference module")?;
            let kmi = parse_kmi_from_module(&module_data)
                .context("could not detect Android KMI from the selected vendor module vermagic")?;
            Some((module_data, kmi))
        } else {
            None
        };

        let (release, kmi, source) = if let Some(identity) = identity {
            if let (Some(image_kmi), Some((module_data, module_kmi))) =
                (identity.kmi.as_deref(), module_kmi_info.as_ref())
            {
                ensure!(
                    image_kmi == module_kmi,
                    "kernel image KMI {image_kmi} conflicts with vendor module KMI {module_kmi}; use files from the same firmware build"
                );
                validate_module_kernel_family(module_data, &identity.release).context(
                    "vendor module vermagic family does not match the target kernel release",
                )?;
            }
            if let Some(kmi) = identity.kmi {
                (Some(identity.release), Some(kmi), Some("kernel-image"))
            } else if let Some((module_data, kmi)) = module_kmi_info {
                validate_module_kernel_family(&module_data, &identity.release).context(
                    "vendor module vermagic family does not match the target kernel release",
                )?;
                (
                    Some(identity.release),
                    Some(kmi),
                    Some("vendor-module-vermagic"),
                )
            } else {
                (Some(identity.release), None, None)
            }
        } else if let Some((_, kmi)) = module_kmi_info {
            (None, Some(kmi), Some("vendor-module-vermagic"))
        } else {
            (None, None, None)
        };

        if let Some(release) = release {
            println!("DETECTED_KERNEL_RELEASE={release}");
        } else {
            println!("DETECTED_KERNEL_RELEASE=NONE");
        }
        if let Some(kmi) = kmi {
            ensure!(
                SUPPORTED_KMIS.contains(&kmi.as_str()),
                "detected KMI {kmi} is not in Zairenkai's supported KernelSU-Next-compatible KMI matrix"
            );
            println!("DETECTED_KMI={kmi}");
        } else {
            println!("DETECTED_KMI=NONE");
        }
        println!("DETECTED_KMI_SOURCE={}", source.unwrap_or("none"));
        return Ok(());
    }

    let boot_path = args
        .boot
        .context("missing --boot <boot.img|init_boot.img|vendor_boot.img>")?;
    let module_path = args.module.context("missing --module <zkfc.ko>")?;
    let init_path = args.init.context("missing --init <zkinit>")?;
    let kmi = args.kmi.context("missing --kmi <androidNN-X.Y>")?;
    let requested_output = args.out.context("missing --out <patched.img>")?;
    let output_path = absolute_path(&requested_output)?;

    ensure!(
        SUPPORTED_KMIS.contains(&kmi.as_str()),
        "unsupported KMI '{kmi}'"
    );
    validate_boot_file(&boot_path)?;
    ensure!(
        module_path.is_file(),
        "ZKFC module does not exist: {}",
        module_path.display()
    );
    ensure!(
        init_path.is_file(),
        "Zairenkai init wrapper does not exist: {}",
        init_path.display()
    );
    if let Some(reference_path) = args.kernel_reference.as_ref() {
        validate_boot_file(reference_path)?;
    }
    if let Some(reference_module_path) = args.kmi_reference_module.as_ref() {
        ensure!(
            reference_module_path.is_file(),
            "KMI reference vendor module does not exist: {}",
            reference_module_path.display()
        );
        let metadata = fs::metadata(reference_module_path)?;
        ensure!(
            metadata.len() > 0 && metadata.len() <= MAX_MODULE,
            "KMI reference vendor module is invalid or exceeds {} MiB",
            MAX_MODULE / (1024 * 1024)
        );
    }

    let boot_meta = fs::metadata(&boot_path)?;
    let module_meta = fs::metadata(&module_path)?;
    let init_meta = fs::metadata(&init_path)?;
    ensure!(
        boot_meta.len() > 0 && boot_meta.len() <= MAX_BOOT_IMAGE,
        "boot/init_boot image size is invalid or exceeds {} MiB",
        MAX_BOOT_IMAGE / (1024 * 1024)
    );
    ensure!(
        module_meta.len() > 0 && module_meta.len() <= MAX_MODULE,
        "ZKFC module size is invalid or exceeds {} MiB",
        MAX_MODULE / (1024 * 1024)
    );
    ensure!(
        init_meta.len() > 0 && init_meta.len() <= MAX_INIT,
        "Zairenkai init wrapper size is invalid or exceeds {} MiB",
        MAX_INIT / (1024 * 1024)
    );

    let canonical_input = fs::canonicalize(&boot_path)?;
    let canonical_module = fs::canonicalize(&module_path)?;
    let canonical_init = fs::canonicalize(&init_path)?;
    ensure!(
        canonical_input != output_path,
        "output path must not overwrite the source boot/init_boot image"
    );
    if let Some(reference_path) = args.kernel_reference.as_ref() {
        let canonical_reference = fs::canonicalize(reference_path)?;
        ensure!(
            canonical_reference != output_path,
            "output path must not overwrite the kernel-reference boot image"
        );
    }
    if let Some(reference_module_path) = args.kmi_reference_module.as_ref() {
        let canonical_reference_module = fs::canonicalize(reference_module_path)?;
        ensure!(
            canonical_reference_module != output_path,
            "output path must not overwrite the KMI reference vendor module"
        );
    }
    ensure!(
        canonical_module != output_path,
        "output path must not overwrite the source ZKFC module"
    );
    ensure!(
        canonical_init != output_path,
        "output path must not overwrite the Zairenkai init wrapper"
    );
    ensure!(
        output_path.parent().is_some_and(Path::exists),
        "output parent directory does not exist"
    );

    println!("- Reading ramdisk target image");
    let boot_data = fs::read(&boot_path).context("failed to read ramdisk target image")?;
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
        println!("- WARNING: zkfc.ko vermagic does not encode the Android KMI branch.");
        println!(
            "- The selected branch {kmi} will be checked against the boot kernel when its banner allows it."
        );
    }

    let boot_image =
        BootImage::parse(&boot_data).context("unsupported or malformed Android boot image")?;
    match boot_image.get_header().get_version() {
        BootImageVersion::Android(version) if version < 3 => {
            bail!("boot header v{version} is unsupported; use Android boot header v3/v4");
        }
        BootImageVersion::Android(_) => {}
        BootImageVersion::Vendor(_) => {
            println!(
                "- Target is vendor_boot.img; selecting init_boot-named or unnamed vendor ramdisk like KernelSU-Next."
            );
        }
        BootImageVersion::RawRamdisk => bail!("raw ramdisk input is not accepted here"),
    }

    // Never synthesize a ramdisk into a kernel-only boot.img. On Android 13+
    // the generic ramdisk normally belongs to init_boot.img; adding one to
    // boot.img can repack successfully but will not replace the real PID 1.
    let ramdisk_image = boot_image.get_blocks().get_ramdisk().ok_or_else(|| anyhow!(
        "selected image has no generic ramdisk. On Android 13+, select init_boot.img, or explicitly select a suitable vendor_boot.img ramdisk, and provide the matching boot.img as the kernel reference"
    ))?;
    let (mut cpio, vendor_ramdisk_index) = extract_ramdisk(ramdisk_image)
        .context("selected image does not contain a usable generic init ramdisk; choose the matching boot.img, init_boot.img, or supported vendor_boot.img")?;
    ensure!(
        cpio.exists(INIT_ENTRY),
        "selected ramdisk does not contain /init. Refusing to replace PID 1 in an image that cannot chainload the original init"
    );

    // The target image may be init_boot.img (ramdisk but no kernel). In that
    // case require an independently selected boot.img and verify exact kernel
    // release, not merely the 5.10/6.6 major-minor family.
    let target_identity = if boot_image.get_blocks().get_kernel().is_some() {
        Some(
            kernel_identity(&boot_image)
                .context("could not identify kernel release in the target image")?,
        )
    } else {
        None
    };
    let reference_identity = if let Some(reference_path) = args.kernel_reference.as_ref() {
        let reference_bytes =
            fs::read(reference_path).context("failed to read kernel-reference boot image")?;
        let reference = BootImage::parse(&reference_bytes)
            .context("unsupported or malformed kernel-reference boot image")?;
        match reference.get_header().get_version() {
            BootImageVersion::Android(version) if version >= 3 => {}
            BootImageVersion::Android(version) => {
                bail!("kernel-reference boot header v{version} is unsupported; use v3/v4")
            }
            BootImageVersion::Vendor(_) => {
                bail!("kernel reference must be boot.img, not vendor_boot.img")
            }
            BootImageVersion::RawRamdisk => {
                bail!("kernel reference must be a boot image, not a raw ramdisk")
            }
        }
        Some(
            kernel_identity(&reference)
                .context("kernel reference does not contain a readable kernel")?,
        )
    } else {
        None
    };
    if let (Some(target), Some(reference)) = (&target_identity, &reference_identity) {
        ensure!(
            target.release == reference.release,
            "kernel reference release '{}' does not match target image kernel release '{}'; select the matching boot.img/init_boot.img pair",
            reference.release,
            target.release
        );
    }
    let identity = target_identity.as_ref().or(reference_identity.as_ref()).ok_or_else(|| anyhow!(
        "selected image has no kernel payload. Select init_boot.img as the patch target and provide the matching boot.img in the kernel-reference field"
    ))?;
    let kernel_release = identity.release.as_str();
    validate_module_kernel_family(&module_data, kernel_release)
        .context("ZKFC module Linux major/minor does not match the selected kernel family")?;
    let reference_module_data = if let Some(reference_module_path) =
        args.kmi_reference_module.as_ref()
    {
        let data = fs::read(reference_module_path)
            .context("failed to read KMI reference vendor module")?;
        validate_module_kernel_family(&data, kernel_release)
            .context("KMI reference vendor module Linux family does not match the target kernel")?;
        Some(data)
    } else {
        None
    };
    let reference_module_kmi = reference_module_data
        .as_deref()
        .map(parse_kmi_from_module)
        .transpose()
        .context("KMI reference module does not contain an Android KMI marker in vermagic")?;

    let kmi_source = match identity.kmi.as_deref() {
        Some(image_kmi) => {
            ensure!(
                image_kmi == kmi,
                "selected KMI is {kmi}, but the referenced kernel identifies {image_kmi}"
            );
            if let Some(reference_kmi) = reference_module_kmi.as_deref() {
                ensure!(
                    reference_kmi == image_kmi,
                    "kernel image KMI {image_kmi} conflicts with vendor module KMI {reference_kmi}; use files from the same firmware build"
                );
            }
            "kernel-image"
        }
        None => {
            if let Some(reference_kmi) = reference_module_kmi.as_deref() {
                ensure!(
                    reference_kmi == kmi,
                    "selected KMI is {kmi}, but vendor module vermagic identifies {reference_kmi}"
                );
                println!(
                    "- KMI detected from vendor module vermagic (KernelSU-Next fallback): {reference_kmi}"
                );
                "vendor-module-vermagic"
            } else {
                println!("- WARNING: kernel release has no recognizable Android KMI marker.");
                println!("- Selected KMI {kmi} is a manual hint, not a verified kernel KMI.");
                "manual"
            }
        }
    };
    println!("- Verified target kernel release: {kernel_release}");
    println!(
        "- Module Linux family: {}",
        linux_version_family(kernel_release)?
    );
    println!("- Target KMI label: {kmi}");

    println!("- Injecting ZKFC into the existing generic ramdisk");
    inject_runtime_payloads(
        &mut cpio,
        module_data,
        init_data,
        &kmi,
        kernel_release,
        kmi_source,
    )?;

    let mut new_cpio = Vec::<u8>::new();
    cpio.dump(&mut new_cpio)
        .context("failed to serialize ramdisk CPIO")?;
    let mut patcher = BootImagePatchOption::new(&boot_image);
    if let Some(index) = vendor_ramdisk_index {
        patcher.replace_vendor_ramdisk(index, Box::new(Cursor::new(new_cpio)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(new_cpio)), false);
    }

    println!("- Repacking ramdisk target image");
    let mut new_boot = Cursor::new(Vec::<u8>::with_capacity(boot_data.len()));
    patcher
        .patch(&mut new_boot)
        .context("boot image repacking failed")?;
    let patched_bytes = new_boot.into_inner();
    ensure!(
        patched_bytes.len() as u64 <= MAX_BOOT_IMAGE,
        "repacked image exceeds the safe output limit of {} MiB",
        MAX_BOOT_IMAGE / (1024 * 1024)
    );

    println!("- Verifying patched image and original-init chain");
    verify_output(
        &patched_bytes,
        &kmi,
        target_identity.as_ref().and_then(|i| i.kmi.as_deref()),
        target_identity.as_ref().map(|i| i.release.as_str()),
        kernel_release,
        kmi_source,
        expected_init_machine,
        true,
    )?;
    write_output_atomically(&output_path, &patched_bytes)?;
    println!("- Output file is written to");
    println!("- {}", output_path.display());
    match kmi_source {
        "kernel-image" => println!("- Kernel image KMI marker matches the selected KMI."),
        "vendor-module-vermagic" => println!(
            "- KMI matches the selected vendor module vermagic using the KernelSU-Next fallback."
        ),
        _ => println!(
            "- WARNING: KMI was selected manually; runtime module compatibility is not proven."
        ),
    }
    println!(
        "- Done! Image structure and Linux family checks passed. The device kernel must still accept the module and publish /dev/zkfc after boot."
    );
    Ok(())
}

fn validate_boot_file(path: &Path) -> Result<()> {
    ensure!(
        path.is_file(),
        "boot image does not exist: {}",
        path.display()
    );
    let metadata = fs::metadata(path)?;
    ensure!(
        metadata.len() > 0 && metadata.len() <= MAX_BOOT_IMAGE,
        "boot image size is invalid or exceeds {} MiB",
        MAX_BOOT_IMAGE / (1024 * 1024)
    );
    Ok(())
}

fn parse_args() -> Result<Args> {
    let mut args = Args::default();
    let mut iter = std::env::args().skip(1);
    while let Some(flag) = iter.next() {
        if flag == "--help" || flag == "-h" {
            println!(
                "Usage: zkbootpatch --boot <boot.img|init_boot.img|vendor_boot.img> [--kernel-reference boot.img] [--kmi-reference-module vendor.ko] --module zkfc.ko --init zkinit --kmi androidNN-X.Y --out patched.img\n       zkbootpatch --detect-kmi --boot <boot.img|init_boot.img|vendor_boot.img> [--kernel-reference boot.img] [--kmi-reference-module vendor.ko]"
            );
            std::process::exit(0);
        }
        if flag == "--detect-kmi" {
            args.detect_kmi = true;
            continue;
        }
        let value = iter
            .next()
            .with_context(|| format!("missing value for {flag}"))?;
        match flag.as_str() {
            "--boot" => args.boot = Some(PathBuf::from(value)),
            "--kernel-reference" => args.kernel_reference = Some(PathBuf::from(value)),
            "--kmi-reference-module" => args.kmi_reference_module = Some(PathBuf::from(value)),
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
        Ok(
            fs::canonicalize(parent)?
                .join(path.file_name().context("output path has no filename")?),
        )
    }
}

fn validate_module_arch(data: &[u8]) -> Result<()> {
    ensure!(data.len() >= 64, "ZKFC module is truncated");
    ensure!(
        &data[..4] == b"\x7fELF",
        "selected file is not an ELF kernel module"
    );
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
        bail!(
            "unsupported helper architecture: {}",
            std::env::consts::ARCH
        );
    };
    ensure!(
        machine == expected_machine,
        "ZKFC module architecture does not match this device/helper ABI (ELF machine {machine}, expected {expected_machine})"
    );
    Ok(())
}

fn validate_init_arch(data: &[u8]) -> Result<()> {
    ensure!(data.len() >= 64, "Zairenkai init wrapper is truncated");
    ensure!(
        &data[..4] == b"\x7fELF",
        "selected init wrapper is not an ELF executable"
    );
    ensure!(data[4] == 2, "Zairenkai init wrapper must be ELF64");
    ensure!(
        data[5] == 1,
        "Zairenkai init wrapper must use little-endian ELF"
    );
    let elf_type = u16::from_le_bytes([data[16], data[17]]);
    let machine = u16::from_le_bytes([data[18], data[19]]);
    ensure!(
        elf_type == 3,
        "Zairenkai init wrapper must be a position-independent executable (ET_DYN)"
    );
    let expected_machine: u16 = if cfg!(target_arch = "aarch64") {
        183 // EM_AARCH64
    } else if cfg!(target_arch = "x86_64") {
        62 // EM_X86_64
    } else {
        bail!(
            "unsupported helper architecture: {}",
            std::env::consts::ARCH
        );
    };
    ensure!(
        machine == expected_machine,
        "Zairenkai init wrapper architecture does not match this device/helper ABI (ELF machine {machine}, expected {expected_machine})"
    );
    Ok(())
}

fn module_vermagic(data: &[u8]) -> Result<String> {
    let marker = b"vermagic=";
    let start = data
        .windows(marker.len())
        .position(|window| window == marker)
        .ok_or_else(|| anyhow!("missing vermagic field in module .modinfo"))?;
    let rest = &data[start + marker.len()..];
    let end = rest
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| anyhow!("unterminated vermagic field"))?;
    std::str::from_utf8(&rest[..end])
        .map(str::to_owned)
        .context("module vermagic is not ASCII/UTF-8")
}

fn parse_kmi_label(text: &str) -> Result<String> {
    // Same branch normalization used by KernelSU-Next: parse Android branch
    // and Linux major.minor from one kernel-version/vermagic string.
    let re = Regex::new(r"(\d+\.\d+)(?:\S+)?(android\d+)")
        .context("failed to compile KMI-from-vermagic matcher")?;
    let captures = re
        .captures(text)
        .ok_or_else(|| anyhow!("string does not contain a recognizable Android KMI marker"))?;
    let kernel_version = captures
        .get(1)
        .context("missing kernel major.minor in KMI string")?
        .as_str();
    let android_version = captures
        .get(2)
        .context("missing Android branch in KMI string")?
        .as_str();
    let kmi = format!("{android_version}-{kernel_version}");
    ensure!(
        SUPPORTED_KMIS.contains(&kmi.as_str()),
        "detected KMI {kmi} is not in the supported matrix"
    );
    Ok(kmi)
}

fn parse_kmi_from_module(data: &[u8]) -> Result<String> {
    let vermagic = module_vermagic(data)?;
    parse_kmi_label(&vermagic)
}

fn module_release(data: &[u8]) -> Result<String> {
    let vermagic = module_vermagic(data)?;
    vermagic
        .split_whitespace()
        .next()
        .map(str::to_owned)
        .filter(|release| !release.is_empty())
        .ok_or_else(|| anyhow!("module vermagic has no kernel release"))
}

fn linux_version_family(release: &str) -> Result<String> {
    let numeric = release.split('-').next().unwrap_or(release);
    let mut parts = numeric.split('.');
    let major = parts
        .next()
        .context("kernel release has no major version")?;
    let minor = parts
        .next()
        .context("kernel release has no minor version")?;
    ensure!(
        !major.is_empty()
            && !minor.is_empty()
            && major.bytes().all(|byte| byte.is_ascii_digit())
            && minor.bytes().all(|byte| byte.is_ascii_digit()),
        "kernel release '{release}' has an invalid Linux major/minor family"
    );
    Ok(format!("{major}.{minor}"))
}

fn validate_module_kernel_family(data: &[u8], expected_release: &str) -> Result<()> {
    let actual_release = module_release(data)?;
    let actual_family = linux_version_family(&actual_release)?;
    let expected_family = linux_version_family(expected_release)?;
    ensure!(
        actual_family == expected_family,
        "zkfc.ko targets Linux {actual_family} (vermagic '{actual_release}'), but the selected kernel is Linux {expected_family} (release '{expected_release}'). Select a module for the same kernel family; KMI and symbol-version compatibility must also match"
    );
    Ok(())
}

fn validate_module_kmi(data: &[u8], expected_kmi: &str) -> Result<bool> {
    let vermagic = module_vermagic(data)?;
    let release = vermagic.split_whitespace().next().unwrap_or_default();
    let release_parts: Vec<&str> = release.split('-').collect();
    let version = release_parts
        .first()
        .context("vermagic has no kernel release")?;
    let mut version_parts = version.split('.');
    let major = version_parts
        .next()
        .context("vermagic has no major version")?;
    let minor = version_parts
        .next()
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
        !android_digits.is_empty() && android_digits.bytes().all(|b| b.is_ascii_digit()),
        "invalid Android KMI branch: {expected_android}"
    );
    ensure!(
        actual_version == expected_version,
        "module vermagic targets Linux {actual_version}, but selected KMI {expected_kmi} requires Linux {expected_version}"
    );

    let actual_android = release_parts
        .iter()
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

#[derive(Debug, Clone)]
struct KernelIdentity {
    release: String,
    kmi: Option<String>,
}

fn kernel_identity(boot_image: &BootImage<'_>) -> Result<KernelIdentity> {
    let kernel = boot_image
        .get_blocks()
        .get_kernel()
        .ok_or_else(|| anyhow!("boot image has no kernel payload"))?;
    let mut kernel_bytes = Vec::<u8>::new();
    kernel
        .dump(&mut kernel_bytes, false)
        .context("failed to decompress kernel payload")?;
    let release = parse_kernel_release(&kernel_bytes)?;
    let kmi = parse_kernel_kmi(&kernel_bytes).ok();
    Ok(KernelIdentity { release, kmi })
}

fn parse_kernel_release(buffer: &[u8]) -> Result<String> {
    const MARKER: &[u8] = b"Linux version ";
    let mut rejected: Option<String> = None;

    // Kernel images contain many printable strings; do not trust the first
    // occurrence of "Linux version" unless its token is a real major.minor
    // release. Keep scanning in case an unrelated embedded string appears first.
    for (start, window) in buffer.windows(MARKER.len()).enumerate() {
        if window != MARKER {
            continue;
        }
        let rest = &buffer[start + MARKER.len()..];
        let end = rest
            .iter()
            .position(|byte| byte.is_ascii_whitespace() || *byte == 0)
            .unwrap_or(rest.len());
        let Ok(candidate) = std::str::from_utf8(&rest[..end]) else {
            continue;
        };
        if candidate.is_empty() || !candidate.bytes().all(|byte| byte.is_ascii_graphic()) {
            continue;
        }
        if linux_version_family(candidate).is_ok() {
            return Ok(candidate.to_owned());
        }
        if rejected.is_none() {
            rejected = Some(candidate.to_owned());
        }
    }

    if let Some(candidate) = rejected {
        bail!(
            "kernel payload contains no valid Linux major.minor release; rejected banner token '{candidate}'"
        );
    }
    bail!(
        "kernel payload does not contain a valid Linux version banner (expected major.minor[.patch])"
    )
}

fn parse_kernel_kmi(buffer: &[u8]) -> Result<String> {
    // Follow the KernelSU-Next matcher: identify the Linux major.minor value
    // in the same NUL-terminated string as androidNN, then normalize it to KMI.
    let re =
        Regex::new(r"(\d+\.\d+)(?:\S+)?(android\d+)").context("failed to compile KMI matcher")?;
    buffer
        .windows(4)
        .enumerate()
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
    kernel_release: &str,
    kmi_source: &str,
) -> Result<bool> {
    ensure!(
        !cpio.is_magisk_patched(),
        "this image already appears to be Magisk-patched; restore a clean image before patching with Zairenkai"
    );
    ensure!(
        !cpio.exists(ORIGINAL_INIT_ENTRY),
        "ramdisk already contains init.real; it may already be patched by another installer"
    );
    ensure!(
        !cpio.exists(MODULE_ENTRY) && !cpio.exists("zkfc_bootpatch.conf"),
        "ramdisk already contains Zairenkai patch markers; restore a clean image before patching again"
    );
    let original_init_preserved = cpio.exists(INIT_ENTRY);
    ensure!(
        original_init_preserved,
        "ramdisk does not contain /init; refusing to install a PID 1 wrapper that cannot chainload Android init"
    );
    println!("- Preserving original init as init.real");
    cpio.mv(INIT_ENTRY, ORIGINAL_INIT_ENTRY)
        .context("could not preserve original ramdisk /init as /init.real")?;
    validate_init_arch(&init_data)?;
    validate_module_arch(&module_data)?;
    validate_module_kmi(&module_data, module_kmi)
        .context("module vermagic does not match requested boot-patch KMI")?;
    validate_module_kernel_family(&module_data, kernel_release).context(
        "module vermagic does not match the Linux major/minor family selected for this image",
    )?;

    println!("- Installing Zairenkai init wrapper");
    cpio.add(INIT_ENTRY, CpioEntry::regular(0o755, Box::new(init_data)))
        .context("could not install Zairenkai init wrapper")?;

    println!("- Adding ZKFC LKM");
    cpio.add(
        MODULE_ENTRY,
        CpioEntry::regular(0o755, Box::new(module_data)),
    )?;

    // Diagnostic metadata is not a runtime module configuration file. The
    // module itself is compiled for the selected KMI.
    let patch_manifest = format!(
        "format=3\nkmi={}\nkmi_source={}\nkernel_release={}\nmodule={}\ninit_wrapper=zkinit\noriginal_init=init.real\npatcher=zkbootpatch-0.3.0\n",
        module_kmi, kmi_source, kernel_release, MODULE_ENTRY,
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
        // Mirror KernelSU-Next: select the ramdisk named "init_boot" first,
        // then the unnamed generic ramdisk, never an arbitrary vendor fragment.
        let (index, target) = ramdisk_image
            .iter_vendor_ramdisk()
            .enumerate()
            .find(|(_, entry)| entry.get_name_raw() == b"init_boot")
            .or_else(|| {
                ramdisk_image
                    .iter_vendor_ramdisk()
                    .enumerate()
                    .find(|(_, entry)| entry.get_name_raw().is_empty())
            })
            .ok_or_else(|| anyhow!(
                "vendor_boot has no suitable init_boot-named or unnamed generic ramdisk fragment"
            ))?;
        let mut cpio_data = Vec::<u8>::new();
        target
            .dump(&mut cpio_data, false)
            .context("failed to decompress selected vendor ramdisk fragment")?;
        return Ok((
            Cpio::load_from_data(&cpio_data)
                .context("selected vendor ramdisk fragment is not a supported newc CPIO archive")?,
            Some(index),
        ));
    }
    let mut cpio_data = Vec::<u8>::new();
    ramdisk_image
        .dump(&mut cpio_data, false)
        .context("failed to decompress generic ramdisk")?;
    Ok((
        Cpio::load_from_data(&cpio_data).context("ramdisk is not a supported newc CPIO archive")?,
        None,
    ))
}

fn verify_output(
    bytes: &[u8],
    expected_kmi: &str,
    expected_image_kmi: Option<&str>,
    expected_target_kernel_release: Option<&str>,
    expected_kernel_release: &str,
    expected_kmi_source: &str,
    expected_init_machine: u16,
    expected_original_init: bool,
) -> Result<()> {
    let image =
        BootImage::parse(bytes).context("repacked file is not a valid Android boot image")?;
    match image.get_header().get_version() {
        BootImageVersion::Android(version) if version >= 3 => {}
        BootImageVersion::Vendor(_) => {}
        _ => bail!(
            "repacked image is not a supported Android v3/v4 boot/init_boot/vendor_boot image"
        ),
    }
    ensure!(
        image.get_blocks().get_ramdisk().is_some(),
        "patched output has no ramdisk"
    );
    if let Some(expected_target_kernel_release) = expected_target_kernel_release {
        let actual_identity = kernel_identity(&image)
            .context("could not re-verify the target kernel after repacking")?;
        ensure!(
            actual_identity.release == expected_target_kernel_release,
            "repacked image kernel release changed from {expected_target_kernel_release} to {}",
            actual_identity.release
        );
        if let Some(expected_image_kmi) = expected_image_kmi {
            ensure!(
                actual_identity.kmi.as_deref() == Some(expected_image_kmi),
                "repacked image kernel KMI changed from {expected_image_kmi}"
            );
        }
    }
    let ramdisk = image
        .get_blocks()
        .get_ramdisk()
        .ok_or_else(|| anyhow!("repacked image lost its ramdisk"))?;
    let (cpio, _) = extract_ramdisk(ramdisk).context("could not re-open repacked ramdisk")?;
    ensure!(
        cpio.exists(MODULE_ENTRY),
        "patched image does not contain zkfc.ko"
    );
    let module = cpio
        .entry_by_name(MODULE_ENTRY)
        .and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image contains an unreadable zkfc.ko"))?;
    validate_module_arch(module).context("patched module verification failed")?;
    validate_module_kmi(module, expected_kmi)
        .context("patched image contains a ZKFC module with incompatible KMI vermagic")?;
    validate_module_kernel_family(module, expected_kernel_release)
        .context("patched image contains a ZKFC module for a different Linux major/minor family")?;

    ensure!(
        cpio.exists(INIT_ENTRY),
        "patched image does not contain the Zairenkai init wrapper"
    );
    ensure!(
        cpio.exists(ORIGINAL_INIT_ENTRY) == expected_original_init,
        "patched image original-init preservation state changed unexpectedly"
    );
    let init = cpio
        .entry_by_name(INIT_ENTRY)
        .and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image contains an unreadable init wrapper"))?;
    validate_init_arch(init).context("patched init wrapper verification failed")?;
    let actual_machine = u16::from_le_bytes([init[18], init[19]]);
    ensure!(
        actual_machine == expected_init_machine,
        "patched init wrapper architecture changed"
    );

    let manifest = cpio
        .entry_by_name("zkfc_bootpatch.conf")
        .and_then(CpioEntry::data)
        .ok_or_else(|| anyhow!("patched image lost the Zairenkai patch manifest"))?;
    let manifest = std::str::from_utf8(manifest).context("patch manifest is not valid UTF-8")?;
    let expected_manifest_line = format!("kmi={expected_kmi}");
    ensure!(
        manifest
            .lines()
            .any(|line| line == expected_manifest_line.as_str()),
        "patch manifest KMI does not match the selected KMI"
    );
    let expected_kmi_source_line = format!("kmi_source={expected_kmi_source}");
    ensure!(
        manifest
            .lines()
            .any(|line| line == expected_kmi_source_line),
        "patch manifest KMI provenance does not match the detected kernel metadata"
    );
    let expected_release_line = format!("kernel_release={expected_kernel_release}");
    ensure!(
        manifest
            .lines()
            .any(|line| line == expected_release_line.as_str()),
        "patch manifest kernel release does not match the verified target kernel"
    );
    ensure!(
        manifest.lines().any(|line| line == "init_wrapper=zkinit"),
        "patch manifest does not identify the injected init wrapper"
    );
    ensure!(
        manifest
            .lines()
            .any(|line| line == "original_init=init.real"),
        "patch manifest original-init state is inconsistent"
    );
    ensure!(
        SUPPORTED_KMIS.contains(&expected_kmi),
        "internal KMI validation failed"
    );
    Ok(())
}

fn write_output_atomically(path: &Path, bytes: &[u8]) -> Result<()> {
    ensure!(
        !path.exists(),
        "output already exists; select a new output filename"
    );
    let parent = path.parent().context("output path has no parent")?;
    let name = path
        .file_name()
        .context("output path has no filename")?
        .to_string_lossy();
    let temp_path = parent.join(format!(".{name}.zkbootpatch.tmp"));
    let mut temp = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp_path)
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
    fn parses_vendor_module_vermagic_as_kmi_fallback_like_kernelsu_next() {
        let module =
            b"vermagic=5.10.269-android12-9-gki SMP preempt mod_unload modversions aarch64\0";
        assert_eq!(parse_kmi_from_module(module).unwrap(), "android12-5.10");
    }

    #[test]
    fn refuses_vendor_module_vermagic_without_android_branch_marker() {
        let module =
            b"vermagic=5.10.269-gki-g18fe99112273 SMP preempt mod_unload modversions aarch64\0";
        assert!(parse_kmi_from_module(module).is_err());
    }

    #[test]
    fn refuses_vendor_module_kmi_outside_supported_matrix() {
        let module =
            b"vermagic=5.10.269-android16-1-gtest SMP preempt mod_unload modversions aarch64\0";
        assert!(parse_kmi_from_module(module).is_err());
    }

    #[test]
    fn parses_kernel_kmi_like_kernelsu_next() {
        let kernel = b"Linux version 6.6.42-android15-12-gabcdef SMP preempt\0other data";
        assert_eq!(parse_kernel_kmi(kernel).unwrap(), "android15-6.6");
        assert_eq!(
            parse_kernel_release(kernel).unwrap(),
            "6.6.42-android15-12-gabcdef"
        );
    }

    #[test]
    fn module_kernel_family_must_match_target_kernel() {
        let module = b"vermagic=5.10.198-android13-9 SMP preempt mod_unload modversions aarch64\0";
        assert!(validate_module_kernel_family(module, "5.10.269-gki-gf198").is_ok());
        assert!(validate_module_kernel_family(module, "6.6.42-android15-12-gabcdef").is_err());
    }

    #[test]
    fn kernel_release_parser_skips_invalid_banner_before_valid_release() {
        let payload = b"Linux version invalid-banner\0embedded text\0Linux version 5.10.269-android12-9-gtest SMP preempt\0";
        assert_eq!(
            parse_kernel_release(payload).unwrap(),
            "5.10.269-android12-9-gtest"
        );
    }

    #[test]
    fn kernel_release_parser_rejects_banner_without_major_minor() {
        let payload = b"Linux version not-a-version\0Linux version 6\0";
        assert!(parse_kernel_release(payload).is_err());
    }

    #[test]
    fn parses_kernelsu_next_kmi_build_matrix_from_kernel_banner() {
        let fixtures = [
            ("5.10.198-android12-9-gabcdef", "android12-5.10"),
            ("5.10.198-android13-9-gabcdef", "android13-5.10"),
            ("5.15.123-android13-11-gabcdef", "android13-5.15"),
            ("5.15.123-android14-11-gabcdef", "android14-5.15"),
            ("6.1.123-android14-11-gabcdef", "android14-6.1"),
            ("6.6.123-android15-11-gabcdef", "android15-6.6"),
            ("6.12.123-android16-11-gabcdef", "android16-6.12"),
            ("6.18.1-android17-1-gabcdef", "android17-6.18"),
        ];
        for (release, expected_kmi) in fixtures {
            let mut payload = b"Linux version ".to_vec();
            payload.extend_from_slice(release.as_bytes());
            payload.extend_from_slice(b" SMP preempt\0");
            assert_eq!(parse_kernel_release(&payload).unwrap(), release);
            assert_eq!(parse_kernel_kmi(&payload).unwrap(), expected_kmi);
        }
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
        let machine = if cfg!(target_arch = "aarch64") {
            183u16
        } else {
            62u16
        };
        let module = synthetic_elf(
            1,
            machine,
            b"vermagic=6.6.42-android15-12-gabcdef SMP preempt\0",
        );
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(b"original-init".to_vec())),
        )
        .unwrap();

        let preserved = inject_runtime_payloads(
            &mut cpio,
            module,
            init,
            "android15-6.6",
            "6.6.42-android15-12-gabcdef",
            "kernel-image",
        )
        .unwrap();
        assert!(preserved);
        assert!(cpio.exists("init"));
        assert!(cpio.exists("init.real"));
        assert!(cpio.exists("zkfc.ko"));
        assert_eq!(
            cpio.entry_by_name("init.real")
                .and_then(CpioEntry::data)
                .unwrap(),
            b"original-init"
        );
        let manifest = cpio
            .entry_by_name("zkfc_bootpatch.conf")
            .and_then(CpioEntry::data)
            .unwrap();
        let manifest = std::str::from_utf8(manifest).unwrap();
        assert!(manifest.contains("kmi=android15-6.6"));
        assert!(manifest.contains("kmi_source=kernel-image"));
        assert!(manifest.contains("kernel_release=6.6.42-android15-12-gabcdef"));
        assert!(manifest.contains("original_init=init.real"));
    }

    #[test]
    fn records_manual_kmi_when_kernel_has_no_branch_marker() {
        let machine = if cfg!(target_arch = "aarch64") {
            183u16
        } else {
            62u16
        };
        let module = synthetic_elf(1, machine, b"vermagic=6.6.42-generic SMP preempt\0");
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(b"original-init".to_vec())),
        )
        .unwrap();

        inject_runtime_payloads(
            &mut cpio,
            module,
            init,
            "android15-6.6",
            "6.6.42-generic",
            "manual",
        )
        .unwrap();
        let manifest = cpio
            .entry_by_name("zkfc_bootpatch.conf")
            .and_then(CpioEntry::data)
            .unwrap();
        let manifest = std::str::from_utf8(manifest).unwrap();
        assert!(manifest.contains("kmi=android15-6.6"));
        assert!(manifest.contains("kmi_source=manual"));
    }

    #[test]
    fn injection_refuses_a_preexisting_init_real() {
        let machine = if cfg!(target_arch = "aarch64") {
            183u16
        } else {
            62u16
        };
        let module = synthetic_elf(
            1,
            machine,
            b"vermagic=6.6.42-android15-12-gabcdef SMP preempt\0",
        );
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        cpio.add(
            "init",
            CpioEntry::regular(0o755, Box::new(b"init".to_vec())),
        )
        .unwrap();
        cpio.add(
            "init.real",
            CpioEntry::regular(0o755, Box::new(b"other installer".to_vec())),
        )
        .unwrap();
        assert!(
            inject_runtime_payloads(
                &mut cpio,
                module,
                init,
                "android15-6.6",
                "6.6.42-android15-12-gabcdef",
                "kernel-image"
            )
            .is_err()
        );
    }

    #[test]
    fn injection_refuses_ramdisk_without_original_init() {
        let machine = if cfg!(target_arch = "aarch64") {
            183u16
        } else {
            62u16
        };
        let module = synthetic_elf(
            1,
            machine,
            b"vermagic=6.6.42-android15-12-gabcdef SMP preempt\0",
        );
        let init = synthetic_elf(3, machine, b"synthetic-init");
        let mut cpio = Cpio::new();
        assert!(
            inject_runtime_payloads(
                &mut cpio,
                module,
                init,
                "android15-6.6",
                "6.6.42-android15-12-gabcdef",
                "kernel-image"
            )
            .is_err()
        );
    }
}
