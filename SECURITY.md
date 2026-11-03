# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: Security tab of this repository, "Report a vulnerability"
(private vulnerability reporting). Do not open a public issue for a security problem.

Include the affected version (`pom.xml`), the module (`mcp-platform-starter` or `mcp-platform-test`), the
configuration involved, and steps to reproduce. A request that gets past the internal JWT check, a way to read a
backend credential from a log or a tool result, or a result that returns a resource the policy denies is in scope.

## Scope

In scope: the code of `mcp-platform-starter` (internal JWT verification, credential handling, policy engine,
result filtering, tool error mapping, audit events, cursors, manifest generation).

Out of scope:

- `mcp-platform-test` is test support. It publishes private test keys on purpose (they come from `mcp-contracts`,
  `test-vectors`) and must never be on a production classpath. A deployment that accepts a key named `test-*` or `dev-`
  is a misconfiguration of that deployment; the starter refuses them outside the `dev` and `devstack` profiles.
- The `dev` and `devstack` profiles relax TLS, mTLS and the replay store by design. They are refused in a container,
  a Kubernetes pod and under systemd unless the documented acknowledgements are set; running them elsewhere is the
  operator's decision.
- The gateway, the credential broker and the policies are separate repositories.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release
(see "Wersjonowanie" in the README).
