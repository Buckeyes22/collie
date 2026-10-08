#!/usr/bin/env bash
# Rebuild the bundled Nerd Font symbol subsets in web/public/fonts/.
#
# NOT part of the build. The .woff2 files are committed, because a webfont is a release artifact,
# not a build step: this script needs Python + fonttools + brotli, and requiring those to build
# Collie would be a worse trade than 1.1 MB in git. Run it only to move to a new Nerd Fonts
# release, then update the filenames and the sizes quoted in web/src/index.css.
#
# It splits the font at the plane boundary so `unicode-range` can be selective — a Powerline-only
# prompt fetches the BMP face alone. Everything outside the private-use areas is dropped: those
# codepoints (♥, ⚡, ☰ …) already render from the system font, and overriding them with a symbol
# face would change text the user can read today.
#
# The subsets are made of glyph families that each carry their own license, so the same run also
# writes LICENSE-nerd-symbols.txt: Nerd Fonts' license audit plus the license text each family
# ships in the release tag. `--licenses-only` writes just that file, with no Python needed.
#
#   pip install 'fonttools[woff]'
#   scripts/build-nerd-font.sh [version]
#   scripts/build-nerd-font.sh --licenses-only [version]
set -euo pipefail

LICENSES_ONLY=0
if [ "${1:-}" = "--licenses-only" ]; then
  LICENSES_ONLY=1
  shift
fi
VERSION="${1:-3.5.0}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/web/public/fonts"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Write LICENSE-nerd-symbols.txt: the audit's glyph-source table, then each family's own license
# text as the release tag ships it. Families the tag ships no license file for (Devicons, Seti,
# IEC Power Symbols, Font Logos, Font Awesome Extension) are covered by the audit table alone.
write_licenses() {
  local audit="https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v$VERSION/license-audit.md"
  local glyphs="https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v$VERSION/src/glyphs"
  local row
  {
    echo "Nerd Fonts Symbols v$VERSION: glyph families in the pua and spua subsets"
    echo "Source of this list: $audit"
    echo
    curl -fsSL "$audit"
    echo
    echo "Nerd Fonts patcher and symbols font: see LICENSE.txt (MIT)."
    for row in \
      "Codicons (CC BY 4.0)|codicons/LICENSE.txt" \
      "Font Awesome (CC BY 4.0 icons, SIL OFL 1.1 fonts, MIT code)|font-awesome/LICENSE.txt" \
      "Material Design Icons (Pictogrammers Free License, Apache 2.0)|materialdesign/LICENSE" \
      "Octicons (MIT)|octicons/LICENSE" \
      "Pomicons (SIL OFL 1.1)|pomicons/LICENSE" \
      "Powerline Extra Symbols (MIT)|powerline-extra/LICENSE" \
      "Powerline Symbols|powerline-symbols/LICENSE.txt" \
      "Weather Icons (SIL OFL 1.1)|weather-icons/OFL.txt"; do
      echo
      echo "== ${row%%|*} =="
      echo
      curl -fsSL "$glyphs/${row##*|}"
    done
    echo
    echo "== Apache License 2.0 (cited by Material Design Icons) =="
    echo
    curl -fsSL "https://www.apache.org/licenses/LICENSE-2.0.txt"
  } > "$OUT/LICENSE-nerd-symbols.txt"
}

if [ "$LICENSES_ONLY" = 1 ]; then
  mkdir -p "$OUT"
  write_licenses
  echo "→ wrote $OUT/LICENSE-nerd-symbols.txt"
  exit 0
fi

command -v pyftsubset >/dev/null || { echo "pyftsubset not found — pip install 'fonttools[woff]'" >&2; exit 1; }

echo "→ fetching Nerd Fonts v$VERSION symbols"
curl -fsSL -o "$WORK/symbols.zip" \
  "https://github.com/ryanoasis/nerd-fonts/releases/download/v$VERSION/NerdFontsSymbolsOnly.zip"
unzip -qo "$WORK/symbols.zip" -d "$WORK"

# The Mono variant, because every glyph lands in a single terminal cell — the mirror is a grid.
SRC="$WORK/SymbolsNerdFontMono-Regular.ttf"
subset() { # <name> <unicodes>
  pyftsubset "$SRC" --unicodes="$2" --flavor=woff2 --layout-features='' --no-hinting \
    --desubroutinize --output-file="$OUT/nerd-symbols-$VERSION-$1.woff2"
}
mkdir -p "$OUT"
subset pua  'U+E000-F8FF'    # Powerline, devicons, codicons, Font Awesome, Octicons, seti, weather
subset spua 'U+F0000-F1AFF'  # plane 15: Material Design Icons, the bulk of the glyph count
# The ranges are deliberately wider than the font: a codepoint in range but absent from the font
# falls through to the next family, which is what should happen. So a pre-v3 Nerd Font config still
# emitting the OLD Material block (U+F500-FD46, moved to plane 15 in v3) is tofu because the GLYPHS
# are gone upstream — widening the range here cannot bring them back. Fix is on the prompt's side.
cp "$WORK/LICENSE" "$OUT/LICENSE.txt"
write_licenses

ls -lh "$OUT"
echo "→ update the filenames + quoted sizes in web/src/index.css"
