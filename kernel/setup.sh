#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# Integrate the Zairenkai Kernel Framework Core (ZKFC) into a kernel tree.
# Copyright (C) 2026 FebriCahyaa
#
# Run from the kernel source root (or from the root of a GKI "common/" build):
#
#   curl -LSs https://raw.githubusercontent.com/FebriCahyaa/Zairenkai/main/kernel/setup.sh | bash -s main
#   bash Zairenkai/kernel/setup.sh [<git-ref>]
#   bash Zairenkai/kernel/setup.sh --cleanup
#
set -euo pipefail

REPO_URL="${ZKFC_REPO_URL:-https://github.com/FebriCahyaa/Zairenkai}"
ROOT="$(pwd)"

info() { printf '\033[1;36m[zkfc]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[zkfc]\033[0m %s\n' "$*" >&2; exit 1; }

if [ -d "$ROOT/common/drivers" ]; then
	DRIVERS="$ROOT/common/drivers"
elif [ -d "$ROOT/drivers" ]; then
	DRIVERS="$ROOT/drivers"
else
	die "run this script from the kernel source root (no drivers/ found)"
fi
KROOT="$(dirname "$DRIVERS")"

cleanup() {
	info "removing ZKFC from $DRIVERS"
	rm -f "$DRIVERS/zkfc"
	sed -i '/obj-\$(CONFIG_ZKFC)/d' "$DRIVERS/Makefile"
	sed -i '/source "drivers\/zkfc\/Kconfig"/d' "$DRIVERS/Kconfig"
	info "done (the Zairenkai checkout was kept)"
}

if [ "${1:-}" = "--cleanup" ]; then
	cleanup
	exit 0
fi

REF="${1:-}"
if [ -d "$ROOT/Zairenkai/.git" ]; then
	info "using existing checkout $ROOT/Zairenkai"
	if [ -n "$REF" ]; then
		git -C "$ROOT/Zairenkai" fetch --tags origin "$REF"
		git -C "$ROOT/Zairenkai" checkout "$REF"
	fi
elif [ -d "$ROOT/Zairenkai/kernel" ]; then
	info "using existing directory $ROOT/Zairenkai"
else
	info "cloning $REPO_URL"
	git clone --depth 1 ${REF:+--branch "$REF"} "$REPO_URL" "$ROOT/Zairenkai"
fi

[ -f "$ROOT/Zairenkai/kernel/Kconfig" ] || die "Zairenkai/kernel/Kconfig not found"

# Relative symlink so the tree stays relocatable.
REL="$(realpath --relative-to="$DRIVERS" "$ROOT/Zairenkai/kernel")"
ln -sfn "$REL" "$DRIVERS/zkfc"
info "linked $DRIVERS/zkfc -> $REL"

grep -q 'obj-$(CONFIG_ZKFC)' "$DRIVERS/Makefile" ||
	printf '\nobj-$(CONFIG_ZKFC)\t\t+= zkfc/\n' >> "$DRIVERS/Makefile"

if ! grep -q 'source "drivers/zkfc/Kconfig"' "$DRIVERS/Kconfig"; then
	# Insert before the final "endmenu".
	sed -i '$ {/^endmenu/i source "drivers/zkfc/Kconfig"
}' "$DRIVERS/Kconfig"
	grep -q 'source "drivers/zkfc/Kconfig"' "$DRIVERS/Kconfig" ||
		printf '\nsource "drivers/zkfc/Kconfig"\n' >> "$DRIVERS/Kconfig"
fi

KVER="$(make -s -C "$KROOT" kernelversion 2>/dev/null || echo unknown)"
cat <<EOF

ZKFC integrated into $KROOT (Linux $KVER).

Next steps
  1. Enable it in your defconfig:
       CONFIG_ZKFC=y                  # or =m for a GKI vendor module
       CONFIG_ZKFC_HOOK_HYBRID=y      # needs CONFIG_KPROBES=y
       # CONFIG_ZKFC_HOOK_MANUAL=y    # kernels without kprobes, see
       #                              # Zairenkai/kernel/hooks/manual/README.md
       CONFIG_ZKFC_LICENSEE_TAG="<tag from your ZKFC API Token>"
  2. Optional: copy zkfc_license.inc into Zairenkai/kernel/license/.
  3. Build as usual. Check dmesg for "zkfc: ZKFC API".

ZKFC API Tokens are issued only by the Zairenkai owner. See
Zairenkai/docs/security/API_TOKENS.md.
EOF
