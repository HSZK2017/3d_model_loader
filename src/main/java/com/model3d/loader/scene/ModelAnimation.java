package com.model3d.loader.scene;

/**
 * An animation, stored as flat keyframe tracks - the shape glTF already uses, because
 * resampling it into per-frame poses would multiply memory by the frame count for no gain.
 *
 * <p>{@link #times} is the <b>concatenation</b> of every track's keyframe times, so its length is
 * the total keyframe count across all tracks and track {@code t} occupies the absolute index range
 * {@code [times[t], times[t + 1])} - that is, a track's keyframes start at its own
 * {@code Track#firstKeyframe()} and run for {@code Track#keyframeCount()} entries. glTF exporters
 * emit one shared time accessor per animation and reuse it across channels, so this layout is
 * usually the file's layout verbatim, and it means a track is described by four parallel arrays
 * instead of four object references.
 *
 * <p>Correction worth recording, because it was wrong here in an earlier revision and the code was
 * right: this javadoc previously said {@code times} had {@code trackCount + 1} entries with track
 * {@code t} at {@code [times[t], times[t + 1])}. That reading is a per-track offset table, which
 * nothing implements - the constructor's duration scan, {@code Track#lastKeyframeTime} and the
 * samplers all index {@code times[firstKeyframe + k]}. A documented data layout that no consumer
 * follows is worse than an undocumented one: it sends the next reader to write a parser to match
 * the prose.
 *
 * <p>{@link #values} is interleaved per keyframe:
 * <ul>
 *   <li>{@code TRANSLATION} and {@code SCALE}: 3 floats per keyframe</li>
 *   <li>any rotation form: 4 floats per keyframe (quaternion xyzw, even for {@code CUBICSPLINE},
 *       where the file stores tangents as quaternions too and they are converted at load time)</li>
 * </ul>
 *
 * <p>{@code CUBICSPLINE} keyframes carry three values each in the file - in-tangent, value,
 * out-tangent - and {@link #values} keeps all three, so {@code valueCount = 3 * keyframeCount}.
 * The other interpolation modes have {@code valueCount = keyframeCount}.
 */
public final class ModelAnimation {

    public enum Interpolation {
        /** Hold the previous keyframe's value until the next one. */
        STEP,
        /** Straight-line (slerp for rotations) between keyframes. */
        LINEAR,
        /**
         * glTF {@code CUBICSPLINE}. Only implemented for translations and scales: glTF stores
         * rotation tangents as quaternions whose composition rule is not the cubic Hermite
         * formula, and every exporter seen so far emits LINEAR or STEP for rotation. A rotation
         * track asking for CUBICSPLINE is downgraded to LINEAR with a warning rather than
         * silently producing wrong poses.
         */
        CUBICSPLINE
    }

    /** What a track drives on its target node. glTF's {@code WEIGHTS} morph target is not supported. */
    public enum Path {
        TRANSLATION,
        ROTATION,
        SCALE
    }

    /**
     * One animated channel: which node, which property, which slice of the shared arrays.
     *
     * <p>Offsets are absolute into {@link ModelAnimation#values}, and {@code componentsPerKey}
     * is 3 or 4.
     */
    public static final class Track {
        private final int targetNode;
        private final Path path;
        private final Interpolation interpolation;
        private final int firstKeyframe;
        private final int keyframeCount;
        private final int valueOffset;
        private final int valueComponents;
        private final int componentsPerKey;

        public Track(int targetNode, Path path, Interpolation interpolation, int firstKeyframe,
                     int keyframeCount, int valueOffset, int valueComponents, int componentsPerKey) {
            this.targetNode = targetNode;
            this.path = path;
            this.interpolation = interpolation;
            this.firstKeyframe = firstKeyframe;
            this.keyframeCount = keyframeCount;
            this.valueOffset = valueOffset;
            this.valueComponents = valueComponents;
            this.componentsPerKey = componentsPerKey;
        }

        public int targetNode() {
            return targetNode;
        }

        public Path path() {
            return path;
        }

        public Interpolation interpolation() {
            return interpolation;
        }

        /** Index of this track's first keyframe in the animation's shared {@code times} array. */
        public int firstKeyframe() {
            return firstKeyframe;
        }

        public int keyframeCount() {
            return keyframeCount;
        }

        /** Absolute offset into {@link ModelAnimation#values}. */
        public int valueOffset() {
            return valueOffset;
        }

        /** 3 for translation/scale, 4 for rotation. */
        public int valueComponents() {
            return valueComponents;
        }

        /** Values stored per keyframe: 1 normally, 3 for CUBICSPLINE. */
        public int componentsPerKey() {
            return componentsPerKey;
        }

        /** Duration of this track in seconds, taken from its own last keyframe. */
        public float lastKeyframeTime(ModelAnimation animation) {
            return animation.times()[firstKeyframe + keyframeCount - 1];
        }
    }

    private final String name;
    private final float[] times;
    private final float[] values;
    private final Track[] tracks;
    private final float duration;

    public ModelAnimation(String name, float[] times, float[] values, Track[] tracks) {
        this.name = name;
        this.times = times;
        this.values = values;
        this.tracks = tracks;
        float max = 0.0f;
        for (Track track : tracks) {
            if (track.keyframeCount() > 0) {
                float last = times[track.firstKeyframe() + track.keyframeCount() - 1];
                if (last > max) {
                    max = last;
                }
            }
        }
        this.duration = max;
    }

    public String name() {
        return name;
    }

    /** Shared keyframe times in seconds; {@code tracks().length + 1} entries. */
    public float[] times() {
        return times;
    }

    public float[] values() {
        return values;
    }

    public Track[] tracks() {
        return tracks;
    }

    public int trackCount() {
        return tracks.length;
    }

    /** Length in seconds, taken as the largest last-keyframe time across all tracks. */
    public float duration() {
        return duration;
    }

    public boolean isEmpty() {
        return tracks.length == 0 || duration <= 0.0f;
    }

    @Override
    public String toString() {
        return "ModelAnimation('" + name + "' tracks=" + tracks.length
                + " duration=" + String.format("%.3f", duration) + "s)";
    }
}
