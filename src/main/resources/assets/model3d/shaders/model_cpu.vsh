#version 150 core

// Vertex shader for the CPU-skinning mesh render path.
//
// PORTED, not invented. This is the vertex shader of ysm_epicfight_compat's
// cpu_skin path - the renderer that author of this project reports as verified on many users'
// environments - with two adaptations for Minecraft 1.20.1:
//
//   * "#version 330 core" becomes "#version 150 core", because 1.20.1's baseline context is
//     OpenGL 3.2 core. The constructs used here (flat qualifier, texelFetch
//     in the fragment stage) are all available in 150.
//   * Nothing else is changed. The uniform names, the vertex attribute slots, the fog
//     replication and the light mixing are the originals, because they are the parts that were
//     exercised on other people's machines.
//
// The contract: positions and normals arrive ALREADY transformed into camera space by the CPU
// (the pose stack is applied when the vertices are written), so this shader applies exactly the
// uniforms Minecraft's own entity shader receives - projection, model-view, inverse view
// rotation - and nothing else. That is what makes it safe: there is no second source of truth
// about where the model is.
//
// Attribute layout is pinned here AND in the VAO, deliberately duplicated so a mismatch is a
// compile-time fact rather than a silent one:
//   0 -> position  3 floats  (offset  0)
//   1 -> uv        2 floats  (offset 12)
//   2 -> normal    4 bytes, GL_INT_2_10_10_10_REV (offset 20)
// Locations are NOT declared with layout(location = N) here: that qualifier arrived in GLSL 330, and
// Minecraft 1.20.1's baseline context is OpenGL 3.2, whose GLSL 150 rejects it outright - "not supported
// for this version or the enabled extensions". The reference could use it because its target was 330.
//
// The slots are pinned instead by glBindAttribLocation before linking, in ModelCpuRenderPath, which is
// the GLSL-150 way to do the same job and is equally deterministic.
in vec3 a_position;
in vec2 a_uv;
in vec4 a_normal;

uniform mat4 u_proj;
uniform mat4 u_mv;
uniform mat3 u_ivr;
uniform vec4 u_color;
uniform int  u_fogShape;
uniform vec3 u_light0;
uniform vec3 u_light1;
uniform int  u_packedLight;

out vec2  v_uv;
out vec3  v_normal;
out vec4  v_color;
out float v_vertexDistance;
flat out int v_packedLight;

// Mirrors Minecraft's fog.glsl exactly: the distance must be computed on the
// view-transformed position (u_mv).
float fogDistance(mat4 modelViewMat, vec3 pos, int shape) {
    if (shape == 0) {
        return length((modelViewMat * vec4(pos, 1.0)).xyz);
    } else {
        float lenXZ = length((modelViewMat * vec4(pos.x, 0.0, pos.z, 1.0)).xyz);
        float lenY = length((modelViewMat * vec4(0.0, pos.y, 0.0, 1.0)).xyz);
        return max(lenXZ, lenY);
    }
}

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    lightDir0 = normalize(lightDir0);
    lightDir1 = normalize(lightDir1);
    float l0 = max(0.0, dot(lightDir0, normal));
    float l1 = max(0.0, dot(lightDir1, normal));
    float lightAccum = min(1.0, (l0 + l1) * 0.6 + 0.4);
    return vec4(color.rgb * lightAccum, color.a);
}

void main() {
    vec4 eyePos = vec4(a_position, 1.0);
    gl_Position = u_proj * (u_mv * eyePos);

    vec3 nrm = normalize(a_normal.xyz);

    v_uv = a_uv;
    v_normal = nrm;
    v_color = minecraft_mix_light(u_light0, u_light1, nrm, u_color);
    // Same fog input as Minecraft's entity shader (IViewRotMat * Position with
    // the model-view matrix); see fogDistance above.
    v_vertexDistance = fogDistance(u_mv, u_ivr * eyePos.xyz, u_fogShape);
    v_packedLight = u_packedLight;
    // Back-face handling is not a shader decision here: culling is a GL state the renderer sets per
    // material from its doubleSided flag, so a double-sided material simply draws both faces.
}
