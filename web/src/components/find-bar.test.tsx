import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { FindBar } from "./find-bar";

const noop = () => {};

describe("FindBar counter", () => {
  it("reads 0/N while no match is selected", () => {
    // History starts with no current match; showing 1/N made its first Next look inert
    // (S25 Ultra walk, 2026-09-11).
    render(<FindBar query="fix" onQueryChange={noop} count={52} current={-1} onPrev={noop} onNext={noop} onClose={noop} />);
    expect(screen.getByText("0/52")).toBeTruthy();
  });

  it("reads the 1-based position once a match is selected", () => {
    render(<FindBar query="fix" onQueryChange={noop} count={52} current={1} onPrev={noop} onNext={noop} onClose={noop} />);
    expect(screen.getByText("2/52")).toBeTruthy();
  });
});
