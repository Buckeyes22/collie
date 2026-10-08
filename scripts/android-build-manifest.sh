#!/usr/bin/env bash
# Record what produced the unsigned release APK. Run immediately after assembleRelease, on a clean tree.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
release="$repo_root/android/app/build/outputs/apk/release"
die() { echo "android build manifest: $1" >&2; exit 1; }
[[ -z "$(git -C "$repo_root" status --porcelain)" ]] || die "commit or stash changes before recording build inputs"
[[ -f "$release/app-release-unsigned.apk" ]] || die "run android/gradlew assembleRelease first"
cat > "$release/build-inputs.json" <<EOF
{
  "sourceCommit": "$(git -C "$repo_root" rev-parse HEAD)",
  "lockfileSha256": "$(sha256sum "$repo_root/android/app/gradle.lockfile" | cut -d ' ' -f 1)",
  "unsignedApkSha256": "$(sha256sum "$release/app-release-unsigned.apk" | cut -d ' ' -f 1)"
}
EOF
echo "Recorded build inputs: $release/build-inputs.json"
