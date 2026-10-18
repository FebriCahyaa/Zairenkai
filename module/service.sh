#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# late_start service: load ZKFC, restore trusted state, and start one zperfd.
# Copyright (C) 2026 FebriCahyaa
MODDIR=${0%/*}

# Validate the packaged source-of-truth before insmod or executing any payload.
# The release APK independently pins the complete module ZIP SHA-256.
verify_payload_manifest() {
  [ -s "$MODDIR/SHA256SUMS" ] || return 1
  # Extra/unlisted files are also rejected. Ignore only manager markers and
  # the per-device .ko staged by customize.sh after the archive was verified.
  expected_count="$(awk 'NF >= 2 { n++ } END { print n + 0 }' "$MODDIR/SHA256SUMS")"
  actual_count="$(find "$MODDIR" -type f \
    ! -path "$MODDIR/SHA256SUMS" \
    ! -path "$MODDIR/zkfc/zkfc.ko" \
    ! -path "$MODDIR/zkfc/target-kmi" \
    ! -path "$MODDIR/disable" ! -path "$MODDIR/remove" \
    ! -path "$MODDIR/update" ! -path "$MODDIR/auto_mount" \
    ! -path "$MODDIR/skip_mount" ! -path "$MODDIR/.core" \
    2>/dev/null | wc -l | tr -d '[:space:]')"
  [ "$expected_count" = "$actual_count" ] || return 1
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$MODDIR" && sha256sum -c SHA256SUMS >/dev/null 2>&1)
  elif command -v toybox >/dev/null 2>&1; then
    (cd "$MODDIR" && toybox sha256sum -c SHA256SUMS >/dev/null 2>&1)
  else
    return 1
  fi
}
if ! verify_payload_manifest; then
  log -t zairenkai "integrity verification failed: module payload hash mismatch or SHA-256 unavailable; refusing to start"
  exit 1
fi

until [ -d /dev ]; do sleep 1; done

# Load ZKFC only when the running release identifies an Android KMI branch.
# Linux major/minor family alone is not enough to safely choose among KMI builds.
KREL="$(uname -r)"
KERNEL_FAMILY="$(echo "$KREL" | cut -d- -f1 | awk -F. 'NF >= 2 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { print $1 "." $2 }')"
ANDROID_BRANCH="$(echo "$KREL" | grep -oE 'android[0-9]+' | head -1)"
KMI=""
if [ -n "$ANDROID_BRANCH" ] && [ -n "$KERNEL_FAMILY" ]; then KMI="$ANDROID_BRANCH-$KERNEL_FAMILY"; fi
# customize.sh records the explicitly selected KMI when uname lacks the branch
# marker. Validate the record against the allowlist and Linux family before use.
TARGET_KMI_FILE="$MODDIR/zkfc/target-kmi"
if [ -f "$TARGET_KMI_FILE" ]; then
  STORED_KMI=""
  IFS= read -r STORED_KMI < "$TARGET_KMI_FILE" || true
  case "$STORED_KMI" in
    android12-5.10|android13-5.10|android13-5.15|android14-5.15|android14-6.1|android15-6.6|android16-6.12|android17-6.18)
      STORED_KMI_FAMILY="${STORED_KMI#*-}"
      if [ "$STORED_KMI_FAMILY" != "$KERNEL_FAMILY" ]; then
        log -t zairenkai "stored target KMI $STORED_KMI conflicts with running Linux family ${KERNEL_FAMILY:-unknown}"
        KMI=""
      elif [ -n "$KMI" ] && [ "$KMI" != "$STORED_KMI" ]; then
        log -t zairenkai "stored target KMI $STORED_KMI conflicts with kernel-banner KMI $KMI"
        KMI=""
      else
        KMI="$STORED_KMI"
        log -t zairenkai "using installer-validated target KMI $KMI"
      fi
      ;;
    *)
      log -t zairenkai "invalid target KMI marker; refusing to load ZKFC"
      KMI=""
      ;;
  esac
fi
ARCH64="$(getprop ro.product.cpu.abi)"
case "$ARCH64" in
  arm64-v8a|arm64*) USER_ABI="arm64-v8a"; ABIDIR="arm64" ;;
  x86_64) USER_ABI="x86_64"; ABIDIR="x86_64" ;;
  *) USER_ABI="unsupported"; ABIDIR="unsupported" ;;
esac

module_release() {
  [ -f "$1" ] || return 0
  grep -a -o 'vermagic=[^[:space:]]*' "$1" 2>/dev/null | head -1 | sed 's/^vermagic=//'
}
module_family() {
  release="$(module_release "$1")"
  numeric="$(echo "$release" | cut -d- -f1)"
  echo "$numeric" | awk -F. 'NF >= 2 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { print $1 "." $2 }'
}
module_kmi() {
  release="$(module_release "$1")"
  branch="$(echo "$release" | grep -oE 'android[0-9]+' | head -1)"
  family="$(module_family "$1")"
  [ -n "$branch" ] && [ -n "$family" ] && echo "$branch-$family"
}

# customize.sh stages a preferred copy at zkfc/zkfc.ko for fast boot loading.
# That staging directory is intentionally outside SHA256SUMS; only accept the
# copy when its bytes match one of the checksummed lkm payloads.
verify_staged_ko() {
  staged="$1"
  source_path="$2"
  [ -f "$staged" ] || return 1
  expected="$(awk -v wanted="$source_path" '$2 == wanted { print $1; exit }' "$MODDIR/SHA256SUMS")"
  [ -n "$expected" ] || return 1
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$staged" 2>/dev/null | awk '{print $1}')"
  elif command -v toybox >/dev/null 2>&1; then
    actual="$(toybox sha256sum "$staged" 2>/dev/null | awk '{print $1}')"
  else
    return 1
  fi
  [ "$(echo "$actual" | tr 'A-F' 'a-f')" = "$(echo "$expected" | tr 'A-F' 'a-f')" ]
}

try_module() {
  candidate="$1"
  [ -f "$candidate" ] || return 1
  candidate_family="$(module_family "$candidate")"
  candidate_kmi="$(module_kmi "$candidate")"
  if [ -z "$KMI" ] || [ "$candidate_kmi" != "$KMI" ] || [ -z "$KERNEL_FAMILY" ] || [ "$candidate_family" != "$KERNEL_FAMILY" ]; then
    log -t zairenkai "skip module with KMI=${candidate_kmi:-unknown} family=${candidate_family:-unknown}: $candidate (kernel KMI=${KMI:-unknown} family=${KERNEL_FAMILY:-unknown})"
    return 1
  fi
  LOAD_LOG="$(insmod "$candidate" 2>&1)"
  LOAD_STATUS=$?
  if [ "$LOAD_STATUS" -ne 0 ]; then
    log -t zairenkai "insmod failed for $(basename "$(dirname "$candidate")"): ${LOAD_LOG:-no loader message}"
    return 1
  fi
  for _ in 1 2 3; do
    [ -e /dev/zkfc ] && break
    sleep 1
  done
  if [ -e /dev/zkfc ]; then
    log -t zairenkai "ZKFC endpoint available; loaded $(basename "$(dirname "$candidate")")"
    return 0
  fi
  # insmod returned success: do not load a second copy if init did not publish
  # the expected misc-device node; this points to a module/init implementation bug.
  log -t zairenkai "insmod returned success but /dev/zkfc stayed missing for $candidate"
  return 2
}

if [ ! -e /dev/zkfc ]; then
  if [ -z "$KMI" ]; then
    log -t zairenkai "kernel release does not expose an Android KMI branch ($KREL); refusing speculative same-family module loads"
  else
    selected="$MODDIR/zkfc/zkfc.ko"
    if [ -f "$selected" ] && verify_staged_ko "$selected" "./lkm/$KMI-$ABIDIR/zkfc.ko"; then
      selected_kmi="$(module_kmi "$selected")"
      if [ "$selected_kmi" = "$KMI" ] && [ "$(module_family "$selected")" = "$KERNEL_FAMILY" ]; then
        try_module "$selected"
      else
        log -t zairenkai "staged module metadata does not match running KMI=$KMI family=${KERNEL_FAMILY:-unknown}; refusing to load it"
      fi
    else
      log -t zairenkai "no integrity-verified staged module exists for exact KMI/ABI $KMI-$ABIDIR; refusing cross-KMI fallback"
    fi
  fi
fi
if [ -e /dev/zkfc ]; then
  log -t zairenkai "ZKFC endpoint available at /dev/zkfc"
else
  log -t zairenkai "ZKFC endpoint missing; kernel=$KREL family=${KERNEL_FAMILY:-unknown} abi=$ABIDIR. Review preceding insmod errors, KMI/symbol CRCs, module signature, and kernel log."
fi

# Compare copied userspace executables against their hashes in the verified
# module manifest. A corrupted /data/adb copy must never be executed at boot.
manifest_sha256_for() {
  [ -s "$MODDIR/SHA256SUMS" ] || return 1
  awk -v wanted="$1" '$2 == wanted { print $1; exit }' "$MODDIR/SHA256SUMS"
}
verify_runtime_binary() {
  binary="$1"
  manifest_path="$2"
  [ -x "$binary" ] || return 1
  expected="$(manifest_sha256_for "$manifest_path")"
  [ -n "$expected" ] || return 1
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$binary" 2>/dev/null | awk '{print $1}')"
  elif command -v toybox >/dev/null 2>&1; then
    actual="$(toybox sha256sum "$binary" 2>/dev/null | awk '{print $1}')"
  else
    return 1
  fi
  [ "$(echo "$expected" | tr 'A-F' 'a-f')" = "$(echo "$actual" | tr 'A-F' 'a-f')" ]
}
if [ "$USER_ABI" = "unsupported" ] || ! verify_runtime_binary /data/adb/zkfc/zkfctl "./bin/$USER_ABI/zkfctl"; then
  log -t zairenkai "installed zkfctl missing, unsupported ABI, or SHA-256 mismatch; refusing to execute it"
  exit 1
fi

# Tighten and restore the root-side license state before the performance engine starts.
STATE=/data/adb/zkfc
mkdir -p "$STATE"
chmod 0700 "$STATE" 2>/dev/null || true
[ -f "$STATE/zkfctl" ] && chmod 0755 "$STATE/zkfctl"
license_restore_failed=0
if [ -x "$STATE/zkfctl" ] && [ -f "$STATE/crl.zkcrl" ]; then
  if ! "$STATE/zkfctl" license crl "$STATE/crl.zkcrl" >/dev/null 2>&1; then
    log -t zairenkai "persistent CRL rejected; entering SAFE MODE"
    license_restore_failed=1
  fi
fi
if [ -x "$STATE/zkfctl" ] && [ -f "$STATE/token.zkl" ]; then
  if ! "$STATE/zkfctl" license install "$STATE/token.zkl" >/dev/null 2>&1; then
    log -t zairenkai "persistent API token rejected; performance stays locked"
  fi
fi
# A staged token is never trusted at boot. It is an interrupted hand-off and
# may contain sensitive or incomplete material, so discard it fail-closed.
rm -f "$STATE/token.pending"

# Boot fail-safe. Two consecutive boots without a healthy app confirmation put
# the device in SAFE MODE. zperfd also observes this marker and restores its
# baseline instead of applying a performance profile.
PENDING="$STATE/boot_pending"
SAFE="$STATE/safe_mode"
THRESHOLD=2
count=0
[ -f "$PENDING" ] && count=$(cat "$PENDING" 2>/dev/null | grep -o '^[0-9]*' | head -1)
[ -n "$count" ] || count=0

if [ "$license_restore_failed" -eq 1 ]; then
  : > "$SAFE"
  log -t zairenkai "SAFE MODE: license state could not be restored"
elif [ "$count" -ge "$THRESHOLD" ]; then
  : > "$SAFE"
  log -t zairenkai "SAFE MODE: $count unconfirmed boots; performance profile disabled"
else
  echo $((count + 1)) > "$PENDING"
  chmod 0600 "$PENDING" 2>/dev/null || true
  rm -f "$SAFE"
fi

# zperfd is the sole owner of performance profile application. Never start a
# second daemon instance when the service is re-triggered by the root manager.
ZPERF=/data/adb/zperf
ZBIN="$ZPERF/zperfd"
PIDFILE="$ZPERF/zperfd.pid"
mkdir -p "$ZPERF"
chmod 0700 "$ZPERF" 2>/dev/null || true
if [ "$license_restore_failed" -eq 0 ]; then
  if [ ! -x "$ZBIN" ]; then
    log -t zairenkai "zperfd not started: executable is missing or not executable at $ZBIN"
  elif [ ! -e /dev/zkfc ]; then
    log -t zairenkai "zperfd not started because /dev/zkfc is unavailable"
  elif ! verify_runtime_binary "$ZBIN" "./bin/$USER_ABI/zperfd"; then
    log -t zairenkai "zperfd missing, unsupported ABI, or SHA-256 mismatch; refusing to execute it"
  else
    running=0
    if [ -f "$PIDFILE" ]; then
      pid=$(cat "$PIDFILE" 2>/dev/null)
      case "$pid" in ''|*[!0-9]*) pid="" ;; esac
      if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null)"
        actual_cmd="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
        case "$actual_exe $actual_cmd" in *"$ZBIN"*) running=1 ;; esac
      fi
    fi
    if [ "$running" -eq 0 ]; then
      rm -f "$PIDFILE"
      LOGFILE="$ZPERF/zperfd.log"
      touch "$LOGFILE" 2>/dev/null || true
      chmod 0600 "$LOGFILE" 2>/dev/null || true
      "$ZBIN" daemon --state "$ZPERF" >>"$LOGFILE" 2>&1 &
      daemon_pid=$!
      sleep 2
      if kill -0 "$daemon_pid" 2>/dev/null; then
        actual_exe="$(readlink "/proc/$daemon_pid/exe" 2>/dev/null)"
        actual_cmd="$(tr '\000' ' ' < "/proc/$daemon_pid/cmdline" 2>/dev/null)"
        case "$actual_exe $actual_cmd" in
          *"$ZBIN"*)
            echo "$daemon_pid" > "$PIDFILE"
            chmod 0600 "$PIDFILE" 2>/dev/null || true
            log -t zairenkai "zperfd process alive after startup; log=$LOGFILE"
            ;;
          *)
            rm -f "$PIDFILE"
            log -t zairenkai "startup PID is not zperfd; no trusted PID was saved"
            ;;
        esac
      else
        wait "$daemon_pid" 2>/dev/null
        exit_status=$?
        rm -f "$PIDFILE"
        reason="$(tail -n 8 "$LOGFILE" 2>/dev/null | tr '\n' ';' | cut -c1-800)"
        log -t zairenkai "zperfd exited during startup (exit=$exit_status): $reason"
      fi
    fi
  fi
fi

# The app calls `zkfctl safe confirm` only after its stability window passes.
