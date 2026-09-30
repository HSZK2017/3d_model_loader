package com.model3d.loader.client.render;

import com.model3d.loader.math.Mat4;

/**
 * Bakes a model's instance transform into its vertices.
 *
 * <h2>Why this class exists</h2>
 * The custom-shader renderer applied the instance's scale, yaw offset and pivot <b>inside the vertex
 * shader</b>: {@code uModelView} was {@code poseStackTop x rootTransform}, so the transform was folded
 * into the model-view matrix and never appeared in any Java code. Replacing the shader with the vanilla
 * vertex pipeline therefore silently dropped it, and the symptom was unmistakable once seen: a model
 * authored 279 units long, submitted unscaled, is a 279-block object that collapses to a one-block
 * artifact at the entity's feet - a "1x1 block" that is the entire aircraft seen from inside itself.
 *
 * <p>Minecraft's pipeline has no equivalent slot for it. The {@code PoseStack} is the caller's (it holds
 * the entity's placement, and the renderer must not mutate state it does not own), and a
 * {@code VertexConsumer} writes model-space vertices directly. So the transform is applied here, to
 * each vertex, exactly as the shader used to.
 *
 * <h2>Relationship to the pose stack</h2>
 * These are two halves of one product, and the order matters:
 * <pre>
 *   final position = poseStackTop * rootTransform * localPosition
 * </pre>
 * {@code rootTransform} is applied here (it is a property of the model instance), and
 * {@code poseStackTop} is applied by {@code VertexConsumer.vertex(PoseStack.Pose, ...)} (it is a property
 * of the entity's placement). Applying the root transform again on the pose stack, or leaving it out
 * here, both produce the wrong size - which is what happened.
 */
final class InstanceTransform {

    private final float[] matrix;
    private final boolean flipX;
    private final boolean flipY;
    private final boolean flipZ;

    private InstanceTransform(float[] matrix, String axes) {
        this.matrix = matrix;
        this.flipX = axes.contains("x");
        this.flipY = axes.contains("y");
        this.flipZ = axes.contains("z");
    }

    /**
     * Wraps an instance's root transform for per-vertex application, with the model's own mirror
     * setting (or the default when it declares none).
     *
     * <p>Null-tolerant: a model with no transform is simply drawn at its authored scale, which is what
     * an instance created before its descriptor is read should do.
     *
     * @param mirrorAxes the model's {@code "mirror"} value, or null for {@link #DEFAULT_MIRROR}
     */
    static InstanceTransform of(Mat4 rootTransform, String mirrorAxes) {
        String axes = !OVERRIDE.isEmpty()
                ? OVERRIDE
                : (mirrorAxes == null || mirrorAxes.isEmpty() ? DEFAULT_MIRROR : mirrorAxes);
        float[] raw = rootTransform == null ? null : rootTransform.raw();
        return new InstanceTransform(raw, axes);
    }

    /**
     * Which axes to negate before the instance transform, as a string of axis letters.
     *
     * <h2>Why this exists</h2>
     * A mesh drawn through this renderer ends up in whatever orientation the product of the pose
     * stack's flip and the model's own space produces. A model authored in a different convention can
     * therefore arrive upside-down or inside-out, and which axis corrects it is a property of the
     * model, not something that can be reasoned out once for all of them: a uniform negate on one axis
     * is a reflection (correcting an inside-out model), on two axes it is a half-turn (correcting an
     * upside-down one), and the two are visually distinct but easy to confuse.
     *
     * <h2>Who decides</h2>
     * The model does, through {@code "mirror"} in its {@code model.json}; {@link #DEFAULT_MIRROR}
     * applies when it says nothing. The system property is an override for comparing two settings on
     * the same build - which is how the default below was chosen - and it wins over every model when
     * it is set, because a debugging override that only applies to some files is not an override.
     *
     * <pre>
     *   model.json: { "mirror": "none" }   the model is already coherent
     *   -Dmodel3d.mirror=xyz               override every model (x, y or z)
     * </pre>
     *
     * <p>The built-in default is {@code xy} because "upside down" and "facing backwards" are one
     * fault, not two: a model whose up axis is negated relative to its forward axis is rotated half a
     * turn about the remaining axis, and negating exactly two axes expresses that. Corrected on a real
     * aircraft by reading its landing gear - visible on top rather than underneath - and its nose being
     * on the wrong end; both are the same half-turn, and fixing only one of them leaves the other.
     */
    static final String DEFAULT_MIRROR = "xy";

    /** {@code -Dmodel3d.mirror}; empty when unset, in which case each model decides. */
    private static final String OVERRIDE = System.getProperty("model3d.mirror", "")
            .trim().toLowerCase(java.util.Locale.ROOT);

    /**
     * Whether to invert normals before rendering.
     *
     * <p>Separate from the axis flip because the two are independent faults with independent evidence.
     * The geometry on screen is what tells you about the flip: a scrambled model means the axes are
     * wrong, a coherent model with holes means the winding is wrong. Lighting tells you about the
     * normals: a model that is silhouetted black against a bright sky while its triangles are otherwise
     * in the right places has normals pointing away from every light.
     *
     * <p>Measured on a real model here: with no axis flip the aircraft is a clean, correctly-shaped
     * silhouette and completely unlit, which is the normal fault rather than the axis one. Inverting is
     * the fix for that and does nothing to the geometry, so it cannot reintroduce the scrambling that an
     * axis flip causes.
     *
     * <pre>
     *   -Dmodel3d.invertNormals=false   turn it off
     * </pre>
     */
    private static final boolean INVERT_NORMALS = !"false".equalsIgnoreCase(
            System.getProperty("model3d.invertNormals", "true"));

    /** The configured axes, for the diagnostic log. */
    String axes() {
        return (flipX ? "x" : "") + (flipY ? "y" : "") + (flipZ ? "z" : "");
    }

    /**
     * Transforms a point in place into {@code out}.
     *
     * <p>Uses {@link Mat4#transform}, the same helper the node transform uses, so both halves of the
     * placement arithmetic go through one implementation.
     */
    void point(float x, float y, float z, float[] out) {
        float mx = flipX ? -x : x;
        float my = flipY ? -y : y;
        float mz = flipZ ? -z : z;
        if (matrix == null) {
            out[0] = mx;
            out[1] = my;
            out[2] = mz;
            return;
        }
        Mat4.transform(matrix, 0, mx, my, mz, 1.0f, out);
    }

    /**
     * Rotates a direction in place into {@code out}, without the translation.
     *
     * <p>The upper 3x3 of the instance transform: for a rotation times a uniform scale that is the
     * correct normal transform, which is what the root transform is (the mirror is applied as a
     * component negation before it).
     */
    void direction(float x, float y, float z, float[] out) {
        float mx = flipX ? -x : x;
        float my = flipY ? -y : y;
        float mz = flipZ ? -z : z;
        if (matrix == null) {
            out[0] = mx;
            out[1] = my;
            out[2] = mz;
            return;
        }
        float nx = matrix[0] * mx + matrix[4] * my + matrix[8] * mz;
        float ny = matrix[1] * mx + matrix[5] * my + matrix[9] * mz;
        float nz = matrix[2] * mx + matrix[6] * my + matrix[10] * mz;
        if (INVERT_NORMALS) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float lengthSquared = nx * nx + ny * ny + nz * nz;
        if (lengthSquared > 1.0e-12f) {
            float inverse = (float) (1.0 / Math.sqrt(lengthSquared));
            out[0] = nx * inverse;
            out[1] = ny * inverse;
            out[2] = nz * inverse;
        } else {
            out[0] = nx;
            out[1] = ny;
            out[2] = nz;
        }
    }
}
