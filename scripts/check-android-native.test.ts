import { describe, expect, test } from "bun:test";

import {
  type AndroidNativeValidationInput,
  validateAndroidNative,
} from "./check-android-native.ts";

const validRootGradle = `plugins {
  id("com.android.application") version "8.9.1" apply false
  id("org.jetbrains.kotlin.android") version "2.0.21" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
dependencyLocking { lockAllConfigurations() }
`;

const validAppGradle = `plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization")
}
android {
  namespace = "com.lateapex.collie"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.lateapex.collie"
    minSdk = 26
    targetSdk = 36
    versionCode = 3
    versionName = "1.5.2"
    buildConfigField("String", "DEFAULT_ORIGIN", "\\"\\"")
  }
  buildFeatures { viewBinding = true }
  buildTypes { release { isMinifyEnabled = true; isDebuggable = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
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
    rootGradleText: validRootGradle,
    appGradleText: validAppGradle,
    wrapperPropertiesText:
      "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip",
    androidManifestText: validManifest,
    dataExtractionRulesText: validExtractionRules,
    kotlinSources: [
      { path: "android/app/src/main/java/com/lateapex/collie/CollieApplication.kt", text: "class CollieApplication" },
      { path: "android/app/src/main/java/com/lateapex/collie/ui/MainActivity.kt", text: "class MainActivity" },
    ],
    javaSources: [],
    dependencyLockfiles: [
      { path: "android/app/gradle.lockfile", text: "com.squareup.okhttp3:okhttp:4.12.0=debugRuntimeClasspath" },
    ],
    ...overrides,
  };
}

function messages(overrides: Partial<AndroidNativeValidationInput> = {}): string {
  return validateAndroidNative(validInput(overrides)).errors.join("\n");
}

describe("check-android-native validation", () => {
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
      rootGradleText: validRootGradle.replace("8.9.1", "8.8.0").replaceAll("2.0.21", "2.1.0"),
      wrapperPropertiesText: "distributionUrl=gradle-8.10-bin.zip",
    });
    expect(output).toContain("include only :app");
    expect(output).toContain("com.android.application 8.9.1");
    expect(output).toContain("org.jetbrains.kotlin.android 2.0.21");
    expect(output).toContain("serialization 2.0.21");
    expect(output).toContain("Gradle wrapper must resolve to 8.11.1");
  });

  test("rejects application, SDK, and version drift", () => {
    const appGradle = validAppGradle
      .replaceAll("com.lateapex.collie", "com.example.other")
      .replace("compileSdk = 36", "compileSdk = 35")
      .replace("minSdk = 26", "minSdk = 24")
      .replace("targetSdk = 36", "targetSdk = 35")
      .replace("versionCode = 3", "versionCode = 4")
      .replace('versionName = "1.5.2"', 'versionName = "1.5.3"');
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
    const output = messages({ appGradleText: validAppGradle.replace("com.squareup.okhttp3:okhttp:4.12.0", "com.squareup.okhttp3:okhttp:4.11.0") });
    expect(output).toContain("com.squareup.okhttp3:okhttp:4.12.0");
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

  test("allows only network and explicit-recording permissions and only the native launcher export", () => {
    const output = messages({
      androidManifestText: validManifest
        .replace("</manifest>", '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /></manifest>')
        .replace("</application>", '<service android:name=".BrowserService" android:exported="true" /></application>'),
    });
    expect(output).toContain("may request only android.permission.INTERNET and android.permission.RECORD_AUDIO");
    expect(output).toContain("must export only com.lateapex.collie.ui.MainActivity");
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
