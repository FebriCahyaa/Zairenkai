/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
/*
 * Zairenkai Kernel Framework Core (ZKFC) - userspace API.
 *
 * Every structure in this header has a fixed layout (no pointers). The supported
 * Android targets are 64-bit arm64/x86_64/riscv64; multi-byte fields of the
 * license token are little endian on the wire.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _UAPI_LINUX_ZKFC_H
#define _UAPI_LINUX_ZKFC_H

#include <linux/ioctl.h>
#include <linux/types.h>

#define ZKFC_DEVICE_NAME "zkfc"
#define ZKFC_DEVICE_PATH "/dev/zkfc"

/* ------------------------------------------------------------------------
 * API versioning
 *
 * MAJOR changes break the ABI (a manager built for another major must refuse
 * to talk to the kernel). MINOR adds ioctls/fields in a compatible way.
 * PATCH is for behaviour fixes. Userspace reports "ZKFC API outdated" when
 * the kernel version is lower than the version it requires.
 * ------------------------------------------------------------------------ */
#define ZKFC_API_MAJOR 1
#define ZKFC_API_MINOR 1
#define ZKFC_API_PATCH 0
#define ZKFC_MKVER(ma, mi, pa) ((((ma) & 0xff) << 16) | (((mi) & 0xff) << 8) | ((pa) & 0xff))
#define ZKFC_VER_MAJOR(v) (((v) >> 16) & 0xff)
#define ZKFC_VER_MINOR(v) (((v) >> 8) & 0xff)
#define ZKFC_VER_PATCH(v) ((v) & 0xff)
#define ZKFC_API_VERSION ZKFC_MKVER(ZKFC_API_MAJOR, ZKFC_API_MINOR, ZKFC_API_PATCH)
/* Oldest manager API this kernel still serves. */
#define ZKFC_API_MIN_SUPPORTED ZKFC_MKVER(1, 0, 0)

/* Kernel capability graph. These describe mechanisms, not tuning policy. */
#define ZKFC_KCAP_CPUFREQ             (1ULL << 0)
#define ZKFC_KCAP_DEVFREQ             (1ULL << 1)
#define ZKFC_KCAP_THERMAL             (1ULL << 2)
#define ZKFC_KCAP_CPU_IDLE            (1ULL << 3)
#define ZKFC_KCAP_AUTOGROUP           (1ULL << 4)
#define ZKFC_KCAP_CGROUPS             (1ULL << 5)
#define ZKFC_KCAP_PSI                 (1ULL << 6)
#define ZKFC_KCAP_ENERGY_MODEL        (1ULL << 7)
#define ZKFC_KCAP_FREQ_QOS             (1ULL << 8)
#define ZKFC_KCAP_UCLAMP              (1ULL << 9)
#define ZKFC_KCAP_SCHEDTUNE            (1ULL << 10)
#define ZKFC_KCAP_WALT                 (1ULL << 11)
#define ZKFC_KCAP_KPROBES              (1ULL << 12)
#define ZKFC_KCAP_MODULES              (1ULL << 13)
#define ZKFC_KCAP_DM_CRYPT             (1ULL << 14)
#define ZKFC_KCAP_BPF                  (1ULL << 15)
#define ZKFC_KCAP_HOOKS_ACTIVE         (1ULL << 16)
#define ZKFC_KCAP_THERMAL_GUARD_TRIPPED (1ULL << 17)
#define ZKFC_KCAP_LICENSE_VALID        (1ULL << 18)

struct zkfc_capability_info {
	__le64 kernel_caps;
	__le64 runtime_caps;
	__u32 kernel_major;
	__u32 kernel_minor;
	__u32 kernel_patch;
	__u32 cpu_count;
	__u32 page_size;
	__u32 kernel_type;
	__u32 hook_mode;
	__u32 reserved;
	char kernel_release[72];
	char build_id[48];
};

enum zkfc_arch {
	ZKFC_ARCH_UNKNOWN = 0,
	ZKFC_ARCH_ARM64 = 1,
	ZKFC_ARCH_X86_64 = 2,
	ZKFC_ARCH_RISCV64 = 3,
};

enum zkfc_hook_mode {
	ZKFC_HOOK_NONE = 0,	/* no hooks: core API only */
	ZKFC_HOOK_HYBRID = 1,	/* kprobes + standard kernel interfaces */
	ZKFC_HOOK_MANUAL = 2,	/* explicit calls patched into kernel source */
};

enum zkfc_kernel_type {
	ZKFC_KERNEL_NON_GKI = 0,
	ZKFC_KERNEL_GKI = 1,
};

/* Features compiled in and available at runtime. */
#define ZKFC_FEAT_INPUT_BOOST	(1U << 0)
#define ZKFC_FEAT_TASK_BOOST	(1U << 1)
#define ZKFC_FEAT_CPUFREQ_QOS	(1U << 2)
#define ZKFC_FEAT_THERMAL_GUARD	(1U << 3)
#define ZKFC_FEAT_SULOG		(1U << 4)
#define ZKFC_FEAT_INTEGRITY	(1U << 5)
#define ZKFC_FEAT_POLICY	(1U << 6)
#define ZKFC_FEAT_UCLAMP	(1U << 7)
#define ZKFC_FEAT_KPROBES	(1U << 8)
#define ZKFC_FEAT_BOOST_INHERIT	(1U << 9)
#define ZKFC_FEAT_ALL		0x3ffU

/* Features that require a valid ZKFC API Token. */
#define ZKFC_FEAT_LICENSED	(ZKFC_FEAT_INPUT_BOOST | ZKFC_FEAT_TASK_BOOST | \
				 ZKFC_FEAT_CPUFREQ_QOS | ZKFC_FEAT_THERMAL_GUARD | \
				 ZKFC_FEAT_BOOST_INHERIT)

/* ------------------------------------------------------------------------
 * ZKFC API Token (license)
 *
 * The token payload is signed with the Owner's Ed25519 key. The public key
 * is compiled into the kernel and into the official manager. Tokens are
 * issued only by the Owner (see docs/security/API_TOKENS.md).
 * ------------------------------------------------------------------------ */
#define ZKFC_LICENSE_MAGIC	0x434c4b5aU	/* "ZKLC" */
#define ZKFC_CRL_MAGIC		0x4c524b5aU	/* "ZKRL" */
#define ZKFC_LICENSE_FORMAT	1
#define ZKFC_SIG_SIZE		64
#define ZKFC_BINDING_SIZE	32
#define ZKFC_LICENSEE_SIZE	64
#define ZKFC_CRL_MAX_IDS	64
/* Domain separation for the kernel binding hash. */
#define ZKFC_BINDING_PREFIX	"ZKFC-BIND-v1:"

/* Token flags */
#define ZKFC_LICF_DEVELOPER	(1U << 0)	/* developer build, verbose logs */
#define ZKFC_LICF_COMMERCIAL	(1U << 1)	/* commercial redistribution */
#define ZKFC_LICF_OWNER		(1U << 2)	/* Owner's own kernels */

struct zkfc_license_payload {
	__le32 magic;			/* ZKFC_LICENSE_MAGIC */
	__le16 format;			/* ZKFC_LICENSE_FORMAT */
	__le16 api_major;		/* API major the token is valid for */
	__le64 license_id;		/* unique, used for revocation */
	__le64 issued_at;		/* unix seconds */
	__le64 expires_at;		/* unix seconds, 0 = never */
	__le32 features;		/* granted ZKFC_FEAT_* bits */
	__le32 flags;			/* ZKFC_LICF_* */
	__u8 binding[ZKFC_BINDING_SIZE];	/* SHA-512(prefix|tag)[0..31], 0 = any */
	char licensee[ZKFC_LICENSEE_SIZE];	/* NUL padded, informational */
};

struct zkfc_license_token {
	struct zkfc_license_payload payload;
	__u8 signature[ZKFC_SIG_SIZE];	/* Ed25519 over payload */
};

/* Certificate revocation list, signed by the Owner. */
struct zkfc_crl_payload {
	__le32 magic;			/* ZKFC_CRL_MAGIC */
	__le16 format;
	__le16 count;			/* number of valid ids */
	__le64 serial;			/* monotonically increasing */
	__le64 issued_at;
	__le64 revoked_ids[ZKFC_CRL_MAX_IDS];
};

struct zkfc_crl {
	struct zkfc_crl_payload payload;
	__u8 signature[ZKFC_SIG_SIZE];
};

enum zkfc_license_state {
	ZKFC_LIC_MISSING = 0,		/* no token installed */
	ZKFC_LIC_VALID = 1,
	ZKFC_LIC_BAD_SIGNATURE = 2,
	ZKFC_LIC_EXPIRED = 3,
	ZKFC_LIC_WRONG_BINDING = 4,	/* token issued for another kernel */
	ZKFC_LIC_API_OUTDATED = 5,	/* token api_major < kernel api major */
	ZKFC_LIC_MALFORMED = 6,
	ZKFC_LIC_NO_OWNER_KEY = 7,	/* kernel built without the Owner key */
	ZKFC_LIC_REVOKED = 8,
	ZKFC_LIC_API_INCOMPATIBLE = 9,	/* token targets another API major */
};

/* ------------------------------------------------------------------------
 * Access policy: UID / GID / supplementary group -> capabilities
 * ------------------------------------------------------------------------ */
#define ZKFC_CAP_READ_INFO	(1U << 0)	/* version, security reports */
#define ZKFC_CAP_READ_LOG	(1U << 1)	/* log and sulog */
#define ZKFC_CAP_TUNE_PERF	(1U << 2)	/* boosts, cpufreq QoS */
#define ZKFC_CAP_TUNE_THERMAL	(1U << 3)	/* thermal guard */
#define ZKFC_CAP_LICENSE	(1U << 4)	/* install tokens / CRL */
#define ZKFC_CAP_ADMIN		(1U << 5)	/* policy, log level, reset */
#define ZKFC_CAP_ALL		0x3fU

enum zkfc_policy_type {
	ZKFC_POLICY_UID = 1,		/* matches the caller's real/effective uid */
	ZKFC_POLICY_GID = 2,		/* matches the caller's primary gid */
	ZKFC_POLICY_GROUP = 3,		/* matches any supplementary group */
};

#define ZKFC_POLICY_MAX		32
#define ZKFC_POLICY_F_DENY	(1U << 0)	/* explicit deny entry */
#define ZKFC_POLICY_F_BUILTIN	(1U << 1)	/* set by the kernel, read-only */

struct zkfc_policy_entry {
	__u32 type;			/* enum zkfc_policy_type */
	__u32 id;			/* uid or gid */
	__u32 caps;			/* ZKFC_CAP_* */
	__u32 flags;			/* ZKFC_POLICY_F_* */
};

struct zkfc_policy_table {
	__u32 count;
	__u32 reserved;
	struct zkfc_policy_entry entries[ZKFC_POLICY_MAX];
};

/* ------------------------------------------------------------------------
 * Information / security reports
 * ------------------------------------------------------------------------ */
struct zkfc_version_info {
	__u32 api_version;
	__u32 api_min_supported;
	__u32 arch;			/* enum zkfc_arch */
	__u32 hook_mode;		/* enum zkfc_hook_mode */
	__u32 kernel_type;		/* enum zkfc_kernel_type */
	__u32 features;			/* ZKFC_FEAT_* available */
	__u32 features_enabled;		/* available & licensed */
	__u32 license_state;		/* enum zkfc_license_state */
	__u32 kernel_version;		/* LINUX_VERSION_CODE */
	__u32 reserved;
	char build_id[48];		/* ZKFC git/build identifier */
	char kernel_release[72];	/* utsname()->release */
};

struct zkfc_license_status {
	__u32 state;			/* enum zkfc_license_state */
	__u32 has_token;
	__u32 owner_key_provisioned;
	__u32 clock_trusted;		/* wall clock looked sane at check */
	__le64 crl_serial;
	__u8 owner_key_fingerprint[32];	/* SHA-512(pubkey)[0..31] */
	__u8 kernel_binding[ZKFC_BINDING_SIZE];
	struct zkfc_license_token token;
};

/* System security: kernel integrity hints. */
#define ZKFC_SYS_MODULE_SIG_ENFORCED	(1U << 0)
#define ZKFC_SYS_LOCKDOWN_INTEGRITY	(1U << 1)
#define ZKFC_SYS_LOCKDOWN_CONFIDENTIAL	(1U << 2)
#define ZKFC_SYS_KPROBES_ENABLED	(1U << 3)
#define ZKFC_SYS_STRICT_KERNEL_RWX	(1U << 4)
#define ZKFC_SYS_STACKPROTECTOR		(1U << 5)
#define ZKFC_SYS_CFI			(1U << 6)
#define ZKFC_SYS_SCS			(1U << 7)	/* shadow call stack */
#define ZKFC_SYS_KASLR			(1U << 8)
#define ZKFC_SYS_SECURITY_SELINUX	(1U << 9)

struct zkfc_sys_security {
	__u32 flags;			/* ZKFC_SYS_* */
	__u32 reserved;
	__u64 taint_mask;		/* bit n = TAINT n */
	__u64 uptime_ns;
	__u8 zkfc_config_digest[32];	/* SHA-512 of ZKFC security config */
};

/* User security: what the caller is allowed to do. */
struct zkfc_user_security {
	__u32 uid;
	__u32 euid;
	__u32 gid;
	__u32 egid;
	__u32 caps;			/* effective ZKFC_CAP_* */
	__u32 matched_type;		/* policy entry that granted caps */
	__u32 matched_id;
	__u32 cap_sys_admin;		/* caller has CAP_SYS_ADMIN */
	__u64 session_id;		/* per open() */
	__u64 session_ops;
};

/* Device security: identity of the running kernel/device. */
struct zkfc_dev_security {
	__u8 kernel_binding[ZKFC_BINDING_SIZE];
	char model[64];			/* DT model or DMI product name */
	char compatible[64];		/* DT root compatible / DMI vendor */
	char licensee_tag[64];		/* CONFIG_ZKFC_LICENSEE_TAG */
	__u32 cpu_count;
	__u32 reserved;
};

/* ------------------------------------------------------------------------
 * Performance
 * ------------------------------------------------------------------------ */
#define ZKFC_UCLAMP_SCALE	1024

#define ZKFC_TB_THREADS		(1U << 0)	/* apply to every thread */
#define ZKFC_TB_INHERIT		(1U << 1)	/* new threads inherit */
#define ZKFC_TB_RESET		(1U << 2)	/* remove the boost */

struct zkfc_task_boost {
	__s32 pid;			/* tgid or tid */
	__u32 uclamp_min;		/* 0..1024 */
	__u32 uclamp_max;		/* 0..1024 */
	__u32 flags;			/* ZKFC_TB_* */
	__u32 applied;			/* out: threads updated */
	__u32 reserved;
};

#define ZKFC_CQ_CLEAR		(1U << 0)

struct zkfc_cpufreq_qos {
	__u32 cpu;			/* any CPU of the policy */
	__u32 min_khz;			/* 0 = leave unchanged */
	__u32 max_khz;			/* 0 = leave unchanged */
	__u32 flags;			/* ZKFC_CQ_* */
};

#define ZKFC_MAX_CLUSTERS	8

struct zkfc_input_boost {
	__u32 enabled;
	__u32 duration_ms;		/* 10..5000 */
	__u32 cluster_count;
	__u32 reserved;
	__u32 cluster_cpu[ZKFC_MAX_CLUSTERS];	/* first CPU of each cluster */
	__u32 min_khz[ZKFC_MAX_CLUSTERS];	/* boost floor per cluster */
};

#define ZKFC_THERMAL_ZONES	4
#define ZKFC_THERMAL_NAME	32
/* Hard safety limits that userspace can never exceed. */
#define ZKFC_THERMAL_LIMIT_MAX_MDEG	95000
#define ZKFC_THERMAL_LIMIT_MIN_MDEG	35000

struct zkfc_thermal_guard {
	__u32 enabled;
	__u32 interval_ms;		/* 100..10000 */
	__s32 limit_mdeg;		/* trip: drop every boost */
	__s32 release_mdeg;		/* resume boosts below this */
	__u32 zone_count;
	__u32 reserved;
	char zones[ZKFC_THERMAL_ZONES][ZKFC_THERMAL_NAME];
};

struct zkfc_thermal_read {
	char zone[ZKFC_THERMAL_NAME];	/* in */
	__s32 temp_mdeg;		/* out */
	__s32 result;			/* out: 0 or -errno */
};

struct zkfc_perf_status {
	__u32 input_boost_enabled;
	__u32 input_boost_active;
	__u64 input_boost_count;
	__u32 boosted_tasks;
	__u32 inherit_groups;
	__u32 cpufreq_requests;
	__u32 thermal_guard_enabled;
	__u32 thermal_tripped;
	__s32 thermal_last_mdeg;
	__u64 thermal_trip_count;
};

/* ------------------------------------------------------------------------
 * Logging and sulog
 * ------------------------------------------------------------------------ */
enum zkfc_log_level {
	ZKFC_LOG_VERBOSE = 0,
	ZKFC_LOG_DEBUG = 1,
	ZKFC_LOG_INFO = 2,
	ZKFC_LOG_WARN = 3,
	ZKFC_LOG_ERROR = 4,
	ZKFC_LOG_SILENT = 5,
};

#define ZKFC_LOG_MSG_SIZE	96
#define ZKFC_LOG_BATCH		16

struct zkfc_log_record {
	__u64 seq;
	__u64 ts_ns;			/* boot time */
	__u32 level;
	__u32 pid;
	__u32 uid;
	__u32 reserved;
	char msg[ZKFC_LOG_MSG_SIZE];
};

struct zkfc_log_read {
	__u64 from_seq;			/* in: first sequence wanted */
	__u64 next_seq;			/* out: pass back as from_seq */
	__u32 count;			/* out: records filled */
	__u32 dropped;			/* out: records lost before from_seq */
	struct zkfc_log_record records[ZKFC_LOG_BATCH];
};

enum zkfc_sulog_event {
	ZKFC_SU_EXEC = 1,		/* an "su" binary was executed */
	ZKFC_SU_PRIV_OP = 2,		/* a privileged ZKFC ioctl succeeded */
	ZKFC_SU_DENIED = 3,		/* ZKFC access denied by policy */
	ZKFC_SU_LICENSE = 4,		/* token / CRL installed */
};

#define ZKFC_SULOG_PATH_SIZE	64

struct zkfc_sulog_record {
	__u64 seq;
	__u64 ts_ns;
	__u32 event;			/* enum zkfc_sulog_event */
	__u32 uid;
	__u32 pid;
	__u32 ppid;
	__s32 result;
	__u32 detail;			/* ioctl nr, caps, ... */
	char comm[16];
	char path[ZKFC_SULOG_PATH_SIZE];
};

struct zkfc_sulog_read {
	__u64 from_seq;
	__u64 next_seq;
	__u32 count;
	__u32 dropped;
	struct zkfc_sulog_record records[ZKFC_LOG_BATCH];
};

/* ------------------------------------------------------------------------
 * ioctl numbers
 * ------------------------------------------------------------------------ */
#define ZKFC_IOC_MAGIC 'Z'

/* Information & security (ZKFC_CAP_READ_INFO) */
#define ZKFC_IOC_GET_VERSION		_IOR(ZKFC_IOC_MAGIC, 0x00, struct zkfc_version_info)
#define ZKFC_IOC_GET_LICENSE		_IOR(ZKFC_IOC_MAGIC, 0x01, struct zkfc_license_status)
#define ZKFC_IOC_GET_SYS_SECURITY	_IOR(ZKFC_IOC_MAGIC, 0x02, struct zkfc_sys_security)
#define ZKFC_IOC_GET_USER_SECURITY	_IOR(ZKFC_IOC_MAGIC, 0x03, struct zkfc_user_security)
#define ZKFC_IOC_GET_DEV_SECURITY	_IOR(ZKFC_IOC_MAGIC, 0x04, struct zkfc_dev_security)
#define ZKFC_IOC_GET_CAPABILITIES		_IOR(ZKFC_IOC_MAGIC, 0x05, struct zkfc_capability_info)

/* License management (ZKFC_CAP_LICENSE) */
#define ZKFC_IOC_INSTALL_LICENSE	_IOW(ZKFC_IOC_MAGIC, 0x08, struct zkfc_license_token)
#define ZKFC_IOC_INSTALL_CRL		_IOW(ZKFC_IOC_MAGIC, 0x09, struct zkfc_crl)

/* Performance (ZKFC_CAP_TUNE_PERF / ZKFC_CAP_TUNE_THERMAL, licensed) */
#define ZKFC_IOC_TASK_BOOST		_IOWR(ZKFC_IOC_MAGIC, 0x10, struct zkfc_task_boost)
#define ZKFC_IOC_CPUFREQ_QOS		_IOW(ZKFC_IOC_MAGIC, 0x11, struct zkfc_cpufreq_qos)
#define ZKFC_IOC_INPUT_BOOST		_IOW(ZKFC_IOC_MAGIC, 0x12, struct zkfc_input_boost)
#define ZKFC_IOC_THERMAL_GUARD		_IOW(ZKFC_IOC_MAGIC, 0x13, struct zkfc_thermal_guard)
#define ZKFC_IOC_THERMAL_READ		_IOWR(ZKFC_IOC_MAGIC, 0x14, struct zkfc_thermal_read)
#define ZKFC_IOC_PERF_STATUS		_IOR(ZKFC_IOC_MAGIC, 0x15, struct zkfc_perf_status)
#define ZKFC_IOC_PERF_RESET		_IO(ZKFC_IOC_MAGIC, 0x16)

/* Logs (ZKFC_CAP_READ_LOG; level needs ZKFC_CAP_ADMIN) */
#define ZKFC_IOC_LOG_SET_LEVEL		_IOW(ZKFC_IOC_MAGIC, 0x20, __u32)
#define ZKFC_IOC_LOG_READ		_IOWR(ZKFC_IOC_MAGIC, 0x21, struct zkfc_log_read)
#define ZKFC_IOC_SULOG_READ		_IOWR(ZKFC_IOC_MAGIC, 0x22, struct zkfc_sulog_read)

/* Policy (read: ZKFC_CAP_READ_INFO, write: ZKFC_CAP_ADMIN + CAP_SYS_ADMIN) */
#define ZKFC_IOC_POLICY_GET		_IOR(ZKFC_IOC_MAGIC, 0x30, struct zkfc_policy_table)
#define ZKFC_IOC_POLICY_SET		_IOW(ZKFC_IOC_MAGIC, 0x31, struct zkfc_policy_table)

#endif /* _UAPI_LINUX_ZKFC_H */
