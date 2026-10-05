<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai userspace engine

`zkfctl` is the root-side engine the Android app drives. The app runs
`zkfctl <command>` through its root solution (Magisk / KernelSU / APatch) and
parses the single JSON object printed on stdout. On error it prints
`{"ok":false,"error":"...","errno":N}` and exits non-zero.

```
include/libzkfc.h   public C API over /dev/zkfc (ioctls, token parsing)
lib/libzkfc.c       ioctl wrappers + enum→string helpers
lib/zk_token.c      decode armored .zkl / .zkcrl token files
lib/zk_sysfs.c      sysfs/procfs IO, confined to kernel tunable roots
lib/zk_tweaks.c     device optimization catalog (27 tunables, multi-SoC)
lib/zk_json.c       streaming JSON writer
zkfctl/main.c       CLI dispatch
```

## Commands

| Command | Purpose |
|---|---|
| `info` | API version, arch, hook mode, kernel type, license state |
| `license [install F\|crl F]` | show / install a ZKFC API token or CRL |
| `security [sys\|user\|dev]` | integrity, caller UID/GID/caps, device identity |
| `tweak list\|get ID\|set ID V` | enumerate / read / apply optimizations |
| `boost task PID MIN MAX [inherit]` | per-task uclamp boost (needs token) |
| `boost status\|reset` | ZKFC performance state |
| `monitor [ms]` | one-shot per-core CPU load+freq, GPU, thermal sample |
| `log [level L\|FROM]` | live kernel log level / ring read |
| `sulog [FROM]` | su / privileged-op audit ring |
| `policy` | access policy table |

`--lite` before the command hides heavy tweaks (device mitigation / lite mode).

The tweak catalog is data-driven: each entry lists candidate sysfs paths and
the first present on the device is used, so one binary covers Snapdragon,
MediaTek, Exynos, Tensor and Kirin. `cpu_governor` and `io_scheduler` fan out
to every cpufreq policy and block device.

## Build

```sh
userspace/build.sh host                       # local test binary
ANDROID_NDK=$NDK userspace/build.sh android    # arm64-v8a + x86_64, stripped
# or CMake (also used by the app's externalNativeBuild):
cmake -S userspace -B build && cmake --build build
```

Only the information and tweak commands run without a token; boost/thermal
commands return `errno 129` (`EKEYREJECTED`) until a valid ZKFC API Token is
installed.
