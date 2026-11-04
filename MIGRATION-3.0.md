# Migrating from 2.x to 3.0

This document grows with the 3.0.0 alpha and beta releases and with 3.0.0 itself. Current state: **3.0.0** (the sections
below keep the step-by-step history from the alpha and beta versions; if you start from 2.x, read it from the top).

## Change of model

In 2.x the server established the identity itself (OIDC or PAT), checked tool permissions against the roles in a policy file
and asked a human to confirm destructive actions. In 3.0 the gateway does all of this. The server accepts traffic only from
the gateway (mTLS plus the internal JWT, C2), receives the credential for the backend system in C3 headers and has no
"anonymous" mode.

## Removed in 3.0.0-alpha-1

| 2.x | Replacement |
| --- | --- |
| `OidcIdentityResolver`, `IdentityResolver`, `IdentityFilter`, `ProtectedResourceMetadataFilter`, `NoIdentityProviderResolver`, `PatIdentityResolver` | `InternalJwtFilter` (C2); login and RFC 9728 live in the gateway |
| `ToolGuard`, `GuardPolicy`, `GuardPolicyStore`, the `guard-policy.yml` file | tool policy in the gateway (Cedar, C7); resource policy in the server from beta on |
| `ConfirmationService`, `ConfirmationCodeSender`, `LoggingConfirmationCodeSender`, `ConfirmationPrompter`, `ElicitationConfirmationPrompter`, the `confirmation_code` argument | confirmations in the gateway (C11); the gateway adds `confirmation_code` to the schema itself |
| `CallerIdentity`, `guard.CallerContext` | `auth.CallerContext` (JWT claims) |
| `ToolRejectedException` | `SanitizedToolException` with a C10 code |
| the `platform_whoami` tool | none; the portal catalogue shows permissions |
| `PatForwardingFilter`, `MissingPatException`, `auth-mode: pat` | `CredentialHeaderFilter` + `CredentialContext.require`; `auth-mode: dev-pat` only in the `dev` profile |
| `CredentialContext.requireBackendToken(system)`, `pat(system)`, `systems()`, `patFingerprint()`, `setAccessToken` | `CredentialContext.require(system)` -> `BackendCredential`; `current()`, `type()` |
| `AuthMode.OAUTH`, `AuthMode.PAT` | `AuthMode.GATEWAY`, `AuthMode.DEV_PAT` |
| dependencies `spring-security-oauth2-jose`, `snakeyaml` | `nimbus-jose-jwt` 10.9, `json-schema-validator` 3.0.8, `mcp-contracts` 2.0.2, `spring-boot-starter-data-redis` |

## Properties

| 2.x | 3.0 |
| --- | --- |
| `aiwcorpo.mcp.platform.auth-mode: oauth \| pat` | `auth-mode: gateway \| dev-pat` |
| `aiwcorpo.mcp.platform.guard.*` (including `oidc.*`, `policy-file`, `whoami-tool`, `enabled`) | removed; their presence stops the start |
| `aiwcorpo.mcp.platform.guard.unclassified` | `aiwcorpo.mcp.platform.tools.unclassified` |
| `aiwcorpo.mcp.platform.pat-header-prefix` | `aiwcorpo.mcp.platform.dev-pat.header-prefix` (`dev-pat` only); the old name stops the start |
| - | new and required: `server-id`, `internal-jwt.jwks-uri`, `credential.connect-url` |
| - | new: `internal-jwt.*`, `mtls.*`, Redis `spring.data.redis.*` (described in the README) |

## Server code

- Outgoing adapter: instead of `"Bearer " + CredentialContext.requireBackendToken("jira")` use
  `CredentialContext.require("jira")` and `credential::applyTo` (or `credential.value()` for headers other than
  `Authorization`, for example GitLab's `PRIVATE-TOKEN`). A missing credential is a `CredentialRequiredException`, which
  the starter turns into `credential_required` with a link to the portal.
- Who is calling: `CallerContext.current()` (for audit and policy only; never as a credential for the backend system,
  invariant 2).
- `@ToolRisk` and `RiskLevel` stay in the package `pl.aiwcorpo.mcp.platform.guard`. `RiskLevel.parse` is removed.
- `SanitizedToolException.getMessage()` is now a C10 JSON object (it used to be `code: message`). The codes
  `credential_required` and `access_denied` replaced `missing_credential` and `forbidden`. `ToolErrorMapping` must return a
  C10 code; the client sees any other code as `internal_error`. `McpErrorMapper.SafeError` has a third field, `details`
  (fields specific to the code).
- The constructor of `AuditingToolCallback` and of `ToolCallbackAuditPostProcessor` takes a `ToolRiskRegistry` and
  interceptors; `AuditLogger` writes C8 events (`toolCall(...)`), and the old method `toolInvoked` is removed.
- Correlation header: `X-Request-Id` (C3) instead of `X-Correlation-Id`; MDC key `requestId` instead of `correlationId`.
- Tests: instead of a local IdP (`TestIdentityProvider`) use `TestGateway` from `mcp-platform-test`, profile `dev`.

## MCP transport

From 3.0.0-alpha-2 the starter sets `spring.ai.mcp.server.protocol=STREAMABLE`, `type=SYNC` and
`streamable-http.mcp-endpoint=/mcp` by default (the server may override them). A server on 3.0.0-alpha-1 has to set them
itself.

## Changes in 3.0.0-alpha-3 (after the review of alpha-1)

- A message reaches the client as `invalid_argument` only from `InvalidToolArgumentException` (a new class, a subclass of
  `IllegalArgumentException`). A plain `IllegalArgumentException` gives `invalid_argument` with a fixed text: throw argument
  validation whose message the model is meant to see as `InvalidToolArgumentException`.
- The `dev` profile relaxes rules only as the sole active profile; `dev` together with another profile stops the start.
- Outside `dev`: `jwks-uri` must be `https`, `server.ssl.client-auth=need` is required, and there is no separate
  `management.server.port`.
- `unauthenticated-paths` accepts only the health list; the MCP protocol is only `STREAMABLE`.
- The `@McpTool` scanner is disabled; a tool outside the wrapper stops the start.
- Default `spring.data.redis.timeout=2s`.

## Changes in 3.0.0-alpha-4

- `server-id` must name a system from the contracts' `SystemId` list; outside the `dev` profile any other system stops the
  start, in `dev` it is a warning.
- The log line `event=tool_failed` carries no exception messages when the cause chain holds an HTTP error from
  `RestClient` (only classes and status). The workaround in the adapter (a `defaultStatusHandler` that rewrites the
  exception) is no longer needed, but it does no harm.

## Changes in 3.0.0-alpha-5

- The `dev` profile listens on the loopback interface only: the starter sets `server.address=127.0.0.1` (lowest
  priority), and another address in `dev` stops the start. Run servers in containers (the dev stack, profile `full`)
  without the `dev` profile, with mTLS and the gateway key.
- The log line `event=tool_failed` leaves out messages also for exceptions of the HTTP and Jackson converters (a 200 body
  that cannot be parsed).

## Changes in 3.0.0-alpha-6

The API has no removals (bumping the BOM is enough to compile). Behaviour and startup changes that can stop a server:

1. **Argument validation by the starter.** The starter turns off `validateToolInputs` in the MCP SDK and checks the
   arguments against `inputSchema` in `AuditingToolCallback`; a violation is a C10 `invalid_argument` (without the argument
   value) and a C8 event. Outside the `dev` profile a tool without `inputSchema`, or with a schema that lacks `type: object`
   and `additionalProperties: false`, stops the start (decision: fail closed, not a warning). Schemas that Spring AI
   generates from `@Tool` are closed; hand-written schemas must be closed (the servicedesk schemas already are).
2. **One MCP customizer.** The starter provides a `McpSyncServerCustomizer` (`@Primary`) that combines the server's
   customizers and finally forces `immediateExecution(true)` and `validateToolInputs(false)`. A server's own customizer
   marked `@Primary` stops the start.
3. **`@ArgumentLimits` and `SchemaLimitedToolCallbacks.of(...)`** in the package `pl.aiwcorpo.mcp.platform.input`.
4. `TestGateway.asGateway(user, credential, BackendCredential.Type)`.
5. The BOM manages the version of `mcp-cedar-entities` (do not add it to the server for patterns, see below).

### What a server removes from its own workarounds

| Server / template | Remove | Use instead |
| --- | --- | --- |
| all | its own `@Primary McpSyncServerCustomizer` with `validateToolInputs(false)` and `immediateExecution(true)` (gitlab: `toolInputsCheckedByTheTools`) | nothing; a server that needs its own MCP settings adds a `McpSyncServerCustomizer` bean without `@Primary` |
| template, gitlab | its own `ArgumentLimits` annotation and `SchemaLimitedToolCallbacks` / `withArgumentLimits` in `McpToolsConfiguration` | `pl.aiwcorpo.mcp.platform.input.ArgumentLimits` and `SchemaLimitedToolCallbacks.of(...)` |
| confluence | `@StringArgument`, `ConstrainedToolCallbacks` | `@ArgumentLimits(minLength, maxLength, pattern)` and `SchemaLimitedToolCallbacks.of(...)` (returns a `ToolCallbackProvider`; confluence currently returns `ToolCallback[]`) |
| gitlab | its own `@Primary McpSyncServerCustomizer` (`toolInputsCheckedByTheTools`; the server does not start without removing it) and `StrictArgumentsToolCallback` (rejecting unknown arguments) | `SchemaLimitedToolCallbacks` adds `additionalProperties: false`, and the starter rejects them as `invalid_argument` |
| servicedesk | hand-written schemas (the `inputSchemas` map in `McpToolsConfiguration`) | `@Tool` + `@ArgumentLimits`; if a schema stays hand-written, it must have `type: object` and `additionalProperties: false` (it does) |
| opensearch | `StrictArgumentsInterceptor` (rejecting unknown arguments) | not needed: `additionalProperties: false` in the schema plus the starter's validation; delete it together with the tests of the SDK text |
| jira | (no workarounds of its own for the schema) | optionally `SchemaLimitedToolCallbacks.of(...)` instead of `MethodToolCallbackProvider`, so the limits reach `inputSchema` |
| all | copies of the identifier patterns from `ResourceIds` in constant `pattern` values | `@ArgumentLimits(contractPattern = "JiraIssueKey")` and so on (the name of a definition in `common.schema.json`) |
| all | `TestGateway`: setting `X-MCP-Credential-Type` by hand in `service-account` / `user-oauth` tests | `GATEWAY.asGateway(user, credential, BackendCredential.Type.SERVICE_ACCOUNT)` |

Differences from the servers' own versions: `@ArgumentLimits` has no default `maxLength = 0` as in the template (no limit is
`-1`, and a text without `maxLength` stops the start); gitlab adds `minimum`/`maximum` for integers (they are in the
starter). Validation in the tool code stays as a second line, but the message about a schema violation no longer has to be
created there: the starter gives the same `invalid_argument` code. Tests that checked the SDK text `input validation
failed` must change to the C10 `invalid_argument`.

The name of an unknown argument comes from the client: the SDK validation put it into the log without escaping (a line
that imitated an audit event). From alpha-6 the SDK validation is off, and the starter neither logs nor returns names
outside the schema (`InputValidationTest.anArgumentNameCannotForgeALogLine`). A server should not log argument names
outside the schema, or values.

Server tests: to check that a bad argument gives C10 and C8, a call through `TestGateway` is enough (model:
`InputValidationTest` in `mcp-platform-test`).

## Changes in 3.0.0-alpha-7

Bumping the BOM requires fixes in servers that have parameters without limits (see below). Contracts: the BOM manages
`mcp-contracts` 2.1.1 (the Cedar schema is byte-identical across 2.x; what changed are the patterns `OpenSearchIndexName`
and `OpenSearchIndexPrefix`, now without a leading dot, so the opensearch server can drop its own dot filter, and
`UntrustedText`, the `authentication` event and the Confluence whoami on the broker side). Servers add no dependencies; the
gateway test (`mcp-platform-test`) reads the 2.1.1 vectors.

### The start stops when any level of a parameter has no limit

The alpha-6 rule (text with `maxLength`) now covers every level: record fields, lists, numbers, maps. Before bumping,
check every `@Tool`:

| Parameter | What to do |
| --- | --- |
| `Integer`, `Long`, `int`, `Double`... | `@ArgumentLimits(minimum = ..., maximum = ...)`, both mandatory (the optional `limit` in jira, gitlab, opensearch, servicedesk) |
| `List<String>`, `String[]` | `@ArgumentLimits(maxItems = ..., maxLength = ..., pattern = ...)`: `maxItems` is mandatory, the rest describes the elements |
| record (a filter, for example `JqlFilter`) | `@ArgumentLimits` on every component of the record; an optional component gets `@Nullable` (`org.springframework.lang.Nullable` or `org.jspecify.annotations.Nullable`, both work on input and on output, test `BoundedSchemaTest`); nothing on the parameter itself |
| `List<Record>` | `@ArgumentLimits(maxItems = ...)` on the parameter, limits on the record's components |
| `Map<String, String>` (an argument) | replace with a record with named fields, or with a list of `{name, value}` records with `@ArgumentLimits(maxItems = ...)` on the list and limits on both components (a map cannot be bounded, the start stops) |
| `Object`, `JsonNode`, a class that is not a record | replace with a record |
| hand-written schema | every level: `additionalProperties: false`, `maxLength`, `maxItems` + `items`, `minimum`/`maximum`; a map only with `maxProperties`, `propertyNames.maxLength` and a bounded value; after `prefixItems` the `items` keyword is required (`false` or bounded) |
| `pattern` | must start with `^`, end with `$` and have no `|` outside a group: `[0-9]+` -> `^[0-9]+$`, `^a|b$` -> `^(?:a|b)$` (`NON_BLANK_LINE` from confluence, `^(?=.*\S)[^\u0000-\u001f\u007f]+$`, already complies: anchored, no `|`, so nothing to change) |

**jira.** `McpToolsConfiguration.boundedFilter` and `withBoundedFilter` are to be removed: `JqlFilter` gets
`@ArgumentLimits(minLength = 1, maxLength = JiraTool.MAX_NAME)` on `status` and `type`, `maxLength = MAX_USERNAME` on
`assignee`, all three `@Nullable`; the registration is `SchemaLimitedToolCallbacks.of(jiraTool)` without a loop over the
callbacks. The starter closes the object itself, removes the artificial `required` (thanks to `@Nullable`) and enforces the
limits on every call. Replace the `McpToolsConfigurationTest` test of `boundedFilter` with a test that calls the tool with a
`status` that is too long (C10 `invalid_argument`).

**Outside `dev` this rule can no longer be switched off with a bean.** A custom `ToolInputPolicy` or
`ToolCallbackAuditPostProcessor` bean stops the start; an extra layer around tools is a `ToolCallInterceptor`.
`ToolInputPolicy.requireClosedSchema()` is now called `requireBoundedSchema()`.

### Characters in arguments and in text from the backend system

- Arguments: text with U+2028/U+2029, an unpaired surrogate, a noncharacter (U+FFFE, U+FFFF), any `\p{Cf}` except
  U+200C..U+200F (bidi, soft hyphen), zero-width characters, U+2060..U+2064, a BOM or tag characters is an
  `invalid_argument` (before, only C0/C1/DEL). In a value with a `pattern` also `\n`, `\r`, `\t` and every `\p{Cf}`; the
  `pattern` rule is evaluated for the schema node of that value, not for the top-level argument.
- Text from the backend system (descriptions, comments, titles) goes through `TextHygiene.normalize(...)` or straight to
  `UntrustedText.plain(...)`. The jira and confluence servers replace their own cleaning (`JiraToolViews`:
  `\p{Cntrl}`/`\p{Cf}`) with a call to the helper. From alpha-8 a removed run of invisible characters is replaced by one
  space (see "Changes in 3.0.0-alpha-8"), and the normalization must happen **before** the server's own filtering.

### `UntrustedText` as the shared type (CC-072)

Use `pl.aiwcorpo.mcp.platform.text.UntrustedText` instead of your own `body`/`summary` record. A tool result must be a
record; the starter publishes `outputSchema` and returns `structuredContent`. The `definitionHash` of the tools changes (a
new `outputSchema` field), so the gateway marks them `pending_approval` until they are approved again. Confluence: move
`omittedMacros` from inside `body` to next to `body`. Jira: `description` and comments, until now with `format: text/plain`,
contained raw wiki markup; what goes into `UntrustedText.plain(...)` is the text after removing the syntax of images, links
and macros (the server removes it on text that is already normalized: `TextHygiene.normalize(raw)`, then the filter, then
the factory) or, if the markup stays, the deviation must be documented.

### Audit and logs

- A rejected request leaves a C8 `authentication` event in the audit (without `sub`, `client`, `tool`); the audit receiver
  must have the 2.1.1 schema. Nothing to do in the server code.
- `event=tool_failed`: INFO (C10 code and exception class) for expected errors, including the domain exceptions of
  `ToolErrorMapping`; WARN for an unavailable backend; ERROR only for `internal_error`. A server that logged its own ERROR
  line for `not_found_or_denied` or `invalid_argument` can remove it. Alerts on ERROR now count only unexpected failures.
- `server/discover` (MCP 2026-07-28) is a protocol request: the service variant is allowed. Nothing to do in the server code.

### Server tests

`mcp-platform-test` has a test for `tool_failed` and the `authentication` events (listening on the logger
`pl.aiwcorpo.mcp.audit`). Server tests that check the ERROR level for a domain error must change to INFO.

## Changes in 3.0.0-alpha-8

Work was done on `3.0.0-alpha-8-SNAPSHOT`; alpha-7 and earlier were left untouched.

### Order: normalize first, then filter

`TextHygiene.normalize` no longer removes invisible characters without a trace: a run of removed characters (control
characters, noncharacters, every `\p{Cf}`) is replaced by one space (skipped at the start and end of the text and next to
whitespace). Before, `"@a<ZWSP>ll"` gave `"@all"` and `"!<ZWSP>https://host/x.png!"` gave the image syntax: text that the
server filter let through because it did not recognize it came out as exactly what the filter was looking for. Now the
output is `"@a ll"` and `"! https://host/x.png!"`. The cost: an emoji sequence with a ZWJ falls apart into separate emoji, and
a ZWNJ in Persian text becomes a space.

The order in the server (jira, confluence, every server that strips syntax from backend text):

1. `String clean = TextHygiene.normalize(raw);`
2. the server filter on `clean` (images, links, macros, mentions, `{noformat}`): it sees what the client will see;
3. `UntrustedText.plain(filtered, MAX)` or `markdown(...)`.

The opposite order (the filter on raw text, normalization in the factory) is not safe: the filter does not recognize
`!<ZWSP>https`. The `UntrustedText` constructor normalizes too (`new UntrustedText(...)` and Jackson deserialization do not
bypass the hygiene), but that is the last line of defence, not the place for filtering.

### A result with `null` in a component without `@Nullable`

For a tool with `UntrustedText` in its result, the result schema follows the record type: a component without `@Nullable` is
`required` and not nullable. A server that moves to `UntrustedText` (jira, confluence) has fields from the backend system in
its result records that may be empty (`assignee`, `dueDate`, `resolution`, `parentId`): **mark each such field `@Nullable`**
(`org.springframework.lang.Nullable` or `org.jspecify.annotations.Nullable`), otherwise a ticket with no assignee ends in an
error. Change in the starter: the result is checked against the schema in the tool wrapper, before the audit. A mismatch is
a C10 `internal_error` and a C8 event with `outcome=error` (before: the audit said `ok`, and the client received `isError`
with an SDK text that neither the client nor the gateway recognizes). The ERROR log line has the field path, not the value.
Do not use Jackson annotations on result components: the schema takes the component names, so `@JsonProperty` makes the
schema and the serialization diverge, and the call ends in `internal_error`. Server test: call the tool on a ticket with
empty optional fields (result ok, `structuredContent` with `null`).

### Running locally in `dev` (alpha-7 smoke test)

In the `dev` profile (the sole active profile) the starter forces, above the server's `application*.yml`,
`server.ssl.enabled=false`, `mtls.required=false`, `internal-jwt.replay-store=memory` and an empty
`internal-jwt.jwks-ssl-bundle`. These four settings (and the CLI workarounds) can be removed from the server's
`application-dev.yml`; `allow-test-keys: true` stays where the local gateway signs with test keys. Description and start
command: README, "Running locally".

### Other behaviour changes

- Object keys in arguments (a map with `propertyNames`, a nested object, an argument name) go through `TextHygiene` like a
  value with a `pattern`; a violation is an `invalid_argument` ("x has a name with ..."), without quoting the key.
- An array element `i` is judged by the rule `prefixItems[i]`, the following ones by the rule `items`.
- `ToolCallbackAuditPostProcessor` no longer reads the `ToolInputPolicy` bean: the policy follows from the active profiles
  at creation time. `RegisteredToolsCheck` and `ToolHintsPostProcessor` are not `@ConditionalOnMissingBean`; a server's own
  bean of any of the four types stops the start outside `dev`.
- `tool_failed` (WARN and ERROR): control and bidi characters are removed from the cause chain, it is one line, and it has
  no URL query.

## Changes in 3.0.0-alpha-9

Work was done on `3.0.0-alpha-9-SNAPSHOT`. The released alpha-8 in `~/.m2` was overwritten in the process by the working
code of alpha-9 (the incident is described in `STATUS.md`); restoring it from the tag `v3.0.0-alpha-8` is up to a human.
Until then, do not take the result of a build "on alpha-8" as proof of how the alpha-8 release behaves. Moving from
alpha-8: bump the BOM version to `3.0.0-alpha-9`. The public API has no removals that would touch server code compatible
with alpha-8; the code has to change only where the points below say so explicitly.

- **Order for backend text**: conversion of the format to text (including decoding entities such as `&#8203;`,
  `&#x200B;`), `TextHygiene.normalize`, the server filter, `UntrustedText.plain|markdown`. The factory logs a WARN
  `event=untrusted_text_not_normalized tool=...` when the text changed after the filter (usually: entities decoded after
  normalization); once the server uses this order, its tests show no such line.
- **Truncation in the `UntrustedText` factory** steps back to the last whitespace if the kept part of the last word has at
  most 64 units: `plain("hello @allison", 10)` gives `hello` (`truncated=true`), not `hello @all`. A server test that
  compares the exact truncated text must be corrected; a longer string without spaces is cut where the limit falls.
- **Result limit** `aiwcorpo.mcp.platform.tools.max-result-size` (default 1MB, UTF-8 bytes): a larger tool result is a C10
  `internal_error` (audit `error`). A tool that returns lists should paginate. A result that does not match `outputSchema`
  is now always `internal_error`, also when the server's error mapping handles `IllegalStateException` (the platform
  exception is a separate type, `ToolResultRejectedException`, checked before the server's mappings).
- **`dev` profile**: with a separate `management.server.port` the guard requires a loopback `management.server.address`
  (Spring Boot does not let you set this address without a separate port, so the starter has no default). `dev` as the only
  profile in a deployment environment (a cloud platform detected by Spring Boot, the `INVOCATION_ID` of a systemd unit)
  ends the start: remove `SPRING_PROFILES_ACTIVE=dev` from `.env`, compose templates and units (the template's `install.sh`
  does not set it). The line `dev_profile_overrides` is logged at WARN and lists the keys that the server configuration
  had set differently.
- **Beans**: `PlatformConfigurationGuard`, `InternalJwtVerifier` and `InternalJwtFilter` are not extension points; a custom
  bean of any of them stops the start in every profile (the extension points are `GatewayKeys`, `JtiReplayStore`,
  `AuditLogger`, `ToolRiskRegistry`, `McpErrorMapper`). `AuditingToolCallback` and `ToolCallbackAuditPostProcessor` have
  package-private constructors, `ToolInputPolicy` is an enum (`STRICT`, `DEV`); server code that did not build them (none
  of the five servers and the template) needs no changes.

## Changes in 3.0.0-alpha-10

Work was done on `3.0.0-alpha-10-SNAPSHOT`. Moving from alpha-9: bump the BOM version to `3.0.0-alpha-10`. Server code
compatible with alpha-9 changes only where the points below say so explicitly.

- **Server tests in CI**: the "`dev` in a deployment" check does not cover a context built by Spring TestContext
  (`@SpringBootTest`, slices), so tests with `TestGateway` pass on a Kubernetes pod and under a systemd runner. There is
  nothing to set; do not add `INVOCATION_ID=` or `SPRING_MAIN_CLOUD_PLATFORM=` to the CI configuration (they do not work and
  would leak into the deployment). `spring.main.cloud-platform=none` is no longer a deployment platform.
- **Deployment signals for `dev`**: also `KUBERNETES_SERVICE_HOST` together with `KUBERNETES_SERVICE_PORT`, and a container
  (`/.dockerenv`, `/run/.containerenv`, the variable `container`, cgroup); the process variables are read directly,
  `-DINVOCATION_ID=` does not mask them. An image with `SPRING_PROFILES_ACTIVE=dev` will not start.
- **Management port** is compared as a number: `0` is always a separate server (requires loopback in `dev`, refused outside
  `dev`), `-1` turns off the actuator HTTP and needs no address.
- **Excluding the platform auto-configuration** (`spring.autoconfigure.exclude` or `exclude` on `@SpringBootApplication`)
  ends the start outside `dev` and outside a test context.
- **Text truncation**: in a string without spaces longer than 64 units the trailing letters and digits are dropped, so no
  incomplete token is left after delimiters (`(((...(@allison` -> `(((...(@`). New `UntrustedText.cut(text, limit)`,
  `plainCut(text, truncated)` and `markdownCut(...)`: a server with a filter cuts before the filter, then builds the value
  from the already shortened text (the cut can remove a closing delimiter such as a backtick, and the filter must see what
  the client will get). Order: conversion, `normalize`, `cut`, filter, `plainCut|markdownCut`. A server test that compares
  the exact truncated long string must be corrected.
- **WARN `untrusted_text_not_normalized`** does not appear for `null` or for a change of line endings alone (CRLF, CR,
  U+2028, U+2029, U+0085 to `\n`).

## From beta-13 to beta-14

Move to contracts **6.3.2** (contracts CHANGELOG 6.3.0, 6.3.1, 6.3.2). Version bump:
`<mcp-platform.version>3.0.0-beta-14</mcp-platform.version>` or `<version>3.0.0-beta-14</version>` in the `mcp-platform-bom`
import; the BOM sets the contracts to 6.3.2. The API only grows (the added component of the `CallerContext` record has a
fallback constructor with the old signature).

- **CQL queries have `AND type = page` (CC-219).** A Confluence server that appended `type = page` to a `StructuredQuery`
  query itself now has it twice: remove its own (keep the type filter on the response as defence in depth). JQL queries are
  unchanged. A test that compares the CQL text must change: `... AND space in ("ENG") AND type = page ORDER BY ...`.
- **JWT receiver mode (CC-226).** A native server does nothing: a token with `mcp_risk` or `mcp_action` is
  `401 invalid_claims` (the default is `receiver-mode: native`). An adapter sidecar sets
  `aiwcorpo.mcp.platform.internal-jwt.receiver-mode: adapter`; then `tools/call` without those claims is `401` with the
  reason `classification_missing`, and the classification is available in `CallerContext.mcpRisk()` and `mcpAction()`. A
  value other than `READ_ONLY` is logged and counted (`mcp_adapter_classification_not_read_only_total`). New
  `InternalJwtRejection.CLASSIFICATION_MISSING` and `InternalJwtVerifier.Settings` with a seventh component `receiverMode`
  (the old constructors stay). Sidecar test with `TestGateway`: `Token.extraClaim("mcp_risk", "READ_ONLY")` and
  `extraClaim("mcp_action", "read")`.
- **Manifest regeneration**: a native server has no hash changes (contracts 6.3.x do not touch the definition or the hash
  for manifests without `source`).
- **Vectors:** `jwt/` 66 (was 59), `audit-events/` 128 (was 125), `query-rendering/` 27 (was 26). Update a server test that
  counts the files of a directory.
- **`decision` in the `authentication` event** is optional in the schema (the broker does not write it); the starter writes
  it as before, so nothing changes on the server side.

## From beta-12 to beta-13

Version bump: `<mcp-platform.version>3.0.0-beta-13</mcp-platform.version>` or `<version>3.0.0-beta-13</version>` in the
`mcp-platform-bom` import. The `CursorCodec` signatures are unchanged, contracts 6.2.1.

- **The cursor is encrypted (AES-256-GCM)**, not only signed: the position in the cursor (for example the `search_after`
  sort values of a hit that the policy hid) is no longer readable by the client. The new format starts with `c2.`; it still
  fits `CursorCodec.ARGUMENT_PATTERN` and `MAX_CURSOR_LENGTH`. Cursors issued by beta-12 and earlier are rejected
  (`invalid_argument`, the same text); they live for 15 minutes, so there is nothing to do.
- The cursor key is derived from `paging.cursor-key` by HKDF with the `server-id`: the same `cursor-key` in two servers gives
  two different keys. Treat `cursor-key` as a secret (Base64, at least 32 bytes, not in the repository).
- Servers that put sort values or other item data into the cursor position need not change anything; that is exactly the
  case the encryption closes. The position still should not contain credentials (the server that opens it sees it in the
  clear).

## From beta-11 to beta-12

Just a version bump: `<mcp-platform.version>3.0.0-beta-12</mcp-platform.version>` in the template or
`<version>3.0.0-beta-12</version>` in the `mcp-platform-bom` import. The contracts stay at 6.2.1, the API only grows (no
signature was removed or changed), the manifest and `definitionHash` are unchanged. What changes in behaviour:

- **The cursor is bound to the `server-id`.** A cursor issued by beta-11 will not pass in beta-12 (cursors live for 15
  minutes, nobody keeps them). New constructor `CursorCodec(key, validity, clock, serverId)`; the old one stays, with an
  empty `serverId`. The starter's bean supplies the `server-id` itself.
- **Cursors with `=` and with non-zero padding bits are rejected**; cursors issued by `CursorCodec` are not like that.
- **The warning about a missing `paging.cursor-key` appears at the first issued cursor**, not at startup.
- **New, optional: `PageFiller.fill(...)`** for tools whose page size comes from the client (README, "Large results"). A tool
  that returns a short page with `nextCursor` after `ResultFilter` reveals that something was hidden; filling the page
  prevents that.
- **Version probe**: a call that exceeds the time limit and ignores the interrupt blocks further runs of that probe (state
  `UNAVAILABLE`) instead of multiplying threads. `ProductVersion.parse("9.12.34567")` is now empty. A probe should not hold
  a server secret (README, "System versions").
- **`ci/run.sh`**: `project_version` calls `maven-help-plugin:3.5.1`; that version must be in `~/.m2` or in the registry.

## From beta-10 to beta-11

Move to contracts **6.2.1** (contracts CHANGELOG 6.0.0, 6.1.0, 6.2.0, "Migration" sections for the gateway, the broker and
the BFF). 6.2.1 is packaging and licence only, the contracts are byte for byte the same as in 6.2.0. The starter code
needed no changes because of the contracts; below is what a server does.

### 1. Version and repositories

`<mcp-platform.version>3.0.0-beta-11</mcp-platform.version>` in the template or `<version>3.0.0-beta-11</version>` in the
`mcp-platform-bom` import; the BOM sets `mcp-contracts`, `mcp-cedar-entities`, `mcp-policy-bundle` and `mcp-manifest` to
6.2.1. Where the artifacts come from is described in the README, "Using the artifacts"; for local work `~/.m2` is still
enough.

### 2. Manifest regeneration

Build the server and register the manifest from the build step again (`manifest.output-file`). The `definitionHash` of the
tools does not change: contracts 6.x do not touch the definition or the hash rules for manifests without `source`, and a
server on the starter does not set `source`. If the hash of some tool did change, it is not because of the contracts: check
whether the tool returns a generic record with `UntrustedText` (point 4).

### 3. `UPDATE` tools (CC-204)

`UPDATE` is delivered at least once: after a `502` from the balancer the client may repeat the call. Go through the
server's `UPDATE` tools and, where the backend system allows it, design them so that a repeat is harmless (target state
instead of an increment, a key to check, an expected version). README, "Repeatability of `UPDATE` tools". Nothing is
enforced.

### 4. Library changes with nothing required of the server

- **`CursorCodec` and `PagedResult<T>`** (README, "Large results"): a shared cursor and result shape for tools that paginate
  or truncate content. Servers with their own cursor may keep it; new tools should use the shared one. All instances of a
  server need the same `aiwcorpo.mcp.platform.paging.cursor-key` (Base64, at least 32 bytes); without a key the starter
  generates one at startup and warns.
- **`CapabilityProbe`** (README, "System versions"): the bean of the backend system version probe; an unknown version gives
  the `degraded` mode instead of stopping the server. Without the bean nothing changes.
- **`ToolOutputSchemas`** substitutes the arguments of generic records (`Page<Issue>`). A tool that returns such a record
  with `UntrustedText` in the element now gets an `outputSchema` that it did not have before, so its `definitionHash`
  changes and the result is checked against the schema. No server in this repository has such a record.

### 5. Tests and vectors

The `audit-events/` directory has 125 vectors (115 in 5.1.0; the broker's events were added). Update a server test that
counts the files of a directory; the schema matches all of them. The other numbers are unchanged: `jwt/` 59,
`result-filter/` 7, `query-rendering/` 26, `tool-errors/` 33.

## From beta-9 to beta-10

Move to contracts **5.1.0** (contracts CHANGELOG, 5.0.0 and 5.1.0, "Migration" sections for the gateway, the broker and the
BFF). Saved links (`/v/<slug>/mcp`) and the claims `vs` / `vsc` no longer exist, so everything the starter did with a link
is gone. Libraries, policy bundles and servers move together with gateway 5.x: a beta-10 server rejects a token with `vs` or
`vsc`, and a 4.x gateway issues them.

### 1. Version

`<mcp-platform.version>3.0.0-beta-10</mcp-platform.version>` in the template or `<version>3.0.0-beta-10</version>` in the
`mcp-platform-bom` import; the BOM sets `mcp-contracts`, `mcp-cedar-entities`, `mcp-policy-bundle` and `mcp-manifest` to
5.1.0. Correct the version values in the server's README by hand.

### 2. What stops working

- **A token with `vs` or `vsc` is `401 invalid_claims`** (the 5.x schema `internal-jwt.schema.json` has
  `unevaluatedProperties: false`). `TestGateway.Token.virtualServer(slug, scopes)` is gone; for a rejection test use
  `Token.extraClaim(name, value)` (a claim outside the contract, for example `extraClaim("vs", "abc-only")`). Server tests
  that checked narrowing by a link (`virtualServer("abc-only", Map.of("jiraProjects", ...))`) should be deleted or replaced
  with a plain read that expects no narrowing. Tests that so far accepted a token with `vs` / `vsc` (gitlab:
  `GatewayLinkClaimsTest`; tests like `acceptsTheVirtualServerClaims`) should change to expect `401`: use `extraClaim`, do
  not delete them, because they guard the rejection. The same holds for the service variant (`system:mcp-gateway`) with
  `vs`: `401`.
- **`CallerContext`** no longer has the components `virtualServer` and `virtualServerScopes` or the method
  `virtualServerSlug()`; the canonical constructor has two arguments fewer
  (`new CallerContext(sub, usr, grp, azp, tool, rid, prv, mfa)`). This concerns code that builds it itself (tests).
- **`Caller` from `mcp-cedar-entities`** no longer has `withVirtualServer` or the component `virtualServer`;
  `VirtualServerScope` does not exist. `ResourcePolicy` builds the caller without them.
- **`QueryConstraint(resourceType, granted)`** (two components). Server queries have no narrowing by a link:
  `queryScope(type)` gives only the grants from groups.
- **`QueryScope`:** `linkIds()` is empty until the scope passes through `narrowedTo(ids)` (the name stays for source
  compatibility; this is narrowing by an argument, not by a link), and `linkType()` is gone. So `jqlProjectClause()`,
  `cqlSpaceClause()` and `jql(...)` / `cql(...)` add no scope clause without `narrowedTo`. Server code that refused or
  returned an empty result when `linkIds()` had no elements in common with the argument becomes a dead branch: **remove
  it**. After `narrowedTo(List.of(x))` the scope is exactly the argument, so an empty intersection does not occur. To
  remove: `JiraTool` (`scope.linkIds().filter(Set::isEmpty)`), `ConfluencePageTools`
  (`scope.linkIds().map(Set::isEmpty)`), servicedesk `QueueGrants.permits` (the condition on `linkIds()`; what stays is
  `!scope.nothingToQuery() && scope.grantedIds().contains(queue)`), `ExampleSearchQuery` in the template and the profiles.
  For OpenSearch `linkIds()` is always empty (`narrowedTo` for that type throws, as before).
- **Server audit:** events have no `virtualServer` field, and the reason `link_scope` is no longer emitted (a policy refusal
  in the probe and after resolving is `policy_forbid`). Remove alerts and rules over the reason `link_scope`. Gateway 5.x
  maps the old activity rows when reading (CC-188), but the server maps nothing.
- **Server documentation** (each server's `README.md` and `docs/`, and the template's `docs/jak-napisac-narzedzie.md`, in
  which `linkIds()` describes the server's scope): remove the descriptions of links, `vs` / `vsc`, `link_scope`,
  `vs-narrow-*` and `virtual-server.cedar`; the scope is the grants from groups and `narrowedTo`.
- **`PolicyEngine`:** `RecordingAuthorizer`, `recordingAuthorizer(Snapshot)` and `LINK_NARROWING_PREFIX` are gone (they
  only served to recognize the `vs-narrow-*` rules). Use `authorizer(Snapshot)`.
- **`mcp-platform-test`:** `PolicyRepository.publishGenerated` does not attach `virtual-server.cedar` (the example left the
  contracts in 5.0.0). Server tests can no longer expect `link_scope`.

### 3. Cedar schema: new bytes (CC-173)

`mcp.cedarschema` 5.x (SHA-256 `51d71b2dbb7f61fd2593cf4c2f1a2373dea06e8b4431425a3f1aa24fa10a0ba5`, previously `902b2540...`)
has no `context.virtualServer`, no `context.vsScopes` and no type `VsScopes`. A policy bundle built for 4.x gives
`schema_mismatch` (the previous one stays, and the first bundle does not let the server start); a beta-10 server needs a
bundle from `mcp-policies` built on 5.x, and a beta-9 server will not accept a 5.x bundle. The schema bytes in 5.0.0 and
5.1.0 are the same, so a bundle built for 5.0.0 stays valid. Test: `PolicyEngineTest` with a bundle from contracts 4.1.0
(`aBundleOnTheSchemaOfContracts4_...`). A policy that reads `context.vsScopes` does not pass validation
(`policy_invalid`).

### 4. Tests and vectors

The 5.1.0 vectors that the starter runs: `jwt/` (59; `removed-vs*` give `invalid_claims`), `audit-events/` (115),
`result-filter/` (7, without the "through-link" variants), `resource-checks/`, `query-rendering/`, `bundles/`. The vector
`result-filter/servicedesk-tickets-by-grant` assumes the credential type of a service account (the example policies without
`context.credentialType` treat the type as a service account, `credential-type.cedar`), so build a test with a queue
outside the grant with `service-account` as the default type, not `user-secret`.

### 5. Tests in a container and server CI

- **`DevProfileLoopbackTest`** (and every test that starts a server with the `dev` profile itself through
  `SpringApplicationBuilder`) fails on a host with a deployment signal: in a CI container, in a pod, under systemd, because
  the guard takes it for a deployment. The starter does not perform this check for a context built by the Spring test
  framework (`@SpringBootTest`), so rewrite a test that only needs a running server as `@SpringBootTest`. A test that
  checks the deployment refusal itself should be guarded by a condition on the same signal; the model is
  `DeploymentSignalAssumption` in `mcp-platform-starter/src/test` and `mcp-platform-test/src/test` of this repository
  (`DevProfileEnvironment.deploymentSignal(new MockEnvironment(), DevProfileEnvironment.LOCAL_HOST, () -> false)` in the
  package `pl.aiwcorpo.mcp.platform.autoconfigure`; the helper class is not in the released artifacts). A skipped test is
  visible in the surefire report.
- **Server CI**: each server has its own `ci/run.sh`, `make ci` and thin overlays (`.gitlab-ci.yml`, `Jenkinsfile`,
  `.github/workflows/ci.yml`). The model is the files in this repository. Rules to adopt: code from a merge request gets
  neither the Docker socket nor main's secrets (the change job with `MCP_CI_NO_DOCKER=true` and a read-only registry
  account, the main job on a runner with the socket and in the environment `ci-main`), separate cache keys with
  `policy: pull` for changes, `clean verify`, an error on zero tests, and `release` refuses when tests were skipped.
- **In this repository:** `ci/run.sh` (`verify`, `hygiene`, `release`; README, "CI").

## From beta-8 to beta-9

For servers a version bump: `<mcp-platform.version>3.0.0-beta-9</mcp-platform.version>` (template) or
`<version>3.0.0-beta-9</version>` in the `mcp-platform-bom` import; the contracts are unchanged (4.1.0). Behaviour changes:

- **A link only narrows (a bug fix of beta-8).** `Confluence::Page` and `ServiceDesk::Ticket` called through a link
  (`vsScopes`) refused a reader with a user token who had no scope group (`mcp-confluence-space-*`,
  `mcp-servicedesk-queue-*`), although without the link the same reader read according to `read.cedar`. Now a link adds no
  scope group requirement. If the server or its tests worked around this with a scope group for the link, **remove the
  workaround**. A service account without a queue grant is still refused, also through a link.
- **A backend failure when resolving a page or ticket for a service account:** on the outside still `not_found_or_denied`,
  but the audit now has `policy_decision` with the reason `upstream_unavailable` and `outcome: error` (it was
  `not_granted`), and the log has `WARN event=resolver_failed_hidden` with the exception class and the HTTP status (without
  the response body). An alert on a backend failure can therefore rely on the reason `upstream_unavailable`, and an outage
  no longer looks like a wave of refusals.

## From beta-7 to beta-8

For servers practically just a version bump: `<mcp-platform.version>3.0.0-beta-8</mcp-platform.version>` (template) or
`<version>3.0.0-beta-8</version>` in the `mcp-platform-bom` import; the contracts are unchanged (4.1.0). Behaviour changes:

- **`Confluence::Page` and `ServiceDesk::Ticket` without a container scope:** beta-7 refused every caller without a group
  `mcp-confluence-space-*` / `mcp-servicedesk-queue-*`, also one with a user token to whom `read.cedar` gives read access
  according to the rights in the backend system (that was too broad). Now a caller without a scope is judged by the policy
  (contract, 9b.2): a service account (`credential-type.cedar`) still gets `not_found_or_denied` before the resolver, and a
  user with `user-oauth`/`user-secret` goes on to the resolver and the full evaluation. The requirement of space groups for
  Confluence from beta-7 no longer applies; the Service Desk bundle with a service account must still grant queue scopes.
  The vectors `servicedesk-service-account/` (6) move to `ResourcePolicy.authorizeCall` (`ServiceDeskVectorsTest`); an
  adapter need not reproduce them.
- **Service account and Page/Ticket:** a resolver error (other than a missing credential) and a resource returned without
  a container look like a missing resource (`not_found_or_denied`).
- **`QueryScope.OPENSEARCH_RESOLVE_PARAMETERS`** now has `ignore_unavailable=true` and `allow_no_indices=true` next to
  `expand_wildcards=open`: add the whole map to `GET _resolve/index/...` (a change of a constant, no API change). Without
  it, a missing index named exactly like the prefix (only `p-2026.10`) gave a 404 for the whole request.
- **A removed property `aiwcorpo.mcp.platform.credential-type` stops the start** (it used to be silently ignored) with a
  message saying what to do (remove it; the first type is the default in `accepted-credential-types`).
- **A resource outside the link:** a call with an argument (`@ResourceRef`) outside the scope of the virtual server ended at
  the tool level as `access_denied` (rule `vs-narrow-call-*`); from beta-8 it is `not_found_or_denied` with the text used
  for a missing resource (CC-146c), and the audit has `link_scope`. Change a server test that expected `access_denied` for a
  queue, project or space outside the link. This requires the narrowing rules in the bundle to have `vs-narrow-*`
  identifiers (as in the contracts example).
- **New: `QueryScope.narrowedTo(ids)`.** A search that the model limits to one project or space no longer has to filter the
  result on the server side: `scope.narrowedTo(List.of(projectKey))` gives the scope (the intersection with the link), and
  `jql(fields)` / `cql(fields)` renders it after the conditions (`(text ~ "x") AND project in ("DEF")`). An empty
  intersection: `nothingToQuery()` (return an empty result without calling the backend system). Pass the ids of an argument
  that is a `@ResourceRef` of type `Jira::Project` / `Confluence::Space`. This changes nothing for servers that do not use
  it.
- **Audit:** `policy_decision` with `credential_type_mismatch`, `policy_unavailable` and `policy_error` has the field
  `argsLength` (as in the vector `server-credential-type-mismatch`). `AuditLogger.policyRefused` and `policyUnavailable`
  have an additional parameter `long argsLength`.

## From beta-6 to beta-7

Fixes after the beta-6 review (0 HIGH, 3 MEDIUM, 6 LOW) and contracts **4.1.0** (the BOM; the 4.0.1 and 4.1.0 vectors are
unchanged for the starter except `credential-type-header/` and two audit events). The beta-6 property `credential-type`
**goes away**: the default type is the first of `manifest.accepted-credential-types` (CC-166, CC-168).

### 1. Version

`<mcp-platform.version>3.0.0-beta-7</mcp-platform.version>` in the template or `<version>3.0.0-beta-7</version>` in the
`mcp-platform-bom` import; the BOM sets `mcp-contracts` to 4.1.0. Contracts 4.1.0 also change the gateway (the risk floor,
`acceptRiskLowering`) and the BFF: the gateway and the BFF move together; a beta-7 server works with the old and the new
gateway, because the old gateway already sends the type header.

### 2. Credential type: set, header, refusal (CC-166, CC-168)

- Remove `aiwcorpo.mcp.platform.credential-type` from the configuration (an unknown property does not stop the start, but
  does nothing). `context.credentialType` in Cedar is the **first** of `accepted-credential-types`: put the system's
  default type first (GitLab: `[user-oauth, user-secret]`; Service Desk with a service account: `[service-account]`).
- The set does not mix `service-account` with user types: a server with `[user-secret, service-account]` does not start (the
  gateway also rejects the manifest: `422 credential_type_mismatch`). `accepted-credential-types` must not be empty, also
  with `credential-required: false`.
- A request with `X-MCP-Credential` must have an `X-MCP-Credential-Type` from the server's set. A missing header, an unknown
  value (exact comparison: `User-OAuth` is an error) or a value outside the set: an `internal_error` refusal before Cedar,
  the probe and the resolvers, with the audit `policy_decision` and `credential_type_mismatch`. Before, a missing or
  unknown type gave `400 invalid_argument` from the header filter; now it is `internal_error` from the tool (HTTP 200 with a
  C10 error). Requests without a credential are not checked.
- Server tests: `TestGateway` accepts `user-secret` and `user-oauth` (default `user-secret`); `asGateway(user, credential,
  type)` with `token-exchange` or `service-account` now gives `internal_error`. A server that tests another type overrides
  `aiwcorpo.mcp.platform.manifest.accepted-credential-types` in `serverProperties()`.
- Removed public `McpPlatformProperties.getCredentialType()`, `setCredentialType(String)` and `effectiveCredentialType()`
  (beta-6): server code that used them stops compiling; the default type is
  `getManifest().getAcceptedCredentialTypes().getFirst()`.
- `ResourcePolicy(..., system, List<String> credentialTypes, maxFiltered)` (instead of a single `String`); the first element
  is the default type, an empty list throws.
- New public class `CredentialTypeCheck.admits(credentialSent, typeHeader, accepted)` and
  `CredentialContext.credentialSent()` / `typeHeader()`.

### 3. Resources: `Confluence::Page` and `ServiceDesk::Ticket` (after the review)

A caller with no scope at all in the bundle for the container (space/page, queue) gets `not_found_or_denied` before the
resolver; a resource that the backend system returned outside the caller's scopes and that cannot be evaluated (a wrong
identifier, no classification) also gets `not_found_or_denied` instead of `internal_error`. This also applied to
Confluence (space scopes); from beta-8 only to a caller to whom the policy gives no read access without scopes (a service
account), see "From beta-7 to beta-8". A probe refusal (Jira, GitLab, Confluence Space, OpenSearch, queue) has the reason
`policy_forbid` in the audit (it was `not_granted`).

### 4. OpenSearch (after the review)

Instead of `String.join(",", names)` use `scope.opensearchSearchTarget(response)`: an `Optional<String>`, empty means "do not
ask" (an empty target would search all indices). `resolveIndexNames` skips names that do not match `OpenSearchIndexName`.
Add `QueryScope.OPENSEARCH_RESOLVE_PARAMETERS` to `GET _resolve/index/...`, and treat a 404 `index_not_found_exception`
response like `QueryScope.EMPTY_RESOLVE_RESPONSE` (behaviour on a live cluster not confirmed). Do not cache `_resolve/index`
responses between tool calls: the window between resolving and the query would last as long as the cache.

### 5. Text queries

No API changes. The README explains why the lack of escaping of Lucene characters in `CONTAINS` (as in the contracts
reference) does not widen the scope.

## From beta-5 to beta-6

Move to contracts **4.0.0** (contracts CHANGELOG, "4.0.0"). Libraries, policy bundles and servers move together: the new
bytes of `mcp.cedarschema` (CC-136) make a bundle built on the 3.1.x schema be rejected as `schema_mismatch`, so a beta-6
server needs a bundle from `mcp-policies` built on 4.0.0, and a beta-5 server will not accept a 4.0.0 bundle. Gateway 4.0.0
and broker 4.0.0 talk to a beta-6 server; beta-5 will not pass registration with gateway 4.0.0 (see "Manifest" below).

### 1. Version

A server from the template: `<mcp-platform.version>3.0.0-beta-6</mcp-platform.version>` in `pom.xml` (the property that
`mcp-platform-bom` reads through `${mcp-platform.version}`; `new-server.sh` takes the version from that template property).
A server without the property: `<version>3.0.0-beta-6</version>` in the `mcp-platform-bom` import
(`pl.aiwcorpo.mcp:mcp-platform-bom`, `<type>pom</type>`, `<scope>import</scope>`). The BOM sets `mcp-contracts` to 4.0.0 (also
`mcp-cedar-entities`, `mcp-policy-bundle`, `mcp-manifest`); do not write them by hand. Correct the version values in the
server's README by hand (starter `3.0.0-beta-6`, contracts `4.0.0`).

### 2. Configuration: `credential-type` (CC-136), mandatory (replaced in beta-7: the default type is the first of `accepted-credential-types`, see "From beta-6 to beta-7")

Every Cedar request now has `context.credentialType` from the server configuration, always. If
`manifest.accepted-credential-types` lists **exactly one** type, the starter takes it from there and nothing has to change. A
server that accepts several types (the README example: `[user-oauth, user-secret]`) must name the one that the configuration
of its system in the gateway uses (the same setting that the gateway uses for the `credential_type_mismatch` check):

```yaml
aiwcorpo.mcp.platform:
  credential-type: user-secret          # user-oauth | user-secret | token-exchange | service-account
  manifest:
    accepted-credential-types: [user-oauth, user-secret]
```

Without a determined type the server does not start (a message from the configuration guard), and a type outside
`accepted-credential-types` also stops the start (the gateway would reject the manifest: `credential_type_mismatch`). The
OpenSearch server registers `acceptedTypes` with `token-exchange` **before** gateway 4.0.0 (a manifest with `user-secret`
alone is rejected); the Service Desk server with a service account sets `credential-type: service-account`, because the
rules in `credential-type.cedar` depend on it. `TestGateway` in `mcp-platform-test` sets `credential-type=user-secret`; a
server test with another type overrides the property in `serverProperties()`.

Whoever builds `ResourcePolicy` by hand (tests): the constructor has a new parameter `credentialType` after `system` and
throws `IllegalStateException` for `null`.

### 3. `dev-` keys and dev mode (CC-137)

A JWT `kid` and a bundle key identifier that start with `dev-` (the keys that the development stack generates locally) are
rejected outside dev mode with the reason `test_key_not_allowed`: dev mode is the `devstack` profile (both consents) **and**
`allow-test-keys=true`. `allow-test-keys` alone (unit tests, the `dev` profile) still accepts `test-*` keys and the published
test keys, and rejects `dev-`. A stack with `dev-` keys therefore sets `SPRING_PROFILES_ACTIVE=devstack` together with
`AIWCORPO_MCP_PLATFORM_INTERNAL_JWT_ALLOW_TEST_KEYS=true` and `AIWCORPO_MCP_PLATFORM_POLICY_ALLOW_TEST_KEYS=true` (in
addition to both consents from beta-4). `PolicyEngine.create(policy, ssl, meters, devstackProfile)` has an overload with the
profile flag (without the flag: `false`); `InternalJwtVerifier.Settings` has a sixth component `devstackProfile` (the
five-argument constructor stays, with `false`).

### 4. Manifest: `environment` and registration (CC-138, CC-133, CC-140)

- A server with the `devstack` profile publishes a manifest with `"environment": "dev"`. A gateway in production mode
  rejects it: `422 manifest_rejected`, reason `environment_mismatch`. In the other profiles the field is absent (it means
  production); it is not part of `definitionHash`.
- You register with the gateway the **artifact of the build step** of the reviewed commit
  (`aiwcorpo.mcp.platform.manifest.output-file`, for example `target/mcp-manifest.json`), never the response of a running
  server: outside `devstack` the gateway does not fetch the manifest from the server. Only `READ_ONLY` tools are approved at
  deployment; new and changed `UPDATE` and `DESTRUCTIVE` tools wait for the administrator's `approve`, and lowering `risk`
  is always `422` (`risk_lowered`).
- `ArgumentLimits(contractPattern = "OpenSearchIndexPrefix")` works without a workaround: the pattern in the contracts is a
  single anchored form, and the starter rewrites nothing (`SchemaLimitedToolCallbacks.oneAnchoredPattern` was removed, it
  was package-private).

### 5. Resources: refusal, order, engine error (CC-146, CC-141)

- `not_found_or_denied` has one `message`: `The resource does not exist or you do not have access to it.` The starter sets it
  regardless of the text from the server's `ToolErrorMapping`, so tests that checked their own text (`"The issue does not
  exist or is not visible."`) compare with the constant `ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE`. Also change the server
  mappings that returned their own text to this constant (supplying a text is no longer needed).
- Order: the grant first. A user without a grant for a resource gets `not_found_or_denied` **before** the resolver is called
  and an entity is built from backend data; an `internal_error` caused by a missing classification no longer reveals the
  existence of a resource to a person without access (described in the README, "One refusal for resources and the order of
  evaluation"). A server test that expected `internal_error` for a resource without a classification must call it as a user
  with a grant. A Service Desk queue outside the scope of the link in an argument is `not_found_or_denied`, not
  `access_denied` (change the test and the mapping in the adapter).
- A missing policy bundle after a restart (or a policy engine error when evaluating a tool) is `upstream_unavailable` with
  the text `Policies are not available. Try again later.`, and in the audit `policy_decision` with the reason
  `policy_unavailable` or `policy_error` (before, an engine error ended as `access_denied`).

### 6. Queries: `StructuredQuery` from normative lists, index names (CC-160, CC-147)

This is the only change in this release that breaks the Java API. Contract 4.0.0 closed the lists of fields and operators;
the starter accepts only a subset:

| Before (beta-5) | Now (beta-6) |
| --- | --- |
| `scope.jql(Set.of("status", "labels", "created", "cf[10000]"))` | condition fields only from `text`, `status`, `assignee`, `summary` (JQL) or `text`, `title`, `label` (CQL); another field: `IllegalArgumentException` at `scope.jql(...)` |
| `.orderBy("created", ...)` from the same set of fields | `ORDER BY` with `created`, `updated`, `priority`, `key` (JQL) or `created`, `lastmodified`, `title` (CQL); to narrow: `scope.jql(fields, orderFields)`; one `ORDER BY` |
| `Operator.IN`, `NOT_IN`, `NOT_CONTAINS`, `GREATER`, `GREATER_OR_EQUAL`, `LESS`, `LESS_OR_EQUAL`, `where(field, op, String...)`/`List` | only `EQUALS`, `CONTAINS`, `NOT_EQUALS`, one value: `where(field, op, String)` |
| `project in (...) AND status = "x"` (scope, then filters) | `(status = "x") AND project in (...)`: conditions in one parenthesis, then the scope |
| `CONTAINS` escapes `* ? ~ ( ) ...` | the value is only quoted; a control character (also tab and newline) is rejected, not escaped |
| list of values (`labels in (...)`) | not in the contract: replace a filter by a list with several calls, or with a fixed named filter on the server side if the contract allows it (a new field requires changing it) |

`QueryScope.quote` rejects control characters (`IllegalArgumentException`). Server tests that compared the exact query text
change the order (conditions, scope, `ORDER BY`).

**A scope that excludes everything (CC-147).** `scope.nothingToQuery()` works for every grantable type (OpenSearch: no index
is both granted and in the link; the others: no grants, for queries limited by grants). Search and list then return an
**empty result without calling the backend system**; fetching by id calls `scope.requireQueryable()` and ends in
`not_found_or_denied`.

**OpenSearch: names, not patterns (CC-160).** The OpenSearch server does not send the pattern `p-*` to the key:

```java
QueryScope scope = policy.queryScope("OpenSearch::Index");
if (scope.nothingToQuery()) {
    return emptyResult();
}
String resolved = openSearch.get("/_resolve/index/" + scope.opensearchIndexExpression());   // the pattern only here
Optional<String> target = scope.opensearchSearchTarget(resolved);                            // only "indices", no aliases or data streams, within the scope
if (target.isEmpty()) {
    return emptyResult();                                                                    // an empty target would search all indices
}
// search, _count and aggregations: target.get() in the path + QueryScope.OPENSEARCH_SEARCH_PARAMETERS
// an index from an argument: String name = scope.resolveIndexArgument(argument, resolved);   // invalid_argument | not_found_or_denied
```

`QueryScope.requireNoAliases` is deprecated: a query to a list of names does not cover aliases, so `aggregate_logs` can
come back (the excluding rule in `mcp-policies` goes away only after the OpenSearch server deploys this). Between resolving
and the query there is a short window in which an administrator could replace an index with an alias of the same name (a
known risk, described in the contract).

### 7. Audit and metrics (CC-143, CC-152)

The counter of surplus `authentication` events is called `mcp_audit_authentication_suppressed_total` and has the tags
`component` (for the starter: `server`) and `reason`; the previous name `mcp_server_audit_authentication_suppressed_total`
goes away (change alerts and dashboards). A request rejected with `400` or `413` before the token is verified is not an
`authentication` event.

### 8. Tests

`TestGateway.start(...)` publishes bundles from the 4.0.0 vectors (the new schema).
`PolicyRepository.publishGenerated(revision, serverId, extraPolicies)` lets you add your own rule to the generated bundle
(for example one that depends on `context.credentialType`). Bundles built on the 3.1.x schema in a server test give
`schema_mismatch`: rebuild them on 4.0.0.

## From beta-4 to beta-5

For servers only a version bump: `<version>3.0.0-beta-5</version>` in the BOM import (contracts 3.1.0). The changes concern
the `devstack` profile and the startup order: the consent `MCP_DEVSTACK_ACKNOWLEDGED=true` must be an **environment variable
of the process** (a property of that name from `application.yml`, the command line, `SPRING_APPLICATION_JSON` or
`DynamicPropertySource` is not enough; the second consent, `aiwcorpo.mcp.platform.devstack.acknowledged`, can come from any
configuration source), and the configuration guard runs before the policy bundle and the JWT keys are fetched, also with
`spring.main.lazy-initialization=true`. A development stack that set the consent in any way other than an environment
variable sets it in the container's `environment:`.

## From beta-3 to beta-4

One new capability and nothing mandatory for a server that works on beta-3. Version: `<version>3.0.0-beta-4</version>` in
the BOM import.

**The `devstack` profile for a development stack in containers.** Up to beta-3 the `test-*` signing keys (the gateway's JWT,
policy bundles) worked only in the `dev` profile, and `dev` is forbidden in a container, so the dev stack had
to name the bundle key differently (`policies-main-dev`). That bypassed the safeguard by name: a copy of such a
configuration passed in production. Now the stack starts servers with the `devstack` profile
(`SPRING_PROFILES_ACTIVE=devstack`, the only active profile) and two consents:

```yaml
# development stack configuration (compose), not the server image
environment:
  SPRING_PROFILES_ACTIVE: devstack
  MCP_DEVSTACK_ACKNOWLEDGED: "true"
  AIWCORPO_MCP_PLATFORM_DEVSTACK_ACKNOWLEDGED: "true"            # = aiwcorpo.mcp.platform.devstack.acknowledged
  AIWCORPO_MCP_PLATFORM_POLICY_ALLOW_TEST_KEYS: "true"
  AIWCORPO_MCP_PLATFORM_POLICY_MAIN_KEY_ID: test-policies-main-dev   # the key name is back to test-*
  AIWCORPO_MCP_PLATFORM_INTERNAL_JWT_ALLOW_TEST_KEYS: "true"       # if the gateway signs with test keys
```

The variable `MCP_DEVSTACK_ACKNOWLEDGED` is read only from the process environment (since beta-5; a property of that name,
also from `application.yml` or `DynamicPropertySource`, does not count), and the property
`aiwcorpo.mcp.platform.devstack.acknowledged` from any configuration source (relaxed binding also accepts it from the
variable `AIWCORPO_MCP_PLATFORM_DEVSTACK_ACKNOWLEDGED`, so both can sit in one `environment:` block of the stack). This
protects against carrying the consent in a configuration file packed into a jar or an image, not against copying the whole
`environment:` block; an image with an `ENV` holding both consents and the `devstack` profile still has it, and apart from
the `test-*` keys the production requirements remain (mTLS, https).

**A server test under `devstack` in a Spring context.** A process variable cannot be supplied through
`@DynamicPropertySource` or `@TestPropertySource` (such a source is not the process environment), so a context test under
the `devstack` profile gets a refusal to start. Two ways out: set the variable in the Surefire/Failsafe configuration
(`<environmentVariables><MCP_DEVSTACK_ACKNOWLEDGED>true</MCP_DEVSTACK_ACKNOWLEDGED></environmentVariables>`) in a separate
execution, or test the configuration without a context:
`new PlatformConfigurationGuard(props, environment, name -> "true").afterPropertiesSet()` (the third argument is the source
of process variables). Server tests with `TestGateway` stay in the `dev` profile.

**The version in the template.** Servers generated from `mcp-server-template` keep the starter version in the property
`mcp-platform.version` in the `<properties>` of the pom, and import the BOM through `${mcp-platform.version}`; a bump is a
change of that one property (`<mcp-platform.version>3.0.0-beta-6</mcp-platform.version>`). Servers without that property
bump the `<version>` in the direct `mcp-platform-bom` import in `dependencyManagement`. The contracts come through the BOM in
both variants.

`devstack` lifts only the ban on `test-*` keys and the published test keys. The rest is as in production and still
required: TLS and mTLS (`server.ssl.client-auth=need`), Redis for `jti`, https for the JWKS and for the bundle repository,
`policy.state-dir`, no `dev-pat`. Without both consents, or with a second profile, the start is refused with a list of what
is missing. Every start logs `WARN event=devstack_profile`, and the metric `mcp_server_devstack_active` has the value 1; the
C6 manifest has no field for the environment (a contract change proposal), so the log and the metric are the marker. Outside
`dev` and `devstack` the `test-*` keys are rejected as before: a stack key will not pass in production, because production
has neither the `devstack` profile nor the consents, and a production key must not be named `test-*`. Rejecting stack keys by
SPKI fingerprint (a list from a configuration published by the policies repository) is not in beta-4.

## From beta-2 to beta-3

Fixes after the beta-2 review (0 CRITICAL/HIGH) and after an automated review. A server that has moved to beta-2 only has to
mark the tools that return text (point 1); the rest are new startup errors and small API changes. Version:
`<version>3.0.0-beta-3</version>` in the BOM import (contracts 3.1.0).

1. **A result that is text requires `@ResultWithoutResources`.** `String`, `CharSequence` and `byte[]` at the top level of a
   result (also inside `Optional`, `List`) could be ready-made JSON with resources that the platform cannot see, so a tool
   that returns plain text declares that it has no resources in it. A `String` as a record component is still an ordinary
   field. This concerns every tool with `String get...()` and `List<String> list...()`:

   ```java
   import pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources;

   @Tool(name = "delete_issue", description = "Deletes an issue.")
   @ToolRisk(RiskLevel.DESTRUCTIVE)
   @ToolTitle("Delete issue")
   @ResultWithoutResources            // "deleted ABC-1": a message, not a resource
   public String deleteIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                             @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
   ```

   A tool that returned `objectMapper.writeValueAsString(hits)` or the backend system's response as text returns records with
   `@ResourceRef` (the annotation `@ResultWithoutResources` on such a result would be false and would filter nothing). A
   `Map` key that is not a scalar (`Map<IssueHit, Long>`) counts as an untracked part.
2. **A hand-written `ToolCallback` and a `MethodToolCallback` without a readable method** stop the start if they do not
   declare the result: `RiskClassifiedTool#resultWithoutResources()` returns `true` (the callback returns no resources or
   filters them itself). Tests with their own callbacks add that method.
3. **Nested arguments.** The search for `@ResourceRef` below a parameter now covers `Optional`, `Map` values, fields of
   classes that are not records together with their superclasses, `sealed` subtypes (`permits`), and ends in a startup error
   for nesting deeper than 6 levels (before, it passed silently). Code with `@ResourceRef` on a component of an argument
   record was already rejected from beta-2; now also when the record sits in `Optional<...>`, `Map<String, ...>`, in a class
   or in its superclass.
4. **Element limit per call.** `tools.max-filtered-elements` (200) counts the elements of all resource lists in the whole
   result, not each list separately; exceeding it is an `internal_error`.
5. **CQL.** `scope.cql(fields, true)` and `scope.cqlSpaceClause(true)` add the scopes from groups for `Confluence::Space`
   (they throw when there are no groups; for `Confluence::Page` they do not exist, because page scopes are subtrees). Values
   in CQL are quoted and escaped as in JQL (tests with hostile values).
6. **OpenSearch.** `QueryScope.requireNoAliases(responseJson)` reads the response of `GET _resolve/index/<expression>` and
   refuses (`access_denied`) for an alias or a data stream; call it before an aggregating query, `_count` or reading
   `hits.total`:

   ```java
   String expression = scope.opensearchIndexExpression();                  // throws when there is nothing to ask
   QueryScope.requireNoAliases(opensearch.resolveIndex(expression));        // GET _resolve/index/<expression>
   var response = opensearch.search(expression, QueryScope.OPENSEARCH_SEARCH_PARAMETERS, body);
   ```

   The index resolver running as a service account returns `tags: []` for an index with no configured tags, not a missing
   attribute (a missing one is an `internal_error`, that is, a different response than for an index that does not exist).
7. **The example server (`mcp-example`).** `TestGateway.start("mcp-example")` now generates a bundle with the rules from the
   contract examples also for resources: read, write within the scope of groups `mcp-jira-proj-ABC` and so on, refusal for
   `restricted`, link narrowing (groups `mcp-example-read|write|destructive`). The template test (read allowed, `restricted`
   rejected, the link narrows) runs on `mcp-example` without pretending to be `mcp-jira` (model: `ExampleServerResourcesTest`).
8. **Contract vectors on `ResourcePolicy`.** `ContractVectorsOnResourcePolicyTest` in `mcp-platform-test` runs every file from
   the contracts' `resource-checks/` and `result-filter/` on `ResourcePolicy` with the example policies; a new vector in the
   contracts runs without code changes. A server can do the same for its own resolvers.
9. **Fetching bundles.** A fetch triggered by the `prv` hint does not wait for the channel lock (`tryLock`), so two engine
   threads do not stand on one `main`.

Known limits (unchanged in beta-3): the words `AND`, `OR`, `NOT` in a `CONTAINS` value act as text-search operators within one
field; `IS EMPTY`, `membersOf(...)` and date functions cannot be expressed in `StructuredQuery`; the builder's output was not
checked against a real Jira (`POST /rest/api/2/jql/parse`) and Confluence (CQL with `\n`, `\\`, `\"`) in the dev environment
(a task for the dev stack owners).

## From beta-1 to beta-2

Changes after the security review and the smoke test of beta-1. For a server written according to the section "From alpha-14
to beta-1" this means: rewriting search on `StructuredQuery`, several new startup errors (a result not tracked by the filter,
`@ResourceRef` in an argument record) and fewer silent behaviours. Versions: `<version>3.0.0-beta-2</version>` in the BOM
import (contracts 3.1.0 unchanged).

1. **JQL and CQL queries are built by the server.** `QueryScope.restrictJql` was removed (beta-1 accepted raw JQL from the
   model and could be bypassed with `\"` outside quotes: Jira reads an escape there, the starter does not). Instead of
   `restrictJql(scope.jqlProjectClause(), userJql)`:

   ```java
   // the model supplies a filter, not query text
   public record IssueFilter(@Nullable @ArgumentLimits(maxLength = 40) String status,
                             @Nullable @ArgumentLimits(maxLength = 200) String text) { }

   String jql = scope.jql(Set.of("status", "text", "created"))                     // import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator / Direction
           .where("status", Operator.EQUALS, filter.status())
           .where("text", Operator.CONTAINS, filter.text())
           .orderBy("created", Direction.DESC).build();
   ```

   Fields from the author's list, operators from a closed set, only `AND`, values always in quotes and escaped. A tool that
   took a `String jql` from the model takes a structure (a record with fields) or parameters; if the product needs a query
   language from the model, it does not go through the starter. `opensearchIndexPatterns()` throws instead of returning an
   empty list when there is nothing to ask (an empty list = all indices); use `nothingToQuery()` and
   `opensearchIndexExpression()` and `QueryScope.OPENSEARCH_SEARCH_PARAMETERS`; aliases: README.
2. **The shape of a result must be tracked or declared.** The start stops a result with `Map`, a class that is not a record,
   `Object`, `JsonNode` and a record with a Jackson annotation on a component, if the record (also through `Page<T>`,
   `Optional`, a list) contains `@ResourceRef`; for a result without resources that the platform cannot inspect, add
   `@ResultWithoutResources` (`pl.aiwcorpo.mcp.platform.resource`):

   ```java
   @Tool(name = "get_stats", description = "Returns counters.")
   @ToolRisk(RiskLevel.READ_ONLY)
   @ToolTitle("Get stats")
   @ResultWithoutResources            // Map<String, Long>: there are no resources in it
   public Map<String, Long> getStats() { ... }
   ```

   Generic records (`Page<IssueHit>`) and `Optional<List<IssueHit>>` work without code changes. A list longer than
   `tools.max-filtered-elements` (200) in a result ends the call with `internal_error`: return pages.
3. **`@ResourceRef` outside a method parameter.** On a component of a record that is an argument
   (`update_issue(IssueUpdate req)`) the start is stopped (in beta-1 the annotation was silently ignored). Pass the
   identifier as a separate parameter: `update_issue(@ResourceRef(type = "Jira::Issue") String issueKey, IssueChanges
   changes)`. Two arguments of the same type must have the same resolver.
4. **Resolver error codes.** An exception from `fetch` or `describe` (a backend failure, a `NullPointerException` from
   `Map.of(..., null)`) is `upstream_unavailable` / `internal_error`, not `invalid_argument`. Only an identifier that does not
   match the type's pattern gets `invalid_argument` (for example `ABC-SLOW` is not a Jira issue key; in tests use `ABC-5`,
   `ABC-999`). A resource without the required classification, with an id that does not match the type or with a different
   type than in `@ResourceRef` is an `internal_error` (contract), not `not_found_or_denied`; a result element in such a state
   is removed. A resolver failure for a result element removes the element instead of ending the call. A result with many
   elements is resolved by one call to `ResourceResolver#fetchAll(ids)` (by default a loop over `fetch`; override it with a
   bulk call, for example `key in (...)`).
5. **A unit test of a tool with `@ResourceRef`.** A method that reads `ResourceContext.required().fetched(...)` throws outside
   a platform call, with instructions. In the test, open the context:

   ```java
   import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
   import pl.aiwcorpo.mcp.platform.test.TestResourceContext;

   try (ResourceContext context = TestResourceContext.open("get_issue", Map.of("issueKey", "ABC-1"))
           .withFetched("Jira::Issue", "ABC-1", new JiraIssue("ABC-1", "ABC"), null)) {
       assertThat(tools.getIssue("ABC-1").project()).isEqualTo("ABC");
   }
   ```

   The policy is checked through an MCP client from `TestGateway`, not by calling the method directly.
6. **The example server (`mcp-example` in the template).** `TestGateway.start("mcp-example")` generates, and signs with a test
   key, a bundle with `call` policies for the groups `mcp-example-read`, `mcp-example-write`, `mcp-example-destructive` and
   with the rules from the contract examples on resources (read, write within the scope of the group `mcp-jira-proj-ABC` and
   so on, refusal for `restricted`, link narrowing) for the same groups, so the read, refusal and link tests work as for the
   five systems (no need to pretend to be `mcp-jira`). Outside the list of five contract systems: in `dev` the start warns
   (`SystemId`), and the manifest does not pass `server-manifest.schema.json` (`serverId` is an enum), which in `dev` is the
   warning `event=manifest_rejected_by_rules`; outside `dev` such a server will not start. So plan the `ManifestTest` in the
   template only for servers from the contract list.
7. **Fetching bundles.** `policy.read-timeout` is the time of one whole request (with the body), not only up to the headers;
   a `prv` newer than the loaded revision hands the fetch to the engine thread and does not block the call. A change only
   for people who read the code.
8. **Interceptors.** A `ToolCallInterceptor` that changes the value of a `@ResourceRef` argument after authorization ends the
   call with `internal_error`; change other arguments, not resource identifiers.
9. **The name tripwire** also knows `del`, `rm`, `revoke`, `unlink`, `kill`, `terminate`, `reset`, `clear`: a tool `clear_cache`
   marked `UPDATE` will not start (mark it `DESTRUCTIVE` or rename it).
10. **Additions to the section "From alpha-14 to beta-1"** (from the smoke test):
    - imports for the examples: `pl.aiwcorpo.mcp.platform.resource.{ResourceRef, ResourceResolver, ResourceContext,
      ResourcePolicy, QueryScope, StructuredQuery}`, `pl.aiwcorpo.mcp.platform.input.ArgumentLimits`,
      `pl.aiwcorpo.mcp.platform.guard.{RiskLevel, ToolRisk, ToolTitle}`, `pl.aiwcorpo.mcp.cedar.entities.ResolvedResource` (from
      `mcp-cedar-entities`, not from the starter), `pl.aiwcorpo.mcp.platform.test.TestGateway`,
      `java.util.{List, Map, Optional, Set}`, `java.util.Objects`;
    - the `dev` configuration is complete only with `server-id`, `internal-jwt.jwks-uri` and `credential.connect-url`:

      ```yaml
      aiwcorpo.mcp.platform:
        server-id: mcp-jira
        internal-jwt.jwks-uri: http://127.0.0.1:18700/.well-known/jwks.json
        credential.connect-url: http://localhost:18090/connections?system={system}
        policy:
          allow-test-keys: true
          main:
            url: http://127.0.0.1:18747/policies/main/latest.json
            key-id: test-policies-main-dev
            public-key-file: /abs/path/mcp-policies/keys/dev/test-policies-main-dev.public.pem
        manifest:
          version: 0.1.0
          internal-url: https://mcp-jira.internal:8443/mcp
          accepted-credential-types: [user-secret]
      ```

    - `public-key-file` is read relative to the working directory of the process (not of the module): give an absolute path or
      one relative to where the process starts (`mvn spring-boot:run` from the module directory, `java -jar` from anywhere);
    - `GATEWAY.serverProperties()` sets `manifest.version=1.0.0` and `internal-url` for tests, so the version from
      `application.yml` is not visible in tests; compare the manifest test with `1.0.0`, not with the value from the file.

## From alpha-14 to beta-1

A list for the template and the five servers (jira, confluence, gitlab, servicedesk, opensearch). Beta-1 moves to
`mcp-contracts` **3.1.0** (through the BOM) and adds what was missing for resource protection: tool titles and a closed
schema, the new text hygiene, `@ResourceRef` with a Cedar policy, the result filter, `QueryScope`, and the C6 manifest from
`GET /mcp-manifest`. After changes 1-4 a server builds and starts without the new logic; points 5-9 add the protection of
resources.

### 1. Versions

`pom.xml` of the server and of the template: `<version>3.0.0-beta-1</version>` in the `mcp-platform-bom` import (the BOM also
manages `mcp-contracts` 3.1.0, `mcp-cedar-entities`, `mcp-policy-bundle` and `mcp-manifest`; do not add them by hand). The
server's README: correct the starter and contract versions written by hand to `3.0.0-beta-1` and `3.1.0`. `new-server.sh`
takes the BOM version from the template's `pom.xml`, so move the template before generating new servers.

Versions released earlier in `~/.m2` are immutable: do not install `3.0.0-alpha-14` again. The beta-1 starter and BOM must be
in `~/.m2` from `./mvnw install` on the tag `v3.0.0-beta-1` (or from the registry) before a server builds.

### 2. `@ToolTitle` on every tool (CC-115)

The contract requires a `title` in the tool definition, and the hash and `tools/list` contain it. A tool without a title
stops the start.

```java
@Tool(name = "get_issue", description = "Returns an issue by key.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Get issue")                      // pl.aiwcorpo.mcp.platform.guard.ToolTitle
public IssueView getIssue(...) { ... }
```

The title: not empty, up to 100 characters, no spaces at the edges, no line endings and no hidden characters. Changing the
title changes the `definitionHash`, which for the gateway is a change of the tool definition. A hand-written `ToolCallback`
with `RiskClassifiedTool` returns the title from the method `title()`. Tests with test tools get the same obligation.

### 3. Input schema: closed and bounded (CC-078, CC-123)

The starter's rules are now the manifest's rules (`input-schema-closed`, `-bounded`, `-no-combinators`), so what the starter
lets through also passes the gateway. What changes for hand-written schemas (those generated by `SchemaLimitedToolCallbacks`
already looked like this):

- a map with `additionalProperties: {schema}` and `maxProperties` is rejected (it used to be allowed): use a record with
  named fields or a list of `{name, value}` records;
- `$ref`, union types (`["string","null"]`), `items: false`, `exclusiveMinimum`/`exclusiveMaximum` instead of
  `minimum`/`maximum`, and a pattern without `^...$` (or with a top-level alternation, for example `^a$|^b$`) are rejected;
- a parameter named `confirmation_code` stops the start (the name belongs to the gateway, C11);
- `@ArgumentLimits(contractPattern = "OpenSearchIndexPrefix")` still works: the contracts' definition has a top-level
  alternation, and the starter publishes it as `^(?:...|...)$`.

### 4. Text hygiene (CC-121, CC-122)

One definition from the contracts (`test-vectors/text-hygiene/`, 79 vectors):

- **Arguments.** Forbidden: C0 controls except TAB/LF/CR, DEL and C1 (also U+0085), U+2028/U+2029, unpaired surrogates,
  noncharacters, every `\p{Cf}` character except U+200C..U+200F, U+034F, U+115F, U+1160, U+3164, U+FFA0, U+2800 and variation
  selectors (U+FE00..U+FE0F, U+E0100..U+E01EF), except for one U+FE0F right after an emoji character. In values with a
  `pattern` (identifiers) TAB, LF and CR are additionally forbidden; ZWJ, ZWNJ, LRM and RLM are no longer forbidden where the
  pattern lets them through. No NFKC.
- **Arguments that reach the server's mention filter** (a comment, a description, any text passed to a system that turns
  `@name` into a notification): mark them, and full-width ASCII (U+FF01..U+FF5E, for example "@all" written with the
  characters U+FF20 U+FF41 U+FF4C U+FF4C) will be rejected as `invalid_argument`:

  ```java
  public IssueComment addComment(
          @ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String issueKey,
          @ArgumentLimits(maxLength = 32000, mentionFiltered = true) String body) { ... }
  // a record: a component with @ArgumentLimits(maxLength = 200, mentionFiltered = true) String body; on a list it applies to the elements
  ```

  Where the mention filter is your own code, call `TextHygiene.firstProblem(text, false, true) != null` there too -> reject.
  The marker on a number or on a value from an `enum` stops the start.
- **Text from the backend system** (`UntrustedText`, `TextHygiene.normalize`): every maximal run of forbidden characters
  becomes **one space**, nothing is trimmed, and CR, CRLF and LF stay. A change from alpha-14: U+2028, U+2029 and U+0085
  used to be a newline, now they are a space; the space is no longer skipped at the edges and next to whitespace
  (`"\u200bhello\ufeff"` gives `" hello "`). Server tests that compare the exact text after normalization must be corrected.
  The order `normalize` -> `cut` -> filter is unchanged.

### 5. Configuration: policy bundle and manifest

New, mandatory properties (without them the start is refused with a list of what is missing). Production:

```yaml
aiwcorpo.mcp.platform:
  policy:
    main:
      url: https://policies.internal/policies/main/latest.json
      key-id: policies-main-2026
      public-key-file: /etc/mcp/policies-main.pub.pem         # Ed25519, SPKI PEM (or public-key: <PEM>)
    overrides:                                                # optional, an emergency bundle with a separate key
      url: https://policies.internal/policies/overrides/latest.json
      key-id: policies-overrides-2026
      public-key-file: /etc/mcp/policies-overrides.pub.pem
    state-dir: /var/lib/mcp/policy-state                      # a separate directory per instance
  manifest:
    version: 1.4.0
    internal-url: https://mcp-jira.internal:8443/mcp
    accepted-credential-types: [user-oauth, user-secret]      # the credential types the server accepts
```

`dev` (the dev stack, the bundle repository on `:18747`; the key from `mcp-policies/keys/dev/`):

```yaml
aiwcorpo.mcp.platform:
  policy:
    allow-test-keys: true                                     # dev only; outside dev the start is refused
    main:
      url: http://127.0.0.1:18747/policies/main/latest.json
      key-id: test-policies-main-dev
      public-key-file: ../mcp-policies/keys/dev/test-policies-main-dev.public.pem
  manifest:
    version: 0.1.0
    internal-url: https://mcp-jira.internal:8443/mcp
    accepted-credential-types: [user-secret]
```

`state-dir` holds the revision and the archive hash of each channel: without it a restart would accept an older signed
bundle again (CC-066), so it is required outside `dev`. A server without a bundle within `policy.startup-timeout` (30 s) does
not start. Details and behaviour on errors: README, "Resources and policies". Server tests need no configuration changes:
`GATEWAY.serverProperties()` adds `policy.*` and `manifest.*` (a bundle with the contracts' example policies, test keys).

### 6. `@ResourceRef` and a resolver instead of your own check

In alpha-14 a tool took a `String issueKey` and asked the system itself. Now the resource is declared with an annotation,
and before the tool runs the starter resolves it with a resolver and evaluates the policy. The resolver (a bean) calls the
system with the caller's credential and returns the canonical id, the parent and the classification **from the system**:

```java
@Component
class JiraIssueResolver implements ResourceResolver<JiraIssue> {

    private final JiraClient jira;

    JiraIssueResolver(JiraClient jira) {
        this.jira = jira;
    }

    @Override
    public String resourceType() {
        return "Jira::Issue";
    }

    @Override
    public Optional<JiraIssue> fetch(String key) {
        return jira.issue(key);          // empty for 404 and 403 (it does not exist or is not allowed); an exception for a system failure
    }

    @Override
    public ResolvedResource describe(JiraIssue issue) {
        // the id and the project from Jira's response: an issue moved to HR has the HR project here, not the one from the argument
        return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key()))
                .withParent(ResolvedResource.of("Jira::Project", issue.projectKey(),
                        Map.of("key", issue.projectKey(), "category", Objects.requireNonNullElse(issue.projectCategory(), ""))));
    }
}

@Tool(name = "get_issue", description = "Returns an issue by key.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Get issue")
public IssueView getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                          @ResourceRef(type = "Jira::Issue") String issueKey) {
    // the result of fetch from this call, without a second request to Jira
    JiraIssue issue = ResourceContext.current().orElseThrow().fetched("Jira::Issue", issueKey, JiraIssue.class).orElseThrow();
    return IssueView.of(issue);
}
```

Mandatory attributes (a resource without them is rejected, not admitted): `Jira::Project.category` (`""` when absent),
`Confluence::Space.labels`, `OpenSearch::Index.tags`; optional `classification` for `GitLab::Group`, `GitLab::Project`,
`ServiceDesk::Queue` (GitLab: the sum of the topics of the ancestor groups). For Confluence a page supplies `ancestorIds`.
All `@ResourceRef` values are evaluated (the list, the source and the target of a transfer); `resourceRefs` without a value
(an optional argument that was omitted) ends in a refusal, so an argument that may be absent is not a `@ResourceRef` (for
search: `QueryScope`, point 7).

**GitLab: topics of ancestor groups (CC-129, contracts 3.1.0).** Topics in GitLab are not inherited, and `restricted` on a
group is meant to protect the whole subtree, so the Cedar schema has a required `inheritedClassification` on `GitLab::Group`
and `GitLab::Project`, which the builder computes from the attribute `ancestorClassifications` supplied by the server. The
starter has no separate API for this: it is an attribute in `ResolvedResource` (a map `<ancestor group path> -> topics`,
exactly for the groups above the resource, for a project including its parent, an explicit `{}` for a top-level group), and
the project's **parent** (a group) gets its own map (the group's ancestors, without the group itself). A missing map, a
missing or surplus key (including the group itself) or topics that are not a list of strings give an `internal_error` (the
resource is not built, the call is rejected). The GitLab server's resolver fetches the topics of every group on the path
(cached for the duration of the call):

```java
// project hr/payroll/app; GitLab: topics of group hr = [Restricted], of group hr/payroll = []
Map<String, List<String>> ancestors = Map.of("hr", List.of("Restricted"), "hr/payroll", List.of());
return ResolvedResource.of("GitLab::Project", "hr/payroll/app",
                Map.of("path", "hr/payroll/app", "classification", ownTopics, "ancestorClassifications", ancestors))
        .withParent(ResolvedResource.of("GitLab::Group", "hr/payroll",
                Map.of("path", "hr/payroll", "classification", groupTopics,
                        "ancestorClassifications", Map.of("hr", List.of("Restricted")))));   // without the group "hr/payroll" itself
```

The values are normalized by the builder (trim, ASCII lower case), so `Restricted` is enough. A model in the tests:
`GitLabProjectResolver` in `mcp-platform-test` (`JiraServer`, `ResourcePolicyTest` tests: a project three levels under a
group with `restricted` is rejected, and a project without the map too). Bundles built on the 3.0.x schema are rejected
(`schema_mismatch`): a bundle on schema 3.1.0 from the policies repository is needed.

`@ToolRisk(value = RiskLevel.UPDATE, action = ToolAction.ADMIN)` selects the Cedar action `admin`; by default `read`,
`update` and `delete` follow from the risk, and a contradiction (`READ_ONLY` with `delete`) stops the start.

What changes in the responses: a nonexistent resource, an inaccessible one and one moved to `restricted` give the same
`not_found_or_denied` with the same text (a resource without the required classification: `internal_error`, since beta-2 as
the contract says); a missing tool group is `access_denied`; a backend failure in the resolver is `upstream_unavailable` (not
a refusal). An adapter that translated a 404 into its own error should return `Optional.empty()` in the resolver.

### 7. Results and search: `@ResourceRef` in records, `QueryScope`

A search tool returns records whose identifier component has `@ResourceRef`; list elements that are not allowed for the
caller are removed after the tool runs (an issue from `DEF` linked in a search of `ABC` disappears when the virtual server's
link narrows to `ABC`). The server builds the query from a structure (beta-2; in beta-1 there was `restrictJql` on raw JQL
from the model, which could be bypassed, see "From beta-1 to beta-2"):

```java
public record IssueHit(@ResourceRef(type = "Jira::Issue") String key, String summary) { }
public record SearchResult(List<IssueHit> hits, int total) { }

@Tool(name = "search_issues", description = "Searches issues.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Search issues")
public SearchResult searchIssues(IssueFilter filter) {
    QueryScope scope = policy.queryScope("Jira::Project");                    // ResourcePolicy injected in the constructor
    String jql = scope.jql(Set.of("status", "labels", "text", "created"))
            .where("status", Operator.EQUALS, filter.status())
            .where("text", Operator.CONTAINS, filter.text())
            .orderBy("created", Direction.DESC).build();                      // project in ("ABC") AND status = "Open" ...
    return toResult(jira.search(jql));
}
```

For Confluence `scope.cql(...)`, for OpenSearch `scope.opensearchIndexExpression()` (see README, "`QueryScope` and
`StructuredQuery`"). `total` and other counters from the system are not corrected after filtering: do not show them if they
are meant to agree with the visible list. A result that is not a record with a marked component is filtered through
`policy.retainAllowedById("Jira::Issue", items, Item::key)` or `policy.retainAllowed(items, item -> resolvedResource)`.

### 8. Server tests

```java
static final TestGateway GATEWAY = TestGateway.start("mcp-jira");          // unchanged

@DynamicPropertySource
static void gateway(DynamicPropertyRegistry registry) {
    GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));   // now also policy.* and manifest.*
}

McpSyncClient client = ...httpRequestCustomizer(GATEWAY.asGateway(
        user -> user.groups(List.of("mcp-jira-read")).virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC"))),
        "Bearer test-jira-pat")).build();
```

- Calling a tool now requires a group that matches the risk in the contracts' example policies (`mcp-jira-read`,
  `mcp-jira-write`, `mcp-jira-destructive`, scope `mcp-jira-proj-ABC`); the default token has `mcp-jira-read`. `DESTRUCTIVE`
  tests also set `mfaAgeSeconds(60)` (the rule `destructive-requires-fresh-mfa` is in the test bundles, not in the
  production policy bundle).
- A resource refusal test: an issue that the fake Jira returns as moved to a project with the category `Restricted` gives
  `not_found_or_denied` (model: `ResourcePolicyTest` in `mcp-platform-test`); a result test: a search with a link narrowed to
  one project returns only its issues.
- The bundle is changed in a test: `GATEWAY.main().publish("20261026130000-b2c3d4e")`, a wrong signature
  `publish(revision, "policies-....tar.gz.sig-by-overrides-key")`, `offline(true)`.
- **A server manifest test** (new, model `ManifestTest` in `mcp-platform-test`): fetch `/mcp-manifest` with a service token
  (`GATEWAY.service().mint().headers()`) and check that `ManifestRules.violations(manifest)` is empty, that the definitions
  equal the entries of `tools/list`, and the `risk` of `DESTRUCTIVE` tools. The starter does the same at startup (outside
  `dev` an error stops the server); the test guards that nobody turned it off.

### 9. Manifest, registration and `DESTRUCTIVE`

`GET /mcp-manifest` and an optional file (`manifest.output-file: target/mcp-manifest.json`) give the manifest for
registration in the gateway (`PUT /admin/v1/registry/servers/{serverId}`). Instead of a build-time generator (annotations
plus a Maven plugin) the manifest is created at startup from what the server really publishes, so `definitionHash` always
agrees with `tools/list`.

A tool is `DESTRUCTIVE` in the manifest only through `@ToolRisk(RiskLevel.DESTRUCTIVE)`. The server does not ask for
confirmation (the gateway does, with a TOTP code), but the starter guards consistency: the same `risk` in the manifest,
`destructiveHint: true` in `tools/list`, the action `delete` (or an explicit `admin`) in Cedar, and a name with
`delete`/`drop`/`purge`/... will not pass as `UPDATE`. A `delete_issue` tool for the demo looks like this:

```java
@Tool(name = "delete_issue", description = "Deletes an issue.")
@ToolRisk(RiskLevel.DESTRUCTIVE)
@ToolTitle("Delete issue")
public DeleteResult deleteIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                                @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
```

### 10. Other changes

- `ToolErrorCode`: removed `CONFIRMATION_DECLINED` (contract 3.0.0), added `CONFIRMATION_SUPERSEDED`,
  `CONFIRMATION_CANCELLED`, `CONFIRMATION_BLOCKED`. A server returns none of them; only the list that `ToolErrorMapping`
  accepts changes.
- Audit: a new event `policy_decision` (a policy refusal) next to `tool_call`; `resource.id` is omitted when it contains a
  character not allowed in a log field (contracts 3.0.2).
- The beans `policyEngine` and `resourcePolicy` are not extension points (like `internalJwtVerifier`): a custom bean with that
  name stops the start; so does excluding `PlatformPolicyAutoConfiguration`.
- Native dependency: `mcp-cedar-entities` pulls in `cedar-java` (libraries for macOS and for Linux with glibc). A server image
  must not be based on Alpine/musl (`eclipse-temurin:25-jre`, not `-alpine`).
- Build: `JAVA_HOME` set to Java 25, `./mvnw -B verify`.

## Moving from alpha-11 (or alpha-12) to 3.0.0-alpha-14

One list for a server and for the template; details and reasons are in the sections "Changes in 3.0.0-alpha-12", "Changes
in 3.0.0-alpha-13" and "Changes in 3.0.0-alpha-14" below. The order matters only for the last point.

1. **Versions.** In the server's `pom.xml`: `<mcp-platform.version>3.0.0-alpha-14</mcp-platform.version>` (the BOM also manages
   `mcp-contracts` 2.2.5). In the server's README the starter and contract versions are written by hand
   (`starter 3.0.0-alpha-11`, `contracts 2.1.1`): correct them to `3.0.0-alpha-14` and `2.2.5`. `new-server.sh` takes the BOM
   version from the template's `pom.xml`, so move the template before generating new servers.
2. **Replace the whole file** `mcp-app/src/test/java/pl/aiwcorpo/mcp/<system>/app/DevProfileLoopbackTest.java` (this is not an
   addition to the alpha-11 test: `TestGateway`, `SpringApplicationBuilder` and `assertThatThrownBy` disappear from the
   imports). The package and the system name stay yours (`jira`, `confluence`, `gitlab`, `servicedesk`, `opensearch`, in the
   template `example`):

   ```java
   package pl.aiwcorpo.mcp.jira.app;

   import org.junit.jupiter.api.Test;
   import org.springframework.beans.factory.annotation.Autowired;
   import org.springframework.context.ApplicationContext;
   import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

   /**
    * The dev profile (test keys, no mTLS) does not listen on an address other machines can reach: the rule is the
    * starter's, and the server's own context must have the guard that applies it.
    */
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

   `ServerTestSupport` is in the same package (the base class of server tests with `@SpringBootTest` and
   `@ActiveProfiles("dev")`), so no import is needed; the test uses its context and does not start the application a second
   time.
3. **Remove the platform exclusions**, if there are any; in alpha-13 they end the start in every profile, also in `dev`.
   Places to search:
   - `src/main/resources/application*.yml` and `.properties` (also `application-dev.yml`): the key
     `spring.autoconfigure.exclude`, written as a list or with indexes (`exclude[0]`);
   - environment variables: `SPRING_AUTOCONFIGURE_EXCLUDE` in `.env`, in `docker-compose*.yml`, in a systemd unit
     (`Environment=`, `EnvironmentFile=`), in the `Dockerfile` (`ENV`), in the CI configuration;
   - code: `@SpringBootApplication(exclude = ...)` or `excludeName`, `@EnableAutoConfiguration(exclude = ...)`,
     `@ImportAutoConfiguration(exclude = ...)`;
   - values to remove: everything from the package `pl.aiwcorpo.mcp.platform.` (for example
     `PlatformSecurityAutoConfiguration`, `PlatformAuthAutoConfiguration`, `PlatformObservabilityAutoConfiguration`,
     `PlatformMcpServerAutoConfiguration`). An example to remove:

     ```java
     @SpringBootApplication(exclude = PlatformSecurityAutoConfiguration.class)   // do not do this
     public class McpServerApplication { ... }
     ```

   A grep that covers the whole tree: `grep -rnE "autoconfigure.exclude|AUTOCONFIGURE_EXCLUDE|exclude *=|excludeName" .`
   (without `target/`). Slices (`@WebMvcTest`) without the platform still work.
4. **Lazy initialization and a custom filter.** A server with `spring.main.lazy-initialization=true` or with its own
   `CredentialHeaderFilter` changes nothing: since alpha-13 the guards are always created.
5. **Code that sets the address of the web server** (`WebServerFactoryCustomizer#setAddress`, an extra `Connector`) must in
   `dev` point to the loopback interface; otherwise the start ends in a refusal.
6. **Build** (`JAVA_HOME` set to Java 25, `./mvnw -B verify`).

## Changes in 3.0.0-alpha-12

Work was done on `3.0.0-alpha-12-SNAPSHOT`. Moving from alpha-11: bump `mcp-platform.version` (the BOM) to
`3.0.0-alpha-12`, which also raises `mcp-contracts` to 2.2.5; in every server and in the template replace the file
`DevProfileLoopbackTest` (the first point) and remove the platform exclusions, if there are any (the second). The rest
changes only where the points say so explicitly.

- **`DevProfileLoopbackTest` checks the server's real configuration.** The alpha-11 test calls the helper on the starter's
  default settings and touched nothing of the server; a server with `@SpringBootApplication(exclude =
  PlatformSecurityAutoConfiguration.class)` had green tests in `dev` despite `server.address=0.0.0.0`. The new file
  `mcp-app/src/test/java/.../app/DevProfileLoopbackTest.java`:

  ```java
  package pl.aiwcorpo.mcp.<system>.app;

  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.context.ApplicationContext;
  import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

  /**
   * The dev profile (test keys, no mTLS) does not listen on an address other machines can reach: the rule is the
   * starter's, and the server's own context must have the guard that applies it.
   */
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

  The class extends `ServerTestSupport` (the same context, no extra application start). The test does not depend on the
  host: it starts nothing with a network address.
- **A platform exclusion is refused also in `dev`.** `spring.autoconfigure.exclude` with a class `pl.aiwcorpo.mcp.platform.*`
  and `exclude` on `@SpringBootApplication` that removes one of the beans from `PlatformPresenceCheck.REQUIRED_BEANS` end the
  start in all profiles outside a test context. A server that had such an exclusion only for `dev` must remove it (the guard
  is what ties the `dev` relaxations to the loopback address). Slices (`@WebMvcTest` and so on) without the platform work as
  before.
- **The `authentication` event according to CC-120 (contracts 2.2.5).** The starter already emitted `missing_token`,
  `client_certificate_rejected`, `jwks_unavailable` and `replay_store_unavailable` with `errorCode: upstream_unavailable`;
  now this is checked with the 2.2.5 `audit-events/` vectors and the schema for every reason
  (`AuthenticationAuditVectorsTest`). New: a limit of `missing_token` events per process,
  `aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second` (default 20, minimum 1; a value below 1 stops the
  start), the surplus only in the counter `mcp_server_audit_authentication_suppressed_total{reason="missing_token"}`. Servers
  change nothing; a SIEM and alerts that count `missing_token` should add this counter.
- **Text truncation**: a long run of `.`, `-`, `_` or combining characters after a short name no longer leaves the name
  (`"x(@all" + 70 * "."` with a limit of 75 gives `x(@`, not `x(@all` with dots). A server test that compares the exact
  truncated text with such a tail must be corrected.
- **Recognizing the test framework jar**: the path must end in `spring-test-<version>.jar` or
  `spring-boot-test-<version>.jar` (with `!/` in a nested jar); a directory with such a name no longer counts. Nothing to
  change in servers.
- **Devcontainer**: unchanged; a human chose option 1 (STATUS), `dev` in a container still refuses.

## Changes in 3.0.0-alpha-14

Work was done on `3.0.0-alpha-14-SNAPSHOT`. Moving: the list "Moving from alpha-11" above (from alpha-13 a version bump is
enough).

- **Event limit: a separate budget per reason, only for an unparsable token.** `missing_token` and `malformed` (a foreign
  scheme, not three segments, a JWT header that is not an object) have separate budgets (20 per second each by default), so a
  flood of one does not use up the other. A token with `crit` or a `typ` other than `JWT` in the header, after resolving the
  `kid`, is no longer `malformed` and no longer limited: it is an attempt, not noise, and it is reported without a limit as
  `alg_not_allowed` (the closest existing C8 code; a code of its own is a contract change proposal noted in STATUS). An alert
  on `malformed` no longer covers these tokens, and `alg_not_allowed` covers them together with headers whose `alg` is not
  ES256. This fixes alpha-13, where the budget was shared by both reasons and by every invalid header.

## Changes in 3.0.0-alpha-13

Work was done on `3.0.0-alpha-13-SNAPSHOT`. Moving: the list "Moving from alpha-11 (or alpha-12)" above.

- **Guards are created with `spring.main.lazy-initialization=true`.** `PlatformConfigurationGuard`,
  `CredentialTransportGuard` and the new `DevListenAddressCheck` are `@Lazy(false)`. Before, the guard was created only as a
  dependency of `CredentialHeaderFilter`, so a server with lazy initialization and its own `CredentialHeaderFilter` started
  with no check at all (`dev` on `0.0.0.0`, `prod` with test keys, without mTLS, with jti in memory and the JWKS over http).
  `assertServerIsGuarded` fetches the beans (`getBean`), not only checks the definitions, so it also runs the guard's checks.
- **The address on which the server really listens.** In `dev`, after the web server (the main one and the management one)
  starts, the Tomcat connectors are checked; an address other than loopback, or unset (all interfaces), ends the start. This
  concerns code that sets the address of the server factory or adds a connector, because `server.address` does not show
  that. `assertServerIsGuarded` checks the same in a context with a web server (`ListenAddresses.nonLoopback`).
- **An event limit for requests without a valid token.** The shared budget covers `missing_token` and `malformed` (an empty or
  foreign `Authorization` header, a string that is not a JWT) and applies both to the `authentication` event and to the line
  `event=internal_jwt_rejected` in the application log. The surplus only in the counter
  `mcp_server_audit_authentication_suppressed_total` with the tag `reason` (`missing_token` or `malformed`); the request gets
  401 as always. The property `internal-jwt.missing-token-events-per-second` stays (the name from alpha-12). The limiter's
  window is now synchronized, so the budget is not exceeded under concurrency. SIEM alerts on `malformed` must also rely on
  the counter.
- Audit tests: the error codes for the reasons are computed from the contract table, not from the filter's predicate;
  `jwks_unavailable` is checked through the filter; the server assertion refuses a context outside `dev`.

## Changes in 3.0.0-alpha-11

Work was done on `3.0.0-alpha-11-SNAPSHOT`. Moving from alpha-10: bump the BOM version to `3.0.0-alpha-11` and replace
`DevProfileLoopbackTest` (the first point). The rest of the server code compatible with alpha-10 changes only where the
points say so explicitly.

- **`DevProfileLoopbackTest` in every server and in the template.** The test from alpha-5..alpha-10 starts the application
  through `SpringApplicationBuilder(...).profiles("dev")` with `--server.address=0.0.0.0` and expects the message about the
  address. In CI in a container (a Docker executor, `/.dockerenv`), on Kubernetes and under systemd the start is refused
  earlier, with the message "the 'dev' profile is active in ...", so the assertion fails. Do not remove the assertion and do
  not set anything in CI: replace the body of the test with a helper that checks the guard's rule independently of the host.
  The whole file `mcp-app/src/test/java/.../app/DevProfileLoopbackTest.java` should look like this (the package and the server
  name stay yours):

  ```java
  package pl.aiwcorpo.mcp.<system>.app;

  import org.junit.jupiter.api.Test;
  import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

  /**
   * The dev profile (test keys, no mTLS) does not listen on an address other machines can reach. The rule is the
   * starter's; this test keeps it from disappearing unnoticed from the starter version the server builds against.
   */
  class DevProfileLoopbackTest {

      @Test
      void devListensOnLoopbackOnly() {
          DevProfileGuardAssertions.assertDevProfileLoopbackRule();
      }
  }
  ```

  The imports of `TestGateway`, `SpringApplicationBuilder` and `assertThatThrownBy` and the references to
  `McpServerApplication` and `ServerTestSupport` disappear from this file; the dependency `mcp-platform-test` (scope `test`)
  is already there. The helper builds its own environment (without the host's variables and properties, with the pod and
  systemd signals set to fixed values), so it passes the same way on macOS, in a container, on a pod and under systemd. A
  specific address: `DevProfileGuardAssertions.assertDevProfileRefusesAddress("10.0.0.5")`. This test alone does not touch
  the server's configuration; from alpha-12 it is complemented by `assertServerIsGuarded(context)` (the section "Changes in
  3.0.0-alpha-12"). The sentence that the guard watches the address in every `@SpringBootTest` is true only when the guard
  exists in the server's context (alpha-11 did not check that).
- **The refusal of `dev` in a container** names devcontainer, Codespaces, Toolbox and Distrobox and says how to work: run the
  server outside the container, run it from a test (a test context is not checked) or use a profile other than `dev` with
  TLS, mTLS and Redis as in a deployment. There is no exemption of `dev` in a devcontainer; the decision waits for a human.
  Server tests that checked the text of the refusal do not exist (apart from `DevProfileLoopbackTest` above).
- **The test context is recognized by the jar, not by the class name alone**: a class from the package
  `org.springframework.test.context` or `org.springframework.boot.test.context` counts as a test framework only when it is
  loaded from the `spring-test` or `spring-boot-test` jar. A server's own class in these packages no longer turns off the
  check. This is a safety net, not a boundary: server code that deliberately forges the jar or the stack is outside the
  threat model.
- **The bean presence check** also covers `platformMcpServerCustomizer`: `@SpringBootApplication(exclude =
  PlatformMcpServerAutoConfiguration.class)` ends the start outside `dev` and outside a test context (without it the SDK
  would validate input before the audit wrapper). A server that did this must stop.
- **Text truncation** (notes from the migration): `-`, `.` and combining characters (`\p{M}`) count as part of a word, so
  `/@all-hands` gives `/@`, not `/@all-`. Cutting at the limit stays when stepping back would remove more than 64 units or
  there is no delimiter before the end: a CJK string after a single parenthesis does not disappear. **A single long word**
  (no spaces and no delimiter in the kept part) now gives a prefix with `truncated=true`, where it used to give an empty text
  (`plain("supercalifragilistic", 5)` is `super`). The prefix was chosen because without a delimiter there is no start of a
  token, and an empty description hid that anything had been there at all; delimiters (`@`, `/`, `(`) are still the boundary
  of stepping back (`@allison` limited to 4 units is `@`). A server test that compared the exact truncated text and expected
  an empty text must be corrected. `UntrustedText.cut(text, -1)` throws `IllegalArgumentException("maxLength must not be
  negative")`.
- **The validation message of a nested field** gives the path and the limit: `jql_filter.status is longer than 40
  characters`, `jql_filter.labels[1] is longer than 8 characters`, `jql_filter.status is required` (before,
  `jql_filter is longer than allowed characters`). The path is made of field names from the schema and array indexes; a map
  key from the input is `*`; the value is not in the message. A server test that checks the old message must be corrected.
- **`Map` in a tool result**: when the result contains `UntrustedText`, the starter publishes an `outputSchema` from the record
  type, and a map has neither field names nor a value type, so the start ends in the error "cannot describe the result type".
  Instead of `Map<String, String>` return a list of `Pair(String name, String value)` records or a record with named fields;
  text from the backend system in a value is `UntrustedText`, not `String`. A result without `UntrustedText` gets no schema,
  and a map in it is not checked.

## Deployment

- The server needs Redis (the user `mcp-<system>`, keys `jti:mcp-<system>:*`, the command `SET`).
- mTLS: `server.ssl.client-auth: need` with a truststore of the internal CA; the starter also checks the CN of the client
  certificate (`mtls.allowed-client-names`).
- Trust in the gateway's JWKS: a Spring SSL bundle in `internal-jwt.jwks-ssl-bundle`.

## Only in 3.0.0

Final documentation and reviews. The code items that this list once held (`@ResourceRef`, `ResourceResolver`, `PolicyEngine`,
`QueryScope`, `ResultFilter`, the C6 manifest and `GET /mcp-manifest`) are in beta-1 (the section "From alpha-14 to beta-1").
