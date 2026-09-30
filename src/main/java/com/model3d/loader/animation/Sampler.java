package com.model3d.loader.animation;

import com.model3d.loader.scene.ModelAnimation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keyframe evaluation for a single {@link ModelAnimation.Track}, allocation-free.
 *
 * <p>Results are written straight into the caller's array rather than returned: a 200-joint
 * skeleton is roughly 600 tracks, and one temporary per track per frame is exactly the GC
 * sawtooth {@link AnimationPose} exists to prevent. {@link AnimationPlayer#sample} therefore
 * hands this class the target node's live pose array and the sample lands in place.
 *
 * <h2>Array layout</h2>
 * A track's keyframe times live in the animation's shared {@code times} array starting at
 * {@link ModelAnimation.Track#firstKeyframe()}; its values start at
 * {@link ModelAnimation.Track#valueOffset()}. Each keyframe occupies
 * {@code valueComponents * componentsPerKey} floats - one block normally, three for
 * {@code CUBICSPLINE} (in-tangent, value, out-tangent) - so keyframe {@code k} of the track
 * starts at {@code valueOffset + k * valueComponents * componentsPerKey}.
 *
 * <h2>Spec rules implemented here</h2>
 * <ul>
 *   <li>Times before the first keyframe clamp to the first value and times after the last clamp
 *       to the last (glTF 2.0, animation sampler).</li>
 *   <li>Rotation is slerped along the shortest arc. {@code CUBICSPLINE} on a rotation is
 *       downgraded to slerp - see {@link ModelAnimation.Interpolation#CUBICSPLINE}.</li>
 *   <li>{@code CUBICSPLINE} tangents are rates (value per second), so the Hermite tangent terms
 *       are multiplied by the segment duration. Dropping that factor leaves the curve exact at
 *       the keyframes and wrong between them, which reads as "slightly wrong motion" rather than
 *       as an error.</li>
 * </ul>
 */
public final class Sampler {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sampler.class);

    /** One-shot: a file full of cubic rotation tracks must not produce a warning per track per frame. */
    private static volatile boolean warnedCubicRotation;

    private Sampler() {
    }

    /**
     * Evaluates {@code track} of {@code animation} at {@code time} and writes
     * {@link ModelAnimation.Track#valueComponents()} floats to {@code out[outOffset..]}.
     *
     * <p>Writes nothing when the track has no keyframes, or when the output buffer cannot hold
     * the result: a malformed track must not take down the render thread with an
     * {@code ArrayIndexOutOfBoundsException}.
     */
    public static void evaluate(ModelAnimation animation, ModelAnimation.Track track, float time,
                                float[] out, int outOffset) {
        final int keyframeCount = track.keyframeCount();
        if (keyframeCount <= 0) {
            return;
        }
        final int components = track.valueComponents();
        if (components <= 0 || outOffset < 0 || out.length - outOffset < components) {
            return;
        }

        final float[] times = animation.times();
        final float[] values = animation.values();
        final int componentsPerKey = track.componentsPerKey();
        final int stride = components * componentsPerKey;
        final int first = track.firstKeyframe();
        final int valueBase = track.valueOffset();
        final int keyframeBase = componentsPerKey >= 3 ? components : 0;

        // A track is four integers a parser computed; one wrong integer must skip one channel, not
        // take down the frame. These bounds checks are the only thing between a malformed file and
        // an ArrayIndexOutOfBoundsException on the render thread.
        if (stride <= 0 || first < 0 || first + keyframeCount > times.length
                || valueBase < 0 || valueBase + keyframeCount * stride > values.length) {
            return;
        }

        // A single keyframe has no segment to interpolate over; every interpolation mode
        // degenerates to a constant.
        if (keyframeCount == 1) {
            copyKeyframe(values, valueBase + keyframeBase, components, out, outOffset);
            return;
        }

        final float firstTime = times[first];
        final float lastTime = times[first + keyframeCount - 1];
        // Written as a negated comparison so a NaN clock clamps instead of walking the binary
        // search into an out-of-range index.
        if (!(time > firstTime)) {
            copyKeyframe(values, valueBase + keyframeBase, components, out, outOffset);
            return;
        }
        if (time >= lastTime) {
            copyKeyframe(values, valueBase + (keyframeCount - 1) * stride + keyframeBase,
                    components, out, outOffset);
            return;
        }

        final int segment = segmentIndex(times, first, keyframeCount, time);
        final int block0 = valueBase + (segment - first) * stride;
        final int block1 = block0 + stride;
        final float segmentStart = times[segment];
        final float duration = times[segment + 1] - segmentStart;
        if (!(duration > 0.0f)) {
            // Duplicate keyframe times (exporters do emit them) or non-monotonic times: the
            // segment has no defined blend, and dividing by it yields a NaN pose that spreads
            // through the whole skin. Take the later value.
            copyKeyframe(values, block1 + keyframeBase, components, out, outOffset);
            return;
        }
        final float u = (time - segmentStart) / duration;

        switch (track.interpolation()) {
            case STEP -> copyKeyframe(values, block0 + keyframeBase, components, out, outOffset);
            case LINEAR -> {
                if (components == 4) {
                    slerp(values, block0 + keyframeBase, block1 + keyframeBase, u, out, outOffset);
                } else {
                    lerp(values, block0 + keyframeBase, block1 + keyframeBase, components, u, out, outOffset);
                }
            }
            case CUBICSPLINE -> {
                if (componentsPerKey < 3) {
                    // Labelled cubic but stored without tangent blocks: treating it as linear is
                    // the least wrong reading of a malformed file.
                    if (components == 4) {
                        slerp(values, block0, block1, u, out, outOffset);
                    } else {
                        lerp(values, block0, block1, components, u, out, outOffset);
                    }
                } else if (components == 4) {
                    noteCubicRotationFallback(animation, track);
                    slerp(values, block0 + components, block1 + components, u, out, outOffset);
                } else {
                    hermite(values, block0, block1, components, duration, u, out, outOffset);
                }
            }
        }
    }

    /**
     * Index of the segment containing {@code time}: the largest {@code i} in
     * {@code [first, first + keyframeCount - 2]} with {@code times[i] <= time}, so
     * {@code times[i] <= time < times[i + 1]} holds for monotonic times.
     *
     * <p>Binary search rather than a scan because a baked animation can carry thousands of
     * keyframes, and a scan makes the cost of one track grow with the length of the clip.
     */
    private static int segmentIndex(float[] times, int first, int keyframeCount, float time) {
        int lo = first;
        int hi = first + keyframeCount - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (times[mid] <= time) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static void copyKeyframe(float[] values, int from, int components, float[] out, int outOffset) {
        System.arraycopy(values, from, out, outOffset, components);
    }

    private static void lerp(float[] values, int a, int b, int components, float u, float[] out, int outOffset) {
        for (int c = 0; c < components; c++) {
            float from = values[a + c];
            out[outOffset + c] = from + (values[b + c] - from) * u;
        }
    }

    /**
     * Spherical linear interpolation along the shortest arc.
     *
     * <p>glTF stores quaternions with an arbitrary sign, and {@code q} and {@code -q} are the same
     * rotation. Interpolating across hemispheres without the sign flip still reaches the right
     * end pose, but travels the long way round: a 10-degree wrist flick becomes a 350-degree
     * spin the other way, i.e. the limb passes through the body.
     *
     * <p>The result is renormalized because {@link com.model3d.loader.math.Mat4#fromQuat} assumes
     * a unit quaternion; drift there shows up as a slowly changing scale on every animated bone.
     */
    private static void slerp(float[] values, int a, int b, float u, float[] out, int outOffset) {
        float ax = values[a], ay = values[a + 1], az = values[a + 2], aw = values[a + 3];
        float bx = values[b], by = values[b + 1], bz = values[b + 2], bw = values[b + 3];

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0.0f) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }

        float rx;
        float ry;
        float rz;
        float rw;
        if (dot > 0.9995f) {
            // The angle is too small for its sine to carry any precision; a normalized linear
            // blend is indistinguishable here and cannot divide by ~0.
            rx = ax + (bx - ax) * u;
            ry = ay + (by - ay) * u;
            rz = az + (bz - az) * u;
            rw = aw + (bw - aw) * u;
        } else {
            float theta = (float) Math.acos(Math.min(dot, 1.0f));
            float sinTheta = (float) Math.sin(theta);
            float w0 = (float) Math.sin((1.0f - u) * theta) / sinTheta;
            float w1 = (float) Math.sin(u * theta) / sinTheta;
            rx = ax * w0 + bx * w1;
            ry = ay * w0 + by * w1;
            rz = az * w0 + bz * w1;
            rw = aw * w0 + bw * w1;
        }

        float length = (float) Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        if (length > 0.0f && Float.isFinite(length)) {
            float inverse = 1.0f / length;
            out[outOffset] = rx * inverse;
            out[outOffset + 1] = ry * inverse;
            out[outOffset + 2] = rz * inverse;
            out[outOffset + 3] = rw * inverse;
        } else {
            // Two zero-length quaternions in the file: identity is the only meaningful pose.
            out[outOffset] = 0.0f;
            out[outOffset + 1] = 0.0f;
            out[outOffset + 2] = 0.0f;
            out[outOffset + 3] = 1.0f;
        }
    }

    /**
     * Cubic Hermite for translation and scale, per glTF 2.0 appendix C:
     * {@code p(u) = h00*p0 + h10*dt*m0 + h01*p1 + h11*dt*m1}.
     *
     * <p>{@code m0} is the current keyframe's out-tangent and {@code m1} the next keyframe's
     * in-tangent, both in value units per second, which is why they are scaled by the segment
     * duration {@code dt} before being used as Hermite slopes.
     */
    private static void hermite(float[] values, int block0, int block1, int components, float duration,
                                float u, float[] out, int outOffset) {
        float u2 = u * u;
        float u3 = u2 * u;
        float h00 = 2.0f * u3 - 3.0f * u2 + 1.0f;
        float h10 = u3 - 2.0f * u2 + u;
        float h01 = -2.0f * u3 + 3.0f * u2;
        float h11 = u3 - u2;
        float outTangentScale = h10 * duration;
        float inTangentScale = h11 * duration;

        for (int c = 0; c < components; c++) {
            float p0 = values[block0 + components + c];
            float m0 = values[block0 + 2 * components + c];
            float p1 = values[block1 + components + c];
            float m1 = values[block1 + c];
            out[outOffset + c] = h00 * p0 + outTangentScale * m0 + h01 * p1 + inTangentScale * m1;
        }
    }

    private static void noteCubicRotationFallback(ModelAnimation animation, ModelAnimation.Track track) {
        if (warnedCubicRotation) {
            return;
        }
        warnedCubicRotation = true;
        LOGGER.warn("Animation '{}' asks for CUBICSPLINE on rotation of node {}; quaternion tangents"
                        + " do not compose under the cubic Hermite formula, so it is sampled as"
                        + " LINEAR slerp (first occurrence logged; same for every other rotation track)",
                animation.name(), track.targetNode());
    }
}
