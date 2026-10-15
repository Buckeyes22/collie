import { afterEach, describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { checkFile, type Failure } from "./check-doc-links.ts";

const roots: string[] = [];
function fixture(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "collie-doc-links-"));
  roots.push(root);
  for (const [name, content] of Object.entries(files)) {
    const file = join(root, name);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
  return root;
}
function failures(root: string): Failure[] {
  const result: Failure[] = [];
  checkFile("README.md", result, root);
  return result;
}
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }); });

describe("documentation links in a moved checkout", () => {
  test("maps the exact canonical repository root to this clone and validates its anchor", () => {
    const root = fixture({
      "README.md": "[canonical](/home/operator/git/collie/docs/moved-only.md#the-current-clone)\n[relative](docs/moved-only.md#the-current-clone)",
      "docs/moved-only.md": "# The current clone\n",
    });
    expect(failures(root)).toEqual([]);
  });

  test("accepts other bounded Linux account names without binding to one account", () => {
    const root = fixture({
      "README.md": "[historical](/home/_reader-2/git/collie/docs/moved-only.md#this-clone)",
      "docs/moved-only.md": "# This clone\n",
    });
    expect(failures(root)).toEqual([]);
  });

  test("does not remap invalid or overlong Linux account components", () => {
    const paths = [
      "/home/operator.name/git/collie/docs/moved-only.md",
      "/home/../git/collie/docs/moved-only.md",
      `/home/${"a".repeat(33)}/git/collie/docs/moved-only.md`,
    ];
    const root = fixture({
      "README.md": paths.map((path) => `[invalid](${path})`).join("\n"),
      "docs/moved-only.md": "# Present only in this clone\n",
    });
    expect(failures(root).map((failure) => failure.reason)).toEqual(paths.map(() => "missing file"));
  });

  test("never accepts a workstation original when this clone is missing its target", () => {
    const root = fixture({ "README.md": "[agreement](/home/operator/git/collie/CLAUDE.md)" });
    expect(failures(root)).toEqual([{
      file: "README.md", line: 1, link: "/home/operator/git/collie/CLAUDE.md", reason: "missing file",
    }]);
  });

  test("checks the current clone's headings rather than another clone's headings", () => {
    const root = fixture({
      "README.md": "[agreement](/home/operator/git/collie/CLAUDE.md#android-fork-scope)",
      "CLAUDE.md": "# A different heading\n",
    });
    expect(failures(root).map((failure) => failure.reason)).toEqual(["missing anchor"]);
  });

  test("does not remap a neighbouring repository prefix", () => {
    const root = fixture({
      "README.md": "[other](/home/operator/git/collie-not-this-repository/docs/moved-only.md)",
      "docs/moved-only.md": "# Present only in this clone\n",
    });
    expect(failures(root).map((failure) => failure.reason)).toEqual(["missing file"]);
  });

  test("refuses traversal out of the mapped checkout", () => {
    const root = fixture({ "README.md": "[escape](/home/operator/git/collie/../outside.md)" });
    expect(failures(root).map((failure) => failure.reason)).toEqual(["repository path escapes checkout"]);
  });

  test("continues to validate absolute paths outside the canonical repository", () => {
    const outside = fixture({ "present.md": "# External evidence\n" });
    const root = fixture({
      "README.md": `[present](${outside}/present.md#external-evidence)\n[missing](${outside}/missing.md)`,
    });
    expect(failures(root)).toEqual([{
      file: "README.md", line: 2, link: `${outside}/missing.md`, reason: "missing file",
    }]);
  });

  test("keeps missing relative files and anchors as failures", () => {
    const root = fixture({
      "README.md": "[missing](missing.md)\n[anchor](docs/page.md#missing-heading)",
      "docs/page.md": "# Actual heading\n",
    });
    expect(failures(root).map((failure) => failure.reason)).toEqual(["missing file", "missing anchor"]);
  });
});
