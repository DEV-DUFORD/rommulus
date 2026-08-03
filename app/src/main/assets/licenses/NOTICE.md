# Bundled Asset Notices

## Controllercons 2.1

- **License**: SIL Open Font License 1.1 (OFL-1.1) — full text now populated in
  `licenses/controllercons-OFL-1.1.txt` (verbatim, confirmed against the
  canonical SIL source).
- **Author/source**: Kieran McClung, https://controllercons.github.io/
  (download: https://controllercons.github.io/versions/2.1/controllercons.2.1.zip).
  Also mirrored at https://github.com/controllercons/controllercons.github.io.
  The project's own "License and usage" section states: "Controllercons are
  licensed under the SIL Open Font License (1.1). That means you can use them
  in commercial and personal projects, modify and share them. You cannot,
  however, redistribute them or sell them for profit." This corroborates the
  OFL-1.1 attribution requirement.
- **Status**: License text confirmed; actual SVG assets are still NOT
  imported into this app (network access to fetch the real .zip was not
  available in this environment). The current placeholder drawables
  (`controller_outline_generic_gamepad.xml`, `controller_outline_generic_handheld.xml`)
  are original geometric shapes drawn for this app and are NOT Controllercons
  artwork — they carry no Controllercons licensing obligation. This notice
  documents the license that WILL apply once the real SVGs are imported.
- **Covers**: Mega Drive/Genesis, SNES, NES, Atari 2600, PS1, N64 (exact
  Controllercons artwork exists for these); Master System is a possible reuse
  candidate pending visual review.
- **Remaining work**: Download `controllercons.2.1.zip` from the source above
  in an environment with network access, extract the outline SVGs, convert to
  Android vector drawables per the annotation-layer approach in
  CONTROLLER_SETTINGS.md, and replace `ControllerArtworkResolver`'s generic
  mappings with the real per-console resources. Bundle the OFL copyright
  notice from the release alongside the imported assets per OFL condition 2.

## Handheld Silhouettes ("1 Color Controllers and Handhelds")

- **Author**: Individual artist, contacted directly for permission to use this
  artwork in this app. Permission has been granted by the artist.
- **Correction (superseding an earlier note in this file)**: The assets are
  **not** actually hosted or sold on the Etsy shop
  https://www.etsy.com/shop/PineappleGraphicsShp — that page was only used as
  a contact channel to reach the artist and is unrelated to the artwork
  itself. It should not be cited as the asset source or as an Etsy
  marketplace-license question; there is no Etsy digital-download licensing
  concern here.
- **License**: No formal written license document exists; permission was
  granted directly by the artist for use of this artwork in this app.
  Attribution to the artist should still be included once the real assets and
  their preferred credit line are on hand.
- **Status**: Placeholder — the real SVG assets are still not imported into
  this app (no network access to retrieve them in this environment). The
  current placeholder drawable (`controller_outline_generic_handheld.xml`) is
  an original shape drawn for this app, not the artist's artwork.
- **Assets covered**: GBA, Game Boy / Game Boy Color, TurboGrafx-16, Neo Geo
  Pocket, WonderSwan, Atari Lynx, Atari 7800.
- **Remaining work**: Obtain the actual SVG files from the artist directly
  (not via Etsy), confirm their preferred attribution wording, convert to
  Android vector drawables per the annotation-layer approach in
  CONTROLLER_SETTINGS.md (single compound-path, filled silhouettes, viewBox
  `0 0 360 360`, no per-control subpaths — same highlighting approach as the
  Controllercons outlines), and replace `ControllerArtworkResolver`'s generic
  handheld mapping with the real per-console resources.
