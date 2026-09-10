import { useSyncExternalStore } from "react";

import { getLocaleSnapshot, setLocale, subscribeLocale, type Locale } from "@/lib/i18n";

// Compatibility hook for components that read the typed English message catalog. The snapshot is
// stable in production because Collie has no locale selector; the subscription remains useful to
// tests that reset the catalog singleton between cases.

export interface UseLocaleReturn {
  locale: Locale;
  setLocale: (locale: Locale) => void;
}

export function useLocale(): UseLocaleReturn {
  const snapshot = useSyncExternalStore(subscribeLocale, getLocaleSnapshot, getLocaleSnapshot);
  return { locale: snapshot.locale, setLocale };
}
