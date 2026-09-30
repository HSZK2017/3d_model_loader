package com.model3d.loader.client.render;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.client.gl.GlStateGuard;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a model by skinning it on the CPU and streaming the result through a dynamic VBO.
 *
 * <h2>Why this replaced two other renderers</h2>
 * This project has now written the same model twice and thrown both away:
 * <ul>
 *   <li>a custom GLSL skinning renderer ({@code GlProgram}/{@code GlMesh}), which produced <b>zero
 *       pixels</b> in a live client while every individual GL call reported success;</li>
 *   <li>Minecraft's own vertex pipeline ({@code VertexConsumer}), which rendered correctly but left the
 *       model's orientation and lighting to a second set of assumptions that had to be re-derived here
 *       - the pose stack's flip, the instance transform, the normal matrix - each of which was a
 *       separate multi-hour fault when it was wrong.</li>
 * </ul>
 *
 * <p>This is the third, and it is not written from scratch: it is
 * {@code ysm_epicfight_compat}'s cpu_skin path, which its author reports as verified across many users'
 * environments. The algorithm, the vertex layout, the shader pair, the GL state sequence and the {@code
 * GL_STREAM_DRAW} orphaning trick are all taken from it. The only adaptation is {@code #version 330 core}
 * to {@code #version 150 core} for Minecraft 1.20.1's OpenGL 3.2 baseline.
 *
 * <h2>What it does, in order</h2>
 * <ol>
 *   <li>skins each vertex on the CPU (joint matrices, then the node transform, then the instance
 *       transform, then the pose stack) into an interleaved buffer;</li>
 *   <li>binds the model's texture, the lightmap and the overlay to units 0, 1 and 2;</li>
 *   <li>uploads {@code RenderSystem}'s projection, model-view and inverse-view-rotation, which is
 *       exactly what the vanilla entity shader receives - so the shader needs no opinion about where
 *       the model is, only about how to light it;</li>
 *   <li>streams the vertices and draws them in one call.</li>
 * </ol>
 */
final class ModelCpuRenderPath {

    private static final boolean TRACE = Boolean.getBoolean("model3d.traceDraw");

    private static final String VSH = "/assets/model3d/shaders/model_cpu.vsh";
    private static final String FSH = "/assets/model3d/shaders/model_cpu.fsh";

    private static int program;
    private static boolean compileFailed;
    private static int locProj = -1;
    private static int locMv = -1;
    private static int locIvr = -1;
    private static int locColor = -1;
    private static int locOverlay = -1;
    private static int locFogStart = -1;
    private static int locFogEnd = -1;
    private static int locFogColor = -1;
    private static int locFogShape = -1;
    private static int locLight0 = -1;
    private static int locLight1 = -1;
    private static int locAlphaMode = -1;
    private static int locAlphaCutoff = -1;
    private static int locPackedLight = -1;

    private static final float[] projScratch = new float[16];
    private static final float[] mvScratch = new float[16];
    private static final float[] ivrScratch = new float[9];

    /**
     * The two entity light directions, as vanilla's entity shader declares them.
     *
     * <p>Taken from the ported source, which reads {@code RenderSystem}'s light directions through an
     * accessor mixin and falls back to exactly these two vectors when the accessor returns nothing. Not
     * reading the live values here is a deliberate trade: the alternative is a Mixin into
     * {@code RenderSystem}, which is a class-transformer dependency this mod does not otherwise have and
     * which breaks on anyone else's rebuild. The values only differ from vanilla's own under a shader
     * pack, and a shader pack replaces this whole path anyway.
     */
    private static final float[] LIGHT0 = {0.2f, 1.0f, -0.7f};
    private static final float[] LIGHT1 = {-0.2f, 1.0f, 0.7f};

    /** Per-model GL resources, keyed by the model id. */
    private static final Map<String, ModelCpuMesh> MESHES = new HashMap<>();

    /**
     * The one vertex writer, reused every frame.
     *
     * <p>Render thread only, which is what makes a single instance safe - and necessary: it owns a
     * native buffer the size of the largest model drawn, and allocating that per draw was a
     * malloc/free of megabytes per entity per frame.
     */
    private static final CpuVertexWriter WRITER = new CpuVertexWriter(0);

    private ModelCpuRenderPath() {
    }

    /**
     * Draws an instance, one draw call per material.
     *
     * <p>Per material rather than per model because a model routinely uses several textures: the aircraft
     * in this repository's corpus has twenty-two materials and five images. The reference draws a single
     * texture per mesh because its meshes are single-material; grouping here keeps that structure - one
     * program, one state sequence, one upload per group - while allowing a model whose parts are painted
     * differently.
     *
     * @param textures resolved texture per material index; entries may be null
     * @param fallbackTexture the registered 1x1 white texture, bound when a material has none
     * @return true when at least one group was drawn
     */
    static boolean draw(ModelInstance instance, PoseStack poseStack, ResourceLocation modelId,
                        ModelDrawList drawList, ResourceLocation[] textures,
                        ResourceLocation fallbackTexture, int packedLight,
                        int packedOverlay, InstanceTransform instanceTransform, float[] jointMatrices,
                        int jointCount) {
        if (!ensureCompiled()) {
            return false;
        }
        ModelScene scene = instance.scene();
        if (drawList.drawableCount() == 0) {
            return false;
        }

        String key = modelId.toString();
        int needed = bufferCapacityFor(drawList);
        ModelCpuMesh mesh = MESHES.get(key);
        if (mesh != null && mesh.capacity() < needed) {
            // The GPU mesh is sized once and keyed by model id, so a model that gained geometry
            // since it was first drawn - the hot-reload path this mod advertises - would otherwise
            // reuse the smaller buffer and overflow exactly as a mis-sized one would. Rebuild it.
            mesh.dispose();
            MESHES.remove(key);
            mesh = null;
            Model3D.LOGGER.info("Model3D: {} grew to {} vertices; rebuilding its stream buffer",
                    key, needed);
        }
        if (mesh == null) {
            mesh = ModelCpuMesh.create(needed);
            if (mesh == null) {
                return false;
            }
            MESHES.put(key, mesh);
        }

        // Skinned once per frame into one reused buffer, then issued once per material group. The
        // writer is a field, not a local: allocating it per draw meant a native malloc/free of the
        // whole buffer every frame per entity (1.7 MB for the corpus aircraft), for storage whose
        // size only changes when the model does.
        CpuVertexWriter writer = WRITER;
        writer.ensureCapacity(mesh.capacity());
        writer.reset();
        fill(writer, drawList, scene, instance, poseStack, instanceTransform, jointMatrices,
                jointCount);
        if (writer.writtenVertices() == 0) {
            return false;
        }
        return issue(poseStack, mesh, writer, scene, drawList, textures, fallbackTexture,
                packedLight, packedOverlay);
    }

    /**
     * Vertices the streaming buffer must hold for one draw of {@code drawList}.
     *
     * <p>The index count, not the vertex count: {@link #fill} expands the geometry and writes
     * <b>one vertex per index</b>, so a shared-vertex mesh needs more slots than it has vertices.
     * Sizing from {@code vertexCount() * 3} agrees only for a triangle soup where no vertex is
     * shared; a cube - 8 vertices, 36 indices - overflows it on the first frame, and the exception
     * leaves the entity renderer and kills the client.
     *
     * <p>A single named expression, used by the allocation and by {@code CpuVertexWriterCapacityTest},
     * so the two cannot drift apart the way an inline literal and a fill loop can.
     */
    static int bufferCapacityFor(ModelDrawList drawList) {
        return drawList.indexCount();
    }

    /** Skins and transforms every vertex into camera space, in triangle order. */
    private static void fill(CpuVertexWriter writer, ModelDrawList drawList, ModelScene scene,
                             ModelInstance instance, PoseStack poseStack,
                             InstanceTransform instanceTransform, float[] jointMatrices, int jointCount) {
        PoseStack.Pose pose = poseStack.last();
        float[] nrm = new float[3];
        float[] scratch = new float[4];
        ModelNode[] nodes = instance.nodes();

        for (ModelDrawList.Drawable drawable : drawList.drawables()) {
            ModelPrimitive primitive = drawable.primitive();
            // Marks where this drawable's vertices begin, so the draw is issued per material over the
            // range that material actually owns. Without this the buffer fills and nothing is drawn,
            // because the range table the draw iterates stays empty.
            writer.beginMaterial(primitive.materialIndex());

            float[] nodeMatrix = null;
            if (drawable.usesNodeTransform() && drawable.nodeIndex() >= 0 && nodes != null
                    && drawable.nodeIndex() < nodes.length && nodes[drawable.nodeIndex()] != null) {
                nodeMatrix = nodes[drawable.nodeIndex()].globalTransform().raw();
            }

            float[] positions = primitive.positions();
            float[] normals = primitive.normals();
            float[] uvs = primitive.uvs();
            float[] jointIndices = primitive.jointIndices();
            float[] jointWeights = primitive.jointWeights();
            short[] indices = primitive.indices();
            boolean skinned = jointCount > 0 && jointMatrices != null && primitive.isSkinned()
                    && jointIndices != null && jointWeights != null;

            for (int i = 0; i < indices.length; i++) {
                int vertex = indices[i] & 0xFFFF;
                int p = vertex * 3;
                if (p + 2 >= positions.length) {
                    continue;
                }
                float x = positions[p];
                float y = positions[p + 1];
                float z = positions[p + 2];
                float nx = normals != null && p + 2 < normals.length ? normals[p] : 0.0f;
                float ny = normals != null && p + 2 < normals.length ? normals[p + 1] : 1.0f;
                float nz = normals != null && p + 2 < normals.length ? normals[p + 2] : 0.0f;

                if (skinned) {
                    float[] skinnedPosition = ModelSkinning.skinPosition(jointMatrices, jointCount,
                            jointIndices, jointWeights, vertex, x, y, z, scratch);
                    x = skinnedPosition[0];
                    y = skinnedPosition[1];
                    z = skinnedPosition[2];
                    float[] skinnedNormal = ModelSkinning.skinNormal(jointMatrices, jointCount,
                            jointIndices, jointWeights, vertex, nx, ny, nz, nrm);
                    nx = skinnedNormal[0];
                    ny = skinnedNormal[1];
                    nz = skinnedNormal[2];
                }
                if (nodeMatrix != null) {
                    com.model3d.loader.math.Mat4.transform(nodeMatrix, 0, x, y, z, 1.0f, scratch);
                    x = scratch[0];
                    y = scratch[1];
                    z = scratch[2];
                    float[] rotated = ModelSkinning.rotateDirection(nodeMatrix, nx, ny, nz, nrm);
                    nx = rotated[0];
                    ny = rotated[1];
                    nz = rotated[2];
                }

                // The instance transform, then the pose stack: positions land in camera space, which is
                // the contract the ported shader expects and the reason it needs no model-space uniform.
                instanceTransform.point(x, y, z, scratch);
                x = scratch[0];
                y = scratch[1];
                z = scratch[2];
                instanceTransform.direction(nx, ny, nz, nrm);
                nx = nrm[0];
                ny = nrm[1];
                nz = nrm[2];

                // The pose stack, applied by hand rather than through a fresh Vector4f/Vector3f per
                // vertex: this loop runs once per index per entity per frame (64 755 vertices for the
                // corpus aircraft), and two short-lived JOML objects per vertex was most of the
                // garbage the render path produced. JOML is column-major: m<column><row>.
                org.joml.Matrix4f poseMatrix = pose.pose();
                float px = poseMatrix.m00() * x + poseMatrix.m10() * y + poseMatrix.m20() * z
                        + poseMatrix.m30();
                float py = poseMatrix.m01() * x + poseMatrix.m11() * y + poseMatrix.m21() * z
                        + poseMatrix.m31();
                float pz = poseMatrix.m02() * x + poseMatrix.m12() * y + poseMatrix.m22() * z
                        + poseMatrix.m32();
                org.joml.Matrix3f normalMatrix = pose.normal();
                float tnx = normalMatrix.m00() * nx + normalMatrix.m10() * ny
                        + normalMatrix.m20() * nz;
                float tny = normalMatrix.m01() * nx + normalMatrix.m11() * ny
                        + normalMatrix.m21() * nz;
                float tnz = normalMatrix.m02() * nx + normalMatrix.m12() * ny
                        + normalMatrix.m22() * nz;

                float u = uvs != null && vertex * 2 + 1 < uvs.length ? uvs[vertex * 2] : 0.0f;
                float v = uvs != null && vertex * 2 + 1 < uvs.length ? uvs[vertex * 2 + 1] : 0.0f;

                writer.vertex(px, py, pz, u, v, tnx, tny, tnz);
            }
        }
    }

    /** The GL state sequence and the draws, ported from the reference's {@code draw}. */
    private static boolean issue(PoseStack poseStack, ModelCpuMesh mesh, CpuVertexWriter writer,
                                 ModelScene scene, ModelDrawList drawList, ResourceLocation[] textures,
                                 ResourceLocation fallbackTexture, int packedLight, int packedOverlay) {
        RenderSystem.getProjectionMatrix().get(projScratch);
        RenderSystem.getModelViewMatrix().get(mvScratch);
        RenderSystem.getInverseViewRotationMatrix().get(ivrScratch);

        Minecraft minecraft = Minecraft.getInstance();
        // Everything below this line changes GL state that the rest of the frame depends on, so it
        // runs inside the guard that puts it back: this renderer is called in the middle of vanilla's
        // entity pass, and a leaked program or texture binding shows up as a bug in whatever draws
        // next. The guard saves the program, the VAO, the active unit and the bindings of units 0-2.
        try (GlStateGuard guard = new GlStateGuard()) {
            // The depth state this draw depends on is SET here, not inherited. Measured with the trace:
            // entering this method GL_DEPTH_TEST was off (depthTest=false on every frame), so every
            // vertex the model wrote ignored the depth buffer - the model drew over the terrain and over
            // entities nearer the camera, which is what "the model is on a layer above the world, and it
            // hides a player standing in front of it" looks like. A raw immediate draw cannot assume the
            // state a batched pipeline happens to leave behind, and nothing about the entity pass
            // promises the test is on. The guard puts the previous state back.
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            // Texture units 0, 1 and 2 as the reference assigns them, and as the vanilla entity shader
            // expects: the model's own texture, the overlay, and the lightmap. Turning the light layer on is
            // what makes the model respond to the light where it stands instead of glowing in the dark - the
            // single most visible difference between a hand-written shader and this one.
            //
            // GlStateManager._activeTexture, not raw glActiveTexture: _bindTexture picks its target from a
            // per-unit cache indexed by the manager's tracked unit, so a raw switch leaves that tracker
            // stale and the next tracked bind either skipped or written into the wrong unit's slot.
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
            minecraft.gameRenderer.lightTexture().turnOnLightLayer();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
            minecraft.gameRenderer.overlayTexture().setupOverlayColor();
            GlStateManager._bindTexture(RenderSystem.getShaderTexture(1));

            GlStateManager._glUseProgram(program);
            setMatrix4(locProj, projScratch);
            setMatrix4(locMv, mvScratch);
            setMatrix3(locIvr, ivrScratch);
            // Uniforms that are the same for every material group. u_color is not one of them: it is
            // the fragment colour multiplier and carries each material's baseColorFactor, so it is
            // uploaded inside the group loop below. A uniform that is never assigned keeps its
            // link-time default of (0,0,0,0) - which is how every fragment once came out transparent
            // black while the draw reported success.
            if (locOverlay >= 0) {
                GL20.glUniform1i(locOverlay, packedOverlay);
            }
            if (locFogStart >= 0) {
                GL20.glUniform1f(locFogStart, RenderSystem.getShaderFogStart());
            }
            if (locFogEnd >= 0) {
                GL20.glUniform1f(locFogEnd, RenderSystem.getShaderFogEnd());
            }
            float[] fogColor = RenderSystem.getShaderFogColor();
            if (locFogColor >= 0) {
                GL20.glUniform4f(locFogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            }
            if (locFogShape >= 0) {
                GL20.glUniform1i(locFogShape, RenderSystem.getShaderFogShape().getIndex());
            }
            if (locPackedLight >= 0) {
                GL20.glUniform1i(locPackedLight, packedLight);
            }
            if (locLight0 >= 0) {
                GL20.glUniform3f(locLight0, LIGHT0[0], LIGHT0[1], LIGHT0[2]);
            }
            if (locLight1 >= 0) {
                GL20.glUniform3f(locLight1, LIGHT1[0], LIGHT1[1], LIGHT1[2]);
            }

            // One upload for the whole model, then two passes over the material groups.
            if (!mesh.upload(writer.buffer(), writer.writtenVertices())) {
                return false;
            }
            // OPAQUE and MASK first, BLEND second. Not cosmetic: a blended surface must blend over an
            // image that is already complete, and the opaque pass is what writes the depth that keeps a
            // model's own far side behind its near one. See applyMaterialState for the depth rule.
            int drawn = drawGroups(mesh, writer, scene, textures, fallbackTexture, false);
            drawn += drawGroups(mesh, writer, scene, textures, fallbackTexture, true);
            mesh.unbind();
            if (TRACE) {
                Model3D.LOGGER.info("Model3D: CPU path drew {} vertices in {} material group(s) for {}",
                        writer.writtenVertices(), drawn, scene.name());
            }
            return drawn > 0;
        }
    }

    /**
     * One pass over the material groups: {@code blendPass} false selects OPAQUE and MASK materials,
     * true selects BLEND ones.
     *
     * <p>Everything that can differ between materials - the texture, the colour, the cutout threshold,
     * blending, culling - is set here, per group, from the material itself.
     */
    private static int drawGroups(ModelCpuMesh mesh, CpuVertexWriter writer, ModelScene scene,
                                  ResourceLocation[] textures, ResourceLocation fallbackTexture,
                                  boolean blendPass) {
        int drawn = 0;
        for (Map.Entry<Integer, java.util.List<int[]>> entry : writer.ranges().entrySet()) {
            int materialIndex = entry.getKey();
            ModelMaterial material = material(scene, materialIndex);
            boolean blend = material.alphaMode() == ModelMaterial.AlphaMode.BLEND;
            if (blend != blendPass) {
                continue;
            }
            bindMaterialTexture(textures, materialIndex, fallbackTexture);
            applyMaterialUniforms(material);
            applyMaterialState(material);
            if (TRACE) {
                // As-run state, read back from GL rather than assumed from the calls above: a state
                // that "was set" and a state that is in force are different claims, and this is the
                // one that decides what the pixels look like.
                float[] base = material.baseColorFactor();
                java.nio.FloatBuffer readBack = org.lwjgl.BufferUtils.createFloatBuffer(4);
                if (program != 0 && locColor >= 0) {
                    GL20.glGetUniformfv(program, locColor, readBack);
                }
                Model3D.LOGGER.info("Model3D: group material[{}] '{}' alphaMode={} baseAlpha={} ->"
                                + " GL_BLEND={} depthMask={} depthTest={} depthFunc={} funcRGB=({},{})"
                                + " equationRGB={} u_color=({},{},{},{}) TRACE={}",
                        materialIndex, material.name(),
                        material.alphaMode(), base.length > 3 ? base[3] : 1.0f,
                        GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                        GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
                        GL11.glGetInteger(0x80C9), GL11.glGetInteger(0x80C8),
                        GL11.glGetInteger(0x8009),
                        readBack.get(0), readBack.get(1), readBack.get(2), readBack.get(3),
                        blendPass);
            }
            for (int[] range : entry.getValue()) {
                if (range[1] <= 0) {
                    continue;
                }
                mesh.drawRange(range[0], range[1]);
                drawn++;
            }
        }
        return drawn;
    }

    /**
     * Binds the texture for one material group, falling back to the registered 1x1 white texture.
     *
     * <p>Texture object 0 is not "no texture": it is an incomplete texture, so every sample returns
     * black and the model draws as a silhouette. The fallback is what makes an untextured material
     * show its lighting instead.
     */
    private static void bindMaterialTexture(ResourceLocation[] textures, int materialIndex,
                                            ResourceLocation fallbackTexture) {
        ResourceLocation texture = materialIndex >= 0 && materialIndex < textures.length
                ? textures[materialIndex] : null;
        if (texture == null) {
            texture = fallbackTexture;
        }
        AbstractTexture bound = texture == null ? null
                : Minecraft.getInstance().getTextureManager().getTexture(texture);
        // Tracked, not raw: _bindTexture picks its target from a per-unit cache indexed by the
        // manager's tracked unit.
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(bound == null ? 0 : bound.getId());
    }

    /** The per-material uniforms: colour, alpha test and its cutoff. */
    private static void applyMaterialUniforms(ModelMaterial material) {
        float[] base = material.baseColorFactor();
        if (locColor >= 0) {
            GL20.glUniform4f(locColor, base[0], base[1], base[2], base[3]);
        }
        if (locAlphaMode >= 0) {
            GL20.glUniform1i(locAlphaMode, alphaModeCode(material.alphaMode()));
        }
        if (locAlphaCutoff >= 0) {
            GL20.glUniform1f(locAlphaCutoff, material.alphaCutoff());
        }
    }

    /** The shader's alpha-mode codes, named rather than taking the enum's ordinal. */
    private static int alphaModeCode(ModelMaterial.AlphaMode mode) {
        return switch (mode) {
            case OPAQUE -> 0;
            case MASK -> 1;
            case BLEND -> 2;
        };
    }

    /**
     * The GL state one material needs, replacing the {@code RenderType} choice the vanilla pipeline
     * used to make: alpha mode decides blending, and {@code doubleSided} decides culling.
     *
     * <h2>Depth writes stay on for every mode, including BLEND</h2>
     * Vanilla turns depth writes off for translucency, which works because it also draws translucent
     * geometry last and sorted back-to-front. This renderer has no sort, and without one the trade is
     * not symmetric: a material that declares BLEND but whose texels are opaque - which is most of
     * them, because exporters mark whole models BLEND - stops occluding anything the moment depth
     * writes stop, so the model's own interior shows through its skin. Measured on a control model
     * whose single material is BLEND with alpha exactly 1.0: opaque renders a solid wall, BLEND
     * rendered the inside of the cube through its own front face.
     *
     * <p>Writing depth costs the other half of the trade instead: geometry drawn <i>after</i> a
     * genuinely translucent surface is hidden behind it. The two-pass draw in {@link #drawGroups} keeps
     * that to blend-material-against-blend-material, since every opaque surface is already in the
     * depth buffer and on screen by then, and a canopy blending over an intact aircraft is exactly
     * what translucency is for.
     *
     * <h2>Culling follows the model, not the alpha mode</h2>
     * A single-sided surface is culled whatever its alpha mode. Turning culling off for every BLEND
     * material drew the far side of a translucent skin as well as the near side, which is the second
     * way this renderer could show a model's inside through its outside.
     */
    private static void applyMaterialState(ModelMaterial material) {
        if (material.alphaMode() == ModelMaterial.AlphaMode.BLEND) {
            GlStateManager._enableBlend();
            GlStateManager._blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            GlStateManager._disableBlend();
        }
        GlStateManager._depthMask(true);
        if (material.doubleSided()) {
            GlStateManager._disableCull();
        } else {
            GlStateManager._enableCull();
        }
    }

    private static void setMatrix4(int location, float[] values) {
        if (location >= 0) {
            GL20.glUniformMatrix4fv(location, false, values);
        }
    }

    private static void setMatrix3(int location, float[] values) {
        if (location >= 0) {
            GL20.glUniformMatrix3fv(location, false, values);
        }
    }

    /**
     * Compiles the ported shader pair once, on the render thread.
     *
     * <p>Loaded from the mod jar's {@code assets/} tree rather than held as Java string constants, so the
     * GLSL can be read and diffed against the reference it was ported from instead of being buried in
     * escaped string concatenation.
     */
    static boolean ensureCompiled() {
        if (program != 0) {
            return true;
        }
        if (compileFailed) {
            return false;
        }
        RenderSystem.assertOnRenderThread();
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, read(VSH), VSH);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, read(FSH), FSH);
            int linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vs);
            GL20.glAttachShader(linked, fs);
            // Pinned to match the VAO's attribute pointers, not left to the driver - see ModelCpuMesh.
            GL20.glBindAttribLocation(linked, 0, "a_position");
            GL20.glBindAttribLocation(linked, 1, "a_uv");
            GL20.glBindAttribLocation(linked, 2, "a_normal");
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                Model3D.LOGGER.error("Model3D: the CPU mesh shader failed to link: {}",
                        GL20.glGetProgramInfoLog(linked));
                compileFailed = true;
                return false;
            }
            GL20.glDetachShader(linked, vs);
            GL20.glDetachShader(linked, fs);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            locProj = GL20.glGetUniformLocation(linked, "u_proj");
            locMv = GL20.glGetUniformLocation(linked, "u_mv");
            locIvr = GL20.glGetUniformLocation(linked, "u_ivr");
            locColor = GL20.glGetUniformLocation(linked, "u_color");
            locOverlay = GL20.glGetUniformLocation(linked, "u_packedOverlay");
            locFogStart = GL20.glGetUniformLocation(linked, "u_fogStart");
            locFogEnd = GL20.glGetUniformLocation(linked, "u_fogEnd");
            locFogColor = GL20.glGetUniformLocation(linked, "u_fogColor");
            locFogShape = GL20.glGetUniformLocation(linked, "u_fogShape");
            locLight0 = GL20.glGetUniformLocation(linked, "u_light0");
            locLight1 = GL20.glGetUniformLocation(linked, "u_light1");
            locAlphaMode = GL20.glGetUniformLocation(linked, "u_alphaMode");
            locAlphaCutoff = GL20.glGetUniformLocation(linked, "u_alphaCutoff");
            locPackedLight = GL20.glGetUniformLocation(linked, "u_packedLight");

            GL20.glUseProgram(linked);
            // Sampler units, assigned once at link time as the reference does. Unit 0 is the model's
            // texture, 1 the overlay, 2 the lightmap - the same assignment the entity shader uses.
            assignSampler(linked, "Sampler0", 0);
            assignSampler(linked, "Sampler1", 1);
            assignSampler(linked, "Sampler2", 2);
            GL20.glUseProgram(0);

            program = linked;
            Model3D.LOGGER.info("Model3D: the CPU mesh shader compiled (port of the verified"
                    + " ysm_epicfight_compat cpu_skin path)");
            return true;
        } catch (Throwable t) {
            Model3D.LOGGER.error("Model3D: the CPU mesh shader could not be built", t);
            compileFailed = true;
            return false;
        }
    }

    private static void assignSampler(int linked, String name, int unit) {
        int location = GL20.glGetUniformLocation(linked, name);
        if (location >= 0) {
            GL20.glUniform1i(location, unit);
        }
    }

    private static int compile(int type, String source, String label) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException(label + " failed to compile:\n"
                    + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = ModelCpuRenderPath.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("shader resource not found: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Frees every model's GL resources, and the program, so the next draw rebuilds them.
     *
     * <p>This is the only teardown the live path has, and it must be reached from the client's
     * reload and unload hooks: the meshes are keyed by model id in a static map and the program is a
     * static field, so without this call a session keeps every VAO/VBO it has ever drawn, and a
     * resource reload that changed the GLSL keeps the old program - the reload would silently do
     * nothing.
     */
    static void disposeAll() {
        for (ModelCpuMesh mesh : MESHES.values()) {
            mesh.dispose();
        }
        MESHES.clear();
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        // Cleared with the program so a reload retries a shader that failed to compile, instead of
        // caching one failure for the life of the process.
        compileFailed = false;
        locProj = -1;
        locMv = -1;
        locIvr = -1;
        locColor = -1;
        locOverlay = -1;
        locFogStart = -1;
        locFogEnd = -1;
        locFogColor = -1;
        locFogShape = -1;
        locLight0 = -1;
        locLight1 = -1;
        locAlphaMode = -1;
        locAlphaCutoff = -1;
        locPackedLight = -1;
        // The shared writer's native buffer goes with them: it is sized for the models that were
        // loaded, and the next draw re-allocates for whatever is loaded then.
        WRITER.free();
    }

    /** Frees one model's GL resources. */
    static void dispose(String modelKey) {
        ModelCpuMesh mesh = MESHES.remove(modelKey);
        if (mesh != null) {
            mesh.dispose();
        }
    }

    private static ModelMaterial material(ModelScene scene, int index) {
        ModelMaterial[] materials = scene.materials();
        if (index >= 0 && index < materials.length && materials[index] != null) {
            return materials[index];
        }
        return ModelMaterial.defaultMaterial();
    }
}
