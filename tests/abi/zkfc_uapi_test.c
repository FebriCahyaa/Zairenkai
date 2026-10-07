/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
#include <stddef.h>
#include <stdio.h>
#include "../../kernel/include/uapi/linux/zkfc.h"

#define EXPECT_SIZE(type, value) \
    do { \
        if (sizeof(type) != (value)) { \
            fprintf(stderr, "ABI size mismatch: %s=%zu expected=%u\n", #type, sizeof(type), (value)); \
            return 1; \
        } \
    } while (0)

#define EXPECT_OFFSET(type, field, value) \
    do { \
        if (offsetof(type, field) != (value)) { \
            fprintf(stderr, "ABI offset mismatch: %s.%s=%zu expected=%u\n", #type, #field, offsetof(type, field), (value)); \
            return 1; \
        } \
    } while (0)

static int check_ioctl(unsigned long actual, unsigned long expected, const char *name)
{
    if (actual != expected) {
        fprintf(stderr, "ioctl mismatch: %s=0x%lx expected=0x%lx\n", name, actual, expected);
        return 1;
    }
    return 0;
}

int main(void)
{
    EXPECT_SIZE(struct zkfc_capability_info, 168);
    EXPECT_SIZE(struct zkfc_version_info, 160);
    EXPECT_SIZE(struct zkfc_license_payload, 136);
    EXPECT_SIZE(struct zkfc_license_token, 200);
    EXPECT_SIZE(struct zkfc_crl_payload, 536);
    EXPECT_SIZE(struct zkfc_crl, 600);
    EXPECT_SIZE(struct zkfc_policy_entry, 16);
    EXPECT_SIZE(struct zkfc_policy_table, 520);
    EXPECT_SIZE(struct zkfc_sys_security, 56);
    EXPECT_SIZE(struct zkfc_user_security, 48);
    EXPECT_SIZE(struct zkfc_dev_security, 232);
    EXPECT_SIZE(struct zkfc_task_boost, 24);
    EXPECT_SIZE(struct zkfc_cpufreq_qos, 16);
    EXPECT_SIZE(struct zkfc_input_boost, 80);
    EXPECT_SIZE(struct zkfc_thermal_guard, 152);
    EXPECT_SIZE(struct zkfc_thermal_read, 40);
    EXPECT_SIZE(struct zkfc_perf_status, 48);
    EXPECT_SIZE(struct zkfc_log_record, 128);
    EXPECT_SIZE(struct zkfc_log_read, 2072);
    EXPECT_SIZE(struct zkfc_sulog_record, 120);
    EXPECT_SIZE(struct zkfc_sulog_read, 1944);

    EXPECT_OFFSET(struct zkfc_capability_info, kernel_caps, 0);
    EXPECT_OFFSET(struct zkfc_capability_info, kernel_release, 48);
    EXPECT_OFFSET(struct zkfc_version_info, build_id, 40);
    EXPECT_OFFSET(struct zkfc_version_info, kernel_release, 88);
    EXPECT_OFFSET(struct zkfc_policy_table, entries, 8);
    EXPECT_OFFSET(struct zkfc_license_status, token, 88);
    EXPECT_OFFSET(struct zkfc_dev_security, licensee_tag, 160);

    if (check_ioctl(ZKFC_IOC_GET_CAPABILITIES, _IOR(ZKFC_IOC_MAGIC, 0x05, struct zkfc_capability_info), "GET_CAPABILITIES")) return 1;
    if (check_ioctl(ZKFC_IOC_POLICY_GET, _IOR(ZKFC_IOC_MAGIC, 0x30, struct zkfc_policy_table), "POLICY_GET")) return 1;
    if (ZKFC_API_VERSION != ZKFC_MKVER(1, 1, 0)) return 1;
    if (ZKFC_API_MIN_SUPPORTED != ZKFC_MKVER(1, 0, 0)) return 1;

    puts("zkfc UAPI ABI checks passed");
    return 0;
}
