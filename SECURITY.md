# Security policy

Collie for Android controls terminal sessions through a self-hosted Collie server. Treat a
paired phone as a credential with the host user's terminal privileges.

## Report a vulnerability

Use GitHub's private reporting route for this fork.

[Report a vulnerability privately](https://github.com/Buckeyes22/collie/security/advisories/new).
Include the app version, server version, affected code path, reproduction steps and impact.
Use a disposable server or test pane when demonstrating terminal writes. Do not attach live
credentials, unreviewed diagnostic exports or private terminal content.

## Scope and supported releases

This fork's security policy covers the native Android app and its Android release tooling.

Security fixes target the newest published Android release; users should update to that
release. Older development APKs and local modifications have no maintenance
guarantee. Server vulnerabilities belong with the [upstream Collie project](https://github.com/AltanS/collie/security).

Relevant boundaries include credential storage, diagnostic exports, TLS validation, redirect
refusal, screen-bound terminal writes, exported Android components and APK signing. The client
does not replace the server's ingress, identity or pairing controls. Server guidance lives in
[docs/security.md](docs/security.md).

## If a credential was exposed

Revoke the affected device on the server immediately.

Generate a fresh pairing code and pair again after addressing the exposure. Deleting a local
connection alone does not revoke its server credential. Review any exported diagnostics and
screenshots before posting a report.
