package com.model3d.loader.animation;

import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an {@link AnimationState} into a pose.
 *
 * <p>Entry point {@link #apply} is the whole contract the rest of the mod depends on: after it
 * returns, every node in {@code nodes} carries an up-to-date local transform and world
 * transform, and {@code pose.jointMatrices()} holds the skinning matrices for this frame (or is
 * left untouched for an unskinned model).
 *
 * <p>Sampling contract, which the implementation must honour exactly:
 * <ul>
 *   <li>Before sampling tracks, every node is reset to its rest pose and the pose flags cleared.
 *       Animations in the wild are partial - a file may animate four joints of a 200-joint
 *       skeleton - and a node left at last frame's value drifts instead of holding its rest
 *       pose.</li>
 *   <li>A track whose keyframes are empty is skipped.</li>
 *   <li>Times before the first keyframe clamp to the first value and after the last clamp to the
 *       last, per the glTF 2.0 spec.</li>
 *   <li>Rotations interpolate with shortest-arc slerp: a quaternion pair on opposite hemispheres
 *       interpolates the long way round without a sign flip, which shows up as a limb spinning
 *       the wrong way through a body.</li>
 *   <li>After sampling, each node's own overrides are applied over the sampled pose and before the
 *       world transforms and joint matrices are derived - the per-instance part driving of
 *       {@code api.ModelNodeRef}. Overrides are the deliberate exception to "the animation is the
 *       pose": they are the caller's values, and they win.</li>
 * </ul>
 *
 * <p>Every frame the sampler writes into the per-instance node arrays and matrices; nothing here
 * allocates after class initialisation, so a 200-joint skeleton at 60 fps produces no pose
 * garbage. The class is CPU-only and, like {@link AnimationState}, belongs to one instance and is
 * only safe on the thread that renders it.
 */
public final class AnimationPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationPlayer.class);

    /**
     * Scratch for {@code jointWorld * inverseBindMatrix}. One reusable 16-float buffer instead of
     * one array per joint per frame: a 200-joint skeleton would otherwise hand the collector
     * 200 arrays every frame.
     */
    private static final float[] JOINT_PRODUCT = new float[16];

    /** One-shot warnings, so a malformed file is visible once rather than 600 times a frame. */
    private static volatile boolean warnedUnresolvedTarget;
    private static volatile boolean warnedComponentMismatch;
    private static volatile boolean warnedExtraSkins;

    private AnimationPlayer() {
    }

    /**
     * Samples {@code state}'s current clock position onto {@code nodes} and recomputes joint
     * matrices into {@code pose}.
     *
     * @param scene the model the nodes were instantiated from; supplies roots, skins and the
     *              node-to-joint mapping
     * @param nodes this instance's node tree, from {@link ModelScene#instantiate()}
     * @param state clock; when its animation is null the nodes are posed at rest but world
     *              transforms and (for a skinned model) joint matrices are still recomputed
     * @param pose scratch space owned by the instance
     */
    public static void apply(ModelScene scene, ModelNode[] nodes, AnimationState state, AnimationPose pose) {
        // Rest first, then sample over it: an animation is normally partial, and a node the
        // current clip does not touch must hold its authored pose rather than last clip's.
        for (int i = 0; i < nodes.length; i++) {
            nodes[i].resetToRest();
        }
        pose.clear();

        ModelAnimation animation = state.animation();
        if (animation != null) {
            sample(animation, state.time(), nodes, pose.animatedFlags());
        }

        // Node overrides land here, on top of the sampled pose and before anything is derived from
        // it. This is the only point in the frame where they can:
        //   * after the reset-to-rest and the sample above, or the next sample would overwrite them -
        //     which is the flicker a driven part shows when an override is written before update();
        //   * before the world-transform walk and computeJointMatrices below, so a driven node that
        //     is a skin joint moves the skin rather than only its own matrix. Applying the overrides
        //     after apply() returns would leave the skin at the clip's pose - the part moves, its
        //     skinned geometry does not.
        // For an instance that drives nothing this is three null/boolean checks per node and no
        // allocation: it runs once per instance per frame, on the render thread.
        applyNodeOverrides(nodes);

        // One exit path for every case. A model with no animation still needs its world transforms
        // and - when it is skinned - its joint matrices rebuilt from the rest pose: a static
        // skinned model has to render in its bind pose, not with the zero matrices an untouched
        // pose array holds. Parent-before-child ordering is what makes the composition correct;
        // reimplementing the walk here is how a limb ends up a frame behind the body.
        ModelScene.updateWorldTransformsInPlace(nodes, scene.rootNodes());
        copyWorldMatrices(nodes, pose);
        computeJointMatrices(scene, nodes, pose);
    }

    /**
     * Writes every node's own overrides over its sampled pose. See the call site for why this is a
     * separate step and where in the frame it belongs.
     *
     * <p>No per-instance registry is kept of which nodes are overridden: a lookup structure would
     * have to be kept in step with the overrides themselves (and with {@code ModelInstance#setScene},
     * which replaces the tree), and the check it would save is one null comparison per node.
     */
    private static void applyNodeOverrides(ModelNode[] nodes) {
        for (int i = 0; i < nodes.length; i++) {
            nodes[i].applyOverrides();
        }
    }

    /** Overload used where only a single {@link ModelAnimation} is at hand (tests, tools). */
    public static void sample(ModelAnimation animation, float time, ModelNode[] nodes,
                              byte[] animatedFlags) {
        if (animation == null) {
            return;
        }
        ModelAnimation.Track[] tracks = animation.tracks();
        for (int t = 0; t < tracks.length; t++) {
            ModelAnimation.Track track = tracks[t];
            if (track.keyframeCount() <= 0) {
                continue;
            }
            int target = track.targetNode();
            if (target < 0 || target >= nodes.length || target >= animatedFlags.length) {
                warnUnresolvedTarget(animation, track, nodes.length);
                continue;
            }
            ModelNode node = nodes[target];
            switch (track.path()) {
                case TRANSLATION -> {
                    if (!sampleTrack(animation, track, time, 3, node.translation())) {
                        continue;
                    }
                    animatedFlags[target] |= AnimationPose.FLAG_TRANSLATION;
                }
                case ROTATION -> {
                    if (!sampleTrack(animation, track, time, 4, node.rotation())) {
                        continue;
                    }
                    animatedFlags[target] |= AnimationPose.FLAG_ROTATION;
                }
                case SCALE -> {
                    if (!sampleTrack(animation, track, time, 3, node.scale())) {
                        continue;
                    }
                    animatedFlags[target] |= AnimationPose.FLAG_SCALE;
                }
            }
        }
    }

    /**
     * Samples one track into the target node's live array, but only when the track's component
     * count matches what that property is stored as.
     *
     * <p>A file that declares a four-component translation would otherwise write past the end of
     * a three-float array and kill the render thread; one bad channel must not blank out a model.
     */
    private static boolean sampleTrack(ModelAnimation animation, ModelAnimation.Track track, float time,
                                       int expectedComponents, float[] target) {
        if (track.valueComponents() != expectedComponents) {
            if (!warnedComponentMismatch) {
                warnedComponentMismatch = true;
                LOGGER.warn("Animation '{}' has a {} channel on node {} with {} components, but that"
                                + " property has {}; the channel is skipped, the rest of the animation"
                                + " still plays (first occurrence logged)",
                        animation.name(), track.path(), track.targetNode(), track.valueComponents(),
                        expectedComponents);
            }
            return false;
        }
        Sampler.evaluate(animation, track, time, target, 0);
        return true;
    }

    /**
     * Copies each node's world matrix into {@link AnimationPose#worldMatrices()} so a renderer can
     * read the whole pose out of one flat array without touching the node objects.
     */
    private static void copyWorldMatrices(ModelNode[] nodes, AnimationPose pose) {
        float[] worldMatrices = pose.worldMatrices();
        if (worldMatrices.length < nodes.length * 16) {
            return;
        }
        for (int i = 0; i < nodes.length; i++) {
            System.arraycopy(nodes[i].globalTransform().raw(), 0, worldMatrices, i * 16, 16);
        }
    }

    /**
     * Fills {@link AnimationPose#jointMatrices()} with {@code jointWorld * inverseBindMatrix} in
     * skin joint order, column-major, 16 floats per joint - this is the array the skinning shader
     * reads, and the only place where the bind pose enters the per-frame maths.
     *
     * <p><b>Limitation:</b> only {@code skins[0]} is applied. A glTF file may declare several skins
     * with independent skeletons, but the pose carries a single joint-matrix block sized for the
     * first skin (see {@code ModelInstance#setScene}) and the renderer draws every primitive with
     * that one block, so the extra skeletons stay in rest pose. Reported once at WARN rather than
     * silently.
     */
    private static void computeJointMatrices(ModelScene scene, ModelNode[] nodes, AnimationPose pose) {
        ModelSkin[] skins = scene.skins();
        if (skins.length == 0) {
            return;
        }
        if (skins.length > 1 && !warnedExtraSkins) {
            warnedExtraSkins = true;
            LOGGER.warn("Model '{}' declares {} skins; only skins[0] ('{}') is skinned and the rest"
                            + " are rendered in rest pose (first occurrence logged)",
                    scene.name(), skins.length, skins[0].name());
        }

        ModelSkin skin = skins[0];
        int[] joints = skin.joints();
        Mat4[] inverseBindMatrices = skin.inverseBindMatrices();
        float[] jointMatrices = pose.jointMatrices();
        int limit = Math.min(joints.length, inverseBindMatrices.length);
        limit = Math.min(limit, jointMatrices.length / 16);
        for (int joint = 0; joint < limit; joint++) {
            int nodeIndex = joints[joint];
            if (nodeIndex < 0 || nodeIndex >= nodes.length) {
                continue;
            }
            Mat4.mul(nodes[nodeIndex].globalTransform().raw(), inverseBindMatrices[joint].raw(),
                    JOINT_PRODUCT);
            System.arraycopy(JOINT_PRODUCT, 0, jointMatrices, joint * 16, 16);
        }
    }

    private static void warnUnresolvedTarget(ModelAnimation animation, ModelAnimation.Track track, int nodeCount) {
        if (warnedUnresolvedTarget) {
            return;
        }
        warnedUnresolvedTarget = true;
        LOGGER.warn("Animation '{}' has a channel targeting node {} but the instance has {} nodes;"
                        + " the channel is skipped and the rest of the animation still plays"
                        + " (first occurrence logged, later ones silent)",
                animation.name(), track.targetNode(), nodeCount);
    }
}
