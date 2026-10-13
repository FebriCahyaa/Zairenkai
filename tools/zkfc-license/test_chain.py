#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Copyright (C) 2026 FebriCahyaa. All rights reserved.
"""
Self-test for the delegated (subordinate-key) token chain. Mirrors what the
kernel verifier (kernel/security/zkfc_license.c) checks, so the on-wire byte
layout and the owner -> subkey -> token chain stay in agreement. Runs offline
with throwaway keys; it never touches the repo's owner key.

    python3 -I tools/zkfc-license/test_chain.py
"""
import importlib.util
import os
import sys
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("zkfc_license", os.path.join(HERE, "zkfc_license.py"))
z = importlib.util.module_from_spec(spec)
spec.loader.exec_module(z)

fails = 0


def check(name, cond):
    global fails
    print(("ok  " if cond else "FAIL") + " - " + name)
    if not cond:
        fails += 1


# 1. Byte layout must match the C structs exactly.
check("payload is 136 bytes", z.PAYLOAD.size == 136)
check("subkey payload is 168 bytes", z.SUBKEY.size == 168)
check("subkey cert is 232 bytes", z.SUBCERT_SIZE == 232)
check("token is 200 bytes", z.TOKEN_SIZE == 200)
check("bundle is 432 bytes", z.BUNDLE_SIZE == 432)

# 2. Build an owner key, a subkey cert, and a delegated token.
owner = Ed25519PrivateKey.generate()
sub = Ed25519PrivateKey.generate()
subpub = sub.public_key().public_bytes_raw()
now = int(time.time())

# Subkey may grant input_boost+task_boost only, developer flag, for 30 days.
cert_payload = z.SUBKEY.pack(
    z.SUBKEY_MAGIC, z.SUBKEY_FORMAT, z.API_MAJOR, 0x1111, now, now + 30 * 86400,
    z.FEATURES["input_boost"] | z.FEATURES["task_boost"], z.FLAGS["developer"],
    z.binding_for("acme-gki"), subpub, b"ACME".ljust(64, b"\0"))
cert = cert_payload + owner.sign(cert_payload)

# A token within the grant.
tok_payload = z.PAYLOAD.pack(
    z.LICENSE_MAGIC, z.FORMAT, z.API_MAJOR, 0x2222, now, now + 10 * 86400,
    z.FEATURES["input_boost"], z.FLAGS["developer"], z.binding_for("acme-gki"),
    b"device".ljust(64, b"\0"))
token = tok_payload + sub.sign(tok_payload)
bundle = cert + token

check("decode_bundle round-trips", z.decode_bundle(bundle)[1]["license_id"] == 0x2222)

ownerpub = owner.public_key()


def chain_ok(bundle_bytes, ownerpub):
    c, t = z.decode_bundle(bundle_bytes)
    ownerpub.verify(c["owner_sig"], c["payload"])            # owner signed the cert
    Ed25519PublicKey.from_public_bytes(c["subkey_pub"]).verify(t["signature"], t["payload"])  # subkey signed token
    assert not (t["features"] & ~c["max_features"]), "features exceed grant"
    assert not (t["flags"] & ~c["flags_mask"]), "flags exceed grant"
    assert not (c["expires_at"] and (not t["expires_at"] or t["expires_at"] > c["expires_at"])), "expiry exceeds grant"
    assert c["binding"] == bytes(32) or t["binding"] == c["binding"], "scope mismatch"
    return True


# 3. Valid chain verifies.
try:
    check("valid chain verifies", chain_ok(bundle, ownerpub))
except (InvalidSignature, AssertionError) as e:
    check("valid chain verifies (%s)" % e, False)

# 4. A different owner key rejects the cert.
try:
    chain_ok(bundle, Ed25519PrivateKey.generate().public_key())
    check("wrong owner key is rejected", False)
except InvalidSignature:
    check("wrong owner key is rejected", True)

# 5. A token signed by the wrong key is rejected.
forged = cert + (tok_payload + Ed25519PrivateKey.generate().sign(tok_payload))
try:
    chain_ok(forged, ownerpub)
    check("forged token signature is rejected", False)
except InvalidSignature:
    check("forged token signature is rejected", True)

# 6. A token that grants more than the subkey allows is caught.
over_payload = z.PAYLOAD.pack(
    z.LICENSE_MAGIC, z.FORMAT, z.API_MAJOR, 0x3333, now, now + 10 * 86400,
    z.FEATURES_ALL, z.FLAGS["developer"], z.binding_for("acme-gki"), b"device".ljust(64, b"\0"))
over = cert + (over_payload + sub.sign(over_payload))
try:
    chain_ok(over, ownerpub)
    check("over-grant token is caught", False)
except AssertionError:
    check("over-grant token is caught", True)

# 7. A token for a different kernel than the subkey's scope is caught.
off_payload = z.PAYLOAD.pack(
    z.LICENSE_MAGIC, z.FORMAT, z.API_MAJOR, 0x4444, now, now + 10 * 86400,
    z.FEATURES["input_boost"], z.FLAGS["developer"], z.binding_for("other-gki"),
    b"device".ljust(64, b"\0"))
off = cert + (off_payload + sub.sign(off_payload))
try:
    chain_ok(off, ownerpub)
    check("out-of-scope kernel is caught", False)
except AssertionError:
    check("out-of-scope kernel is caught", True)

print("\n%s" % ("ALL PASSED" if not fails else "%d FAILED" % fails))
sys.exit(1 if fails else 0)
