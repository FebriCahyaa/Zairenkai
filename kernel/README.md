<!-- SPDX-License-Identifier: GPL-2.0-only -->
# Zairenkai Kernel Framework Core (ZKFC)

ZKFC is the kernel half of Zairenkai. It is a Linux driver that exposes a
single character device, `/dev/zkfc`, through which the Zairenkai userspace
engine (`zkfcd`/`zkfctl`) and the Android app drive performance, thermal and
security features. Performance features are unlocked by an Ed25519-signed
**ZKFC API Token** issued by the project owner.

## Layering (lib -> driver -> UAPI)

```
                 Android app  (Kotlin / JNI)
                        |  ioctl(/dev/zkfc)
                 zkfcd / zkfctl               userspace engine (root)
                        |
  --------------------- /dev/zkfc -------------------- UAPI boundary
                        |                include/uapi/linux/zkfc.h
   core/     zkfc_main.c   char device, ioctl dispatch, per-call access check
             zkfc_log.c    leveled log ring buffer
             zkfc_policy.c UID / GID / supplementary-group -> capability policy
   security/ zkfc_license.c   ZKFC API Token + revocation-list verification
             zkfc_integrity.c system / device integrity reports
   perf/     zkfc_task_boost.c  per-task uclamp boost (+ thread inheritance)
             zkfc_cpufreq.c     cpufreq QoS (freq_qos / policy notifier)
             zkfc_input_boost.c touch/key driven short cpufreq floor
   thermal/  zkfc_thermal.c     thermal guard that suspends ZKFC boosts
   hooks/    zkfc_hooks.c       hook dispatch (hybrid / manual / none)
             zkfc_kprobes.c     hybrid hooks (kprobes, no source patch)
             zkfc_sulog.c       su / privileged-op audit ring
             manual/            patches for kernels without kprobes
   arch/     zkfc_arch*.{c,h}   arm64 / x86_64 / riscv64 register ABI
   crypto/   zk_ed25519.c       verify-only Ed25519 (RFC 8032)
             zk_sha512.c        streaming SHA-512 (FIPS 180-4)
```

`crypto/` is a self-contained **lib** (no kernel API beyond `linux/types.h`);
it is shared verbatim with the host test in `tests/crypto/` and with the owner
token tool, so the exact bytes that verify a token in the kernel are the bytes
that signed it. Everything above `/dev/zkfc` is the **driver**; everything
below the dashed line in userspace talks only through the stable UAPI header.

## Supported kernels

| Type    | Versions                          | How ZKFC ships            | Hooks           |
|---------|-----------------------------------|---------------------------|-----------------|
| GKI     | android12-5.10 ... android17-6.18 | vendor module (`.ko`, =m) | hybrid (kprobes)|
| non-GKI | 4.14 ... 6.x vendor kernels        | built-in (=y) or module   | hybrid or manual|

GKI branches are listed in [`gki/kmi_matrix.txt`](gki/kmi_matrix.txt). Because
a GKI `.ko` is built against a branch's KMI, one module per `(branch, arch)`
loads on every device shipping that branch.

### Build a GKI vendor module (LKM)

```sh
# Against an already-built GKI tree (fastest):
kernel/gki/build_lkm.sh android15-6.6 --arch arm64 --kernel-dir <gki-out>

# Or fetch + build the AOSP common kernel once, then the module:
kernel/gki/build_lkm.sh all --arch arm64
```

Output: `out/lkm/<branch>-<arch>/zkfc.ko`.

### Build for a non-GKI kernel

```sh
# Built-in (recommended): integrate into the kernel tree, then build the kernel
kernel/nongki/build.sh --kernel-dir <vendor-kernel> --arch arm64
scripts/kconfig/merge_config.sh -m arch/arm64/configs/vendor_defconfig \
    kernel/nongki/zkfc_nongki.fragment

# Module against a pre-built non-GKI tree
kernel/nongki/build.sh --kernel-dir <vendor-kernel> --arch arm64 --module
```

### Build in-tree (any kernel)

```sh
cd <kernel-source>
bash /path/to/Zairenkai/kernel/setup.sh        # links drivers/zkfc + Kconfig
# then CONFIG_ZKFC=y (or =m) in your defconfig
```

## Hook modes

- **Hybrid** - kprobes on `security_bprm_check` (su audit) and
  `wake_up_new_task` (boost inheritance). No kernel source changes. Default.
- **Manual** - the kernel source calls ZKFC directly; for kernels without
  kprobes or trees not maintained by Zairenkai. See
  [`hooks/manual/README.md`](hooks/manual/README.md).
- **None** - information and tuning API only.

## API tokens

Performance features require a ZKFC API Token. Tokens are issued **only** by
the project owner, individually, on request. If a kernel's embedded API major
is older than the token's, the app reports *"API ZKFC usang - perbarui kernel"*
(`ZKFC_LIC_API_OUTDATED`). See [`../docs/security/API_TOKENS.md`](../docs/security/API_TOKENS.md).
