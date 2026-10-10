import type { HostControlPort } from "./types.ts";
/** Schema availability alone is not verified runtime support. Feature owners supply tested handlers. */
export const herdrHostControl: HostControlPort = {
  provider: "herdr",
  capabilities: { integrations: false, plugins: false, configuration: false, persistence: false, machines: false, serverAdministration: false },
  notes: { integrations: "Requires a verified host integration handler.", plugins: "Requires a verified host plugin handler.", configuration: "Requires a verified host configuration handler.", persistence: "Requires a verified host persistence handler.", machines: "Requires a verified host management handler.", serverAdministration: "Requires a verified server administration handler." },
  inspect: async (request) => ({ kind: "unsupported", capability: request.capability, reason: "This server has no verified handler for this host administration feature." }),
};
