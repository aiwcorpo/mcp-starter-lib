# STATUS - mcp-starter-lib (ws-02)

## 4.0.0: serwery dynamiczne (2026-11-06)

Wersja 4.0.0, kontrakty 7.0.0 (CC-229 ... CC-237). Polityki serwera z jara (`mcp/policies/`), dane polityki z `GET /internal/v1/policy-data` po mTLS,
bramka `PolicyDataGate` (zatwierdzony `policiesHash`, rewizja jako licznik, nadpisania walidowane), wygenerowane polityki platformy, haki resolverów zamiast
`GrantProbe`, `PolicyDecisions` i `PolicyDataEndpoint` w `mcp-platform-test`. `./mvnw -B verify`: starter 855 testów, mcp-platform-test 169. Opis i migracja: `CHANGELOG.md`.
Lokalne, niewypchnięte: wydanie i tag czekają na zgodę właściciela. Nie sprawdzone na prawdziwym gatewayu (jeszcze go nie ma w wersji 2.0.0): mTLS z `tls-name`
sprawdzony tylko testem nazwy w certyfikacie (`GatewayTlsTest`), nie połączeniem.

## 3.0.0: publikacja na Maven Central (przygotowanie, 2026-11-04)

Wersja 3.0.0 (z 3.0.0-beta-15-SNAPSHOT), zależność od kontraktów 6.3.2. Metadane POM i profil `release` (sources, javadoc, GPG z `MAVEN_GPG_PASSPHRASE`, `central-publishing-maven-plugin` 0.10.0, `autoPublish=false`); `./mvnw -Prelease -Dgpg.skip verify` przechodzi, javadoc bez błędów. `release.yml`: `publish` bez narzędzi budowania, podpis i wysyłka do Portalu (`USER_MANAGED`); `ci.yml` bez sekretów `MCP_MAVEN_REPOSITORY_*` i bez środowiska `ci-main`; akcje z komentarzem wersji przy SHA. Niesprawdzone bez konta: wysyłka do Portalu i walidacja metadanych bez e-maila programisty. Wydanie czeka na kontrakty 6.3.2 na Central i na kroki człowieka z README ("Releasing"). Tag `v3.0.0` jeszcze nie istnieje. Poprawki workflow po przeglądzie (2026-11-04): job `publish-maven` pomija wysyłkę, gdy wersja jest już na Central; weryfikacja podpisów i odcisku klucza (`vars.GPG_FINGERPRINT`); treść błędu Portalu w logu; błąd, gdy wdrożenie nie osiągnie VALIDATED/PUBLISHING/PUBLISHED; filtr tagu tylko `vX.Y.Z`; `sbom` bez `.ci/settings.xml`; `setup-java` 4.9.1 jak w mcp-contracts; `github-release` pomija istniejące wydanie.

## 3.0.0-beta-14: kontrakty 6.3.2

CQL z `AND type = page` (CC-219); tryb odbiorcy JWT `native` / `adapter` (CC-226, `classification_missing`, `CallerContext.mcpRisk/mcpAction`, log i licznik wartości innej niż `READ_ONLY`); `decision` opcjonalne w zdarzeniu `authentication` (starter zapisuje nadal). Wektory `jwt/` (66, z `receiverMode`), `audit-events/` (128), `query-rendering/` (27) przechodzą. Migracja: `MIGRATION-3.0.md`, "Z beta-13 na beta-14".

## 3.0.0-beta-13: kursor szyfrowany

Zgłoszenie z przeglądu serwera OpenSearch: kursor był podpisany, ale czytelny, więc pozycja (wartości sortowania ukrytego trafienia) wyciekała do klienta. `CursorCodec` jest teraz AEAD: AES-256-GCM, klucz z `cursor-key` przez HKDF-SHA256 z `server-id`, losowy nonce 12 B, AAD = `sub` + narzędzie + odcisk zapytania, jeden `invalid_argument` dla każdego błędu. API bez zmian. Testy: ładunek nieczytelny i nonce losowy, zmiana każdego bajtu, inny zapis tych samych bajtów, inny serwer z tym samym kluczem.

## Poprawki po przeglądzie beta-11 i wydanie 3.0.0-beta-12

Źródło: `review-starter-beta11.md`. Zgodność: tylko dodatki w API, przejście beta-11 -> beta-12 to podbicie wersji (`MIGRATION-3.0.md`, "Z beta-11 na beta-12").

| Punkt | Co zrobione |
| --- | --- |
| W-0 | tag `v3.0.0-beta-11` jest lokalny i nie jest do publikacji przez Actions (jego `release.yml` jest sprzed przeglądu: akcje bez SHA, token zapisu w `env` całego joba, cache w jobie publikacji); pierwsze publiczne wydanie to nowy tag. Zapisane w README i nagłówku `release.yml`; w środowisku `release` wymagani recenzenci i reguła tylko dla tagów `v*` (opis w nagłówku workflow) |
| W-1 | kontrakty czytane `GITHUB_TOKEN` przebiegu z `packages: read` (bez PAT-a w sekretach), dostęp przyznany w "Manage Actions access" każdego pakietu mcp-contracts; opis w nagłówku `release.yml` |
| W-2 | runner GitHub działa jako usługa systemd, więc `INVOCATION_ID` bywa ustawione i testy zakładające maszynę programisty się pomijają, a `release` odmawia. Job `verify` wypisuje (same nazwy) obecne sygnały i woła `env -u INVOCATION_ID ci/run.sh release`: zmienna usługi runnera jest zdejmowana tylko dla tego procesu, w kodzie nie ma obejścia (sygnał kontenera lub poda nadal zatrzymuje wydanie). Nie sprawdzone na prawdziwym runnerze |
| Workflow | `timeout-minutes`, `concurrency` (release bez anulowania, CI anuluje tylko pull requesty), obraz CI po digeście (`sha256:4f82a03a...`, z lokalnego obrazu), `maven-help-plugin:3.5.1`, jary budowane w jobie `build` (`contents: read`, bez cache), job `publish` (`packages: write`, bez checkoutu i budowy) tylko wgrywa gotowe pliki przez `deploy:deploy-file`; SBOM budowany w jobie `sbom` (`contents: read`), przekazywany jako artefakt do jobu `github-release` (`contents: write`, bez checkoutu i narzędzi budowy: tylko pobranie artefaktu i `gh release create`) |
| S-1 | `PageFiller` (dopełnianie z limitem odczytów 1..20, `exhausted`), README opisuje sygnał, który zostaje po wyczerpaniu limitu; test przez transport (`paged_issues`, `filled_issues`) |
| S-2 | brak ponownego uruchomienia sondy, dopóki poprzednie wywołanie trwa; pula = liczba sond; test sondy ignorującej przerwanie |
| S-3 | Javadoc i README: serwer nie trzyma sekretów, sonda tylko endpointy bez logowania, GitLab wymaga decyzji o poświadczeniu od brokera |
| N-1 ... N-8 | N-1 `server-id` w podpisie; N-2 ostrzeżenie leniwie (przy pierwszym kursorze), bez zatrzymania startu, żeby przejście było zgodne; N-3 jednoznaczne kodowanie; N-4 `requireNonNull`; N-5 `ProductVersion`; N-6 uwaga o `show-details` w README; N-7 `start()` jednokrotne, Javadoc; N-8 Javadoc `detail` |
| T-1 ... T-3 | test przez transport z `PagedResult` i `@ResourceRef` (element usunięty, kursor zostaje), kursor innego użytkownika, zmieniony bajt podpisu i inne zapisy, test ponownego planowania i `destroy()` |

## M6 etap 3: kontrakty 6.2.1, elementy pod pełne serwery, porządki do wydania 3.0.0-beta-11

Kontrakty: `pl.aiwcorpo.mcp:mcp-contracts:6.2.1` (polecenie mówiło 6.2.0; 6.2.1 to to samo bajt w bajt plus licencja, 6.2.0 nie jest wydane do GitHub Packages (workflow wydania kontraktów powstał w 6.2.1), więc publiczne wydanie startera nie miałoby czego rozwiązać).

| Kamień | Zakres | Stan |
| --- | --- | --- |
| K1 | BOM, starter i mcp-platform-test na kontraktach 6.x; README: powtarzalne `UPDATE` (CC-204); liczba wektorów `audit-events/` 115 -> 125; bez zmian w kodzie (`ManifestExport` nie ustawia `source`, nazwy `jwks_unavailable` i `replay_store_unavailable` zgodne) | zrobione |
| K2 | `CursorCodec` (kursor HMAC związany z `sub`, narzędziem i zapytaniem), `PagedResult<T>` (`truncated` / `omitted` bez liczników filtru), `ToolOutputSchemas` dla rekordów generycznych; `CapabilityProbe` z trybem `degraded`, logiem, metryką i szczegółem zdrowia | zrobione |
| K3 | Apache-2.0 (`LICENSE`, `NOTICE`, także w `META-INF` jarów), `distributionManagement` na GitHub Packages, `release.yml` (niewykonany), `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, README dla autora serwera (instalacja), `ci/run.sh` szuka JDK 25 bez ścieżek Homebrew, bash 3.2 | zrobione |
| K4 | wydanie 3.0.0-beta-11 (tag `v3.0.0-beta-11`, instalacja w `~/.m2`, potem `3.0.0-beta-12-SNAPSHOT`) | zrobione |

Propozycje kontraktów (w `mcp-contracts/CONTRACT_CHANGES.md`, status `proposed`): CC-208 (zdarzenie audytu `backend_capability`), CC-209 (definicje `PageCursor`, `PagedResultFields`, `maxResultBytes` w manifeście).
Z badania pełnych serwerów (pkt 5) w starterze nie ma: `Basic` w `user-secret` i metadanych `deployment` (broker i kontrakt), adaptera wyboru po profilu zdolności (jest `Capabilities.supports(cecha)`, wybór robi serwer), `AdfText` (decyzja badania: zostaje w serwerze Jira),
nowych typów zasobów C7 (kontrakt) i poziomu ryzyka pośredniego (decyzja człowieka).

Poza zakresem: przebieg `release.yml` i publikacja do GitHub Packages (repozytorium i pakiety nie istnieją), SBOM (wtyczka CycloneDX nie jest w `~/.m2`; krok jest w workflow), ścieżka Windows poza WSL2.

Linia `pl.aiwcorpo.mcp:mcp-platform*` 3.x (niezależna od wersji kontraktów). Kontrakty:
`pl.aiwcorpo.mcp:mcp-contracts:6.3.2` od beta-14 (6.2.1 w beta-11 ... beta-13, 5.1.0 w beta-10, 4.1.0 od beta-7; 4.0.0 w beta-6; 3.1.0 w beta-1 ... beta-5, 2.2.5 w alpha-12 ... alpha-14, 2.1.1 od alpha-7, 2.0.2 do alpha-6).

## M5: przejście na kontrakty 5.1.0 i wydanie 3.0.0-beta-10

Wzorzec zmian: CHANGELOG kontraktów 5.0.0 i 5.1.0, "Migracja" dla ws-02 (zapisane linki, claimy `vs` / `vsc` i `context.virtualServer` / `vsScopes` usunięte; CC-173, CC-174). Kamienie (po każdym: testy, commit, ten plik):

| Kamień | Zakres | Stan |
| --- | --- | --- |
| K1 | BOM, starter i mcp-platform-test na 5.1.0; `CallerContext` bez `virtualServer` / `virtualServerScopes`; weryfikator bez `vs` / `vsc` (token z którymkolwiek: `401 invalid_claims` ze schematu kontraktu); `Caller` bez `withVirtualServer`; `QueryConstraint(resourceType, granted)`; `QueryScope` bez zawężenia linkiem (`linkIds()` tylko po `narrowedTo`, `linkType()` usunięte); zdarzenia audytu bez `virtualServer`, bez powodu `link_scope`; `RecordingAuthorizer` usunięty; `TestGateway.Token.extraClaim` zamiast `virtualServer(...)`; test paczki z kontraktów 4.1.0 (`schema_mismatch`); wektory 5.1.0 (`jwt/` 59, `audit-events/` 115, `result-filter/` 7) | zrobione |
| K2 | pkt E: `ci/run.sh` (`verify`, `hygiene`, `release`), `make ci`, nakładki `.gitlab-ci.yml`, `Jenkinsfile`, `.github/workflows/ci.yml`, `.ci/settings.xml` (rejestr Maven przez `MCP_MAVEN_REPOSITORY_URL` jak w mcp-policies); `clean verify`, błąd przy padniętym Mavenie (atrapa `mvnw` z `exit 1`) i przy 0 testów, nieznany krok i pusty argument to kod 2; testy zakładające maszynę programisty same się pomijają przy sygnale wdrożenia (16 w kontenerze), zadanie zmian bez gniazda Dockera i sekretów main; tylko lokalnie i w obrazie, nie na runnerze | w toku |
| K3 | README, `MIGRATION-3.0.md` "Z beta-9 na beta-10", wydanie 3.0.0-beta-10 (main, czyste drzewo, tag `v3.0.0-beta-10`, potem `3.0.0-beta-11-SNAPSHOT`) | zrobione |

Usunięte pliki: `LinkScopeTest` (CC-146c dotyczyło zawężenia linkiem, które nie istnieje). Zmienione oczekiwania: `ResourcePolicyTest`, `ExampleServerResourcesTest`, `ScopeGrantTest` bez wariantów z linkiem; `ContractVectorsOnResourcePolicyTest` nie sprawdza już `virtualServer` w `queryConstraints`
(wektor `result-filter/servicedesk-tickets-by-grant` budowany z `service-account` jako typem domyślnym, bo wektor liczono bez `context.credentialType`, co `credential-type.cedar` traktuje jak konto serwisowe).

Poza zakresem: przebieg na prawdziwym runnerze GitLab/Jenkins/GitHub (sprawdzono lokalnie i w obrazie `maven:3.9.12-eclipse-temurin-25` z gniazdem Dockera), publikacja do rejestru Maven (D12: lokalnie `~/.m2`), uruchomienie 16 pominiętych w kontenerze testów na zwykłym runnerze.

## M4: przejście na kontrakty 4.0.0 i wydanie 3.0.0-beta-6

Wzorzec zmian: CHANGELOG kontraktów 4.0.0, "Migracja per komponent", mcp-starter-lib (ws-02). Kamienie pośrednie (po każdym: pełny `./mvnw clean install`, commit, ten plik); wydanie na końcu K3:

| Kamień | Zakres | Stan |
| --- | --- | --- |
| K1 | kontrakty 4.0.0 w BOM, starterze i mcp-platform-test; klucze `dev-` (JWT i paczki) tylko w trybie dev (`devstack` + `allow-test-keys`); schemat 4.0.0 (paczka 3.1.x -> `schema_mismatch`); `context.credentialType` z konfiguracji (`credential-type`, wymagane, brak -> wyjątek); stały `message` dla `not_found_or_denied`; `upstream_unavailable` i powody `policy_unavailable` / `policy_error` przy braku paczki albo błędzie silnika (CC-141); manifest z `environment: dev` w profilu `devstack`; licznik `mcp_audit_authentication_suppressed_total{component,reason}`; 400/413 przed JWT bez zdarzenia (CC-152); bez obejścia wzorca `OpenSearchIndexPrefix` (CC-139) | zrobione |
| K2 | kolejność ocen "grant przed budową encji" (CC-146, `GrantProbe`); `nothingToQuery()` i `requireQueryable()` (CC-147); `StructuredQuery` z normatywnych list pól i operatorów oraz układem z kontraktu (CC-160, wszystkie 26 wektorów `query-rendering/`); API rozwijania wzorców indeksów OpenSearch do nazw bez aliasów (`resolveIndexNames`, `resolveIndexArgument`) | zrobione |
| K3 | README, `MIGRATION-3.0.md` "Z beta-5 na beta-6", wydanie 3.0.0-beta-6 (main, czyste drzewo, tag `v3.0.0-beta-6`, potem `3.0.0-beta-7-SNAPSHOT`) | zrobione |

Wektory 4.0.0 uruchamiane przez starter: `jwt/` (60, w tym trzy z kluczami `dev-`), `audit-events/` (109), `tool-errors/` (33), `query-rendering/` (26), `resource-checks/`, `result-filter/`, `manifests/`
(w tym `environment`), `text-hygiene/`, `bundles/` (klucz `dev-`: trzy przypadki odtworzone na `PolicyEngine`; paczka na schemacie 3.1.1 z jara kontraktów 3.1.1: `schema_mismatch`).
Wektory, których starter nie wykonuje, bo należą do innych komponentów: `gateway-authentication*`, `catalog-actions`, `activity-*`, `confirmation-binding`, `login-redirects`, `return-paths`, `totp`, `servicedesk-service-account` (adapter serwera: ws-04),
`entity-builder/` i `policy-decisions/` (biblioteka `mcp-cedar-entities`: testy w kontraktach; starter sprawdza wejście `credentialType` w `CredentialTypeTest`).

## Po recenzji beta-6 i kontrakty 4.1.0 (3.0.0-beta-7)

BOM na kontraktach 4.1.0 (CC-165 ... CC-168). Decyzje koordynatora z 2026-10-29:

| Punkt | Co zrobione |
| --- | --- |
| M1, CC-166 | sprawdzenie typu poświadczenia tylko dla żądań z `X-MCP-Credential`: nagłówek `X-MCP-Credential-Type` musi należeć do `accepted-credential-types` (brak, nieznany, wielkość liter, spoza zbioru, serwer bez typu = odmowa) przed Cedar, sondą i resolverami; `internal_error` + `policy_decision` `credential_type_mismatch`. `CredentialTypeCheck`, 9 wektorów strony serwera z `credential-type-header/` (4 wektory gateway'a nie dotyczą startera), test przez transport w `GatewayTransportTest`. Filtr nagłówków nie daje już 400 dla braku i nieznanego typu |
| CC-168 | typ domyślny = pierwszy z `accepted-credential-types` (właściwość `credential-type` z beta-6 usunięta); mieszany zbiór (`service-account` z typami użytkownika) i pusty zbiór zatrzymują start |
| M2 | `Confluence::Page`, `ServiceDesk::Ticket`: brak zakresu kontenera w paczce -> `not_found_or_denied` przed resolverem; zasób poza zakresami wywołującego, którego nie da się ocenić -> też `not_found_or_denied`; `ScopeGrantTest`. Resolver rzucający dla zasobu z częściowym grantem nadal zwraca błąd resolvera |
| M3 | `QueryScope.opensearchSearchTarget(odpowiedź)`: `Optional`, puste = nie pytaj |
| L1 | Lucene bez escapowania jak w referencji; README tłumaczy, dlaczego zakres się nie poszerza |
| L2 | opisane w README (monotoniczność polityki w klasyfikacji, fałszywe odmowy sondy) |
| L3 | odmowa sondy: `policy_forbid`; brak zakresu Page/Ticket: `not_granted` |
| L4 | `resolveIndexNames` pomija nazwy niezgodne z `OpenSearchIndexName` |
| L5 | `OPENSEARCH_RESOLVE_PARAMETERS`, `EMPTY_RESOLVE_RESPONSE`; zachowanie `_resolve/index` na żywym klastrze niepotwierdzone (ws-01) |
| L6 | README i MIGRATION: nie cache'ować odpowiedzi `_resolve/index` |

Wektory 4.1.0: `credential-type-header/` (13; strona serwera 9 uruchamiana), `audit-events/` (113, w tym `server-credential-type-mismatch`), `manifests/` (reguła `credential-types-not-mixed` wykonuje `ManifestRules` z jara); `activity-calls/` (CC-165) nie dotyczy startera.

## Odstępstwa i decyzje (stan na beta-8)

- Typ poświadczenia: zbiór `manifest.accepted-credential-types` (pierwszy = typ domyślny dla Cedar, bez mieszania `service-account` z typami użytkownika); nagłówek `X-MCP-Credential-Type` sprawdzany przed Cedar (CC-166). Właściwość `credential-type` z beta-6 usunięta, jej ustawienie zatrzymuje start z komunikatem migracji.
- Kolejność "grant przed budową encji" (CC-146): sonda z identyfikatora dla `Jira::Project/Issue`, `Confluence::Space`, `GitLab::Group/Project`, `OpenSearch::Index`, `ServiceDesk::Queue`. Dla `Confluence::Page` i `ServiceDesk::Ticket`: zakres w paczce albo zgoda polityki na zasób w kontenerze bez zakresu (9b.2); zasób poza zakresami, którego nie da się ocenić, to `not_found_or_denied`.
  Błąd resolvera dla Page/Ticket wygląda jak brak zasobu tylko przy koncie serwisowym; przy poświadczeniu użytkownika zostaje `upstream_unavailable` (decyzja odbiega od zalecenia recenzji, żeby awaria systemu nie udawała braku zasobu dla wszystkich).
- `StructuredQuery`: operatory `IN`, `NOT_IN`, `NOT_CONTAINS`, `GREATER*`, `LESS*` usunięte (spoza listy normatywnej); znaki Lucene w `CONTAINS` nie są escapowane (jak w referencji kontraktu); znak sterujący w wartości jest odrzucany.
- `QueryScope.requireNoAliases` przestarzałe. `_resolve/index` z `ignore_unavailable=true`; zachowanie na żywym klastrze niepotwierdzone (ws-01).

## Propozycje CC (nie zmieniam kontraktów)

- entity-builder.md 9b.2: sonda z identyfikatora zakłada, że polityka jest monotoniczna w klasyfikacji (tylko `forbid`); warto zapisać to jako regułę dla `mcp-policies`.
- C8: `policy_error` na serwerze obejmuje tylko błąd oceny `Action::"call"` na narzędziu; błąd oceny pojedynczego elementu wyniku usuwa element bez zdarzenia.

3.0.0-beta-10: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-10`, tag `v3.0.0-beta-10` (po wydaniu `3.0.0-beta-11-SNAPSHOT`). Kontrakty 5.1.0 (M5), razem 931 testów (starter 756, mcp-platform-test 175). Instrukcja: `MIGRATION-3.0.md`, "Z beta-9 na beta-10". Zakres: tabela "M5" wyżej.

3.0.0-beta-9: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-9`, tag `v3.0.0-beta-9` (po wydaniu `3.0.0-beta-10-SNAPSHOT`). Instrukcja: `MIGRATION-3.0.md`, "Z beta-8 na beta-9". Zawartość: awaria resolvera dla konta serwisowego w audycie jako `upstream_unavailable` (na zewnątrz `not_found_or_denied`, WARN `resolver_failed_hidden`); błąd sondy bez zakresu dla Page/Ticket przy wywołaniu przez link. Sentinel kontenera ("NOGRANT", "0") leżał poza zakresem linku, więc `vs-narrow-*` odmawiało czytelnikowi z tokenem użytkownika bez grupy zakresu, choć bez linku czytał (link ma tylko zawężać, niezmiennik 5). Teraz sonda używa pierwszego id kontenera z linku, gdy link zawęża ten typ; konto serwisowe bez grantu nadal jest odmawiane. Test: `ScopeGrantTest`.

3.0.0-beta-8: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-8`, tag `v3.0.0-beta-8` (po wydaniu `3.0.0-beta-9-SNAPSHOT`). Poprawki po recenzji beta-7 (1 HIGH, 1 MEDIUM, 5 LOW): H1 definicja grantu Page/Ticket wg 9b.2 (zakres albo polityka) i wektory `servicedesk-service-account/` (6) na `authorizeCall`; M1 `_resolve/index` z `ignore_unavailable=true`; L1 STATUS; L2 błąd resolvera i brak rodzica; L3 usunięta właściwość zatrzymuje start; L4 MIGRATION; L5 `argsLength` w `policy_decision`. Dołożone na prośbę koordynatora: odmowa zawężenia linku dla zasobu z argumentu to `not_found_or_denied` z powodem audytu `link_scope` (CC-146c; `LinkScopeTest` na paczce z `vs-narrow-call-servicedesk-queue`; wymaga identyfikatorów `vs-narrow-*` w paczce) oraz `QueryScope.narrowedTo(ids)` (zakres z argumentu w części zakresu zapytania, przecięcie z linkiem, w ramach kontraktu `query-rendering`). Razem 931 testów (starter 756, mcp-platform-test 175). Dla serwerów tylko podbicie wersji, poza uwagami w `MIGRATION-3.0.md`, "Z beta-7 na beta-8".

3.0.0-beta-7: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-7`, tag `v3.0.0-beta-7` (po wydaniu `3.0.0-beta-8-SNAPSHOT`). Kontrakty 4.1.0, razem 915 testów (starter 751, mcp-platform-test 164). Instrukcja: `MIGRATION-3.0.md`, "Z beta-6 na beta-7". Zakres: tabela "Po recenzji beta-6 i kontrakty 4.1.0" wyżej.

3.0.0-beta-6: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-6`, tag `v3.0.0-beta-6` (po wydaniu `3.0.0-beta-7-SNAPSHOT`). Kontrakty 4.0.0 (M4), razem 893 testy (starter 735, mcp-platform-test 158). Instrukcja: `MIGRATION-3.0.md`, "Z beta-5 na beta-6" (dla serwerów: `credential-type`, klucze `dev-`, `StructuredQuery` z list normatywnych, nazwy indeksów OpenSearch, stały tekst `not_found_or_denied`, licznik `mcp_audit_authentication_suppressed_total`). Zakres M4: tabela kamieni wyżej. Z wcześniejszej pracy na `3.0.0-beta-6-SNAPSHOT` (po recenzji beta-5, 0 HIGH, 1 MEDIUM, 7 LOW): test kontekstu produkcyjnego okablowania zgody (devstack, obie zgody jako właściwości bez zmiennej procesu -> odmowa przed pierwszym żądaniem), `internalJwtVerifier` tworzy strażnika przed rozwiązaniem `GatewayKeys`/`JtiReplayStore`, wariant lazy z asercją przyczyny, MIGRATION: test devstack w kontekście Springa i wersja w szablonie (`mcp-platform.version`).

3.0.0-beta-5: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-5`, tag `v3.0.0-beta-5` (po wydaniu `3.0.0-beta-6-SNAPSHOT`). Zgoda na wydanie: człowiek, 2026-10-29. Instrukcja: `MIGRATION-3.0.md`, "Z beta-4 na beta-5" (dla serwerów tylko podbicie wersji). Zawartość: zgoda `MCP_DEVSTACK_ACKNOWLEDGED` czytana tylko ze środowiska procesu (`PlatformConfigurationGuard` z wstrzykiwalnym źródłem env; przegląd strażnika gateway'a M-1); jawna kolejność (pierwsza linia metody tworzy strażnika) w `policyEngine`, `gatewayKeys`, `jtiReplayStore`, `internalJwtVerifier`, test kontekstu `GuardBeforePullTest` (odmowa przed pierwszym żądaniem do repozytorium, także przy lazy-initialization; mutacja bez wywołania strażnika w `policyEngine` czerwona; kolejność dla kluczy JWT nie ma skutku obserwowalnego, bo JWKS jest pobierany leniwie).

3.0.0-beta-4: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-4`, tag `v3.0.0-beta-4` (po wydaniu `3.0.0-beta-5-SNAPSHOT`). Profil `devstack` (przegląd dev M3, M-2): stos deweloperski w kontenerach akceptuje klucze `test-*` i `allow-test-keys` (JWT i paczki polityk) przy dwóch zgodach (`aiwcorpo.mcp.platform.devstack.acknowledged=true` i `MCP_DEVSTACK_ACKNOWLEDGED=true`), jedyny aktywny profil, reszta wymagań produkcji bez zmian; WARN `devstack_profile` przy każdym starcie i metryka `mcp_server_devstack_active`; poza `dev` i `devstack` klucze testowe odrzucane jak dotąd. Instrukcja: `MIGRATION-3.0.md`, "Z beta-3 na beta-4". Nie zrobione: odrzucanie kluczy stosu po SPKI z listy ws-09.

3.0.0-beta-3: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-3`, tag `v3.0.0-beta-3` (praca na `3.0.0-beta-3-SNAPSHOT`, po wydaniu `3.0.0-beta-4-SNAPSHOT`). Poprawki recenzji beta-2 (0 CRITICAL/HIGH, 1 MEDIUM, 10 LOW) i przeglądu automatycznego. Instrukcja: `MIGRATION-3.0.md`, "Z beta-2 na beta-3".
- B2-M1: `String`, `CharSequence`, `byte[]` jako wynik (także w `Optional`/liście) wymagają `@ResultWithoutResources`. B2-L1: ręczny `ToolCallback` deklaruje `resultWithoutResources()`, nieczytelna metoda `MethodToolCallback` zatrzymuje start. B2-L2: nieskalarny klucz `Map` w wyniku jest nieśledzony. B2-L3: limit elementów na całe wywołanie. B2-L4 i przegląd automatyczny: `nestedRefs` w `Optional`, wartościach `Map`, polach z nadklasami, podtypach `sealed`; głębokość > 6 to błąd startu. B2-L5: `cql(fields, true)`. B2-L7: `tryLock` w pobraniu z `prv`, javadoc. B2-L8: `QueryScope.requireNoAliases` z testem (test na prawdziwym OpenSearch: ws-01). B2-L10: `ContractVectorsOnResourcePolicyTest` uruchamia wszystkie wektory `resource-checks/` (4) i `result-filter/` (8) na `ResourcePolicy`.
- Znane, nieruszane: B2-L6 (słowa `AND`/`OR`/`NOT` w `CONTAINS`; brak `IS EMPTY`/funkcji; sprawdzenie na prawdziwej Jirze i Confluence w ws-01), B2-L9 (`internal_error` rozróżnia istniejący zasób bez klasyfikacji od nieistniejącego przy resolverze na koncie serwisowym: zalecenie `tags: []` w README; propozycja CC poniżej).
- Dodatkowo: paczka generowana dla `mcp-example` ma reguły zasobów z przykładów kontraktów (`ExampleServerResourcesTest`).

3.0.0-beta-2: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-2`, tag `v3.0.0-beta-2` (praca na `3.0.0-beta-2-SNAPSHOT`, po wydaniu `3.0.0-beta-3-SNAPSHOT`). Poprawki recenzji Opus i smoke Haiku beta-1 (0 CRITICAL, 2 HIGH, 3 MEDIUM, 7 LOW) oraz dopiski ws-03. Instrukcja: `MIGRATION-3.0.md`, sekcja "Z beta-1 na beta-2".
- H1: usunięte `QueryScope.restrictJql` (surowy JQL od modelu; obejście `\"` poza cudzysłowem). Zastąpione `StructuredQuery` (`scope.jql(pola)`, `scope.cql(pola)`): pola z białej listy, zamknięty zbiór operatorów, tylko `AND`, wartości zawsze cytowane i escapowane wg JQL/CQL, tekst Lucene escapowany; testy z lekserem czytającym ucieczki jak Jira na 60 wrogich wartościach x 10 operatorów (backslash, cudzysłowy, nawiasy, ORDER BY, funkcje, komentarze, Unicode).
- H2: wynik śledzony przez `ResolvableType` (rekordy generyczne, `Optional`, zbiory, tablice); nieśledzony kształt (`Map`, nie-rekord, `Object`, `JsonNode`) albo adnotacja Jacksona na rekordzie z zasobami zatrzymuje start, chyba że `@ResultWithoutResources`; pole poza komponentami rekordu w JSON i nie-lista w miejscu listy kończą wywołanie `internal_error`.
- M1: `@ResourceRef` na komponencie rekordu-argumentu zatrzymuje start. M2: limit czasu całego żądania (z treścią), pobieranie kanałów niezależne (blokady per kanał, 2 wątki), `prv` zleca pobranie wątkowi silnika. M3: `opensearchIndexPatterns()` rzuca przy pustym wyniku (`nothingToQuery()`, `opensearchIndexExpression()`), `OPENSEARCH_SEARCH_PARAMETERS`, aliasy opisane (README).
- LOW: L1 tryb manifestu z profili, L2 awaria resolvera usuwa element, `fetchAll` (jedno wołanie na listę) i limit `tools.max-filtered-elements`, L3 `evaluation_error`/`resource_id_invalid`/`resource_type_mismatch` to `internal_error`, L4 dwa resolvery jednego typu w narzędziu = błąd startu, L5 `policy_decision` dla odmowy elementu (do 20 na wywołanie), L6 tripwire (`del`, `rm`, `revoke`, `unlink`, `kill`, `terminate`, `reset`, `clear`), L7 interceptor nie zmienia identyfikatorów po autoryzacji.
- Smoke: komunikaty i kody błędów resolvera (`ABC-SLOW` nie pasuje do wzorca klucza: `invalid_argument` było poprawne, opisane), `ResourceContext.required()` z instrukcją i `TestResourceContext` w mcp-platform-test, importy, pełna konfiguracja `dev`, baza ścieżki klucza, wersja manifestu w testach.
- ws-03: `TestGateway.start("mcp-example")` generuje podpisaną paczkę z politykami `call` dla `mcp-example-read|write|destructive`; manifest takiego serwera jest odrzucany przez schemat (`serverId` to enum), w `dev` ostrzeżenie.

3.0.0-beta-1: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-1`, tag `v3.0.0-beta-1` (praca na `3.0.0-alpha-15-SNAPSHOT`; alpha-15 i alpha-16 nie wydane). Kontrakty 3.1.0 (CC-129; BOM, starter i test). Razem 668 testów (starter 552, mcp-platform-test 116). Instrukcja dla template'u i serwerów: `MIGRATION-3.0.md`, sekcja "Z alpha-14 na beta-1". Kamienie pośrednie:
1. Kontrakty 3.0.1 (potem 3.0.2 i 3.1.0), higiena tekstu CC-121/122 (`TextHygiene`: 79 wektorów; `mentionFiltered` na `@ArgumentLimits`), zamknięty i ograniczony `inputSchema` zgodny z regułami mcp-manifest (CC-078/123; test na 27 wektorach `manifests/`), `@ToolTitle` obowiązkowy (CC-115), `ToolAction` w `@ToolRisk`, lista `$defs` (CC-079) pilnowana testem, kody C10 bez `confirmation_declined` (commit f775e9e).
2. Silnik polityk: `PolicyEngine` (pull `latest.json` z ETag, `PolicyStore` z mcp-policy-bundle, rollback po restarcie, kanał `overrides`, `prv` jako podpowiedź do natychmiastowego pobrania), `@ResourceRef` + `ResourceResolver` (S1: każda wartość każdego refa; S2: zasób bez wymaganej klasyfikacji = odmowa), `Action::"call"` oceniana w starterze z tej samej paczki, `ResultFilter` (adnotacja na komponencie rekordu wyniku i `ResourcePolicy.retainAllowed*`), `QueryScope` (JQL/CQL/wzorce OpenSearch, odmowa JQL z niezamkniętym nawiasem), `vsScopes` z `vs`/`vsc` JWT, zdarzenie audytu `policy_decision`.
3. Manifest C6: `ManifestGenerator` (z `tools/list` + adnotacji + `manifest.*`), kontrola schematem i regułami mcp-manifest przy starcie, `GET /mcp-manifest`, `manifest.output-file`; spójność `DESTRUCTIVE` (risk, `destructiveHint`, akcja `delete`/`admin`) pilnowana przy rejestracji i przy generowaniu.
4. Dokumentacja i 3.1.0 (CC-129: `ancestorClassifications` w `ResolvedResource` GitLaba, test projektu trzy poziomy pod grupą `restricted`), README, MIGRATION.

Zgodność z DoD briefu: test `ABC-123` przeniesionego do `HR` (Restricted) -> `not_found_or_denied` (`ResourcePolicyTest`); wyszukiwanie z linkiem zawężonym do `ABC` usuwa zgłoszenie `DEF` (`ResourcePolicyTest`, wynik z adnotacją `@ResourceRef` na komponencie); paczka z błędnym podpisem zostawia poprzednią, z metryką i logiem (`PolicyEngineTest`).

Odstępstwa od briefu:
- Generator manifestu działa przy starcie serwera (nie jako annotation processor ani plugin Maven): manifest powstaje z definicji, które serwer naprawdę publikuje, więc `definitionHash` jest zawsze hashem `tools/list`; plik dla kroku rejestracji daje `manifest.output-file`. Test porównuje definicje z surowym `tools/list`.
- Serwer ocenia także `Action::"call"` na narzędziu (brief pkt 4: `access_denied` dla narzędzi), z paczki serwera, nie z decyzji gateway'a.
- "S2" zinterpretowane jako: zasób (i jego rodzic) bez klasyfikacji wymaganej przez kontrakt nie jest budowany przez builder, więc jest odrzucany (`not_found_or_denied`), a element wyniku usuwany. Dotyczy też GitLaba bez `ancestorClassifications`.
- Klient pobierania paczek jest własny (`PolicyBundleClient`: ETag, 16 MiB, bez przekierowań, nazwy plików tylko obok `latest.json`), bo `mcp-policy-bundle` go nie ma (CC-132 odłożone).
- Starter nie dopuszcza `@ResourceRef` na opcjonalnym argumencie bez wartości (`no_resource` = odmowa); wyszukiwanie bez identyfikatorów używa `QueryScope`.

Nie zrobione / zostaje na 3.0.0: pełne `ResultFilter` dla nietypowych kształtów wyniku (mapy, tablice tablic), recenzja bezpieczeństwa beta-1, smoke "od README" (Haiku), test wywołania na prawdziwej paczce dev ws-09 (samo pobranie i weryfikacja paczki dev 20261028201639-742561e z `:18747` sprawdzone: ładuje się na schemacie 3.1.0; wywołania zasobów sprawdzone na paczkach z wektorów kontraktów 3.1.0).

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

3.0.0-alpha-14: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-14`, tag `v3.0.0-alpha-14` (praca na `3.0.0-alpha-14-SNAPSHOT`). Razem 442 testy (starter 359, mcp-platform-test 83). Poprawka automatycznego przeglądu bezpieczeństwa alpha-13 (MEDIUM): wspólny budżet `missing_token`+`malformed` pozwalał tanimi żądaniami bez tokenu zużyć limit i ukryć w audycie token z nagłówkiem po rozwiązaniu `kid` (`crit`, `typ`), czyli próbę, nie szum.
1. `MissingTokenEventLimiter`: osobny budżet na powód (`missing_token`, `malformed`), limitowane tylko odrzucenia nieparsowalnego tokenu (brak/pusty/obcy `Authorization`, nie trzy segmenty, nagłówek JWT nie jest obiektem).
2. Nowy `InternalJwtRejection.HEADER_NOT_ALLOWED` dla nagłówka z `crit` albo `typ` innym niż `JWT` po rozwiązaniu `kid`: bez limitu, na wire jako `alg_not_allowed` (najbliższy istniejący kod C8; żaden wektor kontraktów nie wymaga tu `malformed`). Zmiana zachowania: wcześniej `malformed`.
3. Testy: `InternalJwtAuditTest.eachReasonWithoutAParsableTokenHasItsOwnBudgetForTheEventAndTheLogLine`, `aFloodWithoutATokenDoesNotSuppressTheEventForATokenWithACritHeader` (zalew `missing_token` i `malformed` nie tłumi zdarzenia dla tokenu z `crit`).
4. Propozycja CC 8 (C8): osobny powód `header_not_allowed` (albo `unsupported_header`) dla nagłówka JWT z `crit` lub niedozwolonym `typ`, zamiast przeciążonego `alg_not_allowed`; do decyzji ws-00.

3.0.0-alpha-13: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-13`, tag `v3.0.0-alpha-13` (praca na `3.0.0-alpha-13-SNAPSHOT`, wersji alpha-12 i wcześniejszych nie instalowano ponownie). Razem 440 testów. Instrukcja: `MIGRATION-3.0.md`, sekcja "Jak przejść z alpha-11 (albo alpha-12) na 3.0.0-alpha-13". Decyzja człowieka A10-L2: opcja 1 (devcontainer nie jest wspierany dla `dev`, bez zmian w kodzie). Kamienie (przegląd alpha-12: 0 CRITICAL/HIGH, 1 MEDIUM, 3 LOW):
1. A12-M1. `PlatformConfigurationGuard`, `CredentialTransportGuard` i `DevListenAddressCheck` są `@Lazy(false)`; przy `spring.main.lazy-initialization=true`
   guard powstawał tylko jako zależność `CredentialHeaderFilter` (`@ConditionalOnMissingBean`), więc serwer z własnym filtrem startował bez kontroli.
   `assertServerIsGuarded` używa `getBean` (wykonanie kontroli), nie `containsBean`. Testy: `StartupRefusalTest.lazyInitializationDoesNotSwitchTheGuardsOff`
   (aplikacja `OwnFilterServer`: `dev` na `0.0.0.0` i `::`, `prod` z kluczami testowymi/bez mTLS/jti w pamięci/JWKS po http, ASYNC), `ServerGuardedAssertionTest`
   (kontekst z leniwym guardem, który się nie tworzy). Mutacja bez `@Lazy(false)` jest czerwona.
2. A12-L1. `MissingTokenEventLimiter` ma jeden budżet dla `missing_token` i `malformed` i obejmuje zdarzenie audytu oraz linię WARN
   `internal_jwt_rejected`; licznik z tagiem `reason`; okno synchronizowane
   (poprzednio nieatomowy reset). Testy: `InternalJwtAuditTest.missingAndMalformedShareOneBudgetForTheEventAndTheLogLine`.
   Odstępstwo od gateway'a: gateway ogranicza tylko `missing_token` (propozycja CC 7 niżej).
3. A12-L2. `DevListenAddressCheck` (listener `WebServerInitializedEvent`, tylko `dev` jako jedyny profil) odmawia startu, gdy któryś konektor Tomcata
   (główny lub managementu) nie jest na pętli zwrotnej (`ListenAddresses.nonLoopback`, publiczne); `assertServerIsGuarded` sprawdza to samo.
   Testy: `ListenAddressesTest`, `StartupRefusalTest.aWildcardAddressSetInCodeStopsTheStartInDev` (`WebServerFactoryCustomizer` z `0.0.0.0`).
   Granica: tylko Tomcat (jedyny serwer w starterze); serwer wystartował na chwilę, zanim odmowa go zatrzymuje.
4. A12-L3. Asercja odrzuca kontekst spoza `dev` i bez profilu (mutacja M6); kod błędu w teście powodów z tabeli kontraktu (`UPSTREAM_UNAVAILABLE`),
   a nie z `infrastructure()`; `jwks_unavailable` sprawdzony przez filtr (zdarzenie i 503).
5. Dokumentacja wg smoke Haiku: jedna lista "Jak przejść z alpha-11" w MIGRATION (wersje, pełny plik `DevProfileLoopbackTest` z pakietem i importami,
   gdzie szukać wykluczeń: yml, env, systemd, compose, adnotacje, grep), README odsyła do niej. Template i `new-server.sh` (wersja BOM z pom template'u,
   README template'u z ręcznie wpisanymi wersjami) przestawia ws-03.

3.0.0-alpha-12: wydane (nie zalecane, następcą jest alpha-13) - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-12`, tag `v3.0.0-alpha-12` (praca na `3.0.0-alpha-12-SNAPSHOT`, wersji alpha-11 i wcześniejszych nie instalowano). Razem 431 testów (starter 353, mcp-platform-test 78). Kontrakty 2.2.5 (BOM i oba moduły). Instrukcja dla serwerów: `MIGRATION-3.0.md`, sekcja "Zmiany w 3.0.0-alpha-12". Kamienie:
1. Kontrakty 2.2.5, wersja `3.0.0-alpha-12-SNAPSHOT`. Bez zmian w kodzie; 352 testy zielone na 2.2.5 (schematy i wektory JWT bez zmian oczekiwań).
2. A11-M1 (MEDIUM). Test serwera nie sprawdzał niczego w serwerze, a serwer z wykluczonym guardem przechodził testy w `dev` na `0.0.0.0`.
   Dwie warstwy: (a) `DevProfileGuardAssertions.assertServerIsGuarded(ApplicationContext)` w `mcp-platform-test`: w kontekście `@SpringBootTest`
   serwera w `dev` są wszystkie beany z `PlatformPresenceCheck.REQUIRED_BEANS` (publiczna lista) i `server.address` jest pętlą zwrotną
   (`PlatformConfigurationGuard.loopback` publiczne); szablon `DevProfileLoopbackTest` dziedziczy po `ServerTestSupport` i woła obie asercje
   (`ServerGuardedAssertionTest`: pełny serwer przechodzi, `ExcludingServer` z `server.address=0.0.0.0` w kontekście testowym nie, adres sieciowy
   przy kompletnych beanach nie). (b) Zamknięte rezyduum A10-L3: `PlatformPresenceCheck` i `refuseExcludedPlatform` działają też w `dev`
   (poza kontekstem testowym); `StartupRefusalTest.excludingThePlatformInDevIsRefused`, `PlatformConfigurationGuardTest`. Zmiana zachowania:
   serwer z wykluczeniem platformy tylko w `dev` przestaje startować. Zdanie MIGRATION o guardzie w każdym `@SpringBootTest` poprawione.
   Nadal otwarte (świadomie): wyjątek kontekstu testowego dla slice'ów (kontekst testowy nie jest sprawdzany; kompensuje go asercja serwera).
3. A11-L1. `UntrustedText.cut`: gdy cięcie zostaje na limicie, zdejmuje z końca `.`, `-`, `_` i znaki łączące, po czym ocenia ogon jak zwykle
   (`"x(@all" + 70 * "."` przy 75 -> `x(@`; także `-`, `_`, U+0301, tekst zaczynający się od `@all`, słowo po spacji); długi ciąg samej
   interpunkcji i `a*30 + .*70` zachowane. `TextHygieneTest` (2 nowe).
4. A11-L2. `DevProfileEnvironment.testFrameworkLocation`: wzorzec zakotwiczony na końcu (`...spring-(boot-)?test-N.jar(!/)?$`), katalog `spring-test-1.jar/`
   i ścieżka z takim jarem wyżej nie liczą się; jar o takiej nazwie nadal poza modelem zagrożeń (jak w alpha-11). Test tabelaryczny w `TestContextExemptionTest`.
5. A11-L3. Trzy luki testów: znak łączący w zachowywanej części (`/@all\u0301ison` przy 66, `TextHygieneTest`), klucz mapy z wejścia nie trafia do
   komunikatu (`ToolInputValidatorTest.aKeyOfAMapFromTheInputIsNotInTheMessage`, schemat z `propertyNames`), pętla adresów pętli zwrotnej w pomocniku
   (`assertLoopbackRule(Function)` pakietowe, `DevProfileGuardAssertionsTest` z guardem odrzucającym `127.0.0.1` i z guardem przyjmującym wszystko).
   Mutacje M6, M8 i M10 przeglądu mają teraz testy, które je zabijają (sprawdzone przez konstrukcję przypadków, nie ręczną mutacją).
6. CC-120 po stronie odbiorcy JWT. Starter emitował już wszystkie powody (`missing_token`, `client_certificate_rejected`, `jwks_unavailable` i
   `replay_store_unavailable` z `errorCode: upstream_unavailable`, rzeczywiste 503); zrobione: (a) `AuthenticationAuditVectorsTest`: wszystkie 42 wektory
   `audit-events/` 2.2.5 zgodne ze schematem wg `expected.valid`, każdy powód `InternalJwtRejection` (w tym infrastrukturalne) daje zdarzenie ważne
   wg schematu, powód infrastrukturalny z `unauthenticated` jest odrzucany; wektor `server-authentication-denied-client-certificate` odtworzony
   przez filtr; (b) `MissingTokenEventLimiter` (ten sam mechanizm i domyślne 20/s co w gateway'u): `missing_token` zapisywane do budżetu na sekundę
   na proces, nadmiar tylko w liczniku `mcp_server_audit_authentication_suppressed_total{reason}` na rejestrze aplikacji (`SimpleMeterRegistry`, gdy
   go brak), żądanie nadal dostaje 401; własność `internal-jwt.missing-token-events-per-second` (minimum 1, mniej zatrzymuje start w guardzie);
   inne powody bez limitu (potrzebują certyfikatu albo tokenu poprawnego kształtu, a każdy jest osobnym sygnałem). Testy: `InternalJwtAuditTest`
   (zalew, brak ukrywania innych powodów), `MissingTokenAuditCapTest` (przez HTTP, własność, licznik, suma zapisanych i policzonych = liczba żądań).
   Odstępstwo od briefu: nie ma. `client_certificate_rejected` nie jest limitowane, bo poza `dev` TLS wymaga `client-auth=need`, więc anonimowe
   żądanie nie dociera do kontroli aplikacji.

### A10-L2, devcontainer i dev w kontenerze programisty: zamknięte, opcja 1

Decyzja człowieka (2026-10-28): opcja 1, bez zmian. `dev` w kontenerze (devcontainer, Codespaces, Toolbox, Distrobox) nadal odmawia, uruchomienie serwera tam idzie z testu albo w profilu z TLS, mTLS i Redisem. Opcja 2 (znaczniki `REMOTE_CONTAINERS`, `CODESPACES`, `/run/.toolboxenv`) wraca tylko przy realnej potrzebie. Rozważane opcje i uzasadnienie:

Problem: sygnał kontenera (`/.dockerenv`, `/run/.containerenv`, `container`, cgroup) odmawia `dev` także w devcontainerze VS Code, Codespaces, Toolboxie
i Distroboxie, czyli tam, gdzie programista pracuje. Komunikat odmowy i README podają dziś trzy wyjścia (poza kontenerem, z testu, profil inny niż `dev`).
Testy serwerów działają w kontenerze (kontekst testowy nie jest sprawdzany). Nikt nie zgłosił, że pracuje w devcontainerze; programista na macOS
nie ma problemu.

- Opcja 1: zostaje jak jest (devcontainer nie jest wspierany dla uruchomienia aplikacji w `dev`, testy tak). Koszt: 0. Zaleta: sygnał kontenera
  zatrzymuje skopiowany `SPRING_PROFILES_ACTIVE=dev` w obrazie wdrożeniowym bez wyjątków. Wada: programista w devcontainerze/Toolboxie uruchamia
  serwer tylko z testu albo w profilu z TLS, mTLS i Redisem.
- Opcja 2: dla samego sygnału kontenera dopuścić `dev`, gdy jest jawny znacznik środowiska programisty (`REMOTE_CONTAINERS=true`, `CODESPACES=true`,
  `/run/.toolboxenv`), a `KUBERNETES_*` i `INVOCATION_ID` nadal odmawiają. Koszt: ok. pół dnia (kod w `DevProfileEnvironment`, testy, README),
  lista znaczników do utrzymania. Ryzyko: znacznik to zmienna, którą ktoś skopiuje do Dockerfile razem z `dev` (ten sam model "siatka, nie granica"
  co sygnały dziś), więc skutek jest taki, że ten jeden błąd konfiguracji przestaje być zatrzymywany; adres pętli zwrotnej i zakaz wykluczeń nadal obowiązują.
- Rekomendacja: opcja 1 teraz. Nie ma zgłoszonej potrzeby, a opcja 2 osłabia jedyną kontrolę przed `dev` w obrazie wdrożeniowym. Wrócić do opcji 2,
  gdy ktoś realnie pracuje w devcontainerze (wtedy z konkretnym znacznikiem tego środowiska, nie z całą listą).

3.0.0-alpha-11: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-11`, tag `v3.0.0-alpha-11` (praca na `3.0.0-alpha-11-SNAPSHOT`, nic innego w `~/.m2` nie instalowane). Razem 352 testy (starter 281, mcp-platform-test 71). Instrukcja dla serwerów: `MIGRATION-3.0.md`, sekcja "Zmiany w 3.0.0-alpha-11". Kamienie:
1. A10-M1. `DevProfileGuardAssertions` w `mcp-platform-test` (`assertDevProfileLoopbackRule()`, `assertDevProfileRefusesAddress(adres)`):
   woła `PlatformConfigurationGuard` z własnym środowiskiem (bez zmiennych procesu i właściwości systemowych hosta, z ustawionymi na
   stałe `INVOCATION_ID` i `KUBERNETES_*`), więc reguła loopbacku jest sprawdzana tak samo na każdym hoście. Wybrane zamiast
   zmiany komunikatu odmowy w EPP (jedna przyczyna z dwoma powodami): serwery i tak muszą zmienić jeden plik, a test nie zależy od
   treści komunikatów platformy. `StartupRefusalTest.devOnANetworkAddress` też nie zakłada hosta (akceptuje oba komunikaty), nowy
   `devOnANetworkAddressOnAPodUnderSystemdIsStillRefused` ma `INVOCATION_ID` i `KUBERNETES_*` ustawione. Instrukcja zamiany testu w serwerach: MIGRATION.
2. A10-L1. Kontekst testowy: klasa z pakietów test frameworka musi pochodzić z jaru `spring-test` / `spring-boot-test` (`CodeSource`,
   `StackWalker` z `RETAIN_CLASS_REFERENCE`). Własna klasa w pakiecie Springa w jarze serwera nie wyłącza już trzech kontroli
   (`TestContextExemptionTest`, klasa `ForgedTestFrameworkClass` w `org.springframework.boot.test.context` w katalogu testów startera).
   To nadal siatka, nie granica: kod serwera, który świadomie fałszuje jar albo stos (np. własny jar o nazwie `spring-test-*.jar`),
   nie jest w modelu zagrożeń; kto steruje kodem serwera, steruje też jego konfiguracją. Opisane w README.
3. A10-L2. Komunikat odmowy dla sygnału kontenera wymienia devcontainer, Codespaces, Toolbox i Distrobox i podaje trzy wyjścia
   (poza kontenerem; z testu; profil inny niż `dev` z TLS, mTLS i Redisem). README (konfiguracja i "Uruchomienie lokalne") to samo.
   Decyzji o dopuszczeniu `dev` w devcontainerze NIE podjęto (opcja (2) przeglądu: znaczniki `REMOTE_CONTAINERS`, `CODESPACES`,
   `/run/.toolboxenv`); czeka na człowieka.
4. A10-L3. `PlatformPresenceCheck` wymaga też `platformMcpServerCustomizer` (`ForeignPrimaryCustomizerCheck.STARTER_BEAN` jest publiczne):
   `exclude = PlatformMcpServerAutoConfiguration.class` kończy start poza `dev` (`StartupRefusalTest.excludingThePlatformServerCustomizerInCodeOutsideDev`).
   Wyjątki dla kontekstu testowego mają testy (`TestContextExemptionTest`); mutacje M1 i M2 z przeglądu (bez wyjątku testowego w
   `PlatformPresenceCheck` i w `refuseExcludedPlatform`) są teraz czerwone (sprawdzone ręcznie). NIE zrobione z kierunku przeglądu:
   odmowa wykluczenia `PlatformAuthAutoConfiguration` i `PlatformSecurityAutoConfiguration` w `dev` oraz zawężenie wyjątku do
   slice'ów; w `dev` wykluczenie auth nadal daje `initialize` bez JWT na loopbacku (rezyduum, nie w zakresie alpha-11).
5. A10-L4. `UntrustedText.cut`: `-`, `.` i `\p{M}` są znakami słowa przy cofaniu (`/@all-hands` -> `/@`); gdy cofnięcie usunęłoby
   więcej niż 64 jednostki albo przed końcem nie ma ogranicznika, cięcie zostaje na limicie (CJK po jednym nawiasie, blob po `#`
   zachowane); ujemny limit w `cut` to `IllegalArgumentException`. Pojedyncze długie słowo: wybrany prefiks z `truncated=true`
   zamiast pustego tekstu (bez ogranicznika nie ma początku tokenu; pusty opis ukrywał, że tekst istniał); opisane w MIGRATION.
   Zmiana zachowania: `plain("@allison", 4)` to `@` (było pusty tekst). Testy: `TextHygieneTest` (4 nowe).
6. A10-L5. Literówka w README ("tnie"), `containerd` na liście cgroup.
7. Uwagi z migracji serwerów. Komunikat walidacji zagnieżdżonego pola: ścieżka z nazw pól schematu i indeksów (`jql_filter.status`),
   limit z węzła schematu, klucz mapy z wejścia jako `*`, bez wartości (`ToolInputValidator.target`, test
   `aNestedViolationIsNamedByPathAndLimitWithoutTheValue`). Wiersz MIGRATION o `NON_BLANK_LINE` poprawiony (wzorzec jest zgodny, nic
   do zmiany). `@Nullable` z jspecify na komponentach rekordu wejścia działa (test `BoundedSchemaTest`), na wyniku działał już
   (`ToolOutputSchemas.isNullable`); MIGRATION wymienia obie adnotacje. `Map` w wyniku: opisana alternatywa (lista par) w MIGRATION.

3.0.0-alpha-10: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-10`, tag `v3.0.0-alpha-10` (praca na `3.0.0-alpha-10-SNAPSHOT`, nic innego w `~/.m2` nie instalowane). Ostatnia przed migracją serwerów. Instrukcja dla serwerów: `MIGRATION-3.0.md`, sekcja "Zmiany w 3.0.0-alpha-10". Kamienie:
1. A9-M2. `DevProfileEnvironment`: `CloudPlatform.NONE` nie jest środowiskiem wdrożenia; kontekst budowany przez Spring TestContext
   (ramki `org.springframework.test.context.` i `org.springframework.boot.test.context.` na stosie wątku, który buduje kontekst)
   nie jest wdrożeniem, więc testy serwerów z `TestGateway` działają w CI na Kubernetesie i pod runnerem systemd. Wybrane
   wykrywanie po stosie, a nie po właściwości (`Inlined Test Properties`, `SpringBootTestContextBootstrapper=true`): właściwość da
   się skopiować do `.env` i do jednostki. Stos wymaga klas test frameworka w wątku, który uruchamia kontekst; produkcyjny
   `java -jar` ich nie ma, a testy ręcznie uruchamiające `SpringApplication` w podzie są nadal odrzucane (to zamierzone).
   Testy: `DeploymentContextTest` (`@SpringBootTest` z `INVOCATION_ID` i `KUBERNETES_*` w `dev` startuje),
   `StartupRefusalTest` (to samo przez `SpringApplicationBuilder` kończy start; `cloud-platform=none` nie), `PlatformConfigurationGuardTest`.
2. A9-L1. Sygnały: zmienne procesu `INVOCATION_ID`, `KUBERNETES_SERVICE_HOST`+`PORT` czytane z `System.getenv` (nie do zasłonięcia
   `-D`), kontener (`/.dockerenv`, `/run/.containerenv`, zmienna `container`, cgroup z `docker|kubepods|libpod|crio|containerd`).
   Puste `SPRING_MAIN_CLOUD_PLATFORM` nadal wyłącza wykrywanie Boota, ale nie zmienne `KUBERNETES_*`. Zdanie "obejścia nie ma" poprawione
   (kamień 1 alpha-9): to nie granica bezpieczeństwa. Ograniczenie: devcontainer programisty jest kontenerem, więc `dev` w nim
   nie wystartuje poza testami; uruchom aplikację poza kontenerem albo użyj testów.
3. A9-L2. Port managementu jako liczby (`separateManagementServer`): `0` zawsze osobny serwer, `-1` wyłączony, nie-liczba osobny
   (odmowa). Dotyczy też reguły poza `dev`. Test `theManagementPortIsComparedAsANumber`.
4. A9-L3. `spring.autoconfigure.exclude` z klasą `pl.aiwcorpo.mcp.platform.*` kończy start poza `dev`
   (`PlatformDefaultsEnvironmentPostProcessor`, ładowany z `spring.factories`), a `PlatformPresenceCheck`
   (`ApplicationContextInitializer` z `spring.factories`, `BeanFactoryPostProcessor`) sprawdza, że `platformConfigurationGuard`,
   `internalJwtVerifier`, `internalJwtFilter` i `toolCallbackAuditPostProcessor` są zdefiniowane, co łapie też `exclude` na
   `@SpringBootApplication`. Oba pomijane w `dev` i w kontekście testowym (slice'y nie ładują platformy z założenia).
   Testy: `StartupRefusalTest` (property, kod), `PlatformConfigurationGuardTest`.
5. A9-L4. `UntrustedText.cut` (publiczne): w ciągu bez spacji dłuższym niż 64 jednostki odpadają końcowe litery, cyfry i `_`,
   o ile zostaje cokolwiek (`(((...(@allison` -> `(((...(@`); ciąg z samych liter i cyfr jest cięty na limicie. Wybrane zamiast
   "usuń cały ostatni ciąg", bo całe zdanie w piśmie bez spacji (CJK) albo blob alfanumeryczny zniknęłyby w całości. Ogranicznik
   zdjęty przez cięcie: serwer tnie `cut` przed filtrem i buduje wartość przez `plainCut|markdownCut(tekst, truncated)`
   (README, MIGRATION). Testy: `TextHygieneTest` (2 nowe).
6. A9-L5. WARN `untrusted_text_not_normalized` pomija `null` i zmianę samych końców linii (CRLF, CR, U+2028, U+2029, U+0085).
   Ograniczenie do jednej linii na narzędzie nie zrobione: wymaga stanu statycznego z czasem, który psułby testy równoległe; po
   poprawce fałszywych trafień zostaje jedna linia na obiekt tylko przy prawdziwej zmianie po filtrze. Test `onlyARealChangeAfterTheFilterIsReported`.
7. A9-L6. `McpErrorMapperTest.aRejectedResultIsInternalErrorEvenWhenTheServerMapsEveryRuntimeException` (mapping `RuntimeException`,
   wyjątek bez opakowania, jak go rzuca `AuditingToolCallback`); mutacja M1 (`if (false)` w `classify`) teraz czerwona. Stary test
   zostaje, ale sam nie zabijał mutacji. Test end-to-end w `OutputSchemaTest` nie dodany: ścieżka opakowania jest pokryta
   `AuditingToolCallbackTest`, a mapowanie ma teraz test jednostkowy.
8. A9-M1, A9-L7. MIGRATION-3.0.md: zdanie "alpha-8 nietknięte" zastąpione stanem faktycznym (nadpisane, odtworzenie po stronie
   człowieka), nagłówek "Stan" na alpha-10. Alpha-8 w `~/.m2` NIE jest odtworzone przez to wydanie. Reguła w buildzie odmawiająca
   `install` wersji bez `-SNAPSHOT` bez tagu `v<wersja>` (enforcer): nie zrobiona, wymaga decyzji o dodaniu pluginu do parenta.
   Razem 335 testów (starter 268, mcp-platform-test 67).

Znalezisko -> status (przegląd Opus alpha-9):

| Znalezisko | Status | Gdzie |
| --- | --- | --- |
| A9-M1 alpha-8 w `~/.m2` zawiera kod alpha-9, MIGRATION mówi inaczej | MIGRATION poprawiony; odtworzenie alpha-8 po stronie człowieka (receptura w STATUS alpha-9) | `MIGRATION-3.0.md` |
| A9-M2 `NONE` jako platforma; testy serwerów blokowane w CI | naprawione: `NONE` pominięte, kontekst TestContext nie jest wdrożeniem | `DeploymentContextTest`, `StartupRefusalTest`, `PlatformConfigurationGuardTest` |
| A9-L1 neutralizatory, brak Dockera, zdanie w STATUS | naprawione: kontenery i bezpośrednie zmienne procesu; zdanie poprawione; dokumentacja | `PlatformConfigurationGuardTest.aContainerIsADeployment...`, `theProcessVariablesCount...` |
| A9-L2 porty jako napisy | naprawione | `theManagementPortIsComparedAsANumber` |
| A9-L3 `spring.autoconfigure.exclude` | naprawione (property i kod) | `StartupRefusalTest`, `PlatformConfigurationGuardTest.excluding...` |
| A9-L4 cięcie długiego ciągu i ograniczniki | naprawione: cofanie liter na końcu, `cut` + `plainCut|markdownCut` | `TextHygieneTest` |
| A9-L5 szum WARN | naprawione: bez `null` i CRLF; limit linii na narzędzie nie zrobiony (uzasadnienie w kamieniu 6) | `TextHygieneTest.onlyARealChange...` |
| A9-L6 luka testu M1 | naprawione, mutacja zabita | `McpErrorMapperTest` |
| A9-L7 nagłówek MIGRATION | naprawione | `MIGRATION-3.0.md` |

3.0.0-alpha-9: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-9`, tag `v3.0.0-alpha-9` (praca na `3.0.0-alpha-9-SNAPSHOT`). Instrukcja dla serwerów: `MIGRATION-3.0.md`,
sekcja "Zmiany w 3.0.0-alpha-9". Kamienie:
1. Profil `dev` i guard (A8-L1, A8-L2, A8-L3).
   L1: guard w `dev` przy osobnym `management.server.port` wymaga loopbacku w `management.server.address` (pusty adres =
   wszystkie interfejsy, więc też odmowa). Domyślnej wartości `127.0.0.1` nie da się ustawić w starterze: Spring Boot odrzuca
   `management.server.address` bez osobnego portu ("Management-specific server address cannot be configured..."), więc
   pozostaje wymóg w guardzie. L2: wybrane odmowa startu, nie zgoda jawna. Zgoda jawna (dodatkowa właściwość) dopisałaby ustawienie, które
   każdy programista skopiuje razem z `dev` do pliku, a nie odróżnia ona maszyny developera od wdrożenia; sygnał środowiska
   odróżnia. `dev` jako jedyny profil kończy start (w `PlatformDefaultsEnvironmentPostProcessor`, przed otwarciem gniazda, i
   w guardzie), gdy Boot wykrywa platformę chmurową (`CloudPlatform.getActive`: Kubernetes, Cloud Foundry itd.) albo ustawione
   jest `INVOCATION_ID` (jednostka systemd, czyli `install.sh` template'u). To siatka na skopiowaną konfigurację, nie granica
   bezpieczeństwa: kto steruje środowiskiem procesu, ukryje sygnały (stan alpha-9 miał też neutralizatory, patrz alpha-10);
   na maszynie dewelopera z terminala `INVOCATION_ID` nie występuje. Obecność `server.ssl.bundle` nie jest sygnałem: README każe go mieć w `dev`.
   Dodatkowo linia `dev_profile_overrides` jest na WARN i wymienia nadpisane klucze, które konfiguracja serwera ustawiała inaczej.
   L3: `PlatformConfigurationGuard`, `InternalJwtVerifier` i `InternalJwtFilter` bez `@ConditionalOnMissingBean`;
   `ForeignToolPolicyCheck` zatrzymuje start z własnym beanem któregoś z nich w każdym profilu (także `dev`). Punkty
   rozszerzenia (`@ConditionalOnMissingBean`): `GatewayKeys`, `JtiReplayStore`, `AuditLogger`, `ToolRiskRegistry`,
   `McpErrorMapper`, filtry nagłówków. Testy: `PlatformConfigurationGuardTest` (4 nowe), `ForeignToolPolicyCheckTest` (1).

2. A8-L4 (rezyduum A7-L6): wrapper i jego post-processor nie dają się zbudować spoza pakietu. `AuditingToolCallback` i
   `ToolCallbackAuditPostProcessor` są `final` z konstruktorami pakietowymi, `ToolInputPolicy` jest enumem (`STRICT`, `DEV`),
   więc ręczne `new AuditingToolCallback(..., ToolInputPolicy(false))` nie kompiluje się, a instancja
   `AuditingToolCallback` widziana w post-procesorze zawsze pochodzi z niego (z polityką profilu i interceptorami serwera).
   `ForeignToolPolicyCheck` sprawdza też źródło definicji czterech beanów (nazwa platformy, ale definicja nie z
   `PlatformObservabilityAutoConfiguration` = odmowa). Poprawione zdanie: nadpisanie definicji przez
   `BeanDefinitionRegistryPostProcessor` jest wykrywane; niewykrywalne zostają `BeanFactoryPostProcessor` uruchomiony po kontroli
   i refleksja, czyli świadome hacki, nie "wygodna konfiguracja". Testy: `ForeignToolPolicyCheckTest` (2 nowe).

3. A8-L5, A8-L6.
   L5 (i): kolejność w serwerze opisana w MIGRATION: konwersja do tekstu (dekodowanie encji), `TextHygiene.normalize`, filtr,
   fabryka. Fabryka loguje WARN `event=untrusted_text_not_normalized tool=...` (bez cytowania tekstu), gdy `normalize(raw) != raw`,
   czyli gdy tekst zmienił się już po filtrze serwera (zwykle encje dekodowane po normalizacji). (ii): cięcie w fabryce cofa się
   do ostatniego białego znaku, gdy zachowywana część ostatniego słowa ma najwyżej 64 jednostki (`plain("hello @allison", 10)` ->
   `hello`, `truncated=true`); dłuższy ciąg bez spacji (pismo bez spacji, blob) jest cięty tam, gdzie wypada limit; nigdy w parze
   surogatów. Osobnego kroku "cięcie przed filtrem" nie ma: fabryka tnie na końcu, a cięcie po granicy słowa nie tworzy tokenu,
   którego filtr nie widział. Wybrane zamiast oznaczenia w tekście (znacznik dopisany do `text` byłby treścią wymyśloną przez
   serwer); flaga `truncated` jest oznaczeniem. (iii) NFKC: propozycja CC 6 niżej, w starterze bez zmian.
   L6 (i): `ToolResultRejectedException` (zwykły `RuntimeException`, nie `IllegalStateException`); `McpErrorMapper` klasyfikuje go
   przed mappingami serwera jako `internal_error`, więc mapping `IllegalStateException` -> `not_found_or_denied` nie zamieni
   niezgodności z `outputSchema` w odmowę. (ii): `aiwcorpo.mcp.platform.tools.max-result-size` (domyślnie 1MB, bajty UTF-8),
   sprawdzane w opakowaniu przed parsowaniem; przekroczenie to `internal_error`, audyt `error`, w logu ERROR rozmiar limitu, bez
   treści. Jedno parsowanie zamiast trzech: nie robione. Trzecie parsowanie to walidacja SDK (poza startera), a drugie
   (`structuredContent` w `ToolHintsPostProcessor`) działa na `CallToolResult` po warstwie Spring AI; przekazanie drzewa przez
   `ThreadLocal` między tymi warstwami dodałoby ukryty stan, a przy limicie 1MB koszt jest rzędu milisekund (pomiar
   przeglądu: 12 MB w 148 ms). Walidator zbiera nadal wszystkie błędy przed odcięciem do 5; przy limicie rozmiaru to
   ograniczone. Testy: `TextHygieneTest` (4 nowe), `AuditingToolCallbackTest` (2), `McpErrorMapperTest` (1), `ForeignToolPolicyCheckTest` (1).

4. Smoke Haiku alpha-8 (README). Przykład "Pierwszy serwer" jest teraz kompletny: `pom.xml` (rodzic Boot 4.1.0, import BOM,
   starter, `<finalName>mcp-jira</finalName>`, `spring-boot-maven-plugin`), `JiraServer` z metodą `main` i importem
   `SpringApplication` (kopia w `mcp-platform-test/.../readme/JiraServer.java` też ma `main`), wskazanie, że konfiguracja to
   sekcja "Konfiguracja". Komenda z jarem: `$JAVA_HOME/bin/java -jar target/mcp-jira.jar ...` z uwagą, że `java` z PATH bywa
   starsza (17), a `mvn` zamiast `./mvnw` (nowy projekt nie ma wrappera). Sprawdzone według README w
   katalogu roboczym (pom, klasa i yml wycięte z README skryptem): `mvn clean package` daje `target/mcp-jira.jar`,
   start na porcie 18600 w `dev`, health 200, `POST /mcp` bez JWT 401, z `INVOCATION_ID` start odmawia; proces zatrzymany.
   Linia `dev_profile_overrides` (WARN, lista nadpisanych kluczy) pisze `PlatformConfigurationGuard`, bo
   `EnvironmentPostProcessor` działa przed konfiguracją logowania i jego własny logger nic nie wypisywał (w alpha-8 INFO z tego
   miejsca też nie było widać w logu).
   Razem 320 testów (starter 258, mcp-platform-test 62).

Znalezisko -> status (przegląd Opus alpha-8):

| Znalezisko | Status | Gdzie |
| --- | --- | --- |
| A8-L1 guard loopback bez serwera managementu | naprawione: w `dev` osobny `management.server.port` wymaga loopbackowego `management.server.address` (domyślnej wartości Boot nie pozwala ustawić bez osobnego portu) | `PlatformConfigurationGuardTest.aSeparateManagementServerInDevMustListenOnLoopbackToo` |
| A8-L2 samo `SPRING_PROFILES_ACTIVE=dev` zdejmuje TLS/mTLS/Redis | naprawione: odmowa startu, gdy `dev` jest w środowisku wdrożenia (platforma chmurowa Boota, `INVOCATION_ID`); WARN z nadpisanymi kluczami; uzasadnienie wyboru w kamieniu 1 | `devInADeploymentIsRefusedBeforeAnySocketIsOpened`, `theDeploymentCheckDoesNotTouch...`, `theOverridesThatChanged...` |
| A8-L3 guard z `@ConditionalOnMissingBean` | naprawione: guard, `InternalJwtVerifier`, `InternalJwtFilter` nie do podmiany w żadnym profilu | `ForeignToolPolicyCheckTest.theConfigurationGuardAndTheJwtCheckCannotBeReplacedInAnyProfile` |
| A8-L4 obejścia polityki (ręczny wrapper, podmiana definicji) | naprawione: konstruktory pakietowe, klasy `final`, `ToolInputPolicy` enum, kontrola źródła definicji; zdanie o "nadpisaniu definicji" poprawione | `ForeignToolPolicyCheckTest` (swap, konstruktory) |
| A8-L5 encje po normalizacji, cięcie po filtrze | naprawione: kolejność w MIGRATION + WARN `untrusted_text_not_normalized`; cięcie na granicy słowa; NFKC jako propozycja CC 6 | `TextHygieneTest` |
| A8-L6 niezgodność wyniku mapowana przez serwer, brak limitu rozmiaru | naprawione: `ToolResultRejectedException` przed mappingami, `tools.max-result-size` (1MB); jedno parsowanie zamiast trzech: nie robione, uzasadnienie w kamieniu 3 | `McpErrorMapperTest`, `AuditingToolCallbackTest` |

Incydent budowy: `./mvnw clean install` odpalony przed ustawieniem wersji `3.0.0-alpha-9-SNAPSHOT` (poms miały jeszcze
`3.0.0-alpha-8`) nadpisał w `~/.m2` bom, rodzica i `mcp-platform-starter` 3.0.0-alpha-8 kodem roboczym alpha-9 (jar
startera z 06:39, `mcp-platform-test` nie doszedł do `install`, bo testy modułu padły). Wersja poprawiona natychmiast na
SNAPSHOT. Odtworzenie nie jest zrobione: `install` z tagu została zablokowana przez klasyfikator uprawnień (nadpisanie wydanej
wersji). Receptura: `git worktree add <katalog> v3.0.0-alpha-8 && cd <katalog> && ./mvnw -DskipTests install` (build z
tagu daje jary identyczne z wydanymi poza MANIFEST i `pom.properties`, jak potwierdził przegląd alpha-8). Do decyzji człowieka.

3.0.0-alpha-8: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-8`, tag
`v3.0.0-alpha-8` (praca na `3.0.0-alpha-8-SNAPSHOT`, alpha-7 w `~/.m2` nietknięte). Instrukcja dla serwerów:
`MIGRATION-3.0.md`, sekcja "Zmiany w 3.0.0-alpha-8". Kamienie:
1. Przegląd Opus alpha-7, A7-M2 i LOW. `TextHygiene.normalize` zastępuje usunięty ciąg niewidocznych znaków jedną spacją
   (pomijaną na brzegach i obok białych znaków), więc `@a<ZWSP>ll` i `!<ZWSP>https` nie sklejają się w token; konstruktor
   `UntrustedText` normalizuje (L5: `new` i Jackson nie omijają higieny); MIGRATION: kolejność normalizacja, filtr, fabryka.
   L1: klucze obiektów przez `TextHygiene` (wartość z `pattern`), komunikat bez cytowania klucza. L2: element `i` oceniany
   regułą `prefixItems[i]`, testy treści prefiksu i startu z nieograniczonym prefiksem. L6: opakowanie bierze politykę z
   profili (nie z beana, więc `@Bean Object` zwracający `DEV`, `BeanPostProcessor` i nadpisanie definicji nic nie zmieniają),
   `RegisteredToolsCheck` i `ToolHintsPostProcessor` bez `@ConditionalOnMissingBean` i objęte `ForeignToolPolicyCheck`.
   L8: `describeChain` przez `TextHygiene.normalize`, jedna linia, bez zapytania w URL. L9: `CapturedLogs.snapshot` (kopia pod
   monitorem appendera) we wszystkich testach czytających `ListAppender`. Literówka "a integer" w komunikacie limitu.
   298 testów (starter 238, mcp-platform-test 60).
2. A7-M1: wynik z `null` w komponencie bez `@Nullable`. Wybrany kierunek: walidacja wyniku w opakowaniu (`ToolResultValidator`
   w `AuditingToolCallback`, przed audytem) zamiast zmiany schematu na domyślnie nullowalny: zgodność ze schematem
   publikowanym w `definitionHash` zostaje ścisła (pole bez `@Nullable` jest `required`), a rozjazd typ-serializacja
   (null, `@JsonProperty`, `@JsonIgnore`) kończy się C10 `internal_error` i audytem `error`, z ścieżką pola w logu ERROR
   i bez wartości. Test end-to-end przez klienta SDK (`OutputSchemaTest`: `@Nullable` puste = ok, bez `@Nullable` = C10 +
   `outcome=error`; mutacja: bez walidacji test czerwony) i `ToolResultValidatorTest`. README i MIGRATION opisują zasadę
   (serwer jira i confluence: `@Nullable` na polach z systemu). Razem 303 testy (starter 241, mcp-platform-test 62).
3. Smoke Haiku alpha-7 (FAIL: README nie pozwalał uruchomić serwera lokalnie). Przyczyna: `NoSuchSslBundleException 'mcp-jira'`
   (`server.ssl.bundle` z konfiguracji z README) i `'mcp-internal-ca'` (`jwks-ssl-bundle`), do tego `replay-store: redis` i
   `mtls.required: true`; `application-dev.yml` serwera trzeba było uzupełniać obejściami z CLI. Teraz
   `PlatformDefaultsEnvironmentPostProcessor` przy `dev` jako jedynym profilu wymusza powyżej `application*.yml` serwera
   (CLI, właściwości systemowe i środowisko wygrywają): `server.ssl.enabled=false`, `mtls.required=false`,
   `replay-store=memory`, puste `jwks-ssl-bundle` (loopback pilnuje `PlatformConfigurationGuard`). `allow-test-keys` nie jest
   wymuszane. README: sekcja "Uruchomienie lokalne" (tabela tego, co `dev` ustawia, komenda startu, health 200,
   `POST /mcp` bez JWT -> 401, kolejność błędów poza `dev`). Sprawdzone według sekcji na serwerze z konfiguracją skopiowaną z
   README, bez `application-dev.yml`: `spring-boot:run -Dspring-boot.run.profiles=dev`, port 18600, health 200, 401 z
   `WWW-Authenticate: Bearer`. Kolejność błędów poza `dev` (brak limitu argumentu niezależnie od błędu SSL): nie zmieniona.
   Serwer HTTP startuje w `onRefresh`, przed utworzeniem beanów narzędzi, więc błąd SSL zawsze poprzedza kontrolę
   limitów; sprawdzenie schematów przed startem serwera HTTP wymagałoby tworzenia beanów narzędzi przed kontekstem
   web (zmiana modelu startu Springa), a koszt to jedna dodatkowa iteracja przy poprawianiu konfiguracji. README opisuje
   kolejność faz. Testy: `PlatformConfigurationGuardTest` (trzy nowe: nadpisania w `dev`, CLI wygrywa, brak nadpisań poza
   `dev`). Razem 306 testów (starter 244, mcp-platform-test 62).

3.0.0-alpha-7: wydane - `pl.aiwcorpo.mcp:mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-alpha-7`, tag `v3.0.0-alpha-7`
(pracowano na `3.0.0-alpha-7-SNAPSHOT`, alpha-6 i wcześniejsze nietknięte). Instrukcja dla serwerów: `MIGRATION-3.0.md`,
sekcja "Zmiany w 3.0.0-alpha-7". Kamienie
1. Kontrakty 2.1.1 przez BOM i własność `mcp-contracts.version` modułów. Zmiana w kodzie: `server/discover` jest
   żądaniem protokołu (wariant serwisowy dozwolony, CC-069); `subscriptions/listen`, `notifications/*`, GET/DELETE
   już wymagały wariantu użytkownika (CC-070). Wektory JWT 51 (było 43). Schemat Cedar bajtowo bez zmian w 2.x.
   `./mvnw clean install`: 250 testów (starter 192, mcp-platform-test 58).
2. Zdarzenie audytu `authentication` (C8, CC-071, zamknięta lista pól z 2.1.1): `AuditLogger.authenticationDenied`,
   wołane z `InternalJwtFilter` przy każdym odrzuceniu (401 z `reason` = kod odrzucenia, 503 z `errorCode`
   `upstream_unavailable`); bez `sub`, `username`, `client`, `tool`, bez treści tokenu. Testy: `InternalJwtAuditTest`,
   `GatewayTransportTest.withoutJwt401` (zdarzenie w audycie po żądaniu bez JWT). Razem 255 testów (starter 197).
3. S-M1, S-L2, S-L3 (przegląd alpha-6): limit na każdym poziomie `inputSchema`, poza `dev` brak limitu zatrzymuje
   start. `InputSchemaBounds` przechodzi schemat rekurencyjnie (właściwości, `items`, `prefixItems`, `$ref`,
   `allOf/anyOf/oneOf`, głębokość do 8): obiekt wymaga `additionalProperties: false` (mapa typowana tylko z
   `maxProperties`, `propertyNames.maxLength` i ograniczoną wartością), tekst `maxLength`, lista `maxItems` i
   `items`, liczba `minimum` i `maximum`, brak typu odrzucany; `pattern` musi zaczynać się od `^`, kończyć na `$`
   i nie ma mieć `|` poza grupą (S-L2: `[0-9]+` i `^a|b$` zatrzymują start). `SchemaLimitedToolCallbacks` publikuje
   limity z `@ArgumentLimits` także dla komponentów rekordów (zagnieżdżone obiekty zamknięte; opcjonalne pole
   oznacza się `@Nullable`, Spring AI nie daje wtedy `required`), elementów list (`maxItems`, limity elementów) i
   liczb (`minimum` i `maximum` obowiązkowe); `Map`, obiekt niebędący rekordem i lista list zatrzymują start.
   `ToolInputPolicy.requireClosedSchema` zmienione na `requireBoundedSchema`. S-L3: `ToolInputPolicy` i
   `ToolCallbackAuditPostProcessor` nie są już `@ConditionalOnMissingBean`; `ForeignToolPolicyCheck` zatrzymuje
   start poza `dev`, gdy serwer zadeklaruje własny bean któregoś z tych typów. Poprawka przy okazji: liczba
   spoza zakresu `long` (`1e30`) powodowała wyjątek biblioteki schematów i `internal_error` z ERROR w logu; teraz
   `invalid_argument`. Testy: `BoundedSchemaTest` (8), `ForeignToolPolicyCheckTest` (4), zmienione testy schematów.
   Razem 267 testów (starter 209, mcp-platform-test 58).
4. S-L1 i `UntrustedText` (CC-072): pakiet `pl.aiwcorpo.mcp.platform.text`. `TextHygiene.normalize` (tekst z
   systemu: usuwa C0/C1/DEL, niesparowane surogaty, nieznaki, każdy `\p{Cf}` łącznie z bidi, zero-width, U+2060,
   BOM i znakami tagów; U+2028/U+2029/U+0085 i CR dają `\n`) i `TextHygiene.firstProblem` (tekst od modelu).
   `ToolInputValidator` używa go dla każdego napisu w argumentach: w tekście swobodnym odrzuca kontrolne, U+2028/9,
   niesparowane surogaty i nieznaki, bidi override/isolate, zero-width, U+2060..2064, BOM, znaki tagów, a ogólnie każdy
   `\p{Cf}` poza U+200C/U+200D (pisma indyjskie, perskie, emoji) i U+200E/U+200F (znaczniki kierunku): allowlista, nie
   denylista; miękki łącznik U+00AD, U+061C i U+FFF9..FFFB są odrzucane; w wartości z `pattern` także `\n \r \t` i każdy `\p{Cf}`.
   `UntrustedText` (record: `kind`, `format`, `text`, `truncated`; fabryki `plain`/`markdown` normalizują i tną do
   limitu bez rozcinania pary surogatów). `ToolOutputSchemas`: narzędzie, którego wynik (rekord, także zagnieżdżony
   lub w liście) zawiera `UntrustedText`, dostaje `outputSchema` z definicją z `common.schema.json` kontraktów,
   publikowaną w `tools/list` (wchodzi do `definitionHash`), a wynik `structuredContent` (MCP wymaga go przy
   `outputSchema`; Spring AI 2.0.0 zwraca sam tekst). Pola dodatkowe systemu (np. `omittedMacros`) idą obok
   `UntrustedText` w rekordzie wyniku, nie wewnątrz. Testy: `TextHygieneTest`, `ToolOutputSchemasTest`,
   `BoundedSchemaTest` (znaki, wiersze w zagnieżdżonych wartościach), `OutputSchemaTest` (transport, klient SDK
   waliduje `structuredContent`). Poprawki z automatycznego przeglądu bezpieczeństwa (MEDIUM): `prefixItems` bez
   `items` (elementy poza prefiksem bez limitu) i reguła `\n\r\t` brana z argumentu najwyższego poziomu dla wartości
   zagnieżdżonych; opis w tabeli poniżej. 5. `tool_failed` dla wyjątków domenowych: `McpErrorMapper` loguje INFO (kod C10 i klasa wyjątku, bez komunikatu i
   stosu) dla błędów oczekiwanych, WARN dla `upstream_unavailable`, ERROR tylko dla `internal_error`. Test:
   `ToolFailureLoggingTest`. Kombinatory (`allOf`, `anyOf`, `oneOf`, `not`, `if`) w `inputSchema` są odrzucane poza `dev`
   (żaden serwer ich nie używa, Spring AI ich nie generuje). Razem 287 testów (starter 227, mcp-platform-test 60).

Znalezisko -> status (przegląd Opus alpha-7 i smoke Haiku alpha-7):

| Znalezisko | Status | Gdzie |
| --- | --- | --- |
| A7-M1 wynik z `null` w komponencie bez `@Nullable`: SDK, nie C10, audyt `ok` | naprawione: wynik sprawdzany względem `outputSchema` w opakowaniu przed audytem, C10 `internal_error` + `outcome=error` | `ToolResultValidator`, `OutputSchemaTest`, `ToolResultValidatorTest` |
| A7-M2 `normalize` skleja tokeny po usunięciu Cf | naprawione: usunięty ciąg zastępuje jedna spacja; MIGRATION: normalizacja przed filtrem serwera | `TextHygieneTest` (sklejanie, brzegi, idempotencja) |
| A7-L1 klucze map bez `TextHygiene` | naprawione | `BoundedSchemaTest.keysOfObjects...` |
| A7-L2 `prefixItems`: treść bez testu, reguła wg `items` | naprawione: element `i` wg `prefixItems[i]`; test startu z nieograniczonym prefiksem | `BoundedSchemaTest.eachPrefixElement...`, `anUnboundedPrefixElement...` |
| A7-L3 heurystyka `allowsWhitespaceControls` i kotwice składniowe | otwarte, świadomie (niżej) | - |
| A7-L4 niewidoczne znaki poza `\p{Cf}` | otwarte, świadomie (niżej) | propozycja CC 5 |
| A7-L5 kanoniczny konstruktor `UntrustedText` przyjmuje surowy tekst | naprawione: konstruktor normalizuje (także Jackson) | `TextHygieneTest.theConstructorNormalizesToo` |
| A7-L6 `ForeignToolPolicyCheck` i pozostałe `@ConditionalOnMissingBean` | naprawione: polityka z profili, nie z beana; `RegisteredToolsCheck` i `ToolHintsPostProcessor` bez `@ConditionalOnMissingBean` i objęte kontrolą | `ForeignToolPolicyCheckTest` |
| A7-L7 400/413 przed JWT bez `authentication` i bez 401 | otwarte, świadomie (niżej) | - |
| A7-L8 `describeChain` czyści tylko `"`, `\n`, `\r` | naprawione: `TextHygiene.normalize`, jedna linia, bez zapytania w URL | `McpErrorMapperTest` |
| A7-L9 niestabilny `trailingLineBreaksDoNotSatisfyTheAnchoredPattern` | naprawione w teście: `CapturedLogs.snapshot` (kopia pod monitorem appendera) we wszystkich 7 klasach czytających `ListAppender` | `CapturedLogs` w obu modułach |
| Smoke alpha-7: README nie pozwala uruchomić serwera lokalnie | naprawione: sekcja "Uruchomienie lokalne", nadpisania `dev`; sprawdzone według sekcji | `PlatformConfigurationGuardTest`, ręcznie port 18600 |
| Literówka "a integer" w komunikacie limitu | naprawione | `SchemaLimitedToolCallbacks.numberLimits` |

Otwarte z uzasadnieniem:
- A7-L3: wzorce pisze autor serwera, więc to obrona przed pomyłką, nie przed atakiem; zaostrzenie (reguła Cf zawsze ścisła
  przy `pattern`, próbka `"a\nb"` skompilowanym regexem, odrzucanie `(?x)`, `\Q`) zmienia zachowanie istniejących wzorców
  i wymaga przejrzenia wzorców wszystkich pięciu serwerów; plan na beta, razem z `BackendRestClient` (W2-L1).
- A7-L4: lista dodatkowych niewidocznych znaków (selektory wariantu, CGJ, wypełniacze Hangul, U+2800) ma być wspólna z
  gateway'em (wektory `text-hygiene`, propozycje CC 2 i 5); jednostronne rozszerzenie rozjechałoby gateway i serwery, a
  selektory wariantu są częścią zwykłych emoji (U+FE0F), więc reguła wymaga kontekstu, nie samej listy.
- A7-L7: 400/413 przed weryfikacją JWT osiągalne tylko z certyfikatem gateway'a (mTLS); weryfikacja podpisu przed
  buforowaniem ciała nie jest możliwa bez ciała (wiązanie `tool` z `params.name`), a samo zdarzenie `authentication` dla
  400/413 z `reason=malformed_request` wymaga decyzji, czy to 401 (niezmiennik 3) czy 400; do rozstrzygnięcia z ws-05.

Znane ograniczenia alpha-8: `TextHygiene.normalize` rozbija sekwencje emoji z ZWJ na osobne emoji rozdzielone spacją i
zamienia ZWNJ w tekście perskim na spację (koszt braku sklejania); `pattern` jest wykonywany regexem JDK.

Znalezisko -> status (przegląd alpha-6 i przeglądy automatyczne commitów alpha-7):

| Znalezisko | Status | Gdzie |
| --- | --- | --- |
| S-M1 limity tylko na tekście najwyższego poziomu | naprawione: `InputSchemaBounds` na każdym poziomie, `@ArgumentLimits` na komponentach rekordów, listy (`maxItems`), liczby (`minimum`+`maximum`), `Map` odrzucona | `BoundedSchemaTest`, `SchemaLimitedToolCallbacks` |
| S-L1 znaki poza C0/C1 | naprawione: `TextHygiene` w walidacji argumentów i `normalize` dla tekstu z systemu | `TextHygieneTest`, `BoundedSchemaTest` |
| S-L2 wzorce bez kotwic | naprawione: `pattern` musi mieć `^`...`$` i `|` tylko w grupie; reguła znaków białych liczona dla węzła wartości | `BoundedSchemaTest` |
| S-L3 polityka wyłączana beanem | naprawione: bez `@ConditionalOnMissingBean`, `ForeignToolPolicyCheck` zatrzymuje start poza `dev` | `ForeignToolPolicyCheckTest` |
| W2-L1 klient backendu kopiowany per serwer | otwarte: `BackendRestClient` w starterze nie zrobiony (poza zakresem alpha-7), kandydat na beta | - |
| W2-L2 testy 3xx w serwerach | nie dotyczy startera (testy serwerów jira i confluence) | - |
| Review auto MEDIUM 1: `prefixItems` bez `items` | naprawione: `items` wymagane (`false` albo ograniczony) | `BoundedSchemaTest.elementsPastAPrefixNeedALimitToo` |
| Review auto MEDIUM 2: `\n\r\t` według reguły najwyższego poziomu | naprawione: reguła wyznaczana dla węzła każdej wartości (`properties`, `items`, wartości mapy, `$ref`); brak węzła = formatted | `BoundedSchemaTest.lineBreaksAreJudgedByTheRuleOfTheValueTheySitIn` |
| Review auto MEDIUM 3: kombinatory i `$ref` z `pattern` obok | naprawione: kombinatory odrzucane w `InputSchemaBounds`; `pattern` sprawdzany na węźle i na całym łańcuchu `$ref`, kombinator w dev = formatted | `BoundedSchemaTest.combinatorsAre...`, `aPatternBesideARef...` |
| Review auto MEDIUM 4: denylista Cf | naprawione: allowlista U+200C..U+200F, reszta `\p{Cf}` odrzucona w tekście swobodnym | `TextHygieneTest.freeTextKeepsOnlyTheJoinersAndDirectionMarks` |
| `1e30` w argumencie liczbowym -> `internal_error` + ERROR | naprawione: `invalid_argument` | `BoundedSchemaTest` |

Znane ograniczenia alpha-7: `TextHygiene.normalize` usuwa także U+200D, więc sekwencje emoji z ZWJ rozpadają się na
osobne emoji; `InputValidationTest.trailingLineBreaksDoNotSatisfyTheAnchoredPattern` zdarzył się raz czerwony przy
pełnym przebiegu (`ListAppender` bez synchronizacji czytany z wątku testu, zapis z wątku Tomcata), w kolejnych
przebiegach zielony: do uodpornienia w teście, nie w kodzie startera; `pattern` jest nadal wykonywany regexem JDK.

## Propozycje CC (do przeniesienia przez ws-00 do CONTRACT_CHANGES.md; status proposed)

Nowe w beta-1 (numeracja robocza):
- **Beta-A: `OpenSearchIndexPrefix` w `common.schema.json` ma alternację na wierzchu (`^...$|^[a-z0-9]$`)**, więc nie spełnia reguły
  `input-schema-bounded` (CC-123), gdy serwer użyje go jako wzorca argumentu przez `contractPattern` (CC-079). Starter przepisuje go
  na `^(?:...|...)$`. Propozycja: jedna zakotwiczona postać w kontrakcie (np. `^(?:[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9])$`).
- **Beta-B: README kontraktów mówi, że manifest generuje starter "w czasie budowy" (`target/mcp-manifest.json`)**; starter robi to przy
  starcie z tego, co publikuje (`tools/list`) i zapisuje plik na żądanie. Propozycja: sformułowanie "przy starcie lub w budowie".
- **Beta-C: kod C10 dla niedostępnej polityki na serwerze** (brak paczki po restarcie z oczekiwanym `overrides`, błąd własny): starter
  używa `upstream_unavailable`; kontrakt tego nie mówi.
- **Beta-D: `reason` zdarzenia `policy_decision` na serwerze** (`policy_forbid` dla odmowy polityki, `not_granted` dla zasobu
  nierozwiązanego): kontrakt wymienia wartości, ale nie rozróżnia przypadków serwera.
- **Beta-E: `BundleFetcher` w `mcp-policy-bundle`** (CC-132 odłożone): `PolicyBundleClient` startera i klient gateway'a to dwie
  implementacje tego samego protokołu bez wspólnych wektorów.
- **Beta-H (B2-L9): kolejność oceny.** Przy braku grantu na typ builder powinien zwrócić `not_found_or_denied` przed budową encji i klasyfikacją; dziś zasób bez wymaganego
  atrybutu daje `internal_error`, co przy resolverze z kontem serwisowym zdradza istnienie indeksu.
- **Beta-I: znacznik środowiska w manifeście C6.** Manifest nie ma pola na środowisko (`additionalProperties: false`), więc serwer z profilem `devstack` oznacza się logiem i metryką.
  Propozycja: opcjonalne pole `environment` (`production` | `dev`) w manifeście, które gateway może odrzucić na produkcji.
- **Beta-F: `SystemId` i `ServerId` w kontrakcie to enum pięciu systemów**, więc serwer przykładowy (`mcp-example` w template) nie może mieć ważnego
  manifestu ani paczki polityk nie jest dla niego zdefiniowana; template i testy udają `mcp-jira` albo działają w `dev` z ostrzeżeniem. Propozycja:
  `ServerId` dopuszcza wzorzec `^mcp-[a-z0-9]+$` poza listą systemów (z wyjątkiem rejestru produkcyjnego), albo kontrakt mówi wprost, że przykład
  nie jest rejestrowalny.
- **Beta-G: tekstowe zapytanie od modelu.** Kontrakt (entity-builder.md, sekcja 8) mówi o cytowaniu identyfikatorów w JQL/CQL, ale nie zabrania
  surowego zapytania od modelu; recenzja pokazała, że nie da się go bezpiecznie ograniczyć po stronie serwera. Propozycja: zapisać w sekcji 8, że
  zapytanie buduje serwer ze struktury.
- Zgłoszone koordynatorowi (naprawione w 3.0.2): jary `test-vectors` 3.0.0 i 3.0.1 miały nieaktualne pliki.

Stan po kontraktach 2.2.5: punkty 1, 2 i 6 przyjęte jako CC-123, CC-121 i CC-122 (3.0.0), punkt 3 jako CC-112 (2.2.4), punkt 4 jako CC-120 (2.2.5).
Pozostałe poniżej wymagają decyzji ws-00; nowe punkty na końcu listy.

1. C6/C10 (rozszerzenie CC-078): `inputSchema` w manifeście ma limit na każdym poziomie: obiekt `additionalProperties:
   false`, tekst `maxLength`, lista `maxItems` i `items`, liczba `minimum` i `maximum`, wzorzec z kotwicami,
   bez `allOf/anyOf/oneOf/not/if`, bez map otwartych. Gateway (walidujący argumenty z manifestu) i serwer wtedy
   zgadzają się co do tego, co jest poprawne; reguła w `server-manifest.schema.json` (semantyczna) i wektory
   odrzuconych manifestów (mapa otwarta, lista bez `maxItems`, liczba bez zakresu, `prefixItems` bez `items`).
2. C10/C8 (higiena tekstu): wspólna definicja znaków niedozwolonych w argumentach i w tekście z systemu (C0/C1/DEL,
   U+2028/U+2029, niesparowane surogaty, nieznaki, `\p{Cf}` poza U+200C..U+200F; w wartości z `pattern` także
   `\n\r\t`), wektory `test-vectors/text-hygiene/` z oczekiwanym wynikiem, żeby gateway i serwery nie rozjechały się.
3. C6/C10 (uzupełnienie CC-072): narzędzie z `outputSchema` zwraca `structuredContent` zgodny ze schematem (MCP);
   `UntrustedText` jest rekordem wyniku, a pola systemu (`omittedMacros`) leżą obok, nie wewnątrz; dopuścić w `format`
   wartość dla wiki markup albo zapisać, że serwer go konwertuje (J2-L2).
4. C8 (patch): lista znanych `reason` dla `authentication` uzupełniona o `client_certificate_rejected`,
   `jwks_unavailable`, `replay_store_unavailable`; przy kodach `jwks_unavailable` i `replay_store_unavailable`
   `errorCode` to `upstream_unavailable` (503), nie `unauthenticated`.
5. C10/C8 (uzupełnienie 2): w `test-vectors/text-hygiene/` wektor normalizacji tekstu z systemu: usunięty ciąg znaków
   niewidocznych zastępuje jedna spacja (`@a<ZWSP>ll` -> `@a ll`, `!<ZWSP>https://h/x.png!` -> `! https://h/x.png!`), pomijana
   na brzegach i obok białych znaków; do wspólnej listy dopisać znaki niewidoczne poza `\p{Cf}` (A7-L4): selektory wariantu
   U+FE00..FE0F i U+E0100..E01EF poza pojedynczym U+FE0F po emoji, U+034F, U+115F, U+1160, U+3164, U+FFA0, U+2800 w wartości z `pattern`.
6. C10/C8 (A8-L5 iii, wspólna z gateway'em): normalizacja NFKC w wektorach `text-hygiene/`. Pełna szerokość
   (`U+FF20 U+FF41 U+FF4C U+FF4C`) przechodzi `normalize` bez zmian, a po NFKC po stronie odbiorcy daje `@all`. Decyzja do podjęcia
   razem dla gateway'a i serwerów (NFKC zmienia też zwykły tekst: ligatury, znaki zgodnościowe, pełną szerokość w CJK); do
   czasu decyzji starter nie normalizuje NFKC, żeby nie rozjechać gateway'a i serwerów.
7. C8 (opis, patch; nowe w alpha-12, uzupełnione w alpha-13): zapisać, że odbiorca zdarzenia `authentication` może ograniczyć częstość żądań bez
   poprawnego tokenu na proces (domyślnie 20 na sekundę; starter obejmuje `missing_token` i `malformed`, gateway dziś tylko `missing_token`: do ujednolicenia
   z ws-05, bo pusty lub obcy nagłówek `Authorization` omija limit gateway'a), a nadmiar wyłącznie liczyć; nazwy liczników dziś różne
   (`gateway_audit_authentication_suppressed_total`, `mcp_server_audit_authentication_suppressed_total`), więc SIEM i alerty
   potrzebują albo wspólnej nazwy, albo listy. Do rozstrzygnięcia przez ws-00 razem z ws-05.
8. C8 (nowe w alpha-14): osobny powód `header_not_allowed` dla nagłówka tokenu z `crit` albo niedozwolonym `typ` po rozwiązaniu `kid`; starter raportuje go dziś jako `alg_not_allowed`.

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

Incydent budowy: przed wydaniem `./mvnw install` zainstalował niewydany kod jako 3.0.0-alpha-5 w `~/.m2`
(nadpisanie wydanej wersji). Przywrócone z tagu `v3.0.0-alpha-5` (jar bez `ForeignPrimaryCustomizerCheck`);
prace szły na `3.0.0-alpha-6-SNAPSHOT`, wersja `3.0.0-alpha-6` ustawiona dopiero przy wydaniu.
Recenzja serwerów (HIGH): nazwa nieznanego argumentu w logu walidacji SDK mogła sfałszować linię audytu C8;
zamknięte przez wyłączenie walidacji SDK (test z nazwą `x\n{"ts":"FORGED"...}`: C10, C8, brak linii z FORGED).
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
- Odrzucenia uwierzytelnienia: od alpha-7 zdarzenie C8 `authentication` (wcześniej tylko log aplikacji).
- Wariant serwisowy tylko dla czterech żądań z C2 (dosłownie): `notifications/initialized` i `GET`/`DELETE` sesji
  wymagają wariantu użytkownika (CC zgłoszone; ws-05 i tak wysyła wariant użytkownika).
- Brak powiązania sesji MCP z `sub` po stronie serwera (C2 nakłada to na gateway).
- Brak własnego health indicatora magazynu `jti`; zdrowie Redisa ze Spring Boot wyłączone domyślnie (komenda
  `INFO` poza ACL).
- `mcp-platform-test` zależy od jara `test-vectors` (klucze testowe) w zakresie compile modułu; serwery dodają
  moduł wyłącznie w `test`.

## Zostało na 3.0.0

Punkty 3, 4, 6, 7 briefu są w beta-1 (patrz wyżej), punkt 5 (`ResultFilter`) także w podstawowej postaci. Na 3.0.0: recenzja
bezpieczeństwa, smoke od README, dokumentacja końcowa. Usunięcia z briefu zrobione w całości w alpha.

## Propozycje zmian kontraktu (mcp-contracts/CONTRACT_CHANGES.md, status open, zgłasza ws-02)

- C2: wariant serwisowy a `notifications/*` i żądania transportu (GET/DELETE sesji).
- C8: zdarzenie audytu dla odrzuconego uwierzytelnienia na serwerze (przyjęte jako CC-071, zrobione w alpha-7).
- C6/C10: zamknięty `inputSchema` i treść `invalid_argument` przy naruszeniu schematu (CC-???, alpha-6).
- C6: nazwy `$defs` w `common.schema.json` jako stabilne API dla serwerów (`contractPattern`) (CC-???, alpha-6).

M5: w toku - kod produkcyjny na kontraktach 5.1.0 wydany jako 3.0.0-beta-10; brakuje przebiegu `ci/run.sh` na prawdziwym runnerze (dowód do linii "osiągnięty").

3.0.0-beta-11: wydane - `pl.aiwcorpo.mcp:mcp-platform|mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-11`, tag `v3.0.0-beta-11` (po wydaniu `3.0.0-beta-12-SNAPSHOT`). Kontrakty 6.2.1, `ci/run.sh release` z 976 przypadkami testowymi i 0 pominiętych. Instrukcja: `MIGRATION-3.0.md`, "Z beta-10 na beta-11". Zakres: tabela "M6 etap 3" na górze.

3.0.0-beta-12: wydane - `pl.aiwcorpo.mcp:mcp-platform|mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-12`, tag `v3.0.0-beta-12` (po wydaniu `3.0.0-beta-13-SNAPSHOT`). Kontrakty 6.2.1, 989 przypadków testowych, 0 pominiętych. Instrukcja: `MIGRATION-3.0.md`, "Z beta-11 na beta-12" (podbicie wersji). Zakres: tabela "Poprawki po przeglądzie beta-11" na górze.

3.0.0-beta-13: wydane - `pl.aiwcorpo.mcp:mcp-platform|mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-13`, tag `v3.0.0-beta-13` (po wydaniu `3.0.0-beta-14-SNAPSHOT`). Instrukcja: `MIGRATION-3.0.md`, "Z beta-12 na beta-13".

3.0.0-beta-14: wydane - `pl.aiwcorpo.mcp:mcp-platform|mcp-platform-bom|mcp-platform-starter|mcp-platform-test:3.0.0-beta-14`, tag `v3.0.0-beta-14` (po wydaniu `3.0.0-beta-15-SNAPSHOT`). Kontrakty 6.3.2. Instrukcja: `MIGRATION-3.0.md`, "Z beta-13 na beta-14".
