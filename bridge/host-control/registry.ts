import { HOST_CAPABILITIES, type HostControlPort } from "./types.ts";
/** No provider/verified handler means no administration authority, even on a known mux. */
export function hostControlConfig(port?: HostControlPort) {
  return {
    provider: port?.provider ?? "none",
    capabilities: Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, port?.capabilities[name] === true])),
    notes: port?.notes ?? Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, "Host administration is unavailable on this server."])),
  };
}
