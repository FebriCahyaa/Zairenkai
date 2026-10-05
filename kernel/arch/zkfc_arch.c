// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC architecture identification.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "../zkfc.h"
#include "zkfc_arch.h"

u32 zkfc_arch_id(void)
{
	return ZKFC_ARCH_ID;
}

const char *zkfc_arch_name(void)
{
	return ZKFC_ARCH_NAME;
}
