# SPDX-License-Identifier: GPL-2.0-only
#
# Zairenkai Kernel Framework Core (ZKFC)
# Copyright (C) 2026 FebriCahyaa
#
# In-tree:      obj-$(CONFIG_ZKFC) += zkfc/   (see setup.sh)
# Out-of-tree:  make -C <kernel-out> M=$(pwd) CONFIG_ZKFC=m
#

obj-$(CONFIG_ZKFC) += zkfc.o

zkfc-y := core/zkfc_main.o \
	  core/zkfc_cap.o \
	  core/zkfc_log.o \
	  core/zkfc_policy.o \
	  core/zkfc_notify.o \
	  hooks/zkfc_hooks.o \
	  hooks/zkfc_kprobes.o \
	  hooks/zkfc_sulog.o \
	  security/zkfc_license.o \
	  security/zkfc_integrity.o \
	  perf/zkfc_task_boost.o \
	  perf/zkfc_cpufreq.o \
	  perf/zkfc_input_boost.o \
	  thermal/zkfc_thermal.o \
	  arch/zkfc_arch.o \
	  crypto/zk_sha512.o \
	  crypto/zk_ed25519.o

ZKFC_SRC := $(if $(filter /%,$(src)),$(src),$(srctree)/$(src))

# Out-of-tree builds have no Kconfig: default to hybrid hooks.
ifeq ($(CONFIG_ZKFC_HOOK_HYBRID)$(CONFIG_ZKFC_HOOK_MANUAL)$(CONFIG_ZKFC_HOOK_NONE),)
ccflags-y += -DCONFIG_ZKFC_HOOK_HYBRID=1
endif

# Out-of-tree builds can set the binding tag: make ZKFC_TAG=my-kernel
ifneq ($(ZKFC_TAG),)
ccflags-y += -DCONFIG_ZKFC_LICENSEE_TAG=\"$(ZKFC_TAG)\"
endif

ZKFC_GIT := $(shell git -C $(ZKFC_SRC) describe --tags --always --dirty 2>/dev/null)
ccflags-y += -DZKFC_BUILD_ID=\"zkfc-$(if $(ZKFC_GIT),$(ZKFC_GIT),local)\"

# Optional build-time token / revocation list issued by the Owner.
ifneq ($(wildcard $(ZKFC_SRC)/license/zkfc_license.inc),)
ccflags-y += -DZKFC_EMBEDDED_LICENSE
endif
ifneq ($(wildcard $(ZKFC_SRC)/license/zkfc_crl.inc),)
ccflags-y += -DZKFC_EMBEDDED_CRL
endif

# Out-of-tree convenience targets.
KDIR ?= /lib/modules/$(shell uname -r)/build

modules:
	$(MAKE) -C $(KDIR) M=$(CURDIR) CONFIG_ZKFC=m modules

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) clean

.PHONY: modules clean
