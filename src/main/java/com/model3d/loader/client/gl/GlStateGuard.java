package com.model3d.loader.client.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Saves the GL state this renderer touches before it draws, and puts it back afterwards.
 *
 * <p>This class is not optional politeness. An entity renderer runs in the middle of vanilla's
 * frame: terrain, other entities, particles and the GUI all draw after it, and several of them
 * assume state they did not set themselves. A leaked program, VAO, texture binding, blend mode,
 * depth mask or cull face therefore shows up as a bug in an unrelated part of the frame - "my
 * block outlines vanished" or "the whole world is see-through" - which is exactly the kind of
 * failure that gets misattributed to whatever mod was added last.
 *
 * <p>Usage:
 * <pre>
 *   try (GlStateGuard guard = new GlStateGuard()) {
 *       ... draw ...
 *   }
 * </pre>
 *
 * <h2>Why raw GL queries instead of {@code GlStateManager}</h2>
 * {@code GlStateManager} tracks a subset of this state for its own caching, but it exposes no
 * getters for blend factors, depth mask or cull mode, and its depth-function field is private.
 * Reading the real pipeline state with {@code glGet*} cannot disagree with the driver, which is
 * the property that matters here.
 *
 * <h2>What is deliberately not saved</h2>
 * Viewport, scissor, stencil, colour mask, polygon offset and the projection/modelview matrices.
 * This renderer never changes any of them, so saving them would be dead state that a reader has
 * to check to discover it is dead. If a future change touches one of them, it must be added here.
 *
 * <p>Render thread only.
 */
public final class GlStateGuard implements AutoCloseable {

    /**
     * Texture units this guard saves and restores: 0 is the model's own texture, 1 the overlay and
     * 2 the lightmap, which is every unit the CPU render path binds.
     *
     * <p>All three, not just unit 0: a renderer that writes unit 2 and restores only unit 0 leaves
     * the lightmap bound for whatever draws next, and the symptom is another mod's entity sampling a
     * 16x16 light texture - a bug that appears in code nobody touched.
     */
    private static final int USED_TEXTURE_UNITS = 3;

    // Program
    private final int previousProgram;
    // Vertex array object
    private final int previousVertexArray;
    // Texture: the active unit plus what is bound to each unit this renderer writes to.
    private final int previousActiveTexture;
    private final int[] previousTextureOnUnits = new int[USED_TEXTURE_UNITS];
    // Blending
    private final boolean blendEnabled;
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    // Depth
    private final boolean depthTestEnabled;
    private final int depthFunc;
    private final boolean depthWriteEnabled;
    // Culling
    private final boolean cullEnabled;
    private final int cullMode;

    private boolean closed;

    /**
     * Snapshots the state this renderer is allowed to change. Construct on the render thread,
     * immediately before the draw, and close it in a finally-block - a draw that throws must not
     * leave the state captured half-applied.
     */
    public GlStateGuard() {
        RenderSystem.assertOnRenderThread();
        int[] scratch = new int[1];

        GL11C.glGetIntegerv(GL20C.GL_CURRENT_PROGRAM, scratch);
        this.previousProgram = scratch[0];

        GL11C.glGetIntegerv(GL30C.GL_VERTEX_ARRAY_BINDING, scratch);
        this.previousVertexArray = scratch[0];

        GL11C.glGetIntegerv(GL13C.GL_ACTIVE_TEXTURE, scratch);
        this.previousActiveTexture = scratch[0];
        // The tracker must agree with the driver before anything below trusts it: both the reads and
        // the restore move the active unit through GlStateManager, which skips the GL call when its
        // tracked unit already matches - so a caller that used raw glActiveTexture earlier would make
        // this guard read one unit's binding into another unit's slot, and "restore" the wrong
        // texture. The live path uses the tracked API throughout; this is what makes the guard safe
        // for callers that do not.
        resyncActiveTextureTracker(this.previousActiveTexture);
        // Read the bindings of every unit this renderer writes to.
        for (int unit = 0; unit < USED_TEXTURE_UNITS; unit++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glGetIntegerv(GL11C.GL_TEXTURE_BINDING_2D, scratch);
            this.previousTextureOnUnits[unit] = scratch[0];
        }
        GlStateManager._activeTexture(this.previousActiveTexture);

        this.blendEnabled = GL11C.glIsEnabled(GL11C.GL_BLEND);
        if (blendEnabled) {
            // glGet* on a disabled capability is undefined, so only query the factors when
            // blending is actually on.
            GL11C.glGetIntegerv(GL14C.GL_BLEND_SRC_RGB, scratch);
            this.blendSrcRgb = scratch[0];
            GL11C.glGetIntegerv(GL14C.GL_BLEND_DST_RGB, scratch);
            this.blendDstRgb = scratch[0];
            GL11C.glGetIntegerv(GL14C.GL_BLEND_SRC_ALPHA, scratch);
            this.blendSrcAlpha = scratch[0];
            GL11C.glGetIntegerv(GL14C.GL_BLEND_DST_ALPHA, scratch);
            this.blendDstAlpha = scratch[0];
        } else {
            this.blendSrcRgb = GL11C.GL_SRC_ALPHA;
            this.blendDstRgb = GL11C.GL_ONE_MINUS_SRC_ALPHA;
            this.blendSrcAlpha = GL11C.GL_ONE;
            this.blendDstAlpha = GL11C.GL_ONE_MINUS_SRC_ALPHA;
        }

        this.depthTestEnabled = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        if (depthTestEnabled) {
            GL11C.glGetIntegerv(GL11C.GL_DEPTH_FUNC, scratch);
            this.depthFunc = scratch[0];
        } else {
            this.depthFunc = GL11C.GL_LEQUAL;
        }
        GL11C.glGetIntegerv(GL11C.GL_DEPTH_WRITEMASK, scratch);
        this.depthWriteEnabled = scratch[0] != GL11C.GL_FALSE;

        this.cullEnabled = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        if (cullEnabled) {
            GL11C.glGetIntegerv(GL11C.GL_CULL_FACE_MODE, scratch);
            this.cullMode = scratch[0];
        } else {
            this.cullMode = GL11C.GL_BACK;
        }
    }

    /** Restores every piece of state captured above. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Program binding goes through GlStateManager so its internal cache agrees with the
        // driver. Restoring with a raw glUseProgram would leave the manager believing the model's
        // program was still bound, and the next vanilla draw could then skip a rebind.
        GlStateManager._glUseProgram(previousProgram);
        GL30C.glBindVertexArray(previousVertexArray);

        // Restore each unit's binding, then put the active unit back. Doing it in this order means
        // every bind lands on the unit it came from.
        for (int unit = 0; unit < USED_TEXTURE_UNITS; unit++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(previousTextureOnUnits[unit]);
        }
        GlStateManager._activeTexture(previousActiveTexture);

        // The restore goes through the tracked API, not raw GL - the same rule the program binding
        // above follows, and for a sharper reason than tidiness: GlStateManager issues a call only when
        // its cache disagrees with the requested value, so a raw restore here does not just skip one
        // update, it makes every later tracked call a no-op for as long as the two disagree.
        //
        // Measured, after this class had been restoring rawly: a per-material BLEND group called
        // _enableBlend()/_blendFunc() and the surface still drew fully opaque, because the raw
        // glDisable(GL_BLEND) restoring "blending was off at capture time" left the cache saying
        // "enabled", so the next frame's tracked enable was skipped and the driver kept blending off.
        // Forcing a cache transition by hand made the identical draw blend again, which is what
        // identified this and not the shader.
        if (blendEnabled) {
            GlStateManager._enableBlend();
        } else {
            GlStateManager._disableBlend();
        }
        GlStateManager._blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);

        if (depthTestEnabled) {
            GlStateManager._enableDepthTest();
        } else {
            GlStateManager._disableDepthTest();
        }
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthWriteEnabled);

        if (cullEnabled) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }
        // Raw on purpose: GlStateManager tracks the cull *enable* but exposes no setter for the face,
        // so there is no cache here to disagree with - the failure mode documented above needs a
        // tracked setter that skips its call, and this is not one.
        GL11C.glCullFace(cullMode);
    }

    /**
     * Makes {@code GlStateManager}'s active-texture tracker agree with the driver.
     *
     * <p>{@code _activeTexture} issues {@code glActiveTexture} only when the requested unit differs
     * from its tracked one, so a driver moved by a raw call is invisible to it. Asking for a unit it
     * is not on, then for the one the driver is actually on, leaves both correct - the first call
     * moves the driver, the second moves it back and updates the tracker.
     */
    private static void resyncActiveTextureTracker(int glEnum) {
        if (GlStateManager._getActiveTexture() == glEnum - GL13C.GL_TEXTURE0) {
            return;
        }
        GlStateManager._activeTexture(glEnum == GL13C.GL_TEXTURE0
                ? GL13C.GL_TEXTURE1 : GL13C.GL_TEXTURE0);
        GlStateManager._activeTexture(glEnum);
    }

    // Read-only accessors used by the verification harness (MeshUploadCheck) to report what state was
    // captured. Without them a fault between "guard taken" and "program bound" cannot be attributed:
    // the captured values are exactly what the guard will restore, and a nonsensical one (program 0
    // where vanilla had one bound) narrows the cause to the snapshot.
    public int previousProgram() {
        return previousProgram;
    }

    public int previousVertexArray() {
        return previousVertexArray;
    }

    public int previousActiveTexture() {
        return previousActiveTexture;
    }

    public boolean depthWriteEnabled() {
        return depthWriteEnabled;
    }
}