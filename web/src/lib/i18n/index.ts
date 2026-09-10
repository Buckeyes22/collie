import { hasDocument } from "../env";
import { en, type MessageKey } from "./messages/en";

/** Collie ships one English message catalog. */
export type Locale = "en";
export const DEFAULT_LOCALE: Locale = "en";
export type { MessageKey, Messages, Dictionary } from "./messages/en";

export interface TemplateVars {
  readonly [slot: string]: string | number;
}

type PluralBaseOf<K> = K extends `${infer Base}.one`
  ? `${Base}.other` extends MessageKey
    ? Base
    : never
  : never;
export type PluralKey = PluralBaseOf<MessageKey>;

export interface LocaleState {
  readonly locale: Locale;
  readonly revision: number;
}

const STORAGE_KEY = "collie:locale:v1";
let state: LocaleState = { locale: DEFAULT_LOCALE, revision: 0 };
const listeners = new Set<() => void>();

function storage(): Storage | null {
  return globalThis.localStorage ?? null;
}

function enforceEnglish(): void {
  try {
    storage()?.removeItem(STORAGE_KEY);
  } catch {
    // Storage can be unavailable in private browsing; the in-memory catalog is still English.
  }
  if (hasDocument()) document.documentElement.lang = DEFAULT_LOCALE;
}

function interpolate(template: string, vars: TemplateVars | undefined): string {
  if (vars === undefined) return template;
  let out = template;
  for (const [slot, value] of Object.entries(vars)) {
    out = out.split(`{${slot}}`).join(String(value));
  }
  return out;
}

export function t(key: MessageKey, vars?: TemplateVars): string {
  return interpolate(en[key], vars);
}

export function tn(keyBase: PluralKey, count: number, vars?: TemplateVars): string {
  const suffix = count === 1 ? "one" : "other";
  const key: MessageKey = `${keyBase}.${suffix}`;
  return interpolate(en[key], { ...vars, count });
}

export function isLocale(value: string): value is Locale {
  return value === DEFAULT_LOCALE;
}

export function setLocale(_locale: Locale): void {
  enforceEnglish();
}

export function subscribeLocale(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getLocaleSnapshot(): LocaleState {
  return state;
}

export function whenLocaleReady(_locale: Locale = DEFAULT_LOCALE): Promise<void> {
  return Promise.resolve();
}

export function __resetLocale(): void {
  state = { locale: DEFAULT_LOCALE, revision: state.revision + 1 };
  enforceEnglish();
  for (const listener of listeners) listener();
}

enforceEnglish();
