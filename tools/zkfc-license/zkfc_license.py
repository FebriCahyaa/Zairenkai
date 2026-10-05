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
FORMAT = 1
API_MAJOR = 1
BINDING_PREFIX = b"ZKFC-BIND-v1:"
CRL_MAX = 64

# struct zkfc_license_payload (little endian, 136 bytes)
PAYLOAD = struct.Struct("<IHHQQQII32s64s")
assert PAYLOAD.size == 136
# struct zkfc_crl_payload (536 bytes)
CRL_PAYLOAD = struct.Struct("<IHHQQ%dQ" % CRL_MAX)
assert CRL_PAYLOAD.size == 536

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


def cmd_issue(a):
    if not a.any_kernel and not a.tag:
        die("pass --tag <kernel tag> or --any-kernel")
    licensee = a.licensee.encode()
    if not 3 <= len(licensee) <= 63:
        die("licensee must be 3..63 bytes")
    key = load_owner_key(a.key_dir)
    _, repo_raw = repo_pubkey()
    own_raw = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    if own_raw != repo_raw:
        die("owner key does not match kernel/security/zkfc_owner_key.h")

    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    expires = 0 if a.no_expiry else now + a.days * 86400
    license_id = secrets.randbits(64) or 1
    payload = PAYLOAD.pack(LICENSE_MAGIC, FORMAT, a.api_major, license_id, now, expires,
                           parse_features(a.features), parse_flags(a.flags),
                           binding_for(None if a.any_kernel else a.tag), licensee.ljust(64, b"\0"))
    token = payload + key.sign(payload)

    out = a.out or "zkfc-%016x" % license_id
    with open(out + ".zkl", "w") as f:
        f.write(armor(TOKEN_BEGIN, TOKEN_END, token))
    with open(out + ".inc", "w") as f:
        f.write(c_bytes(token))

    _, ledger = key_paths(a.key_dir)
    entry = {"license_id": "%016x" % license_id, "licensee": a.licensee, "tag": a.tag,
             "issued_at": now, "expires_at": expires, "contact": a.contact, "note": a.note}
    fd = os.open(ledger, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a") as f:
        f.write(json.dumps(entry) + "\n")

    print(describe(decode_token(token)))
    print("\nwrote %s.zkl (app / runtime install) and %s.inc (kernel/license/)" % (out, out))


def cmd_verify(a):
    with open(a.file) as f:
        blob = unarmor(f.read(), TOKEN_BEGIN, TOKEN_END)
    tok = decode_token(blob)
    pub, _ = repo_pubkey()
    print(describe(tok))
    problems = []
    if tok["magic"] != LICENSE_MAGIC or tok["format"] != FORMAT:
        problems.append("malformed header")
    try:
        pub.verify(tok["signature"], tok["payload"])
    except InvalidSignature:
        problems.append("BAD SIGNATURE (not issued by this owner key)")
    if tok["api_major"] < API_MAJOR:
        problems.append("token is for API %d, current API is %d" % (tok["api_major"], API_MAJOR))
    if tok["expires_at"] and tok["expires_at"] < dt.datetime.now(dt.timezone.utc).timestamp():
        problems.append("expired")
    if a.tag is not None and tok["binding"] not in (bytes(32), binding_for(a.tag)):
        problems.append("bound to another kernel tag")
    print("\nresult     : %s" % ("VALID" if not problems else "INVALID - " + "; ".join(problems)))
    sys.exit(1 if problems else 0)


def cmd_show(a):
    with open(a.file) as f:
        print(describe(decode_token(unarmor(f.read(), TOKEN_BEGIN, TOKEN_END))))


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
    s.add_argument("--out", help="output file prefix")
    s.set_defaults(fn=cmd_issue)

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
