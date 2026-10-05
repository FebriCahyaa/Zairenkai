/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC x86_64 support (Android-x86, Chromebooks, emulators).
 * System V AMD64 ABI: integer arguments in rdi, rsi, rdx, rcx, r8, r9.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_ARCH_X86_64_H
#define _ZKFC_ARCH_X86_64_H

#include <asm/ptrace.h>

#define ZKFC_ARCH_ID ZKFC_ARCH_X86_64
#define ZKFC_ARCH_NAME "x86_64"

static inline unsigned long zkfc_regs_arg(struct pt_regs *regs, unsigned int n)
{
	switch (n) {
	case 0: return regs->di;
	case 1: return regs->si;
	case 2: return regs->dx;
	case 3: return regs->cx;
	case 4: return regs->r8;
	case 5: return regs->r9;
	default: return 0;
	}
}

#endif /* _ZKFC_ARCH_X86_64_H */
