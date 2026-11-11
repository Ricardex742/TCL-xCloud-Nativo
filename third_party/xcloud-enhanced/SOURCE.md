# xCloud Enhanced image processing references

Source: https://github.com/MatheusDourado/xbox-xcloud-image-enhancer
Pinned revision: f96acba37ac27861364177a9c258b90351b2f568

The spatial filtering shader and small luma restoration network are adapted from
this MIT-licensed project by MatheusDourado. The original notice is retained in
LICENSE and shipped in assets/enhancement/LICENSE.txt. The GLES 2 adaptation
uses WebRTC input samplers, optional separate half-float feature targets, bounded
storage and native frame delivery rather than JavaScript/WebView hooks.

The optional SGSR 1 shader is adapted from its Qualcomm SGSR 1 port. Qualcomm's
BSD-3-Clause copyright, conditions and disclaimer are retained in the shader
and in the bundled SGSR-LICENSE.txt. Neural weights are retained in restore.json;
scripts/build_enhancement_shaders.py deterministically produces the GLES 2 passes.
