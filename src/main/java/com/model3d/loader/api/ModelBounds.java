package com.model3d.loader.api;

/**
 * One axis-aligned bounding box in model units, as {@code [minX, minY, minZ, maxX, maxY, maxZ]}.
 *
 * <p>Kept as a flat float array wherever it travels between layers: it is read on the render
 * thread, and a record-with-6-floats would allocate on every frame for no benefit.
 */
public final class ModelBounds {

    private ModelBounds() {
    }

    public static float[] empty() {
        return new float[] { 0, 0, 0, 0, 0, 0 };
    }

    /** Grows {@code out} in place to include {@code other}; returns {@code out}. */
    public static float[] union(float[] out, float[] other) {
        out[0] = Math.min(out[0], other[0]);
        out[1] = Math.min(out[1], other[1]);
        out[2] = Math.min(out[2], other[2]);
        out[3] = Math.max(out[3], other[3]);
        out[4] = Math.max(out[4], other[4]);
        out[5] = Math.max(out[5], other[5]);
        return out;
    }

    public static float sizeX(float[] bounds) {
        return bounds[3] - bounds[0];
    }

    public static float sizeY(float[] bounds) {
        return bounds[4] - bounds[1];
    }

    public static float sizeZ(float[] bounds) {
        return bounds[5] - bounds[2];
    }

    /** Longest axis length. */
    public static float longestExtent(float[] bounds) {
        return Math.max(sizeX(bounds), Math.max(sizeY(bounds), sizeZ(bounds)));
    }

    public static float[] center(float[] bounds) {
        return new float[] {
                (bounds[0] + bounds[3]) * 0.5f,
                (bounds[1] + bounds[4]) * 0.5f,
                (bounds[2] + bounds[5]) * 0.5f
        };
    }

    public static String describe(float[] bounds) {
        return String.format("size=(%.2f, %.2f, %.2f) min=(%.2f, %.2f, %.2f) max=(%.2f, %.2f, %.2f)",
                sizeX(bounds), sizeY(bounds), sizeZ(bounds),
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }
}
