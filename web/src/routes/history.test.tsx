import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeAll, vi } from "vitest";

import { ROOT_ROUTE_ID, type HistoryData, type HomeData } from "@/lib/loaders";
import { fixtureAgents, fixtureTabs, fixtureWorkspaces } from "@/test/handlers";
import type { TranscriptEntry } from "@/lib/types";
import { withHeaderHost } from "@/test/header-host";
import { HistoryRoute } from "./history";

vi.mock("@/hooks/use-loading-stalled", () => ({ useLoadingStalled: () => false }));

// jsdom has no scrolling; History pins its list to the bottom on mount.
beforeAll(() => {
  if (!Element.prototype.scrollTo) Element.prototype.scrollTo = () => {};
  if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => {};
});

const agent = fixtureAgents[0]!;

const rootData: HomeData = {
  bridge: "connected",
  device: undefined,
  agents: fixtureAgents,
  shellPanes: [],
  workspaces: fixtureWorkspaces,
  tabs: fixtureTabs,
  sessions: [],
  servers: [],
  ts: 0,
  scope: {},
  viewAll: false,
  snoozedUntil: null,
  update: undefined,
  error: false,
  authError: false,
};

const entry = (uuid: string, text: string): TranscriptEntry => ({
  uuid,
  ts: "2026-09-11T00:00:00Z",
  role: "assistant",
  parts: [{ kind: "text", text }],
});

const history: HistoryData = {
  paneId: agent.paneId,
  scope: {},
  entries: [entry("a", "first needle"), entry("b", "second needle"), entry("c", "third needle")],
  hasMore: false,
  total: 3,
  fileTruncated: false,
};

function renderHistory() {
  const router = createMemoryRouter(
    [
      {
        id: ROOT_ROUTE_ID,
        path: "/",
        loader: () => rootData,
        children: [{ path: "pane/:paneId/history", loader: () => history, element: withHeaderHost(<HistoryRoute />) }],
      },
    ],
    { initialEntries: [`/pane/${encodeURIComponent(agent.paneId)}/history`] },
  );
  return render(<RouterProvider router={router} />);
}

describe("HistoryRoute find counter", () => {
  it("reads 0/N until a match is selected, then counts from 1", async () => {
    // The counter said 1/N before any match was current, so the first Next looked inert
    // (S25 Ultra walk, 2026-09-11; the native History had the same defect).
    const user = userEvent.setup();
    renderHistory();
    await user.click(await screen.findByRole("button", { name: "Find in history" }));
    await user.type(screen.getByRole("textbox"), "needle");
    expect(await screen.findByText("0/3")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Next match" }));
    expect(await screen.findByText("1/3")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Next match" }));
    expect(await screen.findByText("2/3")).toBeTruthy();
  });
});
