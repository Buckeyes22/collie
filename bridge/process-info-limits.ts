/** Shared process-inspection budgets at the adapter, HTTP and Crew boundaries. */
export const MAX_PROCESS_INFO_TEXT_CHARS = 220 * 1024;
/** Allows UTF-8 expansion of bounded text plus JSON framing while capping raw response buffers. */
export const MAX_PROCESS_INFO_BODY_BYTES = 1_000_000;
