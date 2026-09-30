package com.model3d.loader.client.render;

import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.api.ModelNodeRef;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a hidden node's primitives - and its descendants' - never reach the draw list, while
 * the rest of the world keeps drawing them.
 *
 * <h2>The two failures this exists to prevent</h2>
 * <ol>
 *   <li><b>Hidden geometry that is still submitted.</b> The draw list is what the renderer iterates,
 *       so a "hidden" part that is merely transformed away, or flagged somewhere the walk does not
 *       look, still costs its vertices and still shows. The assertions here are therefore on
 *       {@link ModelDrawList#drawableCount()} itself, not on a flag: a hidden node and every node
 *       under it must be absent from the list.</li>
 *   <li><b>The shared-template trap.</b> {@code ModelScene} is shared by every entity using that
 *       model. If visibility were written to the scene's {@code nodeTemplates()}, hiding the landing
 *       gear on one aircraft would hide it on all of them - and the symptom (every model in the world
 *       moving together) reads like a networking bug, not like an aliasing bug. So the test drives
 *       two instances of <b>one</b> scene and asserts the second is untouched, and it asserts on the
 *       templates directly.</li>
 * </ol>
 *
 * <p>No GL: {@code ModelDrawList} is pure scene-graph bookkeeping over the parser's output, which is
 * exactly why the walk can be tested without a game. The fixture is {@link TestModelGenerator}'s
 * two-bone cube - node 0 {@code bone_root} with no mesh, node 1 {@code bone_spinner} (its child)
 * carrying the single mesh - which gives both cases the requirement names: hiding the mesh node
 * itself, and hiding an ancestor that has no geometry of its own.
 */
class NodeVisibilityDrawListTest {

    private static final String ROOT = "bone_root";
    private static final String SPINNER = "bone_spinner";

    private static ModelScene scene;
    private static int meshNodeIndex;

    @BeforeAll
    static void parseGeneratedFixture() throws Exception {
        Path output = Path.of("build", "test-fixture", "visibility_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "visibility");

        // Found rather than hard-coded: if the fixture's shape changed, a hard-coded index would
        // make this test assert about the wrong node and still pass.
        meshNodeIndex = -1;
        for (ModelNode node : scene.nodeTemplates()) {
            if (node.meshIndex() >= 0) {
                meshNodeIndex = node.index();
            }
        }
        assertTrue(meshNodeIndex >= 0, "the fixture must attach its mesh to a node");
    }

    @Test
    @DisplayName("hiding the mesh's own node removes its primitives from the draw list")
    void hidingTheMeshNodeRemovesItsPrimitives() {
        ModelInstance instance = new ModelInstance(scene, "hidden");
        ModelDrawList everything = ModelDrawList.build(scene);
        assertEquals(1, everything.drawableCount(),
                "the fixture is one mesh with one primitive: " + everything);

        ModelNodeRef ref = instance.node(SPINNER);
        assertNotNull(ref);
        assertTrue(ref.visible(), "a node with no override is visible");

        ref.setVisible(false);
        assertFalse(ref.visible(), "visible() must report the override immediately");
        assertTrue(ref.isOverridden(), "hiding is an override like any other");

        ModelDrawList hidden = ModelDrawList.build(scene, instance);
        System.out.printf("draw list with '%s' hidden: %s%n", SPINNER, hidden);
        assertEquals(0, hidden.drawableCount(), "a hidden node's primitives must not be submitted");
        assertEquals(0, hidden.indexCount(),
                "and the vertices the renderer sizes its buffer from are gone with them");
    }

    @Test
    @DisplayName("hiding an ancestor removes its descendants' primitives too")
    void hidingAnAncestorRemovesDescendants() {
        ModelInstance instance = new ModelInstance(scene, "hidden-root");
        ModelNodeRef root = instance.node(ROOT);
        ModelNodeRef spinner = instance.node(SPINNER);
        assertNotNull(root);
        assertNotNull(spinner);

        // The fixture puts the mesh on the spinner, which is the root's child - so hiding the root,
        // which carries no geometry at all, must still remove the spinner's cube.
        int meshParent = scene.nodeTemplates()[meshNodeIndex].parentIndex();
        assertTrue(meshParent >= 0, "the mesh node must be a child for this test to cover descendants");
        assertEquals(ROOT, scene.nodeTemplates()[meshParent].name(),
                "the fixture's mesh node must hang off " + ROOT);

        root.setVisible(false);
        assertFalse(root.visible());
        assertFalse(spinner.visible(),
                "visibility is inherited: a child of a hidden node is not drawn either");

        ModelDrawList hidden = ModelDrawList.build(scene, instance);
        System.out.printf("draw list with ancestor '%s' hidden: %s%n", ROOT, hidden);
        assertEquals(0, hidden.drawableCount(),
                "a hidden node's *descendants'* primitives must not be submitted either");

        // Revealing the child while the parent stays hidden must not reveal it: the effective flag
        // folds in the parent, which is the case a naive implementation gets wrong by writing only
        // the node's own flag.
        spinner.setVisible(true);
        assertFalse(spinner.visible(), "a node cannot be revealed out of a hidden subtree");
        assertEquals(0, ModelDrawList.build(scene, instance).drawableCount());

        // ... and revealing the parent brings the child back, because the child's own flag is clear.
        root.setVisible(true);
        assertTrue(root.visible());
        assertTrue(spinner.visible(), "revealing the parent restores the child it was hiding");
        assertEquals(1, ModelDrawList.build(scene, instance).drawableCount());
    }

    @Test
    @DisplayName("clear() drops the visibility override and re-reveals the subtree")
    void clearingRevealsTheNodeAgain() {
        ModelInstance instance = new ModelInstance(scene, "cleared");
        ModelNodeRef root = instance.node(ROOT);
        root.setVisible(false);
        assertEquals(0, ModelDrawList.build(scene, instance).drawableCount());

        root.clear();
        assertFalse(root.isOverridden(), "clear() drops every override, visibility included");
        assertTrue(root.visible(), "and the node is revealed again");
        assertEquals(1, ModelDrawList.build(scene, instance).drawableCount(),
                "its descendants are drawn again too");
    }

    @Test
    @DisplayName("the shared-template trap: the other instance of the same scene still draws them")
    void theOtherInstanceStillDrawsThem() {
        ModelInstance hidden = new ModelInstance(scene, "hidden");
        ModelInstance other = new ModelInstance(scene, "other");
        ModelDrawList baseline = ModelDrawList.build(scene);

        hidden.node(ROOT).setVisible(false);
        assertEquals(0, ModelDrawList.build(scene, hidden).drawableCount(),
                "the first instance hides the whole model");
        assertEquals(baseline.drawableCount(), ModelDrawList.build(scene, other).drawableCount(),
                "the second instance of the *same* ModelScene must still draw everything - an"
                        + " override written to the shared templates would have hidden it too");
        assertEquals(baseline.drawableCount(), ModelDrawList.build(scene).drawableCount(),
                "and the scene-only build, used by the sizing tests, is unaffected");

        // The direct statement of the same fact: the load-time templates carry no overrides at all.
        for (ModelNode template : scene.nodeTemplates()) {
            assertTrue(template.isVisible(), template + " must not carry an instance's override");
            assertFalse(template.hasOverrides(),
                    "a template with an override would leak one instance's state into every other");
        }
        // A freshly instantiated tree is un-overridden too, whatever the instances above did.
        ModelNode[] fresh = scene.instantiate();
        for (ModelNode node : fresh) {
            assertTrue(node.isVisible());
            assertFalse(node.hasOverrides());
        }
    }

    @Test
    @DisplayName("hiding is per instance and layer-clean: two instances, four parts of one scene")
    void twoInstancesDifferOnOneScene() {
        // The same scene, the same clock, two different sets of driven parts - the shape a flight mod
        // actually uses: one aircraft's gear is up while the other's is down.
        ModelInstance up = new ModelInstance(scene, "gear-up");
        ModelInstance down = new ModelInstance(scene, "gear-down");
        up.node(SPINNER).setVisible(false);

        assertEquals(0, ModelDrawList.build(scene, up).drawableCount());
        assertEquals(1, ModelDrawList.build(scene, down).drawableCount());
        assertTrue(down.node(SPINNER).visible(), "the second instance's part is untouched");
        assertFalse(up.node(SPINNER).visible());
    }
}
