// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC API Token (license) verification.
 *
 * A token is an Ed25519-signed payload issued by the Owner. It unlocks the
 * performance features of ZKFC for one licensee and, optionally, for one
 * kernel (binding = SHA-512("ZKFC-BIND-v1:" || CONFIG_ZKFC_LICENSEE_TAG)).
 *
 * Tokens can be embedded at build time (kernel/license/zkfc_license.inc) or
 * installed at runtime by root through ZKFC_IOC_INSTALL_LICENSE. Because the
 * signature is checked with the Owner's public key, runtime installation does
 * not weaken security. A signed revocation list (CRL) can disable leaked
 * tokens.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/jiffies.h>
#include <linux/timekeeping.h>

#include "../zkfc.h"
#include "../crypto/zk_ed25519.h"
#include "../crypto/zk_sha512.h"
#include "zkfc_owner_key.h"

#ifdef ZKFC_EMBEDDED_LICENSE
static const u8 zkfc_embedded_license[] __aligned(8) = {
#include "../license/zkfc_license.inc"
};
#endif

#ifdef ZKFC_EMBEDDED_CRL
static const u8 zkfc_embedded_crl[] __aligned(8) = {
#include "../license/zkfc_crl.inc"
};
#endif

/* Anything before this date means the RTC has not been set yet. */
#define ZKFC_EPOCH_SANE 1767225600LL	/* 2026-01-01T00:00:00Z */

static DEFINE_MUTEX(zkfc_lic_mutex);
static struct zkfc_license_token zkfc_tok;
static bool zkfc_have_tok;
static u32 zkfc_lic_state = ZKFC_LIC_MISSING;
static u32 zkfc_lic_feat;
static u64 zkfc_lic_expires;
static bool zkfc_clock_trusted;
static u64 zkfc_crl_serial;
static u32 zkfc_crl_count;
static u64 zkfc_crl_ids[ZKFC_CRL_MAX_IDS];
static u8 zkfc_binding[ZKFC_BINDING_SIZE];

void zkfc_kernel_binding(u8 out[ZKFC_BINDING_SIZE])
{
	memcpy(out, zkfc_binding, ZKFC_BINDING_SIZE);
}

static void zkfc_compute_binding(void)
{
	struct zk_sha512_ctx ctx;
	u8 digest[ZK_SHA512_DIGEST_SIZE];

	zk_sha512_init(&ctx);
	zk_sha512_update(&ctx, ZKFC_BINDING_PREFIX, strlen(ZKFC_BINDING_PREFIX));
	zk_sha512_update(&ctx, CONFIG_ZKFC_LICENSEE_TAG,
			 strlen(CONFIG_ZKFC_LICENSEE_TAG));
	zk_sha512_final(&ctx, digest);
	memcpy(zkfc_binding, digest, ZKFC_BINDING_SIZE);
}

static int zkfc_verify_sig(const void *payload, size_t len, const u8 *sig)
{
	struct zk_ed25519_ws *ws;
	int ret;

	ws = kzalloc(sizeof(*ws), GFP_KERNEL);
	if (!ws)
		return -ENOMEM;
	ret = zk_ed25519_verify(sig, payload, len, zkfc_owner_pubkey, ws);
	kfree(ws);
	return ret ? -EKEYREJECTED : 0;
}

static bool zkfc_is_revoked(u64 id)
{
	u32 i;

	for (i = 0; i < zkfc_crl_count; i++)
		if (zkfc_crl_ids[i] == id)
			return true;
	return false;
}

static bool zkfc_binding_is_any(const u8 *b)
{
	u8 acc = 0;
	int i;

	for (i = 0; i < ZKFC_BINDING_SIZE; i++)
		acc |= b[i];
	return acc == 0;
}

/* Called with zkfc_lic_mutex held. */
static u32 zkfc_evaluate(const struct zkfc_license_token *t)
{
	const struct zkfc_license_payload *p = &t->payload;
	time64_t now = ktime_get_real_seconds();
	int ret;

	if (!ZKFC_OWNER_KEY_PROVISIONED)
		return ZKFC_LIC_NO_OWNER_KEY;
	if (le32_to_cpu(p->magic) != ZKFC_LICENSE_MAGIC ||
	    le16_to_cpu(p->format) != ZKFC_LICENSE_FORMAT)
		return ZKFC_LIC_MALFORMED;

	if (le32_to_cpu(p->flags) & ~(ZKFC_LICF_DEVELOPER | ZKFC_LICF_COMMERCIAL | ZKFC_LICF_OWNER))
		return ZKFC_LIC_MALFORMED;
	if (le32_to_cpu(p->features) & ~ZKFC_FEAT_ALL)
		return ZKFC_LIC_MALFORMED;
	if (le64_to_cpu(p->license_id) == 0)
		return ZKFC_LIC_MALFORMED;

	ret = zkfc_verify_sig(p, sizeof(*p), t->signature);
	if (ret == -ENOMEM)
		return ZKFC_LIC_MALFORMED;
	if (ret)
		return ZKFC_LIC_BAD_SIGNATURE;

	if (le16_to_cpu(p->api_major) < ZKFC_API_MAJOR)
		return ZKFC_LIC_API_OUTDATED;
	if (le16_to_cpu(p->api_major) > ZKFC_API_MAJOR)
		return ZKFC_LIC_API_INCOMPATIBLE;
	if (!zkfc_binding_is_any(p->binding) &&
	    memcmp(p->binding, zkfc_binding, ZKFC_BINDING_SIZE))
		return ZKFC_LIC_WRONG_BINDING;
	if (zkfc_is_revoked(le64_to_cpu(p->license_id)))
		return ZKFC_LIC_REVOKED;

	zkfc_clock_trusted = now >= ZKFC_EPOCH_SANE;
	if (p->expires_at && zkfc_clock_trusted &&
	    (u64)now > le64_to_cpu(p->expires_at))
		return ZKFC_LIC_EXPIRED;

	return ZKFC_LIC_VALID;
}

/* Called with zkfc_lic_mutex held. */
static void zkfc_commit(void)
{
	u32 state = zkfc_have_tok ? zkfc_evaluate(&zkfc_tok) : ZKFC_LIC_MISSING;
	u32 feat = 0;

	if (state == ZKFC_LIC_VALID)
		feat = le32_to_cpu(zkfc_tok.payload.features) & ZKFC_FEAT_ALL;
	zkfc_lic_expires = zkfc_have_tok ? le64_to_cpu(zkfc_tok.payload.expires_at) : 0;
	WRITE_ONCE(zkfc_lic_feat, feat);
	WRITE_ONCE(zkfc_lic_state, state);
}

static void zkfc_check_expiry(void)
{
	time64_t now;

	if (READ_ONCE(zkfc_lic_state) != ZKFC_LIC_VALID || !zkfc_lic_expires)
		return;
	now = ktime_get_real_seconds();
	if (now >= ZKFC_EPOCH_SANE && (u64)now > zkfc_lic_expires) {
		WRITE_ONCE(zkfc_lic_feat, 0);
		WRITE_ONCE(zkfc_lic_state, ZKFC_LIC_EXPIRED);
		zkfc_w("API token expired");
	}
}

u32 zkfc_license_state(void)
{
	zkfc_check_expiry();
	return READ_ONCE(zkfc_lic_state);
}

u32 zkfc_license_features(void)
{
	zkfc_check_expiry();
	return READ_ONCE(zkfc_lic_feat);
}

void zkfc_license_status(struct zkfc_license_status *st)
{
	u8 digest[ZK_SHA512_DIGEST_SIZE];

	memset(st, 0, sizeof(*st));
	zk_sha512(digest, zkfc_owner_pubkey, sizeof(zkfc_owner_pubkey));

	mutex_lock(&zkfc_lic_mutex);
	/* Re-evaluate so a clock that became valid after boot is honoured. */
	zkfc_commit();
	st->state = zkfc_lic_state;
	st->has_token = zkfc_have_tok;
	st->owner_key_provisioned = ZKFC_OWNER_KEY_PROVISIONED;
	st->clock_trusted = zkfc_clock_trusted;
	st->crl_serial = cpu_to_le64(zkfc_crl_serial);
	memcpy(st->owner_key_fingerprint, digest, sizeof(st->owner_key_fingerprint));
	memcpy(st->kernel_binding, zkfc_binding, ZKFC_BINDING_SIZE);
	if (zkfc_have_tok)
		st->token = zkfc_tok;
	mutex_unlock(&zkfc_lic_mutex);
}

/*
 * Anti-brute-force: a token's signature is unforgeable, but we still cap the
 * rate of rejected installs so a process cannot spin on the verifier. After
 * ZKFC_LIC_FAIL_MAX rejects we refuse further attempts for a cooldown; a valid
 * install clears the counter. Held under zkfc_lic_mutex.
 */
#define ZKFC_LIC_FAIL_MAX 8
#define ZKFC_LIC_COOLDOWN (30 * HZ)
static unsigned int zkfc_lic_fails;
static unsigned long zkfc_lic_cooldown_until;

int zkfc_license_install(const struct zkfc_license_token *tok)
{
	u32 state = ZKFC_LIC_MALFORMED;
	bool throttled = false;

	mutex_lock(&zkfc_lic_mutex);
	if (zkfc_lic_fails >= ZKFC_LIC_FAIL_MAX &&
	    time_before(jiffies, zkfc_lic_cooldown_until)) {
		throttled = true;
		goto out;
	}
	state = zkfc_evaluate(tok);
	/*
	 * Only replace the active token with one that verifies. A bad token
	 * must never downgrade a working installation.
	 */
	if (state == ZKFC_LIC_VALID) {
		zkfc_tok = *tok;
		zkfc_have_tok = true;
		zkfc_lic_fails = 0;
		zkfc_commit();
	} else if (++zkfc_lic_fails >= ZKFC_LIC_FAIL_MAX) {
		zkfc_lic_cooldown_until = jiffies + ZKFC_LIC_COOLDOWN;
	}
out:
	mutex_unlock(&zkfc_lic_mutex);

	if (throttled) {
		zkfc_w("API token install throttled (too many rejects)");
		return -EAGAIN;
	}
	if (state == ZKFC_LIC_VALID)
		zkfc_i("API token %llx installed for '%.32s'",
		       le64_to_cpu(tok->payload.license_id), tok->payload.licensee);
	else
		zkfc_w("API token rejected (state %u)", state);
	return state == ZKFC_LIC_VALID ? 0 : -EKEYREJECTED;
}

int zkfc_license_install_crl(const struct zkfc_crl *crl)
{
	const struct zkfc_crl_payload *p = &crl->payload;
	u16 count = le16_to_cpu(p->count);
	u64 serial = le64_to_cpu(p->serial);
	int ret;
	u32 i;

	if (!ZKFC_OWNER_KEY_PROVISIONED)
		return -ENOKEY;
	if (le32_to_cpu(p->magic) != ZKFC_CRL_MAGIC ||
	    le16_to_cpu(p->format) != ZKFC_LICENSE_FORMAT ||
	    count > ZKFC_CRL_MAX_IDS)
		return -EINVAL;
	ret = zkfc_verify_sig(p, sizeof(*p), crl->signature);
	if (ret)
		return ret;

	mutex_lock(&zkfc_lic_mutex);
	/* Refuse rollback to an older list. */
	if (serial <= zkfc_crl_serial && zkfc_crl_serial) {
		mutex_unlock(&zkfc_lic_mutex);
		return -ESTALE;
	}
	zkfc_crl_serial = serial;
	zkfc_crl_count = count;
	for (i = 0; i < count; i++)
		zkfc_crl_ids[i] = le64_to_cpu(p->revoked_ids[i]);
	zkfc_commit();
	mutex_unlock(&zkfc_lic_mutex);

	zkfc_i("revocation list #%llu installed (%u ids)", serial, count);
	return 0;
}

int zkfc_license_init(void)
{
	zkfc_compute_binding();

#ifdef ZKFC_EMBEDDED_CRL
	BUILD_BUG_ON(sizeof(zkfc_embedded_crl) != sizeof(struct zkfc_crl));
	if (zkfc_license_install_crl((const struct zkfc_crl *)zkfc_embedded_crl))
		zkfc_w("embedded revocation list rejected");
#endif

#ifdef ZKFC_EMBEDDED_LICENSE
	BUILD_BUG_ON(sizeof(zkfc_embedded_license) != sizeof(struct zkfc_license_token));
	zkfc_license_install((const struct zkfc_license_token *)zkfc_embedded_license);
#else
	mutex_lock(&zkfc_lic_mutex);
	zkfc_commit();
	mutex_unlock(&zkfc_lic_mutex);
	zkfc_i("no embedded API token; waiting for runtime installation");
#endif
	return 0;
}

void zkfc_license_exit(void)
{
	mutex_lock(&zkfc_lic_mutex);
	memzero_explicit(&zkfc_tok, sizeof(zkfc_tok));
	zkfc_have_tok = false;
	WRITE_ONCE(zkfc_lic_feat, 0);
	WRITE_ONCE(zkfc_lic_state, ZKFC_LIC_MISSING);
	mutex_unlock(&zkfc_lic_mutex);
}
