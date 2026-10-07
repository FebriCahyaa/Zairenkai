# Zairenkai Local API

Zairenkai Local Control Protocol (ZLP) is the private machine API between root-side
components. Version 1 is observation-only by design. It runs over a Unix socket,
uses bounded length-prefixed TOML envelopes, authenticates the peer with
`SO_PEERCRED`, and accepts root peers only.

Mutation endpoints are intentionally absent from v1. Any future mutation endpoint
must route through Core Authority, Sentinel, ZKFC license/API validation, the durable
transaction engine, and post-write observation/reconciliation. A new client must
never gain direct sysfs mutation access merely because it can open the socket.
