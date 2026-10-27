# STATUS - mcp-starter-lib (ws-02)

Linia `pl.aiwcorpo.mcp:mcp-platform*` 3.x (niezależna od wersji kontraktów). Kontrakty:
`pl.aiwcorpo.mcp:mcp-contracts:2.0.2` (decyzja koordynatora: ta sama wersja co gateway).

3.0.0-alpha: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-1` (import),
`pl.aiwcorpo.mcp:mcp-platform-starter:3.0.0-alpha-1`, `pl.aiwcorpo.mcp:mcp-platform-test:3.0.0-alpha-1` (test),
w `~/.m2` (`./mvnw install`), tag git `v3.0.0-alpha-1`.

3.0.0-alpha-2: wydane - te same współrzędne w wersji `3.0.0-alpha-2`, tag `v3.0.0-alpha-2`. API bez zmian
względem alpha-1 (zgodne dla ws-03). Zmiany po smoke teście "konsument według README" (FAIL w alpha-1: 404 na
`/mcp`):
- starter ustawia domyślnie (najniższy priorytet) `spring.ai.mcp.server.protocol=STREAMABLE`, `type=SYNC`,
  `streamable-http.mcp-endpoint=/mcp`; test `TransportDefaultsTest` uruchamia serwer bez żadnej z tych
  właściwości;
- README: sekcja "Pierwszy serwer" (rejestracja narzędzi przez `MethodToolCallbackProvider`), pełny test z
  importami, `initialize`, `callTool`, `credential_required` i żądaniem bez JWT -> 401, opis drugiego argumentu
  `asGateway` (poświadczenie C3 do `X-MCP-Credential`); przykład jest kompilowanym testem
  `mcp-platform-test/.../readme/JiraToolsTest.java`.

Wersja Mavena `3.0.0-alpha-1` zamiast `3.0.0-alpha`: kolejne wydania wstępne (`alpha-2`, `beta-1`) sortują się
poprawnie (`ComparableVersion`), a `3.0.0-alpha` byłoby mniejsze od `alpha-1` i niejednoznaczne.

3.0.0-alpha-6: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-6`,
tag `v3.0.0-alpha-6`. API bez usunięć; zgłoszenia pięciu serwerów ws-04 i template'u. Instrukcja dla serwerów
(co usunąć z obejść): `MIGRATION-3.0.md`, sekcja "Zmiany w 3.0.0-alpha-6".

| Punkt | Rozwiązanie | Testy |
| --- | --- | --- |
| 1. SDK waliduje przed audytem | `PlatformMcpServerCustomizer` ustawia `validateToolInputs(false)`; `AuditingToolCallback` waliduje argumenty względem `inputSchema` narzędzia (`ToolInputValidator`, networknt 2020-12: typy, `required`, `additionalProperties`, `pattern`, długości, zakresy, `enum`) po sprawdzeniu powiązania z gateway'em i przed interceptorami. Odpowiedź C10 `invalid_argument` z komunikatem ze schematu (argument zadeklarowany + reguła; bez wartości i bez niezadeklarowanych nazw), zdarzenie C8 `outcome=error`. Fail-closed: poza `dev` brak schematu albo schemat bez `type: object` + `additionalProperties: false` zatrzymuje start (`ToolInputPolicy`); w `dev` ostrzeżenie `tool_schema_open` i egzekwowanie schematu | `ToolInputValidatorTest`, `ToolInputPolicyWiringTest`, `InputValidationTest` (Tomcat, narzędzie `lookup_issue`, zły wzorzec/długość/typ/zakres/nieznany argument/brak wymaganego: C10, brak tekstu SDK, zdarzenie C8, wartość i nazwa nieznanego argumentu nie występują w odpowiedzi ani w logach platformy i Springa) |
| 2. Jeden customizer | starter dostarcza `@Primary McpSyncServerCustomizer`: najpierw inne beany `McpSyncServerCustomizer` serwera (`@Order`), na końcu `immediateExecution(true)` i `validateToolInputs(false)`; customizer serwera nie cofnie ustawień (recenzja bezpieczeństwa: serwer wołający `validateToolInputs(true)` nadal dostaje C10 od startera). Obcy `@Primary` zatrzymuje start (`ForeignPrimaryCustomizerCheck`, na definicjach beanów) | `ServerCustomizerTest`, `InputValidationTest` (z customizerem serwera wołającym `validateToolInputs(true)` i `immediateExecution(false)`) |
| 3. Limity w schemacie | `pl.aiwcorpo.mcp.platform.input.ArgumentLimits` (`minLength`, `maxLength`, `pattern`, `contractPattern`, `minimum`, `maximum`) i `SchemaLimitedToolCallbacks.of(...)`: wzorzec template'u, `additionalProperties: false`, `MethodToolCallback`, więc `@ToolRisk` działa; tekst bez `maxLength`, limity nie tego typu, nieznany `contractPattern` zatrzymują start. `contractPattern` czyta wzorzec z `common.schema.json` jara kontraktów | `SchemaLimitedToolCallbacksTest`, `InputValidationTest.theSchemaCarriesTheLimits` |
| 4. Typ poświadczenia w teście | `TestGateway.asGateway(user, credential, BackendCredential.Type)`; wersja dwuargumentowa = `USER_SECRET` | `GatewayTransportTest.theCredentialTypeSetByTheTestGatewayReachesTheTool` (4 typy) |
| 5. `mcp-cedar-entities` w BOM | zarządzana wersja w BOM (`${mcp-contracts.version}`), bez dodawania zależności do startera i serwerów. Decyzja: serwery nie dodają modułu dla wzorców identyfikatorów, bo wciąga natywne `cedar-java` (uber jar z bibliotekami macOS/Linux x86_64/aarch64); wzorce dostają przez `contractPattern`. Moduł wejdzie do startera przy `@ResourceRef`/PolicyEngine (beta) | build BOM |

`./mvnw clean install`: 233 testów (starter 176, mcp-platform-test 57), 0 błędów.

Zmiana zachowania (nie łamie API): serwer, którego narzędzie ma schemat otwarty (poza `dev`), albo który ma
własny `@Primary McpSyncServerCustomizer`, nie wstanie. Schematy z `@Tool` (Spring AI) są zamknięte,
więc `MethodToolCallbackProvider` nadal przechodzi.

Recenzja bezpieczeństwa alpha-6 (MEDIUM, poprawione): (a) customizer serwera nie cofa ustawień platformy
(ustawienia startera są stosowane po customizerach serwera; test z `validateToolInputs(true)`); (b) `$` w
`pattern` w Javie pasuje przed końcowym znakiem nowej linii (`"ABC-1\n"` przechodziło `^[A-Z]+-[0-9]+$`): przy
kompilacji schematu każdy nieucieczkowany `$` poza klasą znaków jest zamieniany na `\z` (znaczenie ECMA 262 bez
flagi multiline); komunikat błędu pokazuje wzorzec oryginalny. Dodatkowo znaki sterujące (C0 poza `\t \n \r`, DEL,
C1) w dowolnym tekście argumentu to `invalid_argument`, a `\t \n \r` także w argumencie z `pattern`, chyba że
wzorzec je jawnie dopuszcza (`\n`, `\r`, `\t`, `\s`, `\x`, `\u`, `\p{Cntrl}`). Testy: `ToolInputValidatorTest`
(`aTrailingLineBreakOrBlankDoesNotSatisfyAnAnchoredPattern`, `dollarIsRewrittenOnlyWhereItMeansEndOfInput`,
`controlCharactersAreRefused...`), `InputValidationTest.trailingLineBreaksDoNotSatisfyTheAnchoredPattern`
("ABC-1\n", "ABC-1\r\n", "ABC-1 ": C10 i zdarzenie C8).

Znane ograniczenia alpha-6: zagnieżdżone obiekty w `inputSchema` nie są sprawdzane na `additionalProperties`;
`pattern` jest wykonywany regexem JDK (po zamianie `$` na `\z`; inne różnice względem ECMA 262, np. `\s`,
`.` i właściwości Unicode, pozostają); log `tool_failed` zapisuje komunikat `invalid_argument` (bez wartości) na poziomie ERROR przy każdym
błędzie modelu; logowanie DEBUG transportu SDK (`io.modelcontextprotocol.spec.McpSchema`) drukuje całe
komunikaty, w tym argumenty, i nie jest pod kontrolą startera (nie włączać na produkcji).

3.0.0-alpha-5: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-5`,
tag `v3.0.0-alpha-5`. API bez zmian. Z recenzji template'u:
- log `tool_failed`: komunikaty pomijane, gdy w łańcuchu jest wyjątek z `org.springframework.web.client`,
  `org.springframework.http.converter`, `com.fasterxml.jackson` albo `tools.jackson` (wcześniej tylko
  `RestClientResponseException`). Testy: `McpErrorMapperTest.anUnparsable200BodyIsNotLoggedThroughConverterOrJacksonMessages`,
  `CredentialLeakTest.anUnparsable200BodyReachesNeitherTheLogNorTheClient` (200 z uciętym JSON-em ze znacznikiem;
  znacznika nie ma w logach na DEBUG ani w wyniku);
- profil `dev` tylko na pętli zwrotnej: starter ustawia w `dev` `server.address=127.0.0.1`, inny adres (np.
  `SERVER_ADDRESS=0.0.0.0`) albo brak adresu zatrzymuje start. Testy: `PlatformConfigurationGuardTest`
  (`devListensOnLoopbackOnly`, `theStarterDefaultsDevToLoopbackAndNothingElse`), `StartupRefusalTest.devOnANetworkAddress`;
  testy z `TestGateway` (profil `dev`, klient na 127.0.0.1) działają bez zmian.
`./mvnw clean install`: 208 testów (starter 159, mcp-platform-test 49), 0 błędów.

3.0.0-alpha-4: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-4`,
tag `v3.0.0-alpha-4`. API bez zmian (serwery przechodzą podbiciem BOM). Zgłoszenia ws-03:
- ciało odpowiedzi błędu backendu w logu: gdy łańcuch wyjątków zawiera `RestClientResponseException`,
  `event=tool_failed` loguje tylko klasy i status HTTP, bez komunikatów żadnego poziomu (opakowania, w tym
  `ToolExecutionException`, kopiują ciało do komunikatu). Testy: `McpErrorMapperTest`
  (`wrappersQuotingAnHttpResponseAreNotLoggedEither`), `CredentialLeakTest.backendErrorBodiesReachNeitherTheLogNorTheClient`
  (backend 401 i 500 z unikalnym znacznikiem w ciele; znacznika nie ma w żadnym logu, root na DEBUG, ani w wyniku);
- system spoza `SystemId` kontraktów: poza `dev` odmowa startu, w `dev` ostrzeżenie
  (`PlatformConfigurationGuardTest.aSystemOutsideTheContractIsRefusedOutsideDevAndOnlyWarnedInDev`).
`./mvnw clean install`: 203 testy (starter 156, mcp-platform-test 47), 0 błędów.

3.0.0-alpha-3: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-3`,
tag `v3.0.0-alpha-3`. Poprawki po recenzji bez kontekstu alpha-1. Publiczne API bez usunięć; zmiana zachowania:
`invalid_argument` z komunikatem tylko dla nowej `InvalidToolArgumentException` (M4), plus nowe odmowy startu
opisane w README i `MIGRATION-3.0.md`.

| Znalezisko | Status | Gdzie |
| --- | --- | --- |
| H1 `@McpTool` / `SyncToolSpecification` omijają opakowanie | naprawione: skaner adnotacji domyślnie wyłączony; bean specyfikacji narzędzia zatrzymuje start (`ToolHintsPostProcessor`); `RegisteredToolsCheck` zatrzymuje start, gdy `McpSyncServer.listTools()` zawiera narzędzie spoza `ToolRiskRegistry` | `ToolBypassTest`, `RegisteredToolsCheckTest` |
| M1 `dev` obok innych profili | naprawione: relaksacje tylko, gdy `dev` jest jedynym aktywnym profilem (`getActiveProfiles()`, profil domyślny się nie liczy); `dev` z innym profilem (także z grupy) zatrzymuje start | `PlatformConfigurationGuardTest` |
| M2 dowolne `unauthenticated-paths` | naprawione: tylko `/actuator/health`, `/liveness`, `/readiness`, inaczej odmowa startu w każdym profilu | `PlatformConfigurationGuardTest` |
| M3 JWKS po http | naprawione: poza `dev` tylko absolutny URL `https` | `PlatformConfigurationGuardTest` |
| M4 echo obcych `IllegalArgumentException`, redakcja postaci zakodowanych | naprawione: echo tylko `InvalidToolArgumentException`, inne IAE stały tekst; redakcja raw, URL-encoded (obie wielkości hex, `+`/`%20`) i JSON-escaped | `McpErrorMapperTest`, `CredentialLeakTest` |
| M5 osobny port managementu | naprawione: poza `dev` `management.server.port` różny od `server.port` zatrzymuje start (filtr nie obejmuje kontekstu managementu) | `PlatformConfigurationGuardTest` |
| L1 401 poza audytem C8 | otwarte: czeka na decyzję w CC (zgłoszone przez ws-02) | - |
| L2 monitor przy odświeżaniu JWKS | naprawione: jedno pobranie naraz, pozostałe żądania z kopią (w granicach `max-stale`) nie czekają | `JwksGatewayKeysTest` |
| L3 konfiguracja TLS | częściowo: poza `dev` wymagane `server.ssl.client-auth=need`; przypięcie CA wystawcy/odcisku certyfikatu nie zrobione (zależne od truststore serwera, opisane w README) | `PlatformConfigurationGuardTest` |
| L4 buforowanie ciała przed weryfikacją JWT | otwarte: zmiana kolejności kontroli wymaga osobnego zachowania kolejności powodów z wektorów; ograniczone przez mTLS (certyfikat gateway'a) i limit 4 MB; na beta | - |
| L5 wymagane pola C10 | naprawione: wynik mappera walidowany `tool-error.schema.json`, niezgodny -> `internal_error` (także `credential_required` bez `connectUrl`) | `McpErrorMapperTest` |
| L6 timeout Redis | naprawione: domyślnie `spring.data.redis.timeout=2s` | `PlatformDefaultsEnvironmentPostProcessor` |

## Zrobione w 3.0.0-alpha-1 (brief: punkty 1, 2, 8 i audyt C8)

- Punkt 1, `InternalJwtFilter` / `InternalJwtVerifier` (C2 z 2.0.2): JWKS gateway'a (cache, odświeżenie na
  nieznany `kid` z limitem, `max-stale`), ES256, `iss`, `aud` == `server-id`, `exp`/`iat`/skew, czas życia,
  claimy walidowane `internal-jwt.schema.json` z jara kontraktów (warianty, `vs`+`vsc`, wzorce identyfikatorów),
  replay `jti` w Redis (`SET NX PX` na `jti:<serverId>:*`, działa z ACL z mcp-platform-dev), `tool` ==
  `params.name` przy `tools/call` i zabroniony przy innych metodach, wariant serwisowy tylko dla `initialize`,
  `tools/list`, `ping`, `GET /mcp-manifest`, `rid` == `X-Request-Id`, odrzucanie kluczy `test-*` i
  opublikowanych kluczy testowych po SPKI (lista z `mcp-contracts/security/published-test-keys.json`),
  `allow-test-keys` tylko w profilu `dev`, fail-closed (503) przy niedostępnym Redis/JWKS, CN certyfikatu
  klienta `mcp-gateway`. Ponowne sprawdzenie powiązania z narzędziem w `AuditingToolCallback`.
- Punkt 2, `CredentialContext` v3: `require(system)` -> `BackendCredential {scheme, value, type}`,
  `applyTo(HttpHeaders)`, `toString()` bez wartości, brak -> `credential_required` z `connectUrl`; nagłówki
  poświadczeń ukryte przed dalszą częścią żądania; wartość usuwana z komunikatów błędów i logów.
- Punkt 8, `auth-mode: dev-pat`: `X-PAT-<system>` tylko z profilem `dev`, inaczej serwer nie wstaje; JWT nadal
  wymagany.
- Audyt C8 (`tool_call`, linia JSON zgodna z `audit-event.schema.json`), `CorrelationIdFilter` na `X-Request-Id`,
  błędy C10 zgodne z `tool-error.schema.json`.
- Moduł `mcp-platform-test` (`TestGateway`) dla ws-03.
- Miejsce na beta: SPI `ToolCallInterceptor` (rozwiązanie zasobu i polityka przed narzędziem, filtr wyników po
  nim), `CallerContext` z polami wejścia entity buildera `mcp-cedar-entities`.

## Dowód

`./mvnw clean install` (JDK 25, Docker), 3.0.0-alpha-3: starter 154 testy, mcp-platform-test 46 testów, 0 błędów (alpha-1: 144 i 39).

- `InternalJwtVectorsTest`: 43/43 wektory `test-vectors/jwt` z jara `test-vectors` 2.0.2, wynik i powód zgodne
  (weryfikator), plus 38 wektorów serwera przez filtr HTTP ze statusem z wektora; test pilnuje, że katalog ma 43
  pliki i że każdy powód odrzucenia ma wektor.
- `GatewayTransportTest` (Tomcat + Spring AI): 401 bez JWT, 401 dla wariantu serwisowego na `tools/call`,
  powiązanie z narzędziem, `rid`, `jti`, czasem życia i `aud`; 26 wariantów ścieżek bez JWT -> 401.
- `CredentialLeakTest`: Bearer i Basic (zakodowany i zdekodowany) nie występują w żadnej linii logu (root na DEBUG,
  łącznie z wyjątkami i MDC) ani w odpowiedzi narzędzia, także gdy backend odsyła nagłówek `Authorization` w ciele
  i nagłówku odpowiedzi 401 (`HttpClientErrorException` z `RestClient`) i gdy kod narzędzia wkłada wartość do
  komunikatu wyjątku.
- `RedisJtiReplayStoreTest` (Testcontainers, `redis:8.8.4-alpine`, ACL jak `redis/start.sh`): jednokrotne użycie,
  TTL, wyścig 64 kopii -> 1 przyjęta, klucz poza prefiksem i Redis niedostępny -> fail-closed.
- `StartupRefusalTest`, `PlatformConfigurationGuardTest`: relaksacje dev poza profilem `dev` i ustawienia 2.x
  zatrzymują start.

## Bezpieczeństwo

- HIGH z automatycznego przeglądu (2026-10-27): ominięcie `InternalJwtFilter` przez ścieżki wyjątków z `/**` i
  różnicę normalizacji ścieżek. Poprawione przed wydaniem: wyjątki to wyłącznie dokładne ścieżki (domyślnie
  `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`; wzorzec w konfiguracji zatrzymuje
  start), porównanie ze ścieżką znormalizowaną przez kontener (`servletPath + pathInfo`, ta sama, której używa
  klasyfikator), a surowe URI z `;`, `..`, `%`, `\` albo `//` nigdy nie jest wyjątkiem i przechodzi pełną
  weryfikację. Testy: `InternalJwtFilterTest` i `GatewayTransportTest.noPathVariantGetsInWithoutJwt` (surowe
  żądania przez gniazdo do uruchomionego Tomcata, m.in. `..;/`, `;x`, `%2e%2e`, `%2f`, `%5c`, `\`, `//`,
  wielkość liter, końcowy `/`).
- Treść żądania musi być jednym obiektem JSON bez powtórzonych kluczy (400): batch JSON-RPC i powtórzony
  `params.name` mogłyby zostać inaczej odczytane przez transport MCP niż przez filtr.

## Odstępstwa i znane ograniczenia

- Walidacja wejścia w MCP SDK 2.0.x była przed audytem (tekst spoza C10, bez C8): od alpha-6 wyłączona, walidację
  robi starter (patrz alpha-6).
- Odrzucenia uwierzytelnienia są w logu aplikacji, nie w audycie C8 (brak typu zdarzenia, CC zgłoszone).
- Wariant serwisowy tylko dla czterech żądań z C2 (dosłownie): `notifications/initialized` i `GET`/`DELETE` sesji
  wymagają wariantu użytkownika (CC zgłoszone; ws-05 i tak wysyła wariant użytkownika).
- Brak powiązania sesji MCP z `sub` po stronie serwera (C2 nakłada to na gateway).
- Brak własnego health indicatora magazynu `jti`; zdrowie Redisa ze Spring Boot wyłączone domyślnie (komenda
  `INFO` poza ACL).
- `mcp-platform-test` zależy od jara `test-vectors` (klucze testowe) w zakresie compile modułu; serwery dodają
  moduł wyłącznie w `test`.

## Zostaje na beta (punkty 3, 4, 6, 7) i 3.0.0 (punkt 5)

`@ResourceRef` + `ResourceResolver`, `PolicyEngine` na `PolicyStore` z `mcp-policy-bundle` 2.0.2 (z `isReady`,
osobny katalog stanu na instancję), `EntityBuilder` z `mcp-cedar-entities`, `QueryScope` (`queryConstraint`),
generator manifestu C6 (`mcp-manifest`) i `GET /mcp-manifest`; `ResultFilter` (`allowed` dla list, `checkAll` dla
narzędzi z identyfikatorami) w 3.0.0, dokończenie `MIGRATION-3.0.md`. Usunięcia z briefu zrobione w całości w alpha.

## Propozycje zmian kontraktu (mcp-contracts/CONTRACT_CHANGES.md, status open, zgłasza ws-02)

- C2: wariant serwisowy a `notifications/*` i żądania transportu (GET/DELETE sesji).
- C8: zdarzenie audytu dla odrzuconego uwierzytelnienia na serwerze.
- C6/C10: zamknięty `inputSchema` i treść `invalid_argument` przy naruszeniu schematu (CC-???, alpha-6).
- C6: nazwy `$defs` w `common.schema.json` jako stabilne API dla serwerów (`contractPattern`) (CC-???, alpha-6).
