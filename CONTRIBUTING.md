# Contributing

The library follows the contracts of `mcp-contracts`. A change that needs a different contract (a schema, an
audit event, the manifest) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs approval
before it is implemented here.

## Setup

JDK 25 and a container runtime (one test starts Redis with Testcontainers). The `mcp-contracts` artifacts come from Maven Central; before they are released they must be in `~/.m2`.

```bash
./mvnw -B verify     # the same command runs in CI
```

`./mvnw -B verify` must pass before a merge.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
