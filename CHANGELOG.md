# Changelog

Format: wersje SemVer; kolejność od najnowszej. Przed 3.0.0 wersje `alpha` i `beta` (tagi `v3.0.0-alpha-N`,
`v3.0.0-beta-N`) nie gwarantują zgodności między sobą; kroki migracji każdej z nich są w `MIGRATION-3.0.md`.
Linia `mcp-platform` 3.x nie ma związku z wersją `mcp-contracts` (BOM zarządza obiema).

## 3.0.0-beta-13 - 2026-11-03

- `CursorCodec`: kursor jest szyfrowany i uwierzytelniany (AES-256-GCM), nie tylko podpisany. Klucz z `paging.cursor-key` przez
  HKDF-SHA256 z `server-id` jako kontekstem, losowy nonce, dane uwierzytelniane to `sub`, narzędzie i odcisk zapytania. Ładunek
  (np. wartości sortowania ukrytego trafienia) jest nieczytelny dla klienta. API bez zmian; każdy zły kursor to nadal ten sam `invalid_argument`.

## 3.0.0-beta-12 - 2026-11-03

Zmiany zgodne z beta-11 (podbicie wersji); poprawki po przeglądzie beta-11.

- `PageFiller`: dopełnianie strony kolejnymi stronami systemu do żądanego rozmiaru (z limitem odczytów), żeby krótka strona z
  `nextCursor` nie zdradzała ukrytych elementów; `Filled.exhausted()` mówi o wyczerpanym limicie.
- `CursorCodec`: podpis obejmuje `server-id` (nowy konstruktor, stary zostaje), kodowanie kursora jednoznaczne (bez `=` i bez
  niezerowych bitów dopełnienia), `NullPointerException` z komunikatem dla `null` w `tool`/`fingerprint`, ostrzeżenie o losowym kluczu
  dopiero przy pierwszym wydanym kursorze.
- `CapabilityProbeRunner`: sonda nie jest uruchamiana ponownie, dopóki poprzednie wywołanie trwa; po jednym wątku roboczym i
  harmonogramu na sondę; `start()` jednokrotne.
- `ProductVersion`: składnik powyżej 9999 nie jest wersją (`9.12.34567` jest puste, nie `9.12.0`).
- Workflow wydania: job `verify` bez środowiska i bez prawa zapisu, `publish` z `packages: write` i środowiskiem `release`, `sbom` z
  `contents: write` dołącza SBOM do wydania GitHub; limity czasu i `concurrency`; obraz CI po digeście; wersja pluginu help.
  Tag `v3.0.0-beta-11` nie służy do publikacji przez Actions.

## 3.0.0-beta-11 - 2026-11-03

- Kontrakty `mcp-contracts` 6.2.1 (6.0.0 ... 6.2.0 bez zmian w kodzie startera; 6.2.1 to pakowanie i licencja).
  Katalog `audit-events/` ma 125 wektorów (dochodzą zdarzenia brokera); schemat zgadza się ze wszystkimi.
- `CursorCodec`: kursor HMAC-SHA256 związany z `sub`, narzędziem i odciskiem zapytania, ważny 15 minut, jeden stały
  tekst odmowy (`invalid_argument`). Właściwości `aiwcorpo.mcp.platform.paging.*`.
- `PagedResult<T>`: wspólny kształt dużego wyniku (`items`, `nextCursor`, `truncated`, `omitted`), bez `total` i bez liczników
  elementów usuniętych przez politykę. `ToolOutputSchemas` podstawia argumenty typu rekordów generycznych, więc taki wynik
  dostaje `outputSchema`.
- `CapabilityProbe`, `Capabilities`, `BackendCapabilities`, `ProductVersion`: sonda wersji systemu przy starcie i okresowo,
  tryb `degraded` dla nieznanej wersji (serwer wstaje, zestaw cech zachowawczy), log, metryka `mcp_backend_capability`,
  szczegół zdrowia `backendCapability`. Właściwości `aiwcorpo.mcp.platform.capability.*`.
- README: projektowanie powtarzalnych narzędzi `UPDATE` (CC-204), duże wyniki, wersje systemu, instalacja z GitHub Packages.
- Licencja Apache-2.0 (`LICENSE`, `NOTICE` także w `META-INF` każdego jara), `distributionManagement` na GitHub Packages,
  workflow wydania `.github/workflows/release.yml` (SBOM CycloneDX), `SECURITY.md`, `CONTRIBUTING.md`.
- `ci/run.sh` sam szuka JDK 25 (bez ścieżek Homebrew w skrypcie), działa w bash 3.2.

## 3.0.0-beta-10 - 2026-10-31

- Kontrakty 5.1.0: token bez `vs` i `vsc` (401 `invalid_claims`), `Caller` bez serwera wirtualnego, `QueryConstraint(resourceType, granted)`,
  zdarzenia audytu bez `virtualServer`.
- `ci/run.sh` jako jedyny punkt wejścia CI (`verify`, `hygiene`, `release`) z nakładkami GitLab, Jenkins i GitHub Actions.

## 3.0.0-beta-9 ... beta-6 - 2026-10-29

- beta-9: sonda strony lub zgłoszenia bez zakresów leży wewnątrz linku, więc link tylko zawęża; błąd resolvera konta serwisowego
  to `upstream_unavailable` z ostrzeżeniem.
- beta-8: grant dla stron i zgłoszeń z polityki albo zakresu; `ignore_unavailable` w `_resolve/index`.
- beta-7: kontrakty 4.1.0, nagłówek `X-MCP-Credential-Type` sprawdzany przed Cedar; typ domyślny to pierwszy z
  `accepted-credential-types`.
- beta-6: kontrakty 4.0.0, klucze `dev-` tylko w trybie dev, `context.credentialType` z konfiguracji, `StructuredQuery` z
  normatywnych list pól i operatorów.

## 3.0.0-beta-5 ... beta-1 - 2026-10-28

- Polityki zasobów: `@ResourceRef`, silnik paczek polityk (Ed25519, ETag, ostatnia poprawna), `ResultFilter`, `QueryScope`,
  manifest C6 i `GET /mcp-manifest`; profil `devstack`.

## 3.0.0-alpha-14 ... alpha-1 - 2026-10-27 ... 2026-10-28

- Model "serwer za gateway'em": wewnętrzny JWT (C2), poświadczenie backendu (C3), audyt (C8), błędy narzędzi (C10),
  higiena tekstu i `UntrustedText`, limity argumentów, profil `dev`, `mcp-platform-test`.
- Usunięte z 2.x: `OidcIdentityResolver`, `ToolGuard`, `ConfirmationService`, `guard-policy.yml`.
