# mcp-platform: biblioteka serwerów MCP za gateway'em

Wspólna biblioteka serwerów MCP platformy (`mcp-jira`, `mcp-confluence`, ...). Od 3.0 serwer stoi wyłącznie za
gateway'em: nie loguje użytkowników, nie decyduje o uprawnieniach do narzędzi i nie pyta o potwierdzenia, to
robi gateway. Biblioteka weryfikuje wewnętrzny JWT gateway'a, odbiera poświadczenie do systemu docelowego,
audytuje każde wywołanie i pilnuje, żeby z narzędzia wychodziły tylko bezpieczne błędy.

Stos: Java 25, Spring Boot 4.1.0, Spring AI 2.0.0 (MCP SDK 2.0.0), kontrakty `mcp-contracts` 4.1.0.
Wersja: **3.0.0-beta-7** (stan i plan w `STATUS.md`, zmiany względem 2.x w `MIGRATION-3.0.md`).

## Moduły

| Artefakt | Co to | Zakres w serwerze |
| --- | --- | --- |
| `pl.aiwcorpo.mcp:mcp-platform-bom` | wersje: starter, test, `mcp-contracts`, Spring AI | `import` |
| `pl.aiwcorpo.mcp:mcp-platform-starter` | auto-konfiguracja (niżej) i transport MCP (streamable HTTP) | `compile` |
| `pl.aiwcorpo.mcp:mcp-platform-test` | `TestGateway`: JWKS i wewnętrzne JWT z kluczy testowych kontraktów | **tylko `test`** |

Wersja linii `mcp-platform` (3.x) nie ma związku z wersją kontraktów (BOM zarządza obiema).

## Co robi starter na każdym żądaniu

```
gateway --mTLS + JWT C2 + X-MCP-Credential--> SecurityHeadersFilter
  -> CorrelationIdFilter     X-Request-Id -> MDC requestId
  -> InternalJwtFilter       certyfikat gateway'a, JWT C2 związany z żądaniem -> CallerContext; inaczej 401
  -> CredentialHeaderFilter  X-MCP-Credential(-Type) -> CredentialContext; nagłówki ukryte przed resztą
  -> transport MCP -> AuditingToolCallback -> walidacja argumentów -> ResourcePolicy (Cedar: call, zasoby z @ResourceRef)
                                           -> [ToolCallInterceptor...] -> @Tool serwera -> filtr wyniku
  GET /mcp-manifest (JWT C2, bez tool) -> manifest C6 zbudowany ze startu serwera
```

**`InternalJwtFilter` (C2).** Przepuszcza tylko żądanie z certyfikatem klienta o CN `mcp-gateway` i poprawnym
wewnętrznym JWT: ES256, `kid` z JWKS gateway'a, `iss`, `aud` == `server-id`, `exp`/`iat` z tolerancją do 30 s,
`exp - iat` do 60 s, claimy zgodne z `schemas/internal-jwt.schema.json` (walidowane schematem z jara kontraktów,
także wzorce `vsc` i `vs` razem z `vsc`), `jti` użyty pierwszy raz (Redis), `rid` == `X-Request-Id`, `tool`
obecny dokładnie przy `tools/call` i równy `params.name`, wariant serwisowy tylko dla `initialize`,
`server/discover`, `tools/list`, `ping` i `GET /mcp-manifest`. Klucze z identyfikatorem `test-*` i opublikowane klucze testowe
(rozpoznawane po SPKI z `mcp-contracts/security/published-test-keys.json`) są odrzucane, chyba że
`allow-test-keys=true` w profilu `dev`. Kolejność kontroli i powody odrzucenia są jak w referencyjnym
odbiorcy kontraktów, więc wszystkie 57 wektorów `test-vectors/jwt` dają ten sam wynik i powód.

Odrzucenie: `401 {"error":"unauthenticated",...}` z `WWW-Authenticate: Bearer`, powód tylko w logu
(`event=internal_jwt_rejected reason=<kod>`). Niedostępny JWKS albo Redis: `503 upstream_unavailable`, nigdy
przepuszczenie. Treść żądania jest czytana raz (limit `max-request-body`, inaczej 413) i musi być jednym
obiektem JSON bez powtórzonych kluczy (inaczej 400), bo z niej pochodzi metoda i nazwa narzędzia: tablica
JSON-RPC albo `{"name":"a","name":"b"}` mogłyby zostać inaczej odczytane przez transport MCP.

Bez JWT przechodzą tylko dokładne ścieżki z `internal-jwt.unauthenticated-paths` (domyślnie
`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`), porównywane ze ścieżką
znormalizowaną przez kontener i tylko wtedy, gdy surowe URI jest dokładnie tą ścieżką: URI z `;`, `..`, `%`,
`\` albo `//` zawsze przechodzi pełną weryfikację. Wzorców (`/**`) nie da się skonfigurować.

**`CallerContext`.** Zweryfikowane claimy bieżącego żądania: `subject`, `username`, `groups`, `client`,
`virtualServer`, `virtualServerScopes` (`vsc`), `tool`, `requestId`, `policyRevision`, `mfaAgeSeconds`.
`CallerContext.current()` na wątku żądania. Pola odpowiadają wejściu entity buildera Cedar z
`mcp-cedar-entities`; z niego `ResourcePolicy` buduje wywołującego (`vs` i `vsc` z JWT trafiają do `context.vsScopes`).

**`CredentialContext` (C3).** Poświadczenie do systemu docelowego, wstrzyknięte przez gateway:

```java
BackendCredential credential = CredentialContext.require("jira");      // brak -> credential_required (C10)
restClient.get().uri("/rest/api/2/issue/{key}", key)
        .headers(credential::applyTo)                                    // Authorization: <scheme> <value>
        .retrieve().body(Issue.class);
```

`BackendCredential` ma `scheme()` (`Bearer`, `Basic`, `Token`, `ApiKey`), `value()`, `type()` (`user-oauth`,
`user-secret`, `token-exchange`, `service-account`), `authorizationValue()` i `applyTo(HttpHeaders)`;
`toString()` nie pokazuje wartości. Serwer obsługuje jeden system (`server-id` = `mcp-<system>`), prośba o inny
to błąd programisty (`IllegalStateException`). Po odczycie `Authorization`, `X-MCP-Credential` i `X-PAT-*` są
niewidoczne dla dalszej części żądania. Zły format, sam typ bez poświadczenia albo dwa poświadczenia: 400.

**`AuditingToolCallback`.** Opakowuje każde narzędzie (bean `ToolCallbackProvider` albo `ToolCallback`):
uruchamia je tylko dla zweryfikowanego wywołującego, którego JWT ma `tool` równe nazwie narzędzia (inaczej
`unauthenticated`, narzędzie się nie wykonuje), wywołuje beany `ToolCallInterceptor` (miejsce na politykę zasobów
i filtr wyników w beta), mapuje wyjątki na błędy C10 i zapisuje jedno zdarzenie C8 `tool_call`.

**Błędy narzędzi (C10).** Jedyny wyjątek, który opuszcza narzędzie, to `SanitizedToolException`; jego komunikat
to obiekt `{"error":"<kod>","message":"...",...}` zgodny z `schemas/tool-error.schema.json`. Mapowanie:
`CredentialRequiredException` -> `credential_required` (`system`, `connectUrl`), `InvalidToolArgumentException` ->
`invalid_argument` z własnym komunikatem (piszesz go sam, nigdy z treścią odpowiedzi backendu), każdy inny
`IllegalArgumentException` (biblioteki, konwersje) -> `invalid_argument` ze stałym tekstem, `SecurityException` ->
`access_denied`, backend nieosiągalny albo 502/503/504 -> `upstream_unavailable`, reszta -> `internal_error`.
Własne mapowania: beany `ToolErrorMapping`, tylko z kodami C10; wynik niezgodny z `tool-error.schema.json`
(np. bez wymaganego pola) klient dostaje jako `internal_error`. Z każdego komunikatu (do klienta i do logu)
usuwane jest poświadczenie bieżącego żądania, także w postaci URL-encoded i JSON-escaped. Ciała odpowiedzi błędów
backendu nie trafiają ani do klienta, ani do logu: gdy w łańcuchu wyjątków jest wyjątek z pakietów
`org.springframework.web.client`, `org.springframework.http.converter`, `com.fasterxml.jackson` albo
`tools.jackson` (błąd HTTP, ciało 200, którego nie da się sparsować), log `event=tool_failed` zawiera tylko klasy
wyjątków i status HTTP, bez komunikatów (opakowania jak `ToolExecutionException` Spring AI kopiują do nich
fragmenty ciała). Adapter nie musi tego obchodzić własnym
`defaultStatusHandler`. Poziom `event=tool_failed` zależy od znaczenia błędu: oczekiwany wynik (błąd wywołującego,
brak poświadczenia, odmowa, `not_found_or_denied`, wyjątek domenowy z `ToolErrorMapping`) to INFO z kodem C10 i klasą
wyjątku, bez komunikatu i bez stosu; niedostępny backend to WARN; ERROR tylko dla `internal_error`, czyli tego, czego
serwer nie przewidział.

**Audyt (C8).** Jedna linia JSON na zdarzenie na loggerze `pl.aiwcorpo.mcp.audit` (`component: server`,
`componentId` = `server-id`, `requestId` = `rid`, `risk`, `credentialType`, `outcome`, `errorCode`,
`latencyMs`, `argsLength`). Nigdy wartości argumentów, wyników ani poświadczeń; wszystko poza drukowalnym ASCII
jest zapisywane jako escape JSON.

**Flaga ryzyka.** Każde narzędzie ma `@ToolRisk(RiskLevel.READ_ONLY | UPDATE | DESTRUCTIVE)`; bez niej, z nazwą
sprzeczną z flagą (`delete_*` jako `READ_ONLY`) albo z powtórzoną nazwą serwer nie wstaje. Flaga trafia do
`readOnlyHint`/`destructiveHint` i do audytu (`risk`). Narzędzia `@McpTool` i ręczne beany
`SyncToolSpecification` omijałyby opakowanie: starter wyłącza skaner adnotacji Spring AI
(`spring.ai.mcp.server.annotation-scanner.enabled=false`), a serwer, na którego liście jest narzędzie
niezarejestrowane przez opakowanie, nie wstaje (`RegisteredToolsCheck`).

**Transport SYNC.** `CallerContext` i `CredentialContext` to `ThreadLocal`; `spring.ai.mcp.server.type=ASYNC`
zatrzymuje start (`CredentialTransportGuard`).

## Konfiguracja

```yaml
aiwcorpo.mcp.platform:
  server-id: mcp-jira                                     # wymagane
  auth-mode: gateway                                      # gateway | dev-pat (tylko profil dev)
  internal-jwt:
    issuer: https://mcp-gateway.internal                  # domyślnie
    jwks-uri: https://mcp-gateway.internal:8443/.well-known/jwks.json   # wymagane
    jwks-ssl-bundle: mcp-internal-ca                      # bundle SSL Springa z CA wewnętrznym
    jwks-cache-ttl: 10m
    jwks-max-stale: 1h                                    # dłużej bez JWKS -> 503
    jwks-refresh-min-interval: 30s                        # nieznany kid pobiera JWKS najwyżej raz na 30 s
    clock-skew: 30s                                       # najwyżej 30 s
    max-lifetime: 60s                                     # najwyżej 60 s
    replay-store: redis                                   # redis | memory (tylko profil dev)
    allow-test-keys: false                                # true tylko w profilu dev
    max-request-body: 4MB
    unauthenticated-paths: [/actuator/health, /actuator/health/liveness, /actuator/health/readiness]
  mtls:
    required: true                                        # false tylko w profilu dev
    allowed-client-names: [mcp-gateway]
  credential:
    connect-url: http://localhost:18090/connections?system={system}   # wymagane, do credential_required
  policy:                                                 # paczka polityk C7, ta sama co w gateway'u (niżej, "Zasoby i polityki")
    main:
      url: https://policies.internal/policies/main/latest.json   # wymagane; https poza dev
      key-id: policies-main-2026                          # wymagane; test-* tylko z allow-test-keys w dev
      public-key-file: /etc/mcp/policies-main.pub.pem     # albo public-key: <PEM>; Ed25519, SPKI
      refresh-interval: 30s                               # domyślnie 30 s
      minimum-revision: 20261001000000-0000000            # opcjonalnie, najniższa kiedykolwiek przyjęta (CC-066)
    overrides:                                            # opcjonalna paczka awaryjna, osobny klucz i adres
      url: https://policies.internal/policies/overrides/latest.json
      key-id: policies-overrides-2026
      public-key-file: /etc/mcp/policies-overrides.pub.pem
      refresh-interval: 5s                                # domyślnie 5 s
    state-dir: /var/lib/mcp/policy-state                  # wymagane poza dev: ochrona przed cofnięciem po restarcie
    allow-test-keys: false                                # true tylko w profilu dev
    startup-timeout: 30s                                  # tyle czeka start na pierwszą paczkę (bez niej serwer nie wstaje)
    overrides-grace: 2m                                   # po restarcie czekamy na paczkę awaryjną, która była aktywna
    connect-timeout: 2s
    read-timeout: 10s                                     # czas jednego żądania w całości, z treścią (zawieszone repozytorium nie blokuje wątków)
    ssl-bundle: mcp-internal-ca                           # opcjonalnie, zaufanie do repozytorium paczek
  manifest:                                               # manifest C6 (GET /mcp-manifest)
    version: 1.4.0                                        # wymagane, SemVer serwera
    internal-url: https://mcp-jira.internal:8443/mcp      # wymagane, https; informacyjny (gateway ma własną konfigurację)
    credential-required: true
    accepted-credential-types: [user-oauth, user-secret]  # wymagane; pierwszy to typ domyślny (context.credentialType w Cedar); albo samo service-account
    output-file: target/mcp-manifest.json                 # opcjonalnie: zapis manifestu przy starcie (rejestracja w gateway'u)
  tools.unclassified: fail                                # fail | destructive
  tools.max-filtered-elements: 200                        # najdłuższa lista w wyniku sprawdzana politycznie (dłuższa = internal_error)
  tools.max-result-size: 1MB                              # większy wynik narzędzia = internal_error
spring.ai.mcp.server.name: mcp-jira                      # protocol/type/endpoint ustawia starter
spring.data.redis:                                        # magazyn jti
  host: redis
  username: mcp-jira                                      # klucze jti:mcp-jira:*, tylko SET (NX PX)
  password: ${REDIS_MCP_JIRA_PASSWORD}
server.ssl:                                               # mTLS kończy serwer
  bundle: mcp-jira
  client-auth: need
```

Serwer nie wstaje, gdy:
- brakuje `server-id`, `jwks-uri` albo `connect-url`, `clock-skew`/`max-lifetime` przekraczają limity C2;
- system z `server-id` (`mcp-<system>`) nie jest `SystemId` kontraktów (`jira`, `confluence`, `gitlab`,
  `servicedesk`, `opensearch`; lista czytana z `common.schema.json` w jarze kontraktów). W profilu `dev` tylko
  ostrzeżenie w logu (np. dla serwera przykładowego `mcp-example`), ale wtedy `credential_required` dochodzi do
  klienta jako `internal_error`, bo nie przechodzi walidacji C10;
- `unauthenticated-paths` zawiera coś spoza `/actuator/health`, `/actuator/health/liveness`,
  `/actuator/health/readiness`; protokół MCP jest inny niż `STREAMABLE`;
- brakuje `policy.main.url`, `key-id` albo klucza publicznego (`public-key` albo `public-key-file`), `manifest.version`
  (SemVer), `manifest.internal-url` (https) albo `manifest.accepted-credential-types` przy `credential-required`; `accepted-credential-types`
  jest puste (też bez `credential-required`) albo miesza `service-account` z typami użytkownika (CC-168); poza
  `dev` brakuje `policy.state-dir`, adres paczki nie jest https albo `policy.allow-test-keys=true`;
- nie da się załadować pierwszej paczki polityk w `startup-timeout` (brak repozytorium, zły podpis, klucz `test-*` bez
  `allow-test-keys`, rewizja niższa od zapisanej w `state-dir`): serwer bez podpisanej polityki nie wstaje;
- manifest serwera nie przechodzi schematu `server-manifest.schema.json` ani reguł mcp-manifest (poza `dev`, gdzie jest
  ostrzeżenie `event=manifest_rejected_by_rules`): np. narzędzie bez `@ToolTitle`, schemat bez limitu, `confirmation_code`
  w argumentach;
- zostały ustawienia 2.x (`guard.*`, `pat-header-prefix`);
- w profilu `dev` `server.address` nie jest adresem pętli zwrotnej (`127.0.0.1`, `::1`, `localhost`); starter
  ustawia w `dev` domyślnie `127.0.0.1`, więc odmowa dotyczy jawnego nadpisania (np. `SERVER_ADDRESS=0.0.0.0`).
  Serwer w kontenerze, do którego łączy się gateway, nie może więc działać w profilu `dev`. To samo dla osobnego serwera
  managementu: przy `management.server.port` różnym od `server.port` w `dev` wymagane jest loopbackowe
  `management.server.address`;
- profil `dev` (jedyny aktywny) w środowisku wdrożenia: Spring Boot wykrywa platformę chmurową (Kubernetes, Cloud Foundry
  itd.; `spring.main.cloud-platform=none` nie jest platformą), są ustawione zmienne `KUBERNETES_SERVICE_HOST` i
  `KUBERNETES_SERVICE_PORT`, jest ustawione `INVOCATION_ID` (jednostka systemd) albo proces działa w kontenerze
  (`/.dockerenv`, `/run/.containerenv`, zmienna `container`, ścieżka cgroup z `docker`, `kubepods`, `libpod`, `crio`, `containerd`).
  `dev` zdejmuje z serwera TLS, mTLS i Redis, więc `SPRING_PROFILES_ACTIVE=dev` w pliku `.env`, szablonie compose, obrazie
  czy jednostce systemd zatrzymuje start (przed otwarciem gniazda) zamiast po cichu zostawić serwer z samym JWT.
  Uruchamiaj `dev` z terminala na stacji programisty. Devcontainer VS Code, Codespaces, Toolbox i Distrobox to też
  kontenery: odmowa wymienia je i podaje trzy wyjścia (uruchom serwer poza kontenerem; uruchom go z testu, bo kontekst
  testowy nie jest sprawdzany; albo użyj profilu innego niż `dev` z TLS, mTLS i Redisem skonfigurowanymi jak we wdrożeniu).
  Starter nie dopuszcza `dev` w kontenerze na podstawie znaczników środowiska programisty (`REMOTE_CONTAINERS`,
  `CODESPACES`): to decyzja o modelu zagrożeń, jeszcze nie podjęta. To siatka na skopiowaną konfigurację, nie granica bezpieczeństwa:
  kto steruje środowiskiem procesu, ukryje sygnały (pusty `SPRING_MAIN_CLOUD_PLATFORM`, plik w innym układzie); zmienne
  procesu (`INVOCATION_ID`, `KUBERNETES_*`) są czytane także bezpośrednio, więc `-DINVOCATION_ID=` ich nie zasłania.
  Kontekst budowany przez Spring TestContext (`@SpringBootTest`, slice'y) nie jest wdrożeniem: testy serwera z
  `TestGateway` działają w `dev` w CI na Kubernetesie i pod runnerem systemd. Kontekst testowy jest rozpoznawany po
  ramkach test frameworka na stosie wątku, który go buduje (klasa z pakietu `org.springframework.test.context` albo
  `org.springframework.boot.test.context` załadowana z jaru `spring-test` lub `spring-boot-test`), a nie po właściwości,
  którą dałoby się ustawić we wdrożeniu. To siatka, nie granica: własna klasa w tych pakietach w jarze serwera nie
  liczy się jako test framework, ale kod serwera, który świadomie fałszuje jar albo stos, nie jest w modelu zagrożeń
  (kto steruje kodem serwera, steruje też jego konfiguracją). Test na regułę adresu w `dev` nie startuje aplikacji
  przez `SpringApplicationBuilder` (na hoście CI odmowa "wdrożenie" pada wcześniej); patrz "Testy serwera bez gateway'a";
- `spring.autoconfigure.exclude` (także `SPRING_AUTOCONFIGURE_EXCLUDE`) z klasą z pakietu `pl.aiwcorpo.mcp.platform.`
  albo `exclude` na `@SpringBootApplication`, które usuwa `platformConfigurationGuard`, `internalJwtVerifier`,
  `internalJwtFilter`, `toolCallbackAuditPostProcessor` lub `platformMcpServerCustomizer` (auto-konfiguracja serwera MCP
  platformy), w każdym profilu, także `dev` (guard wiąże relaksacje `dev` z adresem pętli zwrotnej), poza kontekstem
  testowym. Kontekst testowy serwera sprawdza `DevProfileGuardAssertions.assertServerIsGuarded(context)` (patrz "Testy
  serwera bez gateway'a");
- profil `dev` jest aktywny razem z innym profilem (także przez grupę profili). Relaksacje działają tylko, gdy
  `dev` jest **jedynym aktywnym** profilem; `spring.profiles.default=dev` się nie liczy;
- poza takim profilem `dev`: włączono `dev-pat`, `allow-test-keys`, `replay-store: memory` albo
  `mtls.required: false`; `jwks-uri` nie jest absolutnym URL `https`; brak `server.ssl.client-auth=need`;
  `management.server.port` jest ustawiony i różny od `server.port` (osobny serwer managementu nie przechodzi
  przez filtr JWT/mTLS).

Domyślne wartości startera (najniższy priorytet): `management.health.redis.enabled=false` (sprawdzenie zdrowia
Redisa używa `INFO`, którego użytkownik `mcp-<system>` w `mcp-platform-dev` nie może wykonać),
`spring.data.redis.timeout=2s` (zawieszony Redis daje szybko 503), transport MCP jak w "Pierwszy serwer",
`spring.ai.mcp.server.annotation-scanner.enabled=false`.

**Profil `devstack` (wspólny stos deweloperski w kontenerach).** `dev` jest zabroniony w kontenerze, a stos `mcp-platform-dev` uruchamia serwery w
kontenerach z kluczami podpisu `test-*`. Profil `devstack` jest dla niego: zachowuje **wszystko** z produkcji (TLS i mTLS, Redis, https, `state-dir`,
bez `dev-pat`, `replay-store: memory`, `mtls.required: false`) poza tym, że przyjmuje klucze `test-*` i opublikowane klucze testowe
(`internal-jwt.allow-test-keys` i `policy.allow-test-keys`). Wymaga dwóch zgód jednocześnie, żeby skopiowana konfiguracja nie trafiła na produkcję:
właściwość `aiwcorpo.mcp.platform.devstack.acknowledged=true` **i** zmienna środowiskowa `MCP_DEVSTACK_ACKNOWLEDGED=true` (obie w konfiguracji stosu
deweloperskiego, nie w obrazie serwera). Zmienna jest czytana wyłącznie ze środowiska procesu: właściwość o tej nazwie z `application.yml`, linii poleceń
albo `SPRING_APPLICATION_JSON` nie wystarcza. Strażnik konfiguracji działa przed pobraniem paczki polityk i kluczy przez beany platformy (jawne wywołanie na początku `policyEngine`, `gatewayKeys`,
`jtiReplayStore` i `internalJwtVerifier`, także przy `spring.main.lazy-initialization=true`); własne `GatewayKeys` i `JtiReplayStore` serwera są tworzone przed
`internalJwtVerifier`, ale po strażniku, o ile nie robią I/O w konstruktorze. Wersja startera w serwerze: właściwość `mcp-platform.version` (szablon) albo `<version>`
importu `mcp-platform-bom` (opis w `MIGRATION-3.0.md`). Musi być jedynym aktywnym profilem. Każdy start loguje `WARN event=devstack_profile`, a metryka
`mcp_server_devstack_active` ma wartość 1, a manifest C6 dostaje `"environment": "dev"` (CC-138; gateway w trybie produkcyjnym odrzuca taki manifest:
`422 manifest_rejected`, powód `environment_mismatch`; pole nie wchodzi do `definitionHash`, a w pozostałych profilach go nie ma, czyli znaczy produkcję).
Klucze `test-*` pod innym profilem niż `dev` i `devstack` są odrzucane jak dotąd, więc klucz produkcyjny nie może nazywać się `test-...`.
**Klucze `dev-` (CC-137)** (`kid` weryfikatora JWT i identyfikator klucza paczki polityk, które stos deweloperski generuje lokalnie) przechodzą tylko w
trybie dev, czyli przy profilu `devstack` (z obiema zgodami) **i** `allow-test-keys=true`; samo `allow-test-keys` (testy jednostkowe serwerów, profil
`dev`) przyjmuje klucze `test-*`, ale `dev-` odrzuca z powodem `test_key_not_allowed` (JWT: 401) albo `test_key_not_allowed` (paczka: zostaje poprzednia).

**Tryb `dev-pat`** (tylko gdy `dev` jest jedynym aktywnym profilem): poza `X-MCP-Credential` serwer przyjmuje token
programisty w `X-PAT-<system>` (np. `X-PAT-Jira`) jako `Bearer` typu `user-secret`. Wewnętrzny JWT nadal jest
wymagany.

## Pierwszy serwer

Transport MCP ustawia starter (od 3.0.0-alpha-2, najniższy priorytet, serwer może nadpisać):
`spring.ai.mcp.server.protocol=STREAMABLE`, `spring.ai.mcp.server.type=SYNC`,
`spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp`. W 3.0.0-alpha-1 trzeba je ustawić samemu, inaczej
`/mcp` nie istnieje (404). Nazwę serwera podaje `spring.ai.mcp.server.name`.

Minimalny serwer to trzy pliki w jednym module Mavena (`mcp-jira/`), budowane zainstalowanym Mavenem 3.9+ (`mvn`) albo
wrapperem projektu (`./mvnw`). Java 25 jest wymagana do budowy i uruchomienia; `java` z PATH bywa starsza, dlatego
poniżej `$JAVA_HOME/bin/java` (`JAVA_HOME` wskazuje JDK 25).

`pom.xml`. `finalName` decyduje o nazwie jara, `spring-boot-maven-plugin` robi z niego jar wykonywalny:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>
    <groupId>pl.aiwcorpo.mcp.jira</groupId>
    <artifactId>mcp-jira</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <properties>
        <java.version>25</java.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>pl.aiwcorpo.mcp</groupId>
                <artifactId>mcp-platform-bom</artifactId>
                <version>3.0.0-beta-7</version>
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
    </dependencies>
    <build>
        <finalName>mcp-jira</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`src/main/resources/application.yml`: konfiguracja z sekcji "Konfiguracja" powyżej (do uruchomienia w `dev` wystarczą
`server-id`, `internal-jwt.jwks-uri`, `credential.connect-url`, `policy.main.*` (adres, `key-id`, klucz publiczny; w `dev` z
`policy.allow-test-keys: true`) i `manifest.*` (wersja, `internal-url`, typy poświadczeń); reszta ma wartości domyślne albo jest w
`dev` zdejmowana). Serwer bez paczki polityk nie wstaje, więc przy pierwszym starcie lokalnym potrzebne jest repozytorium
paczek (`mcp-platform-dev`: `http://127.0.0.1:18747/policies/main/latest.json`, klucz `test-policies-main-dev` z
`mcp-policies/keys/dev/`); w testach robi to `TestGateway`.

`src/main/java/jira/JiraServer.java`. Narzędzia rejestruje się beanem `ToolCallbackProvider`; sam bean z metodami `@Tool`
nie wystarcza (Spring AI odpowiada wtedy `Unknown tool`):

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
        @ResultWithoutResources     // wynik to zwykły tekst; zob. "Wyniki: ResultFilter"
        public String getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String issueKey) {
            // A real adapter calls Jira with: .headers(CredentialContext.require("jira")::applyTo)
            return issueKey + " read with a " + CredentialContext.require("jira").scheme().wireName() + " credential";
        }
    }
}
```

Budowa: `mvn package` daje `target/mcp-jira.jar` (nazwa z `finalName`; bez niego byłby `mcp-jira-0.1.0-SNAPSHOT.jar`).
Start i sprawdzenie: sekcja niżej.

## Uruchomienie lokalne

Serwer z konfiguracji z sekcji "Konfiguracja" (z `server.ssl`, bundlem CA wewnętrznego i Redisem) startuje na
stacji programisty bez certyfikatów, CA ani Redisa, jeśli `dev` jest **jedynym** aktywnym profilem. W tym profilu
starter, powyżej `application*.yml` serwera (wiersz poleceń, właściwości systemowe i zmienne środowiskowe nadal
wygrywają), ustawia:

| Właściwość | Wartość w `dev` | Po co |
| --- | --- | --- |
| `server.address` | `127.0.0.1` (domyślnie; adres spoza pętli zwrotnej zatrzymuje start) | relaksacje `dev` nie wychodzą z maszyny |
| `server.ssl.enabled` | `false` | bez `server.ssl.bundle` i certyfikatu serwera (bez `NoSuchSslBundleException`) |
| `aiwcorpo.mcp.platform.mtls.required` | `false` | bez certyfikatu klienta gateway'a |
| `aiwcorpo.mcp.platform.internal-jwt.replay-store` | `memory` | bez Redisa |
| `aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle` | puste | bez bundla CA wewnętrznego (JWKS po zwykłym https albo http) |

`internal-jwt.allow-test-keys` nie jest wymuszane: ustaw `true` w `application-dev.yml` serwera, gdy gateway lokalny
(`mcp-platform-dev` albo `TestGateway`) podpisuje kluczami testowymi. `jwks-uri` pozostaje wymagane, ale nie jest
pobierane, dopóki nie przyjdzie żądanie z JWT.

Start (zależności startera muszą być w `~/.m2`: `./mvnw install` w repozytorium startera, albo wydana wersja):

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home   # dowolny JDK 25
mvn spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=18600
# albo po mvn package (jar z finalName `mcp-jira`; Java 25, nie `java` z PATH, jeśli jest starsza):
$JAVA_HOME/bin/java -jar target/mcp-jira.jar --spring.profiles.active=dev --server.port=18600
```

W devcontainerze, Codespaces, Toolboxie albo Distroboxie (kontener programisty) `dev` nie wystartuje: starter widzi
kontener i odmawia (to samo sprawdzenie chroni obraz z `SPRING_PROFILES_ACTIVE=dev`). Uruchom wtedy serwer poza
kontenerem, z testu (`@SpringBootTest` z `TestGateway` nie jest sprawdzany) albo w profilu innym niż `dev` z TLS, mTLS i
Redisem skonfigurowanymi jak we wdrożeniu (sekcja "Konfiguracja"). Czy dopuścić `dev` w kontenerze programisty, nie jest
rozstrzygnięte.

Sprawdzenie:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18600/actuator/health      # 200
curl -s -i -X POST http://127.0.0.1:18600/mcp -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -1                   # HTTP/1.1 401
```

`/actuator/health` jest w zależnościach startera (`spring-boot-starter-actuator`). `POST /mcp` bez wewnętrznego JWT
daje 401 z `WWW-Authenticate: Bearer` i zdarzeniem `authentication` w audycie, nigdy odpowiedzi anonimowej. Wywołanie
narzędzia wymaga JWT gateway'a (nagłówki jak w teście z sekcji "Testy serwera bez gateway'a").

Poza `dev` błędy konfiguracji pojawiają się po kolei, bo Spring sprawdza je w różnych fazach: najpierw start serwera
HTTP (brak bundla SSL, certyfikatu, CA), potem `PlatformConfigurationGuard` (zbiorczo, wszystkie naruszenia w jednym
komunikacie), na końcu narzędzia (brak limitu argumentu, `@ToolRisk`). Poprawiony błąd SSL może więc odsłonić następny
z kolejnej fazy (brak limitu argumentu zgłosi się dopiero po usunięciu błędu SSL: serwer HTTP startuje przed
utworzeniem beanów narzędzi). W `dev` pierwsza faza jest wyłączona, a brak limitu argumentu jest ostrzeżeniem
`event=tool_schema_open` zamiast błędu.

## Argumenty narzędzi: schemat, limity, walidacja

Spring AI generuje z `@Tool` tylko typy i opisy; nie wpisuje do `inputSchema` `maxLength`, `pattern` ani
zakresów. Starter ma na to jedno API (zastępuje własne wersje w template i serwerach):

- `@ArgumentLimits` na parametrze i na komponencie rekordu: `maxLength` (obowiązkowe dla tekstu), `minLength`,
  `pattern`, `contractPattern`, `minimum` i `maximum` (obowiązkowe oba dla liczb), `maxItems` (obowiązkowe dla listy
  i tablicy; pozostałe limity opisują wtedy elementy), `minItems`. `contractPattern = "JiraIssueKey"` bierze wzorzec
  (i `maxLength`, gdy nie podano) z definicji `$defs` w `common.schema.json` jara kontraktów, więc serwer nie
  przepisuje wzorców identyfikatorów (`JiraProjectKey`, `JiraIssueKey`, `ConfluenceSpaceKey`, `ConfluencePageId`,
  `GitLabProjectPath`, `OpenSearchIndexName`, `ServiceDeskTicketId`, ...). `pattern` zawsze z `^` i `$`, bez `|`
  poza grupą (`^(?:a|b)$`).
- Filtr to rekord: limity na jego komponentach, pole opcjonalne z `@Nullable` (Spring AI nie daje wtedy `required`);
  obiekt jest zamykany (`additionalProperties: false`). Lista rekordów: `maxItems` na parametrze, limity na
  komponentach. `Map`, obiekt niebędący rekordem i lista list nie dają się ograniczyć i zatrzymują start.
- `SchemaLimitedToolCallbacks.of(toolObject...)` zamiast `MethodToolCallbackProvider`: buduje te same
  `MethodToolCallback`, dopisuje limity na każdym poziomie i `additionalProperties: false`. Brak limitu na którymkolwiek
  poziomie, limity nieodpowiedniego typu albo nieznany `contractPattern` zatrzymują start.

**Walidacja jest w starterze.** MCP SDK 2.0.x waliduje argumenty przed opakowaniem audytu i odpowiada własnym
tekstem (`Tool (x) input validation failed`), bez C10 i bez zdarzenia C8. Starter wyłącza walidację SDK
(`validateToolInputs(false)`) i sam sprawdza argumenty względem `inputSchema` narzędzia w `AuditingToolCallback`
(biblioteka `json-schema-validator`, JSON Schema 2020-12: typy, `required`, `additionalProperties`, `pattern`,
`minLength`/`maxLength`, `minimum`/`maximum`, `enum`). Kolejność w `AuditingToolCallback`: powiązanie z
wywołaniem gateway'a (brak -> `unauthenticated`, schemat nie jest sondowany bez uwierzytelnienia), walidacja,
interceptory, narzędzie. Naruszenie to `invalid_argument` (C10) z komunikatem w rodzaju
`Invalid arguments: issueKey does not match the required format ^...$; limit is above the maximum 100.` i
zdarzenie C8 (`outcome=error`, `errorCode=invalid_argument`). Komunikat powstaje ze schematu: nie zawiera wartości
argumentu ani nazw argumentów spoza schematu (`unknown arguments are not allowed`). `pattern` jest wykonywany tak, jak
znaczy w ECMA 262: `$` oznacza koniec wejścia (`"ABC-1\n"` nie pasuje do `^[A-Z]+-[0-9]+$`). Tekst
każdego napisu w argumentach (także zagnieżdżonego, według reguły jego własnego węzła schematu) przechodzi
`TextHygiene`: znaki sterujące (C0, DEL, C1), U+2028/U+2029, niesparowane surogaty i nieznaki, bidi override i
isolate, zero-width, U+2060..U+2064, BOM, znaki tagów i każdy inny `\p{Cf}` poza U+200C..U+200F to `invalid_argument`; `\t`, `\n`, `\r` są dozwolone w tekście
swobodnym, a w wartości z `pattern` (klucz, ścieżka) odrzucane razem z każdym znakiem formatującym `\p{Cf}`.
Klucze obiektów (nazwy pól mapy lub obiektu) przechodzą tę samą higienę co wartości z `pattern`. Narzędzie nadal powinno
sprawdzać to, na czym polega, tymi samymi stałymi.

**Fail-closed.** Poza `dev` start zatrzymuje narzędzie bez `inputSchema`, ze schematem bez `type: object` i
`additionalProperties: false` albo z poziomem bez limitu na dowolnej głębokości (`InputSchemaBounds`): obiekt otwarty,
tekst bez `maxLength`, lista bez `maxItems` lub bez `items`, liczba bez `minimum` i `maximum`, wartość bez typu,
`pattern` bez kotwic. W profilu `dev` (jedyny aktywny) jest ostrzeżenie `event=tool_schema_open` i egzekwowane jest to,
co schemat mówi. Polityki nie da się wyłączyć beanem ani własnym opakowaniem: `ToolInputPolicy` (enum), `AuditingToolCallback` i
`ToolCallbackAuditPostProcessor` (klasy `final` z konstruktorami pakietowymi) nie dają się zbudować w serwerze;
`ToolCallbackAuditPostProcessor`, `RegisteredToolsCheck`, `ToolHintsPostProcessor` i `ToolInputPolicy` nie są punktami
rozszerzenia, a bean tych typów (albo podmieniona definicja beana o nazwie platformy) zatrzymuje start poza `dev`.
`PlatformConfigurationGuard`, `InternalJwtVerifier` i `InternalJwtFilter` zatrzymują start przy własnym beanie w każdym
profilu. Punkty rozszerzenia (`@ConditionalOnMissingBean`): `GatewayKeys`, `JtiReplayStore`, `AuditLogger`,
`ToolRiskRegistry`, `McpErrorMapper`. Własne zachowanie wokół narzędzi dodaje się przez `ToolCallInterceptor`. Wzorce z `pattern` pisze serwer, więc unikaj wyrażeń o wykładniczym czasie dopasowania.

**Treść z systemu.** Wynik narzędzia z tekstem z systemu (opis, komentarz, tytuł, wiersz logu) zawiera
`pl.aiwcorpo.mcp.platform.text.UntrustedText` (`UntrustedText.plain(raw, maxLength)` albo `markdown(...)`: tnie do limitu na granicy słowa, żeby cięcie nie utworzyło
na końcu tokenu, którego filtr nie widział (`hello @allison` do 10 znaków to `hello`, `truncated=true`; w długim ciągu bez spacji
odpada końcowy ciąg liter, cyfr, `-`, `.` i znaków łączących, więc po `(((...(@allison` zostaje `(((...(@`, a po `/@all-hands`
zostaje `/@`; jeśli odpadłoby więcej niż 64 jednostki albo przed końcem nie ma ogranicznika, cięcie zostaje na limicie
(zdanie CJK po jednym nawiasie nie znika; jedno długie słowo daje prefiks z `truncated=true`, nie pusty tekst;
ujemny limit w `cut` to `IllegalArgumentException`);
konstruktor normalizuje tekst przez `TextHygiene.normalize`, a usunięty ciąg niewidocznych znaków zastępuje jedną spacją,
żeby nie skleić tokenów). Serwer, który usuwa z tekstu składnię (obrazy, linki, makra, wzmianki), najpierw zamienia format
na tekst (w tym dekoduje encje `&#8203;`), normalizuje **przed** tym filtrem, tnie `UntrustedText.cut(tekst, limit)`
**przed** filtrem (cięcie może zdjąć domykający ogranicznik bloku kodu, a tylko filtr po cięciu widzi to, co dostanie klient)
i buduje wartość przez `plainCut(tekst, truncated)` albo `markdownCut(...)`; fabryka `plain|markdown(raw, limit)` tnie sama.
Fabryka loguje WARN `event=untrusted_text_not_normalized` (bez `null` i bez zmiany samych końców linii), gdy tekst zmienił się jeszcze po filtrze. Wynik narzędzia większy niż
`aiwcorpo.mcp.platform.tools.max-result-size` (domyślnie 1MB, bajty UTF-8) to `internal_error`; narzędzie zwracające listy
stronicuje. Wynik musi być rekordem; starter publikuje wtedy `outputSchema`
z definicją `UntrustedText` z kontraktów (wchodzi do `definitionHash`) i zwraca wynik także jako `structuredContent`.
Pola dodatkowe systemu idą obok `UntrustedText` w rekordzie wyniku.

**Zasada nullowalności wyniku.** Schemat wyniku wynika z typu rekordu: komponent bez `@Nullable` (dowolnej biblioteki)
jest `required` i nie przyjmuje `null`, komponent z `@Nullable` przyjmuje `null`. Pole, które system potrafi zostawić
puste (`assignee`, `dueDate`, `resolution`), musi więc mieć `@Nullable`; nazwy pól to nazwy komponentów (adnotacje
Jacksona `@JsonProperty`, `@JsonIgnore` nie są uwzględniane w schemacie). Opakowanie narzędzia sprawdza wynik względem
schematu **przed** zapisem audytu: niezgodność to C10 `internal_error`, zdarzenie C8 z `outcome=error` i wiersz
ERROR z ścieżką pola (`/assignee (type)`), bez wartości. Komunikat SDK (`output validation failed`) nie dochodzi do klienta.

**Audyt odrzuceń.** Odrzucone żądanie (brak/zły JWT, certyfikat, niedostępny JWKS albo Redis) zostawia w audycie C8
zdarzenie `authentication` (`outcome=denied`, `reason` = kod odrzucenia) tylko z pól dozwolonych w kontraktach 2.2.5
(CC-071, CC-120), bez `sub`, `client`, `tool` z niezweryfikowanego tokenu. Powody to kody z wektorów JWT oraz
`client_certificate_rejected` (certyfikat gateway'a) i, z `errorCode: upstream_unavailable` (odpowiedź 503),
`jwks_unavailable` i `replay_store_unavailable` (magazyn `jti`). Zdarzeń dla żądań, których tokenu nie da się sparsować (`missing_token`: brak lub pusty nagłówek; `malformed`: obcy schemat, nie trzy segmenty, nagłówek JWT nie jest obiektem), proces zapisuje (jako zdarzenie i jako linię logu), osobno dla każdego z dwóch powodów, najwyżej
`aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second` na sekundę (domyślnie 20, minimum 1; gateway ma ten sam budżet dla samego `missing_token`);
nadmiar nie trafia do audytu ani do logu, tylko do licznika `mcp_audit_authentication_suppressed_total{component="server",reason}` (nazwa wspólna z brokerem i gateway'em, CC-143), a żądanie
dostaje 401 jak zawsze. Żądanie odrzucone `400` (treść nie jest jednym obiektem JSON) albo `413` (za duża) przed weryfikacją tokenu nie jest zdarzeniem `authentication` (CC-152). Pozostałe powody nie są ograniczane, także token z `crit` albo `typ` innym niż `JWT` w nagłówku po rozwiązaniu `kid` (raportowany jako `alg_not_allowed`).

Gateway usuwa `confirmation_code` z argumentów przed wywołaniem serwera (C11), więc schemat serwera go nie
deklaruje.

## Customizer serwera MCP

Spring AI przyjmuje jeden `McpSyncServerCustomizer`, a gdy istnieje, pomija własne `immediateExecution(true)`
(narzędzie na wątku żądania, gdzie żyją `CallerContext` i `CredentialContext`). Starter dostarcza więc ten jeden
customizer (`PlatformMcpServerCustomizer`, `@Primary`), który:

1. wywołuje customizery serwera (każdy inny bean `McpSyncServerCustomizer`, w kolejności `@Order`),
2. na końcu wymusza ustawienia platformy: `immediateExecution(true)` i `validateToolInputs(false)`; customizer
   serwera nie może ich cofnąć.

Serwer dopisuje swoje ustawienia zwykłym beanem `McpSyncServerCustomizer` **bez `@Primary`**. Customizer z
`@Primary` zatrzymuje start (`ForeignPrimaryCustomizerCheck`, na definicjach beanów, z komunikatem co zrobić).

## Zasoby i polityki

Serwer ocenia politykę na **zasobach**, tak jak gateway ocenia ją na narzędziach: z tej samej podpisanej paczki Cedar
(C7), tym samym entity builderem (`mcp-cedar-entities`), z tymi samymi wektorami kontraktów. Nic z tego nie wymaga kodu
w narzędziu poza deklaracją, co jest zasobem.

### Paczka polityk

`PolicyEngine` pobiera `latest.json` (z `ETag`, co 30 s) i pliki, które wskazuje (limit 16 MiB, bez przekierowań, nazwy
plików tylko obok `latest.json`), a `PolicyStore` z `mcp-policy-bundle` sprawdza podpis Ed25519 (klucz `test-*` i
opublikowane klucze testowe tylko z `allow-test-keys` w `dev` albo `devstack`; identyfikator klucza `dev-` tylko w trybie dev), ścisłą
kolejność rewizji, schemat równy schematowi kontraktów (**bajt w bajt: od kontraktów 4.0.0 inny niż w 3.1.x** (CC-136), więc paczka zbudowana na
schemacie 3.1.x jest odrzucana jako `schema_mismatch` i zostaje poprzednia; pierwsza taka paczka nie pozwala wstać serwerowi), polityki względem schematu i `static.json`, po czym podmienia migawkę atomowo. Rewizja niższa albo równa
załadowanej jest odrzucana, także po restarcie (`state-dir`, CC-066).

- Błąd (zły podpis, starsza rewizja, uszkodzona zawartość, repozytorium niedostępne) **zostawia poprzednią paczkę**:
  log `event=policy_bundle_rejected channel=main revision=... reason=bad_signature` albo `event=policy_bundle_fetch_failed`,
  licznik `mcp_server_policy_bundle_rejected_total{channel,reason}` albo `mcp_server_policy_bundle_fetch_failed_total{channel,reason}`.
  Odrzucona rewizja jest zgłaszana raz, nie przy każdym pobraniu.
- Start bez paczki kończy się odmową. Bez żadnej polityki serwer nie podejmuje decyzji: wywołanie wymagające decyzji
  dostaje `upstream_unavailable` (tekst `Policies are not available. Try again later.`), nigdy zgody (CC-141). Audyt: `policy_decision`,
  `decision: deny`, `outcome: error`, powód `policy_unavailable` (brak paczki: np. czekamy na paczkę awaryjną po restarcie) albo `policy_error`
  (silnik polityk zawiódł na tym żądaniu).
- Paczka awaryjna (`overrides`, osobny klucz, tylko `forbid`, co 5 s) jest ładowana na wierzch. Jeśli przed restartem była
  aktywna, a po restarcie jej nie ma, wywołania są odmawiane do `overrides-grace` (potem serwer działa i loguje
  `event=policy_overrides_missing_alarm`): reguły wyłącznika nie znikają po cichu.
- `prv` w tokenie nowszy niż załadowana rewizja zleca natychmiastowe pobranie (najwyżej co 2 s, na wątku silnika: wywołanie z hintem
  nie czeka i jest oceniane załadowaną paczką), więc następne wywołania nie są odmawiane tego, co gateway właśnie dopuścił. Każde żądanie
  do repozytorium ma limit czasu na całość, z treścią (`read-timeout`), a kanały `main` i `overrides` są pobierane niezależnie. `prv` `00000000000000-0000000` (gateway bez paczki) niczego nie wymusza.

### Typ poświadczenia w żądaniu Cedar (CC-136)

Każde żądanie Cedar serwera (`Action::"call"` na narzędziu, odczyt i zapis na zasobie) ma `context.credentialType`: **typ domyślny** serwera, czyli pierwszy z
`manifest.accepted-credential-types` (CC-166, CC-168). Reguły takie jak "konto serwisowe czyta kolejkę Service Desku tylko w przyznanym zakresie" muszą się stosować także wtedy,
gdy atrybutu by brakowało, więc zbiór typów jest albo samym `service-account`, albo samymi typami użytkownika: mieszany zbiór ukryłby konto serwisowe przed regułami, dlatego
serwer z takim zbiorem nie wstaje. `ResourcePolicy` utworzona bez typów rzuca wyjątek; `EntityBuilder.withCredentialType` z wartością spoza czterech typów (`user-oauth`,
`user-secret`, `token-exchange`, `service-account`) kończy wywołanie `internal_error`.

**Sprawdzenie nagłówka (CC-166).** Żądanie z `X-MCP-Credential` musi nazwać w `X-MCP-Credential-Type` typ ze zbioru `accepted-credential-types` serwera. Brak nagłówka, nieznana wartość
(porównanie dokładne: `User-OAuth` jest nieznane), wartość spoza zbioru albo serwer bez skonfigurowanego typu to odmowa: `internal_error` na zewnątrz (błąd konfiguracji, nie winy wywołującego),
`ERROR event=credential_type_mismatch` w logu i `policy_decision` z powodem `credential_type_mismatch` w audycie, **przed** Cedar, sondą grantu i resolverami. Żądanie bez `X-MCP-Credential`
nie jest sprawdzane. Serwer, który przyjmuje `[user-oauth, user-secret]` (GitLab), przepuszcza oba typy; wektory `credential-type-header/` (strona serwera) przechodzą w `CredentialTypeHeaderVectorsTest`.
Poświadczenie wysłane bez użytecznego typu nie trafia do `CredentialContext.require` (narzędzie go nie dostaje), a jego wartość jest nadal usuwana z logów i błędów.

### Jedna odmowa dla zasobów i kolejność ocen (CC-146)

`not_found_or_denied` ma zawsze jeden tekst, `The resource does not exist or you do not have access to it.` (stała w schemacie C10): starter ustawia go
niezależnie od tekstu, który napisała mapa błędów serwera, więc treść odmowy nie zdradza, czy zasób istnieje. Kolejność: najpierw grant. Przed
wywołaniem resolvera, przed budową encji z danych systemu i przed odczytem klasyfikacji starter ocenia politykę na **sondzie**: zasobie z samego
identyfikatora argumentu (i jego kontenera, który identyfikator nazywa: projekt klucza zgłoszenia, grupa ścieżki GitLaba) z najmniej restrykcyjną
klasyfikacją (`category: ""`, puste `labels` i `tags`). Odmowa sondy to `not_found_or_denied` bez wywołania systemu; zgoda przepuszcza do pełnej
oceny po rozwiązaniu. Dzięki temu wywołujący bez grantu na typ nie dostaje `internal_error` (brak wymaganej klasyfikacji) ani `upstream_unavailable` z
resolvera, które zdradziłyby istnienie zasobu. W audycie odmowa sondy ma powód `policy_forbid` (odmówiła polityka: `forbid`, brak `permit` albo zawężenie linku).
**Ograniczenie sondy:** zakłada, że polityka jest monotoniczna w klasyfikacji, czyli klasyfikacja tylko zawęża (reguły `forbid`, jak w przykładach kontraktów). Reguła, która przyznaje
dostęp dopiero przy wybranej klasyfikacji (`permit ... when resource.category == "public"`), odrzuciłaby sondę z `category: ""` mimo grantu; i odwrotnie, sonda z kontenera z identyfikatora
odmówi zgłoszenia przeniesionego do projektu, na który użytkownik ma grant, a na stary projekt klucza nie ma. W obu przypadkach odmowa jest bezpieczna (fail-closed), ale nieprecyzyjna.
**`Confluence::Page` i `ServiceDesk::Ticket`** (kontener nie wynika z identyfikatora) nie mają sondy z identyfikatora. Grant to, wg entity-builder.md 9b.2, zakres w paczce **albo** polityka,
która przyznaje dostęp. Wywołujący z jakimkolwiek zakresem kontenera (grupa `mcp-confluence-space-*`, `mcp-servicedesk-queue-*`) idzie dalej do resolvera i pełnej oceny. Wywołujący **bez zakresu** jest
oceniany przez politykę na stronie albo zgłoszeniu w kontenerze, którego żaden zakres nie obejmuje (sentinel): odmowa (konto serwisowe z `credential-type.cedar` czyta tylko przyznane kolejki) to
`not_found_or_denied` przed resolverem, a zgoda (odczyt z `read.cedar` według praw użytkownika w systemie, wektor `srv-servicedesk-user-secret-not-restricted-by-grants`) przepuszcza do pełnej oceny.
Zasób, który system zwrócił poza zakresami wywołującego i którego nie da się ocenić (brak klasyfikacji, zły identyfikator, brak kontenera), daje `not_found_or_denied` zamiast `internal_error`.
Przy koncie serwisowym błąd resolvera dla strony albo zgłoszenia też wygląda jak brak zasobu (z wyjątkiem braku poświadczenia); przy poświadczeniu użytkownika zostaje `upstream_unavailable`, bo
system sam ogranicza to, co użytkownik widzi. Zasób z argumentu poza zakresem linku (reguły `vs-narrow-*` z `virtual-server.cedar`, także `vs-narrow-call-*` na poziomie narzędzia) to `not_found_or_denied` z tym samym tekstem co brak
zasobu (CC-146c), nie `access_denied`; powód w audycie zostaje `link_scope`. Rozpoznanie opiera się na identyfikatorach reguł rozstrzygających (prefiks `vs-narrow-`): paczka polityk musi je zachować. Wektory `servicedesk-service-account/`
przechodzą na `authorizeCall` (`ServiceDeskVectorsTest`).

### `@ResourceRef` i `ResourceResolver`

```java
@Component
class JiraIssueResolver implements ResourceResolver<JiraIssue> {
    public String resourceType() { return "Jira::Issue"; }

    public Optional<JiraIssue> fetch(String key) {          // wołanie Jiry poświadczeniem wywołującego
        return jira.issue(key);                              // pusty dla 404 i 403; wyjątek dla awarii systemu
    }

    public ResolvedResource describe(JiraIssue issue) {      // kanoniczny id, rodzic i klasyfikacja Z SYSTEMU
        return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key()))
                .withParent(ResolvedResource.of("Jira::Project", issue.projectKey(),
                        Map.of("key", issue.projectKey(), "category", issue.projectCategory())));   // "" gdy brak
    }
}

@Tool(name = "get_issue", description = "Returns an issue by key.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Get issue")
public IssueView getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                          @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
```

Przed uruchomieniem narzędzia starter, w tej kolejności i z jednej paczki:

1. `Action::"call"` na narzędziu, zbudowane z claimów tokenu (`sub`, `grp`, `azp`, `mfa`, `vs` i `vsc`) i argumentów tak
   jak robi to gateway. Odmowa: `access_denied`, zdarzenie `policy_decision` w audycie.
2. Dla argumentów z `@ResourceRef` **każda wartość każdego argumentu** (każdy element listy; źródło i cel transferu) jest
   rozwiązywana przez resolver i oceniana akcją narzędzia (`read`, `update`, `delete`, `admin`) na zasobie takim, jakim
   zwraca go system. Pierwsza wartość, której nie da się rozwiązać albo której polityka nie dopuszcza, kończy wywołanie
   `not_found_or_denied`: ta sama odpowiedź, co dla zasobu, którego nie ma. Narzędzie, które deklaruje `@ResourceRef` i nie
   dostało żadnej wartości, jest odrzucane (puste `resourceRefs` na bramce nie oznaczają "wszystko wolno").

Zasady resolvera (GitLab: `attrs.ancestorClassifications`, mapa `<ścieżka grupy-przodka> -> topics` dla projektu i dla jego grupy-rodzica,
CC-129; opis w `MIGRATION-3.0.md`): id i rodzic w wyniku pochodzą z systemu, nigdy z argumentu (zgłoszenie `ABC-123` przeniesione do `HR` ma
projekt `HR`, a jego klasyfikacja `restricted` zamyka dostęp mimo argumentu, który wygląda na `ABC`); `Jira::Project.category`
(`""` gdy brak), `Confluence::Space.labels` i `OpenSearch::Index.tags` są obowiązkowe, a zasób bez nich nie jest budowany: wywołanie
kończy się `internal_error` (kontrakt, entity-builder.md sekcja 6; w logu `event=resource_refused reason=evaluation_error type=...`), a
element wyniku jest usuwany. To samo dla id niezgodnego ze wzorcem typu i dla zasobu innego typu niż w `@ResourceRef`.
Resolver działający kontem serwisowym (OpenSearch) zwraca domyślnie `tags: []`, nie brak atrybutu: brak daje `internal_error`, czyli odpowiedź
różną od `not_found_or_denied` dla indeksu, którego nie ma, i zdradza istnienie indeksu. `ResolvedResource.of` pomija atrybuty o wartości `null` (brak atrybutu), a `Map.of(..., null)` rzuca NPE: użyj `HashMap` albo
`""` dla kategorii projektu. `fetch` zwraca pusty `Optional` dla "nie ma" i "nie wolno użytkownikowi"
(404 i 403), a **rzuca** dla awarii systemu: to `upstream_unavailable` (albo `credential_required`), nie odmowa. Wynik
`fetch` i `describe` jest w pamięci podręcznej na czas wywołania; narzędzie czyta go z
`ResourceContext.required().fetched("Jira::Issue", key, JiraIssue.class)` bez ponownego wołania systemu (w teście jednostkowym,
który woła metodę narzędzia bez platformy: `TestResourceContext.open(...)` z `mcp-platform-test`). Identyfikator musi pasować do
wzorca typu z kontraktów (np. klucz zgłoszenia Jiry `ABC-123`, nie `ABC-SLOW`); niepasujący to `invalid_argument` przed
jakimkolwiek wołaniem resolvera. Wyniki listy rozwiązuje jedno wołanie `fetchAll(ids)` (domyślnie pętla po `fetch`; nadpisz
je wołaniem zbiorczym systemu).
Resolver jest beanem; przy jednym resolverze typu `@ResourceRef(type = ...)` wystarcza, przy kilku `resolver = X.class`.
Brak resolvera, zły typ albo niejednoznaczność zatrzymują start.

Typy dozwolone w `type`: `ResourceType` kontraktów (`Jira::Project`, `Jira::Issue`, `Confluence::Space`, `Confluence::Page`,
`GitLab::Group`, `GitLab::Project`, `OpenSearch::Index`, `ServiceDesk::Queue`, `ServiceDesk::Ticket`). Parametr to `String`,
liczba całkowita albo lista lub tablica takich wartości. Akcja Cedar narzędzia to `@ToolRisk(action = ...)`; domyślnie `read`
dla `READ_ONLY`, `update` dla `UPDATE`, `delete` dla `DESTRUCTIVE`, `ToolAction.ADMIN` ustawia się jawnie. Akcja sprzeczna z
ryzykiem zatrzymuje start.

### Wyniki: `ResultFilter`

Rekord zwracany z narzędzia z komponentem `@ResourceRef` jest sprawdzany po wykonaniu narzędzia: każdy element **listy** o takim
komponencie jest rozwiązywany tym samym resolverem (jedno `fetchAll` na listę) i oceniany, a niedozwolone elementy są usuwane z
wyniku (to nie błąd wywołania; zdarzenie `policy_decision` dla pierwszych 20, licznik w logu `result_elements_removed`). Element,
którego nie da się rozwiązać (awaria systemu, brak), też jest usuwany. Rekord, który nie jest elementem listy, a nie jest
dozwolony, kończy wywołanie `not_found_or_denied`. Lista dłuższa niż `tools.max-filtered-elements` (200) kończy wywołanie
`internal_error`: zwracaj strony. Liczników z systemu (`total`) starter nie poprawia. Wynik narzędzia zapisującego jest oceniany
akcją narzędzia, a odmowa wyniku nie cofa zmiany, która już zaszła.

```java
public record IssueHit(@ResourceRef(type = "Jira::Issue") String key, String summary) {}
public record SearchResult(List<IssueHit> hits, int total) {}
public record Page<T>(List<T> items, int total) {}       // Page<IssueHit>, Optional<List<IssueHit>>, IssueHit[] też
```

**Co starter potrafi śledzić:** rekordy (także generyczne, rozwiązywane z typu zwracanego), listy, zbiory, tablice i `Optional`,
w dowolnym zagnieżdżeniu. **Czego nie:** `Map`, klas, które nie są rekordami, `Object`, `JsonNode`, `Stream`, rekordów z adnotacjami
Jacksona (`@JsonProperty`, `@JsonUnwrapped`, `@JsonIgnore`, ...: zmieniają nazwy pól, pod którymi filtr szuka danych). Wynik z taką częścią
zatrzymuje start (komunikat podaje metodę i część), chyba że metoda ma `@ResultWithoutResources`: wynik nie zawiera zasobów albo
narzędzie filtruje je samo przed zwróceniem. JSON z polem, którego nie ma wśród komponentów rekordu (globalna strategia nazw w
Jacksonie), kończy wywołanie `internal_error` zamiast przepuścić nieprzefiltrowane dane. **Wynik, który sam jest tekstem
(`String`, `CharSequence`, `byte[]`, także w `Optional` albo liście), też wymaga `@ResultWithoutResources`**: mógłby być gotowym JSON-em
(odpowiedź wyszukiwania przekazana dalej), w którym platforma nie widzi zasobów. Narzędzie zwraca typowane rekordy; tekst zwykły
(komunikat, numer) dostaje adnotację. Klucz `Map` niebędący skalarem liczy się jak część nieśledzona. Ręcznie napisany
`ToolCallback` (nie metoda `@Tool`) deklaruje to samo przez `RiskClassifiedTool#resultWithoutResources()` albo nie wystartuje.
Pozostałe znane granice: słowa `AND`, `OR`, `NOT` w wartości `CONTAINS` działają jako operatory wyszukiwania tekstowego Jiry/Confluence
(w obrębie tego jednego pola, nie poszerzają zakresu); `IS EMPTY`, `membersOf(...)` i funkcje dat nie da się wyrazić w
`StructuredQuery` (kontrakt zamyka listę pól i operatorów; nowe pole wymaga jego zmiany).

Dla wyniku budowanego inaczej wstrzyknij `ResourcePolicy` (implementuje `ResultFilter`): `retainAllowed(items, item -> resolvedResource)` albo
`retainAllowedById("Jira::Issue", items, Item::key)`; `allows(resource)` i `require(resource)` dla zasobu, który narzędzie znajduje samo
(projekt, w którym tworzy zgłoszenie). `@ResourceRef` wolno dać tylko na parametrze metody `@Tool` i na komponencie rekordu wyniku;
na komponencie rekordu będącego argumentem zatrzymuje start (byłby pominięty, a narzędzie działałoby bez kontroli).

### `QueryScope` i `StructuredQuery`: zapytanie buduje serwer

Model **nie podaje tekstu zapytania**. JQL i CQL budujesz z ustrukturyzowanych warunków; starter składa je tak jak odniesienie z kontraktów
(`scripts/lib/query-rendering.mjs`, wektory `test-vectors/query-rendering/`, CC-160): warunki użytkownika w **jednym nawiasie**, zakres po nim przez `AND`, więc
`OR` w wartości nie poszerza zakresu; każda wartość to jeden literał w cudzysłowie (`\` i `"` poprzedzone `\`, znak sterujący odrzucony);
wartość nie zamknie cudzysłowu ani nawiasu, nie wywoła funkcji (`currentUser()`), nie doda `OR project = HR`.

```java
QueryScope scope = policy.queryScope("Jira::Project");     // zakres z tokenu (link vsc) i grup z paczki
String jql = scope.jql(Set.of("status", "assignee", "text"))   // pola warunków: podzbiór listy z kontraktu
        .where("status", Operator.EQUALS, filter.status())        // null = warunek pominięty
        .where("text", Operator.CONTAINS, filter.text())
        .orderBy("updated", Direction.DESC).build();
// (status = "Open" AND text ~ "x") AND project in ("ABC", "DEF") ORDER BY updated DESC
```

- **Listy pól i operatorów są normatywne** (kontrakt 4.0.0): warunki JQL `text`, `status`, `assignee`, `summary`; `ORDER BY` JQL `created`, `updated`, `priority`,
  `key`; warunki CQL `text`, `title`, `label`; `ORDER BY` CQL `created`, `lastmodified`, `title`; operatory `EQUALS` (`=`), `CONTAINS` (`~`), `NOT_EQUALS` (`!=`).
  Serwer może dopuścić mniej (`scope.jql(fields)` z podzbiorem; `scope.jql(fields, orderFields)` zawęża też `ORDER BY`), nigdy więcej: pole spoza listy to
  `IllegalArgumentException` już przy tworzeniu buildera (stałe `StructuredQuery.JQL_CONDITION_FIELDS` itd.). Nowe pole wymaga zmiany kontraktu. Jedno `ORDER BY` na zapytanie;
- wartość: 1 do 1000 znaków, bez znaków zakazanych przez higienę tekstu (CC-121) i bez znaków sterujących, do 30 warunków; naruszenie to
  `IllegalArgumentException` (platforma zgłasza `invalid_argument`). Znaków Lucene (`* ? ~ ( ) - + ! ^ : / & |`) w wyszukiwaniu tekstowym starter nie escapuje, tak jak odniesienie
  kontraktu (`query-rendering.mjs`; decyzja koordynatora). Nie poszerza to zakresu: literał kończy tylko nieescapowany `"`, a `\` i `"` są escapowane, więc składnia Lucene działa wyłącznie wewnątrz
  warunku `text ~ "..."`, który jest koniunkcją z zakresem (`project in (...)`, `space in (...)`) i z uprawnieniami użytkownika w systemie. Zmienia się tylko semantyka wyszukiwania
  (`*` i `?` jako wildcard, `foo-bar` jako wykluczenie, `x~` jako fuzzy); gdy kontrakt tego zażąda, escapowanie dojdzie z nową wersją odniesienia;
- `scope.cql(Set.of("title", "label", ...))` dla Confluence (zawężenie do przestrzeni linku); `scope.jql(fields, true)` i
  `scope.cql(fields, true)` (typ `Confluence::Space`) dodają zakresy z grup (konto serwisowe; rzucają, gdy grup brak: pusty zakres to brak zapytania, nigdy zapytanie bez filtra);
- **Zawężenie do projektu albo przestrzeni z argumentu:** `project` i `space` nie są polami warunku (listy normatywne), a serwer nie powinien pobierać 100 wyników, żeby oddać 25.
  `scope.narrowedTo(ids)` (Jira: klucze projektów, Confluence: klucze przestrzeni; ids z argumentu będącego `@ResourceRef` typu kontenera, który polityka oceniła przed narzędziem) zwraca zakres
  będący **przecięciem argumentu z linkiem** (bez linku: argumentem), renderowany w części zakresu zapytania po warunkach (`(text ~ "x") AND project in ("DEF")`), nie jako warunek. Zawężenie
  tylko zmniejsza zakres: id spoza linku odpada, puste przecięcie to `nothingToQuery()` (pusty wynik albo `requireQueryable()` przy pobraniu po id), a przy zapytaniu ograniczanym grantami
  (`jql(fields, true)`) argument bez wspólnych elementów z grantami to wyjątek, jak pusty grant. Identyfikator spoza wzorca kontraktu to `IllegalArgumentException`, nigdy tekst zapytania. Mieści się w
  kontrakcie `query-rendering`: to lista zakresu renderowana jak `scope` odniesienia (grupa warunków w nawiasie, potem `AND project in (...)`), nie nowe pole ani operator.
- `jqlProjectClause()`, `cqlSpaceClause()` zostają jako gotowe fragmenty (identyfikatory cytowane przez `QueryScope.quote`, który odrzuca znaki sterujące);
- **Zakres wyklucza wszystko (CC-147):** `scope.nothingToQuery()` (OpenSearch: żaden indeks nie jest jednocześnie przyznany i w linku; pozostałe typy: brak grantów, dla zapytań
  ograniczanych grantami) znaczy: wyszukiwanie i lista zwracają **pusty wynik bez wywołania systemu** (to nie błąd), a pobranie jednego zasobu po id woła
  `scope.requireQueryable()`, które kończy się `not_found_or_denied`, tak samo jak dla zasobu, którego użytkownik nie widzi;
- **OpenSearch (CC-160): nazwy, nie wzorce.** `scope.opensearchIndexExpression()` (pary `p` i `p-*`, nigdy `p*`) służy **wyłącznie** do `GET _resolve/index/<wyrażenie>`; wyszukiwanie,
  `_count` i agregacje idą do **listy konkretnych nazw**, bo wzorzec `p-*` obejmuje też alias o takiej nazwie, który może wskazywać indeksy innych zespołów.
  `scope.resolveIndexNames(odpowiedź)` bierze z odpowiedzi tylko wpisy `indices` (aliasy i strumienie danych nigdy, jakąkolwiek mają nazwę), zostawia nazwy równe prefiksowi
  albo zaczynające się od `<prefiks>-` (w obrębie linku) i zwraca je posortowane; pusta lista znaczy: nie wykonuj zapytania. Indeks podany jako argument:
  `scope.resolveIndexArgument(argument, odpowiedź)` rzuca `invalid_argument` dla wzorca, listy po przecinku, `_all` i nazwy z kropką na początku (przed jakimkolwiek wywołaniem systemu), a
  `not_found_or_denied` dla aliasu, strumienia, indeksu poza zakresem i nazwy nieznanej systemowi. Do żądań dodaj `QueryScope.OPENSEARCH_SEARCH_PARAMETERS`
  (`expand_wildcards=open`, `ignore_unavailable=true`, `allow_no_indices=true`). Między rozwiązaniem a zapytaniem jest krótkie okno (administrator mógłby zamienić indeks na alias o tej samej nazwie):
  ryzyko znane i przyjęte w kontrakcie; **nie cache'uj odpowiedzi `_resolve/index` między wywołaniami narzędzia**, bo wtedy okno trwa tyle co TTL. Cel wyszukiwania, `_count` i agregacji
  daje `scope.opensearchSearchTarget(odpowiedź)`: puste `Optional` znaczy "nie wykonuj zapytania" (żądanie z pustym celem przeszukuje wszystkie indeksy, więc nie sklejaj nazw sam).
  Nazwy niezgodne ze wzorcem `OpenSearchIndexName` (np. z `%` albo `=`) są pomijane, więc nazwy są bezpieczne w ścieżce bez kodowania. Do `_resolve/index` dodaj
  `QueryScope.OPENSEARCH_RESOLVE_PARAMETERS` (`expand_wildcards=open`, `ignore_unavailable=true`, `allow_no_indices=true`): bez `ignore_unavailable` brak indeksu o nazwie samego prefiksu (układ z
  samymi `p-2026.10`) kończy całe żądanie 404 `index_not_found_exception`. Odpowiedź 404 mimo to traktuj jak `QueryScope.EMPTY_RESOLVE_RESPONSE` (zabezpieczenie; zachowanie na żywym klastrze do potwierdzenia: ws-01). `QueryScope.requireNoAliases` jest przestarzałe (zapytanie do nazw nie wymaga odmowy). Dzięki temu `aggregate_logs` może wrócić po wdrożeniu tego
  w serwerze OpenSearch (reguła wyłączająca w paczce polityk znika dopiero wtedy). DSL zapytania OpenSearch buduj po stronie serwera, nie przyjmuj go od modelu;
- gdzie system sam stosuje prawa użytkownika (Jira, Confluence, GitLab z jego tokenem), wystarczy zawężenie linku; gdzie nie
  (OpenSearch, konto serwisowe) zapytanie ogranicza się do `grantedIds()`, a puste `grantsNothing()` znaczy: nie wykonuj zapytania.
  Ograniczenie zapytania nie zastępuje filtrowania wyniku.

### Audyt

Odmowa polityki to zdarzenie `policy_decision` (C8) z `decision: deny`, `reason` (`policy_forbid`: odmowa polityki, reguła `forbid` albo brak `permit`;
`not_granted`: zasób nierozwiązany albo bez grantu, CC-141), typem i
id zasobu, jeśli id jest bezpieczne w logu, `policyRevision` paczki serwera i `errorCode`; zwykłe wywołanie to `tool_call`.

## Manifest C6 i narzędzia DESTRUCTIVE

`GET /mcp-manifest` (JWT C2 w wariancie użytkownika albo serwisowym, bez `tool`) zwraca manifest serwera. Starter buduje go
po starcie z **tego, co serwer naprawdę publikuje**: definicje to dokładnie wpisy `tools/list` (więc `definitionHash` jest
hashem tego, co widzi klient i co gateway przypina), `risk`, `action` i `resourceRefs` pochodzą z adnotacji, reszta z
`aiwcorpo.mcp.platform.manifest.*`; w profilu `devstack` z `"environment": "dev"` (CC-138). Manifest do rejestracji w gateway'u to artefakt etapu
budowy przeglądanego commitu (`manifest.output-file`, np. `target/mcp-manifest.json`), nigdy odpowiedź działającego serwera (CC-133, CC-140). Przed udostępnieniem manifest przechodzi `server-manifest.schema.json` i reguły
`mcp-manifest` (zamknięty i ograniczony `inputSchema`, `title`, istniejące argumenty `resourceRefs`, hash), czyli kontrole
gateway'a przy rejestracji; manifest, który by ich nie przeszedł, zatrzymuje serwer (w `dev` ostrzeżenie). Z `output-file`
trafia też do pliku dla kroku rejestracji.

Wymagania wobec narzędzia: `@ToolTitle` (CC-115, niepusty, do 100 znaków, bez znaków ukrytych), `@ToolRisk`, schemat bez
`$ref`, typów łączonych i map (`SchemaLimitedToolCallbacks` generuje taki), argument nie nazywa się `confirmation_code`
(gateway dodaje go do `tools/list` i zdejmuje przed wywołaniem). Wzorzec `OpenSearchIndexPrefix` z kontraktów 4.0.0 (CC-139) to jedna zakotwiczona
postać (`^(?:a|b)$`), więc `@ArgumentLimits(contractPattern = "OpenSearchIndexPrefix")` działa bez obejścia; starter niczego nie przepisuje.

**DESTRUCTIVE.** Serwer nie pyta o potwierdzenie (robi to gateway, kodem TOTP, C11); musi tylko prawdziwie powiedzieć, że
narzędzie potwierdzenia wymaga. Jedyny mechanizm to `@ToolRisk(RiskLevel.DESTRUCTIVE)`, a starter pilnuje, żeby ta
deklaracja trafiła wszędzie tak samo: `risk` w manifeście, `destructiveHint: true` i `readOnlyHint: false` w `tools/list`, pole
`risk` w audycie, akcja `delete` (albo jawnie `admin`) w Cedar. Nazwa z `delete`, `drop`, `remove`, `purge`, `truncate`,
`destroy`, `wipe`, `erase` nie przejdzie jako `READ_ONLY` ani `UPDATE`. Przy generowaniu manifestu `risk` i podpowiedzi
`tools/list` są porównywane i rozjazd zatrzymuje serwer.

## Testy serwera bez gateway'a

Testy z `TestGateway` uruchamiaj w profilu `dev` (`@ActiveProfiles("dev")`). Kontrola "`dev` we wdrożeniu" (Kubernetes,
systemd, kontener) nie obejmuje kontekstu budowanego przez Spring TestContext, więc te testy przechodzą także w CI na
Kubernetesie i pod runnerem uruchomionym jako usługa systemd. Aplikacja uruchamiana ręcznie z `SpringApplication` w
takim środowisku nadal jest odrzucana, także w teście. Dlatego test, że `dev` nie nasłuchuje poza pętlą zwrotną, nie
startuje aplikacji z `--server.address=0.0.0.0` (na hoście CI w kontenerze odmowa "wdrożenie" pada przed sprawdzeniem
adresu i komunikatu o adresie nie ma), tylko wywołuje pomocnika, który sprawdza regułę guarda bez zależności od hosta, a
konfigurację samego serwera sprawdza na jego prawdziwym kontekście `@SpringBootTest` w profilu `dev` (ten sam kontekst, co
w pozostałych testach, więc bez kosztu drugiego startu):

```java
package pl.aiwcorpo.mcp.jira.app;   // pakiet i nazwa systemu serwera

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

`ServerTestSupport` to klasa bazowa testów serwera (`@SpringBootTest`, `@ActiveProfiles("dev")`), w tym samym pakiecie.
Przejście z wcześniejszych wersji krok po kroku: `MIGRATION-3.0.md`, sekcja "Jak przejść z alpha-11".

`assertServerIsGuarded(context)` wymaga, żeby w kontekście serwera były wszystkie beany z
`PlatformPresenceCheck.REQUIRED_BEANS` (guard, weryfikator i filtr JWT, post-processor audytu, customizer serwera MCP) i
żeby `server.address` było adresem pętli zwrotnej. Kontekst testowy nie podlega odmowie wykluczenia (slice'y pomijają
platformę celowo), więc bez tego sprawdzenia serwer z `exclude` na `PlatformSecurityAutoConfiguration` przeszedłby wszystkie
swoje testy.

`DevProfileGuardAssertions` (`pl.aiwcorpo.mcp.platform.test`) buduje własne środowisko (bez zmiennych procesu i
właściwości systemowych hosta, z ustawionymi na stałe sygnałami poda i systemd) i woła `PlatformConfigurationGuard`;
`assertDevProfileRefusesAddress(adres)` sprawdza jeden adres.

```xml
<dependency>
  <groupId>pl.aiwcorpo.mcp</groupId><artifactId>mcp-platform-test</artifactId><scope>test</scope>
</dependency>
```

Poniższy test jest w repozytorium jako
`mcp-platform-test/src/test/java/pl/aiwcorpo/mcp/platform/test/readme/JiraToolsTest.java` (z serwerem
`JiraServer` wyżej) i przechodzi przy każdej budowie:

```java
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")                              // klucze testowe, jti w pamięci, bez mTLS: tylko w dev
class JiraToolsTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");   // server-id testowanego serwera

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

    @Test
    void withoutCredentialTheToolAnswersCredentialRequired() {
        try (McpSyncClient client = client(null)) {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("get_issue").arguments(Map.of("issueKey", "ABC-1")).build());

            assertThat(result.isError()).isTrue();
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .contains("\"error\":\"credential_required\"");
        }
    }

    @Test
    void withoutJwtTheServerAnswers401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
```

`TestGateway.asGateway(user, credential)` robi to, co gateway, przy każdym żądaniu HTTP klienta MCP:

- ustawia nowy wewnętrzny JWT (`Authorization: Bearer ...`, podpisany testowym kluczem A z kontraktów) i
  pasujący `X-Request-Id`; przy `tools/call` z `tool` równym `params.name`. Pierwszy argument zmienia claimy
  użytkownika (`username`, `groups`, `virtualServer`, `mfaAgeSeconds`, ...);
- drugi argument to **poświadczenie do systemu docelowego (C3)**, nie token do serwera: trafia dosłownie do
  `X-MCP-Credential` w formacie `<scheme> <value>` (scheme: `Bearer`, `Basic`, `Token`, `ApiKey`), a
  `X-MCP-Credential-Type` dostaje `user-secret` (inny typ: trzeci argument,
  `asGateway(user, credential, BackendCredential.Type.SERVICE_ACCOUNT)`; dostępne `USER_SECRET`, `USER_OAUTH`,
  `TOKEN_EXCHANGE`, `SERVICE_ACCOUNT`). W narzędziu widać je jako `CredentialContext.require(system)`.
  `null` = brak poświadczenia (narzędzie dostanie `credential_required`).

Pojedyncze tokeny do testów odrzuceń: `GATEWAY.user().tool("get_issue").mint().headers()`,
`GATEWAY.service().mint()`, z ustawieniami `audience`, `lifetime`, `requestId`, `jti`, ... Wynik narzędzia
zwracającego `String` przychodzi w `TextContent` jako napis JSON (w cudzysłowach), stąd `contains` w przykładzie.

**Paczka polityk w testach.** `GATEWAY.serverProperties()` zawiera już `policy.*` (adresy, klucz `test-policy-bundle` i
`allow-test-keys`) i `manifest.*`. `TestGateway` serwuje kanał główny i awaryjny z podpisanych paczek z `test-vectors/bundles/files`
kontraktów: `GATEWAY.main().publish("20261026130000-b2c3d4e")` (przykładowe polityki kontraktów, grupy `mcp-jira-read`,
`mcp-jira-write`, `mcp-jira-destructive`, `mcp-jira-proj-ABC`, ...; ta paczka jest domyślna), `publish(rewizja, plikPodpisu)`
(np. `policies-...tar.gz.sig-by-overrides-key` = zły podpis), `offline(true)`, `GATEWAY.overrides().publish("20261026121500-d4e5f60")`
(przed `serverProperties()`, żeby serwer go pobierał). Test zasobu przez prawdziwy transport z resolverem na sztucznym systemie:
`mcp-platform-test/src/test/java/pl/aiwcorpo/mcp/platform/test/resource/ResourcePolicyTest.java`, manifest:
`ManifestTest`. `PolicyEngine.create(policy, null, meterRegistry)` pozwala sprawdzić samo pobieranie paczek (`PolicyEngineTest`).
Przykładowe polityki wymagają w tokenie testowym grupy (`user.groups(List.of("mcp-jira-read"))`), a dla `DESTRUCTIVE` także
`mfaAgeSeconds(60)` (reguła `destructive-requires-fresh-mfa` jest w paczkach testowych kontraktów, nie w produkcyjnej).

## Budowa

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw install          # wymaga Dockera (Testcontainers: Redis z ACL jak w mcp-platform-dev)
```

Serwer importuje BOM i dodaje starter (wersje pilnuje BOM); `<parent>` serwera to
`spring-boot-starter-parent` 4.1.0:

```xml
<dependency>
  <groupId>pl.aiwcorpo.mcp</groupId><artifactId>mcp-platform-bom</artifactId>
  <version>3.0.0-beta-7</version><type>pom</type><scope>import</scope>
</dependency>
```

## Wersjonowanie

SemVer, jedna wersja dla wszystkich modułów. Wydania wstępne: `3.0.0-alpha-N`, `3.0.0-beta-N` (porządek Mavena:
`alpha-1 < alpha-2 < beta-1 < 3.0.0`). Zmiana łamiąca: nowa wersja major i opis w `MIGRATION-<wersja>.md`.
