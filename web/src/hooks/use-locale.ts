import { useSyncExternalStore } from "react";

import { getLocaleSnapshot, setLocale, subscribeLocale, type Locale } from "@/lib/i18n";

// Compatibility hook for components that read the typed English message catalog. The snapshot is
// stable in production because Collie has no locale selector; the subscription remains useful to
// tests that reset the catalog singleton between cases.

export interface UseLocaleReturn {
  locale: Locale;
  /**
   * Bumped on every change that alters rendered text, INCLUDING a lazy dictionary arriving.
   *
   * A component that only calls `t()` never needs this — re-rendering is enough. A component that
   * MEMOISES a derived string does: `locale` alone is the wrong dependency, because choosing German
   * changes it once, paints English (the bundle has not landed), and never changes again when the
   * bundle does. The revision moves on both events, so it is the only honest key for a memo whose
   * value came out of `t()`.
   */
  revision: number;
  setLocale: (locale: Locale) => void;
}

export function useLocale(): UseLocaleReturn {
  const snapshot = useSyncExternalStore(subscribeLocale, getLocaleSnapshot, getLocaleSnapshot);
  return { locale: snapshot.locale, revision: snapshot.revision, setLocale };
}
