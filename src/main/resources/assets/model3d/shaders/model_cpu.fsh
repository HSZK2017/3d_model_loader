#version 150 core

// Fragment shader for the CPU-skinning mesh render path.
//
// PORTED, not invented: this is ysm_epicfight_compat's cpu_skin.fsh, whose algorithm its author
// reports as verified across many users' environments, adapted only in its GLSL version
// ("#version 330 core" -> "#version 150 core" for Minecraft 1.20.1's OpenGL 3.2 core baseline).
//
// What it replicates, and why porting it matters more than writing an equivalent:
//   * cutout / translucent alpha modes, matching RenderType semantics;
//   * the overlay texture (Sampler1) fetched by texel, so the red damage flash works;
//   * the lightmap (Sampler2) fetched by texel, so the model is lit by the block and sky light
//     where it stands rather than by a constant;
//   * linear fog against the shader's own start/end/colour, so a distant model fades into the
//     horizon exactly like an entity rather than staying crisp against it.
//
// Every one of those is a place where a hand-written shader quietly differs from vanilla in a
// way that looks like a modelling problem - a model that ignores the world's light, or glows
// through fog. Taking the shader that has been run on other machines is the point of this file.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform int   u_packedOverlay;
uniform float u_fogStart;
uniform float u_fogEnd;
uniform vec4  u_fogColor;
// Alpha handling, per material group:
//   0 = OPAQUE, 1 = MASK (cutout), 2 = BLEND. The CPU side sets the matching GL state - blending and
//   depth writes for BLEND, culling from doubleSided - so this shader only decides what to discard.
uniform int   u_alphaMode;
uniform float u_alphaCutoff;

in vec2  v_uv;
in vec3  v_normal;
in vec4  v_color;
in float v_vertexDistance;
flat in int v_packedLight;

out vec4 fragColor;

vec4 linearFog(vec4 inColor, float vd, float fs, float fe, vec4 fc) {
    if (vd <= fs) return inColor;
    float t = vd < fe ? smoothstep(fs, fe, vd) : 1.0;
    return vec4(mix(inColor.rgb, fc.rgb, t * fc.a), inColor.a);
}

void main() {
    vec4 texColor = texture(Sampler0, v_uv);

    // MASK is vanilla's entityCutout: discard below the material's own cutoff, which glTF carries as
    // alphaCutoff (0.5 by default) and MTL expresses through d/Tr.
    if (u_alphaMode == 1 && texColor.a < u_alphaCutoff) {
        discard;
    }
    // OPAQUE ignores alpha by definition, but a texel that is fully transparent is never worth
    // writing - it would only be visible as a hole in the depth buffer.
    if (u_alphaMode == 0 && texColor.a < 0.004) {
        discard;
    }
    // BLEND keeps everything: the CPU side has blending on and depth writes off for these groups.

    vec4 color = texColor * v_color;

    int oU = u_packedOverlay & 0xFFFF;
    int oV = (u_packedOverlay >> 16) & 0xFFFF;
    vec4 overlayColor = texelFetch(Sampler1, ivec2(oU, oV), 0);
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);

    int blockUV = (v_packedLight & 0xFFFF) / 16;
    int skyUV   = ((v_packedLight >> 16) & 0xFFFF) / 16;
    vec4 lightColor = texelFetch(Sampler2, ivec2(blockUV, skyUV), 0);
    color.rgb *= lightColor.rgb;

    fragColor = linearFog(color, v_vertexDistance, u_fogStart, u_fogEnd, u_fogColor);
}
