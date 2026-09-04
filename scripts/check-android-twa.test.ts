import { describe, expect, test } from "bun:test";

import {
  type AndroidTwaValidationInput,
  validateAndroidTwa,
} from "./check-android-twa.ts";

const PACKAGE_ID = "com.lateapex.collie";
const FINGERPRINT = Array.from({ length: 32 }, () => "AB").join(":");

type TwaFixtureValue = boolean | number | string | string[] | { alias: string; path: string };

function twa(overrides: Record<string, TwaFixtureValue> = {}): string {
  return JSON.stringify({
    name: "Collie",
    launcherName: "Collie",
    packageId: PACKAGE_ID,
    host: "ed8.taile7b6b1.ts.net",
    startUrl: "/",
    scope: "/",
    enableNotifications: true,
    fallbackType: "customtabs",
    additionalTrustedOrigins: [],
    appVersion: "1.5.0",
    appVersionCode: 1,
    enableAndroidBackup: false,
    signingKey: { path: "./.local/signing/collie-release.keystore", alias: "collie-release" },
    ...overrides,
  });
}

function dal(
  packageName = PACKAGE_ID,
  fingerprint = FINGERPRINT,
): string {
  return JSON.stringify([
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: packageName,
        sha256_cert_fingerprints: [fingerprint],
      },
    },
  ]);
}

function validInput(overrides: Partial<AndroidTwaValidationInput> = {}): AndroidTwaValidationInput {
  const source = dal();
  return {
    twaManifestText: twa(),
    androidManifestText:
      '<manifest><uses-permission android:name="android.permission.POST_NOTIFICATIONS" />' +
      '<application android:allowBackup="false" android:usesCleartextTraffic="false">' +
      '<activity android:name="LauncherActivity" android:exported="true"><intent-filter android:autoVerify="true" /></activity>' +
      '<service android:name=".DelegationService" android:enabled="@bool/enableNotification" android:exported="@bool/enableNotification">' +
      '<meta-data android:name="android.support.customtabs.trusted.SMALL_ICON" android:resource="@drawable/ic_notification_icon" />' +
      '<intent-filter><action android:name="android.support.customtabs.trusted.TRUSTED_WEB_ACTIVITY_SERVICE" /></intent-filter>' +
      '</service>' +
      '</application></manifest>',
    androidStringsText:
      '<resources><string name="assetStatements">[{\\"target\\":{\\"site\\":\\"https://ed8.taile7b6b1.ts.net\\"}}]</string></resources>',
    gradleFiles: [
      {
        path: "android/app/build.gradle",
        text: `def twaManifest = [hostName: 'ed8.taile7b6b1.ts.net', launchUrl: '/']
android {
  compileSdkVersion 36
  defaultConfig {
    applicationId "${PACKAGE_ID}"
    targetSdkVersion 36
    versionCode 1
    versionName "1.5.0"
  }
}\n`,
      },
    ],
    assetLinksSourceText: source,
    assetLinksBuiltText: source,
    ...overrides,
  };
}

function messages(input: AndroidTwaValidationInput): string {
  return validateAndroidTwa(input).errors.join("\n");
}

describe("check-android-twa validation", () => {
  test("accepts the expected TWA, Gradle, and DAL configuration", () => {
    const result = validateAndroidTwa(validInput());
    expect(result).toEqual({ errors: [], checkedAssetLinks: true });
  });

  test("accepts Bubblewrap's absolute fullScopeUrl schema", () => {
    // SAFETY: twa() serializes exactly the scalar-and-array record declared by its return fixture.
    const manifest = JSON.parse(twa()) as Record<string, TwaFixtureValue>;
    delete manifest.scope;
    manifest.fullScopeUrl = "https://ed8.taile7b6b1.ts.net/";
    expect(messages(validInput({ twaManifestText: JSON.stringify(manifest) }))).toBe("");
  });

  test("reports malformed TWA and DAL JSON", () => {
    expect(messages(validInput({ twaManifestText: "{" }))).toContain(
      "android/twa-manifest.json is malformed JSON",
    );
    expect(messages(validInput({ assetLinksSourceText: "[" }))).toContain(
      "web/public/.well-known/assetlinks.json is malformed JSON",
    );
  });

  test("rejects a placeholder fingerprint", () => {
    expect(messages(validInput({ assetLinksSourceText: dal(PACKAGE_ID, "REPLACE_WITH_SHA256") }))).toContain(
      "placeholder fingerprint",
    );
  });

  test("rejects lowercase and malformed fingerprints", () => {
    for (const fingerprint of [FINGERPRINT.toLowerCase(), "AB:CD", FINGERPRINT.replace(/:/, "")]) {
      expect(messages(validInput({ assetLinksSourceText: dal(PACKAGE_ID, fingerprint) }))).toContain(
        "32 uppercase colon-separated hex bytes",
      );
    }
  });

  test("rejects package drift in DAL or Gradle", () => {
    expect(messages(validInput({ assetLinksSourceText: dal("com.example.other") }))).toContain(
      "package_name must match",
    );
    expect(
      messages(
        validInput({
          gradleFiles: [
            {
              path: "android/app/build.gradle.kts",
              text: 'def twaManifest = [hostName: "ed8.taile7b6b1.ts.net", launchUrl: "/"]; android { compileSdk = 36; defaultConfig { applicationId = "com.example.other"; targetSdk = 36; versionCode = 1; versionName = "1.5.0" } }',
            },
          ],
        }),
      ),
    ).toContain("applicationId must match");
  });

  test("rejects extra DAL relations and non-Android targets", () => {
    const extraRelation = dal().replace(
      '"delegate_permission/common.handle_all_urls"',
      '"delegate_permission/common.get_login_creds"',
    );
    expect(messages(validInput({ assetLinksSourceText: extraRelation }))).toContain(
      "must contain only relation",
    );
    const webTarget = dal().replace('"namespace":"android_app"', '"namespace":"web"');
    expect(messages(validInput({ assetLinksSourceText: webTarget }))).toContain(
      'target.namespace must be "android_app"',
    );
  });

  test("rejects a wrong host, start URL, or scope", () => {
    expect(messages(validInput({ twaManifestText: twa({ host: "other.example" }) }))).toContain(
      "host must be",
    );
    expect(messages(validInput({ twaManifestText: twa({ startUrl: "/other" }) }))).toContain(
      "startUrl must be",
    );
    expect(messages(validInput({ twaManifestText: twa({ scope: "/other/" }) }))).toContain(
      "scope must be",
    );
  });

  test("rejects disabled notification delegation", () => {
    expect(messages(validInput({ twaManifestText: twa({ enableNotifications: false }) }))).toContain(
      "enableNotifications must be true",
    );
  });

  test("rejects WebView or any fallback other than Custom Tabs", () => {
    expect(messages(validInput({ twaManifestText: twa({ fallbackType: "webview" }) }))).toContain(
      'fallbackType must be "customtabs"',
    );
  });

  test("rejects source-version drift", () => {
    expect(messages(validInput({ twaManifestText: twa({ appVersion: "1.5.1" }) }))).toContain(
      "appVersion must be",
    );
    expect(messages(validInput({ twaManifestText: twa({ appVersionCode: 2 }) }))).toContain(
      "appVersionCode must be 1",
    );
    const wrongGradleVersion = validInput();
    wrongGradleVersion.gradleFiles = [
      {
        path: "android/app/build.gradle",
        text: `def twaManifest = [hostName: 'ed8.taile7b6b1.ts.net', launchUrl: '/']; android { compileSdkVersion 36; defaultConfig { applicationId "${PACKAGE_ID}"; targetSdkVersion 36; versionCode 2; versionName "1.5.1" } }`,
      },
    ];
    expect(messages(wrongGradleVersion)).toContain("Gradle versionName must be 1.5.0");
    expect(messages(wrongGradleVersion)).toContain("Gradle versionCode must be 1");
  });

  test("rejects enabled Android backup and a WebView fallback activity", () => {
    expect(messages(validInput({ twaManifestText: twa({ enableAndroidBackup: true }) }))).toContain(
      "must disable Android backup",
    );
    expect(
      messages(
        validInput({
          androidManifestText:
            '<manifest><application android:allowBackup="true" android:usesCleartextTraffic="false"><activity android:name="WebViewFallbackActivity" /></application></manifest>',
        }),
      ),
    ).toContain('android:allowBackup="false"');
    expect(
      messages(
        validInput({
          androidManifestText:
            '<manifest><application android:allowBackup="false" android:usesCleartextTraffic="false"><activity android:name="WebViewFallbackActivity" /></application></manifest>',
        }),
      ),
    ).toContain("must not register a WebView");
  });

  test("requires notification delegation and verified-launch manifest wiring", () => {
    const manifest =
      '<manifest><application android:allowBackup="false" android:usesCleartextTraffic="false" /></manifest>';
    const output = messages(validInput({ androidManifestText: manifest }));
    expect(output).toContain("POST_NOTIFICATIONS");
    expect(output).toContain("must export only the Collie LauncherActivity");
    expect(output).toContain("must enable and export DelegationService");
    expect(output).toContain("TRUSTED_WEB_ACTIVITY_SERVICE");
    expect(output).toContain("SMALL_ICON");
    expect(output).toContain("autoVerify HTTPS intent filter");
  });

  test("rejects drift in Gradle launch identity or Android asset statements", () => {
    const wrongGradle = validInput();
    wrongGradle.gradleFiles = wrongGradle.gradleFiles.map((file) => ({
      ...file,
      text: file.text.replace("ed8.taile7b6b1.ts.net", "other.example").replace("launchUrl: '/'", "launchUrl: '/other'"),
    }));
    const gradleOutput = messages(wrongGradle);
    expect(gradleOutput).toContain("Gradle hostName must be");
    expect(gradleOutput).toContain("Gradle launchUrl must be /");
    expect(
      messages(
        validInput({
          androidStringsText:
            '<resources><string name="assetStatements">[{\\"target\\":{\\"site\\":\\"https://other.example\\"}}]</string></resources>',
        }),
      ),
    ).toContain("assetStatements must name only");
  });

  test("rejects cleartext, unexpected permissions, and unexpected exported components", () => {
    const manifest =
      '<manifest><uses-permission android:name="android.permission.CAMERA" />' +
      '<application android:allowBackup="false" android:usesCleartextTraffic="true">' +
      '<service android:name=".UnexpectedService" android:exported="true" />' +
      '</application></manifest>';
    const output = messages(validInput({ androidManifestText: manifest }));
    expect(output).toContain('android:usesCleartextTraffic="false"');
    expect(output).toContain("android.permission.CAMERA");
    expect(output).toContain("unexpected exported value");

    const profileReceiver = validInput();
    profileReceiver.androidManifestText = profileReceiver.androidManifestText.replace(
      "</application>",
      '<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:permission="android.permission.DUMP" android:exported="true" /></application>',
    );
    expect(messages(profileReceiver)).toContain("androidx.profileinstaller.ProfileInstallReceiver");
  });

  test("rejects absolute or placeholder signing-key paths", () => {
    for (const path of [
      "/home/user/collie.jks",
      "C:\\Users\\user\\collie.jks",
      "../collie.jks",
      "file:///keys/collie.jks",
      "REPLACE_WITH_KEYSTORE",
    ]) {
      expect(
        messages(
          validInput({
            twaManifestText: twa({
              signingKey: { path, alias: "collie-release" },
            }),
          }),
        ),
      ).toContain("signingKey.path must be an inert");
    }
  });

  test("rejects compile or target SDK below API 36", () => {
    const lowSdk = validInput();
    lowSdk.gradleFiles = [
      {
        path: "android/app/build.gradle",
        text: `def twaManifest = [hostName: 'ed8.taile7b6b1.ts.net', launchUrl: '/']; android { compileSdkVersion 35; defaultConfig { applicationId "${PACKAGE_ID}"; targetSdkVersion 35; versionCode 1; versionName "1.5.0" } }`,
      },
    ];
    const output = messages(lowSdk);
    expect(output).toContain("compile SDK must resolve to API 36");
    expect(output).toContain("target SDK must resolve to API 36");
  });

  test("rejects source/build DAL drift", () => {
    expect(messages(validInput({ assetLinksBuiltText: `${dal()}\n` }))).toContain(
      "differs from web/public",
    );
    expect(messages(validInput({ assetLinksBuiltText: undefined, webDistExists: true }))).toContain(
      "web/dist/.well-known/assetlinks.json is missing",
    );
  });

  test("allows the DAL source to be absent before the release certificate exists", () => {
    const result = validateAndroidTwa(
      validInput({ assetLinksSourceText: undefined, assetLinksBuiltText: undefined }),
    );
    expect(result).toEqual({ errors: [], checkedAssetLinks: false });
  });

  test("release-strict validation refuses an absent DAL source", () => {
    const result = validateAndroidTwa(
      validInput({
        assetLinksSourceText: undefined,
        assetLinksBuiltText: undefined,
        requireAssetLinks: true,
      }),
    );
    expect(result.checkedAssetLinks).toBeFalse();
    expect(result.errors.join("\n")).toContain("release validation requires");
  });
});
