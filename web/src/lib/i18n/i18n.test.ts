import {
  __resetLocale,
  getLocaleSnapshot,
  isLocale,
  setLocale,
  subscribeLocale,
  t,
  tn,
  whenLocaleReady,
} from "./index";

beforeEach(() => {
  localStorage.clear();
  __resetLocale();
});

describe("English message catalog", () => {
  it("accepts only the English locale", () => {
    expect(isLocale("en")).toBe(true);
    expect(isLocale("de")).toBe(false);
    expect(isLocale("")).toBe(false);
  });

  it("interpolates named values verbatim", () => {
    const device = "$& {device} $' $1 $$";
    expect(t("settings.devices.pairedAs", { device })).toBe(`This device is paired as ${device}.`);
  });

  it("uses English singular and plural forms", () => {
    expect(tn("space.overview.paneCount", 1)).toBe("1 pane");
    expect(tn("space.overview.paneCount", 2)).toBe("2 panes");
    expect(tn("space.overview.paneCount", 0)).toBe("0 panes");
  });

  it("is always ready and stamps the document language", async () => {
    await whenLocaleReady();
    setLocale("en");
    expect(getLocaleSnapshot().locale).toBe("en");
    expect(document.documentElement.lang).toBe("en");
  });

  it("removes obsolete saved locale choices", () => {
    localStorage.setItem("collie:locale:v1", "obsolete");
    __resetLocale();
    expect(localStorage.getItem("collie:locale:v1")).toBeNull();
  });

  it("notifies subscribers when the test store resets", () => {
    let ticks = 0;
    const unsubscribe = subscribeLocale(() => {
      ticks += 1;
    });
    __resetLocale();
    unsubscribe();
    expect(ticks).toBe(1);
  });
});
