# Changelog

Format: wersje SemVer; kolejność od najnowszej. Przed 3.0.0 wersje `alpha` i `beta` (tagi `v3.0.0-alpha-N`,
`v3.0.0-beta-N`) nie gwarantują zgodności między sobą.
Linia `mcp-platform` (3.x, 4.x) nie ma związku z wersją `mcp-contracts` (BOM zarządza obiema).

## 4.0.0 - 2026-11-06

Serwery dynamiczne: lista systemów, serwerów i typów zasobów przestaje być wiedzą startera. Polityki serwera leżą w jego jarze, dane
administratora (mapowanie grup, dozwoleni klienci, nadpisania) serwer pobiera od gatewaya po mTLS, a zatwierdzenie polityk
(`approvedPoliciesHash`) jest warunkiem, żeby serwer w ogóle decydował. Wydanie **major**. Kontrakty `mcp-contracts` 7.0.0 (CC-229 ... CC-237).
Kontrakty 7.0.0 usuwają `mcp-policy-bundle`: starter nie zależy już od paczek polityk, ich kluczy ani katalogu stanu.

- CC-249 (English entry; the bullets below on the policy data client and mTLS are superseded by it): the gateway connects to the server by
  address and pulls and pushes everything itself, the server connects to nothing of the platform but the public JWKS of the gateway.
  - Added: `GET /.well-known/mcp-manifest` (the manifest the build produces; 503 until it is built) and `POST /.well-known/mcp-policy-data`
    (C14), both at the origin. The policy data pushed by the gateway replaces what the server holds only with a strictly newer revision; the answer is
    `{revision, accepted, gate}`. The fail-closed gate answers `409 policy_data_stale` with `heldRevision` and `requiredRevision` (`PolicyDataStaleFilter`,
    right after the JWT filter, ahead of every other refusal), following `PolicyDataGate.decide(ownHash, held, prv)`; reason `policy_data_unavailable` is now `policy_data_stale`.
  - The internal JWT claim `use` (`call`, `manifest`, `policy-data`) is checked for every request, after the one-time `jti`: a mismatch is
    `401 wrong_purpose`, before the variant and the tool are looked at. The 401 body carries the `reason` on the two well-known paths only. 88 jwt vectors pass.
  - `aud: mcp-pending` (the probe token of the add operation, the server id is not known yet) is accepted only for `use: manifest` on `GET /.well-known/mcp-manifest` and only while the server
    holds no policy data (`PolicyDataHeld`, implemented by `PolicyEngine` and the adapter endpoint); after the first accepted push `aud` must equal the server id for every request, and every
    other purpose and path needs it from the start (`401 wrong_audience`). Nothing in the starter depends on the `Host` header (a request addressed by name or by IP works), and the self-signed
    certificate needs no `Host` match on the server side (`ManifestTest`, `PolicyDataNotPushedTest`).
  - TLS of the server port from `MCP_TLS_KEYSTORE` with `MCP_TLS_STORE_PASSWORD`, or `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_PLAIN_HTTP=true`; otherwise
    a self-signed certificate (EC P-256, 10 years) is generated once into `MCP_STATE_DIR` (`/var/lib/mcp/state`, `tls/server.crt`, `tls/server.key`) and reused.
    The start logs `event=tls_certificate source=<generated|pem|keystore> fingerprint=sha256:<hex over the DER>`, the format of `CertificateFingerprint`, so the author
    can compare it with the portal. No client certificate is asked for on the port.
  - The one-time `jti` store is the bounded memory of the process by default (entries end with the token), Redis only when `MCP_REDIS_HOST` is set. The key of paging cursors is generated once into the
    state directory unless `MCP_PAGING_CURSOR_KEY` is given; several instances need both Redis and an explicit key. `MCP_GATEWAY_JWKS_URI` is the public https JWKS of the gateway.
  - `mcp-platform-test`: `TestGateway` pushes the policy data (`PolicyDataEndpoint.attach`, `push`, `pushTo`; a listener attaches the server of a Spring test when its web server
    starts), mints tokens with `use` (`Token.use`, `manifestToken()`), serves the JWKS at `/.well-known/mcp-jwks.json`; `LocalGateway` pushes to the server at the origin of `--upstream`
    every 3 s and repeats a call answered `409 policy_data_stale` once after a push.
  - `manifest.internal-url` is optional and informational. `server.ssl.client-auth` is refused.
  - Removed: the policy data pull client (`PolicyDataClient`), `GatewayTls`, `policy-data.*` (`MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`), `mtls.*` and the check of the
    gateway client certificate (`client_certificate_rejected`), `internal-jwt.jwks-ssl-bundle`, `GET /mcp-manifest`. The removed settings stop the start with a pointer to this entry.
    Java API: `InternalJwtFilter` constructors lose the client certificate arguments, `PolicyEngine.create(policies, meters)`, `PolicyEngine.accept(data)`, `PolicyDataGate.Decision` reasons.

- Polityki z jara serwera: `src/main/resources/mcp/policies/` (`schema.cedarschema`, `*.cedar`, `resource-types.json`). Starter ładuje je i waliduje
  przy starcie (`PolicyFragments.validateFragment`, ten sam test co w gatewayu przy rejestracji) i przy generowaniu manifestu; serwer z niepoprawnymi
  plikami nie startuje, a komunikat wylicza wszystkie powody.
- Manifest: `resourceTypes`, `policies` i `policiesHash` (nad `{policies, resourceTypes}`); `definitionHash` bez zmian. Adapter (`receiver-mode: adapter`)
  nie ma żadnego z nich (`source: adapter`, `resourceTypes: []`, wpisy narzędzi bez ryzyka i akcji).
- `PolicyBundleClient` i część paczkowa `PolicyEngine` zastąpione klientem `GET /internal/v1/policy-data` po mTLS z certyfikatem własnym serwera
  (`policy-data.url`, `tls-name`, `ssl-bundle`), z odpytywaniem przez `ETag` i `If-None-Match`. Bramka `PolicyDataGate` z kontraktów: wywołanie jest
  oceniane tylko przy załadowanych danych, zatwierdzonym `policiesHash` własnych polityk i poprawnych nadpisaniach; inaczej `upstream_unavailable`
  z powodem `policy_data_unavailable`, `policies_not_approved`, `policies_differ` albo `overrides_invalid`. Rewizja to licznik: starter nie przyjmuje
  rewizji nie wyższej od trzymanej. `prv` nowsze od danych: odświeżenie przed oceną (jedno żądanie na rewizję, czekanie najwyżej `read-timeout`, 3 s pamięci nieudanego żądania), a gdy odpowiedź nie jest
  dość nowa, odmowa. Nowsza rewizja z niepoprawnym dokumentem to migawka odmawiająca wszystkiego. Bramka zwraca migawkę, którą wywołanie decyduje we wszystkich krokach.
  Poza `dev` połączenie z gatewayem zawsze używa certyfikatu własnego serwera (bez SSL bundle start się nie udaje); polityki czytane tylko z korzenia classpath klasy `@SpringBootApplication` (moduł albo biblioteka z `mcp/policies` to odmowa startu).
- Zestaw polityk serwera: wygenerowane polityki platformy dla zadeklarowanych typów (`PlatformServerPolicies`: dane `restricted` przez klasyfikację typu
  i rodziców, konto serwisowe poza zakresami użytkownika), fragment serwera i nadpisania, scalone przez `PolicyFragments.merge` (powtórzone `@id` to błąd).
  Dozwoleni klienci (`azp`) sprawdzani w kodzie przed Cedar. Serwer powtarza decyzję gatewaya o `call` (polityki platformy z kontraktów, `Tool` z własnego manifestu) na tej samej migawce, co reszta wywołania (obrona w głąb).
- `@ToolRisk(additionalGroup = "mcp-<system>-<sufiks>")` (CC-242): grupa wymagana oprócz grupy wynikającej z ryzyka; trafia do manifestu i do `definitionHash`,
  serwer egzekwuje ją przy każdym wywołaniu (`ToolDescriptor.withAdditionalGroup`), niepoprawna wartość zatrzymuje start. `RiskClassifiedTool#additionalGroup()` dla ręcznych wywołań.
- Naprawa: `PlatformPresenceCheck` (i `DevProfileGuardAssertions`) w trybie `receiver-mode: adapter` nie wymaga `policyEngine` i `resourcePolicy`, których adapter nie definiuje
  (`PlatformPresenceCheck.requiredBeans(environment)`); wcześniej sidecar adaptera nie startował. Test `AdapterModeStartTest` uruchamia prawdziwą aplikację w trybie adaptera.
- Zakresy, których nie da się użyć (typ spoza deklaracji, część lokalna niezgodna z `scopePattern`), są pomijane i zgłaszane (`event=policy_data_scope_ignored`).
- Zmienione: `CallerContext.policyRevision()` jest `long` (claim `prv` to licznik), `AuditLogger.policyDenied` przyjmuje `Long`.
- Usunięte: `MCP_POLICY_*` i całe `aiwcorpo.mcp.platform.policy.*` (klucze, `state-dir`, `allow-test-keys`, nadpisania paczkami); pozostawione w konfiguracji
  zatrzymują start z odesłaniem do `policy-data`. `ResourcePolicy` nie ocenia `Action::"call"`. `GrantProbe` (tabela typów pięciu systemów) i lista
  `ResourceType` w `ResourceRefs`. `PlatformConfigurationGuard` nie zna listy systemów: `server-id` to `mcp-<SystemId>` według wzorca z kontraktów.
- Pomocniki zasobów bez wiedzy o systemach: `QueryScope.of(constraint, types)`, `narrowedTo(containerType, ids)`, pomocniki JQL, CQL i OpenSearch
  działają na idach dowolnego typu zakresu (serwer używa tych dla swojego systemu); `Scopes.pathPrefixes` i `Scopes.dashPrefixes` z `mcp-cedar-entities`.
  `ResourceResolver` ma opcjonalne haki: `probe(id)`, `scopeTypes()` z `probeOutsideScopes(id)` i `hidesFailureForServiceAccount()`.
  `@ArgumentLimits(resourceType = "Jira::Issue")` bierze `idPattern` z deklaracji; `contractPattern` czyta już tylko definicje, które zostały w `common.schema.json`.
- `mcp-platform-test`: `PolicyDecisions` (test decyzji polityk serwera w zwykłym JUnit), `PolicyDataEndpoint` (`TestGateway.policyData()`) zamiast `PolicyRepository`.
  `TestGateway` i `LocalGateway` serwują `/internal/v1/policy-data` z konfigurowalnym mapowaniem, klientami i nadpisaniami oraz z `approvedPoliciesHash`
  liczonym z polityk serwera pod testem. `LocalGateway`: opcje `--scope-mapping`, `--allowed-clients`, `--policies-dir`; typ poświadczenia domyślnie `user-secret`.
  `TestGateway.Token.policyRevision` przyjmuje `long`. `PolicyEngine.fixed(policies, data)` daje silnik bez gatewaya.
- `LocalGateway` w `mcp-platform-test` (jar z klasyfikatorem `local-gateway`) i `TestGateway.start(serverId, port)` z 3.1.0-SNAPSHOT wchodzą do tego wydania.

### Migracja serwera

1. Zależności: BOM 4.0.0 (zarządza kontraktami 7.0.0); usuń `mcp-policy-bundle`, jeśli był zależnością.
2. Dodaj `src/main/resources/mcp/policies/`: `schema.cedarschema` z jedną przestrzenią własną (wzór: `cedar/examples/systems/jira` w `mcp-contracts`),
   `*.cedar` z akcjami `<Przestrzeń>::Action::"read"` itd. i `resource-types.json` z deklaracjami (`idPattern`, `scopePattern`, `earlyScopeCheck`,
   `classification`, rodzic). Każdy typ ma `scopes: Set<ResourceScope>`. Reguł `restricted` i konta serwisowego nie piszesz: generuje je starter.
3. Resolvery: zwracaj `scopes` zasobu i kontenerów (`ResolvedResource.withScopes`, `Scopes.id`, `Scopes.pathPrefixes`, `Scopes.dashPrefixes`); to, co robił
   builder (prefiksy ścieżek GitLaba, prefiksy indeksów, przodkowie stron Confluence, `inheritedClassification`), liczy resolver serwera. Haki `probe`,
   `scopeTypes` i `hidesFailureForServiceAccount` zastępują `GrantProbe`, jeśli typ ich potrzebuje.
4. Konfiguracja: usuń `aiwcorpo.mcp.platform.policy.*` i `MCP_POLICY_*`; dodaj `policy-data.url` (https poza `dev`), `policy-data.ssl-bundle` albo
   `server.ssl.bundle` (certyfikat własny serwera: CN to `serverId`) i w razie potrzeby `policy-data.tls-name`.
5. `@ArgumentLimits(contractPattern = "JiraIssueKey")` zamień na `@ArgumentLimits(resourceType = "Jira::Issue", maxLength = 16)`; usunięte definicje
   `Jira*`, `Confluence*`, `GitLab*`, `OpenSearch*`, `ServiceDesk*` z kontraktów nie istnieją.
6. Wdrożenie: nowe polityki i deklaracje zmieniają `policiesHash`; serwer odmawia wywołań (`policies_not_approved`, `policies_differ`), dopóki administrator ich
   nie zatwierdzi w gatewayu. Zarejestruj manifest (`manifest.output-file`) przed ruchem.
7. Testy: `TestGateway` serwuje dane polityki i zatwierdza hash polityk z classpath; token testowy ma `prv` równe rewizji startowej. Testy polityk bez Springa:
   `PolicyDecisions.fromResources()`. Token testowy nie musi już mieć grupy narzędzia (`mcp-<system>-read`): o `call` decyduje gateway.

## 3.0.0 - 2026-11-04

Pierwsze wydanie stabilne; kod jak w 3.0.0-beta-14 (kontrakty `mcp-contracts` 6.3.2), zmieniona jest tylko publikacja.

- Artefakty trafiają do Maven Central (grupa `pl.aiwcorpo.mcp`), nie do GitHub Packages; konsument nie potrzebuje wpisu repozytorium ani tokenu.
- Metadane POM dla Central (opis, `developers`, `scm`, `issueManagement`), profil `release` (jary sources i javadoc, podpis GPG).
- Workflow wydania podpisuje i wysyła pakiet do Portalu Central w jobie bez narzędzi budowania; `ci.yml` nie używa sekretów.

## 3.0.0-beta-14 - 2026-11-03

- Kontrakty `mcp-contracts` 6.3.2 (CHANGELOG 6.3.0, 6.3.1, 6.3.2, sekcje ws-02).
- CQL kończy się `AND type = page` (CC-219); wektory `query-rendering/` (27) przechodzą.
- `ReceiverMode` (`internal-jwt.receiver-mode`, `native` albo `adapter`, CC-226): serwer natywny odrzuca `mcp_risk` i `mcp_action` jako `invalid_claims`;
  sidecar adaptera odmawia `tools/call` bez nich (`classification_missing`), wystawia je w `CallerContext.mcpRisk()` / `mcpAction()`, a wartość inną niż
  `READ_ONLY` loguje i liczy. Wektory `jwt/` (66) z `receiverMode` przechodzą.
- `decision` w zdarzeniu `authentication` jest opcjonalne w kontrakcie; starter nadal je zapisuje. Katalog `audit-events/` ma 128 wektorów.

## 3.0.0-beta-13 - 2026-11-03

- `CursorCodec`: kursor jest szyfrowany i uwierzytelniany (AES-256-GCM), nie tylko podpisany. Klucz z `paging.cursor-key` przez
  HKDF-SHA256 z `server-id` jako kontekstem, losowy nonce, dane uwierzytelniane to `sub`, narzędzie i odcisk zapytania. Ładunek
  (np. wartości sortowania ukrytego trafienia) jest nieczytelny dla klienta. API bez zmian; każdy zły kursor to nadal ten sam `invalid_argument`.

## 3.0.0-beta-12 - 2026-11-03

Zmiany zgodne z beta-11 (podbicie wersji); poprawki po przeglądzie beta-11.

- `PageFiller`: dopełnianie strony kolejnymi stronami systemu do żądanego rozmiaru (z limitem odczytów), żeby krótka strona z
  `nextCursor` nie zdradzała ukrytych elementów; `Filled.exhausted()` mówi o wyczerpanym limicie.
- `CursorCodec`: podpis obejmuje `server-id` (nowy konstruktor, stary zostaje), kodowanie kursora jednoznaczne (bez `=` i bez
  niezerowych bitów dopełnienia), `NullPointerException` z komunikatem dla `null` w `tool`/`fingerprint`, ostrzeżenie o losowym kluczu
  dopiero przy pierwszym wydanym kursorze.
- `CapabilityProbeRunner`: sonda nie jest uruchamiana ponownie, dopóki poprzednie wywołanie trwa; po jednym wątku roboczym i
  harmonogramu na sondę; `start()` jednokrotne.
- `ProductVersion`: składnik powyżej 9999 nie jest wersją (`9.12.34567` jest puste, nie `9.12.0`).
- Workflow wydania: job `verify` bez środowiska i bez prawa zapisu, `publish` z `packages: write` i środowiskiem `release`, `sbom` z
  `contents: write` dołącza SBOM do wydania GitHub; limity czasu i `concurrency`; obraz CI po digeście; wersja pluginu help.
  Tag `v3.0.0-beta-11` nie służy do publikacji przez Actions.

## 3.0.0-beta-11 - 2026-11-03

- Kontrakty `mcp-contracts` 6.2.1 (6.0.0 ... 6.2.0 bez zmian w kodzie startera; 6.2.1 to pakowanie i licencja).
  Katalog `audit-events/` ma 125 wektorów (dochodzą zdarzenia brokera); schemat zgadza się ze wszystkimi.
- `CursorCodec`: kursor HMAC-SHA256 związany z `sub`, narzędziem i odciskiem zapytania, ważny 15 minut, jeden stały
  tekst odmowy (`invalid_argument`). Właściwości `aiwcorpo.mcp.platform.paging.*`.
- `PagedResult<T>`: wspólny kształt dużego wyniku (`items`, `nextCursor`, `truncated`, `omitted`), bez `total` i bez liczników
  elementów usuniętych przez politykę. `ToolOutputSchemas` podstawia argumenty typu rekordów generycznych, więc taki wynik
  dostaje `outputSchema`.
- `CapabilityProbe`, `Capabilities`, `BackendCapabilities`, `ProductVersion`: sonda wersji systemu przy starcie i okresowo,
  tryb `degraded` dla nieznanej wersji (serwer wstaje, zestaw cech zachowawczy), log, metryka `mcp_backend_capability`,
  szczegół zdrowia `backendCapability`. Właściwości `aiwcorpo.mcp.platform.capability.*`.
- README: projektowanie powtarzalnych narzędzi `UPDATE` (CC-204), duże wyniki, wersje systemu, instalacja z GitHub Packages.
- Licencja Apache-2.0 (`LICENSE`, `NOTICE` także w `META-INF` każdego jara), `distributionManagement` na GitHub Packages,
  workflow wydania `.github/workflows/release.yml` (SBOM CycloneDX), `SECURITY.md`, `CONTRIBUTING.md`.
- `ci/run.sh` sam szuka JDK 25 (bez ścieżek Homebrew w skrypcie), działa w bash 3.2.

## 3.0.0-beta-10 - 2026-10-31

- Kontrakty 5.1.0: token bez `vs` i `vsc` (401 `invalid_claims`), `Caller` bez serwera wirtualnego, `QueryConstraint(resourceType, granted)`,
  zdarzenia audytu bez `virtualServer`.
- `ci/run.sh` jako jedyny punkt wejścia CI (`verify`, `hygiene`, `release`) z nakładkami GitLab, Jenkins i GitHub Actions.

## 3.0.0-beta-9 ... beta-6 - 2026-10-29

- beta-9: sonda strony lub zgłoszenia bez zakresów leży wewnątrz linku, więc link tylko zawęża; błąd resolvera konta serwisowego
  to `upstream_unavailable` z ostrzeżeniem.
- beta-8: grant dla stron i zgłoszeń z polityki albo zakresu; `ignore_unavailable` w `_resolve/index`.
- beta-7: kontrakty 4.1.0, nagłówek `X-MCP-Credential-Type` sprawdzany przed Cedar; typ domyślny to pierwszy z
  `accepted-credential-types`.
- beta-6: kontrakty 4.0.0, klucze `dev-` tylko w trybie dev, `context.credentialType` z konfiguracji, `StructuredQuery` z
  normatywnych list pól i operatorów.

## 3.0.0-beta-5 ... beta-1 - 2026-10-28

- Polityki zasobów: `@ResourceRef`, silnik paczek polityk (Ed25519, ETag, ostatnia poprawna), `ResultFilter`, `QueryScope`,
  manifest C6 i `GET /mcp-manifest`; profil `devstack`.

## 3.0.0-alpha-14 ... alpha-1 - 2026-10-27 ... 2026-10-28

- Model "serwer za gateway'em": wewnętrzny JWT (C2), poświadczenie backendu (C3), audyt (C8), błędy narzędzi (C10),
  higiena tekstu i `UntrustedText`, limity argumentów, profil `dev`, `mcp-platform-test`.
- Usunięte z 2.x: `OidcIdentityResolver`, `ToolGuard`, `ConfirmationService`, `guard-policy.yml`.
