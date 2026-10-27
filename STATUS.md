# STATUS - mcp-starter-lib (ws-02)

Wydanie docelowe: 3.0.0 (linia `pl.aiwcorpo.mcp:mcp-platform*`, niezależna od wersji kontraktów).
Kontrakty: `pl.aiwcorpo.mcp:mcp-contracts:2.0.2`.

## 3.0.0-alpha (w toku)

- [x] `InternalJwtFilter` / `InternalJwtVerifier` (C2): wszystkie 43 wektory `test-vectors/jwt` (wynik i powód).
- [x] `CredentialContext` v3 z nagłówków C3, `credential_required` (C10).
- [x] tryb `dev-pat` tylko z profilem `dev`.
- [x] audyt C8, błędy C10.
- [ ] moduł `mcp-platform-test`, testy na prawdziwym transporcie, wydanie.

## Bezpieczeństwo

- HIGH z automatycznego przeglądu (2026-10-27): ominięcie `InternalJwtFilter` przez ścieżki wyjątków z `/**`
  i różnicę normalizacji. Poprawione: wyjątki to wyłącznie dokładne ścieżki (domyślnie `/actuator/health`,
  `/actuator/health/liveness`, `/actuator/health/readiness`), porównanie ze ścieżką znormalizowaną przez
  kontener (`servletPath + pathInfo`), a surowe URI z `;`, `..`, `%`, `\` albo `//` nigdy nie jest wyjątkiem
  (przechodzi pełną weryfikację, bez JWT 401). Testy: `InternalJwtFilterTest` (jednostkowo) i test na
  uruchomionym Tomcacie w `mcp-platform-test`.
