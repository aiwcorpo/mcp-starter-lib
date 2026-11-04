# mcp-platform: a library for MCP servers behind the gateway

The shared library for the MCP servers of the platform (`mcp-jira`, `mcp-confluence`, ...). Since 3.0 a server stands only behind
the gateway: it does not sign users in, does not decide who may call a tool and does not ask for confirmations; the gateway does
all of that. The library verifies the gateway's internal JWT, receives the credential for the target system, audits every call and
makes sure that only safe errors leave a tool.

Stack: Java 25, Spring Boot 4.1.0, Spring AI 2.0.0 (MCP SDK 2.0.0), `mcp-contracts` 6.3.2.
Version: **3.0.0** (state and plan in `STATUS.md`, changes since 2.x in `MIGRATION-3.0.md`).

## Who this is for and how to start

The library is for the author of an MCP server that stands behind the platform gateway (`mcp-gateway`) and exposes the tools of one
system (Jira, Confluence, GitLab, ServiceDesk, OpenSearch). It is not a product to run: you add one dependency and write tools as
`@Tool` methods; the starter verifies the caller, handles the credential, the resource policy, the audit and safe errors.
Requirements: JDK 25, Maven (a wrapper is in the repository), Linux or macOS (bash 3.2 or newer); Windows only through WSL2. A
skeleton of a ready server is in `mcp-server-template`.

This file is the reference for what the starter does and how it is configured. Task-oriented guides (generating a server from the
template, writing tools, choosing the credential type, registering the manifest, testing, putting a foreign server behind the
platform, migrations) are in the developer guide of the platform documentation (`mcp-docs`, directory `docs/developer`). Within
this file: the first tool is "First server", running without the gateway is "Running locally", tests are "Server tests without the
gateway", settings are "Configuration", and moving from an earlier version is `MIGRATION-3.0.md`.

### Using the artifacts

Maven Central, group `pl.aiwcorpo.mcp`; no repository entry and no credentials needed. Import the BOM and add the starter (the
BOM also manages `mcp-contracts` 6.3.2, `mcp-cedar-entities`, `mcp-policy-bundle`, `mcp-manifest` and Spring AI):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>pl.aiwcorpo.mcp</groupId>
      <artifactId>mcp-platform-bom</artifactId>
      <version>3.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>pl.aiwcorpo.mcp</groupId>
    <artifactId>mcp-platform-starter</artifactId>
  </dependency>
  <dependency>
    <groupId>pl.aiwcorpo.mcp</groupId>
    <artifactId>mcp-platform-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

| Coordinates | Packaging |
| --- | --- |
| `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0` | pom |
| `pl.aiwcorpo.mcp:mcp-platform-starter:3.0.0` | jar (with sources and javadoc) |
| `pl.aiwcorpo.mcp:mcp-platform-test:3.0.0` | jar (with sources and javadoc), test scope only |
| `pl.aiwcorpo.mcp:mcp-platform:3.0.0` | pom, parent of the BOM |

Every file on Central carries a GPG signature (`.asc`). A version is immutable once published; a fix is a new number.

To work on the library itself, `./mvnw install` installs the modules into `~/.m2`.

## Modules

| Artifact | What it is | Scope in a server |
| --- | --- | --- |
| `pl.aiwcorpo.mcp:mcp-platform-bom` | versions: starter, test, `mcp-contracts`, Spring AI | `import` |
| `pl.aiwcorpo.mcp:mcp-platform-starter` | auto-configuration (below) and the MCP transport (streamable HTTP) | `compile` |
| `pl.aiwcorpo.mcp:mcp-platform-test` | `TestGateway`: a JWKS and internal JWTs from the contracts' test keys | **`test` only** |

The version of the `mcp-platform` line (3.x) has nothing to do with the contracts version (the BOM manages both).

## What the starter does on every request

```
gateway --mTLS + JWT C2 + X-MCP-Credential--> SecurityHeadersFilter
  -> CorrelationIdFilter     X-Request-Id -> MDC requestId
  -> InternalJwtFilter       gateway certificate, JWT C2 bound to the request -> CallerContext; otherwise 401
  -> CredentialHeaderFilter  X-MCP-Credential(-Type) -> CredentialContext; the headers are hidden from the rest
  -> MCP transport -> AuditingToolCallback -> argument validation -> ResourcePolicy (Cedar: call, resources from @ResourceRef)
                                           -> [ToolCallInterceptor...] -> the server's @Tool -> result filter
  GET /mcp-manifest (JWT C2, no tool) -> the C6 manifest built from the server at startup
```

**`InternalJwtFilter` (C2).** It lets through only a request with a client certificate of CN `mcp-gateway` and a correct internal
JWT: ES256, `kid` from the gateway JWKS, `iss`, `aud` == `server-id`, `exp`/`iat` with a tolerance of up to 30 s, `exp - iat` up to
60 s, claims matching `schemas/internal-jwt.schema.json` (validated with the schema from the contracts jar; the claims `vs` and
`vsc` no longer exist, and a token with either is `401 invalid_claims`), a `jti` used for the first time (Redis), `rid` ==
`X-Request-Id`, `tool` present exactly for `tools/call` and equal to `params.name`, and the service variant only for `initialize`,
`server/discover`, `tools/list`, `ping` and `GET /mcp-manifest`. Keys with an identifier `test-*` and the published test keys
(recognized by SPKI from `mcp-contracts/security/published-test-keys.json`) are rejected unless `allow-test-keys=true` in the `dev`
profile. The order of checks and the rejection reasons are those of the contracts' reference receiver, so all 57 vectors of
`test-vectors/jwt` give the same result and reason.

A rejection is `401 {"error":"unauthenticated",...}` with `WWW-Authenticate: Bearer`, the reason only in the log
(`event=internal_jwt_rejected reason=<code>`). An unavailable JWKS or Redis is `503 upstream_unavailable`, never a pass. The request
body is read once (limit `max-request-body`, otherwise 413) and must be a single JSON object without repeated keys (otherwise 400),
because the method and the tool name come from it: a JSON-RPC array or `{"name":"a","name":"b"}` could be read differently by the
MCP transport.

Only the exact paths in `internal-jwt.unauthenticated-paths` (by default `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`) pass without a JWT, compared with the path normalized by the container and only when the raw URI is
exactly that path: a URI with `;`, `..`, `%`, `\` or `//` always goes through full verification. Patterns (`/**`) cannot be
configured.

**`CallerContext`.** The verified claims of the current request: `subject`, `username`, `groups`, `client`, `tool`, `requestId`,
`policyRevision`, `mfaAgeSeconds`; `CallerContext.current()` on the request thread. The fields correspond to the input of the Cedar
entity builder from `mcp-cedar-entities`, from which `ResourcePolicy` builds the caller.

**`CredentialContext` (C3).** The credential for the target system, injected by the gateway:

```java
BackendCredential credential = CredentialContext.require("jira");      // missing -> credential_required (C10)
restClient.get().uri("/rest/api/2/issue/{key}", key)
        .headers(credential::applyTo)                                    // Authorization: <scheme> <value>
        .retrieve().body(Issue.class);
```

`BackendCredential` has `scheme()` (`Bearer`, `Basic`, `Token`, `ApiKey`), `value()`, `type()` (`user-oauth`, `user-secret`,
`token-exchange`, `service-account`), `authorizationValue()` and `applyTo(HttpHeaders)`; `toString()` does not show the value. A
server serves one system (`server-id` = `mcp-<system>`); asking for another is a programmer error (`IllegalStateException`). After
reading, `Authorization`, `X-MCP-Credential` and `X-PAT-*` are invisible to the rest of the request. A bad format, a type without a
credential, or two credentials give 400.

**`AuditingToolCallback`.** It wraps every tool (a `ToolCallbackProvider` or `ToolCallback` bean): it runs the tool only for a
verified caller whose JWT has a `tool` equal to the tool name (otherwise `unauthenticated` and the tool does not run), calls the
`ToolCallInterceptor` beans, maps exceptions to C10 errors and writes one C8 `tool_call` event.

**Tool errors (C10).** The only exception that leaves a tool is `SanitizedToolException`; its message is an object
`{"error":"<code>","message":"...",...}` matching `schemas/tool-error.schema.json`. Mapping: `CredentialRequiredException` ->
`credential_required` (`system`, `connectUrl`); `InvalidToolArgumentException` -> `invalid_argument` with your own message (you
write it yourself, never with the content of a backend response); any other `IllegalArgumentException` (library, conversions) ->
`invalid_argument` with a constant text; `SecurityException` -> `access_denied`; an unreachable backend or 502/503/504 ->
`upstream_unavailable`; everything else -> `internal_error`. Custom mappings are `ToolErrorMapping` beans, with C10 codes only; a
result that does not match `tool-error.schema.json` (for example a missing required field) reaches the client as `internal_error`.
The credential of the current request is removed from every message (to the client and to the log), also in URL-encoded and
JSON-escaped form. Bodies of backend error responses reach neither the client nor the log: when the exception chain contains an
exception from the packages `org.springframework.web.client`, `org.springframework.http.converter`, `com.fasterxml.jackson` or
`tools.jackson` (an HTTP error, a 200 body that cannot be parsed), the log `event=tool_failed` contains only the exception classes
and the HTTP status, without messages (wrappers such as Spring AI's `ToolExecutionException` copy fragments of the body into them).
An adapter does not need to work around this with its own `defaultStatusHandler`. The level of `event=tool_failed` depends on the
meaning of the error: an expected outcome (a caller error, a missing credential, a refusal, `not_found_or_denied`, a domain
exception with a `ToolErrorMapping`) is INFO with the C10 code and the exception class, without a message or stack; an unavailable
backend is WARN; ERROR is only for `internal_error`, which is what the server did not anticipate.

**Audit (C8).** One JSON line per event on the logger `pl.aiwcorpo.mcp.audit` (`component: server`, `componentId` = `server-id`,
`requestId` = `rid`, `risk`, `credentialType`, `outcome`, `errorCode`, `latencyMs`, `argsLength`). Never values of arguments,
results or credentials; everything outside printable ASCII is written as a JSON escape.

**The risk flag.** Every tool has `@ToolRisk(RiskLevel.READ_ONLY | UPDATE | DESTRUCTIVE)`; without it, with a name that contradicts
the flag (`delete_*` as `READ_ONLY`), or with a repeated name the server does not start. The flag goes to
`readOnlyHint`/`destructiveHint` and to the audit (`risk`). `@McpTool` tools and hand-written `SyncToolSpecification` beans would
bypass the wrapper: the starter turns off the Spring AI annotation scanner
(`spring.ai.mcp.server.annotation-scanner.enabled=false`), and a server whose list contains a tool not registered through the
wrapper does not start (`RegisteredToolsCheck`).

**SYNC transport.** `CallerContext` and `CredentialContext` are `ThreadLocal`; `spring.ai.mcp.server.type=ASYNC` stops startup
(`CredentialTransportGuard`).

## Configuration

```yaml
aiwcorpo.mcp.platform:
  server-id: mcp-jira                                     # required
  auth-mode: gateway                                      # gateway | dev-pat (dev profile only)
  internal-jwt:
    issuer: https://mcp-gateway.internal                  # default
    jwks-uri: https://mcp-gateway.internal:8443/.well-known/jwks.json   # required
    jwks-ssl-bundle: mcp-internal-ca                      # Spring SSL bundle with the internal CA
    jwks-cache-ttl: 10m
    jwks-max-stale: 1h                                    # longer without a JWKS -> 503
    jwks-refresh-min-interval: 30s                        # an unknown kid fetches the JWKS at most once per 30 s
    clock-skew: 30s                                       # at most 30 s
    max-lifetime: 60s                                     # at most 60 s
    replay-store: redis                                   # redis | memory (dev profile only)
    receiver-mode: native                                 # native | adapter (adapter sidecar, CC-226)
    allow-test-keys: false                                # true only in the dev profile
    max-request-body: 4MB
    unauthenticated-paths: [/actuator/health, /actuator/health/liveness, /actuator/health/readiness]
  mtls:
    required: true                                        # false only in the dev profile
    allowed-client-names: [mcp-gateway]
  credential:
    connect-url: http://localhost:18090/connections?system={system}   # required, for credential_required
  policy:                                                 # the C7 policy bundle, the same as in the gateway (see "Resources and policies")
    main:
      url: https://policies.internal/policies/main/latest.json   # required; https outside dev
      key-id: policies-main-2026                          # required; test-* only with allow-test-keys in dev
      public-key-file: /etc/mcp/policies-main.pub.pem     # or public-key: <PEM>; Ed25519, SPKI
      refresh-interval: 30s                               # default 30 s
      minimum-revision: 20261001000000-0000000            # optional, the lowest ever accepted (CC-066)
    overrides:                                            # optional emergency bundle, a separate key and address
      url: https://policies.internal/policies/overrides/latest.json
      key-id: policies-overrides-2026
      public-key-file: /etc/mcp/policies-overrides.pub.pem
      refresh-interval: 5s                                # default 5 s
    state-dir: /var/lib/mcp/policy-state                  # required outside dev: rollback protection across restarts
    allow-test-keys: false                                # true only in the dev profile
    startup-timeout: 30s                                  # how long startup waits for the first bundle (without it the server does not start)
    overrides-grace: 2m                                   # after a restart, wait for an emergency bundle that was active
    connect-timeout: 2s
    read-timeout: 10s                                     # time of one whole request, body included (a hung repository does not block threads)
    ssl-bundle: mcp-internal-ca                           # optional, trust for the bundle repository
  manifest:                                               # the C6 manifest (GET /mcp-manifest)
    version: 1.4.0                                        # required, the server's SemVer
    internal-url: https://mcp-jira.internal:8443/mcp      # required, https; informational (the gateway has its own configuration)
    credential-required: true
    accepted-credential-types: [user-oauth, user-secret]  # required; the first is the default type (context.credentialType in Cedar); or service-account alone
    output-file: target/mcp-manifest.json                 # optional: write the manifest at startup (registration in the gateway)
  tools.unclassified: fail                                # fail | destructive
  tools.max-filtered-elements: 200                        # the longest list in a result checked by policy (longer = internal_error)
  tools.max-result-size: 1MB                              # a larger tool result = internal_error
  paging:                                                 # cursors of paging tools (CursorCodec)
    cursor-key: ${MCP_CURSOR_KEY}                         # Base64, at least 32 bytes; the same on every server instance
    cursor-validity: 15m
  capability:                                             # the server's CapabilityProbe (optional)
    timeout: 10s
    interval: 5m                                          # interval between probes of a working system
    retry-interval: 30s                                   # interval when the system does not answer
spring.ai.mcp.server.name: mcp-jira                      # protocol/type/endpoint are set by the starter
spring.data.redis:                                        # the jti store
  host: redis
  username: mcp-jira                                      # keys jti:mcp-jira:*, SET (NX PX) only
  password: ${REDIS_MCP_JIRA_PASSWORD}
server.ssl:                                               # the server terminates mTLS
  bundle: mcp-jira
  client-auth: need
```

The server does not start when:
- `server-id`, `jwks-uri` or `connect-url` is missing, or `clock-skew`/`max-lifetime` exceed the C2 limits;
- the system in `server-id` (`mcp-<system>`) is not a contracts `SystemId` (`jira`, `confluence`, `gitlab`, `servicedesk`,
  `opensearch`; the list is read from `common.schema.json` in the contracts jar). In the `dev` profile this is only a warning in
  the log (for example for the sample server `mcp-example`), but then `credential_required` reaches the client as `internal_error`,
  because it fails C10 validation;
- `unauthenticated-paths` contains anything other than `/actuator/health`, `/actuator/health/liveness`,
  `/actuator/health/readiness`; the MCP protocol is anything other than `STREAMABLE`;
- `policy.main.url`, `key-id` or the public key (`public-key` or `public-key-file`) is missing, `manifest.version` (SemVer),
  `manifest.internal-url` (https) is missing, or `manifest.accepted-credential-types` is missing with `credential-required`;
  `accepted-credential-types` is empty (also without `credential-required`) or mixes `service-account` with user types (CC-168);
  outside `dev`, `policy.state-dir` is missing, the bundle address is not https or `policy.allow-test-keys=true`;
- the first policy bundle cannot be loaded within `startup-timeout` (no repository, a bad signature, a `test-*` key without
  `allow-test-keys`, a revision lower than the one stored in `state-dir`): a server without a signed policy does not start;
- the server manifest fails `server-manifest.schema.json` or the mcp-manifest rules (outside `dev`, where it is a warning
  `event=manifest_rejected_by_rules`): for example a tool without `@ToolTitle`, a schema without a limit, `confirmation_code` among
  the arguments;
- leftover 2.x settings (`guard.*`, `pat-header-prefix`);
- in the `dev` profile, `server.address` is not a loopback address (`127.0.0.1`, `::1`, `localhost`); the starter sets `127.0.0.1`
  by default in `dev`, so the refusal concerns an explicit override (for example `SERVER_ADDRESS=0.0.0.0`). A server in a container
  that the gateway connects to therefore cannot run in the `dev` profile. The same for a separate management server: with
  `management.server.port` different from `server.port`, `dev` requires a loopback `management.server.address`;
- the `dev` profile (the only active one) is used in a deployment environment: Spring Boot detects a cloud platform (Kubernetes,
  Cloud Foundry and so on; `spring.main.cloud-platform=none` is not a platform), `KUBERNETES_SERVICE_HOST` and
  `KUBERNETES_SERVICE_PORT` are set, `INVOCATION_ID` is set (a systemd unit), or the process runs in a container (`/.dockerenv`,
  `/run/.containerenv`, the variable `container`, a cgroup path with `docker`, `kubepods`, `libpod`, `crio`, `containerd`). `dev`
  removes TLS, mTLS and Redis from the server, so `SPRING_PROFILES_ACTIVE=dev` in an `.env` file, a compose template, an image or a
  systemd unit stops startup (before the socket is opened) instead of silently leaving a server protected by the JWT alone. Run
  `dev` from a terminal on a developer workstation. A VS Code devcontainer, Codespaces, Toolbox and Distrobox are containers too:
  the refusal names them and gives three ways out (run the server outside the container; run it from a test, because the test
  context is not checked; or use a profile other than `dev` with TLS, mTLS and Redis configured as in a deployment). The starter
  does not allow `dev` in a container on the strength of developer environment markers (`REMOTE_CONTAINERS`, `CODESPACES`): that is
  a threat model decision not yet taken. This is a net for copied configuration, not a security boundary: whoever controls the
  process environment can hide the signals (an empty `SPRING_MAIN_CLOUD_PLATFORM`, a file in another layout); process variables
  (`INVOCATION_ID`, `KUBERNETES_*`) are also read directly, so `-DINVOCATION_ID=` does not mask them. A context built by Spring
  TestContext (`@SpringBootTest`, slices) is not a deployment: server tests with `TestGateway` run in `dev` in CI on Kubernetes and
  under a systemd runner. A test context is recognized by test framework frames on the stack of the thread that builds it (a class
  from the package `org.springframework.test.context` or `org.springframework.boot.test.context` loaded from the `spring-test` or
  `spring-boot-test` jar), not by a property that could be set in a deployment. This is a net, not a boundary: a custom class in
  those packages in the server's jar does not count as a test framework, but server code that deliberately forges the jar or the
  stack is outside the threat model (whoever controls the server code controls its configuration too). The test of the address rule
  in `dev` does not start the application through `SpringApplicationBuilder` (on a CI host the "deployment" refusal comes first); see
  "Server tests without the gateway";
- `spring.autoconfigure.exclude` (also `SPRING_AUTOCONFIGURE_EXCLUDE`) names a class from the package `pl.aiwcorpo.mcp.platform.`,
  or an `exclude` on `@SpringBootApplication` removes `platformConfigurationGuard`, `internalJwtVerifier`, `internalJwtFilter`,
  `toolCallbackAuditPostProcessor` or `platformMcpServerCustomizer` (the platform's MCP server auto-configuration), in every
  profile including `dev` (the guard ties the `dev` relaxations to the loopback address), outside a test context. A server test
  context is checked by `DevProfileGuardAssertions.assertServerIsGuarded(context)` (see "Server tests without the gateway");
- the `dev` profile is active together with another profile (also through a profile group). The relaxations work only when `dev` is
  the **only active** profile; `spring.profiles.default=dev` does not count;
- outside such a `dev` profile: `dev-pat`, `allow-test-keys`, `replay-store: memory` or `mtls.required: false` is on; `jwks-uri` is
  not an absolute `https` URL; `server.ssl.client-auth=need` is missing; `management.server.port` is set and differs from
  `server.port` (a separate management server does not pass through the JWT/mTLS filter).

The starter's default values (lowest priority): `management.health.redis.enabled=false` (the Redis health check uses `INFO`, which
the user `mcp-<system>` in `mcp-platform-dev` may not run), `spring.data.redis.timeout=2s` (a hung Redis quickly gives 503), the
MCP transport as in "First server", `spring.ai.mcp.server.annotation-scanner.enabled=false`.

**The `devstack` profile (a shared development stack in containers).** `dev` is forbidden in a container, while the `mcp-platform-dev`
stack runs servers in containers with `test-*` signing keys. The `devstack` profile is for it: it keeps **everything** from
production (TLS and mTLS, Redis, https, `state-dir`, no `dev-pat`, `replay-store: memory`, `mtls.required: false`) except that it
accepts `test-*` keys and the published test keys (`internal-jwt.allow-test-keys` and `policy.allow-test-keys`). It needs two
consents at once, so that a copied configuration does not reach production: the property
`aiwcorpo.mcp.platform.devstack.acknowledged=true` **and** the environment variable `MCP_DEVSTACK_ACKNOWLEDGED=true` (both in the
development stack's configuration, not in the server image). The variable is read only from the process environment: a property of
that name from `application.yml`, the command line or `SPRING_APPLICATION_JSON` is not enough. The configuration guard runs before
the policy bundle and keys are fetched by platform beans (an explicit call at the start of `policyEngine`, `gatewayKeys`,
`jtiReplayStore` and `internalJwtVerifier`, also with `spring.main.lazy-initialization=true`); a server's own `GatewayKeys` and
`JtiReplayStore` are created before `internalJwtVerifier` but after the guard, as long as they do no I/O in the constructor. The
starter version in a server comes from the property `mcp-platform.version` (the template) or the `<version>` of the
`mcp-platform-bom` import (described in `MIGRATION-3.0.md`). It must be the only active profile. Every start logs `WARN
event=devstack_profile`, the metric `mcp_server_devstack_active` is 1, and the C6 manifest gets `"environment": "dev"` (CC-138; a
gateway in production mode rejects such a manifest: `422 manifest_rejected`, reason `environment_mismatch`; the field does not
enter `definitionHash` and is absent in other profiles, which means production). `test-*` keys under a profile other than `dev` and
`devstack` are rejected as before, so a production key cannot be named `test-...`. **`dev-` keys (CC-137)** (the `kid` of the JWT
verifier and the key identifier of the policy bundle, which the development stack generates locally) pass only in dev mode, that
is with the `devstack` profile (with both consents) **and** `allow-test-keys=true`; `allow-test-keys` alone (server unit tests, the
`dev` profile) accepts `test-*` keys but rejects `dev-` with the reason `test_key_not_allowed` (JWT: 401; bundle: the previous one
stays).

**The `dev-pat` mode** (only when `dev` is the only active profile): besides `X-MCP-Credential`, the server accepts a developer
token in `X-PAT-<system>` (for example `X-PAT-Jira`) as a `Bearer` of type `user-secret`. The internal JWT is still required.

## First server

The starter sets the MCP transport (lowest priority, the server may override it): `spring.ai.mcp.server.protocol=STREAMABLE`,
`spring.ai.mcp.server.type=SYNC`, `spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp`. The server name comes from
`spring.ai.mcp.server.name`.

A minimal server is three files in one Maven module (`mcp-jira/`), built with an installed Maven 3.9+ (`mvn`) or the project
wrapper (`./mvnw`). Java 25 is required to build and run; the `java` on PATH may be older, so the commands below use
`$JAVA_HOME/bin/java` (`JAVA_HOME` points at a JDK 25).

`pom.xml`: the parent is `spring-boot-starter-parent` 4.1.0, `java.version` is 25, the `dependencyManagement` and dependencies are
those from "Using the artifacts", `<finalName>mcp-jira</finalName>` sets the jar name, and `spring-boot-maven-plugin` makes it an
executable jar. `mcp-server-template` has the complete file.

`src/main/resources/application.yml`: the configuration from "Configuration" above. To run in `dev`, `server-id`,
`internal-jwt.jwks-uri`, `credential.connect-url`, `policy.main.*` (the address, `key-id`, the public key; in `dev` with
`policy.allow-test-keys: true`) and `manifest.*` (version, `internal-url`, credential types) are enough; the rest has defaults or is
removed in `dev`. A server without a policy bundle does not start, so the first local start needs a bundle repository
(`mcp-platform-dev`: `http://127.0.0.1:18747/policies/main/latest.json`, key `test-policies-main-dev` from
`mcp-policies/keys/dev/`); in tests `TestGateway` does this.

`src/main/java/jira/JiraServer.java`. Tools are registered with a `ToolCallbackProvider` bean; a bean with `@Tool` methods alone is
not enough (Spring AI then answers `Unknown tool`):

```java
package jira;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

@SpringBootApplication
public class JiraServer {

    public static void main(String[] args) {
        SpringApplication.run(JiraServer.class, args);
    }

    @Bean
    ToolCallbackProvider jiraTools() {
        return SchemaLimitedToolCallbacks.of(new JiraTools());
    }

    public static class JiraTools {

        @Tool(name = "get_issue", description = "Returns an issue by key.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get issue")
        @ResultWithoutResources     // the result is plain text; see "Results: ResultFilter"
        public String getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String issueKey) {
            // A real adapter calls Jira with: .headers(CredentialContext.require("jira")::applyTo)
            return issueKey + " read with a " + CredentialContext.require("jira").scheme().wireName() + " credential";
        }
    }
}
```

`mvn package` produces `target/mcp-jira.jar` (the name comes from `finalName`; without it the jar would be
`mcp-jira-0.1.0-SNAPSHOT.jar`). Starting and checking it: next section.

## Running locally

A server with the configuration from "Configuration" (with `server.ssl`, the internal CA bundle and Redis) starts on a developer
workstation without certificates, a CA or Redis if `dev` is the **only** active profile. In that profile the starter sets, above
the server's `application*.yml` (the command line, system properties and environment variables still win):

| Property | Value in `dev` | Why |
| --- | --- | --- |
| `server.address` | `127.0.0.1` (default; a non-loopback address stops startup) | the `dev` relaxations do not leave the machine |
| `server.ssl.enabled` | `false` | no `server.ssl.bundle` and no server certificate (no `NoSuchSslBundleException`) |
| `aiwcorpo.mcp.platform.mtls.required` | `false` | no gateway client certificate |
| `aiwcorpo.mcp.platform.internal-jwt.replay-store` | `memory` | no Redis |
| `aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle` | empty | no internal CA bundle (the JWKS over plain https or http) |

`internal-jwt.allow-test-keys` is not forced: set `true` in the server's `application-dev.yml` when the local gateway
(`mcp-platform-dev` or `TestGateway`) signs with test keys. `jwks-uri` stays required but is not fetched until a request with a JWT
arrives.

Start (the starter's dependencies must be in `~/.m2`: `./mvnw install` in the starter repository, or a released version):

```bash
export JAVA_HOME=/path/to/jdk-25   # any JDK 25; ci/run.sh finds one by itself
mvn spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=18600
# or after mvn package (a jar with finalName `mcp-jira`; Java 25, not the `java` on PATH if it is older):
$JAVA_HOME/bin/java -jar target/mcp-jira.jar --spring.profiles.active=dev --server.port=18600
```

In a devcontainer, Codespaces, Toolbox or Distrobox (a developer container) `dev` will not start: the starter sees the container and
refuses (the same check protects an image with `SPRING_PROFILES_ACTIVE=dev`). Run the server outside the container, from a test (a
`@SpringBootTest` with `TestGateway` is not checked), or in a profile other than `dev` with TLS, mTLS and Redis configured as in a
deployment (section "Configuration"). Whether to allow `dev` in a developer container is undecided.

Check:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18600/actuator/health      # 200
curl -s -i -X POST http://127.0.0.1:18600/mcp -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -1                   # HTTP/1.1 401
```

`/actuator/health` comes from the starter's dependencies (`spring-boot-starter-actuator`). A `POST /mcp` without an internal JWT gives
401 with `WWW-Authenticate: Bearer` and an `authentication` audit event, never an anonymous answer. Calling a tool needs the
gateway's JWT (headers as in the test in "Server tests without the gateway").

Outside `dev`, configuration errors appear one after another, because Spring checks them in different phases: first the HTTP server
starts (a missing SSL bundle, certificate or CA), then `PlatformConfigurationGuard` (all violations at once in one message), and
last the tools (a missing argument limit, `@ToolRisk`). A fixed SSL error can therefore expose the next one from a later phase (a
missing argument limit is reported only after the SSL error is gone, because the HTTP server starts before the tool beans are
created). In `dev` the first phase is off, and a missing argument limit is the warning `event=tool_schema_open` instead of an error.

## Tool arguments: schema, limits, validation

Spring AI generates only types and descriptions from `@Tool`; it does not write `maxLength`, `pattern` or ranges into
`inputSchema`. The starter has one API for this (it replaces the private versions in the template and in servers):

- `@ArgumentLimits` on a parameter and on a record component: `maxLength` (mandatory for text), `minLength`, `pattern`,
  `contractPattern`, `minimum` and `maximum` (both mandatory for numbers), `maxItems` (mandatory for a list and an array; the other
  limits then describe the elements), `minItems`. `contractPattern = "JiraIssueKey"` takes the pattern (and `maxLength`, when not
  given) from the `$defs` definition in `common.schema.json` of the contracts jar, so a server does not retype identifier patterns
  (`JiraProjectKey`, `JiraIssueKey`, `ConfluenceSpaceKey`, `ConfluencePageId`, `GitLabProjectPath`, `OpenSearchIndexName`,
  `ServiceDeskTicketId`, ...). A `pattern` always has `^` and `$`, and no `|` outside a group (`^(?:a|b)$`).
- A filter is a record: limits on its components, an optional field with `@Nullable` (Spring AI then does not emit `required`); the
  object is closed (`additionalProperties: false`). A list of records: `maxItems` on the parameter, limits on the components. A
  `Map`, an object that is not a record and a list of lists cannot be bounded and stop startup.
- `SchemaLimitedToolCallbacks.of(toolObject...)` instead of `MethodToolCallbackProvider`: it builds the same `MethodToolCallback`
  and adds limits at every level and `additionalProperties: false`. A missing limit at any level, limits of the wrong type or an
  unknown `contractPattern` stop startup.

**Validation is in the starter.** MCP SDK 2.0.x validates arguments before the audit wrapper and answers with its own text (`Tool
(x) input validation failed`), without C10 and without a C8 event. The starter turns SDK validation off
(`validateToolInputs(false)`) and checks the arguments itself against the tool's `inputSchema` in `AuditingToolCallback` (the
`json-schema-validator` library, JSON Schema 2020-12: types, `required`, `additionalProperties`, `pattern`,
`minLength`/`maxLength`, `minimum`/`maximum`, `enum`). The order in `AuditingToolCallback` is: binding to the gateway call (missing
-> `unauthenticated`; the schema is not probed without authentication), validation, interceptors, the tool. A violation is
`invalid_argument` (C10) with a message such as `Invalid arguments: issueKey does not match the required format ^...$; limit is
above the maximum 100.` plus a C8 event (`outcome=error`, `errorCode=invalid_argument`). The message is built from the schema: it
contains neither the argument value nor argument names outside the schema (`unknown arguments are not allowed`). `pattern` is
executed as ECMA 262 defines it: `$` means the end of input (`"ABC-1\n"` does not match `^[A-Z]+-[0-9]+$`). The text of every
string in the arguments (also nested ones, by the rule of its own schema node) goes through `TextHygiene`: control characters (C0,
DEL, C1), U+2028/U+2029, unpaired surrogates and noncharacters, bidi override and isolate, zero-width characters, U+2060..U+2064,
BOM, tag characters and any other `\p{Cf}` except U+200C..U+200F are `invalid_argument`; `\t`, `\n`, `\r` are allowed in free text,
and in a value with a `pattern` (a key, a path) they are rejected together with every `\p{Cf}` formatting character. Object keys
(field names of a map or object) go through the same hygiene as values with a `pattern`. A tool should still check what it relies
on with the same constants.

**Fail-closed.** Outside `dev`, startup stops for a tool without an `inputSchema`, with a schema without `type: object` and
`additionalProperties: false`, or with a level without a limit at any depth (`InputSchemaBounds`): an open object, text without
`maxLength`, a list without `maxItems` or `items`, a number without `minimum` and `maximum`, a value without a type, a `pattern`
without anchors. In the `dev` profile (the only active one) there is a warning `event=tool_schema_open` and what the schema says is
enforced. The policy cannot be turned off with a bean or a custom wrapper: `ToolInputPolicy` (an enum), `AuditingToolCallback` and
`ToolCallbackAuditPostProcessor` (`final` classes with package-private constructors) cannot be built in a server;
`ToolCallbackAuditPostProcessor`, `RegisteredToolsCheck`, `ToolHintsPostProcessor` and `ToolInputPolicy` are not extension points,
and a bean of these types (or a replaced bean definition with a platform name) stops startup outside `dev`.
`PlatformConfigurationGuard`, `InternalJwtVerifier` and `InternalJwtFilter` stop startup when the server defines its own bean, in
every profile. The extension points (`@ConditionalOnMissingBean`) are `GatewayKeys`, `JtiReplayStore`, `AuditLogger`,
`ToolRiskRegistry`, `McpErrorMapper`. Custom behaviour around tools goes through `ToolCallInterceptor`. The server writes the
`pattern` values, so avoid expressions with exponential matching time.

**System content.** A tool result with text from a system (a description, a comment, a title, a log line) contains
`pl.aiwcorpo.mcp.platform.text.UntrustedText` (`UntrustedText.plain(raw, maxLength)` or `markdown(...)`). It cuts to the limit at a
word boundary, so the cut does not create a token at the end that the filter did not see (`hello @allison` cut to 10 characters is
`hello`, `truncated=true`; in a long string without spaces the trailing run of letters, digits, `-`, `.` and joining characters
drops off, so `(((...(@allison` leaves `(((...(@` and `/@all-hands` leaves `/@`; if more than 64 units would drop off, or there is
no delimiter before the end, the cut stays at the limit (a CJK sentence after a single bracket does not vanish; one long word gives
a prefix with `truncated=true`, not an empty text; a negative limit in `cut` is an `IllegalArgumentException`)). The constructor
normalizes the text with `TextHygiene.normalize`, and a removed run of invisible characters is replaced by one space so as not to
glue tokens. A server that strips syntax from text (images, links, macros, mentions) first converts the format to text (including
decoding entities such as `&#8203;`), normalizes **before** that filter, cuts with `UntrustedText.cut(text, limit)` **before** the
filter (a cut can remove the closing delimiter of a code block, and only a filter after the cut sees what the client will get) and
builds the value with `plainCut(text, truncated)` or `markdownCut(...)`; the factory `plain|markdown(raw, limit)` cuts by itself.
The factory logs WARN `event=untrusted_text_not_normalized` (not for `null` and not for a change of line endings alone) when the
text still changed after the filter. A tool result larger than `aiwcorpo.mcp.platform.tools.max-result-size` (1MB by default, UTF-8
bytes) is `internal_error`; a tool that returns lists pages them. The result must be a record; the starter then publishes an
`outputSchema` with the contracts' `UntrustedText` definition (it enters `definitionHash`) and returns the result also as
`structuredContent`. Extra fields of the system go next to `UntrustedText` in the result record.

**The nullability rule of a result.** The result schema follows the record type: a component without `@Nullable` (from any library)
is `required` and does not accept `null`, a component with `@Nullable` accepts `null`. A field that the system can leave empty
(`assignee`, `dueDate`, `resolution`) must therefore have `@Nullable`; field names are component names (Jackson annotations
`@JsonProperty`, `@JsonIgnore` are not reflected in the schema). The tool wrapper checks the result against the schema **before**
the audit write: a mismatch is the C10 `internal_error`, a C8 event with `outcome=error` and an ERROR line with the field path
(`/assignee (type)`), without values. The SDK message (`output validation failed`) does not reach the client.

**Audit of rejections.** A rejected request (a missing or bad JWT, a certificate, an unavailable JWKS or Redis) leaves in the C8
audit an `authentication` event (`outcome=denied`, `reason` = the rejection code) with only the fields allowed in contracts 2.2.5
(CC-071, CC-120), without `sub`, `client`, `tool` from an unverified token. The reasons are the codes from the JWT vectors plus
`client_certificate_rejected` (the gateway certificate) and, with `errorCode: upstream_unavailable` (a 503 response),
`jwks_unavailable` and `replay_store_unavailable` (the `jti` store). For requests whose token cannot be parsed (`missing_token`: a
missing or empty header; `malformed`: a foreign scheme, not three segments, a JWT header that is not an object), the process
writes events (and a log line) separately for each of the two reasons, at most
`aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second` per second (default 20, minimum 1; the gateway has the same
budget for `missing_token` alone); the excess reaches neither the audit nor the log, only the counter
`mcp_audit_authentication_suppressed_total{component="server",reason}` (a name shared with the broker and the gateway, CC-143), and
the request gets 401 as always. A request rejected with `400` (the body is not a single JSON object) or `413` (too large) before
token verification is not an `authentication` event (CC-152). Other reasons are not limited, including a token with `crit` or a
`typ` other than `JWT` in the header after `kid` resolution (reported as `alg_not_allowed`).

The gateway removes `confirmation_code` from the arguments before calling the server (C11), so the server schema does not declare it.

## MCP server customizer

Spring AI accepts one `McpSyncServerCustomizer` and, when one exists, skips its own `immediateExecution(true)` (the tool on the
request thread, where `CallerContext` and `CredentialContext` live). The starter therefore supplies that one customizer
(`PlatformMcpServerCustomizer`, `@Primary`), which:

1. calls the server's customizers (every other `McpSyncServerCustomizer` bean, in `@Order`),
2. at the end enforces the platform settings: `immediateExecution(true)` and `validateToolInputs(false)`; a server customizer cannot
   undo them.

A server adds its settings with an ordinary `McpSyncServerCustomizer` bean **without `@Primary`**. A customizer with `@Primary` stops
startup (`ForeignPrimaryCustomizerCheck`, on bean definitions, with a message saying what to do).

## Resources and policies

A server evaluates policy on **resources**, as the gateway evaluates it on tools: from the same signed Cedar bundle (C7), with the
same entity builder (`mcp-cedar-entities`) and the same contract vectors. None of it needs code in a tool beyond declaring what the
resource is.

### Policy bundle

`PolicyEngine` fetches `latest.json` (with `ETag`, every 30 s) and the files it points to (limit 16 MiB, no redirects, file names
only next to `latest.json`), and `PolicyStore` from `mcp-policy-bundle` checks the Ed25519 signature (a `test-*` key and the
published test keys only with `allow-test-keys` in `dev` or `devstack`; a `dev-` key identifier only in dev mode), strict revision
order, a schema equal to the contracts schema (**byte for byte; since contracts 4.0.0 different from 3.1.x** (CC-136), so a bundle
built on the 3.1.x schema is rejected as `schema_mismatch` and the previous one stays; the first such bundle keeps a server from
starting), the policies against the schema and `static.json`, and then swaps the snapshot atomically. A revision lower than or equal
to the loaded one is rejected, also after a restart (`state-dir`, CC-066).

- An error (a bad signature, an older revision, damaged contents, an unavailable repository) **keeps the previous bundle**: the log
  `event=policy_bundle_rejected channel=main revision=... reason=bad_signature` or `event=policy_bundle_fetch_failed`, the counter
  `mcp_server_policy_bundle_rejected_total{channel,reason}` or `mcp_server_policy_bundle_fetch_failed_total{channel,reason}`. A
  rejected revision is reported once, not on every fetch.
- Starting without a bundle ends in a refusal. Without any policy the server makes no decisions: a call that needs a decision gets
  `upstream_unavailable` (the text `Policies are not available. Try again later.`), never consent (CC-141). Audit: `policy_decision`,
  `decision: deny`, `outcome: error`, reason `policy_unavailable` (no bundle: for example waiting for the emergency bundle after a
  restart) or `policy_error` (the policy engine failed on this request).
- The emergency bundle (`overrides`, a separate key, `forbid` only, every 5 s) is loaded on top. If it was active before a restart
  and is missing after it, calls are refused until `overrides-grace` (then the server runs and logs
  `event=policy_overrides_missing_alarm`): the kill switch rules do not vanish silently.
- A `prv` in the token newer than the loaded revision triggers an immediate fetch (at most every 2 s, on the engine's thread: the
  call with the hint does not wait and is evaluated with the loaded bundle), so following calls are not refused what the gateway has
  just allowed. Every request to the repository has a time limit for the whole thing, body included (`read-timeout`), and the `main`
  and `overrides` channels are fetched independently. A `prv` of `00000000000000-0000000` (a gateway without a bundle) forces nothing.

### Credential type in the Cedar request (CC-136)

Every Cedar request of the server (`Action::"call"` on a tool, a read and a write on a resource) has `context.credentialType`: the
server's **default type**, that is the first of `manifest.accepted-credential-types` (CC-166, CC-168). Rules such as "the service
account reads a Service Desk queue only in the granted scope" must apply even when the attribute would be missing, so the set of
types is either `service-account` alone or user types alone: a mixed set would hide the service account from the rules, which is why
a server with such a set does not start. A `ResourcePolicy` created without types throws; `EntityBuilder.withCredentialType` with a
value outside the four types (`user-oauth`, `user-secret`, `token-exchange`, `service-account`) ends the call with `internal_error`.

**The header check (CC-166).** A request with `X-MCP-Credential` must name in `X-MCP-Credential-Type` a type from the server's
`accepted-credential-types`. A missing header, an unknown value (exact comparison: `User-OAuth` is unknown), a value outside the
set, or a server with no configured type is a refusal: `internal_error` outwardly (a configuration error, not the caller's fault),
`ERROR event=credential_type_mismatch` in the log and a `policy_decision` with the reason `credential_type_mismatch` in the audit,
**before** Cedar, the grant probe and the resolvers. A request without `X-MCP-Credential` is not checked. A server that accepts
`[user-oauth, user-secret]` (GitLab) lets both types through; the `credential-type-header/` vectors (server side) pass in
`CredentialTypeHeaderVectorsTest`. A credential sent without a usable type does not reach `CredentialContext.require` (the tool does
not get it), and its value is still removed from logs and errors.

### One refusal for resources and the order of evaluation (CC-146)

`not_found_or_denied` always has one text, `The resource does not exist or you do not have access to it.` (a constant in the C10
schema): the starter sets it regardless of the text written by the server's error map, so the text of a refusal does not reveal
whether the resource exists. Order: the grant first. Before the resolver is called, before an entity is built from system data and
before a classification is read, the starter evaluates the policy on a **probe**: a resource built from the argument identifier alone
(and its container, when the identifier names one: the project of an issue key, the group of a GitLab path) with the least
restrictive classification (`category: ""`, empty `labels` and `tags`). A probe refusal is `not_found_or_denied` without calling the
system; consent lets the call go on to full evaluation after resolution. Thanks to this, a caller with no grant for the type does
not get `internal_error` (a missing required classification) or `upstream_unavailable` from the resolver, which would reveal that
the resource exists. In the audit a probe refusal has the reason `policy_forbid` (the policy refused: `forbid`, or no `permit`).
**A limit of the probe:** it assumes the policy is monotonic in classification, that is classification only narrows (`forbid` rules,
as in the contracts' examples). A rule that grants access only for a chosen classification (`permit ... when resource.category ==
"public"`) would reject the probe with `category: ""` despite the grant; and conversely, a probe built from the container in the
identifier would refuse an issue moved to a project the user has a grant for while having none for the old project of the key. In
both cases the refusal is safe (fail-closed) but imprecise. **`Confluence::Page` and `ServiceDesk::Ticket`** (the container does not
follow from the identifier) have no probe from the identifier. A grant means, per entity-builder.md 9b.2, a scope in the bundle
**or** a policy that grants access. A caller with any container scope (group `mcp-confluence-space-*`, `mcp-servicedesk-queue-*`)
goes on to the resolver and full evaluation. A caller **without a scope** is evaluated by the policy on a page or ticket in a
container that no scope covers (a sentinel): a refusal (the service account with `credential-type.cedar` reads only granted queues)
is `not_found_or_denied` before the resolver, and consent (a read from `read.cedar` by the user's rights in the system, vector
`srv-servicedesk-user-secret-not-restricted-by-grants`) lets the call on to full evaluation. A resource that the system returned
outside the caller's scopes and that cannot be evaluated (no classification, a bad identifier, no container) gives
`not_found_or_denied` instead of `internal_error`. With a service account, a resolver error for a page or ticket also looks like a
missing resource (except for a missing credential); with a user credential it stays `upstream_unavailable`, because the system
itself limits what the user sees. Narrowing by a saved link no longer exists (contracts 5.0.0 removed `/v/`, `vs`, `vsc` and
`virtual-server.cedar`), so the audit has no reason `link_scope`; a filter in the address `/mcp?...` narrows the tool list in the
gateway and does not reach the server. The `servicedesk-service-account/` vectors pass through `authorizeCall`
(`ServiceDeskVectorsTest`).

### `@ResourceRef` and `ResourceResolver`

```java
@Component
class JiraIssueResolver implements ResourceResolver<JiraIssue> {
    public String resourceType() { return "Jira::Issue"; }

    public Optional<JiraIssue> fetch(String key) {          // a call to Jira with the caller's credential
        return jira.issue(key);                              // empty for 404 and 403; an exception for a system failure
    }

    public ResolvedResource describe(JiraIssue issue) {      // canonical id, parent and classification FROM THE SYSTEM
        return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key()))
                .withParent(ResolvedResource.of("Jira::Project", issue.projectKey(),
                        Map.of("key", issue.projectKey(), "category", issue.projectCategory())));   // "" when missing
    }
}

@Tool(name = "get_issue", description = "Returns an issue by key.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Get issue")
public IssueView getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                          @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
```

Before a tool runs, the starter does the following, in this order and from one bundle:

1. `Action::"call"` on the tool, built from the token claims (`sub`, `grp`, `azp` and `mfa`) and the arguments the way the gateway
   builds it. A refusal is `access_denied` and a `policy_decision` event in the audit.
2. For arguments with `@ResourceRef`, **every value of every argument** (every list element; the source and the target of a
   transfer) is resolved by the resolver and evaluated with the tool's action (`read`, `update`, `delete`, `admin`) on the resource
   as the system returns it. The first value that cannot be resolved, or that the policy does not allow, ends the call with
   `not_found_or_denied`: the same answer as for a resource that does not exist. A tool that declares `@ResourceRef` and got no value
   is rejected (empty `resourceRefs` at the gate do not mean "everything is allowed").

Resolver rules (GitLab: `attrs.ancestorClassifications`, a map `<ancestor group path> -> topics` for the project and its parent
group, CC-129; described in `MIGRATION-3.0.md`): the id and the parent in the result come from the system, never from an argument
(an issue `ABC-123` moved to `HR` has the project `HR`, and its classification `restricted` closes access despite an argument that
looks like `ABC`). `Jira::Project.category` (`""` when missing), `Confluence::Space.labels` and `OpenSearch::Index.tags` are
mandatory, and a resource without them is not built: the call ends with `internal_error` (the contract, entity-builder.md section
6; in the log `event=resource_refused reason=evaluation_error type=...`), and a result element is removed. The same applies to an id
that does not match the type's pattern and to a resource of a type other than in `@ResourceRef`. A resolver that works with a
service account (OpenSearch) returns `tags: []` by default, not a missing attribute: a missing one gives `internal_error`, an answer
different from `not_found_or_denied` for an index that does not exist, and reveals that the index exists. `ResolvedResource.of`
skips attributes whose value is `null` (a missing attribute), and `Map.of(..., null)` throws an NPE: use a `HashMap` or `""` for a
project category. `fetch` returns an empty `Optional` for "does not exist" and "the user may not" (404 and 403), and **throws** for a
system failure: that is `upstream_unavailable` (or `credential_required`), not a refusal. The result of `fetch` and `describe` is
cached for the duration of the call; the tool reads it with `ResourceContext.required().fetched("Jira::Issue", key, JiraIssue.class)`
without calling the system again (in a unit test that calls the tool method without the platform: `TestResourceContext.open(...)`
from `mcp-platform-test`). The identifier must match the type's pattern from the contracts (for example a Jira issue key `ABC-123`,
not `ABC-SLOW`); a non-matching one is `invalid_argument` before any resolver call. List results are resolved by one `fetchAll(ids)`
call (by default a loop over `fetch`; override it with the system's bulk call). A resolver is a bean; with one resolver of a type
`@ResourceRef(type = ...)` is enough, with several use `resolver = X.class`. A missing resolver, a wrong type or ambiguity stop
startup.

Allowed types in `type`: the contracts' `ResourceType` (`Jira::Project`, `Jira::Issue`, `Confluence::Space`, `Confluence::Page`,
`GitLab::Group`, `GitLab::Project`, `OpenSearch::Index`, `ServiceDesk::Queue`, `ServiceDesk::Ticket`). The parameter is a `String`, an
integer, or a list or array of such values. The tool's Cedar action is `@ToolRisk(action = ...)`; by default `read` for `READ_ONLY`,
`update` for `UPDATE`, `delete` for `DESTRUCTIVE`, and `ToolAction.ADMIN` is set explicitly. An action that contradicts the risk
stops startup.

### Results: `ResultFilter`

A record returned from a tool with a `@ResourceRef` component is checked after the tool runs: every element of a **list** with such
a component is resolved with the same resolver (one `fetchAll` per list) and evaluated, and disallowed elements are removed from the
result (this is not a call error; a `policy_decision` event for the first 20, a count in the log `result_elements_removed`). An
element that cannot be resolved (a system failure, absent) is removed too. A record that is not a list element and is not allowed
ends the call with `not_found_or_denied`. A list longer than `tools.max-filtered-elements` (200) ends the call with
`internal_error`: return pages. The starter does not correct counters from the system (`total`). The result of a write tool is
evaluated with the tool's action, and a refusal of the result does not undo a change that already happened.

```java
public record IssueHit(@ResourceRef(type = "Jira::Issue") String key, String summary) {}
public record SearchResult(List<IssueHit> hits, int total) {}
public record Page<T>(List<T> items, int total) {}       // Page<IssueHit>, Optional<List<IssueHit>>, IssueHit[] also
```

**What the starter can track:** records (also generic ones, resolved from the return type), lists, sets, arrays and `Optional`, at any
nesting. **What it cannot:** `Map`, classes that are not records, `Object`, `JsonNode`, `Stream`, records with Jackson annotations
(`@JsonProperty`, `@JsonUnwrapped`, `@JsonIgnore`, ...: they change the field names under which the filter looks for data). A result
with such a part stops startup (the message names the method and the part), unless the method has `@ResultWithoutResources`: the
result contains no resources, or the tool filters them itself before returning. JSON with a field that is not among the record
components (a global naming strategy in Jackson) ends the call with `internal_error` instead of letting unfiltered data through.
**A result that is itself text (`String`, `CharSequence`, `byte[]`, also in an `Optional` or a list) also needs
`@ResultWithoutResources`**: it could be ready-made JSON (a search response passed on) in which the platform sees no resources. A
tool returns typed records; plain text (a message, a number) gets the annotation. A `Map` key that is not a scalar counts as an
untracked part. A hand-written `ToolCallback` (not a `@Tool` method) declares the same through
`RiskClassifiedTool#resultWithoutResources()` or it will not start. Other known limits: the words `AND`, `OR`, `NOT` in a `CONTAINS`
value act as text search operators of Jira/Confluence (within that one field, they do not widen the scope); `IS EMPTY`,
`membersOf(...)` and date functions cannot be expressed in `StructuredQuery` (the contract closes the list of fields and operators;
a new field needs a contract change).

For a result built another way, inject `ResourcePolicy` (it implements `ResultFilter`): `retainAllowed(items, item ->
resolvedResource)` or `retainAllowedById("Jira::Issue", items, Item::key)`; `allows(resource)` and `require(resource)` for a resource
the tool finds itself (the project in which it creates an issue). `@ResourceRef` is allowed only on a parameter of a `@Tool` method
and on a component of a result record; on a component of a record that is an argument it stops startup (it would be skipped and
the tool would run without a check).

### `QueryScope` and `StructuredQuery`: the server builds the query

The model **does not supply query text**. You build JQL and CQL from structured conditions; the starter composes them the way the
contracts' reference does (`scripts/lib/query-rendering.mjs`, vectors `test-vectors/query-rendering/`, CC-160): the user's
conditions in **one bracket**, the scope after it through `AND`, so an `OR` in a value does not widen the scope; every value is one
quoted literal (`\` and `"` preceded by `\`, a control character rejected); a value cannot close a quote or a bracket, call a
function (`currentUser()`) or add `OR project = HR`.

```java
QueryScope scope = policy.queryScope("Jira::Project");     // the scope from the bundle groups
String jql = scope.jql(Set.of("status", "assignee", "text"))   // condition fields: a subset of the contract list
        .where("status", Operator.EQUALS, filter.status())        // null = the condition is skipped
        .where("text", Operator.CONTAINS, filter.text())
        .orderBy("updated", Direction.DESC).build();
// (status = "Open" AND text ~ "x") AND project in ("ABC", "DEF") ORDER BY updated DESC
```

- **The lists of fields and operators are normative** (contract 4.0.0): JQL conditions `text`, `status`, `assignee`, `summary`; JQL
  `ORDER BY` `created`, `updated`, `priority`, `key`; CQL conditions `text`, `title`, `label`; CQL `ORDER BY` `created`,
  `lastmodified`, `title`; operators `EQUALS` (`=`), `CONTAINS` (`~`), `NOT_EQUALS` (`!=`). A CQL query ends with the constant clause
  `AND type = page` (before `ORDER BY`; CC-219): `content/search` without it also returns comments, attachments and blogs. A server
  may allow less (`scope.jql(fields)` with a subset; `scope.jql(fields, orderFields)` narrows `ORDER BY` too), never more: a field
  outside the list is an `IllegalArgumentException` when the builder is created (the constants `StructuredQuery.JQL_CONDITION_FIELDS`
  and so on). A new field needs a contract change. One `ORDER BY` per query.
- A value: 1 to 1000 characters, without characters forbidden by text hygiene (CC-121) and without control characters, up to 30
  conditions; a violation is an `IllegalArgumentException` (the platform reports `invalid_argument`). The starter does not escape
  Lucene characters (`* ? ~ ( ) - + ! ^ : / & |`) in text search, like the contracts' reference (`query-rendering.mjs`; a deliberate
  choice). This does not widen the scope: a literal ends only at an unescaped `"`, and `\` and `"` are escaped, so Lucene syntax works
  only inside the condition `text ~ "..."`, which is conjoined with the scope (`project in (...)`, `space in (...)`) and with the
  user's permissions in the system. Only search semantics change (`*` and `?` as wildcards, `foo-bar` as an exclusion, `x~` as
  fuzzy); if the contract requires it, escaping will arrive with a new reference version.
- `scope.cql(Set.of("title", "label", ...))` for Confluence; `scope.jql(fields, true)` and `scope.cql(fields, true)` (type
  `Confluence::Space`) add scopes from groups (a service account; they throw when there are no groups: an empty scope is no query,
  never a query without a filter).
- **Narrowing to a project or space from an argument:** `project` and `space` are not condition fields (the lists are normative),
  and a server should not fetch 100 results to return 25. `scope.narrowedTo(ids)` (Jira: project keys, Confluence: space keys; ids
  from an argument that is a `@ResourceRef` of the container type, which the policy evaluated before the tool) returns the scope that
  is the argument (intersected with an earlier narrowing, if the scope was already narrowed), rendered in the scope part of the
  query after the conditions (`(text ~ "x") AND project in ("DEF")`), not as a condition. Narrowing only shrinks the scope: an id
  outside an earlier narrowing drops out, an empty intersection is `nothingToQuery()` (an empty result, or `requireQueryable()` when
  fetching by id), and for a query limited by grants (`jql(fields, true)`) an argument with no element in common with the grants is
  an exception, like an empty grant. An identifier outside the contract pattern is an `IllegalArgumentException`, never query text.
  It fits the `query-rendering` contract: it is a scope list rendered like the reference's `scope` (a bracketed group of conditions,
  then `AND project in (...)`), not a new field or operator.
- `jqlProjectClause()` and `cqlSpaceClause()` stay as ready fragments (identifiers quoted by `QueryScope.quote`, which rejects
  control characters).
- **A scope that excludes everything (CC-147):** `scope.nothingToQuery()` (OpenSearch: no index is granted; other types: no grants,
  for queries limited by grants) means that search and list return an **empty result without calling the system** (this is not an
  error), and fetching a single resource by id calls `scope.requireQueryable()`, which ends in `not_found_or_denied`, the same as
  for a resource the user cannot see.
- **OpenSearch (CC-160): names, not patterns.** `scope.opensearchIndexExpression()` (pairs `p` and `p-*`, never `p*`) is **only** for
  `GET _resolve/index/<expression>`; search, `_count` and aggregations go to a **list of concrete names**, because the pattern `p-*`
  also covers an alias of that name, which may point at other teams' indices. `scope.resolveIndexNames(response)` takes from the
  response only the `indices` entries (aliases and data streams never, whatever their name), keeps names equal to the prefix or
  starting with `<prefix>-`, and returns them sorted; an empty list means do not run the query. An index given as an argument:
  `scope.resolveIndexArgument(argument, response)` throws `invalid_argument` for a pattern, a comma-separated list, `_all` and a
  name with a leading dot (before any call to the system), and `not_found_or_denied` for an alias, a stream, an index outside the
  scope and a name unknown to the system. Add `QueryScope.OPENSEARCH_SEARCH_PARAMETERS` to requests (`expand_wildcards=open`,
  `ignore_unavailable=true`, `allow_no_indices=true`). There is a short window between resolution and the query (an administrator
  could swap an index for an alias of the same name): the risk is known and accepted in the contract; **do not cache `_resolve/index`
  responses between tool calls**, because then the window lasts as long as the TTL. The target of search, `_count` and aggregations is
  given by `scope.opensearchSearchTarget(response)`: an empty `Optional` means "do not run the query" (a request with an empty target
  searches all indices, so do not join the names yourself). Names that do not match the `OpenSearchIndexName` pattern (for example
  with `%` or `=`) are skipped, so names are safe in a path without encoding. Add `QueryScope.OPENSEARCH_RESOLVE_PARAMETERS` to
  `_resolve/index` (`expand_wildcards=open`, `ignore_unavailable=true`, `allow_no_indices=true`): without `ignore_unavailable`, a
  missing index named exactly like the prefix (a layout with only `p-2026.10`) fails the whole request with 404
  `index_not_found_exception`. Treat a 404 response like `QueryScope.EMPTY_RESOLVE_RESPONSE` anyway (a safeguard; behaviour on a live
  cluster still to be confirmed). `QueryScope.requireNoAliases` is deprecated (a query on names does not need a refusal). Because of
  this, `aggregate_logs` can return once the OpenSearch server ships it (the disabling rule in the policy bundle goes away only then).
  Build the OpenSearch query DSL on the server side; do not accept it from the model.
- Where the system itself applies the user's rights (Jira, Confluence, GitLab with the user's token), narrowing from an argument
  (`narrowedTo`) is enough; where it does not (OpenSearch, a service account), the query is limited to `grantedIds()`, and an empty
  `grantsNothing()` means do not run the query. Limiting the query does not replace filtering the result.

### Audit

A policy refusal is a `policy_decision` event (C8) with `decision: deny`, `reason` (`policy_forbid`: a policy refusal, a `forbid` rule
or no `permit`; `not_granted`: an unresolved resource or one without a grant, CC-141), the resource type and id if the id is safe in
a log, the `policyRevision` of the server's bundle and `errorCode`; an ordinary call is a `tool_call`.

## Internal JWT receiver mode: native server and adapter sidecar (CC-226)

`aiwcorpo.mcp.platform.internal-jwt.receiver-mode` says who the token receiver is, because a schema cannot tell.

- `native` (the default, every server written on the starter): a token with `mcp_risk` or `mcp_action` is `401 invalid_claims`. The
  gateway sends these claims only to a `serverId` in adapter mode, so the event on a native server means a gateway configuration
  error or a forged token.
- `adapter` (the `mcp-server-adapter` sidecar standing in front of a foreign server): `tools/call` must carry `mcp_risk` and
  `mcp_action` (always together, with `tool`, a matching pair, otherwise `invalid_claims`); without them a `401` refusal with the
  reason `classification_missing` (fail-closed: an older gateway or a mismatched mode). The administrator's classification is in
  `CallerContext.mcpRisk()` and `mcpAction()` and replaces the substitute risk from the foreign server's hints. A value other than
  `READ_ONLY` is logged (`event=adapter_classification_not_read_only`) and counted
  (`mcp_adapter_classification_not_read_only_total{risk}`): after CC-227 the gateway will not send one.

`CallerContext` has two new components at the end (`mcpRisk`, `mcpAction`, `null` outside the sidecar); the constructor with eight
arguments stays.

## Large results: pages, cursor, `truncated` and `omitted`

A tool whose result may be longer than one call (search, a list, page content, a log) returns `PagedResult<T>`
(`pl.aiwcorpo.mcp.platform.paging`) and accepts a cursor from `CursorCodec`, instead of inventing its own format:

```java
@Tool(description = "Search issues. Pass nextCursor from the previous result to get the next page.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Search issues")
public PagedResult<IssueHit> searchIssues(
        @ArgumentLimits(maxLength = 500) String text,
        @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) @Nullable String cursor) {
    String query = CursorCodec.fingerprint("search_issues", text);
    String position = cursor == null ? null : codec.open(cursor, "search_issues", query);   // a bad cursor: invalid_argument
    JiraPage page = jira.search(scope.jql(text), position, PAGE_SIZE);
    String next = page.nextPageToken() == null ? null : codec.issue("search_issues", query, page.nextPageToken());
    return PagedResult.of(page.issues(), next);
}
```

- **The cursor is opaque to the client and bound** to the caller's `sub`, the tool name, the query fingerprint
  (`CursorCodec.fingerprint(...)`: text, filters, sorting, page size, without the cursor itself) and time (15 min by default,
  `paging.cursor-validity`). A cursor of another user, another tool or a changed query, a forged, truncated or expired one, ends in the
  same `invalid_argument` with a constant text, so the message does not reveal the cause. The cursor is AES-256-GCM: the key is
  derived from `paging.cursor-key` by HKDF-SHA256 with `server-id` as the context, the nonce is random, and the authenticated data
  are `sub`, the tool and the query fingerprint; all server instances must have the same `paging.cursor-key`, otherwise a cursor
  issued by one is rejected by another (without a key the starter draws one per process and warns when the first cursor is issued,
  so a server with no paging tool does not warn at all). The key depends on `server-id`, so a cursor of one server is rejected by
  another even with the same `cursor-key`.
- **A cursor is not a permission.** It carries only the position in the system's paging (an offset, `nextPageToken`, a key value);
  the caller's scope (`QueryScope`) is computed anew for every page, and `ResultFilter` filters every page, so a forged cursor widens
  nothing. **The cursor payload is confidential**: a position may contain values of a hidden element (the `search_after` sort values
  of a hit filtered out by policy), so the client must not be able to read it or change a byte. Even with encryption, do not put a
  credential into the position: the server that opens the cursor sees it in clear. Treat `cursor-key` as a secret (Base64, at least 32
  bytes; rotation invalidates cursors, which with 15 minutes of validity is harmless). At most 512 bytes of position.
- **A page may be empty or shorter and still have `nextCursor`** if `ResultFilter` removed elements from it, and a short page with a
  cursor reveals that something was hidden in that window (a series of empty pages: that only invisible items remain). So a tool that
  takes the page size from the client fills the page with `PageFiller.fill(position, size, maxReads, fetch, allowed)`: it reads
  successive pages of the system until it collects the requested number of allowed elements, reaches the end, or exhausts `maxReads`
  (at most 20). The page may then have more elements than requested (the last page of the system is taken whole, because the system's
  position is the page boundary). **When the read limit is exhausted** (`Filled.exhausted()`), the result is short with a cursor, so
  the signal stays but is bounded by the number of reads the server allows itself; do not then return any number or message about
  hidden elements. It is best to narrow the query before reading (`QueryScope`), so that `ResultFilter` removes only exceptions (for
  example an issue from a link to another project). There is no `total` (the system's counter includes what the caller cannot see,
  and the starter does not correct it).
- **`truncated`** means the server itself cut the content at its limit (a log, a diff, page content), not that there is a next page.
- **`omitted`** is fixed snake case codes (up to 10, up to 40 characters: `binary_attachment`, `unsupported_macro`) for kinds of
  content that the server cannot return safely. It never counts or names elements removed by policy: the number of hidden elements
  would reveal that they exist.

`PagedResult` is a generic record: `ResultFilter` tracks its `items` like any list, and the `outputSchema` (when the element has
`UntrustedText`) is built from the return type with the element type substituted. The shape (`PageCursor`, `PagedResultFields`,
`maxResultBytes` in the manifest) is not yet in the contract: proposal `CC-209`.

## System versions: `CapabilityProbe` and the `degraded` state

A server that handles more than one version of a system (Jira Data Center from 9.12, GitLab with fields that come and go) declares a
`CapabilityProbe` bean (`pl.aiwcorpo.mcp.platform.capability`). The starter runs it after the application starts and then every
`capability.interval`, on its own threads (a slow system does not delay startup or requests), without a caller and without a user
credential: the probe reads only what the system says about itself (`/rest/api/2/serverInfo`, `/api/v4/version`).

```java
@Bean
CapabilityProbe jiraProbe(JiraClient jira) {
    return new CapabilityProbe() {
        public String name() { return "jira"; }
        public Capabilities probe() {
            ProductVersion version = ProductVersion.parse(jira.serverInfo().version()).orElse(null);
            if (version == null) return Capabilities.unavailable("serverInfo has no version");
            if (version.before(9, 12)) {
                return Capabilities.degraded(version.toString(), Set.of("issue_read"), "older than 9.12");
            }
            return Capabilities.supported(version.toString(), Set.of("issue_read", "search_v3", "attachments"));
        }
    };
}
```

The probe reads only endpoints that need no login: a server holds no secrets (credentials come from the broker through the gateway),
so a system whose version endpoint requires a login (GitLab `/api/v4/version`) needs a decision about a service credential from the
broker (a contract change); until then the probe of that system uses a public endpoint or there is none. `detail` in `Capabilities`
is a constant server text, never an exception message or response content (sanitizing keeps letters and digits, so it would not
remove a token).

The state is `SUPPORTED` (a known version, the full feature set), `DEGRADED` (the system answers but the version is unknown: too old
or newer than tested; the feature set is only the baseline that works on every version) or `UNAVAILABLE` (no answer, an error, a
value that is not a version; no feature is assumed). The server **starts in each of them**; a tool asks `BackendCapabilities`
(`of("jira").supports("search_v3")`, `isDegraded("jira")`) and, when a feature is missing, takes the conservative path or ends with
`upstream_unavailable` instead of guessing. Before the first answer the system is `UNAVAILABLE`.

The state is visible in three places: a log line on every state or version change (`backend capability: probe=jira state=degraded
...`; the version and description from the system are cleaned of characters outside `A-Za-z0-9._+ -` and cut to 120, and from an
exception only the class name goes in, because an HTTP client message may contain an address and a token), the metric
`mcp_backend_capability{probe,state}` (1 for the current state) and the `backendCapability` detail in `/actuator/health` (like any
health detail: only with `management.endpoint.health.show-details` and with the internal JWT). Do not turn on
`management.endpoint.health.show-details=always`: `/actuator/health` is outside the internal JWT, and the details carry the system
version and the feature list. A probe that does not react to interruption is not started again while the previous call lasts (state
`UNAVAILABLE`, "the previous probe is still running"); every probe has its own thread. The `DEGRADED` and `UNAVAILABLE` states do not
enter Spring's aggregation, so they do not take the server off the balancer: an unknown or disabled system is an
`upstream_unavailable` answer, not an unhealthy instance. C8 audit events have no type for a backend capability change; that is a
contract proposal (`CC-208`), and until it is decided the log line and the metric stand in.

## The C6 manifest and DESTRUCTIVE tools

`GET /mcp-manifest` (JWT C2 in the user or service variant, without `tool`) returns the server manifest. The starter builds it after
startup from **what the server really publishes**: the definitions are exactly the `tools/list` entries (so `definitionHash` is the
hash of what the client sees and the gateway pins), `risk`, `action` and `resourceRefs` come from annotations, the rest from
`aiwcorpo.mcp.platform.manifest.*`; in the `devstack` profile with `"environment": "dev"` (CC-138). The manifest to register in the
gateway is a build-stage artifact of the reviewed commit (`manifest.output-file`, for example `target/mcp-manifest.json`), never the
answer of a running server (CC-133, CC-140). Before it is served, the manifest passes `server-manifest.schema.json` and the
`mcp-manifest` rules (a closed and bounded `inputSchema`, `title`, existing `resourceRefs` arguments, the hash), which are the
gateway's registration checks; a manifest that would not pass them stops the server (in `dev`, a warning). With `output-file` it is
also written to a file for the registration step.

Requirements for a tool: `@ToolTitle` (CC-115, non-empty, up to 100 characters, no hidden characters), `@ToolRisk`, a schema without
`$ref`, union types and maps (`SchemaLimitedToolCallbacks` generates such a schema), and no argument named `confirmation_code` (the
gateway adds it to `tools/list` and removes it before the call). The `OpenSearchIndexPrefix` pattern from contracts 4.0.0 (CC-139) is
one anchored form (`^(?:a|b)$`), so `@ArgumentLimits(contractPattern = "OpenSearchIndexPrefix")` works without a workaround; the
starter rewrites nothing.

**DESTRUCTIVE.** The server does not ask for confirmation (the gateway does, with a TOTP code, C11); it only has to say truthfully
that a tool needs confirmation. The one mechanism is `@ToolRisk(RiskLevel.DESTRUCTIVE)`, and the starter makes sure the declaration
lands everywhere the same way: `risk` in the manifest, `destructiveHint: true` and `readOnlyHint: false` in `tools/list`, the `risk`
field in the audit, the `delete` action (or an explicit `admin`) in Cedar. A name with `delete`, `drop`, `remove`, `purge`,
`truncate`, `destroy`, `wipe` or `erase` does not pass as `READ_ONLY` or `UPDATE`. When the manifest is generated, `risk` and the
`tools/list` hints are compared, and a mismatch stops the server.

### Repeatability of `UPDATE` tools (CC-204)

An `UPDATE` call is delivered at least once. When a gateway instance dies in the middle of a `tools/call`, the balancer answers `502`
with a constant text about an unknown result, and the client (or the user) may repeat the call; the gateway itself does not retry
`UPDATE` or `DESTRUCTIVE`. The platform has no idempotency key, so an `UPDATE` tool is designed so that a repetition is harmless,
where the target system allows:

- the target state instead of an increment: `set_assignee`, `transition_issue(to)` instead of "move one step";
- a comment, worklog or link created with a key the server can check (a marker in the content, an existing link), not a blind
  append; when the system cannot be asked, say in `@ToolTitle` or the tool description that a repetition duplicates the entry;
- an edit with an expected version (`expectedVersion`) where the API accepts it: a second call ends in a conflict, not an overwrite;
- on `upstream_unavailable` from the target system, do not guess the result: the error message should say that the operation may
  have been performed.

The starter neither enforces nor checks this. `DESTRUCTIVE` does not need such a design: a repetition needs a new confirmation code
(single-use, C11).

## Server tests without the gateway

Run tests with `TestGateway` in the `dev` profile (`@ActiveProfiles("dev")`). The check "`dev` in a deployment" (Kubernetes,
systemd, a container) does not cover a context built by Spring TestContext, so these tests also pass in CI on Kubernetes and under
a runner started as a systemd service. An application started by hand with `SpringApplication` in such an environment is still
rejected, also in a test. That is why the test that `dev` does not listen outside loopback does not start the application with
`--server.address=0.0.0.0` (on a CI host in a container the "deployment" refusal comes before the address check, and there is no
address message); it calls a helper that checks the guard rule independently of the host, and checks the server's own
configuration on its real `@SpringBootTest` context in the `dev` profile (the same context as in the other tests, so without the
cost of a second start):

```java
package pl.aiwcorpo.mcp.jira.app;   // the package and system name of the server

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

class DevProfileLoopbackTest extends ServerTestSupport {

    @Autowired
    ApplicationContext context;

    @Test
    void devListensOnLoopbackOnly() {
        DevProfileGuardAssertions.assertDevProfileLoopbackRule();
        DevProfileGuardAssertions.assertServerIsGuarded(context);
    }
}
```

`ServerTestSupport` is the base class of the server tests (`@SpringBootTest`, `@ActiveProfiles("dev")`), in the same package. Moving
from earlier versions step by step: `MIGRATION-3.0.md`, the section "Jak przejść z alpha-11".

`assertServerIsGuarded(context)` requires that the server context contains all the beans from `PlatformPresenceCheck.REQUIRED_BEANS`
(the guard, the verifier and the JWT filter, the audit post-processor, the MCP server customizer) and that `server.address` is a
loopback address. A test context is not subject to the exclusion refusal (slices skip the platform on purpose), so without this
check a server with an `exclude` on `PlatformSecurityAutoConfiguration` would pass all of its tests.

`DevProfileGuardAssertions` (`pl.aiwcorpo.mcp.platform.test`) builds its own environment (without the host's process variables and
system properties, with the pod and systemd signals fixed) and calls `PlatformConfigurationGuard`; `assertDevProfileRefusesAddress(address)`
checks one address.

```xml
<dependency>
  <groupId>pl.aiwcorpo.mcp</groupId><artifactId>mcp-platform-test</artifactId><scope>test</scope>
</dependency>
```

The test below is in the repository as `mcp-platform-test/src/test/java/pl/aiwcorpo/mcp/platform/test/readme/JiraToolsTest.java`
(with the `JiraServer` above) and passes on every build. Here is its core: it starts the test gateway, points the server at it and
calls a tool through the real MCP transport.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")                              // test keys, jti in memory, no mTLS: dev only
class JiraToolsTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");   // server-id of the server under test

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    McpSyncClient client(String credential) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint("/mcp")
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> user.username("jkowalski"), credential))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    @Test
    void toolCallThroughTheTestGateway() {
        try (McpSyncClient client = client("Bearer test-jira-pat")) {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("get_issue").arguments(Map.of("issueKey", "ABC-1")).build());

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .contains("ABC-1 read with a Bearer credential");
        }
    }
}
```

The same file also has a test with `client(null)` (the tool answers `credential_required`) and a test of a plain `POST /mcp` without
a JWT (401).

`TestGateway.asGateway(user, credential)` does what the gateway does, on every HTTP request of the MCP client:

- it sets a new internal JWT (`Authorization: Bearer ...`, signed with the contracts' test key A) and a matching `X-Request-Id`;
  for `tools/call` with `tool` equal to `params.name`. The first argument changes the user's claims (`username`, `groups`,
  `mfaAgeSeconds`, ...); `extraClaim(name, value)` adds a claim outside the contract (to test rejection: a token with `vs` is 401);
- the second argument is the **credential for the target system (C3)**, not a token for the server: it goes literally into
  `X-MCP-Credential` in the form `<scheme> <value>` (scheme: `Bearer`, `Basic`, `Token`, `ApiKey`), and `X-MCP-Credential-Type`
  gets `user-secret` (another type: the third argument, `asGateway(user, credential, BackendCredential.Type.SERVICE_ACCOUNT)`;
  `USER_SECRET`, `USER_OAUTH`, `TOKEN_EXCHANGE`, `SERVICE_ACCOUNT` are available). In the tool it appears as
  `CredentialContext.require(system)`. `null` = no credential (the tool gets `credential_required`).

Single tokens for rejection tests: `GATEWAY.user().tool("get_issue").mint().headers()`, `GATEWAY.service().mint()`, with the settings
`audience`, `lifetime`, `requestId`, `jti`, ... The result of a tool that returns a `String` arrives in `TextContent` as a JSON string
(in quotes), hence the `contains` in the example.

**The policy bundle in tests.** `GATEWAY.serverProperties()` already contains `policy.*` (addresses, the key `test-policy-bundle` and
`allow-test-keys`) and `manifest.*`. `TestGateway` serves the main and emergency channels from the signed bundles in the contracts'
`test-vectors/bundles/files`: `GATEWAY.main().publish("20261026130000-b2c3d4e")` (the contracts' example policies, groups
`mcp-jira-read`, `mcp-jira-write`, `mcp-jira-destructive`, `mcp-jira-proj-ABC`, ...; this bundle is the default),
`publish(revision, signatureFile)` (for example `policies-...tar.gz.sig-by-overrides-key` = a bad signature), `offline(true)`,
`GATEWAY.overrides().publish("20261026121500-d4e5f60")` (before `serverProperties()`, so that the server fetches it). A resource test
through the real transport with a resolver on an artificial system:
`mcp-platform-test/src/test/java/pl/aiwcorpo/mcp/platform/test/resource/ResourcePolicyTest.java`; a manifest test: `ManifestTest`.
`PolicyEngine.create(policy, null, meterRegistry)` lets you test bundle fetching alone (`PolicyEngineTest`). The example policies
require a group in the test token (`user.groups(List.of("mcp-jira-read"))`), and for `DESTRUCTIVE` also `mfaAgeSeconds(60)` (the rule
`destructive-requires-fresh-mfa` is in the contracts' test bundles, not in the production one).

## Build

```bash
./mvnw install          # JDK 25 in JAVA_HOME or on PATH; ci/run.sh verify finds one itself; needs Docker (Testcontainers: Redis with an ACL as in mcp-platform-dev)
```

### CI

One entry point: `ci/run.sh` (`make ci` calls `ci/run.sh verify`). The same script runs in a developer shell and in the
`maven:3.9.12-eclipse-temurin-25` image; `.gitlab-ci.yml` is a thin overlay, and a Jenkinsfile and a GitHub Actions workflow should
call only this script.

| Command | What it does |
|---|---|
| `ci/run.sh verify` (default) | a text check of the sources (non-ASCII quotes and spaces, tool traces), then `mvnw verify` |
| `ci/run.sh hygiene` | the text check only |
| `ci/run.sh release` | `verify` and `install` into the local Maven repository; refuses for `-SNAPSHOT`, for a version already installed and for a HEAD without the tag `v<version>` |

Overlays that call only this script: `.gitlab-ci.yml`, `Jenkinsfile`, `.github/workflows/ci.yml`. `verify` does `clean verify`, fails
when Maven fails and when no test ran; an unknown command is exit code 2. Code from a change (a merge request, a pull request) does not
get the Docker socket or main's secrets: the change job has `MCP_CI_NO_DOCKER=true` (without `RedisJtiReplayStoreTest`) and a
read-only registry account, the main job runs on a runner with the socket, and the change cache has a separate key and `policy: pull`.
Tests that assume a developer's machine skip themselves on a host with a deployment signal (container, pod, systemd; the same
detector as in the starter); in a container 16 tests are skipped and the other 915 run. The number of skipped tests is in the log, and
`release` refuses when anything was skipped or `MCP_CI_NO_DOCKER` is set. Verified: a local run and a run in the
`maven:3.9.12-eclipse-temurin-25` image; not on a real GitLab, Jenkins or GitHub runner. With Docker that has no route to the host,
set `TESTCONTAINERS_HOST_OVERRIDE`. `MAVEN_REPO_LOCAL` is the one Maven repository for build, install and the check of an existing
version.

The `mcp-contracts` 6.3.2 artifacts come from Maven Central; until they are released they must be in `~/.m2` or in the registry given
by `MCP_MAVEN_REPOSITORY_URL` (`_USER`, `_PASSWORD`; `.ci/settings.xml`, for GitLab CI and Jenkins). `RedisJtiReplayStoreTest` starts
Redis through Testcontainers, so access to a container environment is needed (the Docker socket mounted into the CI container, or
`DOCKER_HOST`).

A server imports the BOM and adds the starter (versions are kept by the BOM); the server's `<parent>` is
`spring-boot-starter-parent` 4.1.0 (the snippet is in "Using the artifacts").

## Releasing

Per release (a maintainer):

1. Release `mcp-contracts` first and publish its deployment in the Central Portal: this repository depends on its artifacts
   (`mcp-contracts`, `mcp-cedar-entities`, `mcp-policy-bundle`, `mcp-manifest`), which Maven resolves from Central. Until then the
   build works only with them in `~/.m2`.
2. Version without `-SNAPSHOT` in all poms (`3.0.0`), `CHANGELOG.md` entry, `ci/run.sh verify` green, and
   `./mvnw -Prelease -Dgpg.skip verify` (builds the sources and javadoc jars Central requires; javadoc must pass doclint).
3. Commit, tag `vX.Y.Z`, push the tag. `.github/workflows/release.yml` runs `verify` (all tests, Redis container, no skipped test),
   `build` (read-only: jars, sources, javadoc), `publish` (environment `release`, needs approval: checks the exact file list, signs
   with GPG, uploads one bundle to the Central Portal as `USER_MANAGED`, waits for `VALIDATED`; no Maven runs there), then `sbom` and
   `github-release` (SBOM attached to the GitHub Release).
4. Open https://central.sonatype.com/publishing/deployments, check the deployment and press **Publish** (or **Drop**). This is the
   step that cannot be undone, so it stays manual. The version shows up on Central within minutes to an hour.

From a maintainer's machine, without CI: Portal user token in `~/.m2/settings.xml` as server `central`, `MAVEN_GPG_PASSPHRASE`
exported (or gpg-agent), then `./mvnw -Prelease deploy`. `central.autoPublish` is `false` by default; `-Dcentral.autoPublish=true`
publishes without the manual step.

One-time setup (a human):

1. Account on https://central.sonatype.com and the namespace `pl.aiwcorpo`: the Portal shows a verification key; add it as a DNS TXT
   record on `aiwcorpo.pl` and press Verify. One verified namespace serves this repository and `mcp-contracts`.
2. Portal user token: secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_TOKEN`.
3. GPG key dedicated to releases (`gpg --quick-generate-key "aiwcorpo releases" ed25519 sign 2y`, with a passphrase); publish the public
   key (`gpg --keyserver keyserver.ubuntu.com --send-keys <fingerprint>` and https://keys.openpgp.org). Secrets `GPG_PRIVATE_KEY`
   (`gpg --armor --export-secret-keys <fingerprint>`) and `GPG_PASSPHRASE`. The same key as for `mcp-contracts` is fine.
4. The four secrets go to the GitHub environment `release` (not repository-wide) with required reviewers other than the person who
   pushes the tag and a deployment rule that allows only tags `v*`.
5. The old secrets `MCP_MAVEN_REPOSITORY_URL`, `_USER`, `_PASSWORD`, `_RO_USER`, `_RO_PASSWORD` and the environment `ci-main` are not
   used any more; delete them. GitHub Packages is no longer a publishing target; the packages already there can be deleted.

## Versioning

SemVer, one version for all modules. Pre-releases: `3.0.0-alpha-N`, `3.0.0-beta-N` (Maven ordering: `alpha-1 < alpha-2 < beta-1 <
3.0.0`). A breaking change is a new major version and a description in `MIGRATION-<version>.md`.
