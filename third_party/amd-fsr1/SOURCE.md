# AMD FidelityFX Super Resolution 1

Upstream: https://github.com/GPUOpen-Effects/FidelityFX-FSR/tree/a21ffb8f6c13233ba336352bdff293894c706575

Release **v1.0.2**, commit `a21ffb8f6c13233ba336352bdff293894c706575`.

The unmodified headers are reference/test inputs, not linked into the APK:

| File | Upstream path | SHA-256 |
| --- | --- | --- |
| ffx_fsr1.h | ffx-fsr/ffx_fsr1.h | 93c3922362ea7fc99cbcc698ca30c98de4f8c246d1fbb0b09e015ddef38ce3a5 |
| ffx_a.h | ffx-fsr/ffx_a.h | f60e2722fcd13989523b9164d776ab382b3692791767f3bf8bb19967f763f3fb |
| LICENSE | license.txt | db089274ce766da70f5b7d791029c3486f9f9e27c8c79c652689603d3192e802 |

The APK includes GLES 2 adaptations in `assets/fsr1` plus the complete MIT license.
EASU retains the twelve taps, edge direction/anisotropy, polynomial weights and
nearest-four deringing. RCAS retains its cross kernel, limiter and optional AMD
denoise path (enabled for compressed video). The port uses highp exact reciprocals
and inverse square root instead of the upstream bit approximations; it guards zero
denominators. RGB point loads replace textureGather for GLES 2 compatibility.

An RGB preparation pass applies WebRTC's texture transform/YUV conversion before
EASU. RCAS runs at the EASU output resolution and includes the existing saturation
adjustment. This is spatial video postprocessing, without temporal reconstruction,
motion-vector estimation, frame generation or integration into the remote game.

The reference test compiles the original GPU functions with three unsigned-zero
literals made explicit in generated GLSL ES source. The vendored files stay
unchanged. Git attributes preserve their bytes and intentional upstream whitespace.
