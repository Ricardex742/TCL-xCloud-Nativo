# Third-party notices

The project's original code is licensed under the [MIT license](LICENSE).
Third-party components retain their respective copyrights and licenses.

## Better xCloud regional access reference

The optional experimental region bypass adapts Better xCloud's fixed country/IP
reference table and login-header technique, under the MIT license. The source
revision, attribution and full license are retained in
[third_party/better-xcloud-region](third_party/better-xcloud-region/SOURCE.md).
The complete notice is shipped as `assets/region-bypass/LICENSE.txt`.

## AMD FidelityFX Super Resolution

The experimental FSR renderer incorporates an OpenGL ES adaptation of AMD
FidelityFX Super Resolution 1.0.2 (EASU and RCAS), copyright © 2021 Advanced Micro
Devices, Inc., licensed under the MIT license.

The complete notice is included in [third_party/amd-fsr1/LICENSE](third_party/amd-fsr1/LICENSE)
and shipped inside the APK as `assets/fsr1/LICENSE.txt`. The pinned upstream
revision and adaptation details are in [SOURCE.md](third_party/amd-fsr1/SOURCE.md).

## Barlow font

The bundled Barlow fonts are copyright 2017 The Barlow Project Authors
(https://github.com/jpt/barlow) and licensed under the SIL Open Font License 1.1.
The complete license is included in
[app/src/main/assets/fonts/OFL.txt](app/src/main/assets/fonts/OFL.txt)
and shipped inside the APK as `assets/fonts/OFL.txt`.

## Optional image restoration and SGSR

The optional image treatment adapts spatial filtering and the small luma
restoration network from [xCloud Enhanced](https://github.com/MatheusDourado/xbox-xcloud-image-enhancer)
by MatheusDourado, under its MIT license. The pinned source, adaptations and
complete notice are retained in [third_party/xcloud-enhanced](third_party/xcloud-enhanced/SOURCE.md)
and shipped as `assets/enhancement/LICENSE.txt`.

The optional SGSR 1 spatial upscaler incorporates Qualcomm's BSD-3-Clause
implementation. Its copyright, conditions and disclaimer are preserved in
the shader and shipped as `assets/enhancement/SGSR-LICENSE.txt`.

## Third-party artwork and trademarks

Xbox, Microsoft, game names, logos and game artwork belong to their respective
owners. The project's MIT license does not grant rights to those assets or
imply endorsement by their owners.

## Catalog badge artwork

The Game Pass and Xbox Series X|S marks belong to Microsoft. The catalog uses native vector outlines from official Microsoft assets. Source
SVGs, URLs, hashes and conversion details are retained in
[third_party/xbox-branding/SOURCES.json](third_party/xbox-branding/SOURCES.json).
Earlier bitmap references are retained as source artwork:

- [Xbox Game Pass mark](https://commons.wikimedia.org/wiki/File:Xbox_Game_Pass_new_logo_-_black_color_version.svg)
- [Xbox Series X|S mark](https://commons.wikimedia.org/wiki/File:Xbox_Series_X_S_black.svg)

These marks retain their owners' rights and are not covered by the project's
MIT license. They identify catalog capabilities and do not imply endorsement.


## Dashboard demonstration artwork

`app/src/test/resources/showcase/hero-0.jpg` is the public promotional SuperHeroArt
for **007 First Light**, supplied by the Microsoft Store product
[9NPF9F0DCVKS](https://www.xbox.com/games/store/007-first-light/9NPF9F0DCVKS).
It is used only in automated interface demonstrations and generated screenshots;
it is not bundled in the application. Rights remain with the game's respective
owners and the artwork is not covered by the project's MIT license.

## Xbox console marks

Xbox, Xbox 360, Xbox One, Xbox Series X|S and Xbox Game Pass marks belong to
Microsoft Corporation. The console artwork is retained as brand identification
for the optional interface themes. These marks are not part of the project's
MIT license and do not imply endorsement by Microsoft.

The original SVG artwork, source URLs and hashes are retained in
[third_party/xbox-branding/SOURCES.json](third_party/xbox-branding/SOURCES.json).
The APK uses proportion-preserving raster renderings of those SVGs, with
monochrome display variants where needed for contrast. Game Pass and Series
catalog symbols use scalable Android vector outlines instead of bitmap crops.

## Official Xbox wallpapers

The optional **Official Xbox** wallpaper collection uses the public
[Xbox wallpaper gallery](https://www.xbox.com/en-US/wallpapers) and its
[published catalog](https://www.xbox.com/en-US/wallpapers/JS/wallpapers-Sheet.js).
`assets/wallpapers/xbox-official.json` retains titles, preview URLs and the
publisher's console-size image URLs. Images are fetched from Microsoft's CDN
when displayed; the catalog contains static images, not console animation
packages. Their copyrights remain with Microsoft and the respective artists
and publishers. The project's MIT license does not license those images.

The unchanged Xbox 360 and Xbox Series image fixtures under
`app/src/test/resources/wallpapers/` are used only by automated interface tests
and are not packaged in the application. Their public source URLs and hashes
are recorded in `third_party/xbox-wallpapers/FIXTURES.json`.
