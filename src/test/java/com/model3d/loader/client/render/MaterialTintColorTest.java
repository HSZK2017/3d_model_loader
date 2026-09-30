package com.model3d.loader.client.render;

import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one decision the live draw makes about colour: whether the RGBA it uploads to
 * {@code u_color} for a material group is the instance's tint or the material's own
 * {@code baseColorFactor}.
 *
 * <h2>Why this is a test at all, and not just four lines of Java</h2>
 * The upload itself is a {@code glUniform4f} and needs a GL context, so the obvious implementation
 * - fetch the colour inline, upload it - can only be verified by reading the code, or by a client
 * run. This project has already been wrong in exactly that spot: {@code u_color} had a valid
 * location and was never uploaded for a while, and every fragment came out transparent black while
 * the draw reported success (see {@code LiveShaderContractTest}'s class comment). Extracting
 * {@link ModelCpuRenderPath#groupColor} makes the decision assertable without a driver; this test
 * asserts it.
 *
 * <p>What it does <b>not</b> cover: that the returned array reaches the driver. That stays the GL
 * harness's job ({@code gradlew meshUploadCheck}) and ultimately the client acceptance run.
 */
class MaterialTintColorTest {

    private static final String MATERIAL = "plain";
    private static final int MATERIAL_INDEX = 0;

    /** The fixture material's authored colour, from {@link TestModelGenerator}. */
    private static final float AUTHORED_RED = 0.85f;

    private static ModelScene scene;
    private static ModelMaterial material;

    @BeforeAll
    static void parseGeneratedFixture() throws Exception {
        Path output = Path.of("build", "test-fixture", "tint_color_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d",
                        output.getFileName().toString()),
                "tint_color");
        material = scene.materials()[MATERIAL_INDEX];
    }

    @Test
    @DisplayName("without a tint the group uploads the material's own baseColorFactor")
    void withoutATintTheMaterialColourStands() {
        ModelInstance instance = new ModelInstance(scene, "plain");
        float[] color = ModelCpuRenderPath.groupColor(material, instance, MATERIAL_INDEX);
        System.out.printf(Locale.ROOT, "no tint -> u_color = (%.3f, %.3f, %.3f, %.3f)%n",
                color[0], color[1], color[2], color[3]);
        assertEquals(AUTHORED_RED, color[0], 1e-6f, "the file's own red");
        assertEquals(1.0f, color[3], 1e-6f, "the file's own alpha");
    }

    @Test
    @DisplayName("a tint replaces the material's colour for that group, live")
    void aTintReplacesTheMaterialColour() {
        ModelInstance instance = new ModelInstance(scene, "tinted");
        instance.setMaterialTint(MATERIAL, 2.0f, 1.75f, 1.5f, 0.75f);

        float[] color = ModelCpuRenderPath.groupColor(material, instance, MATERIAL_INDEX);
        System.out.printf(Locale.ROOT, "tinted -> u_color = (%.3f, %.3f, %.3f, %.3f)%n",
                color[0], color[1], color[2], color[3]);
        assertEquals(2.0f, color[0], 1e-6f, "the tint, not the material's " + AUTHORED_RED);
        assertEquals(1.75f, color[1], 1e-6f);
        assertEquals(1.5f, color[2], 1e-6f);
        assertEquals(0.75f, color[3], 1e-6f, "a tint carries alpha too: a fading plume");

        // The returned array is the instance's live storage rather than a copy, which is what keeps
        // the per-group upload allocation-free - the test asserts the identity deliberately, because
        // a defensive copy here would silently reintroduce one small array per group per frame, the
        // exact kind of garbage this render path was deallocated of once already.
        assertSame(instance.materialTint(MATERIAL_INDEX), color,
                "groupColor must hand back the live tint, not a copy");

        // ... and that liveness is observable: flashing a light by re-setting the tint must change
        // what the next group upload sees.
        instance.setMaterialTint(MATERIAL, 0.1f, 0.2f, 0.3f, 0.4f);
        float[] flashed = ModelCpuRenderPath.groupColor(material, instance, MATERIAL_INDEX);
        assertEquals(0.1f, flashed[0], 1e-6f, "a re-set tint is visible to the very next group");
        assertEquals(0.4f, flashed[3], 1e-6f);

        instance.clearMaterialTint(MATERIAL);
        assertEquals(AUTHORED_RED,
                ModelCpuRenderPath.groupColor(material, instance, MATERIAL_INDEX)[0], 1e-6f,
                "after clearing, the material's own colour is back");
    }

    @Test
    @DisplayName("an index the instance has no tint for falls back to the material")
    void unknownIndexFallsBackToTheMaterial() {
        ModelInstance instance = new ModelInstance(scene, "unknown-index");
        // A material with no tint, an index past the material list, and the negative index a
        // primitive with no material carries. All three reach here from real files, and all three
        // must draw the material's colour rather than nothing.
        assertTrue(ModelCpuRenderPath.groupColor(material, instance, MATERIAL_INDEX)[0] > 0.0f);
        assertEquals(AUTHORED_RED,
                ModelCpuRenderPath.groupColor(material, instance, 999)[0], 1e-6f);
        assertEquals(AUTHORED_RED,
                ModelCpuRenderPath.groupColor(material, instance, -1)[0], 1e-6f);
        // The draw path passes the instance the renderer drew; the null guard exists for a caller
        // with no instance at all, which must not change the colour either.
        assertEquals(AUTHORED_RED, ModelCpuRenderPath.groupColor(material, null, MATERIAL_INDEX)[0],
                1e-6f);
    }
}
