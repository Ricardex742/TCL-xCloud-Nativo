# Third-party notices

The project's original code is licensed under the [MIT license](LICENSE).
Third-party components retain their respective copyrights and licenses.

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

The Game Pass and Xbox Series X|S marks belong to Microsoft. The local badge
assets use public copies of the black artwork matching the supplied references:

- [Xbox Game Pass mark](https://commons.wikimedia.org/wiki/File:Xbox_Game_Pass_new_logo_-_black_color_version.svg)
- [Xbox Series X|S mark](https://commons.wikimedia.org/wiki/File:Xbox_Series_X_S_black.svg)

These marks retain their owners' rights and are not covered by the project's
MIT license. They identify catalog capabilities and do not imply endorsement.
