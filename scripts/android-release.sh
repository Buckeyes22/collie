#!/usr/bin/env bash
# Sign an already-built release APK. Credentials come from the environment, never argv or Git.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools="$sdk_root/build-tools/36.0.0"
unsigned="$repo_root/android/app/build/outputs/apk/release/app-release-unsigned.apk"
output="${COLLIE_ANDROID_RELEASE_DIR:-$repo_root/android/app/build/release-dist}"

die() { echo "android release: $1" >&2; exit 1; }
for name in COLLIE_ANDROID_KEYSTORE COLLIE_ANDROID_KEY_ALIAS COLLIE_ANDROID_STORE_PASSWORD \
  COLLIE_ANDROID_KEY_PASSWORD COLLIE_ANDROID_CERT_SHA256; do
  [[ -n "${!name:-}" ]] || die "set $name before signing"
done
[[ -f "$COLLIE_ANDROID_KEYSTORE" ]] || die "release keystore file is missing"
[[ -x "$tools/apksigner" && -x "$tools/aapt" ]] || die "set ANDROID_HOME to an SDK with Build Tools 36.0.0"
[[ -f "$unsigned" ]] || die "run android/gradlew assembleRelease first"
manifest="$(dirname "$unsigned")/build-inputs.json"
[[ -f "$manifest" ]] || die "run scripts/android-build-manifest.sh after assembleRelease"
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || die "refusing to sign from a dirty worktree"
field() { sed -n "s/^  \"$1\": \"\([0-9a-f]*\)\",\{0,1\}$/\1/p" "$manifest"; }
[[ "$(field sourceCommit)" == "$(git -C "$repo_root" rev-parse HEAD)" ]] || die "HEAD is not the commit that built this APK"
[[ "$(field lockfileSha256)" == "$(sha256sum "$repo_root/android/app/gradle.lockfile" | cut -d ' ' -f 1)" ]] ||
  die "gradle.lockfile changed since the APK was built"
[[ "$(field unsignedApkSha256)" == "$(sha256sum "$unsigned" | cut -d ' ' -f 1)" ]] ||
  die "the unsigned APK is not the one the manifest recorded"
command -v bun >/dev/null || die "install Bun to generate the release SBOM"
[[ ! -e "$output" ]] || die "release output already exists; choose a new COLLIE_ANDROID_RELEASE_DIR"

badging="$("$tools/aapt" dump badging "$unsigned")"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
version="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")"
code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")"
[[ "$package" == com.lateapex.collie ]] || die "unexpected application ID"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "invalid Android versionName"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || die "invalid Android versionCode"
[[ ! "$badging" =~ application-debuggable ]] || die "refusing to distribute a debuggable APK"
tag="android-v$version"
[[ -z "${COLLIE_ANDROID_EXPECTED_TAG:-}" || "$COLLIE_ANDROID_EXPECTED_TAG" == "$tag" ]] ||
  die "tag does not match the APK versionName"
expected_cert="$(tr '[:upper:]' '[:lower:]' <<< "$COLLIE_ANDROID_CERT_SHA256" | tr -d ':[:space:]')"
[[ "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || die "COLLIE_ANDROID_CERT_SHA256 must be a SHA-256 certificate fingerprint"

mkdir -p "$(dirname "$output")"
stage="$(mktemp -d "$(dirname "$output")/.android-release.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
apk="collie-android-$version.apk"
"$tools/apksigner" sign --ks "$COLLIE_ANDROID_KEYSTORE" \
  --ks-key-alias "$COLLIE_ANDROID_KEY_ALIAS" \
  --ks-pass env:COLLIE_ANDROID_STORE_PASSWORD --key-pass env:COLLIE_ANDROID_KEY_PASSWORD \
  --v4-signing-enabled false --out "$stage/$apk" "$unsigned"
"$tools/apksigner" verify --verbose --print-certs "$stage/$apk" > "$stage/signing-certificate.txt"
actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$stage/signing-certificate.txt")"
[[ "$actual_cert" == "$expected_cert" ]] || die "signing certificate does not match the pinned fingerprint"
bun "$repo_root/scripts/android-sbom.ts" "$stage/$apk" "$version" "$stage/sbom.cdx.json"
commit="$(git -C "$repo_root" rev-parse HEAD)"
digest="$(sha256sum "$stage/$apk" | cut -d ' ' -f 1)"
cp "$repo_root/LICENSE" "$stage/LICENSE.txt"
cp "$repo_root/android/app/src/main/res/raw/third_party_notices.txt" "$stage/"
cp "$repo_root/android/app/src/main/res/raw/dependency_notices.txt" "$stage/"
cp "$repo_root"/android/app/src/main/res/raw/license_*.txt "$stage/"
cp "$manifest" "$stage/"
cat > "$stage/release-metadata.json" <<EOF
{
  "tag": "$tag",
  "applicationId": "$package",
  "versionName": "$version",
  "versionCode": $code,
  "sourceCommit": "$commit",
  "buildInputs": $(cat "$manifest"),
  "buildInputsVerified": true,
  "apk": "$apk",
  "sha256": "$digest",
  "signingCertificateSha256": "$actual_cert"
}
EOF
(cd "$stage" && sha256sum ./* > SHA256SUMS)
mv "$stage" "$output"
echo "Signed Android release files: $output"
