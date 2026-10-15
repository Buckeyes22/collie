import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";

const EXPECTED_APPLICATION_ID = "com.lateapex.collie";
const EXPECTED_ACTIVITY = "com.lateapex.collie.ui.MainActivity";
const EXPECTED_VERSION_NAME = "1.6.0";
const EXPECTED_VERSION_CODE = 4;
const EXPECTED_COMPILE_SDK = 37;
const EXPECTED_COMPILE_SDK_MINOR = 2;
const EXPECTED_BUILD_TOOLS = "37.0.0";
const EXPECTED_GRADLE_SHA256 = "bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c";
const EXPECTED_TARGET_SDK = 37;
const EXPECTED_MIN_SDK = 36;
const EXPECTED_GRADLE = "9.8.0";
const EXPECTED_AGP = "9.4.1";
const EXPECTED_KOTLIN = "2.4.20";
const EXPECTED_DEFAULT_ORIGIN = "";

const FORBIDDEN_ANDROID_PATTERN =
  /(?:androidbrowserhelper|bubblewrap|trusted\s*web\s*activity|TrustedWebActivity|customtabs|CustomTabsIntent|capacitor|androidx?\.webkit|JavascriptInterface|firebase|crashlytics|google-services|play-services-analytics|logging-interceptor|okhttp3\.logging)/i;
const FORBIDDEN_TLS_PATTERN =
  /(?:ALLOW_ALL_HOSTNAME_VERIFIER|trustAll|TrustAll|HostnameVerifier|X509TrustManager|hostnameVerifier\s*\{|sslSocketFactory\s*\()/;

export interface AndroidNativeSourceFile {
  path: string;
  text: string;
}

export interface AndroidNativeValidationInput {
  settingsGradleText: string;
  gradlePropertiesText: string;
  rootGradleText: string;
  appGradleText: string;
  wrapperPropertiesText: string;
  androidManifestText: string;
  dataExtractionRulesText: string;
  kotlinSources: readonly AndroidNativeSourceFile[];
  javaSources?: readonly AndroidNativeSourceFile[];
  dependencyLockfiles: readonly AndroidNativeSourceFile[];
  mergedAndroidManifestText?: string;
  forbiddenArtifacts?: readonly string[];
}

export interface AndroidNativeValidationResult {
  errors: string[];
  checkedMergedManifest: boolean;
}

function valuesForStringAssignment(text: string, name: string): string[] {
  const pattern = new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`, "g");
  return Array.from(text.matchAll(pattern), (match) => match[1] ?? "");
}

function valuesForNumberAssignment(text: string, name: string): number[] {
  const pattern = new RegExp(`\\b${name}\\s*=\\s*(\\d+)\\b`, "g");
  return Array.from(text.matchAll(pattern), (match) => Number(match[1]));
}

function requireExactStrings(
  text: string,
  name: string,
  expected: string,
  label: string,
  errors: string[],
): void {
  const values = valuesForStringAssignment(text, name);
  if (values.length === 0 || values.some((value) => value !== expected)) {
    errors.push(`${label} must be ${JSON.stringify(expected)}; found ${values.length === 0 ? "no declaration" : values.join(", ")}`);
  }
}

function requireExactNumbers(
  text: string,
  name: string,
  expected: number,
  label: string,
  errors: string[],
): void {
  const values = valuesForNumberAssignment(text, name);
  if (values.length === 0 || values.some((value) => value !== expected)) {
    errors.push(`${label} must be ${expected}; found ${values.length === 0 ? "no declaration" : values.join(", ")}`);
  }
}

function validateSettings(text: string, errors: string[]): void {
  const modules = Array.from(
    text.matchAll(/\binclude\s*\(([^)]*)\)/g),
    (match) => Array.from((match[1] ?? "").matchAll(/["'](:[^"']+)["']/g), (item) => item[1] ?? ""),
  ).flat();
  const uniqueModules = [...new Set(modules)];
  if (uniqueModules.length !== 1 || uniqueModules[0] !== ":app") {
    errors.push(`android/settings.gradle.kts must include only :app; found ${uniqueModules.length === 0 ? "no modules" : uniqueModules.join(", ")}`);
  }
}

function requirePluginVersion(
  text: string,
  plugin: string,
  version: string,
  errors: string[],
): void {
  const escaped = plugin.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`id\\(["']${escaped}["']\\)\\s+version\\s+["']${version.replaceAll(".", "\\.")}["']`);
  if (!pattern.test(text)) {
    errors.push(`android/build.gradle.kts must pin ${plugin} ${version}`);
  }
}

function validateBuildConfiguration(input: AndroidNativeValidationInput, errors: string[]): void {
  requirePluginVersion(input.rootGradleText, "com.android.application", EXPECTED_AGP, errors);
  if (!input.rootGradleText.includes(`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${EXPECTED_KOTLIN}")`)) {
    errors.push(`AGP built-in Kotlin compiler must pin org.jetbrains.kotlin:kotlin-gradle-plugin:${EXPECTED_KOTLIN}`);
  }
  const kotlinBuild = `${input.rootGradleText}\n${input.appGradleText}`;
  if (/id\(\s*["']org\.jetbrains\.kotlin\.android["']\s*\)/.test(kotlinBuild) || /\bkotlinOptions\s*\{/.test(kotlinBuild)) {
    errors.push("Android must use AGP built-in Kotlin and kotlin.compilerOptions, without the retired kotlin-android plugin or kotlinOptions");
  }
  if (/android\.(?:builtInKotlin|newDsl)\s*=\s*false/.test(`${kotlinBuild}\n${input.gradlePropertiesText}`)) {
    errors.push("Android must not opt out of built-in Kotlin or the current AGP DSL");
  }
  requirePluginVersion(
    input.rootGradleText,
    "org.jetbrains.kotlin.plugin.serialization",
    EXPECTED_KOTLIN,
    errors,
  );

  const wrapperNeedle = `gradle-${EXPECTED_GRADLE}-`;
  if (!input.wrapperPropertiesText.includes(wrapperNeedle)) {
    errors.push(`Gradle wrapper must resolve to ${EXPECTED_GRADLE}`);
  }

  if (!new RegExp(`^distributionSha256Sum=${EXPECTED_GRADLE_SHA256}$`, "m").test(input.wrapperPropertiesText)) {
    errors.push("Gradle wrapper must pin the official distribution SHA256");
  }

  const app = input.appGradleText;
  requireExactStrings(app, "namespace", EXPECTED_APPLICATION_ID, "Android namespace", errors);
  requireExactStrings(app, "applicationId", EXPECTED_APPLICATION_ID, "Android applicationId", errors);
  requireExactStrings(app, "versionName", EXPECTED_VERSION_NAME, "Android versionName", errors);
  requireExactNumbers(app, "versionCode", EXPECTED_VERSION_CODE, "Android versionCode", errors);
  const compileSdk = app.match(/\bcompileSdk\s*\{\s*version\s*=\s*release\(\s*(\d+)\s*\)\s*\{\s*minorApiLevel\s*=\s*(\d+)\s*}\s*}/);
  if (Number(compileSdk?.[1]) !== EXPECTED_COMPILE_SDK || Number(compileSdk?.[2]) !== EXPECTED_COMPILE_SDK_MINOR || /\bcompileSdk\s*=/.test(app)) {
    errors.push(`Android compileSdk must be release(${EXPECTED_COMPILE_SDK}) with minorApiLevel = ${EXPECTED_COMPILE_SDK_MINOR}`);
  }
  requireExactStrings(app, "buildToolsVersion", EXPECTED_BUILD_TOOLS, "Android Build Tools", errors);
  requireExactNumbers(app, "targetSdk", EXPECTED_TARGET_SDK, "Android targetSdk", errors);
  requireExactNumbers(app, "minSdk", EXPECTED_MIN_SDK, "Android minSdk", errors);
  const defaultOrigin = app.match(
    /buildConfigField\(\s*["']String["']\s*,\s*["']DEFAULT_ORIGIN["']\s*,\s*["']\\["']([^"']*)\\["']["']\s*\)/,
  )?.[1];
  if (defaultOrigin !== EXPECTED_DEFAULT_ORIGIN) {
    errors.push(`Android DEFAULT_ORIGIN must be empty for public builds; found ${JSON.stringify(defaultOrigin)}`);
  }

  if (!/\bviewBinding\s*=\s*true\b/.test(app)) {
    errors.push("android/app/build.gradle.kts must enable View Binding");
  }
  if (!/\bjvmToolchain\s*\(\s*21\s*\)/.test(app) && !/JavaLanguageVersion\.of\(\s*21\s*\)/.test(app)) {
    errors.push("Android Kotlin compilation must use the JDK 21 toolchain");
  }
  const java17Count = app.match(/JavaVersion\.VERSION_17/g)?.length ?? 0;
  if (java17Count < 2 || !/(?:JvmTarget\.JVM_17|jvmTarget\s*=\s*["']17["'])/.test(app)) {
    errors.push("Android Java and Kotlin bytecode targets must both be 17");
  }
  if (!/\bcompilerOptions\s*\{/.test(app) || !/\bjvmDefault\.set\(\s*(?:org\.jetbrains\.kotlin\.gradle\.dsl\.)?JvmDefaultMode\.NO_COMPATIBILITY\s*\)/.test(app)) {
    errors.push("Android must configure JVM defaults through the current Kotlin compilerOptions DSL");
  }
  if (!/\bisMinifyEnabled\s*=\s*true\b/.test(app)) {
    errors.push("Android release builds must enable minification");
  }
  if (!/\bisDebuggable\s*=\s*false\b/.test(app)) {
    errors.push("Android release builds must explicitly remain non-debuggable");
  }
  if (!/\blockAllConfigurations\s*\(\s*\)/.test(`${input.rootGradleText}\n${app}`)) {
    errors.push("Android Gradle configuration must lock all dependency configurations");
  }
  if (input.dependencyLockfiles.length === 0) {
    errors.push("Android dependency locking must produce at least one tracked gradle.lockfile");
  }
  for (const lockfile of input.dependencyLockfiles) {
    if (lockfile.text.trim().length === 0) {
      errors.push(`${lockfile.path} must not be empty`);
    }
  }

  const requiredDependencies = [
    `org.jetbrains.kotlin:kotlin-bom:${EXPECTED_KOTLIN}`,
    "com.squareup.okhttp3:okhttp:5.5.0",
    "com.squareup.okhttp3:mockwebserver3:5.5.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
    "androidx.lifecycle:lifecycle-runtime-ktx:2.11.0",
    "androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0",
    "androidx.core:core-ktx:1.19.1",
    "androidx.appcompat:appcompat:1.8.0",
    "com.google.android.material:material:1.14.0",
    "androidx.recyclerview:recyclerview:1.4.0",
    "androidx.test.espresso:espresso-core:3.7.0",
  ];
  for (const dependency of requiredDependencies) {
    if (!app.includes(dependency)) {
      errors.push(`android/app/build.gradle.kts must declare ${dependency}`);
    }
  }

  const combined = `${input.settingsGradleText}\n${input.rootGradleText}\n${app}`;
  const forbidden = combined.match(FORBIDDEN_ANDROID_PATTERN)?.[0];
  if (forbidden !== undefined) {
    errors.push(`Android Gradle configuration contains forbidden browser/TWA/Capacitor/Firebase term ${JSON.stringify(forbidden)}`);
  }
}

function attribute(attributes: string, name: string): string | undefined {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return attributes.match(new RegExp(`\\b${escaped}\\s*=\\s*["']([^"']+)["']`))?.[1];
}

function validatePermissions(
  text: string,
  label: string,
  merged: boolean,
  errors: string[],
): void {
  const permissions = Array.from(
    text.matchAll(/<uses-permission\b([^>]*)\/?\s*>/g),
    (match) => attribute(match[1] ?? "", "android:name") ?? "",
  );
  const unique = [...new Set(permissions)];
  if (permissions.length !== unique.length) {
    errors.push(`${label} contains duplicate uses-permission declarations`);
  }
  const allowed = merged
    ? unique.filter(
        (permission) =>
          permission === "android.permission.INTERNET" ||
          permission === "android.permission.RECORD_AUDIO" ||
          permission === "android.permission.POST_NOTIFICATIONS" ||
          permission === "android.permission.REQUEST_INSTALL_PACKAGES" ||
          ["android.permission.WAKE_LOCK", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.FOREGROUND_SERVICE"].includes(permission) ||
          /^com\.lateapex\.collie(?:\.debug|\.completiontest)?\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$/.test(
            permission,
          ),
      )
    : unique.filter(
        (permission) =>
          permission === "android.permission.INTERNET" || permission === "android.permission.RECORD_AUDIO" || permission === "android.permission.POST_NOTIFICATIONS" || permission === "android.permission.REQUEST_INSTALL_PACKAGES",
      );
  if (
    !unique.includes("android.permission.INTERNET") ||
    !unique.includes("android.permission.RECORD_AUDIO") ||
    allowed.length !== unique.length
  ) {
    errors.push(`${label} may request only android.permission.INTERNET and android.permission.RECORD_AUDIO${merged ? " plus native notification/install and bounded connector/WorkManager permissions and its AndroidX signature receiver permission" : ""}, and approved native notification/install permissions, and must declare both; found ${unique.length === 0 ? "none" : unique.join(", ")}`);
  }
}

function validateManifest(text: string, label: string, merged: boolean, errors: string[]): void {
  validatePermissions(text, label, merged, errors);
  const applicationAttributes = text.match(/<application\b([^>]*)>/)?.[1];
  if (applicationAttributes === undefined) {
    errors.push(`${label} must declare an application`);
    return;
  }
  const expectedAttributes = new Map([
    ["android:name", ".CollieApplication"],
    ["android:allowBackup", "false"],
    ["android:fullBackupContent", "false"],
    ["android:usesCleartextTraffic", "false"],
    ["android:dataExtractionRules", "@xml/data_extraction_rules"],
  ]);
  for (const [name, expected] of expectedAttributes) {
    const actual = attribute(applicationAttributes, name);
    if (
      name === "android:name" &&
      actual !== expected &&
      actual !== `${EXPECTED_APPLICATION_ID}.CollieApplication`
    ) {
      errors.push(`${label} application ${name} must be ${expected}; found ${JSON.stringify(actual)}`);
    } else if (name !== "android:name" && actual !== expected) {
      errors.push(`${label} application ${name} must be ${expected}; found ${JSON.stringify(actual)}`);
    }
  }

  const components = Array.from(text.matchAll(/<(activity|activity-alias|provider|receiver|service)\b([^>]*)>/g));
  const exported = components.filter((match) => attribute(match[2] ?? "", "android:exported") === "true");
  const exportedNames = exported.map((match) => attribute(match[2] ?? "", "android:name") ?? "unnamed");
  const mainAliases = new Set([".ui.MainActivity", "ui.MainActivity", EXPECTED_ACTIVITY]);
  // ADR 0098: one constrained native OAuth dispatcher may be exported beside the launcher.
  const callbackAliases = new Set([".ui.OpenLinkActivity", "ui.OpenLinkActivity", `${EXPECTED_APPLICATION_ID}.ui.OpenLinkActivity`]);
  const callbackExport = exported.filter((match) => callbackAliases.has(attribute(match[2] ?? "", "android:name") ?? ""));
  // The pinned connector authenticates distributor identity before private PushService delivery.
  // WorkManager's only exported service is protected by Android's signature job permission.
  const allowedLibraryExport = (kind: string, attrs: string): boolean => {
    if (!merged) return false;
    const name = attribute(attrs, "android:name");
    if (name === "androidx.work.impl.background.systemjob.SystemJobService") return kind === "service" && attribute(attrs, "android:permission") === "android.permission.BIND_JOB_SERVICE";
    const expected = name === "org.unifiedpush.android.connector.internal.RaiseToForegroundService"
      ? { kind: "service", actions: ["RAISE_TO_FOREGROUND"] }
      : name === "org.unifiedpush.android.connector.internal.MessagingReceiverImpl"
        ? { kind: "receiver", actions: ["MESSAGE", "UNREGISTERED", "NEW_ENDPOINT", "REGISTRATION_FAILED", "TEMP_UNAVAILABLE"] } : null;
    if (expected === null || kind !== expected.kind) return false;
    const block = Array.from(text.matchAll(new RegExp(`<${kind}\\b(?![^>]*\\/\\>)([^>]*)>([\\s\\S]*?)<\\/${kind}>`, "g")))
      .find((m) => attribute(m[1] ?? "", "android:name") === name)?.[2] ?? "";
    const actions = Array.from(block.matchAll(/<action\b([^>]*)\/?>/g), (m) => attribute(m[1] ?? "", "android:name"));
    return actions.length === expected.actions.length && expected.actions.every((a) => actions.includes(`org.unifiedpush.android.connector.${a}`)) && !/<data\b|<category\b/.test(block);
  };
  if (exported.some((match) => !mainAliases.has(attribute(match[2] ?? "", "android:name") ?? "") && !callbackAliases.has(attribute(match[2] ?? "", "android:name") ?? "") && !allowedLibraryExport(match[1] ?? "", match[2] ?? "")) || exported.filter((match) => mainAliases.has(attribute(match[2] ?? "", "android:name") ?? "")).length !== 1 || callbackExport.length > 1 || callbackExport.some((match) => match[1] !== "activity")) {
    errors.push(`${label} must export only ${EXPECTED_ACTIVITY} and the constrained native OpenLinkActivity plus verified pinned native connector/job entry points; found ${exportedNames.length === 0 ? "none" : exportedNames.join(", ")}`);
  }

  const mainBlock = text.match(
    /<activity\b([^>]*android:name\s*=\s*["'](?:\.ui\.MainActivity|ui\.MainActivity|com\.lateapex\.collie\.ui\.MainActivity)["'][^>]*)>([\s\S]*?)<\/activity>/,
  );
  if (mainBlock === null) {
    errors.push(`${label} must declare ${EXPECTED_ACTIVITY}`);
  } else {
    if (attribute(mainBlock[1] ?? "", "android:exported") !== "true") {
      errors.push(`${label} ${EXPECTED_ACTIVITY} must set android:exported="true"`);
    }
    const body = mainBlock[2] ?? "";
    if (!body.includes("android.intent.action.MAIN") || !body.includes("android.intent.category.LAUNCHER")) {
      errors.push(`${label} ${EXPECTED_ACTIVITY} must be the launcher entry point`);
    }
  }

  const callbackBlock = text.match(/<activity\b([^>]*android:name\s*=\s*["'](?:\.ui\.OpenLinkActivity|ui\.OpenLinkActivity|com\.lateapex\.collie\.ui\.OpenLinkActivity)["'][^>]*)>([\s\S]*?)<\/activity>/);
  const callbackBody = callbackBlock?.[2] ?? "";
  const outsideCallback = callbackBlock ? text.replace(callbackBlock[0], "") : text;
  if (/android\.intent\.action\.VIEW|android\.intent\.category\.BROWSABLE|android:autoVerify/.test(outsideCallback)) errors.push(`${label} must not register browser or verified-link intent filters outside the native callback`);
  if (callbackExport.length === 1) {
    const filters = Array.from(callbackBody.matchAll(/<intent-filter\b[^>]*>([\s\S]*?)<\/intent-filter>/g));
    const callbackFilters = filters.filter((filter) => filter[1]?.includes("android.intent.action.VIEW"));
    const shareFilters = filters.filter((filter) => filter[1]?.includes("android.intent.action.SEND"));
    const nativeCallback = callbackFilters[0]?.[1] ?? "";
    const actions = Array.from(nativeCallback.matchAll(/<action\b([^>]*)\/?>/g));
    const categories = Array.from(nativeCallback.matchAll(/<category\b([^>]*)\/?>/g));
    const data = Array.from(nativeCallback.matchAll(/<data\b([^>]*)\/?>/g));
    const extraOutsideFilters = callbackBody.replace(/<intent-filter\b[^>]*>[\s\S]*?<\/intent-filter>/g, "");
    if (/android:autoVerify/.test(callbackBody) || callbackFilters.length !== 1 || filters.length !== 1 + shareFilters.length || shareFilters.length > 1 || /<action\b|<category\b|<data\b|android:autoVerify/.test(extraOutsideFilters) || actions.length !== 1 || attribute(actions[0]?.[1] ?? "", "android:name") !== "android.intent.action.VIEW" || categories.length !== 2 || new Set(categories.map((match) => attribute(match[1] ?? "", "android:name"))).size !== 2 || data.length !== 1 || attribute(data[0]?.[1] ?? "", "android:scheme") !== "com.lateapex.collie" || Array.from((data[0]?.[1] ?? "").matchAll(/([\w:.-]+)\s*=/g)).some((match) => match[1] !== "android:scheme") || /android:(?:host|port|path\w*|mimeType)|android:autoVerify/.test(nativeCallback) || !nativeCallback.includes("android.intent.category.DEFAULT") || !nativeCallback.includes("android.intent.category.BROWSABLE")) errors.push(`${label} native callback must accept only com.lateapex.collie:/oauth2redirect`);
    if (shareFilters.length === 1) {
      const share = shareFilters[0]![1] ?? "";
      const shareActions = Array.from(share.matchAll(/<action\b([^>]*)\/?>/g));
      const shareCategories = Array.from(share.matchAll(/<category\b([^>]*)\/?>/g));
      const shareData = Array.from(share.matchAll(/<data\b([^>]*)\/?>/g));
      if (shareActions.length !== 1 || attribute(shareActions[0]?.[1] ?? "", "android:name") !== "android.intent.action.SEND" || shareCategories.length !== 1 || attribute(shareCategories[0]?.[1] ?? "", "android:name") !== "android.intent.category.DEFAULT" || shareData.length !== 1 || attribute(shareData[0]?.[1] ?? "", "android:mimeType") !== "text/plain" || Array.from((shareData[0]?.[1] ?? "").matchAll(/([\w:.-]+)\s*=/g)).some((match) => match[1] !== "android:mimeType") || /android:(?:host|port|path\w*|scheme)|android:autoVerify/.test(share)) errors.push(`${label} native sharing must accept only a separate text/plain SEND filter`);
    }
  }
  const forbidden = text.match(FORBIDDEN_ANDROID_PATTERN)?.[0];
  if (forbidden !== undefined) {
    errors.push(`${label} contains forbidden browser/TWA/Capacitor/Firebase term ${JSON.stringify(forbidden)}`);
  }
}

function validateDataExtractionRules(text: string, errors: string[]): void {
  for (const section of ["cloud-backup", "device-transfer"]) {
    const body = text.match(new RegExp(`<${section}\\b[^>]*>([\\s\\S]*?)<\\/${section}>`))?.[1];
    if (body === undefined) {
      errors.push(`data_extraction_rules.xml must declare ${section}`);
      continue;
    }
    const exclusions = Array.from(body.matchAll(/<exclude\b([^>]*)\/?\s*>/g), (match) => ({
      domain: attribute(match[1] ?? "", "domain") ?? "",
      path: attribute(match[1] ?? "", "path") ?? "",
    }));
    const excludedDomains = new Set(
      exclusions.filter((exclusion) => exclusion.path === ".").map((exclusion) => exclusion.domain),
    );
    for (const domain of ["root", "file", "database", "sharedpref", "external"]) {
      if (!excludedDomains.has(domain)) {
        errors.push(`data_extraction_rules.xml ${section} must exclude all ${domain} data`);
      }
    }
  }
}

function validateKotlinSources(sources: readonly AndroidNativeSourceFile[], errors: string[]): void {
  const paths = sources.map((source) => source.path);
  for (const suffix of ["CollieApplication.kt", "ui/MainActivity.kt"]) {
    if (!paths.some((path) => path.endsWith(suffix))) {
      errors.push(`Android native source is missing ${suffix}`);
    }
  }
  for (const source of sources) {
    const forbidden = source.text.match(FORBIDDEN_ANDROID_PATTERN)?.[0];
    if (forbidden !== undefined) {
      errors.push(`${source.path} contains forbidden browser/TWA/Capacitor/Firebase term ${JSON.stringify(forbidden)}`);
    }
    const unsafeTls = source.text.match(FORBIDDEN_TLS_PATTERN)?.[0];
    if (unsafeTls !== undefined) {
      errors.push(`${source.path} contains forbidden TLS-bypass hook ${JSON.stringify(unsafeTls)}`);
    }
    if (/HttpLoggingInterceptor|android\.util\.Log\b/.test(source.text)) {
      errors.push(`${source.path} must not add HTTP or Android runtime logging to the native client`);
    }
  }
}

function validateNoJavaSources(
  sources: readonly AndroidNativeSourceFile[],
  errors: string[],
): void {
  for (const source of sources) {
    errors.push(`${source.path} is legacy Java source; the native client source must remain Kotlin`);
  }
}

export function validateAndroidNative(
  input: AndroidNativeValidationInput,
): AndroidNativeValidationResult {
  const errors: string[] = [];
  validateSettings(input.settingsGradleText, errors);
  validateBuildConfiguration(input, errors);
  validateManifest(input.androidManifestText, "android/app/src/main/AndroidManifest.xml", false, errors);
  validateDataExtractionRules(input.dataExtractionRulesText, errors);
  validateKotlinSources(input.kotlinSources, errors);
  validateNoJavaSources(input.javaSources ?? [], errors);
  if (input.mergedAndroidManifestText !== undefined) {
    validateManifest(input.mergedAndroidManifestText, "debug merged AndroidManifest.xml", true, errors);
  }
  for (const path of input.forbiddenArtifacts ?? []) {
    errors.push(`${path} is a removed TWA/Node artifact and must not exist`);
  }
  return { errors, checkedMergedManifest: input.mergedAndroidManifestText !== undefined };
}

function readRequired(path: string, errors: string[]): string {
  if (!existsSync(path)) {
    errors.push(`${relative(process.cwd(), path)} is missing`);
    return "";
  }
  return readFileSync(path, "utf8");
}

function collectKotlinSources(root: string): AndroidNativeSourceFile[] {
  if (!existsSync(root)) return [];
  return readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".kt"))
    .map((entry) => {
      const path = join(entry.parentPath, entry.name);
      return { path: relative(process.cwd(), path), text: readFileSync(path, "utf8") };
    });
}

function collectJavaSources(root: string): AndroidNativeSourceFile[] {
  if (!existsSync(root)) return [];
  return readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".java"))
    .map((entry) => {
      const path = join(entry.parentPath, entry.name);
      return { path: relative(process.cwd(), path), text: readFileSync(path, "utf8") };
    });
}

function collectDependencyLockfiles(root: string): AndroidNativeSourceFile[] {
  return [join(root, "gradle.lockfile"), join(root, "app/gradle.lockfile")]
    .filter((path) => existsSync(path))
    .map((path) => ({ path: relative(process.cwd(), path), text: readFileSync(path, "utf8") }));
}

function firstExisting(paths: readonly string[]): string | undefined {
  return paths.find((path) => existsSync(path));
}

function runCli(): number {
  const root = process.cwd();
  const readErrors: string[] = [];
  const appRoot = join(root, "android", "app");
  const mergedPath = firstExisting([
    join(appRoot, "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
    join(appRoot, "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
  ]);
  const forbiddenArtifacts = [
    "android/package.json",
    "android/package-lock.json",
    "android/twa-manifest.json",
    "android/manifest-checksum.txt",
    "android/app/src/main/res/raw/web_app_manifest.json",
    "android/app/src/main/res/xml/filepaths.xml",
    "android/app/src/main/res/xml/shortcuts.xml",
  ].filter((path) => existsSync(join(root, path)));
  const input: AndroidNativeValidationInput = {
    settingsGradleText: readRequired(join(root, "android/settings.gradle.kts"), readErrors),
    gradlePropertiesText: readRequired(join(root, "android/gradle.properties"), readErrors),
    rootGradleText: readRequired(join(root, "android/build.gradle.kts"), readErrors),
    appGradleText: readRequired(join(appRoot, "build.gradle.kts"), readErrors),
    wrapperPropertiesText: readRequired(
      join(root, "android/gradle/wrapper/gradle-wrapper.properties"),
      readErrors,
    ),
    androidManifestText: readRequired(join(appRoot, "src/main/AndroidManifest.xml"), readErrors),
    dataExtractionRulesText: readRequired(
      join(appRoot, "src/main/res/xml/data_extraction_rules.xml"),
      readErrors,
    ),
    kotlinSources: collectKotlinSources(join(appRoot, "src/main/java")),
    javaSources: collectJavaSources(join(appRoot, "src/main/java")),
    dependencyLockfiles: collectDependencyLockfiles(join(root, "android")),
    forbiddenArtifacts,
  };
  if (mergedPath !== undefined) {
    input.mergedAndroidManifestText = readFileSync(mergedPath, "utf8");
  }
  const result = validateAndroidNative(input);
  const errors = [...readErrors, ...result.errors];
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`✗ ${error}\n`);
    return 1;
  }
  const merged = result.checkedMergedManifest ? " including the merged debug manifest" : "";
  process.stdout.write(`✓ Android native configuration is valid${merged}\n`);
  return 0;
}

if (import.meta.main) process.exitCode = runCli();
