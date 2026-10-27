# mcp-platform: biblioteka serwerów MCP za gateway'em

Wspólna biblioteka serwerów MCP platformy (`mcp-jira`, `mcp-confluence`, ...). Od 3.0 serwer stoi wyłącznie za
gateway'em: nie loguje użytkowników, nie decyduje o uprawnieniach do narzędzi i nie pyta o potwierdzenia, to
robi gateway. Biblioteka weryfikuje wewnętrzny JWT gateway'a, odbiera poświadczenie do systemu docelowego,
audytuje każde wywołanie i pilnuje, żeby z narzędzia wychodziły tylko bezpieczne błędy.

Stos: Java 25, Spring Boot 4.1.0, Spring AI 2.0.0 (MCP SDK 2.0.0), kontrakty `mcp-contracts` 2.0.2.
Wersja: **3.0.0-alpha-5** (stan i plan w `STATUS.md`, zmiany względem 2.x w `MIGRATION-3.0.md`).

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
`tools/list`, `ping` i `GET /mcp-manifest`. Klucze z identyfikatorem `test-*` i opublikowane klucze testowe
(rozpoznawane po SPKI z `mcp-contracts/security/published-test-keys.json`) są odrzucane, chyba że
`allow-test-keys=true` w profilu `dev`. Kolejność kontroli i powody odrzucenia są jak w referencyjnym
odbiorcy kontraktów, więc wszystkie 43 wektory `test-vectors/jwt` dają ten sam wynik i powód.

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
`defaultStatusHandler`.

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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

@SpringBootApplication
public class JiraServer {

    @Bean
    ToolCallbackProvider jiraTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new JiraTools()).build();
    }

    public static class JiraTools {

        @Tool(name = "get_issue", description = "Returns an issue by key.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String getIssue(String issueKey) {
            // A real adapter calls Jira with: .headers(CredentialContext.require("jira")::applyTo)
            return issueKey + " read with a " + CredentialContext.require("jira").scheme().wireName() + " credential";
        }
    }
}
```

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
  `X-MCP-Credential-Type` dostaje `user-secret`. W narzędziu widać je jako `CredentialContext.require(system)`.
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
  <version>3.0.0-alpha-5</version><type>pom</type><scope>import</scope>
</dependency>
```

## Wersjonowanie

SemVer, jedna wersja dla wszystkich modułów. Wydania wstępne: `3.0.0-alpha-N`, `3.0.0-beta-N` (porządek Mavena:
`alpha-1 < alpha-2 < beta-1 < 3.0.0`). Zmiana łamiąca: nowa wersja major i opis w `MIGRATION-<wersja>.md`.
