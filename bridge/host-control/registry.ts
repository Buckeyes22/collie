import { HOST_CAPABILITIES, type HostControlPort } from "./types.ts";
/** No provider/verified handler means no administration authority, even on a known mux. */
export function hostControlConfig(port?: HostControlPort, advertised?: Readonly<Record<string, boolean>>) {
  const capabilities = Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, advertised?.[name] === true || port?.capabilities[name] === true]));
  const hasProvider = Object.values(capabilities).some(Boolean);
  return {
    provider: port?.provider ?? (hasProvider ? "herdr" : "none"),
    capabilities,
    notes: port?.notes ?? Object.fromEntries(HOST_CAPABILITIES.map((name) => [name, hasProvider ? "Available on the selected host's verified Herdr runtime." : "Host administration is unavailable on this server."])),
  };
}
