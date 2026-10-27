# Migracja z 2.x na 3.0

Dokument rośnie razem z wydaniami 3.0.0-alpha, -beta i 3.0.0. Stan: **3.0.0-alpha-7**.

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

## Zmiany w 3.0.0-alpha-5

- Profil `dev` słucha tylko na pętli zwrotnej: starter ustawia `server.address=127.0.0.1` (najniższy priorytet),
  a inny adres w `dev` zatrzymuje start. Serwery w kontenerach (`mcp-platform-dev`, profil `full`) uruchamiaj bez
  profilu `dev`, z mTLS i kluczem gateway'a.
- Log `event=tool_failed` pomija komunikaty także przy wyjątkach konwerterów HTTP i Jacksona (ciało 200, którego
  nie da się sparsować).

## Zmiany w 3.0.0-alpha-6

API bez usunięć (podbicie BOM wystarcza do kompilacji). Zmiany zachowania i start, które mogą zatrzymać serwer:

1. **Walidacja argumentów przez starter.** Starter wyłącza `validateToolInputs` w MCP SDK i sprawdza argumenty
   względem `inputSchema` w `AuditingToolCallback`; naruszenie to C10 `invalid_argument` (bez wartości argumentu)
   i zdarzenie C8. Poza profilem `dev` narzędzie bez `inputSchema` albo ze schematem bez `type: object` i
   `additionalProperties: false` zatrzymuje start (decyzja: fail-closed, nie ostrzeżenie). Schematy generowane
   przez Spring AI z `@Tool` są zamknięte; ręcznie pisane schematy muszą być zamknięte (schematy servicedesk już są).
2. **Jeden customizer MCP.** Starter dostarcza `McpSyncServerCustomizer` (`@Primary`), który składa customizery
   serwera i na końcu wymusza `immediateExecution(true)` i `validateToolInputs(false)`. Własny customizer
   serwera z `@Primary` zatrzymuje start.
3. **`@ArgumentLimits` i `SchemaLimitedToolCallbacks.of(...)`** w pakiecie `pl.aiwcorpo.mcp.platform.input`.
4. `TestGateway.asGateway(user, credential, BackendCredential.Type)`.
5. BOM zarządza wersją `mcp-cedar-entities` (nie dodawać do serwera dla wzorców, patrz niżej).

### Co serwer usuwa z własnych obejść

| Serwer / template | Usunąć | Zamiast tego |
| --- | --- | --- |
| wszystkie | własny `@Primary McpSyncServerCustomizer` z `validateToolInputs(false)` i `immediateExecution(true)` (gitlab: `toolInputsCheckedByTheTools`) | nic; kto potrzebuje własnych ustawień MCP, dopisuje bean `McpSyncServerCustomizer` bez `@Primary` |
| template, gitlab | własna adnotacja `ArgumentLimits` i `SchemaLimitedToolCallbacks` / `withArgumentLimits` w `McpToolsConfiguration` | `pl.aiwcorpo.mcp.platform.input.ArgumentLimits` i `SchemaLimitedToolCallbacks.of(...)` |
| confluence | `@StringArgument`, `ConstrainedToolCallbacks` | `@ArgumentLimits(minLength, maxLength, pattern)` i `SchemaLimitedToolCallbacks.of(...)` (zwraca `ToolCallbackProvider`; confluence dziś zwraca `ToolCallback[]`) |
| gitlab | własny `@Primary McpSyncServerCustomizer` (`toolInputsCheckedByTheTools`; bez usunięcia serwer nie wstanie) oraz `StrictArgumentsToolCallback` (odrzucanie nieznanych argumentów) | `additionalProperties: false` dopisuje `SchemaLimitedToolCallbacks`, odrzuca je starter jako `invalid_argument` |
| servicedesk | ręcznie pisane schematy (mapa `inputSchemas` w `McpToolsConfiguration`) | `@Tool` + `@ArgumentLimits`; jeśli schemat zostaje ręczny, musi mieć `type: object` i `additionalProperties: false` (ma) |
| opensearch | `StrictArgumentsInterceptor` (odrzucanie nieznanych argumentów) | zbędny: `additionalProperties: false` w schemacie + walidacja startera; do wyrzucenia razem z testami tekstu SDK |
| jira | (brak własnych obejść schematu) | opcjonalnie `SchemaLimitedToolCallbacks.of(...)` zamiast `MethodToolCallbackProvider`, żeby limity trafiły do `inputSchema` |
| wszystkie | kopie wzorców identyfikatorów z `ResourceIds` w stałych `pattern` | `@ArgumentLimits(contractPattern = "JiraIssueKey")` itd. (nazwa definicji z `common.schema.json`) |
| wszystkie | `TestGateway`: ręczne ustawianie `X-MCP-Credential-Type` w testach `service-account`/`user-oauth` | `GATEWAY.asGateway(user, credential, BackendCredential.Type.SERVICE_ACCOUNT)` |

Różnice względem wersji własnych: `@ArgumentLimits` nie ma wartości domyślnej `maxLength = 0` jak w template
(brak limitu to `-1`, a tekst bez `maxLength` zatrzymuje start); gitlab dodaje `minimum`/`maximum` dla liczb
całkowitych (są w starterze). Walidacja w kodzie narzędzia zostaje jako druga linia, ale komunikatu o
naruszeniu schematu nie trzeba już tworzyć: ten sam kod `invalid_argument` daje starter. Testy, które sprawdzały
tekst `input validation failed` z SDK, trzeba zmienić na C10 `invalid_argument`.

Nazwa nieznanego argumentu pochodzi od klienta: walidacja SDK wstawiała ją do logu bez escapowania (linia
udająca zdarzenie audytu). Od alpha-6 walidacja SDK jest wyłączona, a starter nie loguje i nie zwraca nazw spoza
schematu (`InputValidationTest.anArgumentNameCannotForgeALogLine`). Serwer nie powinien logować nazw argumentów
spoza schematu ani wartości.

Testy serwera: do sprawdzenia, że zły argument daje C10 i C8, wystarczy wywołanie przez `TestGateway` (wzór:
`InputValidationTest` w `mcp-platform-test`).

## Zmiany w 3.0.0-alpha-7

Podbicie BOM wymaga poprawek w serwerach, które mają parametry bez limitów (patrz niżej). Kontrakty: BOM zarządza
`mcp-contracts` 2.1.1 (schemat Cedar bajtowo bez zmian w 2.x; zmieniły się wzorce `OpenSearchIndexName` i
`OpenSearchIndexPrefix` bez wiodącej kropki, więc serwer opensearch może usunąć własny filtr kropki, oraz `UntrustedText`,
zdarzenie `authentication` i whoami Confluence po stronie brokera). Serwery nie dodają zależności; test gateway'a
(`mcp-platform-test`) czyta wektory 2.1.1.

### Start zatrzymuje się, gdy któryś poziom parametru nie ma limitu (S-M1)

Reguła z alpha-6 (tekst z `maxLength`) obejmuje teraz każdy poziom: pola rekordów, listy, liczby, mapy. Przed
podbiciem sprawdź wszystkie `@Tool`:

| Parametr | Co zrobić |
| --- | --- |
| `Integer`, `Long`, `int`, `Double`... | `@ArgumentLimits(minimum = ..., maximum = ...)`, oba obowiązkowe (opcjonalny `limit` w jira, gitlab, opensearch, servicedesk) |
| `List<String>`, `String[]` | `@ArgumentLimits(maxItems = ..., maxLength = ..., pattern = ...)`: `maxItems` obowiązkowe, reszta opisuje elementy |
| rekord (filtr, np. `JqlFilter`) | `@ArgumentLimits` na każdym komponencie rekordu; komponent opcjonalny z `@Nullable`; nic na samym parametrze |
| `List<Rekord>` | `@ArgumentLimits(maxItems = ...)` na parametrze, limity na komponentach rekordu |
| `Map<String, String>` | zastąpić rekordem z nazwanymi polami albo listą rekordów `{name, value}` (mapa nie daje się ograniczyć, start zatrzymany) |
| `Object`, `JsonNode`, klasa niebędąca rekordem | zastąpić rekordem |
| ręcznie pisany schemat | każdy poziom: `additionalProperties: false`, `maxLength`, `maxItems` + `items`, `minimum`/`maximum`; mapa tylko z `maxProperties`, `propertyNames.maxLength` i ograniczoną wartością; po `prefixItems` wymagane `items` (`false` albo ograniczony) |
| `pattern` | musi zaczynać się od `^`, kończyć na `$` i nie mieć `|` poza grupą: `[0-9]+` -> `^[0-9]+$`, `^a|b$` -> `^(?:a|b)$` (confluence: `NON_BLANK_LINE`) |

**jira.** `McpToolsConfiguration.boundedFilter` i `withBoundedFilter` do usunięcia: `JqlFilter` dostaje
`@ArgumentLimits(minLength = 1, maxLength = JiraTool.MAX_NAME)` na `status` i `type`, `maxLength = MAX_USERNAME` na
`assignee`, wszystkie trzy `@Nullable`; rejestracja `SchemaLimitedToolCallbacks.of(jiraTool)` bez pętli po callbackach.
Starter sam zamyka obiekt, usuwa sztuczne `required` (dzięki `@Nullable`) i egzekwuje limity na każdym wywołaniu.
Test `McpToolsConfigurationTest` na `boundedFilter` zastąpić testem wywołania z za długim `status` (C10 `invalid_argument`).

**Poza `dev` nie da się już wyłączyć tej reguły beanem (S-L3).** Własny bean `ToolInputPolicy` albo
`ToolCallbackAuditPostProcessor` zatrzymuje start; zbędny dodatek wokół narzędzi to `ToolCallInterceptor`.
`ToolInputPolicy.requireClosedSchema()` nazywa się `requireBoundedSchema()`.

### Znaki w argumentach i w tekście z systemu (S-L1)

- Argumenty: tekst z U+2028/U+2029, niesparowanym surogatem, nieznakiem (U+FFFE, U+FFFF), każdym `\p{Cf}` poza U+200C..U+200F (bidi, miękki łącznik),
  zero-width, U+2060..U+2064, BOM albo znakami tagów to `invalid_argument` (wcześniej tylko C0/C1/DEL). W wartości z
  `pattern` także `\n`, `\r`, `\t` i każdy `\p{Cf}`; reguła `pattern` liczona jest dla węzła schematu danej wartości, nie
  dla argumentu najwyższego poziomu.
- Tekst z systemu (opisy, komentarze, tytuły) przez `TextHygiene.normalize(...)` albo od razu `UntrustedText.plain(...)`.
  Serwery jira i confluence zastępują własne czyszczenie (`JiraToolViews`: `\p{Cntrl}`/`\p{Cf}`) wywołaniem helpera.
  Od alpha-8 usunięty ciąg niewidocznych znaków zastępuje jedna spacja (patrz "Zmiany w 3.0.0-alpha-8"), a normalizację
  trzeba zrobić **przed** filtrowaniem serwera.

### `UntrustedText` jako typ wspólny (CC-072)

Zamiast własnego rekordu `body`/`summary`: `pl.aiwcorpo.mcp.platform.text.UntrustedText`. Wynik narzędzia musi być
rekordem; starter publikuje `outputSchema` i zwraca `structuredContent`. Zmienia się `definitionHash` narzędzi
(nowe pole `outputSchema`), więc gateway oznaczy je jako `pending_approval` do ponownego zatwierdzenia. Confluence:
`omittedMacros` przenieść z wnętrza `body` obok `body`. Jira: `description` i komentarze dotąd z `format: text/plain`
zawierały surowy wiki markup (J2-L2); do `UntrustedText.plain(...)` trafia tekst po usunięciu składni obrazów, linków i
makr (usuwanie robi serwer na tekście już znormalizowanym: `TextHygiene.normalize(raw)`, potem filtr, potem fabryka)
albo, jeśli markup zostaje, odstępstwo trzeba opisać.

### Audyt i logi

- Odrzucone żądanie zostawia w audycie C8 zdarzenie `authentication` (bez `sub`, `client`, `tool`); odbiorca audytu
  musi mieć schemat 2.1.1. Nic w kodzie serwera.
- `event=tool_failed`: INFO (kod C10 i klasa wyjątku) dla błędów oczekiwanych, w tym wyjątków domenowych z
  `ToolErrorMapping`; WARN dla niedostępnego backendu; ERROR tylko dla `internal_error`. Serwer, który logował własny
  wiersz na ERROR przy `not_found_or_denied` albo `invalid_argument`, może go usunąć. Alerty na ERROR liczą teraz
  tylko niespodziewane awarie.
- `server/discover` (MCP 2026-07-28) jest żądaniem protokołu: wariant serwisowy dozwolony. Nic w kodzie serwera.

### Testy serwera

`mcp-platform-test` ma test na `tool_failed` i zdarzenia `authentication` (nasłuch na loggerze `pl.aiwcorpo.mcp.audit`).
Testy serwera sprawdzające poziom ERROR dla błędu domenowego trzeba zmienić na INFO.

## Zmiany w 3.0.0-alpha-8

Pracowano na `3.0.0-alpha-8-SNAPSHOT`; alpha-7 i wcześniejsze nietknięte.

### Kolejność: najpierw normalizacja, potem filtr (A7-M2)

`TextHygiene.normalize` nie usuwa już znaków niewidocznych bez śladu: ciąg usuniętych znaków (kontrolne, nieznaki,
każdy `\p{Cf}`) zastępuje jedna spacja (pomijana na początku i końcu tekstu oraz obok białych znaków). Wcześniej
`"@a<ZWSP>ll"` dawało `"@all"` i `"!<ZWSP>https://host/x.png!"` składnię obrazu, czyli tekst, który filtr serwera
przepuścił, bo go nie rozpoznał, wychodził jako dokładnie to, czego filtr szukał. Teraz wychodzi `"@a ll"` i
`"! https://host/x.png!"`. Koszt: sekwencja emoji z ZWJ rozpada się na osobne emoji, ZWNJ w tekście perskim staje się spacją.

Kolejność w serwerze (jira, confluence, każdy, który usuwa składnię z tekstu systemu):

1. `String clean = TextHygiene.normalize(raw);`
2. filtr serwera na `clean` (obrazy, linki, makra, wzmianki, `{noformat}`): widzi to samo, co zobaczy klient;
3. `UntrustedText.plain(filtered, MAX)` albo `markdown(...)`.

Odwrotna kolejność (filtr na surowym tekście, normalizacja w fabryce) nie jest bezpieczna: filtr nie rozpozna
`!<ZWSP>https`. Konstruktor `UntrustedText` też normalizuje (`new UntrustedText(...)` i deserializacja Jacksona nie
omijają higieny), ale to ostatnia linia obrony, nie miejsce na filtrowanie.

### Wynik z `null` w komponencie bez `@Nullable` (A7-M1)

Dla narzędzia z `UntrustedText` w wyniku schemat wyniku wynika z typu rekordu: komponent bez `@Nullable` jest
`required` i bez `null`. Serwer, który przechodzi na `UntrustedText` (jira, confluence), ma w rekordach wyniku pola z
systemu, które bywają puste (`assignee`, `dueDate`, `resolution`, `parentId`): **każde takie pole oznaczyć
`@Nullable`** (`org.springframework.lang.Nullable` albo `org.jspecify.annotations.Nullable`), inaczej zgłoszenie bez
przypisanej osoby kończy się błędem. Zmiana w starterze: wynik jest sprawdzany względem schematu w opakowaniu narzędzia,
przed audytem. Niezgodność to C10 `internal_error` i zdarzenie C8 `outcome=error` (wcześniej: audyt `ok`, a klient
dostawał `isError` z tekstem SDK, którego nie rozpoznaje ani klient, ani gateway). W logu ERROR jest ścieżka pola, nie
wartość. Adnotacji Jacksona na komponentach wyniku nie używać: schemat bierze nazwy komponentów, więc `@JsonProperty`
rozjeżdża schemat i serializację, a wywołanie kończy się `internal_error`. Test serwera: wywołanie narzędzia na
zgłoszeniu z pustymi polami opcjonalnymi (wynik ok, `structuredContent` z `null`).

### Pozostałe zmiany zachowania

- Klucze obiektów w argumentach (mapa z `propertyNames`, obiekt zagnieżdżony, nazwa argumentu) przechodzą `TextHygiene`
  jak wartość z `pattern`; naruszenie to `invalid_argument` ("x has a name with ..."), bez cytowania klucza.
- Element tablicy `i` jest oceniany regułą `prefixItems[i]`, kolejne regułą `items`.
- `ToolCallbackAuditPostProcessor` nie czyta już beana `ToolInputPolicy`: polityka wynika z aktywnych profili w chwili
  tworzenia. `RegisteredToolsCheck` i `ToolHintsPostProcessor` nie są `@ConditionalOnMissingBean`; własny bean któregoś z
  czterech typów zatrzymuje start poza `dev`.
- `tool_failed` (WARN i ERROR): w łańcuchu przyczyn usunięte znaki sterujące i bidi, jedna linia, bez zapytania w URL.

## Wdrożenie

- Serwer potrzebuje Redisa (użytkownik `mcp-<system>`, klucze `jti:mcp-<system>:*`, komenda `SET`).
- mTLS: `server.ssl.client-auth: need` z truststore CA wewnętrznego; starter dodatkowo sprawdza CN certyfikatu
  klienta (`mtls.allowed-client-names`).
- Zaufanie do JWKS gateway'a: bundle SSL Springa w `internal-jwt.jwks-ssl-bundle`.

## Dopiero w beta i 3.0.0

`@ResourceRef` i `ResourceResolver`, `PolicyEngine` (Cedar, paczki C7), `QueryScope`, generator manifestu C6 i
`GET /mcp-manifest` (beta); `ResultFilter` (3.0.0). Ten dokument zostanie uzupełniony o ich użycie.
