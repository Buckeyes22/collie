import { expect, test } from "bun:test";
import { hostControlConfig } from "./registry.ts";
import { herdrHostControl } from "./herdr.ts";
import { HOST_CAPABILITIES } from "./types.ts";

test("host administration has a typed unsupported outcome independent of mux support", async () => {
  const wire = hostControlConfig(herdrHostControl);
  expect(wire.provider).toBe("herdr");
  for (const capability of HOST_CAPABILITIES) {
    expect(wire.capabilities[capability]).toBe(false);
    expect((await herdrHostControl.inspect({ capability })).kind).toBe("unsupported");
  }
  expect(hostControlConfig().provider).toBe("none");
});
