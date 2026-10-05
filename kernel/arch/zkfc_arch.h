/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC architecture dispatch: arm64, x86_64 and riscv64.
 *
 * Each architecture provides:
 *   ZKFC_ARCH_ID            enum zkfc_arch value
 *   ZKFC_ARCH_NAME          human readable name
 *   zkfc_regs_arg(regs, n)  n-th integer argument of a probed function
 *                           (n = 0..3), read from the kprobe pt_regs.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_ARCH_H
#define _ZKFC_ARCH_H

#if defined(CONFIG_ARM64)
#include "arm64/zkfc_arch_arm64.h"
#elif defined(CONFIG_X86_64)
#include "x86/zkfc_arch_x86_64.h"
#elif defined(CONFIG_RISCV) && defined(CONFIG_64BIT)
#include "riscv/zkfc_arch_riscv64.h"
#else
#define ZKFC_ARCH_ID ZKFC_ARCH_UNKNOWN
#define ZKFC_ARCH_NAME "unsupported"
#define ZKFC_ARCH_NO_KPROBE_ARGS 1
#endif

#endif /* _ZKFC_ARCH_H */
