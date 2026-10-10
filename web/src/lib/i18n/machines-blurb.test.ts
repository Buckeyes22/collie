import { en } from "./messages/en";

describe("the Machines row in Settings", () => {
  it("names the disk, as the page description does", () => {
    expect(en["machines.entry.description"]).toContain("disk");
    expect(en["settings.section.machines.blurb"]).toContain("disk");
  });
});
