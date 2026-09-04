import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, join, win32 } from "node:path";

const EXPECTED_PACKAGE_ID = "com.lateapex.collie";
const EXPECTED_HOST = "ed8.taile7b6b1.ts.net";
const EXPECTED_ORIGIN = `https://${EXPECTED_HOST}`;
const EXPECTED_PATH = "/";
const EXPECTED_APP_VERSION = "1.5.0";
const EXPECTED_APP_VERSION_CODE = 1;
const REQUIRED_API_LEVEL = 36;
const HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls";
const ALLOWED_PERMISSIONS = new Set([
  "android.permission.INTERNET",
  "android.permission.POST_NOTIFICATIONS",
  `${EXPECTED_PACKAGE_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
]);
const SHA256_FINGERPRINT = /^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$/;
const PLACEHOLDER = /(?:PLACEHOLDER|REPLACE|TODO|YOUR[_ -]|<[^>]+>)/i;

type JsonPrimitive = boolean | number | string | null;
type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
type JsonObject = { [key: string]: JsonValue };

export interface AndroidSourceFile {
  path: string;
  text: string;
}

export interface AndroidTwaValidationInput {
  twaManifestText: string;
  androidManifestText: string;
  androidStringsText: string;
  mergedAndroidManifestText?: string;
  gradleFiles: readonly AndroidSourceFile[];
  assetLinksSourceText?: string;
  assetLinksBuiltText?: string;
  webDistExists?: boolean;
  requireAssetLinks?: boolean;
}

export interface AndroidTwaValidationResult {
  errors: string[];
  checkedAssetLinks: boolean;
}

interface ParsedJson {
  ok: boolean;
  value: JsonValue;
}

function parseJson(text: string, label: string, errors: string[]): ParsedJson {
  try {
    // SAFETY: JSON.parse can only produce values represented by JsonValue; all fields are narrowed
    // by the helpers below before validation reads them.
    const value = JSON.parse(text) as JsonValue;
    return { ok: true, value };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    errors.push(`${label} is malformed JSON: ${detail}`);
    return { ok: false, value: null };
  }
}

function isObject(value: JsonValue | undefined): value is JsonObject {
  return value !== undefined && Object.prototype.toString.call(value) === "[object Object]";
}

function stringValue(value: JsonValue | undefined): string | null {
  return Object.prototype.toString.call(value) === "[object String]" ? String(value) : null;
}

function stringArray(value: JsonValue | undefined): string[] | null {
  if (!Array.isArray(value)) return null;
  const strings = value.map(stringValue);
  return strings.every((entry) => entry !== null) ? strings : null;
}

function fieldError(errors: string[], field: string, expected: string, actual: JsonValue | undefined): void {
  errors.push(
    `android/twa-manifest.json ${field} must be ${expected}; found ${JSON.stringify(actual)}`,
  );
}

function validateTwaManifest(text: string, errors: string[]): void {
  const parsed = parseJson(text, "android/twa-manifest.json", errors);
  if (!parsed.ok) return;
  if (!isObject(parsed.value)) {
    errors.push("android/twa-manifest.json must contain a JSON object");
    return;
  }

  const manifest = parsed.value;
  if (stringValue(manifest.packageId) !== EXPECTED_PACKAGE_ID) {
    fieldError(errors, "packageId", JSON.stringify(EXPECTED_PACKAGE_ID), manifest.packageId);
  }
  if (stringValue(manifest.host) !== EXPECTED_HOST) {
    fieldError(errors, "host", JSON.stringify(EXPECTED_HOST), manifest.host);
  }

  const startUrl = stringValue(manifest.startUrl);
  if (startUrl !== EXPECTED_PATH && startUrl !== `${EXPECTED_ORIGIN}/`) {
    fieldError(errors, "startUrl", '"/" (or its absolute URL)', manifest.startUrl);
  }

  const scope = stringValue(manifest.scope);
  const fullScopeUrl = stringValue(manifest.fullScopeUrl);
  if (scope === null && fullScopeUrl === null) {
    errors.push(
      `android/twa-manifest.json must declare scope "/" or fullScopeUrl "${EXPECTED_ORIGIN}/"`,
    );
  }
  if (scope !== null && scope !== EXPECTED_PATH && scope !== `${EXPECTED_ORIGIN}/`) {
    fieldError(errors, "scope", '"/" (or its absolute URL)', manifest.scope);
  }
  if (fullScopeUrl !== null && fullScopeUrl !== `${EXPECTED_ORIGIN}/`) {
    fieldError(errors, "fullScopeUrl", `"${EXPECTED_ORIGIN}/"`, manifest.fullScopeUrl);
  }

  if (manifest.enableNotifications !== true) {
    fieldError(errors, "enableNotifications", "true", manifest.enableNotifications);
  }
  if (stringValue(manifest.fallbackType) !== "customtabs") {
    fieldError(errors, "fallbackType", '"customtabs"', manifest.fallbackType);
  }

  const appVersion = manifest.appVersion;
  const appVersionName = manifest.appVersionName;
  if (appVersion === undefined && appVersionName === undefined) {
    errors.push(`android/twa-manifest.json must declare appVersion or appVersionName as "${EXPECTED_APP_VERSION}"`);
  }
  if (appVersion !== undefined && stringValue(appVersion) !== EXPECTED_APP_VERSION) {
    fieldError(errors, "appVersion", JSON.stringify(EXPECTED_APP_VERSION), appVersion);
  }
  if (appVersionName !== undefined && stringValue(appVersionName) !== EXPECTED_APP_VERSION) {
    fieldError(errors, "appVersionName", JSON.stringify(EXPECTED_APP_VERSION), appVersionName);
  }
  if (manifest.appVersionCode !== EXPECTED_APP_VERSION_CODE) {
    fieldError(errors, "appVersionCode", String(EXPECTED_APP_VERSION_CODE), manifest.appVersionCode);
  }

  if (manifest.enableAndroidBackup === true || manifest.allowBackup === true) {
    errors.push("android/twa-manifest.json must disable Android backup");
  }

  const signingKey = manifest.signingKey;
  if (signingKey === undefined) {
    errors.push("android/twa-manifest.json must configure signingKey with an inert, ignored relative schema path");
  } else if (!isObject(signingKey)) {
    errors.push("android/twa-manifest.json signingKey must be an object");
  } else {
    const signingPath = stringValue(signingKey.path);
    if (signingPath === null || signingPath.length === 0) {
      errors.push("android/twa-manifest.json signingKey.path must name an inert, ignored relative schema path");
    } else if (
      isAbsolute(signingPath) ||
      win32.isAbsolute(signingPath) ||
      signingPath.startsWith("~") ||
      signingPath.split(/[\\/]/).includes("..") ||
      /^[a-z][a-z0-9+.-]*:/i.test(signingPath) ||
      PLACEHOLDER.test(signingPath)
    ) {
      errors.push(
        "android/twa-manifest.json signingKey.path must be an inert, ignored relative schema path; " +
          `found ${JSON.stringify(signingPath)}`,
      );
    }
  }

  const additionalOrigins = manifest.additionalTrustedOrigins;
  if (additionalOrigins !== undefined && (!Array.isArray(additionalOrigins) || additionalOrigins.length !== 0)) {
    errors.push("android/twa-manifest.json additionalTrustedOrigins must remain empty");
  }
}

function validateAndroidManifest(text: string, label: string, errors: string[]): void {
  const backup = text.match(/\bandroid:allowBackup\s*=\s*["']([^"']+)["']/)?.[1];
  if (backup !== "false") {
    errors.push(
      `${label} must declare android:allowBackup="false"; found ${JSON.stringify(backup)}`,
    );
  }
  if (/\b(?:WebViewFallbackActivity|android\.webkit\.WebView)\b/.test(text)) {
    errors.push(`${label} must not register a WebView or WebView fallback activity`);
  }

  const cleartext = text.match(/\bandroid:usesCleartextTraffic\s*=\s*["']([^"']+)["']/)?.[1];
  if (cleartext !== "false") {
    errors.push(
      `${label} must declare android:usesCleartextTraffic="false"; found ${JSON.stringify(cleartext)}`,
    );
  }

  const permissionPattern = /<uses-permission\b[^>]*\bandroid:name\s*=\s*["']([^"']+)["'][^>]*>/g;
  const permissions = Array.from(text.matchAll(permissionPattern), (match) => match[1] ?? "");
  if (!permissions.includes("android.permission.POST_NOTIFICATIONS")) {
    errors.push(`${label} must request android.permission.POST_NOTIFICATIONS`);
  }
  for (const permission of permissions) {
    if (!ALLOWED_PERMISSIONS.has(permission)) {
      errors.push(`${label} requests unexpected permission ${JSON.stringify(permission)}`);
    }
  }

  const componentPattern = /<(activity|provider|receiver|service)\b([^>]*)>/g;
  let launcherExported = false;
  let delegationExported = false;
  for (const match of text.matchAll(componentPattern)) {
    const kind = match[1] ?? "component";
    const attributes = match[2] ?? "";
    const name = attributes.match(/\bandroid:name\s*=\s*["']([^"']+)["']/)?.[1] ?? "unnamed";
    const exported = attributes.match(/\bandroid:exported\s*=\s*["']([^"']+)["']/)?.[1];
    const enabled = attributes.match(/\bandroid:enabled\s*=\s*["']([^"']+)["']/)?.[1];
    const launcherExport =
      kind === "activity" &&
      [".LauncherActivity", "LauncherActivity", `${EXPECTED_PACKAGE_ID}.LauncherActivity`].includes(name) &&
      exported === "true";
    const delegationExport =
      kind === "service" &&
      name.endsWith("DelegationService") &&
      exported === "@bool/enableNotification" &&
      enabled === "@bool/enableNotification";
    launcherExported ||= launcherExport;
    delegationExported ||= delegationExport;
    if (
      exported !== undefined &&
      exported !== "false" &&
      !launcherExport &&
      !delegationExport
    ) {
      errors.push(
        `${label} ${kind} ${JSON.stringify(name)} has unexpected exported value ` +
          JSON.stringify(exported),
      );
    }
  }
  if (!launcherExported) {
    errors.push(`${label} must export only the Collie LauncherActivity for verified link launches`);
  }
  if (!delegationExported) {
    errors.push(
      `${label} must enable and export DelegationService through @bool/enableNotification`,
    );
  }
  if (!text.includes("android.support.customtabs.trusted.TRUSTED_WEB_ACTIVITY_SERVICE")) {
    errors.push(`${label} DelegationService must handle TRUSTED_WEB_ACTIVITY_SERVICE`);
  }
  const smallIcon =
    /<meta-data\b[^>]*android:name\s*=\s*["']android\.support\.customtabs\.trusted\.SMALL_ICON["'][^>]*android:resource\s*=\s*["']@drawable\/ic_notification_icon["'][^>]*>/.test(
      text,
    );
  if (!smallIcon) {
    errors.push(`${label} DelegationService must declare @drawable/ic_notification_icon as SMALL_ICON`);
  }
  if (!/\bandroid:autoVerify\s*=\s*["']true["']/.test(text)) {
    errors.push(`${label} LauncherActivity must retain its autoVerify HTTPS intent filter`);
  }
}

function validateAndroidStrings(text: string, errors: string[]): void {
  if (!/<string\b[^>]*\bname\s*=\s*["']assetStatements["']/.test(text)) {
    errors.push("android/app/src/main/res/values/strings.xml must declare assetStatements");
    return;
  }
  const sites = Array.from(
    text.matchAll(/\\?["']site\\?["']\s*:\s*\\?["'](https:\/\/[^"'\\]+)\\?["']/g),
    (match) => match[1] ?? "",
  );
  if (sites.length === 0 || sites.some((site) => site !== EXPECTED_ORIGIN)) {
    errors.push(
      `Android assetStatements must name only ${EXPECTED_ORIGIN}; found ` +
        (sites.length === 0 ? "no site" : sites.join(", ")),
    );
  }
}

function numericGradleValues(text: string, key: "compileSdk" | "targetSdk"): number[] {
  const expression = new RegExp(`\\b${key}(?:Version)?\\s*(?:=\\s*)?(\\d+)\\b`, "g");
  return Array.from(text.matchAll(expression), (match) => Number(match[1]));
}

function applicationIds(text: string): string[] {
  const expression = /\bapplicationId\s*(?:=\s*)?["']([^"']+)["']/g;
  return Array.from(text.matchAll(expression), (match) => match[1] ?? "");
}

function gradleVersionNames(text: string): string[] {
  const expression = /\bversionName\s*(?:=\s*)?["']([^"']+)["']/g;
  return Array.from(text.matchAll(expression), (match) => match[1] ?? "");
}

function gradleVersionCodes(text: string): number[] {
  const expression = /\bversionCode\s*(?:=\s*)?(\d+)\b/g;
  return Array.from(text.matchAll(expression), (match) => Number(match[1]));
}

function validateGradle(files: readonly AndroidSourceFile[], errors: string[]): void {
  if (files.length === 0) {
    errors.push("no Android Gradle build file was found; expected android/app/build.gradle or build.gradle.kts");
    return;
  }

  const sdkText = files.map((file) => file.text).join("\n");
  const compileLevels = numericGradleValues(sdkText, "compileSdk");
  const targetLevels = numericGradleValues(sdkText, "targetSdk");
  const hostNames = Array.from(sdkText.matchAll(/\bhostName\s*:\s*["']([^"']+)["']/g), (match) => match[1] ?? "");
  const launchUrls = Array.from(sdkText.matchAll(/\blaunchUrl\s*:\s*["']([^"']+)["']/g), (match) => match[1] ?? "");
  if (hostNames.length === 0 || hostNames.some((host) => host !== EXPECTED_HOST)) {
    errors.push(
      `Android Gradle hostName must be ${EXPECTED_HOST}; found ` +
        (hostNames.length === 0 ? "no declaration" : hostNames.join(", ")),
    );
  }
  if (launchUrls.length === 0 || launchUrls.some((url) => url !== EXPECTED_PATH)) {
    errors.push(
      `Android Gradle launchUrl must be /; found ` +
        (launchUrls.length === 0 ? "no declaration" : launchUrls.join(", ")),
    );
  }

  if (compileLevels.length === 0) {
    errors.push("Android Gradle configuration must declare a numeric compileSdk/compileSdkVersion of 36");
  } else if (compileLevels.some((level) => level !== REQUIRED_API_LEVEL)) {
    errors.push(`Android Gradle compile SDK must resolve to API 36; found ${compileLevels.join(", ")}`);
  }

  if (targetLevels.length === 0) {
    errors.push("Android Gradle configuration must declare a numeric targetSdk/targetSdkVersion of 36");
  } else if (targetLevels.some((level) => level !== REQUIRED_API_LEVEL)) {
    errors.push(`Android Gradle target SDK must resolve to API 36; found ${targetLevels.join(", ")}`);
  }

  const packageIds = files.flatMap((file) => applicationIds(file.text));
  if (packageIds.length === 0) {
    errors.push('Android Gradle configuration must declare applicationId "com.lateapex.collie"');
  } else if (packageIds.some((packageId) => packageId !== EXPECTED_PACKAGE_ID)) {
    errors.push(
      `Android Gradle applicationId must match ${EXPECTED_PACKAGE_ID}; found ${packageIds.join(", ")}`,
    );
  }

  const versionNames = files.flatMap((file) => gradleVersionNames(file.text));
  if (versionNames.length === 0 || versionNames.some((version) => version !== EXPECTED_APP_VERSION)) {
    errors.push(
      `Android Gradle versionName must be ${EXPECTED_APP_VERSION}; found ` +
        (versionNames.length === 0 ? "no declaration" : versionNames.join(", ")),
    );
  }
  const versionCodes = files.flatMap((file) => gradleVersionCodes(file.text));
  if (versionCodes.length === 0 || versionCodes.some((version) => version !== EXPECTED_APP_VERSION_CODE)) {
    errors.push(
      `Android Gradle versionCode must be ${EXPECTED_APP_VERSION_CODE}; found ` +
        (versionCodes.length === 0 ? "no declaration" : versionCodes.join(", ")),
    );
  }
}

function validateAssetLinks(text: string, errors: string[]): void {
  if (PLACEHOLDER.test(text)) {
    errors.push("web/public/.well-known/assetlinks.json contains a placeholder fingerprint");
  }

  const parsed = parseJson(text, "web/public/.well-known/assetlinks.json", errors);
  if (!parsed.ok) return;
  if (!Array.isArray(parsed.value)) {
    errors.push("web/public/.well-known/assetlinks.json must contain a JSON array");
    return;
  }

  let associationCount = 0;
  for (const [index, statementValue] of parsed.value.entries()) {
    if (!isObject(statementValue)) {
      errors.push(`assetlinks.json statement ${index} must be a JSON object`);
      continue;
    }
    const relations = stringArray(statementValue.relation);
    if (relations === null || relations.length !== 1 || relations[0] !== HANDLE_ALL_URLS) {
      errors.push(
        `assetlinks.json statement ${index} must contain only relation ${HANDLE_ALL_URLS}`,
      );
      continue;
    }

    associationCount += 1;
    const target = statementValue.target;
    if (!isObject(target)) {
      errors.push(`assetlinks.json statement ${index} must contain an Android target object`);
      continue;
    }
    if (stringValue(target.namespace) !== "android_app") {
      errors.push(`assetlinks.json statement ${index} target.namespace must be "android_app"`);
    }
    if (stringValue(target.package_name) !== EXPECTED_PACKAGE_ID) {
      errors.push(
        `assetlinks.json statement ${index} package_name must match ${EXPECTED_PACKAGE_ID}; ` +
          `found ${JSON.stringify(target.package_name)}`,
      );
    }

    const fingerprints = stringArray(target.sha256_cert_fingerprints);
    if (fingerprints === null || fingerprints.length === 0) {
      errors.push(`assetlinks.json statement ${index} must contain at least one SHA-256 fingerprint`);
      continue;
    }
    for (const fingerprint of fingerprints) {
      if (!SHA256_FINGERPRINT.test(fingerprint)) {
        errors.push(
          `assetlinks.json statement ${index} fingerprint must be 32 uppercase colon-separated hex bytes; ` +
            `found ${JSON.stringify(fingerprint)}`,
        );
      }
    }
  }

  if (associationCount === 0) {
    errors.push(`assetlinks.json must delegate ${HANDLE_ALL_URLS} to ${EXPECTED_PACKAGE_ID}`);
  }
}

export function validateAndroidTwa(input: AndroidTwaValidationInput): AndroidTwaValidationResult {
  const errors: string[] = [];
  validateTwaManifest(input.twaManifestText, errors);
  validateAndroidManifest(input.androidManifestText, "android/app/src/main/AndroidManifest.xml", errors);
  validateAndroidStrings(input.androidStringsText, errors);
  if (input.mergedAndroidManifestText !== undefined) {
    validateAndroidManifest(input.mergedAndroidManifestText, "merged debug AndroidManifest.xml", errors);
  }
  validateGradle(input.gradleFiles, errors);

  const checkedAssetLinks = input.assetLinksSourceText !== undefined;
  if (input.assetLinksSourceText !== undefined) {
    validateAssetLinks(input.assetLinksSourceText, errors);
  } else if (input.requireAssetLinks === true) {
    errors.push(
      "release validation requires web/public/.well-known/assetlinks.json with the release certificate fingerprint",
    );
  }
  if (input.assetLinksBuiltText !== undefined && input.assetLinksSourceText === undefined) {
    errors.push("web/dist contains assetlinks.json but the tracked web/public source is missing");
  } else if (
    input.webDistExists === true &&
    input.assetLinksSourceText !== undefined &&
    input.assetLinksBuiltText === undefined
  ) {
    errors.push("web/dist exists but web/dist/.well-known/assetlinks.json is missing");
  } else if (
    input.assetLinksBuiltText !== undefined &&
    input.assetLinksSourceText !== undefined &&
    input.assetLinksBuiltText !== input.assetLinksSourceText
  ) {
    errors.push("web/dist/.well-known/assetlinks.json differs from web/public/.well-known/assetlinks.json");
  }

  return { errors, checkedAssetLinks };
}

function optionalFile(path: string): string | undefined {
  return existsSync(path) ? readFileSync(path, "utf8") : undefined;
}

function main(args: readonly string[]): number {
  const unexpectedArgs = args.filter((arg) => arg !== "--require-dal");
  if (unexpectedArgs.length > 0) {
    console.error(`Usage: bun scripts/check-android-twa.ts [--require-dal]\nUnexpected: ${unexpectedArgs.join(" ")}`);
    return 2;
  }
  const root = join(import.meta.dir, "..");
  const twaManifestPath = join(root, "android", "twa-manifest.json");
  if (!existsSync(twaManifestPath)) {
    console.error("Android TWA check failed:\n- missing android/twa-manifest.json");
    return 1;
  }
  const androidManifestPath = join(root, "android", "app", "src", "main", "AndroidManifest.xml");
  if (!existsSync(androidManifestPath)) {
    console.error("Android TWA check failed:\n- missing android/app/src/main/AndroidManifest.xml");
    return 1;
  }
  const androidStringsPath = join(root, "android", "app", "src", "main", "res", "values", "strings.xml");
  if (!existsSync(androidStringsPath)) {
    console.error("Android TWA check failed:\n- missing android/app/src/main/res/values/strings.xml");
    return 1;
  }
  const mergedManifestPath = join(
    root,
    "android",
    "app",
    "build",
    "intermediates",
    "merged_manifest",
    "debug",
    "processDebugMainManifest",
    "AndroidManifest.xml",
  );

  const gradlePaths = [
    join(root, "android", "build.gradle"),
    join(root, "android", "build.gradle.kts"),
    join(root, "android", "app", "build.gradle"),
    join(root, "android", "app", "build.gradle.kts"),
  ];
  const gradleFiles = gradlePaths
    .filter(existsSync)
    .map((path) => ({ path, text: readFileSync(path, "utf8") }));
  const sourceDalPath = join(root, "web", "public", ".well-known", "assetlinks.json");
  const builtDalPath = join(root, "web", "dist", ".well-known", "assetlinks.json");
  const result = validateAndroidTwa({
    twaManifestText: readFileSync(twaManifestPath, "utf8"),
    androidManifestText: readFileSync(androidManifestPath, "utf8"),
    androidStringsText: readFileSync(androidStringsPath, "utf8"),
    mergedAndroidManifestText: optionalFile(mergedManifestPath),
    gradleFiles,
    assetLinksSourceText: optionalFile(sourceDalPath),
    assetLinksBuiltText: optionalFile(builtDalPath),
    webDistExists: existsSync(join(root, "web", "dist")),
    requireAssetLinks: args.includes("--require-dal"),
  });

  if (result.errors.length > 0) {
    console.error(`Android TWA check failed:\n${result.errors.map((error) => `- ${error}`).join("\n")}`);
    return 1;
  }

  const dalStatus = result.checkedAssetLinks
    ? "Digital Asset Links source is valid"
    : "Digital Asset Links source is not present yet; fingerprint checks skipped";
  console.log(`✓ Android TWA configuration matches the Collie identity and API 36; ${dalStatus}.`);
  return 0;
}

if (import.meta.main) process.exitCode = main(process.argv.slice(2));
