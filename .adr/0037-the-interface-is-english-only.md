# 0037 — The interface is English-only

Status: **Accepted** (2026-09-04)

Supersedes: [ADR 0030](./0030-the-ui-is-translated-by-a-typed-dictionary-not-a-library.md)

## Context

Collie previously carried multiple interface catalogs, language selectors, device-local locale
preferences, and parallel Android resource overlays. That duplicated every copy change across the
web and native clients and created avoidable parity and maintenance work.

The operator requires one product language. This applies to Collie-owned interface copy, not to
terminal output, agent responses, operator commands, filenames, or protocol payloads, which must
remain faithful to their source.

## Decision

Collie's web and Android interfaces ship in English only.

- `web/src/lib/i18n/messages/en.ts` is the sole web message catalog. `t()` and `tn()` remain as the
  typed copy and interpolation boundary, not as a locale-selection system.
- The web app has no language selector, alternate catalogs, lazy locale loading, or saved locale.
- The Android app has no language selector, application-locale preference, locale configuration,
  localized resource overlays, or translation generator/cache.
- Existing saved web locale choices are discarded. Existing Android installs resolve all Collie
  resources from the English base catalog after upgrade.
- Tests and repository checks must reject a reintroduced alternate catalog or locale surface.

## Consequences

- Copy changes happen once per client, and parity work no longer includes translation synchronization.
- Collie-owned interface text is predictable during testing and support.
- Host and operator content remains arbitrary Unicode and is never translated or rewritten.
- Adding another interface language requires a new superseding ADR and the full web/native parity,
  accessibility, persistence, and maintenance design; a one-off catalog is not sufficient.
