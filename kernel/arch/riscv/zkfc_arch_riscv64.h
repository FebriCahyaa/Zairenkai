/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC riscv64 support.
 * RISC-V psABI: integer arguments in a0..a7.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_ARCH_RISCV64_H
#define _ZKFC_ARCH_RISCV64_H

#include <asm/ptrace.h>

#define ZKFC_ARCH_ID ZKFC_ARCH_RISCV64
#define ZKFC_ARCH_NAME "riscv64"

static inline unsigned long zkfc_regs_arg(struct pt_regs *regs, unsigned int n)
{
	switch (n) {
	case 0: return regs->a0;
	case 1: return regs->a1;
	case 2: return regs->a2;
	case 3: return regs->a3;
	case 4: return regs->a4;
	case 5: return regs->a5;
	case 6: return regs->a6;
	case 7: return regs->a7;
	default: return 0;
	}
}

#endif /* _ZKFC_ARCH_RISCV64_H */
