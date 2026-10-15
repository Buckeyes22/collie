import { describe, expect, test } from "bun:test";

import {
  type AndroidNativeValidationInput,
  validateAndroidNative,
} from "./check-android-native.ts";

const validRootGradle = `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }
plugins {
  id("com.android.application") version "9.4.1" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
dependencyLocking { lockAllConfigurations() }
`;

const validAppGradle = `plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.serialization")
}
android {
  namespace = "com.lateapex.collie"
  compileSdk { version = release(37) { minorApiLevel = 2 } }
  buildToolsVersion = "37.0.0"
  defaultConfig {
    applicationId = "com.lateapex.collie"
    minSdk = 36
    targetSdk = 37
    versionCode = 5
    versionName = "1.6.1"
    buildConfigField("String", "DEFAULT_ORIGIN", "\\"\\"")
  }
  buildFeatures { viewBinding = true }
  buildTypes { release { isMinifyEnabled = true; isDebuggable = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget = JvmTarget.JVM_17; jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY) } }
dependencies {
  implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.20"))
  implementation("androidx.core:core-ktx:1.19.1")
  implementation("com.squareup.okhttp3:okhttp:5.5.0")
  testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
  implementation("androidx.appcompat:appcompat:1.8.0")
  implementation("com.google.android.material:material:1.14.0")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
`;

const validManifest = `<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  <application android:name=".CollieApplication" android:allowBackup="false"
    android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules"
    android:usesCleartextTraffic="false">
    <activity android:name=".ui.MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>`;

const validExtractionRules = `<data-extraction-rules>
  <cloud-backup>
    <exclude domain="root" path="."/><exclude domain="file" path="."/>
    <exclude domain="database" path="."/><exclude domain="sharedpref" path="."/>
    <exclude domain="external" path="."/>
  </cloud-backup>
  <device-transfer>
    <exclude domain="root" path="."/><exclude domain="file" path="."/>
    <exclude domain="database" path="."/><exclude domain="sharedpref" path="."/>
    <exclude domain="external" path="."/>
  </device-transfer>
</data-extraction-rules>`;

function validInput(overrides: Partial<AndroidNativeValidationInput> = {}): AndroidNativeValidationInput {
  return {
    settingsGradleText: 'rootProject.name = "CollieAndroid"\ninclude(":app")',
    gradlePropertiesText: "android.useAndroidX=true",
    rootGradleText: validRootGradle,
    appGradleText: validAppGradle,
    wrapperPropertiesText:
      "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.0-bin.zip\ndistributionSha256Sum=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c",
    androidManifestText: validManifest,
    dataExtractionRulesText: validExtractionRules,
    kotlinSources: [
      { path: "android/app/src/main/java/com/lateapex/collie/CollieApplication.kt", text: "class CollieApplication" },
      { path: "android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt", text: "class MainActivity" },
    ],
    javaSources: [],
    dependencyLockfiles: [
      { path: "android/app/gradle.lockfile", text: "com.squareup.okhttp3:okhttp:5.5.0=debugRuntimeClasspath" },
    ],
    ...overrides,
  };
}

function messages(overrides: Partial<AndroidNativeValidationInput> = {}): string {
  return validateAndroidNative(validInput(overrides)).errors.join("\n");
}

describe("check-android-native validation", () => {
  test("rejects retired Kotlin integration and DSL opt-outs", () => {
    expect(messages({ rootGradleText: `${validRootGradle}\nplugins { id("org.jetbrains.kotlin.android") }` })).toContain("AGP built-in Kotlin");
    expect(messages({ appGradleText: `${validAppGradle}\nandroid { kotlinOptions { jvmTarget = "17" } }` })).toContain("AGP built-in Kotlin");
    for (const property of ["android.builtInKotlin=false", "android.newDsl=false"]) {
      expect(messages({ gradlePropertiesText: property })).toContain("must not opt out");
    }
    expect(messages({ appGradleText: validAppGradle.replace("jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)", "") })).toContain("compilerOptions DSL");
  });

  test("requires the current stable SDK minor and verified wrapper checksum", () => {
    expect(messages({ appGradleText: validAppGradle.replace("minorApiLevel = 2", "minorApiLevel = 1") })).toContain("minorApiLevel = 2");
    expect(messages({ appGradleText: validAppGradle.replace('buildToolsVersion = "37.0.0"', 'buildToolsVersion = "36.0.0"') })).toContain("Build Tools");
    expect(messages({ wrapperPropertiesText: "distributionUrl=gradle-9.8.0-bin.zip\ndistributionSha256Sum=wrong" })).toContain("official distribution SHA256");
  });

  test("accepts the pinned native Android configuration", () => {
    expect(validateAndroidNative(validInput())).toEqual({ errors: [], checkedMergedManifest: false });
    expect(validateAndroidNative(validInput({ mergedAndroidManifestText: validManifest }))).toEqual({
      errors: [],
      checkedMergedManifest: true,
    });
    const mergedWithAndroidxPermission = validManifest.replace(
      "</manifest>",
      '<uses-permission android:name="com.lateapex.collie.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" /></manifest>',
    );
    expect(
      validateAndroidNative(validInput({ mergedAndroidManifestText: mergedWithAndroidxPermission })),
    ).toEqual({ errors: [], checkedMergedManifest: true });
  });

  test("requires the single app module and pinned plugin/wrapper versions", () => {
    const output = messages({
      settingsGradleText: 'include(":app", ":browser")',
      rootGradleText: validRootGradle.replace("9.4.1", "8.8.0").replaceAll("2.4.20", "2.1.0"),
      wrapperPropertiesText: "distributionUrl=gradle-8.10-bin.zip",
    });
    expect(output).toContain("include only :app");
    expect(output).toContain("com.android.application 9.4.1");
    expect(output).toContain("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20");
    expect(output).toContain("serialization 2.4.20");
    expect(output).toContain("Gradle wrapper must resolve to 9.8.0");
  });

  test("rejects Kotlin runtime BOM drift from the compiler pin", () => {
    const output = messages({
      appGradleText: validAppGradle.replace("kotlin-bom:2.4.20", "kotlin-bom:2.1.0"),
    });
    expect(output).toContain("org.jetbrains.kotlin:kotlin-bom:2.4.20");
  });

  test("requires the current MockWebServer artifact", () => {
    expect(messages({ appGradleText: validAppGradle.replace("mockwebserver3:5.5.0", "mockwebserver:5.5.0") })).toContain("com.squareup.okhttp3:mockwebserver3:5.5.0");
  });

  test("rejects application, SDK, and version drift", () => {
    const appGradle = validAppGradle
      .replaceAll("com.lateapex.collie", "com.example.other")
      .replace("release(37)", "release(36)")
      .replace("minSdk = 36", "minSdk = 37")
      .replace("targetSdk = 37", "targetSdk = 36")
      .replace("versionCode = 5", "versionCode = 6")
      .replace('versionName = "1.6.1"', 'versionName = "1.6.2"');
    const output = messages({ appGradleText: appGradle });
    for (const field of ["namespace", "applicationId", "compileSdk", "minSdk", "targetSdk", "versionCode", "versionName"]) {
      expect(output).toContain(field);
    }
  });

  test("rejects a private default origin in public builds", () => {
    expect(
      messages({
        appGradleText: validAppGradle.replace(
          'buildConfigField("String", "DEFAULT_ORIGIN", "\\"\\"")',
          'buildConfigField("String", "DEFAULT_ORIGIN", "\\"https://private.example/\\"")',
        ),
      }),
    ).toContain("Android DEFAULT_ORIGIN must be empty for public builds");
  });

  test("requires native UI, toolchain, release, and dependency-lock configuration", () => {
    const output = messages({
      rootGradleText: validRootGradle.replace("dependencyLocking { lockAllConfigurations() }", ""),
      appGradleText: validAppGradle
        .replace("viewBinding = true", "viewBinding = false")
        .replace("jvmToolchain(21)", "jvmToolchain(17)")
        .replaceAll("JavaVersion.VERSION_17", "JavaVersion.VERSION_11")
        .replace("JvmTarget.JVM_17", "JvmTarget.JVM_11")
        .replace("isMinifyEnabled = true", "isMinifyEnabled = false")
        .replace("isDebuggable = false", "isDebuggable = true"),
    });
    expect(output).toContain("View Binding");
    expect(output).toContain("JDK 21");
    expect(output).toContain("bytecode targets must both be 17");
    expect(output).toContain("enable minification");
    expect(output).toContain("non-debuggable");
    expect(output).toContain("lock all dependency");
  });

  test("requires non-empty generated dependency lock state", () => {
    expect(messages({ dependencyLockfiles: [] })).toContain("at least one tracked gradle.lockfile");
    expect(
      messages({ dependencyLockfiles: [{ path: "android/app/gradle.lockfile", text: "" }] }),
    ).toContain("android/app/gradle.lockfile must not be empty");
  });

  test("requires the approved native dependency baseline", () => {
    const output = messages({ appGradleText: validAppGradle.replace("com.squareup.okhttp3:okhttp:5.5.0", "com.squareup.okhttp3:okhttp:4.11.0") });
    expect(output).toContain("com.squareup.okhttp3:okhttp:5.5.0");
  });

  test("rejects TWA, WebView, Capacitor, and Firebase build dependencies", () => {
    for (const term of [
      "com.google.androidbrowserhelper:androidbrowserhelper:2.6.2",
      "androidx.webkit:webkit:1.13.0",
      "com.getcapacitor:capacitor-android:8.4.2",
      "com.google.firebase:firebase-messaging:24.0.0",
      "com.squareup.okhttp3:logging-interceptor:4.12.0",
    ]) {
      expect(messages({ appGradleText: `${validAppGradle}\nimplementation("${term}")` })).toContain(
        "forbidden browser/TWA/Capacitor/Firebase",
      );
    }
  });

  test("requires hardened application and launcher declarations", () => {
    const output = messages({
      androidManifestText: validManifest
        .replace('android:name=".CollieApplication"', 'android:name=".OtherApplication"')
        .replace('android:allowBackup="false"', 'android:allowBackup="true"')
        .replace('android:fullBackupContent="false"', 'android:fullBackupContent="true"')
        .replace('android:usesCleartextTraffic="false"', 'android:usesCleartextTraffic="true"')
        .replace('android:dataExtractionRules="@xml/data_extraction_rules"', "")
        .replace("android.intent.category.LAUNCHER", "android.intent.category.DEFAULT"),
    });
    for (const field of ["android:name", "android:allowBackup", "android:fullBackupContent", "android:usesCleartextTraffic", "android:dataExtractionRules", "launcher entry point"]) {
      expect(output).toContain(field);
    }
  });

  test("rejects unrelated permissions and exported entry points", () => {
    const output = messages({
      androidManifestText: validManifest
        .replace("</manifest>", '<uses-permission android:name="android.permission.CAMERA" /></manifest>')
        .replace("</application>", '<service android:name=".BrowserService" android:exported="true" /></application>'),
    });
    expect(output).toContain("may request only android.permission.INTERNET and android.permission.RECORD_AUDIO");
    expect(output).toContain("must export only com.lateapex.collie.ui.MainActivity");
  });

  test("allows only the pinned connector exports and Android-protected job service", () => {
    const service = `<service android:name="org.unifiedpush.android.connector.internal.RaiseToForegroundService" android:exported="true"><intent-filter><action android:name="org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"/></intent-filter></service>`;
    const receiver = `<receiver android:name="org.unifiedpush.android.connector.internal.MessagingReceiverImpl" android:exported="true"><intent-filter>${["MESSAGE", "UNREGISTERED", "NEW_ENDPOINT", "REGISTRATION_FAILED", "TEMP_UNAVAILABLE"].map((a) => `<action android:name="org.unifiedpush.android.connector.${a}"/>`).join("")}</intent-filter></receiver>`;
    const jobs = `<service android:name="androidx.work.impl.background.systemjob.SystemJobService" android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE"/>`;
    const privateSelfClosed = `<receiver android:name=".data.AndroidReleaseInstallReceiver" android:exported="false"/><service android:name=".PrivateService" android:exported="false"/>`;
    const merged = validManifest.replace("</application>", `${privateSelfClosed}${service}${receiver}${jobs}</application>`);
    expect(messages({ mergedAndroidManifestText: merged })).toBe("");
    for (const bad of [merged.replace("BIND_JOB_SERVICE", "INTERNET"), merged.replace("TEMP_UNAVAILABLE", "FOREIGN"), merged.replace("</receiver>", '<intent-filter><action android:name="android.intent.action.VIEW"/></intent-filter></receiver>')]) {
      expect(messages({ mergedAndroidManifestText: bad })).toContain("must export only");
    }
    expect(messages({ androidManifestText: merged })).toContain("must export only");
  });

  test("allows only the fixed exported transaction callback and retains unrelated export/browser refusals", () => {
    const callback = `<activity android:name=".ui.OpenLinkActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="com.lateapex.collie"/></intent-filter></activity>`;
    const manifest = validManifest.replace("</application>", `${callback}</application>`);
    expect(messages({ androidManifestText: manifest })).toBe("");
    const share = `<intent-filter><action android:name="android.intent.action.SEND"/><category android:name="android.intent.category.DEFAULT"/><data android:mimeType="text/plain"/></intent-filter>`;
    const sharedCallback = callback.replace("</activity>", `${share}</activity>`);
    expect(messages({ androidManifestText: validManifest.replace("</application>", `${sharedCallback}</application>`) })).toBe("");
    for (const badShare of [share.replace("<intent-filter>", '<intent-filter android:autoVerify="true">'), share.replace("text/plain", "*/*"), share.replace("SEND", "SEND_MULTIPLE"), share.replace("DEFAULT", "BROWSABLE"), share.replace('<data ', '<data android:scheme="https" '), share.replace('</intent-filter>', '<action android:name="android.intent.action.VIEW"/></intent-filter>'), share + share]) {
      expect(messages({ androidManifestText: validManifest.replace("</application>", `${callback.replace("</activity>", `${badShare}</activity>`)}</application>`) })).not.toBe("");
    }
    for (const changed of [callback.replace("com.lateapex.collie", "https"), callback.replace('<data ', '<data android:path="/oauth2redirect" '), callback.replace('<data ', '<data android:pathAdvancedPattern=".*" '), callback.replace('<data ', '<data android:port="443" '), callback.replace('<data ', '<data android:mimeType="text/plain" '), callback.replace('<data ', '<data android:host="foreign.example" '), callback.replace('</intent-filter>', '<action android:name="android.intent.action.SEND"/></intent-filter>')]) {
      expect(messages({ androidManifestText: validManifest.replace("</application>", `${changed}</application>`) })).toContain("native callback must accept only");
    }
    expect(messages({ androidManifestText: manifest.replace("</application>", '<service android:name=".Extra" android:exported="true"/></application>') })).toContain("must export only");
  });

  test("requires RECORD_AUDIO but continues to reject broad media access", () => {
    expect(
      messages({
        androidManifestText: validManifest.replace(
          '  <uses-permission android:name="android.permission.RECORD_AUDIO" />\n',
          "",
        ),
      }),
    ).toContain("must declare both");
    expect(
      messages({
        androidManifestText: validManifest.replace(
          "</manifest>",
          '<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" /></manifest>',
        ),
      }),
    ).toContain("READ_MEDIA_IMAGES");
  });

  test("rejects browser and verified-link intent filters", () => {
    const output = messages({
      androidManifestText: validManifest.replace(
        "</intent-filter>",
        '<action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/></intent-filter>',
      ),
    });
    expect(output).toContain("must not register browser or verified-link intent filters");
  });

  test("requires cloud-backup and device-transfer to exclude every data domain", () => {
    const output = messages({
      dataExtractionRulesText: validExtractionRules
        .replace('<exclude domain="database" path="."/>', "")
        .replace('<exclude domain="sharedpref" path="."/>', '<exclude domain="sharedpref" path="public.xml"/>')
        .replace(/<device-transfer>[\s\S]*?<\/device-transfer>/, ""),
    });
    expect(output).toContain("cloud-backup must exclude all database data");
    expect(output).toContain("cloud-backup must exclude all sharedpref data");
    expect(output).toContain("must declare device-transfer");
  });

  test("requires native entry-point sources", () => {
    expect(messages({ kotlinSources: [] })).toContain("CollieApplication.kt");
    expect(messages({ kotlinSources: [] })).toContain("ui/MainActivity.kt");
  });

  test("rejects leftover generated Java shell source", () => {
    expect(
      messages({
        javaSources: [
          { path: "android/app/src/main/java/com/lateapex/collie/LauncherActivity.java", text: "" },
        ],
      }),
    ).toContain("is legacy Java source");
  });

  test("rejects browser engines, native bridges, unsafe TLS, and logging in Kotlin", () => {
    const unsafe = [
      "android.webkit.WebView(context)",
      "@JavascriptInterface fun bridge() = Unit",
      "builder.hostnameVerifier { _, _ -> true }",
      "HttpLoggingInterceptor()",
      'android.util.Log.d("Collie", token)',
    ].join("\n");
    const output = messages({
      kotlinSources: [
        ...validInput().kotlinSources,
        { path: "android/app/src/main/java/com/lateapex/collie/Unsafe.kt", text: unsafe },
      ],
    });
    expect(output).toContain("forbidden browser/TWA/Capacitor/Firebase");
    expect(output).toContain("forbidden TLS-bypass hook");
    expect(output).toContain("must not add HTTP or Android runtime logging");
  });

  test("rejects removed Node and TWA artifacts", () => {
    const output = messages({ forbiddenArtifacts: ["android/package.json", "android/twa-manifest.json"] });
    expect(output).toContain("android/package.json is a removed TWA/Node artifact");
    expect(output).toContain("android/twa-manifest.json is a removed TWA/Node artifact");
  });
});
