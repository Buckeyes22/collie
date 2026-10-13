# 0104 — Host integration administration stays on a narrow owner port

Trail: [B30 H13 host-administration proposal](../android/acceptance/capability-work-items/B30.md), which rejects direct Android socket/SSH access and calls for scoped HTTP/Crew host operations. Reaches host-control routes, Herdr adapter, Crew dispatch and native Settings.

Amends [ADR 0096](./0096-control-authority-is-scoped-and-positive.md) in scope.

## Context

Integration installation changes host-local agent hooks and manifest refresh can fetch remote data. The existing native client is an HTTPS client, and a Crew lead must act through the owning member's Collie API. A mux capability or a server name alone cannot prove an administration method is available. Herdr's supported integration target enum also excludes Letta even though its CLI offers an experimental command.

## Decision

Keep integration administration behind a separate typed `HostControlPort` and a closed, host-scoped Collie HTTP route set. Negotiate each member's positive host-control capabilities independently; absent or incompatible declarations remain unsupported. Use only the exact typed Herdr integration and manifest methods. Keep experimental Letta as a distinct fixed-argument CLI adapter, never add it to the socket enum. Plan every mutation against current owner state, require explicit product confirmation and persist the request-bound outcome before returning. A timeout or lost response stays unknown and is never automatically repeated.

The native client and Crew carry only Collie routes and declared targets. They never open a Herdr socket, SSH connection, arbitrary shell or generic RPC channel. Manifest reload and remote fetch remain separate effects, so a cache reload does not imply network egress.

## Consequences

Herdr hosts with a matching 0.9.3+ protocol-22 handshake can expose integration status, install/removal and manifest workflows through the phone or Crew. Other muxes and older peers remain explicitly unsupported. A successful source fixture does not establish actual hook safety on every operating system; controlled isolated-home and Windows acceptance remains required.
