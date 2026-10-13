import { expect, test } from "bun:test";
import { generateKeyPairSync } from "node:crypto";
import { parseProfile, NATIVE_REDIRECT } from "./contracts.ts";
import { strictJson } from "./jose.ts";

const issuer = generateKeyPairSync("ec", { namedCurve: "P-256" });
const document = {
  schema: 1, issuer: "https://issuer.example/", clientId: "native", audience: "gateway",
  redirectUri: NATIVE_REDIRECT, authorizationEndpoint: "https://issuer.example/authorize",
  tokenEndpoint: "https://issuer.example/token", jwksEndpoint: "https://issuer.example/jwks",
  gatewayOrigin: "https://collie.example/", algorithms: ["ES256"],
  jwks: [{ ...issuer.publicKey.export({ format: "jwk" }), kid: "issuer" }],
};

test("public profile retains explicit API scopes without changing legacy exports or audience", () => {
  expect<unknown>(parseProfile(strictJson(JSON.stringify(document)))).toEqual(document);
  const configured = { ...document, scopes: ["collie.native", "Resource:Read"] };
  const profile = parseProfile(strictJson(JSON.stringify(configured)));
  expect<unknown>(profile).toEqual(configured);
  expect(profile.audience).toBe("gateway");
  expect(profile.scopes).toEqual(["collie.native", "Resource:Read"]);
});

test("scope tokens follow RFC6749 ASCII grammar and bounded closed operator configuration", () => {
  for (const scopes of [null, "collie.native", [], [1], [{}], [""], ["openid"], ["offline_access"],
    ["collie.native", "collie.native"], ["two scopes"], ["tab\tscope"], ["line\nscope"],
    ['quote"scope'], ["back\\slash"], ["unicodeé"], ["\u007f"], ["a".repeat(129)],
    Array.from({ length: 9 }, (_, i) => `scope${i}`),
    Array.from({ length: 8 }, (_, i) => `${i}${"a".repeat(60)}`)]) {
    expect(() => parseProfile(strictJson(JSON.stringify({ ...document, scopes })))).toThrow();
  }
  for (const scopes of [["!#[]~"], ["a".repeat(128)], ["case", "Case"],
    Array.from({ length: 8 }, (_, i) => `${i}${"a".repeat(59)}`)]) {
    expect(parseProfile(strictJson(JSON.stringify({ ...document, scopes }))).scopes).toEqual(scopes);
  }
  expect(() => parseProfile(strictJson(JSON.stringify({ ...document, scope: "collie.native" })))).toThrow();
  expect(() => parseProfile(strictJson(JSON.stringify({ ...document, scopes: ["collie.native"], tokenEndpoint: "https://foreign.example/token" })))).toThrow();
});
