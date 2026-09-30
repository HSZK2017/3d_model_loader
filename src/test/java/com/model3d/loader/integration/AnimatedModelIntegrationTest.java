package com.model3d.loader.integration;

import com.model3d.loader.animation.AnimationPlayer;
import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelImage;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The seam test: parse a real file, animate it, and check the skinned vertex positions that come
 * out the far end.
 *
 * <h2>Why this test has to exist separately</h2>
 * Every other test in this project stops at a boundary it owns. The parser tests assert on a
 * {@link ModelScene} and never run the animation runtime; the animation tests assert on hand-built
 * fixtures and never run a parser. Both suites can be fully green while the two halves disagree
 * about, say, whether a joint index is a node index or a skin-slot index, or whether the
 * inverse-bind matrix is stored column-major. Nothing in either suite would notice.
 *
 * <p>So this test drives the whole CPU pipeline in one go:
 * <pre>
 *   generated .glb bytes -> ModelFormatRegistry -> ModelScene -> ModelInstance
 *                        -> AnimationPlayer -> pose.jointMatrices() -> skinned vertex positions
 * </pre>
 * and asserts on <b>where a vertex actually ends up</b>, computed through the same arithmetic the
 * GPU vertex shader performs. A vertex's final position is the one number that cannot be faked by
 * two components being wrong in compensating ways.
 *
 * <h2>Why the model is generated rather than loaded</h2>
 * No asset in the third-party corpus carries animation or skin data — all five glTF/GLB files have
 * zero {@code animations} and {@code skins} keys. The generated fixture (see
 * {@link TestModelGenerator}) is a two-bone skinned cube whose geometry is known exactly, which is
 * what makes an exact position assertion possible instead of a tolerance guess.
 */
class AnimatedModelIntegrationTest {

    /** The fixture's own contract; see TestModelGenerator's javadoc. These are its stated values. */
    private static final int EXPECTED_NODES = 2;
    private static final int EXPECTED_JOINTS = 2;
    private static final int EXPECTED_VERTICES = 8;
    private static final int EXPECTED_TRIANGLES = 12;
    private static final String SPIN = "spin";
    private static final String BOB = "bob";
    private static final float SPIN_SECONDS = 2.0f;

    private static ModelScene scene;

    @BeforeAll
    static void parseGeneratedFixture() throws IOException, ModelParseException {
        // Generated into the test's own build directory rather than read out of src/main/resources:
        // this test is about the generator and the parsers agreeing, and reading the committed
        // copy would let a stale resource hide a generator regression.
        Path output = Path.of("build", "test-fixture", "animated_test.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());

        DirectoryModelSource source = new DirectoryModelSource(
                output.getParent(), "model3d", output.getFileName().toString());
        scene = ModelFormatRegistry.parse(source, "animated_test");
    }

    @Test
    @DisplayName("the generated fixture parses into the shape the generator documents")
    void fixtureParsesToDocumentedShape() {
        System.out.println("parsed: " + scene);
        assertEquals(EXPECTED_NODES, scene.nodeCount(), "node count");
        assertEquals(1, scene.meshes().length, "mesh count");
        assertEquals(1, scene.skins().length, "skin count");
        assertEquals(EXPECTED_JOINTS, scene.skins()[0].jointCount(), "joint count");
        assertEquals(2, scene.animations().size(), "animation count");
        assertNotNull(scene.animation(SPIN), "animation '" + SPIN + "'");
        assertNotNull(scene.animation(BOB), "animation '" + BOB + "'");
        assertEquals(SPIN_SECONDS, scene.animation(SPIN).duration(), 1e-4f, "spin duration");

        ModelMesh mesh = scene.meshes()[0];
        assertTrue(mesh.isSkinned(), "the fixture mesh must be skinned");
        ModelPrimitive primitive = mesh.primitives()[0];
        assertEquals(EXPECTED_VERTICES, primitive.vertexCount(), "vertex count");
        assertEquals(EXPECTED_TRIANGLES, primitive.indexCount() / 3, "triangle count");
        assertEquals(EXPECTED_JOINTS - 1, primitive.maxJointIndex(),
                "the fixture must reference both joints, or half of it is unskinned");
    }

    @Test
    @DisplayName("both animations drive exactly one node each, with the documented paths")
    void animationsTargetTheDocumentedNodesAndPaths() {
        for (String name : new String[] { SPIN, BOB }) {
            var animation = scene.animation(name);
            System.out.printf(Locale.ROOT, "%s: tracks=%d duration=%.4f keyframes=%d%n",
                    name, animation.trackCount(), animation.duration(), animation.times().length);
            assertEquals(1, animation.trackCount(), name + " should have one track");
            var track = animation.tracks()[0];
            assertEquals(1, track.targetNode(), name + " should drive the spinner bone (node 1)");
            assertEquals(3, track.keyframeCount(), name + " keyframe count");
            System.out.printf(Locale.ROOT, "  node[%d] %s %s%n", track.targetNode(), track.path(),
                    track.interpolation());
        }
        assertEquals(com.model3d.loader.scene.ModelAnimation.Path.ROTATION,
                scene.animation(SPIN).tracks()[0].path(), SPIN + " path");
        assertEquals(com.model3d.loader.scene.ModelAnimation.Path.TRANSLATION,
                scene.animation(BOB).tracks()[0].path(), BOB + " path");
    }

    @Test
    @DisplayName("a posed skinned frame puts vertices where a hand-computed transform says they go")
    void skinnedVerticesLandWhereTheMathSays() {
        ModelInstance instance = new ModelInstance(scene, "integration");
        assertTrue(instance.play(SPIN, true), "the fixture's " + SPIN + " animation must be playable");

        // Step to exactly half the spin in sub-clamp increments: the fixture's 'spin' is
        // 0 -> 180 -> 360 degrees about +Y over 2 s, so t = 0.5 s is a quarter turn.
        //
        // Stepped in tenths rather than with one update(0.5f) because AnimationState clamps a
        // single step to MAX_STEP_SECONDS (0.25 s). That clamp is deliberate - it keeps the visible
        // pose a function of elapsed time rather than of frame rate - but it is silent, and the
        // first version of this test asserted a pose for t=0.5 while the clock actually sat at
        // 0.25. Stepping realistically is what the renderer does, and it makes the intent explicit.
        stepSeconds(instance, 0.5f);
        assertEquals(0.5f, instance.animationState().time(), 1e-4f,
                "the clock must reach exactly the requested time when stepped in sub-clamp increments");
        System.out.printf(Locale.ROOT, "clock=%s%n", instance.animationState());
        System.out.printf(Locale.ROOT, "spinner node rotation=(%.6f, %.6f, %.6f, %.6f)%n",
                instance.nodes()[1].rotation()[0], instance.nodes()[1].rotation()[1],
                instance.nodes()[1].rotation()[2], instance.nodes()[1].rotation()[3]);
        System.out.printf(Locale.ROOT, "spinner node translation=(%.6f, %.6f, %.6f)%n",
                instance.nodes()[1].translation()[0], instance.nodes()[1].translation()[1],
                instance.nodes()[1].translation()[2]);
        System.out.printf(Locale.ROOT, "joints (skin order) = %s%n",
                java.util.Arrays.toString(scene.skins()[0].joints()));
        float[] joints = instance.jointMatrices();
        assertNotNull(joints, "a skinned model must produce joint matrices");
        assertEquals(EXPECTED_JOINTS * 16, joints.length, "joint matrix block size");

        System.out.println("--- joint matrices at t=0.5s (quarter turn about +Y) ---");
        for (int joint = 0; joint < EXPECTED_JOINTS; joint++) {
            System.out.printf(Locale.ROOT, "  joint[%d] %s%n", joint,
                    describe(joints, joint * 16));
        }

        // The vertex at (0.5, 0.5, 0.5) belongs to the spinner bone (y = 0.5 is at or above the
        // joint plane) and sits at bind offset (0.5, 0, 0.5) from that bone's origin (0, 0.5, 0).
        //
        // This expectation was wrong TWICE before it was right, which is why it is worth stating
        // where it comes from. It is derived from the definition the runtime implements -
        // jointMatrix = jointWorld * inverseBind, applied to the bind-space vertex - using the
        // rotation matrix built independently from the sampled quaternion, with the inverse bind
        // from the fixture's own file: translate(0, -0.5, 0). Worked out numerically, independently
        // of the production code: bind (0.5, 0.5, 0.5) -> (-0.5, 0.5, 0.5).
        //
        // What makes the assertion meaningful is the set of nearby wrong answers it excludes:
        //   * no rotation at all (a missing or identity track)      -> ( 0.5,  0.5,  0.5)
        //   * rotation the other way                                -> ( 0.5,  0.5, -0.5)
        //   * inverse bind applied in the wrong direction           -> ( 0.5,  1.5,  0.5)
        //   * jointWorld inverted                                   -> (-0.5,  0.5, -0.5)
        //   * inverse bind ignored                                  -> ( 0.5,  0.0,  0.5)
        float[] actual = skin(joints, 1, 0.5f, 0.5f, 0.5f);
        System.out.printf(Locale.ROOT, "vertex (0.5, 0.5, 0.5) skinned -> (%.6f, %.6f, %.6f)%n",
                actual[0], actual[1], actual[2]);

        assertEquals(-0.5f, actual[0], 1e-4f, "x after a quarter turn about +Y");
        assertEquals(0.5f, actual[1], 1e-4f, "y: this bone only rotates about Y, so y cannot change");
        assertEquals(0.5f, actual[2], 1e-4f, "z after a quarter turn about +Y");

        // The other half of the cube is bound to the static root bone and must NOT move. This is
        // what makes a wrong joint index visible: if the vertex below the plane were skinned to
        // the spinner, it would move, and if the upper one were skinned to the root, it would not.
        float[] stationary = skin(joints, 0, 0.5f, -0.5f, 0.5f);
        System.out.printf(Locale.ROOT, "vertex (0.5, -0.5, 0.5) skinned -> (%.6f, %.6f, %.6f)%n",
                stationary[0], stationary[1], stationary[2]);
        assertEquals(0.5f, stationary[0], 1e-4f, "the root-bound half must not move");
        assertEquals(-0.5f, stationary[1], 1e-4f, "the root-bound half must not move");
        assertEquals(0.5f, stationary[2], 1e-4f, "the root-bound half must not move");

        // The two halves must land exactly one unit apart on x, which is the geometric statement of
        // "a quarter turn about +Y swung the upper half a quarter of the way around". A permutation
        // of the wrong corner would keep the distance but change which corner, so this is a second,
        // independent handle on the same fact rather than a restatement of it.
        assertEquals(1.0f, Math.abs(actual[0] - stationary[0]), 1e-4f,
                "the two halves must end up one unit apart on x");
    }

    @Test
    @DisplayName("the documented step clamp is real: one coarse update does not integrate it whole")
    void coarseStepIsClamped() {
        ModelInstance instance = new ModelInstance(scene, "integration");
        instance.play(SPIN, true);
        // One call with a half-second delta advances only MAX_STEP_SECONDS. Pinned as a test because
        // it is the exact trap the skinned-vertex test above fell into: the animation keeps
        // playing, at half the expected rate, with nothing to indicate the clock was truncated.
        instance.update(0.5f);
        assertEquals(com.model3d.loader.animation.AnimationState.MAX_STEP_SECONDS,
                instance.animationState().time(), 1e-4f,
                "a single update() must not integrate more than the documented step cap");
        System.out.printf(Locale.ROOT, "update(0.5f) -> clock %.4f s (cap %.4f s)%n",
                instance.animationState().time(),
                com.model3d.loader.animation.AnimationState.MAX_STEP_SECONDS);

        // And stepping the same total in sub-clamp increments really does reach it, so the cap is a
        // per-call limit rather than a leak of time.
        ModelInstance stepped = new ModelInstance(scene, "integration");
        stepped.play(SPIN, true);
        stepSeconds(stepped, 0.5f);
        assertEquals(0.5f, stepped.animationState().time(), 1e-4f,
                "five 0.1 s steps must reach 0.5 s");
    }

    /** Advances {@code total} seconds in sub-clamp increments, as a renderer's frames would. */
    private static void stepSeconds(ModelInstance instance, float total) {
        float step = 0.1f;
        int steps = Math.round(total / step);
        for (int i = 0; i < steps; i++) {
            instance.update(step);
        }
    }

    @Test
    @DisplayName("the pose is a function of the clock: two times give two different poses, all finite")
    void poseChangesWithTheClock() {
        ModelInstance instance = new ModelInstance(scene, "integration");
        instance.play(SPIN, true);

        List<float[]> samples = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // Sub-clamp steps, so the sampled clock positions are the ones this test names.
            stepSeconds(instance, SPIN_SECONDS / 8.0f);
            samples.add(instance.jointMatrices().clone());
        }

        for (float[] sample : samples) {
            for (float value : sample) {
                assertTrue(Float.isFinite(value),
                        "a non-finite joint matrix would collapse the mesh to the origin; got "
                                + java.util.Arrays.toString(sample));
            }
        }
        // Sampling a spinning bone at eight evenly spaced times inside one full turn must produce
        // eight distinct poses. Identical samples would mean the clock is not reaching the sampler.
        long distinct = samples.stream().map(java.util.Arrays::toString).distinct().count();
        System.out.printf(Locale.ROOT, "8 frames across one 2 s spin -> %d distinct joint-matrix "
                + "blocks%n", distinct);
        assertEquals(samples.size(), distinct, "every sampled frame should differ");
    }

    @Test
    @DisplayName("after a full turn the pose returns to the start, i.e. the loop closes")
    void fullTurnReturnsToTheStart() {
        ModelInstance instance = new ModelInstance(scene, "integration");
        instance.play(SPIN, true);
        instance.update(0.0f);
        float[] start = instance.jointMatrices().clone();

        // Advance in small steps so the clock wraps naturally instead of being clamped, then land
        // exactly on one full duration.
        for (int i = 0; i < 10; i++) {
            stepSeconds(instance, SPIN_SECONDS / 10.0f);
        }
        float[] after = instance.jointMatrices();
        float maxDelta = 0.0f;
        for (int i = 0; i < start.length; i++) {
            maxDelta = Math.max(maxDelta, Math.abs(start[i] - after[i]));
        }
        System.out.printf(Locale.ROOT, "max |jointMatrix(t=0) - jointMatrix(t=2s)| = %.6e%n", maxDelta);
        // The fixture's spin ends at 360 degrees, which is the same orientation as 0 degrees, so a
        // closed loop must reconstruct the starting matrices. A missing modulo in the clock or a
        // sign error in the quaternion would show up here as a large delta.
        assertEquals(0.0f, maxDelta, 1e-3f, "one full turn must reproduce the starting pose");
    }

    @Test
    @DisplayName("a model with no animation attached still yields a valid bind pose")
    void staticModelHasABindPose() {
        ModelInstance instance = new ModelInstance(scene, "integration");
        // No play() call at all: this is the "skinned but not animated" case, which rendered every
        // vertex at the origin before the update() path was unified.
        instance.update(0.016f);
        float[] joints = instance.jointMatrices();
        assertNotNull(joints, "a skinned model must produce joint matrices even with no animation");
        for (float value : joints) {
            assertTrue(Float.isFinite(value), "bind-pose joint matrices must be finite");
        }
        float[] actual = skin(joints, 1, 0.5f, 0.5f, 0.5f);
        System.out.printf(Locale.ROOT, "un-animated bind pose: vertex (0.5, 0.5, 0.5) -> "
                + "(%.6f, %.6f, %.6f)%n", actual[0], actual[1], actual[2]);
        assertEquals(0.5f, actual[0], 1e-4f, "bind pose must leave the vertex where it was authored");
        assertEquals(0.5f, actual[1], 1e-4f, "bind pose");
        assertEquals(0.5f, actual[2], 1e-4f, "bind pose");
    }

    @Test
    @DisplayName("re-entrancy: two instances of one scene animate independently")
    void instancesDoNotSharePoseState() {
        ModelInstance first = new ModelInstance(scene, "first");
        ModelInstance second = new ModelInstance(scene, "second");
        assertTrue(first.play(SPIN, true));
        assertTrue(second.play(SPIN, true));

        first.update(0.1f);
        float[] firstPose = first.jointMatrices().clone();
        // The second instance stays at t = 0 and must be unaffected by the first one having moved.
        second.update(0.0f);
        float[] secondPose = second.jointMatrices().clone();

        float maxDelta = 0.0f;
        for (int i = 0; i < firstPose.length; i++) {
            maxDelta = Math.max(maxDelta, Math.abs(firstPose[i] - secondPose[i]));
        }
        System.out.printf(Locale.ROOT, "two instances, clocks 0.1 s apart -> max matrix delta %.6f%n",
                maxDelta);
        // A shared node tree would make both instances report the same (last-written) pose. This is
        // the specific bug ModelScene.instantiate() exists to prevent, and it is invisible in every
        // single-instance test.
        assertTrue(maxDelta > 0.005f, "two instances at different clock positions must pose "
                + "differently; maxDelta=" + maxDelta);
    }

    @Test
    @DisplayName("the generated fixture carries no embedded images, so the embedded path is opt-in")
    void generatedFixtureHasNoEmbeddedImages() {
        // Pins the fixture's contract: it is geometry + skin + animation only. If someone adds an
        // embedded image here, the real corpus (where embedded images are the norm) stops being the
        // only evidence for that path, and the note in README changes.
        assertEquals(0, scene.embeddedImages().size(),
                "the generated fixture is deliberately image-free; got " + scene.embeddedImages());
        for (ModelImage image : scene.embeddedImages()) {
            assertTrue(image.byteSize() > 0);
        }
    }

    @Test
    @DisplayName("a real third-party model parses when the corpus is present")
    void realModelParsesWhenAvailable() {
        Path sample = Path.of("models", "sukhoi_su-30_flanker_c.glb").toAbsolutePath();
        assumeTrue(Files.isRegularFile(sample),
                "third-party corpus not present at " + sample + "; this check is opt-in");

        try {
            DirectoryModelSource source = new DirectoryModelSource(
                    sample.getParent(), "corpus", sample.getFileName().toString());
            ModelScene real = ModelFormatRegistry.parse(source, "su30");
            System.out.printf(Locale.ROOT, "corpus model parsed: %s%n", real);

            // Numbers measured on the real file, not guessed: 22 meshes, 22 materials, 24244
            // vertices, 21585 triangles, 279.288 units on its longest axis.
            assertEquals(22, real.meshes().length, "mesh count of the real sample");
            assertEquals(22, real.materials().length, "material count of the real sample");
            int vertices = 0;
            int triangles = 0;
            for (ModelMesh mesh : real.meshes()) {
                for (ModelPrimitive primitive : mesh.primitives()) {
                    vertices += primitive.vertexCount();
                    triangles += primitive.indexCount() / 3;
                }
            }
            assertEquals(24244, vertices, "total vertex count of the real sample");
            assertEquals(21585, triangles, "total triangle count of the real sample");
            assertEquals(279.288f, real.longestExtent(), 0.01f, "longest axis of the real sample");
            System.out.printf(Locale.ROOT, "  %d vertices, %d triangles, longest axis %.3f -> "
                            + "scale %.6f blocks/unit for a 4-block target%n",
                    vertices, triangles, real.longestExtent(), real.scaleForTargetSize(4.0f));
        } catch (ModelParseException e) {
            throw new AssertionError("the real corpus model failed to parse: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // The vertex shader's arithmetic, on the CPU
    // ------------------------------------------------------------------

    /**
     * Applies one joint's matrix to a bind-pose vertex exactly as the vertex shader does:
     * {@code position * matrix} with the matrix in column-major order, as glTF stores it.
     *
     * <p>Deliberately written out longhand instead of reusing {@code Mat4.transformPoint}: if the
     * production helper and the test both used the same helper, a wrong convention inside the
     * helper would make the test pass. This is an independent implementation of the same
     * specification, which is the only way the assertion means anything.
     */
    private static float[] skin(float[] matrices, int joint, float x, float y, float z) {
        int base = joint * 16;
        float m0 = matrices[base];
        float m1 = matrices[base + 1];
        float m2 = matrices[base + 2];
        float m3 = matrices[base + 3];
        float m4 = matrices[base + 4];
        float m5 = matrices[base + 5];
        float m6 = matrices[base + 6];
        float m7 = matrices[base + 7];
        float m8 = matrices[base + 8];
        float m9 = matrices[base + 9];
        float m10 = matrices[base + 10];
        float m11 = matrices[base + 11];
        float m12 = matrices[base + 12];
        float m13 = matrices[base + 13];
        float m14 = matrices[base + 14];
        float m15 = matrices[base + 15];
        // Column-major: column c contributes m[c*4 + row] * component[c], and the translation lives
        // in column 3. The result is NOT perspective-divided: skinning matrices are affine.
        float rx = m0 * x + m4 * y + m8 * z + m12;
        float ry = m1 * x + m5 * y + m9 * z + m13;
        float rz = m2 * x + m6 * y + m10 * z + m14;
        float rw = m3 * x + m7 * y + m11 * z + m15;
        if (rw != 0.0f && rw != 1.0f) {
            rx /= rw;
            ry /= rw;
            rz /= rw;
        }
        return new float[] { rx, ry, rz };
    }

    private static String describe(float[] matrices, int base) {
        StringBuilder sb = new StringBuilder("[");
        for (int row = 0; row < 4; row++) {
            if (row > 0) {
                sb.append("; ");
            }
            for (int column = 0; column < 4; column++) {
                sb.append(String.format(Locale.ROOT, "%8.4f",
                        matrices[base + column * 4 + row]));
                if (column < 3) {
                    sb.append(' ');
                }
            }
        }
        return sb.append(']').toString();
    }

    /**
     * Guards the test's own premise: if the fixture stopped being skinned or animated, every
     * assertion above would fail for a reason unrelated to the code under test, and the failure
     * message would point at the runtime instead of at the fixture.
     */
    @Test
    @DisplayName("premise: the fixture really is skinned and animated")
    void fixturePremiseHolds() {
        ModelSkin skin = scene.skins()[0];
        assertEquals(EXPECTED_JOINTS, skin.jointCount());
        for (int joint = 0; joint < skin.jointCount(); joint++) {
            assertNotNull(skin.inverseBindMatrices()[joint],
                    "joint " + joint + " must have an inverse bind matrix");
        }
        assertTrue(scene.isAnimated(), "the fixture must carry animation data");
        // And the animation runtime must be reachable at all - it threw UnsupportedOperationException
        // for most of this project's life, which is exactly the state this test exists to catch.
        AnimationPlayer.apply(scene, scene.instantiate(), new com.model3d.loader.animation.AnimationState(),
                new com.model3d.loader.animation.AnimationPose(scene.nodeCount(),
                        skin.jointCount()));
    }
}
