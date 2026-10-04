# mcp-platform — wspólna biblioteka dla serwerów MCP

Jeden, wersjonowany fundament, od którego zależą serwery MCP w organizacji. Skupia cross-cutting
(bezpieczeństwo, TLS-konwencja, obserwowalność, mapowanie błędów) oraz transport MCP, dzięki czemu
poprawka bezpieczeństwa = jedno wydanie platformy i bump wersji u serwerów — bez kopiuj-wklej.

**Stos: Java 25 (LTS), Spring Boot 4.1.0, Spring AI 2.0.0**.

## Co zawiera

```
mcp-platform/
├── mcp-platform-bom/       # zarządzanie wersjami (import w serwerach): starter + Spring AI BOM
└── mcp-platform-starter/   # Spring Boot auto-configuration (jar) — jedyna zależność serwera
    └── pl.aiwcorpo.mcp.platform.{security,guard,observability,error,tls,autoconfigure}
```

Starter po dodaniu na classpath serwera automatycznie konfiguruje:
- **Poświadczenia do backendów** — w trybie `pat` `PatForwardingFilter` wyciąga per-request PAT-y
  z nagłówków `X-PAT-<system>` do `CredentialContext`; w trybie `oauth` trafia tam token dostępu
  wywołującego. Adaptery forwardują go do backendów, więc każdy użytkownik działa tam jako on sam. `SecurityHeadersFilter` dodaje HSTS/nosniff/frame-deny.
- **Observability** — `CorrelationIdFilter` + strukturalny `AuditLogger` wywołań narzędzi. Audyt jest
  wpinany automatycznie: `ToolCallbackAuditPostProcessor` opakowuje każdy bean `ToolCallbackProvider`
  w `AuditingToolCallback`, więc serwer nie musi o niczym pamiętać.
- **Error** — bezpieczne mapowanie wyjątków na błędy MCP (bez wycieku szczegółów). Egzekwowane w tym
  samym dekoratorze: **jedynym wyjątkiem opuszczającym narzędzie jest `SanitizedToolException`**.
- **Tool guard** (od 2.0.0) — przed każdym narzędziem: kim jest wywołujący, czy ma uprawnienie do
  narzędzia o tym poziomie ryzyka i czy człowiek potwierdził akcję destrukcyjną. Opis niżej.
- **Transport MCP** — pociąga `spring-ai-starter-mcp-server-webmvc` tranzytywnie (streamable-HTTP).

### Tool guard

Deterministyczny punkt egzekwowania polityki: tylko kod i plik polityki, bez pytania modelu.

```
tools/call ─► IdentityFilter ─► AuditingToolCallback ─► ToolGuard ─► @Tool serwera
              Who am I?                                 1. narzędzie włączone?
              (Authorization: Bearer)                   2. uprawnienie wywołującego ≥ ryzyko narzędzia?
                                                        3. potwierdzenie człowieka (kod jednorazowy)?
```

**Flaga ryzyka** — obowiązkowa na każdym `@Tool`; serwer z narzędziem bez flagi nie wstaje:

```java
@Tool(name = "order_cancel", description = "Cancel an order.")
@ToolRisk(RiskLevel.UPDATE)          // READ_ONLY | UPDATE | DESTRUCTIVE
public Order cancel(String id) { ... }
```

Flaga jest też publikowana klientowi MCP jako `readOnlyHint` / `destructiveHint`. To podpowiedź dla
klienta; egzekwowanie nie zależy od tego, czy klient ją uszanuje.

**Tryb uwierzytelniania** — jeden wybór dla całego serwera, `aiwcorpo.mcp.platform.auth-mode`:

| | `oauth` (domyślny) | `pat` |
|---|---|---|
| Co wysyła klient | `Authorization: Bearer <token JWT>` | `X-PAT-<system>: <token backendu>` |
| Kim jest wywołujący | użytkownik z tokenu (`sub`), zweryfikowany | `pat:<skrót tokenu>`, niezweryfikowany |
| Skąd uprawnienia | role z tokenu → `roles` w polityce | jeden poziom dla wszystkich: `pat` w polityce |
| Co idzie do backendu | ten sam token dostępu | PAT użytkownika |
| Dostawca tożsamości | wymagany (`guard.oidc`) | brak |
| Drugi rodzaj poświadczenia | `X-PAT-*` jest ignorowany | token Bearer daje 401 |

Adapter wyjściowy nie musi znać trybu: `CredentialContext.requireBackendToken("jira")` zwraca
właściwy token albo rzuca `MissingPatException` z informacją, co klient ma wysłać.

Dwie rzeczy, które trzeba wiedzieć przed wyborem:
- **`pat`:** serwer nie potrafi sprawdzić PAT-a, robi to dopiero backend. Dowolna wartość w `X-PAT-*`
  daje na serwerze poziom `pat`, więc guard ogranicza tu, co posiadacz tokenu może *spróbować*,
  a o tym, co może *zrobić*, decyduje backend. Trzymaj `pat` nisko (domyślnie read-only).
- **`oauth`:** do backendu trafia token wydany dla tego serwera (`aud`). Działa, gdy backend ufa
  temu samemu dostawcy i akceptuje tę publiczność. Przekazywanie tokenu dalej jest uproszczeniem,
  którego specyfikacja autoryzacji MCP odradza; docelowo: token exchange (osobny token per backend).

**Tożsamość w trybie `oauth` (OAuth 2 / OIDC)** — serwer jest *resource serverem*: przyjmuje token dostępu JWT
(`Authorization: Bearer`) wydany przez dostawcę OIDC i go waliduje: podpis względem kluczy dostawcy
(JWKS, znajdowane przez discovery), `iss`, `exp` i `aud`. Serwer nie prowadzi logowania i nie widzi
haseł; token zdobywa klient MCP u dostawcy.

```yaml
aiwcorpo.mcp.platform.guard.oidc:
  issuer-uri: https://login.example.com/realms/corp   # jedyne wymagane pole
  audiences: [mcp-server]          # identyfikator tego serwera u dostawcy; ustaw zawsze
  subject-claim: sub               # co trafia do audytu jako wywołujący (np. preferred_username)
  role-claims: [roles, groups, realm_access.roles, permissions, scope, scp]
  jwk-set-uri:                     # tylko gdy klucze nie mają być brane z discovery
  resource-uri:                    # publiczny URL endpointu MCP; domyślnie wyliczany z żądania
```

Domyślne `role-claims` pokrywają Keycloak (`realm_access.roles`), Entra ID (`roles`, `groups`, `scp`),
Okta i Auth0 (`groups`, `permissions`, `scope`). Role klienta w Keycloak dodasz jako
`resource_access.<client>.roles`. Znalezione role są mapowane na uprawnienia w pliku polityki —
dostawca mówi, kim jesteś i w jakich grupach; plik polityki mówi, co to znaczy na tym serwerze.

- Brak nagłówka: wywołujący anonimowy na poziomie `anonymous` z polityki (domyślnie read-only).
- Token nieprawidłowy (zły podpis, inny wydawca, inna publiczność, po terminie): HTTP 401, nigdy
  ciche obniżenie do anonimowego. Powód trafia do logu, nie do odpowiedzi.
- Ważny token bez żadnej zmapowanej roli: poziom `authenticated` (domyślnie read-only).
- `GET /.well-known/oauth-protected-resource` zwraca metadane RFC 9728 wskazujące dostawcę, a 401
  niesie `WWW-Authenticate: Bearer resource_metadata=...` — klient MCP może sam zacząć logowanie.
- Bez `guard.oidc` serwer nie ma dostawcy tożsamości: wszyscy są anonimowi, a tokeny Bearer są
  odrzucane (nie da się ich sprawdzić).

Tokeny nieprzezroczyste (introspekcja), mTLS itp.: zarejestruj własny bean `IdentityResolver`.

**Potwierdzanie akcji destrukcyjnych** — kod `XXX-XXX` jest związany z wywołującym, narzędziem
i dokładnymi argumentami, działa raz, wygasa i ma limit prób. Błędne kody liczą się per wywołujący,
także między kolejnymi kodami: po `max-attempts` pomyłkach nowe kody nie są wysyłane do końca okna
(jeden TTL), a liczba kodów na okno jest ograniczona. Zatwierdzający widzi nazwę narzędzia i argumenty
w ustalonej kolejności kluczy; gdy się nie mieszczą, komunikat mówi wprost, że są obcięte. Dwa tryby:

| Klient | Przebieg |
|--------|----------|
| wspiera MCP elicitation | wywołanie czeka, klient pokazuje użytkownikowi pole na kod, po poprawnym kodzie narzędzie się wykonuje |
| nie wspiera | pierwsze wywołanie wraca jako `confirmation_required`; agent pyta użytkownika o kod i ponawia wywołanie z tymi samymi argumentami plus `confirmation_code` |

Kod dostarcza `ConfirmationCodeSender` kanałem, którego model nie czyta. **Domyślna implementacja
wypisuje kod do logu serwera (`pl.aiwcorpo.mcp.confirmation`) i służy wyłącznie do demo** — kto czyta
ten log, może zatwierdzać akcje destrukcyjne. Przed produkcją zarejestruj własny bean (push, SMS,
komunikator).

**Plik polityki** (`aiwcorpo.mcp.platform.guard.policy-file`, np. `file:/etc/mcp/guard-policy.yml`):

```yaml
anonymous: read-only              # bez poświadczeń: read-only | update | destructive | deny
authenticated: read-only          # tryb oauth: ważny token bez zmapowanej roli
pat: read-only                    # tryb pat: każdy, kto przysłał X-PAT-*
roles:                            # rola / grupa / scope z tokenu -> co daje na tym serwerze
  mcp-operator: { max-risk: update }
  mcp-admin:
    max-risk: destructive
    deny-tools: [repo_purge]
tools:
  repo_archive: { enabled: false }      # wyłącznik awaryjny
  repo_rename:  { risk: destructive }   # polityka może ryzyko tylko podnieść
  repo_comment: { confirmation: true }  # wymuś potwierdzenie
confirmation:
  required-from: destructive      # od jakiego poziomu wymagany kod | never
  ttl-seconds: 120
  max-attempts: 3
```

Plik jest czytany ponownie, gdy się zmieni — zmiana działa od następnego wywołania, bez restartu.
Błędny lub pusty plik przy starcie zatrzymuje serwer; błędna edycja w trakcie pracy zostawia w mocy
ostatnią poprawną politykę i loguje błąd. Nieznany klucz to błąd, a nie cicho zignorowana literówka;
reguła dla narzędzia, którego serwer nie ma, daje ostrzeżenie `guard_policy_unknown_tools`.
Podmieniaj plik atomowo (`mv` nowego pliku w miejsce starego): pliku uciętego w miejscu, w którym
nadal jest poprawnym YAML-em, nie da się odróżnić od zamierzonej zmiany.

Kody odmowy widziane przez klienta: `unauthenticated`, `access_denied`, `tool_disabled`,
`confirmation_required`, `confirmation_failed`, `confirmation_declined`.

W pliku polityki nie ma użytkowników ani tokenów. Wywołujący dostaje najwyższy `max-risk` spośród
swoich ról; listy `deny-tools` się sumują; `allow-tools` zawęża tylko wtedy, gdy każda z jego ról ma
taką listę.

**Ograniczenia, o których trzeba wiedzieć:**
- Tylko tokeny JWT. Unieważnienie tokenu przed `exp` nie jest sprawdzane — trzymaj krótki czas życia
  tokenów u dostawcy.
- Tryby `oauth` i `pat` się wykluczają; serwer nie przyjmuje obu rodzajów poświadczeń naraz.
- `tools/list` zwraca wszystkie narzędzia każdemu; uprawnienia są egzekwowane przy wywołaniu.
  Listę „co mi wolno" daje narzędzie `platform_whoami`.
- Narzędzia `@McpTool` omijają guard, więc serwer z takim narzędziem nie wstaje. Używaj `@Tool`.
- Pole `confirmation_code` trafia do schematu narzędzi, które przy starcie nie są read-only.
  Narzędzie read-only, któremu polityka w trakcie pracy podniesie ryzyko albo wymusi potwierdzenie,
  da się potwierdzić tylko przez elicitation, dopóki serwer nie zostanie zrestartowany.
- Stan potwierdzeń jest w pamięci jednej instancji. Kilka instancji za load balancerem wymaga
  wspólnego magazynu albo sticky sessions.
- Tożsamość, jak PAT-y, żyje w `ThreadLocal` — stąd wymóg transportu SYNC (`CredentialTransportGuard`).

### Dlaczego dekorator, a nie `ToolExecutionExceptionProcessor`

`ToolExecutionExceptionProcessor` to hak strony **klienckiej** (`ToolCallingManager`); w serwerze MCP
nikt go nie wywołuje. Faktyczna ścieżka to `McpToolUtils`, który łapie `Exception` wokół
`ToolCallback.call(...)` i wkłada **`e.getMessage()` wprost** do `CallToolResult` (`isError=true`).
Bez dekoratora `HttpClientErrorException` z `RestClienta` oddaje modelowi URL backendu i fragment ciała
odpowiedzi. Dlatego platforma opakowuje `ToolCallback`, a nie polega na hakach frameworka.

Mapowanie: `MissingPatException` → `missing_credential` (komunikat przepuszczany — klient musi wiedzieć,
że brakuje nagłówka), `IllegalArgumentException` → `invalid_argument` (komunikat przepuszczany — model
ma poprawić własne wywołanie), `SecurityException` → `forbidden`, reszta → `internal_error`.
**Niezmiennik:** `IllegalArgumentException` nigdy nie może nieść treści z odpowiedzi backendu.

**Od 1.0.2 mapper idzie po łańcuchu przyczyn**, bo inaczej powyższe trzy reguły są martwe: Spring AI
opakowuje wszystko, co wychodzi z metody `@Tool`, w `ToolExecutionException`, więc mapper nigdy nie
widział wyjątku, który narzędzie faktycznie rzuciło. Zapomniany nagłówek `X-PAT-*` wracał do klienta
jako `internal_error`. Na każdym poziomie łańcucha najpierw pytane są `ToolErrorMapping` serwera,
potem reguły platformy; `SanitizedToolException` napotkany w łańcuchu przechodzi bez zmian.

### Dlaczego serwer z `type: ASYNC` nie wstaje

`CredentialContext` to `ThreadLocal` wypełniany na wątku servletowym. Handler asynchroniczny biegnie
na innym wątku, więc **każde** narzędzie zachowywałoby się tak, jakby wywołujący nie podał PAT-a —
bez błędu i bez ostrzeżenia. `CredentialTransportGuard` przerywa więc start przy
`spring.ai.mcp.server.type=ASYNC` (wyłącznik: `aiwcorpo.mcp.platform.require-sync-transport=false`).

Tego samego dotyczy `immediateExecution(true)`, które Spring AI ustawia na stosie servletowym: nie da
się go sprawdzić właściwością, więc pilnuje go `CredentialPassthroughTest` — prawdziwe wywołanie
narzędzia po prawdziwym transporcie, sprawdzające, że PAT dociera do handlera. Jeden test dla całej
floty, psujący się przy budowie platformy, a nie przy dziewięciu wdrożeniach.

### Czego audyt NIE zapisuje

Nie zapisuje wartości argumentów (to tekst od modelu: treści issue, opisy snapshotów), PAT-ów ani
kodów potwierdzeń — tylko nazwę narzędzia, wywołującego, poziom ryzyka, correlation id, wynik
z kodem powodu, latencję, długość argumentów i zbiór systemów, dla których przekazano PAT.

`outcome` przyjmuje wartości `ok`, `error`, `denied` (guard odmówił) i `confirmation_pending`
(czeka na kod); wykonana akcja zatwierdzona przez człowieka ma `reason="user_confirmed"`.
`subject` to wywołujący rozpoznany przez guard (`anonymous`, gdy nie podał tokenu). Przy wyłączonym
guardzie zostaje `-`, a tożsamość jest tylko w logu audytowym backendu — łącz oba logi po
correlation id.

Serwer dostarcza własną domenę, use-case'y, integracje, beany `@Tool` i `ToolCallbackProvider`.

## Budowa

```bash
./mvnw install                    # zbuduj + zainstaluj lokalnie (mcp-platform-bom + starter)
```

Serwery biorą bibliotekę z lokalnego repozytorium Mavena. Żeby publikować ją do wspólnego
repozytorium, dodaj `<distributionManagement>` do `pom.xml` agregatora i startera.

## Jak używa tego serwer

Serwer importuje BOM i dodaje jedną zależność (bez wersji — pilnuje jej BOM):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>pl.aiwcorpo.mcp</groupId><artifactId>mcp-platform-bom</artifactId>
      <version>2.0.0</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
...
<dependency>
  <groupId>pl.aiwcorpo.mcp</groupId><artifactId>mcp-platform-starter</artifactId>
</dependency>
```

Wersja Spring Boot w `<parent>` serwera musi być taka sama jak w `<parent>` startera (4.1.0).

## Wersjonowanie (SemVer)
- **patch/minor** — poprawka bezpieczeństwa/TLS, nowe opcje kompatybilne wstecz. Serwery: bump BOM.
- **major** — zmiana łamiąca (np. inny model auth). Wymaga migracji serwerów; opisz ją w tym README.

## Rozszerzanie
Nowy wspólny mechanizm (np. rate-limiting) dodajesz **tu** jako kolejną auto-konfigurację i wpis w
`AutoConfiguration.imports`. Po wydaniu wszystkie serwery dostają go przez bump wersji.
