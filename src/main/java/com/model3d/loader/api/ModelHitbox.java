package com.model3d.loader.api;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;

/**
 * The collision box a model occupies, in blocks, resolved from the model itself or from what its
 * author declared.
 *
 * <h2>Why this is API and not a line in the consumer's entity</h2>
 * A 40-block aircraft is not a 40-block cube, and it is certainly not the 0.6 x 1.8 box a vanilla mob
 * shape gives you. Two numbers are needed, and both are properties of the model, not of the entity:
 *
 * <ul>
 *   <li><b>Its size</b> - derived from the model's geometry and the scale that ended up applied to it.
 *       A consumer that computed this itself would be re-deriving normalization, the descriptor's
 *       multiplier and the carrier's own scale, and would get a different answer than the renderer
 *       used the moment any of the three changed.</li>
 *   <li><b>Where it sits relative to the entity's origin</b> - the origin is not necessarily the
 *       centre of the model. A model exported with its pivot at the nose has a box that extends
 *       further behind the entity than in front, and a box centred on the entity would let players
 *       walk through the nose.</li>
 * </ul>
 *
 * <h2>Two ways to get a box</h2>
 * <ol>
 *   <li><b>Automatic</b> - the model's own bounding box, scaled. Right for most models, and right for
 *       anything roughly box-shaped.</li>
 *   <li><b>Declared</b> - {@code model.json} may carry {@code "hitbox": [sizeX, sizeY, sizeZ]} and
 *       {@code "hitboxOffset": [x, y, z]}, both in blocks. A declared box is used as written and is
 *       <b>not</b> multiplied by the scale, because writing it down is how an author says "the derived
 *       answer is wrong": an aircraft wants a long, flat box around the fuselage rather than a slab
 *       containing the wings and the tail, and nothing can infer that.</li>
 * </ol>
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * // In the entity, when the model is known:
 * ModelHitbox.Box box = ModelHitbox.of(handle, carrier.modelScale());
 * if (!box.isEmpty()) {
 *     setBoundingBox(box.aabb(getX(), getY(), getZ()));
 * }
 *
 * // Or let Minecraft shape the entity box, which is width x height with a square footprint:
 * EntityDimensions dimensions = box.dimensions();
 * }</pre>
 *
 * <p>Minecraft's entity collision box is {@code width x height} with a <b>square footprint</b>, so a
 * model that is 30 blocks wide and 40 long cannot be expressed through it. {@link Box#aabb} is the way
 * to get the real shape: override the entity's own bounding-box method. {@link Box#dimensions} is the
 * vanilla-shaped approximation for callers that cannot.
 *
 * <p>None of this touches the render path: the box is arithmetic over values the loader already has,
 * so it is safe to recompute when a model or scale changes rather than caching it.
 */
public final class ModelHitbox {

    private ModelHitbox() {
    }

    /**
     * An axis-aligned box in blocks, relative to the entity's own origin (not to the world).
     *
     * <p>Offsets rather than a size and a centre, because that is what both a collision box and a
     * culling box need, and because it keeps an asymmetric model's asymmetry visible instead of
     * rounding it to a centre.
     */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        public double sizeX() {
            return maxX - minX;
        }

        public double sizeY() {
            return maxY - minY;
        }

        public double sizeZ() {
            return maxZ - minZ;
        }

        public double centerX() {
            return (minX + maxX) * 0.5;
        }

        public double centerY() {
            return (minY + maxY) * 0.5;
        }

        public double centerZ() {
            return (minZ + maxZ) * 0.5;
        }

        /** True when the model has no usable geometry and declares no box - fall back to vanilla. */
        public boolean isEmpty() {
            return sizeX() <= 0.0 || sizeY() <= 0.0 || sizeZ() <= 0.0;
        }

        /**
         * This box in world coordinates for an entity at {@code (x, y, z)}, where {@code y} is the
         * entity's own origin (its feet, as Minecraft uses it).
         */
        public AABB aabb(double x, double y, double z) {
            return new AABB(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
        }

        /**
         * The vanilla-shaped approximation: {@code width x height}, square in footprint.
         *
         * <p>Width is the larger of the horizontal sizes, because a box narrower than the model lets
         * players walk through the wings - and the whole reason this class exists is that "walk through
         * the model" is the bug. The depth is lost: Minecraft's entity box cannot express it.
         *
         * <p><b>For anything long and thin, prefer {@link #aabb}.</b> Measured on this project's sample
         * aircraft, whose automatic box is {@code size=(40.00, 3.82, 6.16)}: this method answers
         * 40 x 40, a square as long as the aircraft - better than a mob-sized box, but far from the
         * shape. The entity's own bounding-box hook is the place to use the real one.
         *
         * <p>Clamped to at least one sixteenth of a block, the smallest size the game itself uses: a
         * zero-sized dimension produces a degenerate box that nothing collides with.
         */
        public EntityDimensions dimensions() {
            float width = (float) Math.max(Math.max(sizeX(), sizeZ()), 1.0 / 16.0);
            float height = (float) Math.max(sizeY(), 1.0 / 16.0);
            return EntityDimensions.scalable(width, height);
        }

        /** The box's longest axis in blocks. */
        public double longestExtent() {
            return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
        }

        public String describe() {
            return String.format("size=(%.2f, %.2f, %.2f) offset=(%.2f, %.2f, %.2f)",
                    sizeX(), sizeY(), sizeZ(), centerX(), centerY(), centerZ());
        }

        @Override
        public String toString() {
            return "ModelHitbox.Box(" + describe() + ")";
        }
    }

    /**
     * The box for {@code handle}, sized by the scale the loader would apply to it
     * ({@link ModelScale#forHandle}).
     *
     * <p>Use this when nothing else has scaled the model. When the carrier's own
     * {@link ModelCarrier#modelScale()} is authoritative - it is what the client applied - pass it to
     * {@link #of(ModelHandle, float)} instead, or the hitbox will disagree with what is on screen.
     */
    public static Box of(ModelHandle handle) {
        // Guard before asking for the scale: ModelScale.forHandle(null) throws, and a null handle is
        // the documented "no model" case that must answer with an empty box rather than an NPE on a
        // path a consumer reaches whenever an entity's model has not arrived yet.
        return handle == null ? EMPTY : of(handle, ModelScale.forHandle(handle));
    }

    /**
     * The box for {@code handle} at {@code blocksPerUnit} blocks per model unit.
     *
     * <p>{@code blocksPerUnit <= 0} means "not set", matching {@link ModelCarrier#modelScale()}, and
     * falls back to the scale the loader would apply.
     *
     * <p>A declared box wins over both: see {@link com.model3d.loader.resource.ModelDescriptor#hitbox()}.
     */
    public static Box of(ModelHandle handle, float blocksPerUnit) {
        if (handle == null) {
            return EMPTY;
        }
        float[] declared = handle.descriptor() == null ? null : handle.descriptor().hitbox();
        if (declared != null && declared.length >= 3) {
            // Declared in blocks, used as written: not multiplied by any scale. The offset is the
            // author's statement about where the box sits, which for a model whose pivot is at the nose
            // is the difference between a box that contains the aircraft and one that contains half of it.
            float[] offset = handle.descriptor().hitboxOffset();
            double halfX = declared[0] * 0.5;
            double halfY = declared[1] * 0.5;
            double halfZ = declared[2] * 0.5;
            return new Box(offset[0] - halfX, offset[1] - halfY, offset[2] - halfZ,
                    offset[0] + halfX, offset[1] + halfY, offset[2] + halfZ);
        }

        float scale = blocksPerUnit > 0.0f && Float.isFinite(blocksPerUnit)
                ? blocksPerUnit
                : ModelScale.forHandle(handle);
        float[] bounds = handle.bounds();
        if (bounds == null || bounds.length < 6) {
            return EMPTY;
        }
        // The bounds are already relative to the model's origin, so they are the box: no re-centring,
        // which is what preserves an off-centre pivot.
        return new Box(bounds[0] * scale, bounds[1] * scale, bounds[2] * scale,
                bounds[3] * scale, bounds[4] * scale, bounds[5] * scale);
    }

    /** Returned for a null handle or a model with no geometry; see {@link Box#isEmpty()}. */
    public static final Box EMPTY = new Box(0, 0, 0, 0, 0, 0);
}
