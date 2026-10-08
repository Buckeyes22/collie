#!/usr/bin/env bash
# scripts/android-release.test.sh — drives android-release.sh against a stub SDK in a scratch repo.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $1" >&2; exit 1; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cert="$(printf 'a%.0s' {1..64})"
release_dir="$work/repo/android/app/build/outputs/apk/release"

setup() {
  rm -rf "$work/repo" "$work/out" "$work/sdk"
  mkdir -p "$work/repo/scripts" "$work/repo/android/app/src/main/res/raw" "$release_dir" \
    "$work/sdk/build-tools/36.0.0"
  cp "$here"/scripts/android-{release.sh,build-manifest.sh,sbom.ts} "$work/repo/scripts/"
  cp "$here/LICENSE" "$work/repo/"; cp "$here/android/app/gradle.lockfile" "$work/repo/android/app/"
  cp "$here"/android/app/src/main/res/raw/*.txt "$work/repo/android/app/src/main/res/raw/"
  printf 'build/\n' > "$work/repo/android/app/.gitignore"
  cat > "$work/sdk/build-tools/36.0.0/aapt" <<'EOF'
#!/usr/bin/env bash
echo "package: name='com.lateapex.collie' versionCode='2' versionName='1.5.1'"
EOF
  # Stub apksigner: `sign ... --out <path> <input>` copies <input> to <path>; `verify` prints the signer.
  cat > "$work/sdk/build-tools/36.0.0/apksigner" <<EOF
#!/usr/bin/env bash
if [[ \$1 == sign ]]; then
  out=""; prev=""
  for arg in "\$@"; do [[ \$prev == --out ]] && out="\$arg"; prev="\$arg"; done
  [[ -n \$out ]] || { echo "stub apksigner: no --out" >&2; exit 2; }
  cp "\${@: -1}" "\$out"
  exit 0
fi
echo "Signer #1 certificate SHA-256 digest: $cert"
EOF
  chmod +x "$work/sdk/build-tools/36.0.0/"*
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
  bash "$work/repo/scripts/android-release.sh"
}
manifest() { bash "$work/repo/scripts/android-build-manifest.sh" >/dev/null; }

# The stub must really copy its input, or every later case proves nothing.
setup
"$work/sdk/build-tools/36.0.0/apksigner" sign --ks k --out "$work/stub-out.apk" "$release_dir/app-release-unsigned.apk"
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
echo "android-release.test.sh: 6 cases passed"
