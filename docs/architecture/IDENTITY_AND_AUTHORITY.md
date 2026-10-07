# Identity and Authority Model

Zairenkai separates four concepts that are often incorrectly collapsed:

1. **Identity** — what device/kernel is running.
2. **Capability** — what primitive is actually exposed.
3. **Authority** — whether an operation is allowed to use that primitive.
4. **Policy** — what behavior should be selected within those boundaries.

The permission vocabulary is semantic and vendor-neutral. Examples include
`tune.cpu`, `tune.gpu`, `tune.memory`, `tune.thermal`, `security.policy` and
`control.reset`.

The default authority policy is deny-by-default. The kernel remains the hard
security boundary, while zperfd provides an additional runtime policy layer.

Root is not treated as evidence that every requested operation is safe. The
operation still passes compatibility, capability, validation and transaction
checks.
