# Native identity gateway

This opt-in profile adds native identity authentication to Variant C/B deployments whose browser
SSO cannot authenticate native HTTP. Direct native Tailscale access remains the default. Source
implementation exists; independent review, native gates and actual issuer/device/Crew/Windows and
signed push retirement acceptance are pending. Do not infer runtime support from this document.

The issuer must support public authorization-code clients, PKCE S256, `openid offline_access`,
the registered callback `com.lateapex.collie:/oauth2redirect`, authorization response `iss`, ES256
DPoP on code and refresh requests, `token_type=DPoP`, signed ES256 JWT access tokens with exact
issuer/audience/client_id/subject/exp/iat and `cnf.jkt`, and signed OIDC ID tokens with nonce. Discovery
must explicitly declare PKCE S256, DPoP ES256 and response `iss`; declared endpoint URLs and fetched
JWKS must match the operator pins. The supported audience is one exact string. All endpoints are
HTTPS on the declared issuer origin. No opaque tokens, client secret, cookies or arbitrary issuer guessing.

If the issuer requires an API scope to issue that audience, declare it in the profile's optional
`scopes` array, for example `"scopes": ["collie.native"]`. Collie always requests `openid offline_access`
and appends only those operator-declared scopes; the `audience` parameter alone does not select an
API resource on every issuer. Additional scopes are unique RFC 6749 ASCII scope tokens, at most eight
of 128 characters each, with the complete space-separated request limited to 512 characters.
Do not repeat `openid` or `offline_access` in this array. The native import review displays the complete
requested scope list. Profiles without the array preserve their existing request. API scopes do not
relax the required signed token audience, issuer, client or proof-key validation.

Copy the issuer identifier exactly from trusted discovery, including whether its root URL ends in
`/`. Both forms are valid; Collie preserves that literal for discovery, callback, signed-token
and device-binding comparisons. Other profile endpoints retain their canonical URL checks.

## One front door, two authenticated mounts

Use one operator-managed HTTPS listener, for example `https://collie.example`, with these routes:

| Public route | Authentication | Local target and path contract |
| --- | --- | --- |
| `/browser/` | Existing browser SSO, trusted identity/device header injection and Collie pairing | Collie loopback port; **retain** `/browser/` in the forwarded path |
| `/native/api/…` | Native DPoP gateway replaces cookie authentication on this mount | Gateway loopback port; retain `/native/api/…` to the gateway; gateway alone strips `/native/` and signs `/api/…` to Collie |

Set `COLLIE_SKIP_SERVE=1`, `COLLIE_BASE_PATH=/browser/`, `COLLIE_TRUSTED_USER=operator@example`,
`COLLIE_DEVICE_HEADER=X-Device`, the appropriate existing device allowlist and allowed HTTPS origin.
Keep Collie and gateway loopback-only. The browser route must strip incoming trusted login/device,
`Forwarded`/`X-Forwarded-*`, native/provenance `X-Collie-*` and Crew headers before injecting its authenticated values. Preserve only the existing `X-Collie-Seen: 1` read intent on pane/history/chat GETs; it grants no authentication or write authority.
Browser identity/device/Origin/pairing gates remain active; a native login does not widen them.
No unauthenticated public path may reach Collie directly. A cookie gate rejecting `/native/api/`
before the gateway is incompatible; explicitly route that mount to the DPoP gateway instead.
The native mount must be distinct from the non-root browser mount and match the imported native
connection base URL. Bind proof `htu` to the original public native path, excluding query/fragment.
Clients cannot choose public authority from Forwarded headers or callback URLs.

## Operator configuration and enrollment

Create an operator JSON file containing the public issuer profile and local routing configuration:

```json
{
  "schema": 1,
  "port": 8788,
  "upstream": "http://127.0.0.1:8787/",
  "publicBase": "https://collie.example/native/",
  "deviceHeader": "X-Device",
  "mappedLogin": "operator@example",
  "profile": {
    "schema": 1,
    "issuer": "https://issuer.example/",
    "clientId": "collie-native",
    "audience": "collie-gateway",
    "redirectUri": "com.lateapex.collie:/oauth2redirect",
    "authorizationEndpoint": "https://issuer.example/authorize",
    "tokenEndpoint": "https://issuer.example/token",
    "jwksEndpoint": "https://issuer.example/jwks",
    "gatewayOrigin": "https://collie.example/",
    "algorithms": ["ES256"],
    "jwks": [{"kty":"EC","crv":"P-256","kid":"operator-key-id","x":"REPLACE_WITH_ACTUAL_PUBLIC_COORDINATE","y":"REPLACE_WITH_ACTUAL_PUBLIC_COORDINATE"}]
  }
}
```

Those placeholder coordinates are deliberately invalid: obtain genuine public issuer pins through
your supported operator route. Run `collie native-access configure /absolute/operator-profile.json`
against the intended instance, then restart that Collie and run `collie native-access serve` under
your existing operator process supervision. The command creates private ingress/nonce keys in
the owner-only state directory, never prints them, and does not configure or deploy a front door.
Protect the state directory on Windows; refusing an unverifiable ACL is intentional. A crashed
writer's `.lock` lease fails closed and requires the operator to establish that no writer remains
before removing only that stale lease. Do not remove binding or replay registries to retry an effect.

`collie native-access profile` exports only public metadata. In Android, use the native sign-in
screen to review/import that JSON for the exact configured HTTPS origin. Copy the **public device
key** from that screen. An operator uses the known issuer subject and already approved device label:

```text
collie native-access enroll https://issuer.example/ SUBJECT PUBLIC_JKT EXISTING_PAIRED_DEVICE_LABEL
collie native-access list
collie native-access revoke BINDING_ID
```

Pair the phone through an already supported authenticated/direct native route **before** explicit
operator enrollment. The configured gateway authenticates every request, including health and pair;
a fresh gateway-only phone cannot bootstrap enrollment and that first-pairing workflow is not
supported. Android's narrowly tagged candidate health/pair requests still reach that gateway once
and show its typed refusal; they never borrow a different saved connection's pairing or identity.
There is no public unauthenticated gateway exemption or implicit pre-pair registry entry.

Enrollment verifies the existing paired-device incarnation without approving/re-pairing a device.
The app opens the external browser through ACTION_VIEW; callback state is expiring, one-shot and
credential/process-bound. Identity tokens use separate encrypted Keystore custody; pairing never
reaches the issuer. Obtain a gateway nonce with a read-only health probe before a protected write.
The original admitted origin, paired incarnation, host/session, nullable subject and navigation
generation stay with each request across credential refresh and nonce waits. Retired work does not
send a terminal mutation or adopt a replacement connection. Refusals do not transparently resend terminal mutations. After sign-in, refresh Collie to establish
current snapshot-derived write authority; explicit saved copies never authorize writes.

Stopping the sign-in screen clears its profile editor and confirmation copy, dismisses the dialog
and cancels in-flight presentation work. Completed authority retirement also waits for that main
thread wipe. An intentionally opened external browser may still return to its encrypted one-shot
transaction on a newly started screen; stopping does not grant an old dialog permission to import.
Conditional encrypted publication and retirement use one store revision, including after callback
state has already been consumed. No network or view acknowledgement runs under that custody lock.

## Revocation and evidence boundary

Token expiry requests sign-in/refresh and preserves pairing and push enrollment. The native
binding witness belongs to the exact origin, paired identity, issuer subject and proof key; it
survives same-identity refresh and reauthentication independently of transient access tokens.
Authenticated exact replacement/revocation writes an encrypted retirement outbox together with the
custody change. Observation does not acknowledge it; only an exact durable B22 consumer ACK may
remove that event.

The private ingress MAC carries an integer seconds effect lease, bounded to 30 seconds and no
later than the verified access token's expiry. After request-body, prompt or route preflight awaits,
Collie checks the captured binding/revision, pairing incarnation, device gate, lead role and this
lease immediately before the named adapter effect or Crew forward. It never substitutes a new map.
This final check does not make vendor RPC compare-and-send atomic; a lost or partial effect remains
uncertain and must not be retried automatically. Device-map
revocation blocks future content, removes only exact local registration metadata and emits a
durable typed retirement event. B22's signed offline clear consumer must commit custody before
acknowledging that event; it remains a joint open integration requirement. Connected native clients
receive the exact binding retirement signal after an authenticated gateway refusal. Already delivered
offline content cannot be guaranteed remotely erased. Direct enrollments keep their pairing lifecycle.

| Required acceptance | Current disposition |
| --- | --- |
| Crypto/parser/replay/mount/AND-factor and actual central route fixtures | Controlled source fixtures; see the B24 handoff for exact current results |
| Native PKCE/DPoP/refresh/callback/provenance fixtures and five Gradle gates | Authored, unrun by the author; ROOT owns compilation |
| Disposable genuine issuer → conforming two-mount HTTPS front door → gateway → Collie → native login/read/Files/write | Pending ROOT-controlled runtime acceptance |
| Solo and three-node Crew, read-only/unsupported peer, lifecycle/SavedCopy/replacement | Pending actual joint acceptance; OAuth credentials must never cross Crew |
| API26/current/physical external browser/Keystore, 200% text and TalkBack | Pending designated device acceptance |
| Windows actual owner ACL and cross-process registry semantics | Pending actual Windows acceptance |
| B23 unified callback and B22 signed connected/offline retirement | Joint OPEN until serial integration and whole review |

The protocol sources are [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252),
[RFC 7636](https://www.rfc-editor.org/rfc/rfc7636), [OIDC Core](https://openid.net/specs/openid-connect-core-1_0.html),
[OIDC Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html),
[RFC 9449](https://www.rfc-editor.org/rfc/rfc9449), [RFC 9207](https://www.rfc-editor.org/rfc/rfc9207),
[RFC 9068](https://www.rfc-editor.org/rfc/rfc9068), [RFC 7638](https://www.rfc-editor.org/rfc/rfc7638)
and [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515). The Collie header separation and private
ingress MAC are explicit adapter extensions, not standard proxy or OIDC claims.
