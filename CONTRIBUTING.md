# Contributing

The library follows the contracts of `mcp-contracts`. A change that needs a different contract (a schema, an
audit event, the manifest) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs approval
before it is implemented here.

## Setup

JDK 25 and a container runtime (one test starts Redis with Testcontainers). Linux and macOS with bash 3.2 or newer;
Windows only through WSL2. The `mcp-contracts` artifacts come from Maven Central; before they are released they must be in `~/.m2`.

```bash
ci/run.sh verify     # text checks, then clean verify; the same script runs in CI
```

`ci/run.sh verify` must pass before a merge. `ci/run.sh hygiene` runs only the text checks (no non-ASCII quotes or
spaces, no tool traces in sources).

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
