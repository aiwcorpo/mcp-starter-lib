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

`./mvnw clean install` (JDK 25, Docker): starter 144 testy, mcp-platform-test 43 testy (alpha-1: 39), 0 błędów.

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

- Walidacja wejścia narzędzi w MCP SDK 2.0.0 (`ToolInputValidator`) odpowiada przed wywołaniem narzędzia tekstem
  `Tool (...) input validation failed: ...` z `isError: true`, który nie jest obiektem C10 (bez wycieku: treść
  pochodzi z argumentów klienta i schematu). Do poprawy w beta (własna walidacja z `invalid_argument`).
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
