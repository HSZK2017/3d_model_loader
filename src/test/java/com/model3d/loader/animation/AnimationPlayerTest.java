package com.model3d.loader.animation;

import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.model3d.loader.animation.AnimationFixtures.IDENTITY_MATRIX;
import static com.model3d.loader.animation.AnimationFixtures.IDENTITY_QUAT;
import static com.model3d.loader.animation.AnimationFixtures.UNIT_SCALE;
import static com.model3d.loader.animation.AnimationFixtures.assertMatrix;
import static com.model3d.loader.animation.AnimationFixtures.assertQuat;
import static com.model3d.loader.animation.AnimationFixtures.assertVec;
import static com.model3d.loader.animation.AnimationFixtures.assertAllZero;
import static com.model3d.loader.animation.AnimationFixtures.assertFinite;
import static com.model3d.loader.animation.AnimationFixtures.cubicTrack;
import static com.model3d.loader.animation.AnimationFixtures.matrix;
import static com.model3d.loader.animation.AnimationFixtures.node;
import static com.model3d.loader.animation.AnimationFixtures.quatZ;
import static com.model3d.loader.animation.AnimationFixtures.scene;
import static com.model3d.loader.animation.AnimationFixtures.singleTrack;
import static com.model3d.loader.animation.AnimationFixtures.track;
import static com.model3d.loader.animation.AnimationFixtures.vec;
import static com.model3d.loader.animation.AnimationFixtures.vec3;
import static com.model3d.loader.animation.AnimationFixtures.vec4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnimationPlayer#apply} and {@link AnimationPlayer#sample} end to end on hand-built
 * scenes: track targeting, reset-to-rest, parent-before-child composition and skinning matrices.
 */
class AnimationPlayerTest {

    private static final float DELTA = 1e-4f;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** root(0) -> mid(1) -> tip(2), each child offset one unit along +X, no skin. */
    private static ModelScene chainScene() {
        ModelNode root = node(0, "root", -1, vec(0, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        ModelNode mid = node(1, "mid", 0, vec(1, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        ModelNode tip = node(2, "tip", 1, vec(1, 0, 0), IDENTITY_QUAT, UNIT_SCALE);
        return scene("chain", new ModelNode[] { root, mid, tip }, new int[] { 0 }, new ModelSkin[0]);
    }

    /** A rotation of the root from 0 to 90 degrees about Z over one second. */
    private static ModelAnimation armAnimation() {
        float[] q0 = quatZ(0);
        float[] q1 = quatZ(90);
        return singleTrack("arm", new float[] { 0f, 1f },
                new float[] { q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3] },
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 0));
    }

    /**
     * Bind pose of the two-joint skeleton used by the skinning tests.
     *
     * <pre>
     *   joint A (node 0): world = RZ(90)
     *   joint B (node 1): local = T(0,1,0) under A, so world = RZ(90) * T(0,1,0)
     * </pre>
     *
     * The inverse bind matrices are written out literally rather than computed with
     * {@link Mat4#invert()}: a bug in the inverse would otherwise cancel itself out and the test
     * would pass on a broken bind matrix.
     *
     * <pre>
     *   inverse(RZ(90))            = RZ(-90)              = [0 -1 0 0; 1 0 0 0; 0 0 1 0; 0 0 0 1]
     *   inverse(RZ(90) * T(0,1,0)) = RZ(-90) then T(0,-1,0) = [0 -1 0 0; 1 0 0 0; 0 0 1 0; 0 -1 0 1]
     * </pre>
     */
    private static final float[] INVERSE_BIND_A = {
            0, -1, 0, 0,
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private static final float[] INVERSE_BIND_B = {
            0, -1, 0, 0,
            1, 0, 0, 0,
            0, 0, 1, 0,
            0, -1, 0, 1
    };

    private static ModelScene skinnedScene(ModelSkin[] skins) {
        ModelNode jointA = node(0, "A", -1, vec(0, 0, 0), quatZ(90), UNIT_SCALE);
        ModelNode jointB = node(1, "B", 0, vec(0, 1, 0), IDENTITY_QUAT, UNIT_SCALE);
        return scene("skinned", new ModelNode[] { jointA, jointB }, new int[] { 0 }, skins);
    }

    private static ModelSkin twoJointSkin() {
        return new ModelSkin("skeleton", new int[] { 0, 1 },
                new Mat4[] { matrix(INVERSE_BIND_A), matrix(INVERSE_BIND_B) }, -1);
    }

    /** A clip with no channels: the pose stays at rest, but the skinning path still runs. */
    private static ModelAnimation emptyClip(String name) {
        return new ModelAnimation(name, new float[] { 0f }, new float[0], new ModelAnimation.Track[0]);
    }

    private static AnimationState stateAt(ModelAnimation animation, float time) {
        AnimationState state = new AnimationState();
        state.play(animation, false, false, 0.0f);
        state.setTime(time);
        return state;
    }

    // ------------------------------------------------------------------
    // Hierarchy composition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rotation on the root moves the whole chain: parent-before-child ordering")
    void rootRotationComposesToTip() {
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);
        AnimationState state = stateAt(armAnimation(), 1.0f);

        AnimationPlayer.apply(scene, nodes, state, pose);

        float[] midPosition = new float[3];
        float[] tipPosition = new float[3];
        nodes[1].globalTransform().transformPoint(0, 0, 0, midPosition, 0);
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.printf(Locale.ROOT, "[chain] root rotated 90 deg about Z: mid world %s, tip world %s%n",
                vec3(midPosition, 0), vec3(tipPosition, 0));

        // 90 degrees about Z maps (1,0,0) -> (0,1,0), so the two-unit tip ends at (0,2,0).
        assertVec("mid world position", new float[] { 0f, 1f, 0f }, midPosition, 0, DELTA);
        assertVec("tip world position", new float[] { 0f, 2f, 0f }, tipPosition, 0, DELTA);

        System.out.println("[chain] flags: root=" + pose.animatedFlags()[0]
                + " mid=" + pose.animatedFlags()[1] + " tip=" + pose.animatedFlags()[2]);
        assertEquals(AnimationPose.FLAG_ROTATION, pose.animatedFlags()[0], "root flag");
        assertEquals(0, pose.animatedFlags()[1], "mid is not animated and must keep rest pose");
        assertEquals(0, pose.animatedFlags()[2], "tip is not animated and must keep rest pose");

        // Half a second later the same composition must hold at 45 degrees.
        state.setTime(0.5f);
        AnimationPlayer.apply(scene, nodes, state, pose);
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.printf(Locale.ROOT, "[chain] root rotated 45 deg about Z: tip world %s (expect 2*cos45, 2*sin45)%n",
                vec3(tipPosition, 0));
        assertVec("tip world position at 45 deg",
                new float[] { (float) (2.0 * Math.cos(Math.PI / 4)), (float) (2.0 * Math.sin(Math.PI / 4)), 0f },
                tipPosition, 0, DELTA);
    }

    @Test
    @DisplayName("world matrices land in pose.worldMatrices() in node order, column-major")
    void worldMatricesArePublished() {
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);

        AnimationPlayer.apply(scene, nodes, stateAt(armAnimation(), 1.0f), pose);

        float[] published = pose.worldMatrices();
        System.out.printf(Locale.ROOT, "[worldMatrices] tip translation from the flat array: (%.6f, %.6f, %.6f)%n",
                published[32 + 12], published[32 + 13], published[32 + 14]);
        assertEquals(nodes[2].globalTransform().get(3, 0), published[32 + 12], DELTA, "tip x");
        assertEquals(nodes[2].globalTransform().get(3, 1), published[32 + 13], DELTA, "tip y");
        assertEquals(nodes[2].globalTransform().get(3, 2), published[32 + 14], DELTA, "tip z");
    }

    // ------------------------------------------------------------------
    // Reset, rest pose, defensive skipping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no animation: nodes return to rest and nothing is skinned")
    void restPoseWhenAnimationIsNull() {
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);

        // Pose it first, so the rest assertion proves the reset rather than an untouched state.
        AnimationPlayer.apply(scene, nodes, stateAt(armAnimation(), 1.0f), pose);
        float[] tipPosition = new float[3];
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.println("[rest] animated tip world " + vec3(tipPosition, 0));

        AnimationState stopped = new AnimationState();
        stopped.stop();
        AnimationPlayer.apply(scene, nodes, stopped, pose);
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.println("[rest] after apply with no animation, tip world " + vec3(tipPosition, 0));

        assertVec("tip back at rest", new float[] { 2f, 0f, 0f }, tipPosition, 0, DELTA);
        assertAllZero("flags cleared", pose.animatedFlags(), 0, 3);
        assertAllZero("unskinned joint matrices untouched", pose.jointMatrices(), 0, 16);
    }

    @Test
    @DisplayName("a static skinned model is posed in its bind pose, not with zero joint matrices")
    void staticSkinnedModelShowsBindPose() {
        // ModelInstance.update() always goes through apply(), including for a model that plays
        // nothing, so this is the path a static skinned model takes. If apply() skipped the
        // skinning step when the clock holds no animation, the renderer would read the zero-filled
        // pose array and collapse every skinned vertex to the origin.
        ModelScene scene = skinnedScene(new ModelSkin[] { twoJointSkin() });
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(2, 2);
        AnimationState idle = new AnimationState();
        assertEquals(null, idle.animation(), "fixture must have no animation at all");

        AnimationPlayer.apply(scene, nodes, idle, pose);

        System.out.println("[static-skin] no animation: joint A = " + matrix(pose.jointMatrices(), 0));
        System.out.println("[static-skin] no animation: joint B = " + matrix(pose.jointMatrices(), 16));
        assertMatrix("static joint A = bind pose identity", IDENTITY_MATRIX, pose.jointMatrices(), 0, DELTA);
        assertMatrix("static joint B = bind pose identity", IDENTITY_MATRIX, pose.jointMatrices(), 16, DELTA);
        assertAllZero("no pose flags on a static model", pose.animatedFlags(), 0, 2);
    }

    @Test
    @DisplayName("a channel targeting a node index that does not exist is skipped, not thrown")
    void outOfRangeTargetIsSkipped() {
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);

        float[] q0 = quatZ(0);
        float[] q1 = quatZ(90);
        float[] times = { 0f, 1f, 0f, 1f };
        float[] values = {
                // track 0: rotation aimed at node 99, which this instance does not have
                q0[0], q0[1], q0[2], q0[3], q1[0], q1[1], q1[2], q1[3],
                // track 1: a healthy translation on node 0
                0, 0, 0, 10, 0, 0
        };
        ModelAnimation animation = new ModelAnimation("broken", times, values, new ModelAnimation.Track[] {
                track(99, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 0),
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 2, 2, 8)
        });

        AnimationPlayer.apply(scene, nodes, stateAt(animation, 1.0f), pose);

        System.out.printf(Locale.ROOT, "[out-of-range] node0 translation %s (good channel applied),"
                        + " node0 rotation %s (bad channel skipped)%n",
                vec3(nodes[0].translation(), 0), vec4(nodes[0].rotation(), 0));
        assertVec("healthy channel still applied", new float[] { 10f, 0f, 0f }, nodes[0].translation(), 0, DELTA);
        assertQuat("bad channel wrote nothing", IDENTITY_QUAT, nodes[0].rotation(), 0, 0.0f);
        assertEquals(AnimationPose.FLAG_TRANSLATION, pose.animatedFlags()[0], "only the translation flag");
    }

    @Test
    @DisplayName("sample(node index) on the direct overload matches apply()")
    void directSampleOverloadWorks() {
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        byte[] flags = new byte[3];
        ModelAnimation animation = armAnimation();

        AnimationPlayer.sample(animation, 1.0f, nodes, flags);
        ModelScene.updateWorldTransforms(nodes, scene.rootNodes());

        float[] tipPosition = new float[3];
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        System.out.println("[sample] direct sample at t=1: tip world " + vec3(tipPosition, 0));
        assertVec("tip world position", new float[] { 0f, 2f, 0f }, tipPosition, 0, DELTA);
        assertEquals(AnimationPose.FLAG_ROTATION, flags[0], "root rotation flag");
    }

    // ------------------------------------------------------------------
    // Skinning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bind pose: jointWorld == inverse(IBM) gives identity joint matrices (vertices unmoved)")
    void bindPoseYieldsIdentityJointMatrices() {
        ModelScene scene = skinnedScene(new ModelSkin[] { twoJointSkin() });
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(2, 2);

        AnimationPlayer.apply(scene, nodes, stateAt(emptyClip("bind"), 0.0f), pose);

        float[] jointMatrices = pose.jointMatrices();
        System.out.println("[skin-bind] joint A world = " + nodes[0].globalTransform());
        System.out.println("[skin-bind] joint B world = " + nodes[1].globalTransform());
        System.out.println("[skin-bind] IBM A = " + matrix(INVERSE_BIND_A, 0));
        System.out.println("[skin-bind] IBM B = " + matrix(INVERSE_BIND_B, 0));
        assertMatrix("bind pose joint A = identity", IDENTITY_MATRIX, jointMatrices, 0, DELTA);
        assertMatrix("bind pose joint B = identity", IDENTITY_MATRIX, jointMatrices, 16, DELTA);

        // Spelled out: a vertex at the bind position of joint B is where the skin leaves it.
        float[] jointB = new float[16];
        System.arraycopy(jointMatrices, 16, jointB, 0, 16);
        float[] skinned = new float[3];
        new Mat4(jointB).transformPoint(0f, 1f, 0f, skinned, 0);
        System.out.printf(Locale.ROOT, "[skin-bind] bind vertex (0,1,0) skinned to %s (unmoved)%n",
                vec3(skinned, 0));
        assertVec("bind vertex unmoved", new float[] { 0f, 1f, 0f }, skinned, 0, DELTA);
    }

    @Test
    @DisplayName("skin matrices equal the hand-computed jointWorld * inverseBindMatrix product")
    void skinMatricesMatchHandComputedProduct() {
        // At t = 1 the clip holds:
        //   node 0: rotation RZ(180)
        //   node 1: translation (1,1,0)
        // so joint A world = RZ(180) and joint B world = RZ(180) * T(1,1,0), translation (-1,-1,0).
        // joint A matrix = RZ(180) * RZ(-90)                                  = RZ(90)
        // joint B matrix = RZ(180) * T(1,1,0) * T(0,-1,0) * RZ(-90)
        //                = RZ(180) * T(1,0,0) * RZ(-90)  ->  RZ(90), translation (-1,0,0)
        float[] q90 = quatZ(90);
        float[] q180 = quatZ(180);
        float[] times = { 0f, 1f, 0f, 1f };
        float[] values = {
                q90[0], q90[1], q90[2], q90[3], q180[0], q180[1], q180[2], q180[3],
                0, 1, 0, 1, 1, 0
        };
        ModelAnimation animation = new ModelAnimation("skinned-pose", times, values, new ModelAnimation.Track[] {
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 0),
                track(1, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 2, 2, 8)
        });

        ModelScene scene = skinnedScene(new ModelSkin[] { twoJointSkin() });
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(2, 2);
        AnimationPlayer.apply(scene, nodes, stateAt(animation, 1.0f), pose);

        float[] jointWorldB = new float[3];
        nodes[1].globalTransform().transformPoint(0, 0, 0, jointWorldB, 0);
        System.out.println("[skin-product] joint B world = " + nodes[1].globalTransform()
                + " translation " + vec3(jointWorldB, 0));

        float[] expectedA = {
                0, 1, 0, 0,
                -1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
        float[] expectedB = {
                0, 1, 0, 0,
                -1, 0, 0, 0,
                0, 0, 1, 0,
                -1, 0, 0, 1
        };
        float[] jointMatrices = pose.jointMatrices();
        assertMatrix("joint A = RZ(180) * IBM_A = RZ(90)", expectedA, jointMatrices, 0, DELTA);
        assertMatrix("joint B = RZ(180)*T(1,1,0)*IBM_B", expectedB, jointMatrices, 16, DELTA);

        System.out.println("[skin-product] flags: node0=" + pose.animatedFlags()[0]
                + " node1=" + pose.animatedFlags()[1]);
        assertEquals(AnimationPose.FLAG_ROTATION, pose.animatedFlags()[0], "node 0 rotation flag");
        assertEquals(AnimationPose.FLAG_TRANSLATION, pose.animatedFlags()[1], "node 1 translation flag");
    }

    @Test
    @DisplayName("a model with two skins applies skins[0] and ignores the rest")
    void extraSkinsAreIgnored() {
        ModelSkin extra = new ModelSkin("extra", new int[] { 1 },
                new Mat4[] { matrix(new float[] {
                        2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, 2, 0,
                        0, 0, 0, 1
                }) }, -1);
        ModelScene scene = skinnedScene(new ModelSkin[] { twoJointSkin(), extra });
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(2, 2);

        AnimationPlayer.apply(scene, nodes, stateAt(emptyClip("bind"), 0.0f), pose);

        float[] jointMatrices = pose.jointMatrices();
        System.out.println("[multi-skin] joints[0] = " + matrix(jointMatrices, 0));
        System.out.println("[multi-skin] joints[1] = " + matrix(jointMatrices, 16)
                + "  (skin 1 would have scaled this by 2)");
        assertMatrix("skins[0] joint A applied", IDENTITY_MATRIX, jointMatrices, 0, DELTA);
        assertMatrix("skins[0] joint B applied", IDENTITY_MATRIX, jointMatrices, 16, DELTA);
        assertEquals(2, scene.skins().length, "fixture must declare two skins");
    }

    // ------------------------------------------------------------------
    // Track layout, path coverage, long runs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("times is the concatenation of every track's keyframes, not one entry per track")
    void concatenatedTimesArrayIsIndexedByFirstKeyframe() {
        // Track 0 owns times[0..2] = {0, 1, 2}; track 1 owns times[3..4] = {0, 4}. Both start at
        // their own firstKeyframe, and each track's values are addressed through its own
        // valueOffset - this is the layout ModelAnimation.Track documents and lastKeyframeTime()
        // implements.
        float[] times = { 0f, 1f, 2f, 0f, 4f };
        float[] values = {
                // track 0: translation on node 0, keys 0, 10, 20
                0, 0, 0, 10, 0, 0, 20, 0, 0,
                // track 1: rotation on node 0, keys identity -> 180 degrees about Z
                0, 0, 0, 1, 0, 0, 1, 0
        };
        ModelAnimation animation = new ModelAnimation("two-tracks", times, values, new ModelAnimation.Track[] {
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 0, 3, 0),
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 3, 2, 9)
        });
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);

        AnimationPlayer.apply(scene, nodes, stateAt(animation, 1.0f), pose);
        System.out.printf(Locale.ROOT, "[layout] t=1.0 node0 translation %s rotation %s%n",
                vec3(nodes[0].translation(), 0), vec4(nodes[0].rotation(), 0));
        // Track 0 is exactly on its middle keyframe (10); track 1 is a quarter of the way from
        // identity to 180 degrees, i.e. a 45 degree rotation: (0,0,sin22.5,cos22.5).
        assertVec("track 0 at its own keyframe 1", new float[] { 10f, 0f, 0f }, nodes[0].translation(), 0, DELTA);
        assertQuat("track 1 quarter way", quatZ(45), nodes[0].rotation(), 0, DELTA);

        AnimationPlayer.apply(scene, nodes, stateAt(animation, 4.0f), pose);
        System.out.printf(Locale.ROOT, "[layout] t=4.0 node0 translation %s rotation %s%n",
                vec3(nodes[0].translation(), 0), vec4(nodes[0].rotation(), 0));
        // Track 0's last keyframe is at t=2, so at t=4 it clamps; track 1 ends exactly at t=4.
        assertVec("track 0 clamps to its own last keyframe", new float[] { 20f, 0f, 0f },
                nodes[0].translation(), 0, DELTA);
        assertQuat("track 1 at its last keyframe", quatZ(180), nodes[0].rotation(), 0, DELTA);
    }

    @Test
    @DisplayName("translation, rotation and scale on one node set all three pose flags")
    void allThreePathsSetTheirFlags() {
        float[] q90 = quatZ(90);
        float[] times = { 0f, 1f, 0f, 1f, 0f, 1f };
        float[] values = {
                0, 0, 0, 10, 0, 0,
                0, 0, 0, 1, q90[0], q90[1], q90[2], q90[3],
                1, 1, 1, 3, 3, 3
        };
        ModelAnimation animation = new ModelAnimation("all-paths", times, values, new ModelAnimation.Track[] {
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 0, 2, 0),
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 2, 2, 6),
                track(0, ModelAnimation.Path.SCALE, ModelAnimation.Interpolation.LINEAR, 4, 2, 14)
        });
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);

        AnimationPlayer.apply(scene, nodes, stateAt(animation, 0.5f), pose);

        System.out.printf(Locale.ROOT,
                "[paths] t=0.5 node0 translation %s rotation %s scale %s flags=%d%n",
                vec3(nodes[0].translation(), 0), vec4(nodes[0].rotation(), 0),
                vec3(nodes[0].scale(), 0), pose.animatedFlags()[0]);
        assertVec("translation midpoint", new float[] { 5f, 0f, 0f }, nodes[0].translation(), 0, DELTA);
        assertQuat("rotation midpoint", quatZ(45), nodes[0].rotation(), 0, DELTA);
        assertVec("scale midpoint", new float[] { 2f, 2f, 2f }, nodes[0].scale(), 0, DELTA);
        assertEquals(AnimationPose.FLAG_TRANSLATION | AnimationPose.FLAG_ROTATION | AnimationPose.FLAG_SCALE,
                pose.animatedFlags()[0], "all three flags");

        // The scale reaches the composed world matrix too: with the root scaled 2x and rotated 45
        // degrees, the two-unit tip sits at (5,0,0) + 4 * (cos45, sin45, 0).
        float[] tipPosition = new float[3];
        nodes[2].globalTransform().transformPoint(0, 0, 0, tipPosition, 0);
        float scaledTipX = 5.0f + (float) (4.0 * Math.cos(Math.PI / 4));
        float scaledTipY = (float) (4.0 * Math.sin(Math.PI / 4));
        System.out.printf(Locale.ROOT, "[paths] tip world %s  expected (%.6f, %.6f, 0)%n",
                vec3(tipPosition, 0), scaledTipX, scaledTipY);
        assertVec("tip pushed out by the root scale and rotation",
                new float[] { scaledTipX, scaledTipY, 0f }, tipPosition, 0, DELTA);
    }

    @Test
    @DisplayName("600 frames of a mixed-interpolation clip stay finite and wrap cleanly")
    void longRunStaysFinite() {
        float[] times = { 0f, 1f, 2f, 0f, 2f, 0f, 2f };
        float[] values = {
                // translation, LINEAR, 3 keys
                0, 0, 0, 0, 5, 0, 0, 0, 0,
                // rotation, LINEAR, 2 keys: the near-antipodal pair that needs the short arc
                0, 0, 0, 1, 0, 0, 0.087156f, -0.996195f,
                // scale, CUBICSPLINE, 2 keys with tangents
                0, 0, 0, 1, 1, 1, 0.5f, 0.5f, 0.5f,
                0, 0, 0, 2, 2, 2, 0, 0, 0
        };
        ModelAnimation animation = new ModelAnimation("mixed", times, values, new ModelAnimation.Track[] {
                track(0, ModelAnimation.Path.TRANSLATION, ModelAnimation.Interpolation.LINEAR, 0, 3, 0),
                track(0, ModelAnimation.Path.ROTATION, ModelAnimation.Interpolation.LINEAR, 3, 2, 9),
                cubicTrack(1, ModelAnimation.Path.SCALE, 5, 2, 17)
        });
        ModelScene scene = chainScene();
        ModelNode[] nodes = scene.instantiate();
        AnimationPose pose = new AnimationPose(3, 0);
        AnimationState state = new AnimationState();
        state.play(animation, true, false, 0.0f);

        for (int frame = 0; frame < 600; frame++) {
            state.advance(1.0f / 60.0f);
            AnimationPlayer.apply(scene, nodes, state, pose);
            assertFinite("frame " + frame + " node0 translation", nodes[0].translation(), 0, 3);
            assertFinite("frame " + frame + " node0 rotation", nodes[0].rotation(), 0, 4);
            assertFinite("frame " + frame + " node1 scale", nodes[1].scale(), 0, 3);
            assertFinite("frame " + frame + " tip world", nodes[2].globalTransform().raw(), 0, 16);
        }
        System.out.printf(Locale.ROOT,
                "[long-run] 600 frames, clock=%.4fs, completions=%d, node0 t=%s r=%s, node1 scale=%s%n",
                state.time(), state.completionCount(), vec3(nodes[0].translation(), 0),
                vec4(nodes[0].rotation(), 0), vec3(nodes[1].scale(), 0));
        assertTrue(state.completionCount() >= 4, "a 2s clip loops about 5 times in 10s");
    }
}
