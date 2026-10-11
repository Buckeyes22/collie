# 0098 — Native identity gateway keeps pairing independent

Status: Proposed implementation; independent review and actual R02 acceptance pending.

## Context

An external browser session does not authorize native OkHttp. R02 requires an explicitly supported
public-client OIDC/PKCE/DPoP profile and an operator-run loopback gateway behind one HTTPS front door.
This supersedes the blanket no-callback/export restriction in the original native policy, the
configured-ingress provenance obligation deferred by [ADR 0096](0096-control-authority-is-scoped-and-positive.md),
and extends the private-state inventory without creating state for unused solo features.

## Decision

Use pinned ES256 JWT access tokens, RFC 7636 S256, an Android Keystore P-256 proof key and a fixed
`com.lateapex.collie:/oauth2redirect` external ACTION_VIEW callback. One exported OpenLinkActivity
dispatches this callback before B23 connection import. Codes cross to the internal sign-in Activity
only through an expiring process-only ID. State, nonce and verifier live in a separate encrypted,
expiring, credential-generation/process-bound transaction. Consume state durably before exchange.
Do not import browser cookies, embed a client secret, follow issuer redirects or send pairing to an issuer.
Identity updates and every retirement use serialized conditional encrypted-record revisions, even
after callback state consumption. No network or main-thread ACK runs under that custody monitor.
Stopping clears profile/modal presentation but retains an intentional external-browser transaction.
A stable enrollment witness survives token refresh/expiry; exact authenticated retirement commits
an encrypted outbox whose durable signed B22 consumer alone may acknowledge it.

The native API uses an explicitly separate public mount. Its configured gateway consumes
`Collie-Proxy-Authorization: DPoP …` and `DPoP`; ordinary `Authorization: Bearer …` remains Collie pairing.
Verify the pinned access token and the proof's public method/path, token hash, key, time, nonce and
durably consumed jti. Strip incoming trusted identity/device/provenance/Crew headers. Inject only
operator-mapped login and a current enrolled device. Enroll against the existing opaque paired-device
incarnation; never write the pairing registry. Collie verifies a private gateway MAC against its own
binding registry. The MAC binds the exact profile, original public native mount, canonical stripped
target, method, pairing-header hash, request correlation, current binding/revision and short time window. Its integer seconds effect lease can only shorten
verified access expiry and is bounded to 30 seconds. After awaited preflight, recheck that captured
lease, binding, paired incarnation, current role and ordinary gate immediately before each named
adapter effect or Crew forward; no OAuth credential or private stamp crosses Crew. Preserve only
the closed visible-pane read-intent header, never phone-provided identity or provenance.

The same HTTPS front door keeps browser authentication on a distinct browser mount. For the
supported two-mount profile, the authenticated browser route must **retain** the browser mount
when forwarding to Collie, strip all phone trust headers and inject its authenticated login/device.
Native gateway requests strip only their configured native mount and always need the MAC. Collie
requires the browser's configured trusted login plus its existing pairing/device/Origin gates;
native URLs cannot select this browser route through the gateway. A direct unauthenticated upstream
path is never a supported deployment. This is a concrete operator routing contract, not a claim
that arbitrary proxies or cookie-only gates support native tokens. See [deployment requirements](../docs/native-identity-gateway.md).

Reserve bounded proof/ingress replay state before dispatch, refuse a full registry and refuse stale
filesystem leases instead of guessing whether another process owns them. Registry files, temporary
siblings and leases remain inside owner-only state with no-follow, hardlink, size and Windows ACL
checks. Loading an absent profile writes nothing. HTTP bodies, active requests and request lifetime
are bounded; abort/retirement closes delivery and returns capacity without waiting for an arbitrary upstream.

Revocation atomically persists an exact typed binding-retired outbox event. The local Push consumer
removes only that enrollment before further content; it never acknowledges the offline signed-clear
obligation. B22 must commit signed retirement custody before ACK. Identity token expiry emits no
binding retirement and preserves pairing. Native gateway errors require the configured request's
correlation, owned marker and exact matching envelope/header factor; inconsistencies freeze actions.
Only authenticated Collie pairing provenance retires pairing. There is no automatic terminal replay.

## Consequences and acceptance

Unconfigured Tailscale access and browser pairing remain unchanged. Candidate health/pair admission
permits only the exact no-credential request and no gateway exemption. Pairing through an already
supported route must precede explicit operator mapping; fresh gateway-only first pairing remains
unsupported. No replacement connection lends bootstrap authority. An issuer must genuinely support
public-client DPoP, response `iss`, pinned ES256 JWT access tokens, PKCE and refresh binding. Opaque
access tokens and provider-only browser sessions require another implemented profile.

B23 dispatcher joining, B22 signed offline/connected clear joining, actual API26/current/physical
Keystore/browser callbacks, accessibility, Windows owner ACLs, disposable conforming issuer and
two-mount HTTPS local/Crew acceptance remain required. Generated-key fixtures establish source
contracts; they do not close live issuer, gateway or push acceptance.
