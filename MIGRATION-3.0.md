# Migracja z 2.x na 3.0

Dokument rośnie razem z wydaniami 3.0.0-alpha, -beta i 3.0.0. Stan: **3.0.0-alpha-4**.

## Zmiana modelu

W 2.x serwer sam ustalał tożsamość (OIDC albo PAT), sprawdzał uprawnienia do narzędzia według ról z pliku
polityki i prosił człowieka o potwierdzenie akcji destrukcyjnych. W 3.0 robi to gateway (ws-05). Serwer przyjmuje
ruch wyłącznie od gateway'a (mTLS + wewnętrzny JWT C2), dostaje poświadczenie do systemu w nagłówkach C3 i nie
ma trybu "anonimowego".

## Usunięte w 3.0.0-alpha-1

| 2.x | Co zamiast |
| --- | --- |
| `OidcIdentityResolver`, `IdentityResolver`, `IdentityFilter`, `ProtectedResourceMetadataFilter`, `NoIdentityProviderResolver`, `PatIdentityResolver` | `InternalJwtFilter` (C2); logowanie i RFC 9728 w gateway'u |
| `ToolGuard`, `GuardPolicy`, `GuardPolicyStore`, plik `guard-policy.yml` | polityka narzędzi w gateway'u (Cedar, C7); polityka zasobów w serwerze od beta |
| `ConfirmationService`, `ConfirmationCodeSender`, `LoggingConfirmationCodeSender`, `ConfirmationPrompter`, `ElicitationConfirmationPrompter`, argument `confirmation_code` | potwierdzenia w gateway'u (C11); gateway sam dokłada `confirmation_code` do schematu |
| `CallerIdentity`, `guard.CallerContext` | `auth.CallerContext` (claimy JWT) |
| `ToolRejectedException` | `SanitizedToolException` z kodem C10 |
| narzędzie `platform_whoami` | brak; uprawnienia pokazuje katalog portalu |
| `PatForwardingFilter`, `MissingPatException`, tryb `auth-mode: pat` | `CredentialHeaderFilter` + `CredentialContext.require`; `auth-mode: dev-pat` tylko w profilu `dev` |
| `CredentialContext.requireBackendToken(system)`, `pat(system)`, `systems()`, `patFingerprint()`, `setAccessToken` | `CredentialContext.require(system)` -> `BackendCredential`; `current()`, `type()` |
| `AuthMode.OAUTH`, `AuthMode.PAT` | `AuthMode.GATEWAY`, `AuthMode.DEV_PAT` |
| zależności `spring-security-oauth2-jose`, `snakeyaml` | `nimbus-jose-jwt` 10.9, `json-schema-validator` 3.0.8, `mcp-contracts` 2.0.2, `spring-boot-starter-data-redis` |

## Właściwości

| 2.x | 3.0 |
| --- | --- |
| `aiwcorpo.mcp.platform.auth-mode: oauth \| pat` | `auth-mode: gateway \| dev-pat` |
| `aiwcorpo.mcp.platform.guard.*` (w tym `oidc.*`, `policy-file`, `whoami-tool`, `enabled`) | usunięte; obecność zatrzymuje start |
| `aiwcorpo.mcp.platform.guard.unclassified` | `aiwcorpo.mcp.platform.tools.unclassified` |
| `aiwcorpo.mcp.platform.pat-header-prefix` | `aiwcorpo.mcp.platform.dev-pat.header-prefix` (tylko `dev-pat`); stara nazwa zatrzymuje start |
| - | nowe wymagane: `server-id`, `internal-jwt.jwks-uri`, `credential.connect-url` |
| - | nowe: `internal-jwt.*`, `mtls.*`, Redis `spring.data.redis.*` (opis w README) |

## Kod serwera

- Adapter wyjściowy: zamiast `"Bearer " + CredentialContext.requireBackendToken("jira")` używa
  `CredentialContext.require("jira")` i `credential::applyTo` (albo `credential.value()` dla nagłówków innych niż
  `Authorization`, np. `PRIVATE-TOKEN` GitLaba). Brak poświadczenia to `CredentialRequiredException`, którą
  starter zamienia na `credential_required` z linkiem do portalu.
- Kim jest wywołujący: `CallerContext.current()` (tylko do audytu i polityki; nigdy jako poświadczenie do
  systemu, niezmiennik 2).
- `@ToolRisk` i `RiskLevel` zostają w pakiecie `pl.aiwcorpo.mcp.platform.guard`. `RiskLevel.parse` usunięte.
- Komunikat `SanitizedToolException.getMessage()` to teraz obiekt JSON C10 (wcześniej `kod: komunikat`). Kody
  `credential_required` i `access_denied` zastąpiły `missing_credential` i `forbidden`. `ToolErrorMapping` musi
  zwracać kod C10, inny kod klient zobaczy jako `internal_error`. `McpErrorMapper.SafeError` ma trzecie pole
  `details` (pola specyficzne dla kodu).
- Konstruktor `AuditingToolCallback` i `ToolCallbackAuditPostProcessor` przyjmuje `ToolRiskRegistry` i
  interceptory; `AuditLogger` pisze zdarzenia C8 (`toolCall(...)`), stara metoda `toolInvoked` usunięta.
- Nagłówek korelacji: `X-Request-Id` (C3) zamiast `X-Correlation-Id`; klucz MDC `requestId` zamiast
  `correlationId`.
- Testy: zamiast lokalnego IdP (`TestIdentityProvider`) `TestGateway` z `mcp-platform-test`, profil `dev`.

## Transport MCP

Od 3.0.0-alpha-2 starter ustawia domyślnie `spring.ai.mcp.server.protocol=STREAMABLE`, `type=SYNC` i
`streamable-http.mcp-endpoint=/mcp` (serwer może nadpisać). Serwer na 3.0.0-alpha-1 musi je ustawić sam.

## Zmiany w 3.0.0-alpha-3 (po recenzji alpha-1)

- Komunikat do klienta jako `invalid_argument` tylko z `InvalidToolArgumentException` (nowa klasa, podklasa
  `IllegalArgumentException`). Zwykły `IllegalArgumentException` daje `invalid_argument` ze stałym tekstem:
  walidację argumentów, której komunikat ma zobaczyć model, rzucaj jako `InvalidToolArgumentException`.
- Profil `dev` daje relaksacje tylko jako jedyny aktywny profil; `dev` z innym profilem zatrzymuje start.
- Poza `dev`: `jwks-uri` tylko `https`, wymagane `server.ssl.client-auth=need`, brak osobnego
  `management.server.port`.
- `unauthenticated-paths` tylko z listy zdrowia; protokół MCP tylko `STREAMABLE`.
- Skaner `@McpTool` wyłączony; narzędzie spoza opakowania zatrzymuje start.
- Domyślny `spring.data.redis.timeout=2s`.

## Zmiany w 3.0.0-alpha-4

- `server-id` musi wskazywać system z listy `SystemId` kontraktów; poza profilem `dev` inny system zatrzymuje
  start, w `dev` jest ostrzeżenie.
- Log `event=tool_failed` nie zawiera komunikatów wyjątków, gdy w łańcuchu jest błąd HTTP z `RestClient`
  (tylko klasy i status). Obejście w adapterze (`defaultStatusHandler` przepisujący wyjątek) nie jest już
  potrzebne, ale nie przeszkadza.

## Wdrożenie

- Serwer potrzebuje Redisa (użytkownik `mcp-<system>`, klucze `jti:mcp-<system>:*`, komenda `SET`).
- mTLS: `server.ssl.client-auth: need` z truststore CA wewnętrznego; starter dodatkowo sprawdza CN certyfikatu
  klienta (`mtls.allowed-client-names`).
- Zaufanie do JWKS gateway'a: bundle SSL Springa w `internal-jwt.jwks-ssl-bundle`.

## Dopiero w beta i 3.0.0

`@ResourceRef` i `ResourceResolver`, `PolicyEngine` (Cedar, paczki C7), `QueryScope`, generator manifestu C6 i
`GET /mcp-manifest` (beta); `ResultFilter` (3.0.0). Ten dokument zostanie uzupełniony o ich użycie.
