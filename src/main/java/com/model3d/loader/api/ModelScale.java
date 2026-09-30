package com.model3d.loader.api;

import com.model3d.loader.scene.ModelScene;

/**
 * Decides how many Minecraft blocks one unit of a model occupies.
 *
 * <p>This is the difference between "the model loaded" and "the model is visible". Interchange
 * formats carry no unit: a glTF aircraft is authored in metres or centimetres, an OBJ exported
 * from a modelling package may be in anything at all, and the real corpus in this repository has
 * a Su-30 whose fuselage is 250 units long. At scale 1 that is 250 blocks - it would swallow the
 * render distance and be clipped into invisibility by the frustum, which reads as "nothing
 * rendered".
 *
 * <h2>Rule</h2>
 * The descriptor's {@code scale} is a <b>multiplier on top of</b> automatic normalization, not a
 * replacement for it, because a mod author setting {@code "scale": 2} means "twice the size of
 * the sensible default", not "two blocks". The default is chosen so the model's longest axis
 * comes out at {@link #DEFAULT_TARGET_BLOCKS}; a descriptor value of 0 is rejected at parse time
 * so this can never divide by it.
 */
public final class ModelScale {

    /**
     * Longest-axis size, in blocks, that an undeclared model is normalized to.
     *
     * <h2>Why it is 40 and not 4</h2>
     * This began at 4 blocks, argued from Minecraft's own scale - a pig is about 1 block long, a player
     * 1.8 tall. That reasoning was sound about mobs and wrong about models. A model file's units are
     * arbitrary: the Su-30 in this repository is authored 279 units long, and normalizing it to 4
     * blocks produces an object 4.0 by 0.38 by 0.62 blocks. Viewed from any normal distance that is not
     * an aircraft, it is a sliver - and worse, seen from above (which is where a player's camera
     * usually is) it is almost exactly edge-on and effectively invisible.
     *
     * <p>The cost of the original value was paid in misdiagnosis: the model uploaded, textured,
     * transformed and drew correctly, and its on-screen extent measured single-digit pixels, so every
     * report described "the model did not load". A default that makes correct work look broken is the
     * wrong default, whatever the numbers say about pigs.
     *
     * <p>40 blocks makes the same model a landmark - clearly visible, and still small enough to see
     * whole from a few blocks away. A model that wants to be mob-sized can say so with
     * {@code "targetBlocks"} in its {@code model.json}, or scale the default with {@code "scale"}.
     */
    public static final float DEFAULT_TARGET_BLOCKS = 40.0f;

    private ModelScale() {
    }

    /**
     * Blocks per model unit for {@code handle}: automatic normalization times the descriptor's
     * relative multiplier.
     */
    public static float forHandle(ModelHandle handle) {
        ModelScene scene = handle.scene();
        float authored = handle.descriptor().scale();
        if (scene == null || scene.longestExtent() <= 0.0f) {
            return authored;
        }
        return scene.scaleForTargetSize(targetBlocksFor(handle)) * authored;
    }

    /**
     * The normalization target for a model: its own declared {@code targetBlocks}, or the default.
     *
     * <p>Per-model because the right size is a property of the model, not of the loader - a suitcase and
     * an airliner should not be normalized to the same footprint, and the default can only ever suit
     * one of them. A declaration of zero or less is ignored rather than honoured: it would collapse
     * every vertex onto the origin, which presents as a model that failed to load instead of as a bad
     * setting, and that is the worse of the two failures to debug.
     */
    public static float targetBlocksFor(ModelHandle handle) {
        float declared = handle.descriptor().targetBlocks();
        return declared > 0.0f ? declared : DEFAULT_TARGET_BLOCKS;
    }

    /** Blocks per model unit for a scene with no descriptor: pure normalization. */
    public static float forScene(ModelScene scene) {
        if (scene == null || scene.longestExtent() <= 0.0f) {
            return 1.0f;
        }
        return scene.scaleForTargetSize(DEFAULT_TARGET_BLOCKS);
    }

    /**
     * How long the model's longest axis is <b>in blocks</b> once placed.
     *
     * <p>This is the number a caller actually wants when sizing anything around a model - a culling
     * box, a spawn distance, a "is it even visible" check - and it is deliberately here rather than
     * left as "multiply the scale by the extent yourself": the two factors come from different places
     * (the descriptor and the file), and getting their product wrong is how a model ends up culled
     * while it fills the screen.
     *
     * @return the longest axis in blocks, or 0 when the model has no geometry
     */
    public static float longestAxisBlocks(ModelHandle handle) {
        return forHandle(handle) * handle.longestExtent();
    }

    /**
     * The radius in blocks that contains the model: half its longest axis.
     *
     * <p>For a culling box or a proximity check, this is the useful form - a model is centred on its
     * origin, so it reaches this far in every direction at worst.
     */
    public static float boundingRadiusBlocks(ModelHandle handle) {
        return longestAxisBlocks(handle) * 0.5f;
    }
}
