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

## Pineapple Graphics Handheld Silhouettes

- **Author**: Pineapple Graphics
- **Shop**: https://www.etsy.com/shop/PineappleGraphicsShp
- **Collection**: "1 Color Controllers and Handhelds"
- **License**: No formal license published by the author — attribution
  required. The shop's pages returned HTTP 403 to automated fetching in this
  environment, so its policies/terms of use for digital downloads could not
  be verified here. **Before importing these assets, open the shop manually
  in a browser, review its About/Policies tabs, and obtain explicit written
  permission from the seller for commercial redistribution** — Etsy's
  default policy treats digital downloads as personal-use only unless the
  seller states otherwise, which would be a blocker for shipping in a public
  app without seller sign-off.
- **Status**: Placeholder — pending SVG asset import and seller license
  confirmation. The current placeholder drawable
  (`controller_outline_generic_handheld.xml`) is an original shape drawn for
  this app, not Pineapple Graphics artwork.
- **Assets covered**: GBA, Game Boy / Game Boy Color, TurboGrafx-16, Neo Geo
  Pocket, WonderSwan, Atari Lynx, Atari 7800.
- **Attribution**: The handheld SVGs are authored by Pineapple Graphics (Etsy
  shop https://www.etsy.com/shop/PineappleGraphicsShp). They are single
  compound-path, filled silhouettes with viewBox `0 0 360 360` and no
  per-control subpaths, so they use the same annotation-layer highlighting
  approach as the Controllercons outlines. Record the source/license of
  every shipped asset once imported.
