import { expect, test } from "bun:test";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const dir = resolve(import.meta.dir, "../.github/workflows");

test("every action is pinned to a full commit SHA with its version as a comment", () => {
  const loose = readdirSync(dir).filter((f) => f.endsWith(".yml")).flatMap((f) =>
    readFileSync(resolve(dir, f), "utf8").split("\n")
      .map((line, i) => ({ where: `${f}:${i + 1}`, line: line.trim() }))
      .filter(({ line }) => /^(- )?uses: /.test(line) && !/@[0-9a-f]{40} # v\S+$/.test(line)),
  );
  expect(loose).toEqual([]);
});

test("every grype-version is a release tag with the leading v", () => {
  const bad = readdirSync(dir).filter((f) => f.endsWith(".yml")).flatMap((f) =>
    readFileSync(resolve(dir, f), "utf8").split("\n")
      .map((line, i) => ({ where: `${f}:${i + 1}`, line: line.trim() }))
      .filter(({ line }) => line.startsWith("grype-version:") && !/^grype-version: "v\d+\.\d+\.\d+"$/.test(line)),
  );
  expect(bad).toEqual([]);
});
