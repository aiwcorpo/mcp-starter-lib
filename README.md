# mcp-platform: biblioteka serwerów MCP za gateway'em

Wspólna biblioteka serwerów MCP platformy (`mcp-jira`, `mcp-confluence`, ...). Od 3.0 serwer stoi wyłącznie za
gateway'em: nie loguje użytkowników, nie decyduje o uprawnieniach do narzędzi i nie pyta o potwierdzenia, to
robi gateway. Biblioteka weryfikuje wewnętrzny JWT gateway'a, odbiera poświadczenie do systemu docelowego,
audytuje każde wywołanie i pilnuje, żeby z narzędzia wychodziły tylko bezpieczne błędy.

Stos: Java 25, Spring Boot 4.1.0, Spring AI 2.0.0 (MCP SDK 2.0.0), kontrakty `mcp-contracts` 2.1.1.
Wersja: **3.0.0-alpha-7** (stan i plan w `STATUS.md`, zmiany względem 2.x w `MIGRATION-3.0.md`).

## Moduły

| Artefakt | Co to | Zakres w serwerze |
| --- | --- | --- |
| `pl.aiwcorpo.mcp:mcp-platform-bom` | wersje: starter, test, `mcp-contracts`, Spring AI | `import` |
| `pl.aiwcorpo.mcp:mcp-platform-starter` | auto-konfiguracja (niżej) i transport MCP (streamable HTTP) | `compile` |
| `pl.aiwcorpo.mcp:mcp-platform-test` | `TestGateway`: JWKS i wewnętrzne JWT z kluczy testowych kontraktów | **tylko `test`** |

Wersja linii `mcp-platform` (3.x) nie ma związku z wersją kontraktów (2.x).

## Co robi starter na każdym żądaniu

```
gateway --mTLS + JWT C2 + X-MCP-Credential--> SecurityHeadersFilter
  -> CorrelationIdFilter     X-Request-Id -> MDC requestId
  -> InternalJwtFilter       certyfikat gateway'a, JWT C2 związany z żądaniem -> CallerContext; inaczej 401
  -> CredentialHeaderFilter  X-MCP-Credential(-Type) -> CredentialContext; nagłówki ukryte przed resztą
  -> transport MCP -> AuditingToolCallback -> [ToolCallInterceptor...] -> @Tool serwera
```

**`InternalJwtFilter` (C2).** Przepuszcza tylko żądanie z certyfikatem klienta o CN `mcp-gateway` i poprawnym
wewnętrznym JWT: ES256, `kid` z JWKS gateway'a, `iss`, `aud` == `server-id`, `exp`/`iat` z tolerancją do 30 s,
`exp - iat` do 60 s, claimy zgodne z `schemas/internal-jwt.schema.json` (walidowane schematem z jara kontraktów,
także wzorce `vsc` i `vs` razem z `vsc`), `jti` użyty pierwszy raz (Redis), `rid` == `X-Request-Id`, `tool`
obecny dokładnie przy `tools/call` i równy `params.name`, wariant serwisowy tylko dla `initialize`,
`server/discover`, `tools/list`, `ping` i `GET /mcp-manifest`. Klucze z identyfikatorem `test-*` i opublikowane klucze testowe
(rozpoznawane po SPKI z `mcp-contracts/security/published-test-keys.json`) są odrzucane, chyba że
`allow-test-keys=true` w profilu `dev`. Kolejność kontroli i powody odrzucenia są jak w referencyjnym
odbiorcy kontraktów, więc wszystkie 51 wektorów `test-vectors/jwt` dają ten sam wynik i powód.

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
`mcp-cedar-entities`, z którego skorzysta silnik polityk (beta).

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
  tools.unclassified: fail                                # fail | destructive
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
- zostały ustawienia 2.x (`guard.*`, `pat-header-prefix`);
- w profilu `dev` `server.address` nie jest adresem pętli zwrotnej (`127.0.0.1`, `::1`, `localhost`); starter
  ustawia w `dev` domyślnie `127.0.0.1`, więc odmowa dotyczy jawnego nadpisania (np. `SERVER_ADDRESS=0.0.0.0`).
  Serwer w kontenerze, do którego łączy się gateway, nie może więc działać w profilu `dev`;
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

**Tryb `dev-pat`** (tylko gdy `dev` jest jedynym aktywnym profilem): poza `X-MCP-Credential` serwer przyjmuje token
programisty w `X-PAT-<system>` (np. `X-PAT-Jira`) jako `Bearer` typu `user-secret`. Wewnętrzny JWT nadal jest
wymagany.

## Pierwszy serwer

Transport MCP ustawia starter (od 3.0.0-alpha-2, najniższy priorytet, serwer może nadpisać):
`spring.ai.mcp.server.protocol=STREAMABLE`, `spring.ai.mcp.server.type=SYNC`,
`spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp`. W 3.0.0-alpha-1 trzeba je ustawić samemu, inaczej
`/mcp` nie istnieje (404). Nazwę serwera podaje `spring.ai.mcp.server.name`.

Narzędzia rejestruje się beanem `ToolCallbackProvider`; sam bean z metodami `@Tool` nie wystarcza (Spring AI
odpowiada wtedy `Unknown tool`):

```java
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

@SpringBootApplication
public class JiraServer {

    @Bean
    ToolCallbackProvider jiraTools() {
        return SchemaLimitedToolCallbacks.of(new JiraTools());
    }

    public static class JiraTools {

        @Tool(name = "get_issue", description = "Returns an issue by key.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String issueKey) {
            // A real adapter calls Jira with: .headers(CredentialContext.require("jira")::applyTo)
            return issueKey + " read with a " + CredentialContext.require("jira").scheme().wireName() + " credential";
        }
    }
}
```

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

Start (zależności startera muszą być w `~/.m2`: `./mvnw install` w tym repozytorium, albo wydana wersja):

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=18600
# albo po ./mvnw package:
java -jar target/mcp-jira.jar --spring.profiles.active=dev --server.port=18600
```

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
co schemat mówi. Polityki nie da się wyłączyć beanem: `ToolInputPolicy`, `ToolCallbackAuditPostProcessor`, `RegisteredToolsCheck`
i `ToolHintsPostProcessor` nie są punktami rozszerzenia, a bean tych typów w serwerze zatrzymuje start poza `dev`
(opakowanie bierze politykę z aktywnych profili, nie z beana, więc bean innego typu jej nie zmieni). Własne zachowanie wokół narzędzi dodaje
się przez `ToolCallInterceptor`. Wzorce z `pattern` pisze serwer, więc unikaj wyrażeń o wykładniczym czasie dopasowania.

**Treść z systemu.** Wynik narzędzia z tekstem z systemu (opis, komentarz, tytuł, wiersz logu) zawiera
`pl.aiwcorpo.mcp.platform.text.UntrustedText` (`UntrustedText.plain(raw, maxLength)` albo `markdown(...)`: tnie do limitu;
konstruktor normalizuje tekst przez `TextHygiene.normalize`, a usunięty ciąg niewidocznych znaków zastępuje jedną spacją,
żeby nie skleić tokenów). Serwer, który usuwa z tekstu składnię (obrazy, linki, makra, wzmianki), normalizuje surowy tekst
**przed** tym filtrem i dopiero potem woła fabrykę. Wynik musi być rekordem; starter publikuje wtedy `outputSchema`
z definicją `UntrustedText` z kontraktów (wchodzi do `definitionHash`) i zwraca wynik także jako `structuredContent`.
Pola dodatkowe systemu idą obok `UntrustedText` w rekordzie wyniku.

**Zasada nullowalności wyniku.** Schemat wyniku wynika z typu rekordu: komponent bez `@Nullable` (dowolnej biblioteki)
jest `required` i nie przyjmuje `null`, komponent z `@Nullable` przyjmuje `null`. Pole, które system potrafi zostawić
puste (`assignee`, `dueDate`, `resolution`), musi więc mieć `@Nullable`; nazwy pól to nazwy komponentów (adnotacje
Jacksona `@JsonProperty`, `@JsonIgnore` nie są uwzględniane w schemacie). Opakowanie narzędzia sprawdza wynik względem
schematu **przed** zapisem audytu: niezgodność to C10 `internal_error`, zdarzenie C8 z `outcome=error` i wiersz
ERROR z ścieżką pola (`/assignee (type)`), bez wartości. Komunikat SDK (`output validation failed`) nie dochodzi do klienta.

**Audyt odrzuceń.** Odrzucone żądanie (brak/zły JWT, certyfikat, niedostępny JWKS albo Redis) zostawia w audycie C8
zdarzenie `authentication` (`outcome=denied`, `reason` = kod odrzucenia) tylko z pól dozwolonych w kontraktach 2.1.1,
bez `sub`, `client`, `tool` z niezweryfikowanego tokenu.

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

## Identyfikatory zasobów a `mcp-cedar-entities`

BOM zarządza wersją `mcp-cedar-entities` (ta sama co `mcp-contracts`), żeby gateway, starter i serwer używały
jednej. Serwery **nie** powinny go dodawać tylko po wzorce identyfikatorów: wciąga natywne `cedar-java` (uber jar
z bibliotekami dla macOS i Linuksa). Wzorce dostają przez `@ArgumentLimits(contractPattern = ...)`. Moduł będzie
potrzebny dopiero przy `@ResourceRef` i silniku polityk (beta), po stronie startera.

## Testy serwera bez gateway'a

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
  <version>3.0.0-alpha-7</version><type>pom</type><scope>import</scope>
</dependency>
```

## Wersjonowanie

SemVer, jedna wersja dla wszystkich modułów. Wydania wstępne: `3.0.0-alpha-N`, `3.0.0-beta-N` (porządek Mavena:
`alpha-1 < alpha-2 < beta-1 < 3.0.0`). Zmiana łamiąca: nowa wersja major i opis w `MIGRATION-<wersja>.md`.
