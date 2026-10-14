#!/usr/bin/env bash
# scripts/android-release.test.sh — drives android-release.sh against a stub SDK in a scratch repo.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $1" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cert="$(printf 'a%.0s' {1..64})"
other_cert="$(printf 'b%.0s' {1..64})"
release_dir="$work/repo/android/app/build/outputs/apk/release"
# Synthetic metadata custody exercises the real signing path without production credentials.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc \
  -pass pass:fixture -out "$work/metadata-key.pem" 2>/dev/null
openssl pkey -in "$work/metadata-key.pem" -passin pass:fixture -pubout \
  -out "$work/metadata-public.pem" 2>/dev/null

setup() {
  rm -rf "$work/repo" "$work/out" "$work/sdk"
  rm -f "$work/verify-fail"
  # Real SDK 37 sole-signer labels, plus an unrelated source-stamp certificate.
  cat > "$work/verify-output.txt" <<EOF
Verifies
Number of signers: 1
V3.0 Signer: certificate SHA-256 digest: $cert
Source Stamp Signer: certificate SHA-256 digest: $other_cert
EOF
  mkdir -p "$work/repo/scripts" "$work/repo/android/app/src/main/res/raw" \
    "$work/repo/android/app/src/main/cpp" "$release_dir" \
    "$work/sdk/build-tools/37.0.0"
  cp "$here"/scripts/android-{release.sh,build-manifest.sh,sbom.ts,release-metadata-chain.ts} "$work/repo/scripts/"
  cp "$here/LICENSE" "$work/repo/"; cp "$here/android/app/gradle.lockfile" "$work/repo/android/app/"
  cp "$here"/android/app/src/main/res/raw/*.txt "$work/repo/android/app/src/main/res/raw/"
  cp "$work/metadata-public.pem" "$work/repo/android/app/src/main/res/raw/android_release_metadata_public_key.pem"
  # The release SBOM verifies the exact vendored native inputs, including their manifests/licenses.
  cp -R "$here/android/app/src/main/cpp/third_party" "$work/repo/android/app/src/main/cpp/"
  printf 'build/\n' > "$work/repo/android/app/.gitignore"
  cat > "$work/sdk/build-tools/37.0.0/aapt" <<'EOF'
#!/usr/bin/env bash
echo "package: name='com.lateapex.collie' versionCode='3' versionName='1.5.2'"
echo "sdkVersion:'37'"
EOF
  # Stub apksigner: `sign ... --out <path> <input>` copies <input> to <path>; `verify` prints the signer.
  cat > "$work/sdk/build-tools/37.0.0/apksigner" <<EOF
#!/usr/bin/env bash
if [[ \$1 == sign ]]; then
  out=""; prev=""
  for arg in "\$@"; do [[ \$prev == --out ]] && out="\$arg"; prev="\$arg"; done
  [[ -n \$out ]] || { echo "stub apksigner: no --out" >&2; exit 2; }
  cp "\${@: -1}" "\$out"
  exit 0
fi
if [[ -f "$work/verify-fail" ]]; then echo 'DOES NOT VERIFY'; exit 1; fi
cat "$work/verify-output.txt"
EOF
  chmod +x "$work/sdk/build-tools/37.0.0/"*
  git -C "$work/repo" init -q
  git -C "$work/repo" add -A
  git -C "$work/repo" -c user.email=t@t -c user.name=t commit -qm init
  echo apk > "$release_dir/app-release-unsigned.apk"
  : > "$work/ks"
}
sign() {
  ANDROID_HOME="$work/sdk" COLLIE_ANDROID_KEYSTORE="$work/ks" COLLIE_ANDROID_KEY_ALIAS=a \
  COLLIE_ANDROID_STORE_PASSWORD=p COLLIE_ANDROID_KEY_PASSWORD=p \
  COLLIE_ANDROID_CERT_SHA256="$cert" COLLIE_ANDROID_RELEASE_DIR="$work/out" \
  COLLIE_ANDROID_METADATA_SIGNING_KEY="$work/metadata-key.pem" \
  COLLIE_ANDROID_METADATA_SIGNING_PASSWORD=fixture COLLIE_ANDROID_METADATA_KEY_CHAIN="" \
  COLLIE_ANDROID_EXPECTED_TAG="" \
  bash "$work/repo/scripts/android-release.sh"
}
manifest() { bash "$work/repo/scripts/android-build-manifest.sh" >/dev/null; }

# The stub must really copy its input, or every later case proves nothing.
setup
"$work/sdk/build-tools/37.0.0/apksigner" sign --ks k --out "$work/stub-out.apk" "$release_dir/app-release-unsigned.apk"
cmp -s "$work/stub-out.apk" "$release_dir/app-release-unsigned.apk" || fail "stub apksigner did not copy its input"

setup; sign 2>/dev/null && fail "signed without a build-inputs manifest"
setup; manifest
echo swapped > "$release_dir/app-release-unsigned.apk"
sign 2>/dev/null && fail "signed an APK rebuilt after the manifest"
setup; manifest; echo x >> "$work/repo/LICENSE"
sign 2>/dev/null && fail "signed from a dirty tree"
setup; echo x >> "$work/repo/LICENSE"
manifest 2>/dev/null && fail "wrote a manifest from a dirty tree"
setup; manifest; sign >/dev/null
meta="$work/out/release-metadata.json"
grep -q '"buildInputsVerified": true' "$meta" || fail "metadata lacks verified build inputs"
grep -q worktreeDirty "$meta" && fail "metadata still carries worktreeDirty"
bun -e 'await Bun.write(process.argv[2], Buffer.from(await Bun.file(process.argv[1]).text(), "base64url"));' \
  "$work/out/release-metadata.sig" "$work/metadata.signature.der"
openssl dgst -sha256 -verify "$work/metadata-public.pem" \
  -signature "$work/metadata.signature.der" "$meta" >/dev/null 2>&1 || fail "metadata signature does not verify"
bun -e 'const sbom = await Bun.file(process.argv[1]).json(); for (const name of ["woff2", "brotli"]) { if (!sbom.components.some(component => component.name === name)) throw new Error("Missing native component: " + name); }' \
  "$work/out/sbom.cdx.json"
grep -q "\"signingCertificateSha256\": \"$cert\"" "$meta" || fail "metadata used the source stamp instead of the APK certificate"

# Every accepted label must identify the same one verified signer; version/build gates stay real.
for label in 'Signer #1' 'Signer #1:' 'V1 Signer:' 'V2 Signer:'; do
  setup; manifest
  printf 'Verifies\nNumber of signers: 1\n%s certificate SHA-256 digest: %s\n' "$label" "$cert" > "$work/verify-output.txt"
  sign >/dev/null || fail "rejected supported one-signer label $label"
done

setup; manifest
printf 'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: %s\nSource Stamp Signer: certificate SHA-256 digest: %s\n' "$other_cert" "$cert" > "$work/verify-output.txt"
sign 2>/dev/null && fail "accepted wrong APK certificate because the source stamp matched"
[[ ! -e "$work/out" ]] || fail "published output after wrong APK certificate"

setup; manifest
printf 'Verifies\nNumber of signers: 2\nV2 Signer #1: certificate SHA-256 digest: %s\nV2 Signer #2: certificate SHA-256 digest: %s\n' "$cert" "$other_cert" > "$work/verify-output.txt"
sign 2>/dev/null && fail "accepted a multiple-signer APK"

setup; manifest
printf 'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: %s\nSigner #2 certificate SHA-256 digest: %s\n' "$cert" "$other_cert" > "$work/verify-output.txt"
sign 2>/dev/null && fail "accepted undeclared extra signer output"

setup; manifest
printf 'Verifies\nV3.0 Signer: certificate SHA-256 digest: %s\n' "$cert" > "$work/verify-output.txt"
sign 2>/dev/null && fail "accepted output without a verified signer count"

setup; manifest
printf 'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: %s\nV3.1 Signer: certificate SHA-256 digest: %s\n' "$cert" "$other_cert" > "$work/verify-output.txt"
sign 2>/dev/null && fail "guessed a signer from a rotation certificate set"

setup; manifest
printf 'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: malformed\n' > "$work/verify-output.txt"
sign 2>/dev/null && fail "accepted a malformed SHA-256 digest"

setup; manifest
touch "$work/verify-fail"
sign 2>/dev/null && fail "accepted failed apksigner verification"
echo "android-release.test.sh: 17 cases passed"
