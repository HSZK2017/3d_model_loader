package com.model3d.loader.api;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for driving individual nodes of a model from entity state - the API a flight mod needs and
 * the one thing "play a whole clip" cannot express.
 *
 * <h2>What is actually being pinned here</h2>
 * The failure this feature has to survive is not "the value did not arrive"; it is <b>"the value
 * arrived and was then overwritten"</b>. The pose pipeline resets every node to the file's rest pose
 * and samples the active clip on every {@code update()}, so an override that is written into the
 * node before the sample is silently erased once per frame - and the visible symptom is a part that
 * does not move at all, which is indistinguishable from the feature not being implemented. The
 * "still holds after a second and third update()" assertion is the regression guard for exactly that,
 * and the {@code clear()} test is its mirror image: after clearing, the node must show the animated
 * pose again, bit for bit against an un-overridden instance at the same clock.
 *
 * <p>The model is the project's own generated fixture, parsed in-process through the real
 * {@code ModelFormatRegistry} - see {@link TestModelGenerator}. Its shape is the contract used below:
 * <pre>
 *   node[0] 'bone_root'    translation (0, 0, 0)
 *   node[1] 'bone_spinner' translation (0, 0.5, 0), mesh 0, skin joint 1
 *   animations: 'spin' (node 1 rotation about +Y, 0 -> 180 -> 360 deg over 2 s)
 *               'bob'  (node 1 translation y, 0.5 -> 0.8 -> 0.5 over 1 s)
 * </pre>
 * No GL and no game: the parsers and the animation runtime are deliberately Minecraft-free, so this
 * runs as a plain JUnit test.
 */
class ModelNodeDrivingTest {

    private static final String SPIN = "spin";
    private static final String BOB = "bob";
    private static final String SPINNER = "bone_spinner";
    private static final String ROOT = "bone_root";

    /** Node indices in the fixture; the nodes are looked up by name too, and these cross-check it. */
    private static final int ROOT_NODE = 0;
    private static final int SPINNER_NODE = 1;

    private static ModelScene scene;

    @BeforeAll
    static void parseGeneratedFixture() throws Exception {
        Path output = Path.of("build", "test-fixture", "node_driving_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "node_driving");
    }

    // ------------------------------------------------------------------
    // 1. Addressing a node by name
    // ------------------------------------------------------------------

    @Test
    @DisplayName("nodeNames() is the model's own node list, in file order")
    void nodeNamesMatchTheModel() {
        ModelInstance instance = new ModelInstance(scene, "names");
        List<String> names = instance.nodeNames();
        System.out.println("nodeNames() = " + names);
        assertEquals(scene.nodeCount(), names.size(), "one name per node");
        assertFalse(names.isEmpty(), "a parsed model must expose node names");
        assertEquals(List.of(ROOT, SPINNER), names,
                "the fixture's nodes are bone_root then bone_spinner, in that file order");

        // Every name must address its own node: this is what makes the list usable for a UI or a
        // command, and it catches an off-by-one between the name list and the node array.
        ModelNode[] nodes = instance.nodes();
        for (int i = 0; i < names.size(); i++) {
            assertEquals(nodes[i].name(), names.get(i),
                    "nodeNames() must be the instance's node array in order");
            ModelNodeRef ref = instance.node(names.get(i));
            assertNotNull(ref, "node '" + names.get(i) + "' must be addressable");
            assertEquals(names.get(i), ref.name(), "name() round-trip");
            assertFalse(ref.isOverridden(), "a fresh instance overrides nothing");
        }
    }

    @Test
    @DisplayName("node lookup is case-insensitive, and an absent name is null")
    void nodeLookupIsCaseInsensitive() {
        ModelInstance instance = new ModelInstance(scene, "lookup");
        assertNotNull(instance.node(SPINNER), "exact name");
        assertNotNull(instance.node("BONE_SPINNER"), "upper case");
        assertNotNull(instance.node("Bone_Spinner"), "mixed case");
        // The important part: all spellings address the *same* node, not merely a non-null handle.
        instance.node("Bone_Spinner").setScale(2.0f);
        assertTrue(instance.nodes()[SPINNER_NODE].hasOverrides(),
                "a differently-cased name must drive the same node");
        assertFalse(instance.nodes()[ROOT_NODE].hasOverrides(),
                "and must not touch its parent");

        assertNull(instance.node("no_such_node"), "an absent name is null, not an empty handle");
        assertNull(instance.node(null), "a null name is null, not an exception");
    }

    // ------------------------------------------------------------------
    // 2. Driving a node
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rotation override survives update() after update() without being re-set")
    void rotationOverrideSurvivesUpdates() {
        ModelInstance driven = new ModelInstance(scene, "driven");
        ModelInstance plain = new ModelInstance(scene, "plain");
        assertTrue(driven.play(SPIN, true), "the fixture's " + SPIN + " must be playable");
        assertTrue(plain.play(SPIN, true));

        ModelNodeRef spinner = driven.node(SPINNER);
        spinner.setRotation(0.0f, 90.0f, 0.0f);
        assertTrue(spinner.isOverridden());

        driven.update(0.1f);
        plain.update(0.1f);
        // The override must have replaced the node's own local rotation on the very first frame
        // after it was set, not only "eventually": a quarter turn about +Y is the quaternion
        // (0, sin45, 0, cos45).
        float[] rotation = driven.nodes()[SPINNER_NODE].rotation();
        System.out.printf(Locale.ROOT, "after 1 update: node rotation = (%.6f, %.6f, %.6f, %.6f)%n",
                rotation[0], rotation[1], rotation[2], rotation[3]);
        assertEquals(0.0f, rotation[0], 1e-6f, "x");
        assertEquals((float) Math.sin(Math.PI / 4.0), rotation[1], 1e-6f,
                "y of a 90 deg yaw quaternion");
        assertEquals(0.0f, rotation[2], 1e-6f, "z");
        assertEquals((float) Math.cos(Math.PI / 4.0), rotation[3], 1e-6f,
                "w of a 90 deg yaw quaternion");

        // The animation is genuinely running and the un-driven instance genuinely differs, so the
        // "it stays put" assertion below is about the override rather than about a stopped clock.
        assertTrue(plain.animationState().time() > 0.0f, "the reference clip must be advancing");
        assertTrue(globalDelta(driven, plain, SPINNER_NODE) > 0.1f,
                "with the override in force, the driven node cannot match the clip's pose");

        float[] first = driven.nodes()[SPINNER_NODE].globalTransform().raw().clone();
        driven.update(0.1f);
        float[] second = driven.nodes()[SPINNER_NODE].globalTransform().raw().clone();
        driven.update(0.1f);
        float[] third = driven.nodes()[SPINNER_NODE].globalTransform().raw().clone();

        // This is the regression that matters. The clock is now at 0.3 s and the 'spin' clip has
        // rotated node 1 by 54 degrees; if the override were overwritten by the sample, the matrix
        // would have moved. Reported as a printed delta as well as an assertion, because "0.0000000"
        // is the evidence and "the test passed" is not.
        System.out.printf(Locale.ROOT,
                "driven node world matrix drift: update2 %.3e, update3 %.3e%n",
                maxDelta(first, second), maxDelta(first, third));
        assertEquals(0.0f, maxDelta(first, second), 1e-5f,
                "the override must still hold after a second update()");
        assertEquals(0.0f, maxDelta(first, third), 1e-5f,
                "the override must still hold after a third update()");

        // And it survives the animation being stopped as well: "applied after the animation" means
        // after the sampler's reset-to-rest too, or a driven part would only work while a clip runs.
        driven.stopAnimation();
        driven.update(0.1f);
        float[] afterStop = driven.nodes()[SPINNER_NODE].globalTransform().raw();
        System.out.printf(Locale.ROOT, "drift after stopping the clip: %.3e%n",
                maxDelta(first, afterStop));
        assertEquals(0.0f, maxDelta(first, afterStop), 1e-5f,
                "the override must outlive the clip that was playing when it was set");
    }

    @Test
    @DisplayName("setRotation applies x, then y, then z (the order is pinned, not assumed)")
    void rotationOrderIsXThenYThenZ() {
        ModelInstance instance = new ModelInstance(scene, "euler");
        ModelNodeRef spinner = instance.node(SPINNER);
        // Node 1 is at (0, 0.5, 0) with no rotation in rest pose, so the world matrix of a local
        // point is the rotation applied to it plus that translation.
        //
        // 90 deg about x maps local +Y to +Z; 90 deg about y then maps +Z to +X. Expected world
        // point of local (0, 1, 0): (1, 0.5, 0). The other composition order (y then x) leaves the
        // point at (0, 0.5, 1), so this single assertion separates the two conventions.
        spinner.setRotation(90.0f, 90.0f, 0.0f);
        instance.update(0.0f);
        float[] point = new float[3];
        com.model3d.loader.math.Mat4.transform(
                instance.nodes()[SPINNER_NODE].globalTransform().raw(), 0, 0.0f, 1.0f, 0.0f, 1.0f,
                point);
        System.out.printf(Locale.ROOT, "setRotation(90, 90, 0): local (0,1,0) -> (%.4f, %.4f, %.4f)%n",
                point[0], point[1], point[2]);
        assertEquals(1.0f, point[0], 1e-4f, "x first, then y: local +Y ends up along +X");
        assertEquals(0.5f, point[1], 1e-4f, "the node's own translation is unaffected");
        assertEquals(0.0f, point[2], 1e-4f, "the reversed order would leave 1.0 here");
    }

    @Test
    @DisplayName("setScale scales about the node's own origin, and setTranslation adds to the pose")
    void scaleAndTranslationOverrides() {
        ModelInstance instance = new ModelInstance(scene, "scale");
        ModelNodeRef spinner = instance.node(SPINNER);
        spinner.setScale(2.0f);
        instance.update(0.0f);

        float[] point = new float[3];
        com.model3d.loader.math.Mat4.transform(
                instance.nodes()[SPINNER_NODE].globalTransform().raw(), 0, 0.0f, 0.0f, 0.0f, 1.0f,
                point);
        assertEquals(0.0f, point[0], 1e-6f, "the origin must not move: this scales about the node");
        assertEquals(0.5f, point[1], 1e-6f, "the origin must not move");
        assertEquals(0.0f, point[2], 1e-6f, "the origin must not move");

        com.model3d.loader.math.Mat4.transform(
                instance.nodes()[SPINNER_NODE].globalTransform().raw(), 0, 0.5f, 0.5f, 0.5f, 1.0f,
                point);
        assertEquals(1.0f, point[0], 1e-6f, "a local offset doubles under a uniform scale of 2");
        assertEquals(1.5f, point[1], 1e-6f, "(0.5 * 2) + the node's authored y of 0.5");
        assertEquals(1.0f, point[2], 1e-6f, "a local offset doubles under a uniform scale of 2");

        // The translation override is additive, on top of what the clip authored. 'bob' puts node 1
        // at y = 0.8 halfway through its one-second loop, and the override must not replace that.
        ModelInstance bobbed = new ModelInstance(scene, "bob");
        assertTrue(bobbed.play(BOB, true));
        bobbed.node(SPINNER).setTranslation(1.0f, 0.0f, 0.0f);
        stepSeconds(bobbed, 0.5f);
        float[] translation = bobbed.nodes()[SPINNER_NODE].translation();
        System.out.printf(Locale.ROOT, "bob at t=0.5 + setTranslation(1,0,0): (%.4f, %.4f, %.4f)%n",
                translation[0], translation[1], translation[2]);
        assertEquals(1.0f, translation[0], 1e-4f, "the added offset");
        assertEquals(0.8f, translation[1], 1e-4f,
                "the clip's authored translation must still be there - the override adds");
    }

    @Test
    @DisplayName("clear() restores the animated pose exactly, and is a no-op when nothing is set")
    void clearRestoresTheAnimatedPose() {
        ModelInstance driven = new ModelInstance(scene, "driven");
        ModelInstance plain = new ModelInstance(scene, "plain");
        assertTrue(driven.play(SPIN, true));
        assertTrue(plain.play(SPIN, true));

        ModelNodeRef spinner = driven.node(SPINNER);
        assertFalse(spinner.isOverridden(), "a fresh instance has no overrides");
        spinner.clear();
        spinner.clear();
        spinner.setRotation(0.0f, 90.0f, 0.0f).setScale(1.5f).setTranslation(0.25f, 0.0f, 0.0f);
        assertTrue(spinner.isOverridden(), "every setter must be visible through isOverridden()");

        stepSeconds(driven, 0.4f);
        stepSeconds(plain, 0.4f);
        float drivenDelta = globalDelta(driven, plain, SPINNER_NODE);
        System.out.printf(Locale.ROOT, "driven vs plain, override in force: max delta %.6f%n",
                drivenDelta);
        assertTrue(drivenDelta > 0.05f,
                "with the override set, the two instances must pose differently; delta=" + drivenDelta);

        spinner.clear();
        assertFalse(spinner.isOverridden(), "clear() drops every override");
        assertTrue(spinner.visible(), "clear() also drops a visibility override");

        // Both instances are at the same clock position and the override is gone, so the next
        // resample must reproduce the animated pose - the same floats, through the same code path.
        driven.update(0.1f);
        plain.update(0.1f);
        float afterClear = globalDelta(driven, plain, SPINNER_NODE);
        System.out.printf(Locale.ROOT,
                "driven vs plain after clear(): max delta %.3e (was %.6f)%n",
                afterClear, drivenDelta);
        assertEquals(0.0f, afterClear, 1e-6f,
                "after clear() the node must show exactly the pose the clip gives the plain instance");
    }

    @Test
    @DisplayName("the degrees-to-quaternion conversion is unit for every angle triple")
    void eulerConversionIsAlwaysAUnitQuaternion() {
        // Mat4.fromQuat assumes a unit quaternion; a conversion that is not unit does not rotate the
        // node, it scales it. That is not hypothetical here: the first version of the two-axis
        // assertion below failed with x = 0.49999994 instead of 1.0, because the z component's last
        // term multiplied sin(z/2) where the product needs cos(z/2) - so the quaternion had length
        // 0.866 and every driven part came out 13% too small. This walks a spread of triples and
        // checks the length, which is the invariant that catches that class of slip whatever the
        // angles are.
        ModelInstance instance = new ModelInstance(scene, "unit");
        ModelNodeRef spinner = instance.node(SPINNER);
        float[][] triples = {
                { 30.0f, 0.0f, 0.0f }, { 0.0f, 45.0f, 0.0f }, { 0.0f, 0.0f, 90.0f },
                { 90.0f, 90.0f, 0.0f }, { 12.0f, -34.0f, 56.0f }, { 180.0f, 180.0f, 180.0f },
                { -90.0f, 0.0f, 45.0f },
        };
        for (float[] triple : triples) {
            spinner.setRotation(triple[0], triple[1], triple[2]);
            instance.update(0.0f);
            float[] q = instance.nodes()[SPINNER_NODE].rotation();
            double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
            System.out.printf(Locale.ROOT, "setRotation(%s, %s, %s) -> q=(%.6f, %.6f, %.6f, %.6f)"
                            + " |q|=%.9f%n", triple[0], triple[1], triple[2], q[0], q[1], q[2], q[3],
                    norm);
            assertEquals(1.0, norm, 1e-6, "a non-unit quaternion scales the node instead of rotating it");
        }
        // Zero is the identity, not an empty override: a caller that wants the rest orientation of a
        // node says so with zeros.
        spinner.setRotation(0.0f, 0.0f, 0.0f);
        instance.update(0.0f);
        float[] identity = instance.nodes()[SPINNER_NODE].rotation();
        assertEquals(0.0f, identity[0], 1e-7f);
        assertEquals(0.0f, identity[1], 1e-7f);
        assertEquals(0.0f, identity[2], 1e-7f);
        assertEquals(1.0f, identity[3], 1e-7f);
    }

    @Test
    @DisplayName("an override on a skin joint moves the skinned geometry, not only the node")
    void overrideOnAJointMovesTheSkin() {
        // Why this test exists: where in the frame the override is applied decides whether a driven
        // joint moves its skin. The fixture's spinner node IS skin joint 1, so a rotation override
        // that reaches the joint matrices moves the cube's upper half; one applied after the joint
        // matrices are computed would leave the skin at the clip's pose while the node's own matrix
        // looked correct - a part that "moves" in a log and not on screen.
        ModelInstance driven = new ModelInstance(scene, "driven-joint");
        ModelInstance plain = new ModelInstance(scene, "plain-joint");
        assertTrue(driven.play(SPIN, true));
        assertTrue(plain.play(SPIN, true));

        // Both at t = 0.5 s, where 'spin' has turned the spinner a quarter turn about +Y. The
        // integration test computes the same point for that pose: (-0.5, 0.5, 0.5).
        stepSeconds(driven, 0.5f);
        stepSeconds(plain, 0.5f);
        float[] clipPoint = skin(plain.jointMatrices(), 1, 0.5f, 0.5f, 0.5f);

        // A half turn, not a quarter: chosen so the override's result differs from the clip's on z,
        // which makes "the override won" visible in the skinned vertex rather than only in the node.
        driven.node(SPINNER).setRotation(0.0f, 180.0f, 0.0f);
        driven.update(0.0f);
        float[] drivenPoint = skin(driven.jointMatrices(), 1, 0.5f, 0.5f, 0.5f);
        System.out.printf(Locale.ROOT,
                "skinned vertex (0.5, 0.5, 0.5) -> clip t=0.5s (%.4f, %.4f, %.4f),"
                        + " driven (0.5, 0.5, 0.5) at yaw 180 (%.4f, %.4f, %.4f)%n",
                clipPoint[0], clipPoint[1], clipPoint[2],
                drivenPoint[0], drivenPoint[1], drivenPoint[2]);

        assertEquals(-0.5f, clipPoint[0], 1e-4f, "premise: the clip's quarter turn moves the vertex");
        assertEquals(0.5f, clipPoint[2], 1e-4f, "premise: the clip's quarter turn moves the vertex");

        // Hand-derived, independently of the production code: jointWorld = T(0,0.5,0)*Ry(180),
        // inverse bind = T(0,-0.5,0), so the bind vertex (0.5,0.5,0.5) -> (0.5,0,0.5) after the
        // inverse bind, negated in x and z by the half turn -> (-0.5,0,-0.5), then the joint's own
        // translation back to (-0.5, 0.5, -0.5).
        assertEquals(-0.5f, drivenPoint[0], 1e-4f, "the override must reach the skinning matrices");
        assertEquals(0.5f, drivenPoint[1], 1e-4f, "this joint only rotates about +Y");
        assertEquals(-0.5f, drivenPoint[2], 1e-4f,
                "the clip's pose put +0.5 here; the driven pose must show its own value");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Applies one joint's matrix to a bind-pose vertex exactly as the vertex shader does, written out
     * longhand on purpose: reusing a production helper here would make a wrong convention inside that
     * helper invisible to this test. Column-major, {@code m[column * 4 + row]}.
     */
    private static float[] skin(float[] matrices, int joint, float x, float y, float z) {
        int base = joint * 16;
        float m0 = matrices[base];
        float m1 = matrices[base + 1];
        float m2 = matrices[base + 2];
        float m4 = matrices[base + 4];
        float m5 = matrices[base + 5];
        float m6 = matrices[base + 6];
        float m8 = matrices[base + 8];
        float m9 = matrices[base + 9];
        float m10 = matrices[base + 10];
        float m12 = matrices[base + 12];
        float m13 = matrices[base + 13];
        float m14 = matrices[base + 14];
        return new float[] {
                m0 * x + m4 * y + m8 * z + m12,
                m1 * x + m5 * y + m9 * z + m13,
                m2 * x + m6 * y + m10 * z + m14,
        };
    }

    /** Advances {@code total} seconds in sub-clamp increments, as a renderer's frames would. */
    private static void stepSeconds(ModelInstance instance, float total) {
        float step = 0.1f;
        int steps = Math.round(total / step);
        for (int i = 0; i < steps; i++) {
            instance.update(step);
        }
    }

    /** Largest absolute element difference between two instances' world matrix for one node. */
    private static float globalDelta(ModelInstance a, ModelInstance b, int node) {
        return maxDelta(a.nodes()[node].globalTransform().raw(), b.nodes()[node].globalTransform().raw());
    }

    private static float maxDelta(float[] a, float[] b) {
        float max = 0.0f;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }
}
