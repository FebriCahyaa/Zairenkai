/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC arm64 (AArch64) support.
 * AAPCS64: integer arguments in x0..x7.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_ARCH_ARM64_H
#define _ZKFC_ARCH_ARM64_H

#include <asm/ptrace.h>

#define ZKFC_ARCH_ID ZKFC_ARCH_ARM64
#define ZKFC_ARCH_NAME "arm64"

static inline unsigned long zkfc_regs_arg(struct pt_regs *regs, unsigned int n)
{
	return n < 8 ? regs->regs[n] : 0;
}

#endif /* _ZKFC_ARCH_ARM64_H */
