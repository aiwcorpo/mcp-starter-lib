# AGENTS.md

The shared Java library of [MCP Platform](https://github.com/aiwcorpo/mcp-platform): every MCP server (`mcp-server-jira`,
`-confluence`, `-gitlab`, `-servicedesk`, `-opensearch`, `mcp-server-template`, `mcp-server-adapter`) depends on it. It verifies the
internal JWT of `mcp-gateway`, takes the user's credential from the gateway, audits every tool call and lets only safe errors out.
Its interfaces are contracts of `mcp-contracts` (C2 internal JWT, C3 credential, C6 manifest, C8 audit, C10 tool errors, C14
`openapi/mcp-server.v1.yaml`). README.md is the reference for behaviour and configuration; CONTRIBUTING.md for setup.

## Commands

JDK 25 is required (`java.version` 25); the default `java` on PATH may be older. On macOS with Homebrew:
`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.

```bash
./mvnw -B verify                                          # full CI run, about 1 min, ~1100 tests; needs Docker
./mvnw -B verify '-Dtest=!RedisJtiReplayStoreTest'        # without Docker (what CI runs for a pull request)
./mvnw -B -pl mcp-platform-starter test -Dtest=InternalJwtFilterTest   # one test class
./mvnw -B -Prelease -Dgpg.skip -DskipTests verify         # sources and javadoc jars; javadoc must pass doclint
```

`RedisJtiReplayStoreTest` starts Redis with Testcontainers; with a Docker that has no route to the host set
`TESTCONTAINERS_HOST_OVERRIDE`. The `mcp-contracts` artifacts come from Maven Central; an unreleased contracts version must be
installed in `~/.m2` first. There is no separate lint or formatter step.

## Layout

- `mcp-platform-bom`: versions only. `mcp-platform-starter`: the auto-configuration. `mcp-platform-test`: `TestGateway`,
  `LocalGateway`, `PolicyDecisions`; servers use it in `test` scope only.
- The test vectors (`*VectorsTest`, `jwt/*.json`) and schemas are read from the `mcp-contracts` jar on the classpath
  (`mcp-contracts/test-vectors/...`). Never copy or edit them here; a vector change is a contract change.
- Tests that assume a developer machine skip themselves on a host with a deployment signal (`DeploymentSignalAssumption`). The
  release workflow fails if any test was skipped.
- `STATUS.md` is a local, git-ignored file; do not commit it.

## Rules

- Contract first: a change to a schema, audit event, error code, header or the manifest starts as a `proposed` CC entry in
  `CONTRACT_CHANGES.md` of `mcp-contracts` and is implemented here only after a person approves it. Cite the CC id in the commit.
- Every public type in `mcp-platform-starter` and `mcp-platform-test`, and every configuration property, is API of all servers.
  SemVer with one version for all modules: removing or changing any of it is a new major version and needs a
  `### Migracja serwera` part in the CHANGELOG entry. Prefer an additive change; say in the PR which servers must change.
- The order of JWT checks and the rejection reasons follow the reference receiver of `mcp-contracts`; every `test-vectors/jwt`
  vector must keep giving the same status and reason.
- Fail closed: an unavailable JWKS or Redis is `503 upstream_unavailable`, never a pass. Unauthenticated paths are exact strings,
  never patterns. Test keys (`test-*`, published test keys) stay rejected outside the `dev` profile; the `dev` profile stays refused
  in a container.
- Never log or return a credential, an argument value, a tool result or a backend error body. Audit (logger
  `pl.aiwcorpo.mcp.audit`) carries lengths and codes only. Tests assert this on a log appender read through `CapturedLogs.snapshot`.
- Only `SanitizedToolException` leaves a tool, with C10 codes only. Log lines are `event=<name> key=value`.
- Test methods are sentences in camelCase (`theGatewaysCredentialIsTakenAndHiddenFromTheRestOfTheRequest`). Add a negative test for
  every refusal path.
- CHANGELOG.md: newest first, CC ids in the bullets. The version heading is written by the person who releases.
- Actions in workflows are pinned to a commit SHA and the build image to a digest; keep them pinned when you change them.

## Boundaries

Ask a person before you:

- change a version in any `pom.xml`, create or push a tag (never `git push --tags`: local `v3.0.0-beta-N` tags must stay
  unpublished), or touch `.github/workflows/release.yml`. Publishing to Maven Central and pressing Publish in the Portal is done
  by a person.
- push to `main`, or use real systems, accounts or tokens. Tests use the contracts' test keys and dummy data only.

Never commit secrets, `.env` files, TLS or GPG material (`*.pem`, `*.key`, `*.p12`, `*.jks`), internal host names or IP addresses.

## Pull requests

One change per PR. Commit subject in English, imperative, up to 72 characters; the body says what changed and why, not the
process. Tests go in the same commit as the code. `./mvnw -B verify` must pass. Update README.md and CHANGELOG.md when a server
author would notice the change. See CONTRIBUTING.md.
