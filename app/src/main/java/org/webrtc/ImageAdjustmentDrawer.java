package org.webrtc;

import android.opengl.GLES20;

/** Optional single-pass color/unsharp filter using the existing WebRTC OES/RGB/YUV shader adapter. */
public final class ImageAdjustmentDrawer extends GlGenericDrawer {
    private static final String FRAGMENT_SHADER =
        "uniform float sharpness;\n" +
        "uniform float saturation;\n" +
        "uniform vec2 step_x;\n" +
        "uniform vec2 step_y;\n" +
        "void main() {\n" +
        "  vec3 color = sample(tc).rgb;\n" +
        "  if (sharpness > 0.0) {\n" +
        "    vec3 nearColor = (sample(clamp(tc + step_x, 0.0, 1.0)).rgb +\n" +
        "      sample(clamp(tc - step_x, 0.0, 1.0)).rgb +\n" +
        "      sample(clamp(tc + step_y, 0.0, 1.0)).rgb +\n" +
        "      sample(clamp(tc - step_y, 0.0, 1.0)).rgb) * 0.25;\n" +
        "    color = clamp(color + (color - nearColor) * sharpness, 0.0, 1.0);\n" +
        "  }\n" +
        "  float luminance = dot(color, vec3(0.299, 0.587, 0.114));\n" +
        "  gl_FragColor = vec4(clamp(mix(vec3(luminance), color, saturation), 0.0, 1.0), 1.0);\n" +
        "}\n";

    private static final class Adjustments implements ShaderCallbacks {
        volatile float sharpness = 0;
        volatile float saturation = 1;
        volatile int width = 1920;
        volatile int height = 1080;
        private int sharpnessLocation, saturationLocation, stepXLocation, stepYLocation;
        public void onNewShader(GlShader shader) {
            sharpnessLocation=shader.getUniformLocation("sharpness");
            saturationLocation=shader.getUniformLocation("saturation");
            stepXLocation=shader.getUniformLocation("step_x");
            stepYLocation=shader.getUniformLocation("step_y");
        }
        public void onPrepareShader(GlShader shader, float[] matrix, int frameWidth, int frameHeight, int viewportWidth, int viewportHeight) {
            GLES20.glUniform1f(sharpnessLocation,sharpness);
            GLES20.glUniform1f(saturationLocation,saturation);
            GLES20.glUniform2f(stepXLocation,matrix[0]/Math.max(1,width),matrix[1]/Math.max(1,width));
            GLES20.glUniform2f(stepYLocation,matrix[4]/Math.max(1,height),matrix[5]/Math.max(1,height));
        }
    }
    private final Adjustments adjustments;
    public ImageAdjustmentDrawer() { this(new Adjustments()); }
    private ImageAdjustmentDrawer(Adjustments adjustments) { super(FRAGMENT_SHADER,adjustments); this.adjustments=adjustments; }
    public void configure(int sharpness, int saturation, int width, int height) {
        adjustments.sharpness=Math.max(0,Math.min(100,sharpness))/100f;
        adjustments.saturation=Math.max(0,Math.min(200,saturation))/100f;
        adjustments.width=width; adjustments.height=height;
    }
}
