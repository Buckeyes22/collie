#!/usr/bin/env bash
# Sign an already-built release APK. Credentials come from the environment, never argv or Git.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
tools="$sdk_root/build-tools/37.0.0"
unsigned="$repo_root/android/app/build/outputs/apk/release/app-release-unsigned.apk"
output="${COLLIE_ANDROID_RELEASE_DIR:-$repo_root/android/app/build/release-dist}"

die() { echo "android release: $1" >&2; exit 1; }
for name in COLLIE_ANDROID_KEYSTORE COLLIE_ANDROID_KEY_ALIAS COLLIE_ANDROID_STORE_PASSWORD \
  COLLIE_ANDROID_KEY_PASSWORD COLLIE_ANDROID_CERT_SHA256 COLLIE_ANDROID_METADATA_SIGNING_KEY \
  COLLIE_ANDROID_METADATA_SIGNING_PASSWORD; do
  [[ -n "${!name:-}" ]] || die "set $name before signing"
done
[[ -f "$COLLIE_ANDROID_KEYSTORE" ]] || die "release keystore file is missing"
[[ -f "$COLLIE_ANDROID_METADATA_SIGNING_KEY" ]] || die "metadata signing key file is missing"
[[ -x "$tools/apksigner" && -x "$tools/aapt" ]] || die "set ANDROID_HOME to an SDK with Build Tools 37.0.0"
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
min_sdk="$(sed -n "s/^sdkVersion:'\([0-9][0-9]*\)'.*/\1/p" <<< "$badging")"
[[ "$package" == com.lateapex.collie ]] || die "unexpected application ID"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "invalid Android versionName"
[[ "$code" =~ ^[1-9][0-9]*$ ]] || die "invalid Android versionCode"
[[ "$min_sdk" =~ ^[0-9]+$ ]] || die "could not read APK minSdkVersion"
[[ ! "$badging" =~ application-debuggable ]] || die "refusing to distribute a debuggable APK"
tag="android-v$version"
[[ -z "${COLLIE_ANDROID_EXPECTED_TAG:-}" || "$COLLIE_ANDROID_EXPECTED_TAG" == "$tag" ]] ||
  die "tag does not match the APK versionName"
expected_cert="$(tr '[:upper:]' '[:lower:]' <<< "$COLLIE_ANDROID_CERT_SHA256" | tr -d ':[:space:]')"
[[ "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || die "COLLIE_ANDROID_CERT_SHA256 must be a SHA-256 certificate fingerprint"

mkdir -p "$(dirname "$output")"
stage="$(mktemp -d "$(dirname "$output")/.android-release.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
metadata_pin="$repo_root/android/app/src/main/res/raw/android_release_metadata_public_key.pem"
[[ -f "$metadata_pin" ]] || die "bundled metadata verification key is missing"
openssl pkey -pubin -in "$metadata_pin" -text_pub -noout 2>/dev/null | grep -Fq 'ASN1 OID: prime256v1' ||
  die "bundled metadata verification key is not P-256"
openssl pkey -in "$COLLIE_ANDROID_METADATA_SIGNING_KEY" \
  -passin env:COLLIE_ANDROID_METADATA_SIGNING_PASSWORD -pubout \
  -out "$stage/metadata-signing-public.pem" 2>/dev/null || die "metadata signing key cannot be unlocked"
openssl pkey -pubin -in "$stage/metadata-signing-public.pem" -text_pub -noout 2>/dev/null | grep -Fq 'ASN1 OID: prime256v1' ||
  die "metadata signing key is not P-256"
pin_key_id="$(openssl pkey -pubin -in "$metadata_pin" -outform DER 2>/dev/null | sha256sum | cut -d ' ' -f 1)"
signer_key_id="$(openssl pkey -pubin -in "$stage/metadata-signing-public.pem" -outform DER 2>/dev/null | sha256sum | cut -d ' ' -f 1)"
[[ "$pin_key_id" =~ ^[0-9a-f]{64}$ && "$signer_key_id" =~ ^[0-9a-f]{64}$ ]] || die "could not identify metadata signing key"
chain_input="${COLLIE_ANDROID_METADATA_KEY_CHAIN:-$stage/empty-metadata-key-chain.json}"
if [[ -z "${COLLIE_ANDROID_METADATA_KEY_CHAIN:-}" ]]; then printf '[]' > "$chain_input"; fi
[[ -f "$chain_input" ]] || die "metadata key transition chain file is missing"
metadata_key_chain="$(bun "$repo_root/scripts/android-release-metadata-chain.ts" \
  "$metadata_pin" "$stage/metadata-signing-public.pem" "$chain_input" "$code" "$tag")" ||
  die "metadata key transition chain does not authorize the active key"
apk="collie-android-$version.apk"
"$tools/apksigner" sign --ks "$COLLIE_ANDROID_KEYSTORE" \
  --ks-key-alias "$COLLIE_ANDROID_KEY_ALIAS" \
  --ks-pass env:COLLIE_ANDROID_STORE_PASSWORD --key-pass env:COLLIE_ANDROID_KEY_PASSWORD \
  --v4-signing-enabled false --out "$stage/$apk" "$unsigned"
"$tools/apksigner" verify --verbose --print-certs "$stage/$apk" > "$stage/signing-certificate.txt" ||
  die "APK signature verification failed"
# Build Tools 37 names a sole signer by its verified scheme (V1, V2 or V3.0).
# Older tools used Signer #1. Source Stamp is separate custody, never the APK signer.
# Require one declared signer and exactly one recognized certificate; rotation/multiple
# certificate output is refused rather than guessing which certificate should match the pin.
actual_cert="$(awk '
  /^Number of signers: / { counts++; if ($0 != "Number of signers: 1") invalid = 1 }
  /certificate SHA-256 digest:/ {
    if ($0 ~ /^Source Stamp Signer: certificate SHA-256 digest: /) next
    if ($0 !~ /^(Signer #1 certificate|Signer #1: certificate|V(1|2|3[.]0) Signer: certificate) SHA-256 digest: /) {
      invalid = 1; next
    }
    value = $0
    sub(/^.* certificate SHA-256 digest: /, "", value)
    if (length(value) != 64 || value !~ /^[0-9a-fA-F]+$/) invalid = 1
    certificates++; digest = tolower(value)
  }
  END { if (counts != 1 || certificates != 1 || invalid) exit 1; print digest }
' "$stage/signing-certificate.txt")" || die "expected exactly one verified APK signer certificate"
[[ "$actual_cert" == "$expected_cert" ]] || die "signing certificate does not match the pinned fingerprint"
bun "$repo_root/scripts/android-sbom.ts" "$stage/$apk" "$version" "$stage/sbom.cdx.json"
commit="$(git -C "$repo_root" rev-parse HEAD)"
digest="$(sha256sum "$stage/$apk" | cut -d ' ' -f 1)"
apk_size="$(wc -c < "$stage/$apk" | tr -d '[:space:]')"
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
  "signingCertificateSha256": "$actual_cert",
  "apkSizeBytes": $apk_size,
  "minSdk": $min_sdk,
  "metadataKeyId": "$signer_key_id",
  "metadataKeyChain": $metadata_key_chain
}
EOF
(
  openssl dgst -sha256 -sign "$COLLIE_ANDROID_METADATA_SIGNING_KEY" \
    -passin env:COLLIE_ANDROID_METADATA_SIGNING_PASSWORD \
    -out "$stage/release-metadata.signature.der" "$stage/release-metadata.json" 2>/dev/null
  unset COLLIE_ANDROID_METADATA_SIGNING_PASSWORD
  openssl dgst -sha256 -verify "$stage/metadata-signing-public.pem" \
    -signature "$stage/release-metadata.signature.der" "$stage/release-metadata.json" >/dev/null 2>&1 ||
    die "metadata signature self-verification failed"
  base64 -w0 "$stage/release-metadata.signature.der" | tr '+/' '-_' | tr -d '=' > "$stage/release-metadata.sig"
)
rm -f "$stage/release-metadata.signature.der" "$stage/metadata-signing-public.pem"
(cd "$stage" && sha256sum ./* > SHA256SUMS)
mv "$stage" "$output"
echo "Signed Android release files: $output"
