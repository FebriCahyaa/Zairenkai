#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Copyright (C) 2026 FebriCahyaa
"""Generate SHA-512 and Ed25519 test vectors for the ZKFC C verifier."""
import hashlib
import os
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

L = 2**252 + 27742317777372353535851937790883648493


def raw_pub(key):
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def main(out):
    rnd = __import__("random").Random(0x5A4B)
    lines = []
    for n in [0, 1, 3, 111, 112, 127, 128, 129, 239, 240, 255, 256, 1000, 4097]:
        msg = bytes(rnd.getrandbits(8) for _ in range(n))
        lines.append("H %s %s" % (msg.hex() or "-", hashlib.sha512(msg).hexdigest()))
    for i in range(24):
        key = Ed25519PrivateKey.from_private_bytes(bytes(rnd.getrandbits(8) for _ in range(32)))
        msg = bytes(rnd.getrandbits(8) for _ in range(rnd.randint(0, 300)))
        sig = key.sign(msg)
        pk = raw_pub(key)
        lines.append("V 1 %s %s %s" % (pk.hex(), sig.hex(), msg.hex() or "-"))
        # Corrupted message, signature and key must fail.
        if msg:
            bad = bytearray(msg); bad[rnd.randrange(len(bad))] ^= 1 << rnd.randrange(8)
            lines.append("V 0 %s %s %s" % (pk.hex(), sig.hex(), bytes(bad).hex()))
        bads = bytearray(sig); bads[rnd.randrange(64)] ^= 1 << rnd.randrange(8)
        lines.append("V 0 %s %s %s" % (pk.hex(), bytes(bads).hex(), msg.hex() or "-"))
        # Malleated S + L must be rejected (non-canonical).
        s = int.from_bytes(sig[32:], "little") + L
        if s < 2**256:
            lines.append("V 0 %s %s %s" % (pk.hex(), (sig[:32] + s.to_bytes(32, "little")).hex(), msg.hex() or "-"))
        other = raw_pub(Ed25519PrivateKey.generate())
        lines.append("V 0 %s %s %s" % (other.hex(), sig.hex(), msg.hex() or "-"))
    # RFC 8032 test 1 and 2.
    lines.append("V 1 d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a "
                 "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b -")
    lines.append("V 1 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c "
                 "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00 72")
    with open(out, "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "vectors.txt"))
