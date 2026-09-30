package com.model3d.loader.api;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.resource.ModelDescriptor;
import com.model3d.loader.resource.ModelDescriptor.ModelDescriptorException;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collision box: derived from a real model's geometry, or declared in {@code model.json}.
 *
 * <p>Runs the real generator and the real parser, because the numbers this class produces are the
 * numbers an entity's collision box will use - a hand-built scene would prove the arithmetic and
 * nothing about the shape it lands on.
 *
 * <p>The fixture is a cube of known size, which is what makes exact assertions possible: the automatic
 * box must come out at the loader's own normalization target on its longest axis, and a declared box
 * must come out exactly as written.
 */
class ModelHitboxTest {

    private static ModelScene scene;

    @BeforeAll
    static void generateFixture() throws IOException, Exception {
        Path output = Path.of("build", "test-fixture", "animated_test.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        DirectoryModelSource source = new DirectoryModelSource(
                output.getParent(), "model3d", output.getFileName().toString());
        scene = ModelFormatRegistry.parse(source, "animated_test");
    }

    private static ModelHandle handle(ModelDescriptor descriptor) {
        return new ModelHandle("animated_test", scene, "test fixture", descriptor);
    }

    @Test
    @DisplayName("the automatic box is the model's geometry brought to the loader's own target size")
    void automaticBoxFollowsNormalization() {
        ModelHandle handle = handle(ModelDescriptor.defaults());
        float blocksPerUnit = ModelScale.forHandle(handle);
        ModelHitbox.Box box = ModelHitbox.of(handle);

        System.out.println("fixture bounds=" + ModelBounds.describe(handle.bounds())
                + " scale=" + blocksPerUnit + " blocks/unit -> " + box);

        assertFalse(box.isEmpty(), "a model with geometry gets a box");
        // The fixture is a cube, so all three axes must agree. If the loader ever normalises one axis
        // differently from another, this is where it shows.
        assertEquals(box.sizeX(), box.sizeY(), 1e-4, "cube: X and Y agree");
        assertEquals(box.sizeY(), box.sizeZ(), 1e-4, "cube: Y and Z agree");
        // The documented normalization: longest axis to DEFAULT_TARGET_BLOCKS, times the descriptor's
        // multiplier (1.0 here). Pinned because a hitbox that silently changes size with a scale tweak
        // is the failure this test exists to catch.
        assertEquals(ModelScale.DEFAULT_TARGET_BLOCKS, box.longestExtent(), 1e-3,
                "longest axis is the loader's target size in blocks");

        // Passing 0 means "not set", the same convention the carrier uses, and must equal the derived
        // scale rather than collapsing the box.
        assertEquals(box.sizeX(), ModelHitbox.of(handle, 0.0f).sizeX(), 1e-6,
                "0 blocks/unit means 'use the loader's scale'");
    }

    @Test
    @DisplayName("the carrier's own scale changes the box, because it changed what is on screen")
    void boxFollowsTheCarrierScale() {
        ModelHandle handle = handle(ModelDescriptor.defaults());
        float base = ModelScale.forHandle(handle);

        ModelHitbox.Box doubled = ModelHitbox.of(handle, base * 2.0f);
        ModelHitbox.Box normal = ModelHitbox.of(handle, base);

        assertEquals(normal.sizeX() * 2.0, doubled.sizeX(), 1e-4,
                "twice the blocks per unit is twice the box");
    }

    @Test
    @DisplayName("a declared box is used exactly as written, and is not scaled")
    void declaredBoxWinsAndIsNotScaled() throws Exception {
        ModelDescriptor descriptor = ModelDescriptor.parse(
                "{\"hitbox\": [12, 4, 30], \"hitboxOffset\": [0, 2, 0]}");
        ModelHandle handle = handle(descriptor);

        ModelHitbox.Box box = ModelHitbox.of(handle, 40.0f);

        System.out.println("declared -> " + box);
        assertEquals(12.0, box.sizeX(), 1e-6, "declared X");
        assertEquals(4.0, box.sizeY(), 1e-6, "declared Y");
        assertEquals(30.0, box.sizeZ(), 1e-6, "declared Z");
        // The offset is the author's statement about where the box sits: 2 blocks up, centred otherwise.
        assertEquals(-6.0, box.minX(), 1e-6, "centred in X");
        assertEquals(0.0, box.minY(), 1e-6, "offset in Y");
        assertEquals(4.0, box.maxY(), 1e-6, "offset in Y, top");
        assertEquals(-15.0, box.minZ(), 1e-6, "centred in Z");
        // ...and the scale passed in must not have touched it: that is the whole point of declaring it.
        assertEquals(4.0, ModelHitbox.of(handle, 400.0f).sizeY(), 1e-6,
                "a declared box ignores the scale, however wrong the derived one would be");
    }

    @Test
    @DisplayName("the vanilla-shaped dimensions take the wider horizontal axis and clamp to a usable size")
    void dimensionsAreUsableByVanilla() {
        ModelHitbox.Box aircraft = new ModelHitbox.Box(-15, 0, -20, 15, 4, 20);
        // 30 wide, 40 long: the entity box's footprint is square, so the choice is which axis to keep.
        // The wider one, deliberately: an entity box narrower than the model is the bug this class
        // exists to prevent ("the collision box cannot be tiny on a model that big"), and oversizing is
        // visible and fixable, while walking through a wing is neither. A caller that wants the real
        // shape uses aabb() instead - see the javadoc on dimensions().
        assertEquals(40.0f, aircraft.dimensions().width, 1e-4,
                "width is the wider horizontal axis, so players cannot walk through the wings");
        assertEquals(4.0f, aircraft.dimensions().height, 1e-4, "height");

        // A degenerate box must not become a zero-sized entity box: nothing collides with it.
        ModelHitbox.Box flat = new ModelHitbox.Box(0, 0, 0, 0, 0, 0);
        assertTrue(flat.dimensions().width > 0.0f, "width is clamped above zero");
        assertTrue(flat.dimensions().height > 0.0f, "height is clamped above zero");
        assertEquals(1.0f / 16.0f, flat.dimensions().width, 1e-6, "clamped to the smallest size the game uses");
    }

    @Test
    @DisplayName("the box is placed from the entity's own origin, not from the world origin or its centre")
    void aabbIsRelativeToTheEntity() {
        // An asymmetric box, the way a nose-pivot model comes out: 6 blocks behind, 2 in front.
        ModelHitbox.Box box = new ModelHitbox.Box(-6, 0, -1, 2, 3, 1);
        var aabb = box.aabb(100.0, 64.0, -250.0);

        assertEquals(94.0, aabb.minX, 1e-6, "min X");
        assertEquals(102.0, aabb.maxX, 1e-6, "max X");
        assertEquals(64.0, aabb.minY, 1e-6, "the entity's Y is the box's floor");
        assertEquals(67.0, aabb.maxY, 1e-6, "max Y");
        assertEquals(-251.0, aabb.minZ, 1e-6, "min Z");
        assertEquals(-249.0, aabb.maxZ, 1e-6, "max Z");
    }

    @Test
    @DisplayName("a model with no geometry and no declaration yields an empty box, not a guess")
    void emptyModelYieldsAnEmptyBox() {
        assertTrue(ModelHitbox.of(null).isEmpty(), "null handle");
        assertTrue(ModelHitbox.EMPTY.isEmpty(), "the constant");
        assertEquals(0.0, ModelHitbox.EMPTY.longestExtent(), 1e-6, "no extent");
        // An empty box is the sentinel a caller checks before touching an entity's collision box: the
        // alternative is a zero-sized box, which is an entity that falls through the world.
        assertTrue(ModelHitbox.of(null).dimensions().width > 0.0f,
                "even the empty box produces a usable vanilla dimension rather than a degenerate one");
    }

    @Test
    @DisplayName("a malformed declared box is rejected at parse time, not silently zero")
    void malformedDeclarationsAreRejected() {
        // Fail closed for the same reason the scale does: a zero-size hitbox is an entity you walk
        // through, which reads as "the model did not load" and sends the author looking in the wrong
        // place. Every one of these must be an error rather than a fallback.
        assertThrows(ModelDescriptorException.class, () -> ModelDescriptor.parse("{\"hitbox\": [0, 4, 30]}"));
        assertThrows(ModelDescriptorException.class, () -> ModelDescriptor.parse("{\"hitbox\": [12, -1, 30]}"));
        assertThrows(ModelDescriptorException.class, () -> ModelDescriptor.parse("{\"hitbox\": [12, 4]}"));
        assertThrows(ModelDescriptorException.class, () -> ModelDescriptor.parse("{\"hitbox\": \"12 4 30\"}"));
        assertThrows(ModelDescriptorException.class, () -> ModelDescriptor.parse("{\"hitboxOffset\": [0, 2]}"));
    }

    @Test
    @DisplayName("no declaration means 'derive it', which is distinguishable from a declared box")
    void undeclaredHitboxIsDerived() throws Exception {
        ModelDescriptor descriptor = ModelDescriptor.parse("{\"targetBlocks\": 20}");
        assertNull(descriptor.hitbox(), "no declared box");
        assertEquals(0.0f, descriptor.hitboxOffset()[1], 1e-6, "no declared offset");

        ModelHitbox.Box derived = ModelHitbox.of(handle(descriptor));
        assertEquals(20.0, derived.longestExtent(), 1e-3,
                "derived from the descriptor's own target size");
        assertNotNull(ModelDescriptor.parse("{\"hitbox\": [1, 1, 1]}").hitbox(), "and a declared one is not null");
    }
}
