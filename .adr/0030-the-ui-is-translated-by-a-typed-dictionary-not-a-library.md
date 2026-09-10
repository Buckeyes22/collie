# 0030 — The UI is translated by a typed dictionary, not an i18n library

Status: **Superseded** by [ADR 0037](./0037-the-interface-is-english-only.md) (2026-09-04)

This decision previously governed Collie's multi-language interface. The catalogs, selectors,
locale persistence, native resource overlays, and translation tooling it described have been
removed. The surviving typed `t()`/`tn()` boundary now serves the sole English catalog.
