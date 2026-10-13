#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Copyright (C) 2026 FebriCahyaa. All rights reserved.
"""
zkfc-license - Owner tool for ZKFC API Tokens.

Only the Zairenkai Owner runs this tool. It keeps the Ed25519 signing key
OUTSIDE the repository (default ~/.zkfc, mode 0700/0600, passphrase
protected) and issues tokens to kernel developers who asked for permission.

  init-owner   create the Owner key and write the public key into the source
  binding      print the binding hash of a kernel tag
  issue        issue a token for one licensee (and optionally one kernel tag)
  verify       verify a token against the public key in the source tree
  show         decode a token without verifying it
  revoke       produce a signed revocation list (CRL)
  ledger       list every token issued from this machine
"""
import argparse
import base64
import datetime as dt
import getpass
import hashlib
import json
import os
import re
import secrets
import stat
import struct
import sys

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey,
        Ed25519PublicKey,
    )
except ImportError:  # pragma: no cover
    sys.exit("zkfc-license needs the 'cryptography' package: pip install cryptography")

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OWNER_KEY_HEADER = os.path.join(REPO, "kernel", "security", "zkfc_owner_key.h")
DEFAULT_KEY_DIR = os.path.expanduser(os.environ.get("ZKFC_KEY_DIR", "~/.zkfc"))

LICENSE_MAGIC = 0x434C4B5A  # "ZKLC"
CRL_MAGIC = 0x4C524B5A  # "ZKRL"
SUBKEY_MAGIC = 0x4B534B5A  # "ZKSK"
FORMAT = 1
SUBKEY_FORMAT = 1
API_MAJOR = 1
BINDING_PREFIX = b"ZKFC-BIND-v1:"
CRL_MAX = 64

# struct zkfc_license_payload (little endian, 136 bytes)
PAYLOAD = struct.Struct("<IHHQQQII32s64s")
assert PAYLOAD.size == 136
# struct zkfc_crl_payload (536 bytes)
CRL_PAYLOAD = struct.Struct("<IHHQQ%dQ" % CRL_MAX)
assert CRL_PAYLOAD.size == 536
# struct zkfc_subkey_payload (168 bytes): a delegated signing key the Owner caps.
SUBKEY = struct.Struct("<IHHQQQII32s32s64s")
assert SUBKEY.size == 168
SUBCERT_SIZE = SUBKEY.size + 64        # payload + owner signature
TOKEN_SIZE = PAYLOAD.size + 64
BUNDLE_SIZE = SUBCERT_SIZE + TOKEN_SIZE  # cert + token

FEATURES = {
    "input_boost": 1 << 0,
    "task_boost": 1 << 1,
    "cpufreq_qos": 1 << 2,
    "thermal_guard": 1 << 3,
    "sulog": 1 << 4,
    "integrity": 1 << 5,
    "policy": 1 << 6,
    "uclamp": 1 << 7,
    "kprobes": 1 << 8,
    "boost_inherit": 1 << 9,
}
FEATURES_ALL = 0x3FF
FLAGS = {"developer": 1 << 0, "commercial": 1 << 1, "owner": 1 << 2}

TOKEN_BEGIN = "-----BEGIN ZKFC API TOKEN-----"
TOKEN_END = "-----END ZKFC API TOKEN-----"
CRL_BEGIN = "-----BEGIN ZKFC REVOCATION LIST-----"
CRL_END = "-----END ZKFC REVOCATION LIST-----"
BUNDLE_BEGIN = "-----BEGIN ZKFC DELEGATED TOKEN-----"
BUNDLE_END = "-----END ZKFC DELEGATED TOKEN-----"


def die(msg):
    sys.exit("zkfc-license: " + msg)


def binding_for(tag):
    if tag is None:
        return bytes(32)
    return hashlib.sha512(BINDING_PREFIX + tag.encode()).digest()[:32]


def key_paths(key_dir):
    return os.path.join(key_dir, "owner_ed25519.pem"), os.path.join(key_dir, "issued.jsonl")


def passphrase(confirm=False):
    env = os.environ.get("ZKFC_OWNER_PASSPHRASE")
    if env:
        return env.encode()
    first = getpass.getpass("Owner key passphrase: ")
    if confirm and getpass.getpass("Repeat passphrase: ") != first:
        die("passphrases do not match")
    if len(first) < 12:
        die("use a passphrase of at least 12 characters")
    return first.encode()


def load_owner_key(key_dir):
    path, _ = key_paths(key_dir)
    if not os.path.exists(path):
        die("no owner key at %s (run init-owner first)" % path)
    mode = stat.S_IMODE(os.stat(path).st_mode)
    if mode & 0o077:
        die("%s is accessible by other users (mode %o); run chmod 600" % (path, mode))
    with open(path, "rb") as f:
        data = f.read()
    try:
        return serialization.load_pem_private_key(data, password=passphrase())
    except (TypeError, ValueError):
        die("wrong passphrase or damaged key")


def repo_pubkey():
    with open(OWNER_KEY_HEADER) as f:
        text = f.read()
    if re.search(r"ZKFC_OWNER_KEY_PROVISIONED\s+0", text):
        die("the source tree has no owner key yet (run init-owner)")
    body = text[text.index("zkfc_owner_pubkey[32]"):]
    raw = bytes(int(x, 16) for x in re.findall(r"0x([0-9a-fA-F]{2})", body)[:32])
    return Ed25519PublicKey.from_public_bytes(raw), raw


def write_owner_header(pub):
    rows = []
    for i in range(0, 32, 8):
        rows.append("\t" + ", ".join("0x%02x" % b for b in pub[i:i + 8]) + ",")
    with open(OWNER_KEY_HEADER) as f:
        text = f.read()
    text = re.sub(r"#define ZKFC_OWNER_KEY_PROVISIONED \d", "#define ZKFC_OWNER_KEY_PROVISIONED 1", text)
    text = re.sub(r"(zkfc_owner_pubkey\[32\] = \{\n)(.*?)(\n\};)",
                  lambda m: m.group(1) + "\n".join(rows) + m.group(3), text, flags=re.S)
    with open(OWNER_KEY_HEADER, "w") as f:
        f.write(text)


def armor(begin, end, blob):
    b64 = base64.b64encode(blob).decode()
    lines = [b64[i:i + 64] for i in range(0, len(b64), 64)]
    return "\n".join([begin] + lines + [end]) + "\n"


def unarmor(text, begin, end):
    if begin not in text:
        die("not a ZKFC file (missing %s)" % begin)
    body = text.split(begin, 1)[1].split(end, 1)[0]
    return base64.b64decode("".join(body.split()))


def c_bytes(blob):
    out = []
    for i in range(0, len(blob), 12):
        out.append("\t" + " ".join("0x%02x," % b for b in blob[i:i + 12]))
    return "/* Generated by zkfc-license. Do not edit. */\n" + "\n".join(out) + "\n"


def decode_token(blob):
    if len(blob) != PAYLOAD.size + 64:
        die("token has %d bytes, expected %d" % (len(blob), PAYLOAD.size + 64))
    f = PAYLOAD.unpack(blob[:PAYLOAD.size])
    return {
        "magic": f[0], "format": f[1], "api_major": f[2], "license_id": f[3],
        "issued_at": f[4], "expires_at": f[5], "features": f[6], "flags": f[7],
        "binding": f[8], "licensee": f[9].rstrip(b"\0").decode(errors="replace"),
        "payload": blob[:PAYLOAD.size], "signature": blob[PAYLOAD.size:],
    }


def fmt_time(ts):
    if not ts:
        return "never"
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def describe(tok):
    feats = [n for n, b in FEATURES.items() if tok["features"] & b]
    flags = [n for n, b in FLAGS.items() if tok["flags"] & b]
    binding = "any kernel" if tok["binding"] == bytes(32) else tok["binding"].hex()
    return "\n".join([
        "license id : %016x" % tok["license_id"],
        "licensee   : %s" % tok["licensee"],
        "api major  : %d" % tok["api_major"],
        "issued     : %s" % fmt_time(tok["issued_at"]),
        "expires    : %s" % fmt_time(tok["expires_at"]),
        "features   : %s" % (", ".join(feats) or "none"),
        "flags      : %s" % (", ".join(flags) or "none"),
        "binding    : %s" % binding,
    ])


def decode_cert(blob):
    if len(blob) != SUBCERT_SIZE:
        die("subkey cert has %d bytes, expected %d" % (len(blob), SUBCERT_SIZE))
    f = SUBKEY.unpack(blob[:SUBKEY.size])
    return {
        "magic": f[0], "format": f[1], "api_major": f[2], "subkey_id": f[3],
        "issued_at": f[4], "expires_at": f[5], "max_features": f[6], "flags_mask": f[7],
        "binding": f[8], "subkey_pub": f[9], "holder": f[10].rstrip(b"\0").decode(errors="replace"),
        "payload": blob[:SUBKEY.size], "owner_sig": blob[SUBKEY.size:],
    }


def decode_bundle(blob):
    if len(blob) != BUNDLE_SIZE:
        die("delegated token has %d bytes, expected %d" % (len(blob), BUNDLE_SIZE))
    return decode_cert(blob[:SUBCERT_SIZE]), decode_token(blob[SUBCERT_SIZE:])


def describe_cert(c):
    feats = [n for n, b in FEATURES.items() if c["max_features"] & b]
    flags = [n for n, b in FLAGS.items() if c["flags_mask"] & b]
    binding = "any kernel" if c["binding"] == bytes(32) else c["binding"].hex()
    return "\n".join([
        "subkey id  : %016x" % c["subkey_id"],
        "holder     : %s" % c["holder"],
        "api major  : %d" % c["api_major"],
        "issued     : %s" % fmt_time(c["issued_at"]),
        "expires    : %s" % fmt_time(c["expires_at"]),
        "max feats  : %s" % (", ".join(feats) or "none"),
        "flag mask  : %s" % (", ".join(flags) or "none"),
        "scope      : %s" % binding,
        "subkey pub : %s" % c["subkey_pub"].hex(),
    ])


def save_subkey_file(path, priv, cert_blob, holder, subkey_id):
    pem = priv.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption())
    data = {"holder": holder, "subkey_id": "%016x" % subkey_id,
            "priv": base64.b64encode(pem).decode(), "cert": base64.b64encode(cert_blob).decode()}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f)


def load_subkey_file(path):
    mode = stat.S_IMODE(os.stat(path).st_mode)
    if mode & 0o077:
        die("%s is accessible by other users (mode %o); run chmod 600" % (path, mode))
    with open(path) as f:
        data = json.load(f)
    priv = serialization.load_pem_private_key(base64.b64decode(data["priv"]), password=None)
    return priv, base64.b64decode(data["cert"]), data


# ----------------------------------------------------------------- commands

def cmd_init_owner(a):
    path, _ = key_paths(a.key_dir)
    if os.path.exists(path) and not a.force:
        die("%s already exists; use --force to rotate (every issued token becomes invalid)" % path)
    os.makedirs(a.key_dir, mode=0o700, exist_ok=True)
    os.chmod(a.key_dir, 0o700)
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.BestAvailableEncryption(passphrase(confirm=True)))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    pub = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    write_owner_header(pub)
    print("Owner key written to %s (keep an offline backup!)" % path)
    print("Public key   : %s" % pub.hex())
    print("Fingerprint  : %s" % hashlib.sha512(pub).digest()[:32].hex())
    print("Updated      : %s  -> commit this file" % os.path.relpath(OWNER_KEY_HEADER, REPO))


def cmd_binding(a):
    print(binding_for(a.tag).hex())


def parse_features(spec):
    if spec == "all":
        return FEATURES_ALL
    mask = 0
    for name in spec.split(","):
        name = name.strip()
        if name not in FEATURES:
            die("unknown feature %r (known: %s)" % (name, ", ".join(FEATURES)))
        mask |= FEATURES[name]
    return mask


def parse_flags(spec):
    mask = 0
    for name in filter(None, (s.strip() for s in (spec or "").split(","))):
        if name not in FLAGS:
            die("unknown flag %r (known: %s)" % (name, ", ".join(FLAGS)))
        mask |= FLAGS[name]
    return mask


def _ledger_append(key_dir, entry):
    _, ledger = key_paths(key_dir)
    fd = os.open(ledger, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a") as f:
        f.write(json.dumps(entry) + "\n")


def cmd_issue(a):
    if not a.any_kernel and not a.tag:
        die("pass --tag <kernel tag> or --any-kernel")
    licensee = a.licensee.encode()
    if not 3 <= len(licensee) <= 63:
        die("licensee must be 3..63 bytes")

    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    expires = 0 if a.no_expiry else now + a.days * 86400
    license_id = secrets.randbits(64) or 1
    features = parse_features(a.features)
    flags = parse_flags(a.flags)
    binding = binding_for(None if a.any_kernel else a.tag)
    payload = PAYLOAD.pack(LICENSE_MAGIC, FORMAT, a.api_major, license_id, now, expires,
                           features, flags, binding, licensee.ljust(64, b"\0"))

    if a.subkey:
        # Delegated: sign the token with a subordinate key and ship it together
        # with the Owner-signed certificate. The grant may not exceed the cert.
        sub, cert_blob, meta = load_subkey_file(a.subkey)
        cert = decode_cert(cert_blob)
        if features & ~cert["max_features"]:
            die("token features exceed the subkey grant (%s)" % meta["subkey_id"])
        if flags & ~cert["flags_mask"]:
            die("token flags exceed the subkey grant")
        if cert["expires_at"] and (expires == 0 or expires > cert["expires_at"]):
            die("token expiry is later than the subkey expiry")
        if cert["binding"] != bytes(32) and binding != cert["binding"]:
            die("token kernel scope must match the subkey scope")
        if a.api_major != cert["api_major"]:
            die("token api-major must equal the subkey api-major (%d)" % cert["api_major"])
        token = payload + sub.sign(payload)
        bundle = cert_blob + token
        out = a.out or "zkfc-%016x" % license_id
        with open(out + ".zkl", "w") as f:
            f.write(armor(BUNDLE_BEGIN, BUNDLE_END, bundle))
        with open(out + ".inc", "w") as f:
            f.write(c_bytes(bundle))
        _ledger_append(a.key_dir, {"license_id": "%016x" % license_id, "licensee": a.licensee,
                                   "tag": a.tag, "issued_at": now, "expires_at": expires,
                                   "subkey": meta["subkey_id"], "kind": "delegated"})
        print(describe_cert(cert)); print(); print(describe(decode_token(token)))
        print("\nwrote %s.zkl and %s.inc (delegated bundle -> kernel/license/zkfc_bundle.inc)" % (out, out))
        return

    key = load_owner_key(a.key_dir)
    _, repo_raw = repo_pubkey()
    own_raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    if own_raw != repo_raw:
        die("owner key does not match kernel/security/zkfc_owner_key.h")
    token = payload + key.sign(payload)

    out = a.out or "zkfc-%016x" % license_id
    with open(out + ".zkl", "w") as f:
        f.write(armor(TOKEN_BEGIN, TOKEN_END, token))
    with open(out + ".inc", "w") as f:
        f.write(c_bytes(token))
    _ledger_append(a.key_dir, {"license_id": "%016x" % license_id, "licensee": a.licensee, "tag": a.tag,
                               "issued_at": now, "expires_at": expires, "contact": a.contact, "note": a.note})

    print(describe(decode_token(token)))
    print("\nwrote %s.zkl (app / runtime install) and %s.inc (kernel/license/)" % (out, out))


def cmd_issue_subkey(a):
    if not a.any_kernel and not a.tag:
        die("pass --tag <kernel tag> or --any-kernel")
    holder = a.holder.encode()
    if not 3 <= len(holder) <= 63:
        die("holder must be 3..63 bytes")
    key = load_owner_key(a.key_dir)
    _, repo_raw = repo_pubkey()
    own_raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    if own_raw != repo_raw:
        die("owner key does not match kernel/security/zkfc_owner_key.h")

    sub = Ed25519PrivateKey.generate()
    subpub = sub.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    expires = 0 if a.no_expiry else now + a.days * 86400
    subkey_id = secrets.randbits(64) or 1
    payload = SUBKEY.pack(SUBKEY_MAGIC, SUBKEY_FORMAT, a.api_major, subkey_id, now, expires,
                          parse_features(a.features), parse_flags(a.flags),
                          binding_for(None if a.any_kernel else a.tag), subpub, holder.ljust(64, b"\0"))
    cert = payload + key.sign(payload)

    out = a.out or "zkfc-subkey-%016x" % subkey_id
    save_subkey_file(out + ".zksub", sub, cert, a.holder, subkey_id)
    _ledger_append(a.key_dir, {"subkey_id": "%016x" % subkey_id, "holder": a.holder, "tag": a.tag,
                               "issued_at": now, "expires_at": expires, "features": a.features,
                               "flags": a.flags, "kind": "subkey"})
    print(describe_cert(decode_cert(cert)))
    print("\nwrote %s.zksub -- hand this to the builder (keep it 0600; it holds the subkey private key)." % out)
    print("The builder issues delegated tokens with:  issue --subkey %s.zksub --licensee <name> --tag <tag>" % out)


def cmd_verify(a):
    text = open(a.file).read()
    pub, _ = repo_pubkey()
    problems = []
    nowts = dt.datetime.now(dt.timezone.utc).timestamp()

    if BUNDLE_BEGIN in text:
        cert, tok = decode_bundle(unarmor(text, BUNDLE_BEGIN, BUNDLE_END))
        print(describe_cert(cert)); print(); print(describe(tok))
        if cert["magic"] != SUBKEY_MAGIC or cert["format"] != SUBKEY_FORMAT:
            problems.append("malformed subkey cert")
        try:
            pub.verify(cert["owner_sig"], cert["payload"])
        except InvalidSignature:
            problems.append("BAD CERT SIGNATURE (subkey not delegated by this owner key)")
        try:
            Ed25519PublicKey.from_public_bytes(cert["subkey_pub"]).verify(tok["signature"], tok["payload"])
        except InvalidSignature:
            problems.append("BAD TOKEN SIGNATURE (token not signed by the subkey)")
        if tok["features"] & ~cert["max_features"]:
            problems.append("token features exceed the subkey grant")
        if tok["flags"] & ~cert["flags_mask"]:
            problems.append("token flags exceed the subkey grant")
        if cert["expires_at"] and (not tok["expires_at"] or tok["expires_at"] > cert["expires_at"]):
            problems.append("token expiry exceeds the subkey expiry")
        if cert["binding"] != bytes(32) and tok["binding"] != cert["binding"]:
            problems.append("token scope differs from the subkey scope")
        for who, obj in (("subkey", cert), ("token", tok)):
            if obj["api_major"] < API_MAJOR:
                problems.append("%s is for API %d, current is %d" % (who, obj["api_major"], API_MAJOR))
            if obj["expires_at"] and obj["expires_at"] < nowts:
                problems.append("%s expired" % who)
        if a.tag is not None and tok["binding"] not in (bytes(32), binding_for(a.tag)):
            problems.append("bound to another kernel tag")
    else:
        tok = decode_token(unarmor(text, TOKEN_BEGIN, TOKEN_END))
        print(describe(tok))
        if tok["magic"] != LICENSE_MAGIC or tok["format"] != FORMAT:
            problems.append("malformed header")
        try:
            pub.verify(tok["signature"], tok["payload"])
        except InvalidSignature:
            problems.append("BAD SIGNATURE (not issued by this owner key)")
        if tok["api_major"] < API_MAJOR:
            problems.append("token is for API %d, current API is %d" % (tok["api_major"], API_MAJOR))
        if tok["expires_at"] and tok["expires_at"] < nowts:
            problems.append("expired")
        if a.tag is not None and tok["binding"] not in (bytes(32), binding_for(a.tag)):
            problems.append("bound to another kernel tag")

    print("\nresult     : %s" % ("VALID" if not problems else "INVALID - " + "; ".join(problems)))
    sys.exit(1 if problems else 0)


def cmd_show(a):
    text = open(a.file).read()
    if BUNDLE_BEGIN in text:
        cert, tok = decode_bundle(unarmor(text, BUNDLE_BEGIN, BUNDLE_END))
        print(describe_cert(cert)); print(); print(describe(tok))
    else:
        print(describe(decode_token(unarmor(text, TOKEN_BEGIN, TOKEN_END))))


def cmd_revoke(a):
    ids = [int(x, 16) for x in a.ids]
    if len(ids) > CRL_MAX:
        die("at most %d ids per list" % CRL_MAX)
    key = load_owner_key(a.key_dir)
    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    payload = CRL_PAYLOAD.pack(CRL_MAGIC, FORMAT, len(ids), a.serial, now, *(ids + [0] * (CRL_MAX - len(ids))))
    crl = payload + key.sign(payload)
    with open(a.out + ".zkcrl", "w") as f:
        f.write(armor(CRL_BEGIN, CRL_END, crl))
    with open(a.out + ".inc", "w") as f:
        f.write(c_bytes(crl))
    print("revocation list #%d with %d ids -> %s.zkcrl / %s.inc" % (a.serial, len(ids), a.out, a.out))


def cmd_ledger(a):
    _, ledger = key_paths(a.key_dir)
    if not os.path.exists(ledger):
        print("no tokens issued yet")
        return
    with open(ledger) as f:
        for line in f:
            e = json.loads(line)
            print("%s  %-28s tag=%-24s expires=%s" % (e["license_id"], e["licensee"], e.get("tag") or "*",
                                                     fmt_time(e["expires_at"])))


def main(argv=None):
    p = argparse.ArgumentParser(prog="zkfc-license", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--key-dir", default=DEFAULT_KEY_DIR, help="owner key directory (default ~/.zkfc)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("init-owner", help="create the owner signing key")
    s.add_argument("--force", action="store_true", help="rotate an existing key")
    s.set_defaults(fn=cmd_init_owner)

    s = sub.add_parser("binding", help="print the binding hash for a kernel tag")
    s.add_argument("tag")
    s.set_defaults(fn=cmd_binding)

    s = sub.add_parser("issue", help="issue a ZKFC API Token")
    s.add_argument("--licensee", required=True, help="person or team the token is issued to")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--tag", help="CONFIG_ZKFC_LICENSEE_TAG of the licensee's kernel")
    g.add_argument("--any-kernel", action="store_true", help="no kernel binding (owner builds only)")
    e = s.add_mutually_exclusive_group()
    e.add_argument("--days", type=int, default=365, help="validity in days (default 365)")
    e.add_argument("--no-expiry", action="store_true")
    s.add_argument("--features", default="all", help="'all' or comma separated feature names")
    s.add_argument("--flags", default="", help="developer,commercial,owner")
    s.add_argument("--api-major", type=int, default=API_MAJOR)
    s.add_argument("--contact", default="", help="ledger only: how to reach the licensee")
    s.add_argument("--note", default="", help="ledger only: free text")
    s.add_argument("--subkey", help="issue with a delegated subkey file (.zksub) instead of the owner key")
    s.add_argument("--out", help="output file prefix")
    s.set_defaults(fn=cmd_issue)

    s = sub.add_parser("issue-subkey", help="delegate issuance to a subordinate key (owner only)")
    s.add_argument("--holder", required=True, help="person or team the subkey is for")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--tag", help="restrict the subkey to this kernel tag")
    g.add_argument("--any-kernel", action="store_true", help="no kernel scope")
    e = s.add_mutually_exclusive_group()
    e.add_argument("--days", type=int, default=365, help="validity in days (default 365)")
    e.add_argument("--no-expiry", action="store_true")
    s.add_argument("--features", default="all", help="max features the subkey may grant")
    s.add_argument("--flags", default="", help="flags the subkey may grant")
    s.add_argument("--api-major", type=int, default=API_MAJOR)
    s.add_argument("--out", help="output file prefix")
    s.set_defaults(fn=cmd_issue_subkey)

    s = sub.add_parser("verify", help="verify a token")
    s.add_argument("file")
    s.add_argument("--tag", help="also check the kernel binding")
    s.set_defaults(fn=cmd_verify)

    s = sub.add_parser("show", help="decode a token")
    s.add_argument("file")
    s.set_defaults(fn=cmd_show)

    s = sub.add_parser("revoke", help="create a signed revocation list")
    s.add_argument("--serial", type=int, required=True, help="must increase with every list")
    s.add_argument("--out", default="zkfc-crl")
    s.add_argument("ids", nargs="+", help="license ids (hex)")
    s.set_defaults(fn=cmd_revoke)

    s = sub.add_parser("ledger", help="list issued tokens")
    s.set_defaults(fn=cmd_ledger)

    a = p.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
