package com.model3d.loader.api;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for per-instance material colour overrides - the plume made brighter under afterburner and
 * the anti-collision light flashed, without editing the model.
 *
 * <h2>Why the assertions are about the lookup, not about pixels</h2>
 * The uniform upload itself needs a GL context and an entity, which a plain JUnit test does not have;
 * what <i>can</i> be pinned without one is everything the draw path depends on to decide what to
 * upload: that a tint is remembered under a case-insensitive name, that setting it again replaces it
 * rather than accumulating, that clearing it restores "use the material's own colour", and that an
 * unknown material name is harmless. The last one matters more than it looks: the tint map is keyed by
 * material <b>index</b> so the per-group upload stays allocation-free, and a name that resolves to no
 * index must therefore be dropped rather than stored somewhere that can never be read back -
 * otherwise the API would remember a tint that no draw could ever honour.
 *
 * <p>The fixture's single material is named {@code plain} (see {@link TestModelGenerator}), and its
 * own {@code baseColorFactor} is (0.85, 0.30, 0.25, 1.0) - quoted here so a test that accidentally
 * asserted the material's colour instead of the tint's would be visible.
 */
class ModelMaterialTintTest {

    private static final String MATERIAL = "plain";
    private static final int MATERIAL_INDEX = 0;

    private static ModelScene scene;

    @BeforeAll
    static void parseGeneratedFixture() throws Exception {
        Path output = Path.of("build", "test-fixture", "material_tint_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "material_tint");
    }

    @Test
    @DisplayName("a tint is remembered, replaceable, and cleared back to the material's own colour")
    void tintIsRememberedReplacedAndCleared() {
        ModelInstance instance = new ModelInstance(scene, "tinted");
        assertFalse(instance.hasMaterialTint(MATERIAL), "a fresh instance tints nothing");
        assertNull(instance.materialTint(MATERIAL_INDEX), "no live tint before one is set");

        // A plume is meant to blow out to white, so components above 1.0 are the point rather than a
        // mistake: this is a multiplier on the material's own colour.
        instance.setMaterialTint(MATERIAL, 1.5f, 1.4f, 1.2f, 1.0f);
        assertTrue(instance.hasMaterialTint(MATERIAL));
        assertTrue(instance.hasMaterialTint("PLAIN"),
                "the tint lookup is case-insensitive, like every other name lookup here");
        float[] tint = instance.materialTint(MATERIAL_INDEX);
        assertNotNull(tint, "a set tint must be readable by material index - the draw path's lookup");
        assertEquals(1.5f, tint[0], 0.0f);
        assertEquals(1.4f, tint[1], 0.0f);
        assertEquals(1.2f, tint[2], 0.0f);
        assertEquals(1.0f, tint[3], 0.0f);

        // Replacing is the common case: a light is flashed by setting the tint every frame from a
        // blink phase, and an implementation that queued tints instead of replacing them would show
        // an ever-brighter light.
        instance.setMaterialTint(MATERIAL, 0.25f, 0.5f, 0.75f, 0.5f);
        float[] replaced = instance.materialTint(MATERIAL_INDEX);
        System.out.printf(Locale.ROOT, "replaced tint = (%.3f, %.3f, %.3f, %.3f)%n",
                replaced[0], replaced[1], replaced[2], replaced[3]);
        assertEquals(0.25f, replaced[0], 0.0f, "the new value replaces, it does not accumulate");
        assertEquals(0.75f, replaced[2], 0.0f);
        assertEquals(0.5f, replaced[3], 0.0f);

        instance.clearMaterialTint(MATERIAL);
        assertFalse(instance.hasMaterialTint(MATERIAL), "cleared");
        assertNull(instance.materialTint(MATERIAL_INDEX),
                "a cleared material must fall back to its baseColorFactor");
        // Clearing twice, or clearing what was never set, must not be an error - the same rule
        // ModelNodeRef#clear follows, because an unconditional per-tick reset is the normal caller.
        instance.clearMaterialTint(MATERIAL);
        instance.clearMaterialTint("never_set");
    }

    @Test
    @DisplayName("tints are per instance: one aircraft's afterburner does not light the other's")
    void tintsArePerInstance() {
        ModelInstance lit = new ModelInstance(scene, "lit");
        ModelInstance dark = new ModelInstance(scene, "dark");
        lit.setMaterialTint(MATERIAL, 2.0f, 2.0f, 2.0f, 1.0f);

        assertTrue(lit.hasMaterialTint(MATERIAL));
        assertFalse(dark.hasMaterialTint(MATERIAL),
                "a tint on one instance must not reach another instance of the same scene");
        assertNull(dark.materialTint(MATERIAL_INDEX),
                "and the shared scene's material is untouched: this is per-instance state, not a"
                        + " mutation of the load-time data");
        // The scene's own material still reports its authored colour, which is the second half of
        // "the override lives on the instance": if the tint had been written into the material,
        // every entity using this model would be lit.
        assertEquals(0.85f, scene.materials()[MATERIAL_INDEX].baseColorFactor()[0], 1e-6f);
    }

    @Test
    @DisplayName("an unknown material name is harmless: nothing is remembered, nothing throws")
    void unknownMaterialIsHarmless() {
        ModelInstance instance = new ModelInstance(scene, "unknown");
        instance.setMaterialTint("no_such_material", 1.0f, 0.0f, 0.0f, 1.0f);
        assertFalse(instance.hasMaterialTint("no_such_material"),
                "a name that matches no material of this model has nothing to tint and is dropped");
        assertFalse(instance.hasMaterialTint(MATERIAL),
                "and it must not have landed on some other material's slot");
        instance.clearMaterialTint("no_such_material");
        instance.setMaterialTint(null, 1.0f, 0.0f, 0.0f, 1.0f);
        assertFalse(instance.hasMaterialTint(null), "a null name is a miss, not an exception");

        // Out-of-range indices are the draw path's concern too: an untextured or out-of-range
        // material index reaches it from a malformed file, and the lookup must answer null rather
        // than throw inside the render loop.
        assertNull(instance.materialTint(-1));
        assertNull(instance.materialTint(999));
    }

    @Test
    @DisplayName("swapping the model drops the tints, so they cannot land on another material")
    void swappingTheSceneDropsTints() {
        ModelInstance instance = new ModelInstance(scene, "swapped");
        instance.setMaterialTint(MATERIAL, 2.0f, 2.0f, 2.0f, 1.0f);
        assertTrue(instance.hasMaterialTint(MATERIAL));

        // Re-attaching a model gives a fresh node tree and fresh material indices. Keeping the tint
        // would colour whatever material now occupies index 0 - a nozzle tint appearing on a canopy,
        // which reads as a rendering bug rather than as stale state.
        instance.setScene(scene);
        assertFalse(instance.hasMaterialTint(MATERIAL), "a new tree starts untinted");
        assertNull(instance.materialTint(MATERIAL_INDEX));
    }
}
