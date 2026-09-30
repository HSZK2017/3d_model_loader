package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.scene.ModelAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code animations[]} into {@link ModelAnimation} keyframe tracks.
 *
 * <p>Nothing is sampled or resampled: the keyframes are stored exactly as the file has them, which is
 * also why the shared-array layout works - all the tracks of one animation are concatenated into a
 * single {@code times} array (track {@code t} occupying
 * {@code [firstKeyframe, firstKeyframe + keyframeCount)}) and a single interleaved {@code values}
 * array. glTF exporters already write one time accessor per sampler, so this is usually the file's own
 * layout with the per-track arrays stitched together.
 *
 * <p>Keyframe times must be strictly increasing, and that is checked rather than assumed: the runtime
 * sampler binary-searches the segment between two keyframes and divides by the segment length, so a
 * file with {@code t[i] == t[i-1]} would produce a NaN pose - an invisible or inside-out model that
 * looks like a rendering bug rather than the malformed input it is.
 *
 * <p>{@code CUBICSPLINE} stores three values per keyframe (in-tangent, value, out-tangent). For
 * translations and scales all three are kept, because the runtime can evaluate the cubic Hermite form
 * from them. For <b>rotations</b> they are not, because glTF's rotation tangents are quaternions and
 * the Hermite formula does not apply to them; that case is downgraded to LINEAR keeping only the
 * middle value, exactly as {@link ModelAnimation.Interpolation} documents.
 */
final class AnimationReader {

    private final JsonArray animations;
    private final AccessorReader accessors;
    private final int nodeCount;

    AnimationReader(JsonObject root, AccessorReader accessors, int nodeCount)
            throws ModelParseException {
        this.animations = root.getArray("animations");
        this.accessors = accessors;
        this.nodeCount = nodeCount;
    }

    /** Every animation the file declares, in file order; empty when it has none. */
    List<ModelAnimation> read() throws ModelParseException {
        if (animations == null || animations.isEmpty()) {
            return List.of();
        }
        List<ModelAnimation> out = new ArrayList<>(animations.size());
        for (int i = 0; i < animations.size(); i++) {
            ModelAnimation animation = readAnimation(i);
            if (animation != null) {
                out.add(animation);
            }
        }
        return out;
    }

    private ModelAnimation readAnimation(int index) throws ModelParseException {
        String element = "animations[" + index + "]";
        JsonObject animation = animations.requireObject(index);
        String name = animation.getString("name");
        String displayName = name == null ? "animation[" + index + "]" : name;

        JsonArray channels = animation.getArray("channels");
        JsonArray samplers = animation.getArray("samplers");
        if (channels == null || channels.isEmpty()) {
            throw ModelParseException.at(element, "no 'channels'; an animation must animate something");
        }
        if (samplers == null || samplers.isEmpty()) {
            throw ModelParseException.at(element, "no 'samplers'; the channels reference nothing");
        }

        List<Pending> pending = new ArrayList<>(channels.size());
        for (int c = 0; c < channels.size(); c++) {
            Pending track = readChannel(index, element, channels, samplers, c);
            if (track != null) {
                pending.add(track);
            }
        }
        if (pending.isEmpty()) {
            Model3D.LOGGER.warn("{} ('{}'): every channel targets something this loader does not "
                    + "animate (morph weights, or no target node); the animation is dropped", element,
                    displayName);
            return null;
        }

        int totalKeyframes = 0;
        int totalValues = 0;
        for (Pending track : pending) {
            totalKeyframes += track.times.length;
            totalValues += track.values.length;
        }
        float[] times = new float[totalKeyframes];
        float[] values = new float[totalValues];
        ModelAnimation.Track[] tracks = new ModelAnimation.Track[pending.size()];
        int keyframeOffset = 0;
        int valueOffset = 0;
        for (int t = 0; t < pending.size(); t++) {
            Pending track = pending.get(t);
            System.arraycopy(track.times, 0, times, keyframeOffset, track.times.length);
            System.arraycopy(track.values, 0, values, valueOffset, track.values.length);
            tracks[t] = new ModelAnimation.Track(track.targetNode, track.path, track.interpolation,
                    keyframeOffset, track.times.length, valueOffset, track.components,
                    track.componentsPerKey);
            keyframeOffset += track.times.length;
            valueOffset += track.values.length;
        }
        return new ModelAnimation(displayName, times, values, tracks);
    }

    private Pending readChannel(int animationIndex, String element, JsonArray channels,
                               JsonArray samplers, int channelIndex) throws ModelParseException {
        String channelElement = element + ".channels[" + channelIndex + "]";
        JsonObject channel = channels.requireObject(channelIndex);
        JsonObject target = channel.requireObject("target");
        String pathText = target.requireString("path");

        ModelAnimation.Path path;
        switch (pathText) {
            case "translation":
                path = ModelAnimation.Path.TRANSLATION;
                break;
            case "rotation":
                path = ModelAnimation.Path.ROTATION;
                break;
            case "scale":
                path = ModelAnimation.Path.SCALE;
                break;
            case "weights":
                // Morph targets are not represented in ModelPrimitive at all, so there is nothing to
                // drive; saying so beats animating the wrong property.
                Model3D.LOGGER.warn("{}: target.path 'weights' (morph targets) is not supported; "
                        + "skipping the channel", channelElement);
                return null;
            default:
                throw ModelParseException.at(channelElement, "target.path '" + pathText
                        + "' is not one of translation, rotation, scale or weights");
        }

        int nodeIndex = target.getInt("node", -1);
        if (nodeIndex < 0) {
            Model3D.LOGGER.warn("{}: no target.node (a morph-weight channel has none); skipping the "
                    + "channel", channelElement);
            return null;
        }
        if (nodeIndex >= nodeCount) {
            throw ModelParseException.at(channelElement, "target.node " + nodeIndex
                    + " out of range (nodes: " + nodeCount + ")");
        }

        int samplerIndex = channel.requireInt("sampler");
        if (samplerIndex < 0 || samplerIndex >= samplers.size()) {
            throw ModelParseException.at(channelElement, "sampler " + samplerIndex
                    + " out of range (samplers: " + samplers.size() + ")");
        }
        String samplerElement = element + ".samplers[" + samplerIndex + "]";
        JsonObject sampler = samplers.requireObject(samplerIndex);
        int inputAccessor = sampler.requireInt("input");
        int outputAccessor = sampler.requireInt("output");

        ModelAnimation.Interpolation interpolation = interpolationOf(sampler, samplerElement);
        int components = path == ModelAnimation.Path.ROTATION ? 4 : 3;
        String accessorOwner = channelElement + " -> " + samplerElement;

        String inputType = accessors.typeOf(inputAccessor, accessorOwner);
        if (!inputType.equals("SCALAR")) {
            throw ModelParseException.at(samplerElement, "input accessor[" + inputAccessor + "] is "
                    + inputType + "; keyframe times must be SCALAR");
        }
        requireFloatAccessor(inputAccessor, samplerElement, "input", accessorOwner);
        int keyframeCount = accessors.count(inputAccessor, accessorOwner);
        if (keyframeCount <= 0) {
            Model3D.LOGGER.warn("{}: input accessor[{}] has no keyframes; skipping the channel",
                    samplerElement, inputAccessor);
            return null;
        }
        float[] times = accessors.readFloats(inputAccessor, accessorOwner);
        for (int i = 1; i < times.length; i++) {
            if (!(times[i] > times[i - 1])) {
                throw ModelParseException.at(samplerElement, "input accessor[" + inputAccessor
                        + "] keyframe times are not strictly increasing (t[" + i + "]=" + times[i]
                        + " <= t[" + (i - 1) + "]=" + times[i - 1] + "); interpolation would divide "
                        + "by a zero-length segment");
            }
        }

        String outputType = accessors.typeOf(outputAccessor, accessorOwner);
        String expectedType = components == 4 ? "VEC4" : "VEC3";
        if (!outputType.equals(expectedType)) {
            throw ModelParseException.at(samplerElement, "output accessor[" + outputAccessor + "] is "
                    + outputType + " but " + pathText + " keyframes are " + expectedType);
        }
        requireFloatAccessor(outputAccessor, samplerElement, "output", accessorOwner);

        int componentsPerKey = interpolation == ModelAnimation.Interpolation.CUBICSPLINE ? 3 : 1;
        float[] raw = accessors.readFloats(outputAccessor, accessorOwner);
        long expectedValues = (long) keyframeCount * componentsPerKey * components;
        if (raw.length != expectedValues) {
            throw ModelParseException.at(samplerElement, "output accessor[" + outputAccessor + "] has "
                    + raw.length + " values but " + keyframeCount + " keyframes"
                    + (componentsPerKey == 3 ? " of CUBICSPLINE (3 values each)" : "")
                    + " x " + components + " components needs " + expectedValues);
        }

        if (interpolation == ModelAnimation.Interpolation.CUBICSPLINE && components == 4) {
            Model3D.LOGGER.warn("{}: CUBICSPLINE rotation is not supported (glTF stores rotation "
                    + "tangents as quaternions, which the cubic Hermite form does not apply to); "
                    + "downgrading the track for node {} to LINEAR", samplerElement, nodeIndex);
            float[] middle = new float[keyframeCount * components];
            for (int k = 0; k < keyframeCount; k++) {
                System.arraycopy(raw, (k * 3 + 1) * components, middle, k * components, components);
            }
            raw = middle;
            componentsPerKey = 1;
            interpolation = ModelAnimation.Interpolation.LINEAR;
        }
        return new Pending(nodeIndex, path, interpolation, times, raw, components, componentsPerKey);
    }

    private void requireFloatAccessor(int accessorIndex, String samplerElement, String role,
                                     String requester) throws ModelParseException {
        int componentType = accessors.componentTypeOf(accessorIndex, requester);
        if (componentType != AccessorReader.FLOAT) {
            throw ModelParseException.at(samplerElement, role + " accessor[" + accessorIndex
                    + "] must use FLOAT components; glTF defines no quantized form for keyframes");
        }
        if (accessors.isNormalized(accessorIndex, requester)) {
            throw ModelParseException.at(samplerElement, role + " accessor[" + accessorIndex
                    + "] must not be normalized");
        }
    }

    private static ModelAnimation.Interpolation interpolationOf(JsonObject sampler, String element)
            throws ModelParseException {
        String text = sampler.getString("interpolation");
        if (text == null) {
            return ModelAnimation.Interpolation.LINEAR;
        }
        switch (text) {
            case "LINEAR":
                return ModelAnimation.Interpolation.LINEAR;
            case "STEP":
                return ModelAnimation.Interpolation.STEP;
            case "CUBICSPLINE":
                return ModelAnimation.Interpolation.CUBICSPLINE;
            default:
                throw ModelParseException.at(element, "interpolation '" + text
                        + "' is not one of STEP, LINEAR or CUBICSPLINE");
        }
    }

    /** One channel's keyframes before they are concatenated into the animation's shared arrays. */
    private record Pending(int targetNode, ModelAnimation.Path path,
                           ModelAnimation.Interpolation interpolation, float[] times, float[] values,
                           int components, int componentsPerKey) {
    }
}
