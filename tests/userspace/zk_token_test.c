/* SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary */
/* Parser contract tests; signatures are validated by the kernel verifier. */
#define _POSIX_C_SOURCE 200809L
#include "libzkfc.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char b64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int write_armor(const char *path, const char *begin, const char *end,
                       const unsigned char *data, size_t len)
{
    FILE *f = fopen(path, "wb");
    size_t i;

    if (!f)
        return -1;
    if (fprintf(f, "%s\n", begin) < 0)
        goto fail;

    for (i = 0; i < len; i += 3) {
        unsigned int a = data[i];
        unsigned int b = i + 1 < len ? data[i + 1] : 0;
        unsigned int c = i + 2 < len ? data[i + 2] : 0;
        char out[4];

        out[0] = b64[a >> 2];
        out[1] = b64[((a & 3) << 4) | (b >> 4)];
        out[2] = i + 1 < len ? b64[((b & 15) << 2) | (c >> 6)] : '=';
        out[3] = i + 2 < len ? b64[c & 63] : '=';
        if (fwrite(out, 1, sizeof(out), f) != sizeof(out))
            goto fail;
        if ((i / 3 + 1) % 16 == 0 && fputc('\n', f) == EOF)
            goto fail;
    }

    if (fprintf(f, "\n%s\n", end) < 0)
        goto fail;
    if (fclose(f) != 0)
        return -1;
    return 0;

fail:
    fclose(f);
    return -1;
}

#define CHECK(cond, message) do { \
    if (!(cond)) { \
        fprintf(stderr, "FAIL: %s\n", message); \
        return 1; \
    } \
} while (0)

int main(void)
{
    char path[] = "/tmp/zkfc-token-test-XXXXXX";
    unsigned char token_bytes[sizeof(struct zkfc_license_token)] = {0};
    unsigned char bundle_bytes[sizeof(struct zkfc_license_bundle)] = {0};
    unsigned char short_bundle[sizeof(struct zkfc_license_bundle) - 1] = {0};
    struct zkfc_license_token token;
    struct zkfc_license_bundle bundle;
    int fd = mkstemp(path);

    CHECK(fd >= 0, "create temporary input file");
    close(fd);

    CHECK(write_armor(path, "-----BEGIN ZKFC API TOKEN-----",
                      "-----END ZKFC API TOKEN-----",
                      token_bytes, sizeof(token_bytes)) == 0,
          "write token fixture");
    CHECK(zkfc_parse_token_file(path, &token) == 0,
          "parse exact-size standalone token");
    CHECK(zkfc_parse_bundle_file(path, &bundle) != 0,
          "reject standalone token as delegated bundle");

    CHECK(write_armor(path, "-----BEGIN ZKFC DELEGATED TOKEN-----",
                      "-----END ZKFC DELEGATED TOKEN-----",
                      bundle_bytes, sizeof(bundle_bytes)) == 0,
          "write bundle fixture");
    CHECK(zkfc_parse_bundle_file(path, &bundle) == 0,
          "parse exact-size delegated bundle");
    CHECK(zkfc_parse_token_file(path, &token) != 0,
          "reject delegated bundle as standalone token");

    CHECK(write_armor(path, "-----BEGIN ZKFC DELEGATED TOKEN-----",
                      "-----END ZKFC DELEGATED TOKEN-----",
                      short_bundle, sizeof(short_bundle)) == 0,
          "write short bundle fixture");
    CHECK(zkfc_parse_bundle_file(path, &bundle) == -EMSGSIZE,
          "reject bundle with incorrect byte length");

    {
        FILE *f = fopen(path, "wb");
        CHECK(f != NULL, "open malformed fixture");
        CHECK(fprintf(f, "-----BEGIN ZKFC DELEGATED TOKEN-----\n%%AAA\n"
                         "-----END ZKFC DELEGATED TOKEN-----\n") > 0,
              "write malformed base64 fixture");
        CHECK(fclose(f) == 0, "close malformed fixture");
        CHECK(zkfc_parse_bundle_file(path, &bundle) == -EINVAL,
              "reject invalid base64 character");
    }

    CHECK(write_armor(path, "-----BEGIN ZKFC DELEGATED TOKEN-----",
                      "-----END ZKFC DELEGATED TOKEN-----",
                      bundle_bytes, sizeof(bundle_bytes)) == 0,
          "write valid bundle for trailing-content test");
    {
        FILE *f = fopen(path, "ab");
        CHECK(f != NULL, "open trailing-content fixture");
        CHECK(fputs("trailing", f) >= 0, "append trailing-content fixture");
        CHECK(fclose(f) == 0, "close trailing-content fixture");
        CHECK(zkfc_parse_bundle_file(path, &bundle) == -EINVAL,
              "reject non-whitespace trailing content");
    }

    unlink(path);
    puts("zkfc token parser tests passed");
    return 0;
}
