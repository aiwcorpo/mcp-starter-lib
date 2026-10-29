# Migracja z 2.x na 3.0

Dokument rośnie razem z wydaniami 3.0.0-alpha, -beta i 3.0.0. Stan: **3.0.0-alpha-14**.

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
| rekord (filtr, np. `JqlFilter`) | `@ArgumentLimits` na każdym komponencie rekordu; komponent opcjonalny z `@Nullable` (`org.springframework.lang.Nullable` albo `org.jspecify.annotations.Nullable`, oba działają na wejściu i na wyniku, test `BoundedSchemaTest`); nic na samym parametrze |
| `List<Rekord>` | `@ArgumentLimits(maxItems = ...)` na parametrze, limity na komponentach rekordu |
| `Map<String, String>` (argument) | zastąpić rekordem z nazwanymi polami albo listą rekordów `{name, value}` z `@ArgumentLimits(maxItems = ...)` na liście i limitami na obu komponentach (mapa nie daje się ograniczyć, start zatrzymany) |
| `Object`, `JsonNode`, klasa niebędąca rekordem | zastąpić rekordem |
| ręcznie pisany schemat | każdy poziom: `additionalProperties: false`, `maxLength`, `maxItems` + `items`, `minimum`/`maximum`; mapa tylko z `maxProperties`, `propertyNames.maxLength` i ograniczoną wartością; po `prefixItems` wymagane `items` (`false` albo ograniczony) |
| `pattern` | musi zaczynać się od `^`, kończyć na `$` i nie mieć `|` poza grupą: `[0-9]+` -> `^[0-9]+$`, `^a|b$` -> `^(?:a|b)$` (`NON_BLANK_LINE` z confluence, `^(?=.*\S)[^\u0000-\u001f\u007f]+$`, jest już zgodny: zakotwiczony, bez `|`, więc nic do zmiany) |

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

### Uruchomienie lokalne w `dev` (smoke alpha-7)

W profilu `dev` (jedyny aktywny) starter wymusza powyżej `application*.yml` serwera `server.ssl.enabled=false`,
`mtls.required=false`, `internal-jwt.replay-store=memory` i puste `internal-jwt.jwks-ssl-bundle`. Z `application-dev.yml`
serwera można usunąć te cztery ustawienia (i obejścia z CLI); `allow-test-keys: true` zostaje tam, gdzie lokalny gateway
podpisuje kluczami testowymi. Opis i komenda startu: README, "Uruchomienie lokalne".

### Pozostałe zmiany zachowania

- Klucze obiektów w argumentach (mapa z `propertyNames`, obiekt zagnieżdżony, nazwa argumentu) przechodzą `TextHygiene`
  jak wartość z `pattern`; naruszenie to `invalid_argument` ("x has a name with ..."), bez cytowania klucza.
- Element tablicy `i` jest oceniany regułą `prefixItems[i]`, kolejne regułą `items`.
- `ToolCallbackAuditPostProcessor` nie czyta już beana `ToolInputPolicy`: polityka wynika z aktywnych profili w chwili
  tworzenia. `RegisteredToolsCheck` i `ToolHintsPostProcessor` nie są `@ConditionalOnMissingBean`; własny bean któregoś z
  czterech typów zatrzymuje start poza `dev`.
- `tool_failed` (WARN i ERROR): w łańcuchu przyczyn usunięte znaki sterujące i bidi, jedna linia, bez zapytania w URL.

## Zmiany w 3.0.0-alpha-9

Pracowano na `3.0.0-alpha-9-SNAPSHOT`. Wydane alpha-8 w `~/.m2` zostało przy tym nadpisane kodem roboczym alpha-9 (incydent
opisany w `STATUS.md`); odtworzenie z tagu `v3.0.0-alpha-8` jest po stronie człowieka. Do tego czasu nie uznawaj wyniku
budowy "na alpha-8" za dowód zachowania wydania alpha-8. Przejście z alpha-8: podbić wersję BOM na
`3.0.0-alpha-9`. API publiczne nie ma usunięć, które dotykałyby kodu serwera zgodnego z alpha-8; kod ma się zmienić tylko
tam, gdzie poniższe punkty mówią wprost.

- **Kolejność dla tekstu z systemu** (A8-L5): konwersja formatu do tekstu (w tym dekodowanie encji `&#8203;`, `&#x200B;`),
  `TextHygiene.normalize`, filtr serwera, `UntrustedText.plain|markdown`. Fabryka loguje WARN
  `event=untrusted_text_not_normalized tool=...`, gdy tekst zmienił się po filtrze (zwykle: encje dekodowane po
  normalizacji); po przejściu na tę kolejność w testach serwera nie ma takiej linii.
- **Cięcie w fabryce `UntrustedText`** cofa się do ostatniego białego znaku, jeśli zachowywana część ostatniego słowa ma
  najwyżej 64 jednostki: `plain("hello @allison", 10)` daje `hello` (`truncated=true`), nie `hello @all`. Test serwera
  porównujący dokładnie ucięty tekst trzeba poprawić; dłuższy ciąg bez spacji jest cięty tam, gdzie wypada limit.
- **Limit wyniku** `aiwcorpo.mcp.platform.tools.max-result-size` (domyślnie 1MB, bajty UTF-8): większy wynik narzędzia to
  C10 `internal_error` (audyt `error`). Narzędzie zwracające listy ma stronicować. Niezgodność wyniku z `outputSchema` jest
  teraz zawsze `internal_error`, także gdy mapping błędów serwera obsługuje `IllegalStateException` (wyjątek platformy to
  osobny typ, `ToolResultRejectedException`, sprawdzany przed mappingami serwera).
- **Profil `dev`**: przy osobnym `management.server.port` guard wymaga loopbackowego `management.server.address`
  (Spring Boot nie pozwala ustawić tego adresu bez osobnego portu, więc starter nie ma domyślnej wartości). `dev` jako jedyny profil w środowisku wdrożenia
  (platforma chmurowa wykrywana przez Spring Boot, `INVOCATION_ID` jednostki systemd) kończy start: usuń
  `SPRING_PROFILES_ACTIVE=dev` z `.env`, szablonów compose i jednostek (`install.sh` template'u go nie ustawia).
  Linia `dev_profile_overrides` jest na WARN i wymienia klucze, które konfiguracja serwera ustawiała inaczej.
- **Beany**: `PlatformConfigurationGuard`, `InternalJwtVerifier` i `InternalJwtFilter` nie są punktami rozszerzenia; własny
  bean któregokolwiek zatrzymuje start w każdym profilu (punkty rozszerzenia: `GatewayKeys`, `JtiReplayStore`,
  `AuditLogger`, `ToolRiskRegistry`, `McpErrorMapper`). `AuditingToolCallback` i `ToolCallbackAuditPostProcessor` mają
  konstruktory pakietowe, `ToolInputPolicy` jest enumem (`STRICT`, `DEV`); kod serwera, który ich nie budował (żaden z
  pięciu serwerów i template'u), nie wymaga zmian.

## Zmiany w 3.0.0-alpha-10

Pracowano na `3.0.0-alpha-10-SNAPSHOT`. Przejście z alpha-9: podbić wersję BOM na `3.0.0-alpha-10`. Kod serwera zgodny z
alpha-9 zmienia się tylko tam, gdzie poniższe punkty mówią wprost.

- **Testy serwera w CI** (A9-M2): kontrola "`dev` we wdrożeniu" nie obejmuje kontekstu budowanego przez Spring TestContext
  (`@SpringBootTest`, slice'y), więc testy z `TestGateway` przechodzą na podzie Kubernetesa i pod runnerem systemd. Nic nie
  trzeba ustawiać; nie dodawaj `INVOCATION_ID=` ani `SPRING_MAIN_CLOUD_PLATFORM=` do konfiguracji CI (nie działają i
  przeciekłyby do wdrożenia). `spring.main.cloud-platform=none` nie jest już platformą wdrożenia.
- **Sygnały wdrożenia dla `dev`**: także `KUBERNETES_SERVICE_HOST` z `KUBERNETES_SERVICE_PORT` i kontener (`/.dockerenv`,
  `/run/.containerenv`, zmienna `container`, cgroup); zmienne procesu są czytane bezpośrednio, `-DINVOCATION_ID=` ich nie
  zasłania. Obraz z `SPRING_PROFILES_ACTIVE=dev` nie wystartuje.
- **Port managementu** porównywany jako liczba: `0` to zawsze osobny serwer (wymaga loopbacku w `dev`, odmowa poza `dev`),
  `-1` wyłącza HTTP actuatora i nie wymaga adresu.
- **Wykluczenie auto-konfiguracji platformy** (`spring.autoconfigure.exclude` albo `exclude` na `@SpringBootApplication`)
  kończy start poza `dev` i poza kontekstem testowym.
- **Cięcie tekstu**: w ciągu bez spacji dłuższym niż 64 jednostki odpadają końcowe litery i cyfry, więc po ogranicznikach
  nie zostaje niepełny token (`(((...(@allison` -> `(((...(@`). Nowe `UntrustedText.cut(tekst, limit)`,
  `plainCut(tekst, truncated)` i `markdownCut(...)`: serwer z filtrem tnie przed filtrem, potem buduje wartość z już
  przyciętego tekstu (cięcie może zdjąć domykający ogranicznik, np. backtick, a filtr ma widzieć to, co dostanie klient).
  Kolejność: konwersja, `normalize`, `cut`, filtr, `plainCut|markdownCut`. Test serwera porównujący dokładnie ucięty
  długi ciąg trzeba poprawić.
- **WARN `untrusted_text_not_normalized`** nie pojawia się dla `null` ani dla samej zmiany końców linii (CRLF, CR, U+2028,
  U+2029, U+0085 na `\n`).

## Z beta-4 na beta-5

Dla serwerów tylko podbicie wersji: `<version>3.0.0-beta-5</version>` w imporcie BOM (kontrakty 3.1.0). Zmiany dotyczą profilu `devstack` i kolejności startu:
zgoda `MCP_DEVSTACK_ACKNOWLEDGED=true` musi być **zmienną środowiskową procesu** (właściwość o tej nazwie z `application.yml`, linii poleceń,
`SPRING_APPLICATION_JSON` albo `DynamicPropertySource` nie wystarcza; druga zgoda, `aiwcorpo.mcp.platform.devstack.acknowledged`, może pochodzić z
dowolnego źródła konfiguracji), a strażnik konfiguracji działa przed pobraniem paczki polityk i kluczy JWT, także przy
`spring.main.lazy-initialization=true`. Stos deweloperski, który ustawiał zgodę inaczej niż przez zmienną środowiskową, ustawia ją w `environment:` kontenera.

## Z beta-3 na beta-4

Jedna nowa możliwość i nic obowiązkowego dla serwera, który działa na beta-3. Wersja: `<version>3.0.0-beta-4</version>` w imporcie BOM.

**Profil `devstack` dla stosu deweloperskiego w kontenerach.** Do beta-3 klucze podpisu `test-*` (JWT gateway'a, paczki polityk) działały tylko w
profilu `dev`, a `dev` jest zabroniony w kontenerze, więc stos `mcp-platform-dev` musiał nazwać klucz paczki inaczej (`policies-main-dev`). To omijało
zabezpieczenie po nazwie: kopia takiej konfiguracji przechodziła na produkcji. Teraz stos uruchamia serwery z profilem `devstack`
(`SPRING_PROFILES_ACTIVE=devstack`, jedyny aktywny profil) i dwiema zgodami:

```yaml
# konfiguracja stosu dev (compose), nie obraz serwera
environment:
  SPRING_PROFILES_ACTIVE: devstack
  MCP_DEVSTACK_ACKNOWLEDGED: "true"
  AIWCORPO_MCP_PLATFORM_DEVSTACK_ACKNOWLEDGED: "true"            # = aiwcorpo.mcp.platform.devstack.acknowledged
  AIWCORPO_MCP_PLATFORM_POLICY_ALLOW_TEST_KEYS: "true"
  AIWCORPO_MCP_PLATFORM_POLICY_MAIN_KEY_ID: test-policies-main-dev   # nazwa klucza wraca do test-*
  AIWCORPO_MCP_PLATFORM_INTERNAL_JWT_ALLOW_TEST_KEYS: "true"       # jeśli gateway podpisuje kluczami testowymi
```

Zmienna `MCP_DEVSTACK_ACKNOWLEDGED` jest czytana tylko ze środowiska procesu (od beta-5; property o tej nazwie, także z `application.yml` czy
`DynamicPropertySource`, nie liczy się), a właściwość `aiwcorpo.mcp.platform.devstack.acknowledged` z dowolnego źródła konfiguracji, więc obie zgody
pochodzą z dwóch różnych miejsc.

`devstack` zdejmuje wyłącznie zakaz kluczy `test-*` i opublikowanych kluczy testowych. Reszta jest jak na produkcji i nadal wymagana: TLS i mTLS
(`server.ssl.client-auth=need`), Redis na `jti`, https do JWKS i do repozytorium paczek, `policy.state-dir`, brak `dev-pat`. Bez obu zgód albo z drugim
profilem start jest odrzucany z listą braków. Każdy start loguje `WARN event=devstack_profile`, a metryka `mcp_server_devstack_active` ma wartość 1;
manifest C6 nie ma pola na środowisko (propozycja CC Beta-I), więc znacznikiem są log i metryka. Poza `dev` i `devstack` klucze `test-*` są
odrzucane jak dotąd: klucz stosu nie przejdzie na produkcji, bo produkcja nie ma profilu `devstack` ani zgód, a klucz produkcyjny nie może się nazywać
`test-*`. Ewentualne odrzucanie kluczy stosu po odcisku SPKI (lista z konfiguracji publikowanej przez ws-09) nie jest w beta-4.

## Z beta-2 na beta-3

Poprawki po recenzji beta-2 (0 CRITICAL/HIGH) i po przeglądzie automatycznym. Serwer, który przeszedł na beta-2, musi tylko oznaczyć
narzędzia zwracające tekst (punkt 1); reszta to nowe błędy startu i drobne API. Wersja: `<version>3.0.0-beta-3</version>` w imporcie BOM
(kontrakty 3.1.0).

1. **Wynik, który jest tekstem, wymaga `@ResultWithoutResources`.** `String`, `CharSequence` i `byte[]` na najwyższym poziomie wyniku
   (także w `Optional`, `List`) mogły być gotowym JSON-em z zasobami, których platforma nie widzi, więc narzędzie zwracające zwykły tekst
   deklaruje, że nie ma w nim zasobów. `String` jako komponent rekordu nadal jest zwykłym polem. Dotyczy każdego narzędzia z
   `String get...()` i `List<String> list...()`:

   ```java
   import pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources;

   @Tool(name = "delete_issue", description = "Deletes an issue.")
   @ToolRisk(RiskLevel.DESTRUCTIVE)
   @ToolTitle("Delete issue")
   @ResultWithoutResources            // "deleted ABC-1": komunikat, nie zasób
   public String deleteIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                             @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
   ```

   Narzędzie, które zwracało `objectMapper.writeValueAsString(hits)` albo odpowiedź systemu jako tekst, zwraca rekordy z
   `@ResourceRef` (adnotacja `@ResultWithoutResources` na takim wyniku byłaby fałszywa i nic by nie filtrowało). Klucz `Map` niebędący
   skalarem (`Map<IssueHit, Long>`) liczy się jak część nieśledzona.
2. **Ręcznie napisany `ToolCallback` i `MethodToolCallback` bez czytelnej metody** zatrzymują start, jeśli nie deklarują wyniku:
   `RiskClassifiedTool#resultWithoutResources()` zwraca `true` (callback nie zwraca zasobów albo filtruje je sam). Testy z własnymi
   callbackami dopisują tę metodę.
3. **Argumenty zagnieżdżone.** Szukanie `@ResourceRef` poniżej parametru obejmuje teraz `Optional`, wartości `Map`, pola klas niebędących
   rekordami razem z nadklasami, podtypy `sealed` (`permits`) i kończy się błędem startu przy zagnieżdżeniu głębszym niż 6 poziomów
   (wcześniej przechodziło po cichu). Kod z `@ResourceRef` na komponencie rekordu-argumentu był już odrzucany od beta-2; teraz także gdy rekord
   siedzi w `Optional<...>`, `Map<String, ...>`, w klasie albo jej nadklasie.
4. **Limit elementów na wywołanie.** `tools.max-filtered-elements` (200) liczy elementy wszystkich list-zasobów w całym wyniku, nie każdą listę osobno;
   przekroczenie to `internal_error`.
5. **CQL.** `scope.cql(fields, true)` i `scope.cqlSpaceClause(true)` dodają zakresy z grup dla `Confluence::Space` (rzucają, gdy grup brak; dla
   `Confluence::Page` nie istnieją, bo zakresy stron to poddrzewa). Wartości w CQL są cytowane i escapowane jak w JQL (testy z wrogimi wartościami).
6. **OpenSearch.** `QueryScope.requireNoAliases(odpowiedźJson)` czyta odpowiedź `GET _resolve/index/<wyrażenie>` i odmawia (`access_denied`) przy
   aliasie albo data streamie; wołaj je przed zapytaniem agregującym, `_count` albo odczytem `hits.total`:

   ```java
   String expression = scope.opensearchIndexExpression();                  // rzuca, gdy nie ma czego pytać
   QueryScope.requireNoAliases(opensearch.resolveIndex(expression));        // GET _resolve/index/<expression>
   var response = opensearch.search(expression, QueryScope.OPENSEARCH_SEARCH_PARAMETERS, body);
   ```

   Resolver indeksów działający kontem serwisowym zwraca `tags: []` dla indeksu bez skonfigurowanych tagów, nie brak atrybutu (brak to `internal_error`,
   czyli inna odpowiedź niż dla indeksu, którego nie ma).
7. **Serwer przykładowy (`mcp-example`).** `TestGateway.start("mcp-example")` generuje teraz paczkę z regułami z przykładów kontraktów także dla zasobów:
   odczyt, zapis w zakresie grup `mcp-jira-proj-ABC` itd., odmowa dla `restricted`, zawężenie linku (grupy `mcp-example-read|write|destructive`). Test
   szablonu (odczyt dozwolony, `restricted` odrzucony, link zawęża) działa na `mcp-example` bez udawania `mcp-jira` (wzór: `ExampleServerResourcesTest`).
8. **Wektory kontraktów na `ResourcePolicy`.** `ContractVectorsOnResourcePolicyTest` w `mcp-platform-test` uruchamia każdy plik z `resource-checks/` i
   `result-filter/` kontraktów na `ResourcePolicy` z przykładowymi politykami; nowy wektor w kontraktach jest uruchamiany bez zmian w kodzie. Serwer
   może zrobić to samo dla własnych resolverów.
9. **Pobieranie paczek.** Pobranie zlecone podpowiedzią `prv` nie czeka na blokadę kanału (`tryLock`), więc dwa wątki silnika nie stoją na jednym `main`.

Znane granice (bez zmiany w beta-3): słowa `AND`, `OR`, `NOT` w wartości `CONTAINS` działają jako operatory wyszukiwania tekstowego w obrębie jednego pola;
`IS EMPTY`, `membersOf(...)` i funkcje dat nie są wyrażalne w `StructuredQuery`; wynik buildera nie był sprawdzony na prawdziwej Jirze (`POST
/rest/api/2/jql/parse`) i Confluence (CQL z `\n`, `\\`, `\"`) w środowisku dev (zadanie dla ws-01).

## Z beta-1 na beta-2

Zmiany po recenzji bezpieczeństwa i smoke teście beta-1. Dla serwera pisanego według sekcji "Z alpha-14 na beta-1" to: przepisanie
wyszukiwania na `StructuredQuery`, kilka nowych błędów startu (wynik nieśledzony przez filtr, `@ResourceRef` w rekordzie argumentu) i
mniej ciche zachowania. Wersje: `<version>3.0.0-beta-2</version>` w imporcie BOM (kontrakty 3.1.0 bez zmian).

1. **Zapytania JQL i CQL buduje serwer.** `QueryScope.restrictJql` został usunięty (beta-1 przyjmował surowy JQL od modelu i
   dawał się obejść przez `\"` poza cudzysłowem: Jira czyta tam ucieczkę, starter nie). Zamiast `restrictJql(scope.jqlProjectClause(), userJql)`:

   ```java
   // model podaje filtr, nie tekst zapytania
   public record IssueFilter(@Nullable @ArgumentLimits(maxLength = 40) String status,
                             @Nullable @ArgumentLimits(maxLength = 200) String text) { }

   String jql = scope.jql(Set.of("status", "text", "created"))                     // import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator / Direction
           .where("status", Operator.EQUALS, filter.status())
           .where("text", Operator.CONTAINS, filter.text())
           .orderBy("created", Direction.DESC).build();
   ```

   Pola z listy autora, operatory z zamkniętego zbioru, tylko `AND`, wartości zawsze w cudzysłowach i escapowane. Narzędzie, które brało
   `String jql` od modelu, bierze strukturę (rekord z polami) albo parametry; jeśli produkt wymaga języka zapytań od modelu, to nie przechodzi
   przez starter. `opensearchIndexPatterns()` rzuca wyjątek zamiast zwracać pustą listę, gdy nie ma czego pytać (pusta lista = wszystkie
   indeksy); użyj `nothingToQuery()` i `opensearchIndexExpression()` oraz `QueryScope.OPENSEARCH_SEARCH_PARAMETERS`, aliasy: README.
2. **Kształt wyniku musi być śledzony albo zadeklarowany.** Start zatrzymuje wynik z `Map`, klasą niebędącą rekordem, `Object`, `JsonNode`
   i rekord z adnotacją Jacksona na komponencie, jeśli rekord (także przez `Page<T>`, `Optional`, listę) zawiera `@ResourceRef`; dla
   wyniku bez zasobów, którego platforma nie umie obejrzeć, dodaj `@ResultWithoutResources` (`pl.aiwcorpo.mcp.platform.resource`):

   ```java
   @Tool(name = "get_stats", description = "Returns counters.")
   @ToolRisk(RiskLevel.READ_ONLY)
   @ToolTitle("Get stats")
   @ResultWithoutResources            // Map<String, Long>: nie ma w niej zasobów
   public Map<String, Long> getStats() { ... }
   ```

   Generyczne rekordy (`Page<IssueHit>`) i `Optional<List<IssueHit>>` działają bez zmian w kodzie. Lista dłuższa niż
   `tools.max-filtered-elements` (200) w wyniku kończy wywołanie `internal_error`: zwracaj strony.
3. **`@ResourceRef` poza parametrem metody.** Na komponencie rekordu będącego argumentem (`update_issue(IssueUpdate req)`) start jest
   zatrzymywany (w beta-1 adnotacja była po cichu ignorowana). Podaj identyfikator jako osobny parametr:
   `update_issue(@ResourceRef(type = "Jira::Issue") String issueKey, IssueChanges changes)`. Dwa argumenty tego samego typu muszą
   mieć ten sam resolver.
4. **Kody błędów resolvera.** Wyjątek z `fetch` albo `describe` (awaria systemu, `NullPointerException` z `Map.of(..., null)`) to
   `upstream_unavailable` / `internal_error`, nie `invalid_argument`. `invalid_argument` dostaje tylko identyfikator niezgodny ze wzorcem typu
   (np. `ABC-SLOW` nie jest kluczem zgłoszenia Jiry; w testach używaj `ABC-5`, `ABC-999`). Zasób bez wymaganej klasyfikacji, z id niezgodnym
   z typem albo innym typem niż w `@ResourceRef` to `internal_error` (kontrakt), nie `not_found_or_denied`; element wyniku w takim stanie jest
   usuwany. Awaria resolvera dla elementu wyniku usuwa element zamiast kończyć wywołanie. Wynik wielu elementów rozwiązuje jedno wołanie
   `ResourceResolver#fetchAll(ids)` (domyślnie pętla po `fetch`; nadpisz wołaniem zbiorczym, np. `key in (...)`).
5. **Test jednostkowy narzędzia z `@ResourceRef`.** Metoda, która czyta `ResourceContext.required().fetched(...)`, rzuca poza wywołaniem
   platformy z instrukcją. W teście otwórz kontekst:

   ```java
   import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
   import pl.aiwcorpo.mcp.platform.test.TestResourceContext;

   try (ResourceContext context = TestResourceContext.open("get_issue", Map.of("issueKey", "ABC-1"))
           .withFetched("Jira::Issue", "ABC-1", new JiraIssue("ABC-1", "ABC"), null)) {
       assertThat(tools.getIssue("ABC-1").project()).isEqualTo("ABC");
   }
   ```

   Politykę sprawdza się przez klienta MCP z `TestGateway`, nie przez bezpośrednie wywołanie metody.
6. **Serwer przykładowy (`mcp-example` w template).** `TestGateway.start("mcp-example")` generuje i podpisuje kluczem testowym paczkę z
   politykami `call` dla grup `mcp-example-read`, `mcp-example-write`, `mcp-example-destructive` oraz regułami z przykładów kontraktów na zasobach
   (odczyt, zapis w zakresie grupy `mcp-jira-proj-ABC` itd., odmowa dla `restricted`, zawężenie linku) dla tych samych grup, więc testy odczytu,
   odmowy i linku działają jak dla pięciu systemów (nie trzeba udawać `mcp-jira`). Poza listą pięciu
   systemów kontraktów: w `dev` start ostrzega (`SystemId`), a manifest nie przechodzi `server-manifest.schema.json` (`serverId` jest
   enumem), co w `dev` jest ostrzeżeniem `event=manifest_rejected_by_rules`; poza `dev` taki serwer nie wstanie. Test `ManifestTest` w
   template'cie zaplanuj więc tylko dla serwerów z listy kontraktów.
7. **Pobieranie paczek.** `policy.read-timeout` to czas jednego żądania w całości (z treścią), a nie tylko do nagłówków; `prv` nowsze niż
   załadowana rewizja zleca pobranie wątkowi silnika i nie blokuje wywołania. Zmiana tylko dla czytających kod.
8. **Interceptory.** `ToolCallInterceptor`, który zmienia wartość argumentu `@ResourceRef` po autoryzacji, kończy wywołanie
   `internal_error`; zmieniaj inne argumenty, nie identyfikatory zasobów.
9. **Tripwire nazw** zna też `del`, `rm`, `revoke`, `unlink`, `kill`, `terminate`, `reset`, `clear`: narzędzie `clear_cache` oznaczone
   `UPDATE` nie wystartuje (oznacz `DESTRUCTIVE` albo zmień nazwę).
10. **Uzupełnienia sekcji "Z alpha-14 na beta-1"** (z smoke testu):
    - importy do przykładów: `pl.aiwcorpo.mcp.platform.resource.{ResourceRef, ResourceResolver, ResourceContext, ResourcePolicy, QueryScope,
      StructuredQuery}`, `pl.aiwcorpo.mcp.platform.input.ArgumentLimits`, `pl.aiwcorpo.mcp.platform.guard.{RiskLevel, ToolRisk, ToolTitle}`,
      `pl.aiwcorpo.mcp.cedar.entities.ResolvedResource` (z `mcp-cedar-entities`, nie ze startera), `pl.aiwcorpo.mcp.platform.test.TestGateway`,
      `java.util.{List, Map, Optional, Set}`, `java.util.Objects`;
    - konfiguracja `dev` jest kompletna dopiero z `server-id`, `internal-jwt.jwks-uri` i `credential.connect-url`:

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

    - `public-key-file` jest czytany względem katalogu roboczego procesu (nie modułu): podawaj ścieżkę bezwzględną albo względną do miejsca
      startu (`mvn spring-boot:run` z katalogu modułu, `java -jar` z dowolnego);
    - `GATEWAY.serverProperties()` ustawia `manifest.version=1.0.0` i `internal-url` dla testów, więc wersja z `application.yml` nie jest w
      testach widoczna; test manifestu porównuj z `1.0.0`, nie z wartością z pliku.

## Z alpha-14 na beta-1

Lista dla template'u i pięciu serwerów (jira, confluence, gitlab, servicedesk, opensearch). Beta-1 przechodzi na
`mcp-contracts` **3.1.0** (przez BOM) i dodaje to, czego brakowało do kamienia M3: tytuły narzędzi i zamknięty schemat,
nową higienę tekstu, `@ResourceRef` z polityką Cedar, filtr wyników, `QueryScope`, manifest C6 z `GET /mcp-manifest`. Serwer
po zmianie 1-4 buduje się i startuje bez nowej logiki; punkty 5-9 dodają ochronę zasobów.

### 1. Wersje

`pom.xml` serwera i template'u: `<version>3.0.0-beta-1</version>` w imporcie `mcp-platform-bom` (BOM zarządza też
`mcp-contracts` 3.1.0, `mcp-cedar-entities`, `mcp-policy-bundle` i `mcp-manifest`; nie dopisuj ich ręcznie). README serwera:
popraw ręcznie wpisane wersje startera i kontraktów na `3.0.0-beta-1` i `3.1.0`. `new-server.sh` bierze wersję BOM z `pom.xml`
template'u, więc template przestaw przed generowaniem nowych serwerów.

Wydane wcześniej wersje w `~/.m2` są niezmienne: nie instaluj ponownie `3.0.0-alpha-14`. Starter i BOM beta-1 trzeba mieć w
`~/.m2` z `./mvnw install` na tagu `v3.0.0-beta-1` (albo z rejestru), zanim serwer się zbuduje.

### 2. `@ToolTitle` na każdym narzędziu (CC-115)

Kontrakt wymaga `title` w definicji narzędzia, a hash i `tools/list` go zawierają. Narzędzie bez tytułu zatrzymuje start.

```java
@Tool(name = "get_issue", description = "Returns an issue by key.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Get issue")                      // pl.aiwcorpo.mcp.platform.guard.ToolTitle
public IssueView getIssue(...) { ... }
```

Tytuł: niepusty, do 100 znaków, bez spacji na brzegach, końców linii i znaków ukrytych. Zmiana tytułu zmienia
`definitionHash`, czyli dla gateway'a jest zmianą definicji narzędzia. Ręcznie napisany `ToolCallback` z
`RiskClassifiedTool` zwraca tytuł z metody `title()`. Testy z narzędziami testowymi dostają ten sam obowiązek.

### 3. Schemat wejścia: zamknięty i ograniczony (CC-078, CC-123)

Reguły startera są teraz regułami manifestu (`input-schema-closed`, `-bounded`, `-no-combinators`), więc to, co starter
przepuszcza, przechodzi też gateway. Zmienia się dla schematów pisanych ręcznie (generowane przez
`SchemaLimitedToolCallbacks` już tak wyglądały):

- mapa z `additionalProperties: {schemat}` i `maxProperties` jest odrzucana (była dopuszczona): użyj rekordu z nazwanymi
  polami albo listy rekordów `{name, value}`;
- `$ref`, typy łączone (`["string","null"]`), `items: false`, `exclusiveMinimum`/`exclusiveMaximum` zamiast `minimum`/`maximum`
  i wzorzec bez `^...$` (albo z alternacją na wierzchu, np. `^a$|^b$`) są odrzucane;
- parametr o nazwie `confirmation_code` zatrzymuje start (nazwa należy do gateway'a, C11);
- `@ArgumentLimits(contractPattern = "OpenSearchIndexPrefix")` działa nadal: definicja kontraktów ma alternację na wierzchu,
  starter publikuje ją jako `^(?:...|...)$`.

### 4. Higiena tekstu (CC-121, CC-122)

Jedna definicja z kontraktów (`test-vectors/text-hygiene/`, 79 wektorów):

- **Argumenty.** Zakazane: kontrolki C0 poza TAB/LF/CR, DEL i C1 (także U+0085), U+2028/U+2029, niesparowane surogaty,
  nieznaki, każdy znak `\p{Cf}` poza U+200C..U+200F, U+034F, U+115F, U+1160, U+3164, U+FFA0, U+2800 i selektory wariantów
  (U+FE00..U+FE0F, U+E0100..U+E01EF), z wyjątkiem jednego U+FE0F zaraz po znaku emoji. W wartościach z `pattern`
  (identyfikatory) zakazane są dodatkowo TAB, LF i CR; ZWJ, ZWNJ, LRM i RLM nie są już zakazane tam, gdzie wzorzec je przepuszcza.
  Brak NFKC.
- **Argumenty, które trafiają do filtra wzmianek serwera** (komentarz, opis, dowolny tekst przekazywany do systemu, który
  zamienia `@nazwa` na powiadomienie): oznacz je, a pełna szerokość ASCII (U+FF01..U+FF5E, np. "@all" zapisane znakami U+FF20
  U+FF41 U+FF4C U+FF4C) będzie odrzucana jako `invalid_argument`:

  ```java
  public IssueComment addComment(
          @ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String issueKey,
          @ArgumentLimits(maxLength = 32000, mentionFiltered = true) String body) { ... }
  // rekord: komponent z @ArgumentLimits(maxLength = 200, mentionFiltered = true) String body; na liście dotyczy elementów
  ```

  Gdzie filtr wzmianek jest Twoim kodem, wywołaj tam też `TextHygiene.firstProblem(text, false, true) != null` -> odrzuć. Znacznik
  na liczbie albo wartości z `enum` zatrzymuje start.
- **Tekst z systemu** (`UntrustedText`, `TextHygiene.normalize`): każdy maksymalny ciąg znaków zakazanych staje się **jedną
  spacją**, nic nie jest przycinane, a CR, CRLF i LF zostają. Zmiana względem alpha-14: U+2028, U+2029 i U+0085 były nowym
  wierszem, teraz są spacją; spacja nie jest już pomijana na brzegach i obok białych znaków (`"\u200bhello\ufeff"` daje `" hello "`).
  Testy serwera, które porównują dokładny tekst po normalizacji, trzeba poprawić. Kolejność `normalize` -> `cut` -> filtr bez zmian.

### 5. Konfiguracja: paczka polityk i manifest

Nowe, obowiązkowe właściwości (bez nich start jest odrzucany z listą braków). Produkcja:

```yaml
aiwcorpo.mcp.platform:
  policy:
    main:
      url: https://policies.internal/policies/main/latest.json
      key-id: policies-main-2026
      public-key-file: /etc/mcp/policies-main.pub.pem         # Ed25519, SPKI PEM (albo public-key: <PEM>)
    overrides:                                                # opcjonalnie, paczka awaryjna z osobnym kluczem
      url: https://policies.internal/policies/overrides/latest.json
      key-id: policies-overrides-2026
      public-key-file: /etc/mcp/policies-overrides.pub.pem
    state-dir: /var/lib/mcp/policy-state                      # osobny katalog na instancję
  manifest:
    version: 1.4.0
    internal-url: https://mcp-jira.internal:8443/mcp
    accepted-credential-types: [user-oauth, user-secret]      # typy poświadczeń, które serwer przyjmuje
```

`dev` (dev-stack `mcp-platform-dev`, repozytorium paczek `ws-09` na `:18747`; klucz z `mcp-policies/keys/dev/`):

```yaml
aiwcorpo.mcp.platform:
  policy:
    allow-test-keys: true                                     # tylko dev; poza dev odmowa startu
    main:
      url: http://127.0.0.1:18747/policies/main/latest.json
      key-id: test-policies-main-dev
      public-key-file: ../mcp-policies/keys/dev/test-policies-main-dev.public.pem
  manifest:
    version: 0.1.0
    internal-url: https://mcp-jira.internal:8443/mcp
    accepted-credential-types: [user-secret]
```

`state-dir` trzyma rewizję i skrót archiwum każdego kanału: bez niego restart przyjąłby ponownie starszą podpisaną paczkę
(CC-066), więc poza `dev` jest wymagany. Serwer bez paczki w `policy.startup-timeout` (30 s) nie wstaje. Szczegóły i
zachowanie przy błędach: README, "Zasoby i polityki". Testy serwera nie wymagają zmian w konfiguracji:
`GATEWAY.serverProperties()` dokłada `policy.*` i `manifest.*` (paczka z przykładowymi politykami kontraktów, klucze testowe).

### 6. `@ResourceRef` i resolver zamiast własnego sprawdzania

W alpha-14 narzędzie brało `String issueKey` i samo pytało system. Teraz zasób deklaruje się adnotacją, a starter przed
uruchomieniem narzędzia rozwiązuje go resolverem i ocenia politykę. Resolver (bean) woła system poświadczeniem wywołującego,
zwraca kanoniczny id, rodzica i klasyfikację **z systemu**:

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
        return jira.issue(key);          // empty dla 404 i 403 (nie ma albo nie wolno); wyjątek dla awarii systemu
    }

    @Override
    public ResolvedResource describe(JiraIssue issue) {
        // id i projekt z odpowiedzi Jiry: zgłoszenie przeniesione do HR ma tu projekt HR, nie ten z argumentu
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
    // wynik fetch z tego wywołania, bez drugiego pytania Jiry
    JiraIssue issue = ResourceContext.current().orElseThrow().fetched("Jira::Issue", issueKey, JiraIssue.class).orElseThrow();
    return IssueView.of(issue);
}
```

Atrybuty obowiązkowe (zasób bez nich jest odrzucany, nie dopuszczany): `Jira::Project.category` (`""` gdy brak),
`Confluence::Space.labels`, `OpenSearch::Index.tags`; opcjonalne `classification` dla `GitLab::Group`, `GitLab::Project`,
`ServiceDesk::Queue` (GitLab: suma topics grup przodków). Dla Confluence strona podaje `ancestorIds`. Wszystkie wartości
`@ResourceRef` są oceniane (lista, źródło i cel transferu); `resourceRefs` bez wartości (opcjonalny argument pominięty) kończy się
odmową, więc argument, który może być nieobecny, nie jest `@ResourceRef` (wyszukiwanie: `QueryScope`, punkt 7).

**GitLab: topics grup-przodków (CC-129, kontrakty 3.1.0).** Topics w GitLabie się nie dziedziczą, a `restricted` na grupie ma chronić całe
poddrzewo, więc schemat Cedar ma wymagany `inheritedClassification` na `GitLab::Group` i `GitLab::Project`, który builder liczy z
atrybutu `ancestorClassifications` podanego przez serwer. Starter nie ma na to osobnego API: to atrybut w `ResolvedResource`
(mapa `<ścieżka grupy-przodka> -> topics`, dokładnie dla grup nad zasobem, dla projektu łącznie z jego rodzicem, `{}` jawnie dla grupy najwyższego poziomu), a **rodzic** projektu
(grupa) dostaje swoją własną mapę (przodkowie grupy, bez niej samej). Brak mapy, brakujący albo nadmiarowy klucz (w tym sama grupa)
lub topics niebędące listą napisów dają `internal_error` (zasób nie jest budowany, wywołanie jest odrzucone). Resolver
serwera GitLab pobiera topics każdej grupy na ścieżce (cache na czas wywołania):

```java
// projekt hr/payroll/app; GitLab: topics grupy hr = [Restricted], grupy hr/payroll = []
Map<String, List<String>> ancestors = Map.of("hr", List.of("Restricted"), "hr/payroll", List.of());
return ResolvedResource.of("GitLab::Project", "hr/payroll/app",
                Map.of("path", "hr/payroll/app", "classification", ownTopics, "ancestorClassifications", ancestors))
        .withParent(ResolvedResource.of("GitLab::Group", "hr/payroll",
                Map.of("path", "hr/payroll", "classification", groupTopics,
                        "ancestorClassifications", Map.of("hr", List.of("Restricted")))));   // bez samej grupy "hr/payroll"
```

Wartości są normalizowane przez builder (trim, małe litery ASCII), więc `Restricted` wystarcza. Wzór w testach:
`GitLabProjectResolver` w `mcp-platform-test` (`JiraServer`, testy `ResourcePolicyTest`: projekt trzy poziomy pod grupą `restricted` jest
odrzucany, projekt bez mapy też). Paczki zbudowane na schemacie 3.0.x są odrzucane (`schema_mismatch`): potrzebna jest paczka
ws-09 na schemacie 3.1.0.

`@ToolRisk(value = RiskLevel.UPDATE, action = ToolAction.ADMIN)` wybiera akcję Cedar `admin`; domyślnie `read`, `update`,
`delete` wynikają z ryzyka, a sprzeczność (`READ_ONLY` z `delete`) zatrzymuje start.

Co zmienia się w odpowiedziach: nieistniejący, niedostępny i przeniesiony do `restricted` zasób dają ten sam
`not_found_or_denied` z tym samym tekstem (zasób bez wymaganej klasyfikacji: `internal_error`, od beta-2 zgodnie z kontraktem); brak grupy narzędzia to `access_denied`; awaria systemu w resolverze to
`upstream_unavailable` (nie odmowa). Adapter, który tłumaczył 404 na własny błąd, powinien w resolverze zwracać `Optional.empty()`.

### 7. Wyniki i wyszukiwanie: `@ResourceRef` w rekordach, `QueryScope`

Narzędzie wyszukujące zwraca rekordy, których komponent z identyfikatorem ma `@ResourceRef`; elementy listy niedozwolone dla
wywołującego są usuwane po wykonaniu narzędzia (zgłoszenie z `DEF` podlinkowane w wyszukiwaniu `ABC` znika, gdy link
wirtualnego serwera zawęża do `ABC`). Zapytanie buduje serwer ze struktury (beta-2; w beta-1 było tu `restrictJql` na surowym JQL od
modelu, które dało się obejść, patrz "Z beta-1 na beta-2"):

```java
public record IssueHit(@ResourceRef(type = "Jira::Issue") String key, String summary) { }
public record SearchResult(List<IssueHit> hits, int total) { }

@Tool(name = "search_issues", description = "Searches issues.")
@ToolRisk(RiskLevel.READ_ONLY)
@ToolTitle("Search issues")
public SearchResult searchIssues(IssueFilter filter) {
    QueryScope scope = policy.queryScope("Jira::Project");                    // ResourcePolicy wstrzyknięte w konstruktorze
    String jql = scope.jql(Set.of("status", "labels", "text", "created"))
            .where("status", Operator.EQUALS, filter.status())
            .where("text", Operator.CONTAINS, filter.text())
            .orderBy("created", Direction.DESC).build();                      // project in ("ABC") AND status = "Open" ...
    return toResult(jira.search(jql));
}
```

Dla Confluence `scope.cql(...)`, dla OpenSearch `scope.opensearchIndexExpression()` (patrz README, "`QueryScope` i `StructuredQuery`"). `total` i inne
liczniki z systemu nie są poprawiane po filtrowaniu: nie pokazuj ich, jeśli mają być zgodne z widoczną listą. Wynik, który nie jest rekordem z
oznaczonym komponentem, filtruj przez `policy.retainAllowedById("Jira::Issue", items, Item::key)` albo
`policy.retainAllowed(items, item -> resolvedResource)`.

### 8. Testy serwera

```java
static final TestGateway GATEWAY = TestGateway.start("mcp-jira");          // bez zmian

@DynamicPropertySource
static void gateway(DynamicPropertyRegistry registry) {
    GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));   // teraz także policy.* i manifest.*
}

McpSyncClient client = ...httpRequestCustomizer(GATEWAY.asGateway(
        user -> user.groups(List.of("mcp-jira-read")).virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC"))),
        "Bearer test-jira-pat")).build();
```

- Wywołanie narzędzia wymaga teraz grupy zgodnej z ryzykiem w przykładowych politykach kontraktów (`mcp-jira-read`,
  `mcp-jira-write`, `mcp-jira-destructive`, zakres `mcp-jira-proj-ABC`); domyślny token ma `mcp-jira-read`. Testy
  `DESTRUCTIVE` ustawiają też `mfaAgeSeconds(60)` (reguła `destructive-requires-fresh-mfa` jest w paczkach testowych, nie w
  produkcyjnej paczce `ws-09`).
- Test odmowy zasobu: zgłoszenie, które sztuczna Jira oddaje jako przeniesione do projektu z kategorią `Restricted`, daje
  `not_found_or_denied` (wzór: `ResourcePolicyTest` w `mcp-platform-test`); test wyniku: wyszukiwanie z linkiem zawężonym do jednego
  projektu zwraca tylko jego zgłoszenia.
- Paczkę zmienia się w teście: `GATEWAY.main().publish("20261026130000-b2c3d4e")`, zły podpis `publish(rewizja, "policies-....tar.gz.sig-by-overrides-key")`,
  `offline(true)`.
- **Test manifestu serwera** (nowy, wzór `ManifestTest` w `mcp-platform-test`): pobierz `/mcp-manifest` z tokenem serwisowym
  (`GATEWAY.service().mint().headers()`) i sprawdź `ManifestRules.violations(manifest)` pusty, definicje równe wpisom `tools/list` i
  `risk` narzędzi `DESTRUCTIVE`. Starter robi to samo przy starcie (poza `dev` błąd zatrzymuje serwer), test pilnuje, że nikt tego
  nie wyłączył.

### 9. Manifest, rejestracja i `DESTRUCTIVE`

`GET /mcp-manifest` i opcjonalny plik (`manifest.output-file: target/mcp-manifest.json`) dają manifest do rejestracji w
gateway'u (`PUT /admin/v1/registry/servers/{serverId}`). Zamiast generatora w czasie budowy (adnotacje + plugin Maven) manifest
powstaje przy starcie z tego, co serwer naprawdę publikuje, więc `definitionHash` zawsze zgadza się z `tools/list`.

Narzędzie jest `DESTRUCTIVE` w manifeście wyłącznie przez `@ToolRisk(RiskLevel.DESTRUCTIVE)`. Serwer nie pyta o potwierdzenie
(gateway, kod TOTP), ale starter pilnuje spójności: ten sam `risk` w manifeście, `destructiveHint: true` w `tools/list`,
akcja `delete` (lub jawnie `admin`) w Cedar, nazwa z `delete`/`drop`/`purge`/... nie przejdzie jako `UPDATE`. Narzędzie `delete_issue`
dla dema M3 wygląda tak:

```java
@Tool(name = "delete_issue", description = "Deletes an issue.")
@ToolRisk(RiskLevel.DESTRUCTIVE)
@ToolTitle("Delete issue")
public DeleteResult deleteIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                                @ResourceRef(type = "Jira::Issue") String issueKey) { ... }
```

### 10. Pozostałe zmiany

- `ToolErrorCode`: usunięte `CONFIRMATION_DECLINED` (kontrakt 3.0.0), dodane `CONFIRMATION_SUPERSEDED`, `CONFIRMATION_CANCELLED`,
  `CONFIRMATION_BLOCKED`. Serwer nie zwraca żadnego z nich; zmienia się tylko lista, którą akceptuje `ToolErrorMapping`.
- Audyt: nowe zdarzenie `policy_decision` (odmowa polityki) obok `tool_call`; `resource.id` pomijane, gdy zawiera znak niedozwolony
  w polu logu (kontrakty 3.0.2).
- Bean `policyEngine` i `resourcePolicy` nie są punktami rozszerzenia (jak `internalJwtVerifier`): własny bean o tej nazwie
  zatrzymuje start; wykluczenie `PlatformPolicyAutoConfiguration` też.
- Zależność natywna: `mcp-cedar-entities` wciąga `cedar-java` (biblioteki dla macOS i Linuksa z glibc). Obraz serwera nie może być
  oparty o Alpine/musl (`eclipse-temurin:25-jre`, nie `-alpine`).
- Zbuduj: `JAVA_HOME` na Javę 25, `./mvnw -B verify`.

## Jak przejść z alpha-11 (albo alpha-12) na 3.0.0-alpha-14

Jedna lista dla serwera i dla template'u; szczegóły i uzasadnienia są w sekcjach "Zmiany w 3.0.0-alpha-12" i
"Zmiany w 3.0.0-alpha-13" i "Zmiany w 3.0.0-alpha-14" niżej. Kolejność ma znaczenie tylko dla ostatniego punktu.

1. **Wersje.** W `pom.xml` serwera `<mcp-platform.version>3.0.0-alpha-14</mcp-platform.version>` (BOM zarządza też
   `mcp-contracts` 2.2.5). W README serwera wersja startera i kontraktów jest wpisana ręcznie (`starter 3.0.0-alpha-11`,
   `kontrakty 2.1.1`): popraw na `3.0.0-alpha-14` i `2.2.5`. `new-server.sh` bierze wersję BOM z `pom.xml` template'u, więc
   template trzeba przestawić przed generowaniem nowych serwerów.
2. **Zastąp cały plik** `mcp-app/src/test/java/pl/aiwcorpo/mcp/<system>/app/DevProfileLoopbackTest.java` (to nie jest
   dopisek do testu z alpha-11: `TestGateway`, `SpringApplicationBuilder` i `assertThatThrownBy` znikają z importów).
   Pakiet i nazwa systemu zostają Twoje (`jira`, `confluence`, `gitlab`, `servicedesk`, `opensearch`, w template'cie `example`):

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

   `ServerTestSupport` jest w tym samym pakiecie (klasa bazowa testów serwera z `@SpringBootTest` i `@ActiveProfiles("dev")`),
   więc import nie jest potrzebny; test używa jej kontekstu i nie startuje aplikacji drugi raz.
3. **Usuń wykluczenia platformy**, jeśli są; w alpha-13 kończą start w każdym profilu, także w `dev`. Miejsca do przeszukania:
   - `src/main/resources/application*.yml` i `.properties` (także `application-dev.yml`): klucz
     `spring.autoconfigure.exclude`, zapisany jako lista albo z indeksami (`exclude[0]`);
   - zmienne środowiska: `SPRING_AUTOCONFIGURE_EXCLUDE` w `.env`, w `docker-compose*.yml`, w jednostce systemd
     (`Environment=`, `EnvironmentFile=`), w `Dockerfile` (`ENV`), w konfiguracji CI;
   - kod: `@SpringBootApplication(exclude = ...)` albo `excludeName`, `@EnableAutoConfiguration(exclude = ...)`,
     `@ImportAutoConfiguration(exclude = ...)`;
   - wartości do wyrzucenia: wszystko z pakietu `pl.aiwcorpo.mcp.platform.` (np. `PlatformSecurityAutoConfiguration`,
     `PlatformAuthAutoConfiguration`, `PlatformObservabilityAutoConfiguration`, `PlatformMcpServerAutoConfiguration`).
     Przykład do usunięcia:

     ```java
     @SpringBootApplication(exclude = PlatformSecurityAutoConfiguration.class)   // nie rób tego
     public class McpServerApplication { ... }
     ```

   Grep, który wystarcza na całe drzewo: `grep -rnE "autoconfigure.exclude|AUTOCONFIGURE_EXCLUDE|exclude *=|excludeName" .`
   (bez `target/`). Slice'y (`@WebMvcTest`) bez platformy nadal działają.
4. **Lazy initialization i własny filtr.** Serwer z `spring.main.lazy-initialization=true` albo z własnym
   `CredentialHeaderFilter` nic nie zmienia: od alpha-13 guardy powstają zawsze.
5. **Kod, który ustawia adres serwera WWW** (`WebServerFactoryCustomizer#setAddress`, dodatkowy `Connector`) w `dev` musi
   wskazywać pętlę zwrotną; inaczej start kończy się odmową.
6. **Zbuduj** (`JAVA_HOME` na Javę 25, `./mvnw -B verify`).

## Zmiany w 3.0.0-alpha-12

Pracowano na `3.0.0-alpha-12-SNAPSHOT`. Przejście z alpha-11: podbić `mcp-platform.version` (BOM) na `3.0.0-alpha-12`, co
podnosi też `mcp-contracts` do 2.2.5; w każdym serwerze i w template'cie zastąpić plik `DevProfileLoopbackTest` (pierwszy
punkt) i usunąć wykluczenia platformy, jeśli są (drugi). Reszta zmienia się tylko tam, gdzie punkty mówią wprost.

- **`DevProfileLoopbackTest` sprawdza prawdziwą konfigurację serwera (A11-M1).** Test z alpha-11 woła pomocnika na
  domyślnych ustawieniach startera i nie dotykał niczego z serwera; serwer z `@SpringBootApplication(exclude =
  PlatformSecurityAutoConfiguration.class)` miał w `dev` zielone testy mimo `server.address=0.0.0.0`. Nowy plik
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

  Klasa dziedziczy po `ServerTestSupport` (ten sam kontekst, bez dodatkowego startu aplikacji). Test nie zależy od hosta:
  nie startuje niczego z adresem sieciowym.
- **Wykluczenie platformy odmawiane także w `dev` (A10-L3, A11-M1).** `spring.autoconfigure.exclude` z klasą
  `pl.aiwcorpo.mcp.platform.*` i `exclude` na `@SpringBootApplication`, które usuwa jeden z beanów z
  `PlatformPresenceCheck.REQUIRED_BEANS`, kończą start we wszystkich profilach poza kontekstem testowym. Serwer, który miał
  takie wykluczenie tylko dla `dev`, musi je usunąć (guard jest tym, co wiąże relaksacje `dev` z adresem pętli zwrotnej).
  Slice'y (`@WebMvcTest` itd.) bez platformy działają jak dotąd.
- **Zdarzenie `authentication` według CC-120 (kontrakty 2.2.5).** Starter emitował już `missing_token`, `client_certificate_rejected`,
  `jwks_unavailable` i `replay_store_unavailable` z `errorCode: upstream_unavailable`; teraz jest to sprawdzane wektorami
  `audit-events/` 2.2.5 i schematem dla każdego powodu (`AuthenticationAuditVectorsTest`). Nowe: limit zdarzeń `missing_token`
  na proces, `aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second` (domyślnie 20, minimum 1; wartość mniejsza
  od 1 zatrzymuje start), nadmiar tylko w liczniku `mcp_server_audit_authentication_suppressed_total{reason="missing_token"}`.
  Serwery nic nie zmieniają; SIEM i alerty, które liczą `missing_token`, powinny dodać ten licznik.
- **Cięcie tekstu** (A11-L1): długi ciąg `.`, `-`, `_` albo znaków łączących po krótkiej nazwie nie zostawia już nazwy
  (`"x(@all" + 70 * "."` przy limicie 75 daje `x(@`, nie `x(@all` z kropkami). Test serwera porównujący dokładnie ucięty
  tekst z takim ogonem trzeba poprawić.
- **Rozpoznanie jaru test frameworka** (A11-L2): ścieżka musi kończyć się na `spring-test-<wersja>.jar` albo
  `spring-boot-test-<wersja>.jar` (z `!/` w jarze zagnieżdżonym); katalog o takiej nazwie już się nie liczy. Nic do zmiany w serwerach.
- **Devcontainer** (A10-L2): bez zmian; człowiek wybrał opcję 1 (STATUS), `dev` w kontenerze nadal odmawia.

## Zmiany w 3.0.0-alpha-14

Pracowano na `3.0.0-alpha-14-SNAPSHOT`. Przejście: lista "Jak przejść z alpha-11" wyżej (z alpha-13 wystarczy podbić wersję).

- **Limit zdarzeń: osobny budżet na powód, tylko dla nieparsowalnego tokenu.** `missing_token` i `malformed` (obcy schemat, nie trzy
  segmenty, nagłówek JWT nie jest obiektem) mają osobne budżety (domyślnie 20 na sekundę każdy), więc zalew jednego nie zużywa drugiego.
  Token z `crit` albo `typ` innym niż `JWT` w nagłówku, po rozwiązaniu `kid`, nie jest już `malformed` ani limitowany: to próba, nie szum,
  i jest raportowany bez limitu jako `alg_not_allowed` (najbliższy istniejący kod C8; własny kod to propozycja CC w STATUS).
  Alert na `malformed` nie obejmuje już tych tokenów, a `alg_not_allowed` obejmuje je razem z nagłówkami z `alg` innym niż ES256.
  Poprawia alpha-13, gdzie budżet był wspólny dla obu powodów i dla każdego niepoprawnego nagłówka.

## Zmiany w 3.0.0-alpha-13

Pracowano na `3.0.0-alpha-13-SNAPSHOT`. Przejście: lista "Jak przejść z alpha-11 (albo alpha-12)" wyżej.

- **Guardy powstają przy `spring.main.lazy-initialization=true` (A12-M1).** `PlatformConfigurationGuard`,
  `CredentialTransportGuard` i nowy `DevListenAddressCheck` są `@Lazy(false)`. Wcześniej guard był tworzony tylko jako
  zależność `CredentialHeaderFilter`, więc serwer z leniwą inicjalizacją i własnym `CredentialHeaderFilter` startował bez
  żadnej kontroli (`dev` na `0.0.0.0`, `prod` z kluczami testowymi, bez mTLS, z jti w pamięci i JWKS po http).
  `assertServerIsGuarded` pobiera beany (`getBean`), nie tylko sprawdza definicje, więc wykonuje też kontrole guarda.
- **Adres, na którym serwer naprawdę słucha (A12-L2).** W `dev` po starcie serwera WWW (głównego i managementu) sprawdzane są
  konektory Tomcata; adres inny niż pętla zwrotna albo nieustawiony (wszystkie interfejsy) kończy start. Dotyczy kodu, który
  ustawia adres fabryki serwera albo dodaje konektor, bo `server.address` tego nie pokazuje. `assertServerIsGuarded` sprawdza to
  samo w kontekście z serwerem WWW (`ListenAddresses.nonLoopback`).
- **Limit zdarzeń bez poprawnego tokenu (A12-L1).** Wspólny budżet obejmuje `missing_token` i `malformed` (pusty albo obcy
  nagłówek `Authorization`, napis, który nie jest JWT) i dotyczy zarówno zdarzenia `authentication`, jak i linii
  `event=internal_jwt_rejected` w logu aplikacji. Nadmiar tylko w liczniku
  `mcp_server_audit_authentication_suppressed_total` z tagiem `reason` (`missing_token` albo `malformed`); żądanie dostaje 401
  jak zawsze. Własność `internal-jwt.missing-token-events-per-second` zostaje (nazwa z alpha-12). Okno limitera jest
  teraz synchronizowane, więc budżet nie jest przekraczany pod współbieżnością. Alerty na `malformed` w SIEM trzeba oprzeć
  też na liczniku.
- Testy audytu (A12-L3): kody błędu dla powodów liczone z tabeli kontraktu, nie z predykatu filtra; `jwks_unavailable`
  sprawdzone przez filtr; asercja serwera odmawia kontekstu spoza `dev`.

## Zmiany w 3.0.0-alpha-11

Pracowano na `3.0.0-alpha-11-SNAPSHOT`. Przejście z alpha-10: podbić wersję BOM na `3.0.0-alpha-11` i zamienić
`DevProfileLoopbackTest` (pierwszy punkt). Reszta kodu serwera zgodnego z alpha-10 zmienia się tylko tam, gdzie punkty
mówią wprost.

- **`DevProfileLoopbackTest` w każdym serwerze i w template'cie (A10-M1).** Test z alpha-5..alpha-10 startuje aplikację
  przez `SpringApplicationBuilder(...).profiles("dev")` z `--server.address=0.0.0.0` i oczekuje komunikatu o adresie. W
  CI w kontenerze (executor Docker, `/.dockerenv`), na Kubernetesie i pod systemd start jest odrzucany wcześniej, z
  komunikatem "the 'dev' profile is active in ...", więc asercja pada. Nie usuwaj asercji i nie ustawiaj nic w CI: zamień
  ciało testu na pomocnika, który sprawdza regułę guarda niezależnie od hosta. Cały plik
  `mcp-app/src/test/java/.../app/DevProfileLoopbackTest.java` ma wyglądać tak (pakiet i nazwa serwera zostają Twoje):

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

  Importy `TestGateway`, `SpringApplicationBuilder` i `assertThatThrownBy` oraz odwołania do `McpServerApplication` i
  `ServerTestSupport` znikają z tego pliku; zależność `mcp-platform-test` (scope `test`) już jest. Pomocnik buduje własne
  środowisko (bez zmiennych i właściwości hosta, z sygnałami poda i systemd ustawionymi na stałe), więc przechodzi tak samo
  na macOS, w kontenerze, na podzie i pod systemd. Szczególny adres: `DevProfileGuardAssertions.assertDevProfileRefusesAddress("10.0.0.5")`.
  Ten test sam nie dotyka konfiguracji serwera; od alpha-12 uzupełnia go `assertServerIsGuarded(context)` (sekcja
  "Zmiany w 3.0.0-alpha-12"). Zdanie, że guard pilnuje adresu w każdym `@SpringBootTest`, jest prawdziwe tylko wtedy, gdy
  guard istnieje w kontekście serwera (alpha-11 tego nie sprawdzała).
- **Odmowa `dev` w kontenerze** (A10-L2) wymienia devcontainer, Codespaces, Toolbox i Distrobox i podaje, jak pracować:
  uruchom serwer poza kontenerem, uruchom go z testu (kontekst testowy nie jest sprawdzany) albo użyj profilu innego niż
  `dev` z TLS, mTLS i Redisem jak we wdrożeniu. Dopuszczenia `dev` w devcontainerze nie ma; decyzja czeka na człowieka.
  Testy serwera, które sprawdzały treść odmowy, nie istnieją (poza `DevProfileLoopbackTest` wyżej).
- **Kontekst testowy po jarze, nie po samej nazwie klasy** (A10-L1): klasa z pakietu `org.springframework.test.context`
  albo `org.springframework.boot.test.context` liczy się jako test framework tylko wtedy, gdy jest załadowana z jaru
  `spring-test` albo `spring-boot-test`. Własna klasa serwera w tych pakietach nie wyłącza już kontroli. To siatka, nie
  granica: kod serwera, który świadomie fałszuje jar albo stos, nie jest w modelu zagrożeń.
- **Kontrola obecności beanów** (A10-L3) obejmuje też `platformMcpServerCustomizer`: `@SpringBootApplication(exclude =
  PlatformMcpServerAutoConfiguration.class)` kończy start poza `dev` i poza kontekstem testowym (bez tego SDK walidowałoby
  wejście przed opakowaniem audytu). Serwer, który tak robił, ma przestać.
- **Cięcie tekstu** (A10-L4, uwagi z migracji): `-`, `.` i znaki łączące (`\p{M}`) liczą się do słowa, więc
  `/@all-hands` daje `/@`, nie `/@all-`. Cięcie na limicie zostaje, gdy cofnięcie usunęłoby więcej niż 64 jednostki albo
  przed końcem nie ma ogranicznika: ciąg CJK po jednym nawiasie nie znika. **Pojedyncze długie słowo** (bez spacji i bez
  ogranicznika w zachowywanej części) daje teraz prefiks z `truncated=true`, wcześniej pusty tekst (`plain("supercalifragilistic", 5)`
  to `super`). Wybrane prefiks, bo bez ogranicznika nie ma początku tokenu, a pusty opis ukrywał, że coś w ogóle było;
  ograniczniki (`@`, `/`, `(`) nadal są granicą cofania (`@allison` do 4 jednostek to `@`). Test serwera porównujący
  dokładny ucięty tekst, który oczekiwał pustego tekstu, trzeba poprawić. `UntrustedText.cut(tekst, -1)` rzuca
  `IllegalArgumentException("maxLength must not be negative")`.
- **Komunikat walidacji zagnieżdżonego pola** podaje ścieżkę i limit: `jql_filter.status is longer than 40 characters`,
  `jql_filter.labels[1] is longer than 8 characters`, `jql_filter.status is required` (wcześniej
  `jql_filter is longer than allowed characters`). Ścieżka składa się z nazw pól ze schematu i indeksów tablic; klucz mapy
  z wejścia to `*`; wartości nie ma w komunikacie. Test serwera sprawdzający stary komunikat trzeba poprawić.
- **`Map` w wyniku narzędzia**: gdy wynik zawiera `UntrustedText`, starter publikuje `outputSchema` z typu rekordu, a mapa
  nie ma nazw pól ani typu wartości, więc start kończy się błędem "cannot describe the result type". Zamiast
  `Map<String, String>` zwracaj listę rekordów `Pair(String name, String value)` albo rekord z nazwanymi polami; tekst z
  systemu w wartości to `UntrustedText`, nie `String`. Wynik bez `UntrustedText` nie dostaje schematu i mapa w nim nie jest
  sprawdzana.

## Wdrożenie

- Serwer potrzebuje Redisa (użytkownik `mcp-<system>`, klucze `jti:mcp-<system>:*`, komenda `SET`).
- mTLS: `server.ssl.client-auth: need` z truststore CA wewnętrznego; starter dodatkowo sprawdza CN certyfikatu
  klienta (`mtls.allowed-client-names`).
- Zaufanie do JWKS gateway'a: bundle SSL Springa w `internal-jwt.jwks-ssl-bundle`.

## Dopiero w 3.0.0

Dokumentacja końcowa i recenzje; kod z tej listy: `@ResourceRef`, `ResourceResolver`, `PolicyEngine`, `QueryScope`, `ResultFilter`,
manifest C6 i `GET /mcp-manifest` są w beta-1 (sekcja "Z alpha-14 na beta-1").
