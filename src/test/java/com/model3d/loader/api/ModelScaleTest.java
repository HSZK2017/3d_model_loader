package com.model3d.loader.api;

import com.model3d.loader.resource.ModelDescriptor;
import com.model3d.loader.resource.ModelDescriptor.ModelDescriptorException;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for how big a model becomes, which turned out to be the difference between "it loaded" and
 * "I can see it".
 *
 * <h2>Why this has tests at all</h2>
 * The original default normalized a model's longest axis to 4 blocks. For the Su-30 in this
 * repository - authored 279 units long - that produced an object 4.0 by 0.38 by 0.62 blocks, which
 * reads as a sliver, and from a player's usual downward camera angle as nothing whatsoever. The model
 * uploaded, textured, transformed and drew correctly the entire time; the default size was what made
 * correct work look broken. These tests pin the size that replaced it, and the per-model override that
 * stops the next model from having to change a constant to be visible.
 */
class ModelScaleTest {

    /**
     * A stand-in for a parsed model whose longest axis is {@code longestExtent}.
     *
     * <p>Built through the real constructor with empty geometry rather than a mock: only the bounds are
     * consulted by the scale logic, and using the real type means this test breaks if that ever stops
     * being true instead of passing against a stub forever.
     */
    private static ModelScene sceneWith(float longestExtent) {
        float[] bounds = longestExtent <= 0.0f
                ? new float[] {0, 0, 0, 0, 0, 0}
                : new float[] {0, 0, 0, longestExtent, longestExtent * 0.1f, longestExtent * 0.2f};
        return new ModelScene("test", new int[0], new com.model3d.loader.scene.ModelNode[0],
                new com.model3d.loader.scene.ModelMesh[0],
                new com.model3d.loader.scene.ModelMaterial[0],
                new com.model3d.loader.scene.ModelSkin[0], java.util.List.of(), bounds, "test");
    }

    @Test
    @DisplayName("the default normalizes a model's longest axis to 40 blocks, ten times the old 4")
    void defaultTargetIsFortyBlocks() {
        assertEquals(40.0f, ModelScale.DEFAULT_TARGET_BLOCKS, 0.001f,
                "the default target is what makes a model visible; changing it changes every model");

        // Sanity-check the arithmetic on the model that motivated it: the Su-30's 279.29 units should
        // come out at 40 blocks, not 4.
        ModelScene su30 = sceneWith(279.28827f);
        float blocksPerUnit = su30.scaleForTargetSize(ModelScale.DEFAULT_TARGET_BLOCKS);
        assertEquals(40.0f, 279.28827f * blocksPerUnit, 0.01f,
                "the longest axis must land on the target");
        System.out.printf("su30: %.6f blocks/unit -> %.2f blocks long%n", blocksPerUnit,
                279.28827f * blocksPerUnit);
    }

    @Test
    @DisplayName("a model that declares targetBlocks overrides the default without arithmetic")
    void declaredTargetBlocksWins() throws Exception {
        ModelDescriptor descriptor = ModelDescriptor.parse("{\"targetBlocks\": 6}");
        assertEquals(6.0f, descriptor.targetBlocks(), 0.001f);

        // Zero is the "undeclared" sentinel, not a size: no descriptor means no opinion.
        assertEquals(0.0f, ModelDescriptor.defaults().targetBlocks(), 0.001f);
    }

    @Test
    @DisplayName("a negative targetBlocks is rejected instead of collapsing the model")
    void negativeTargetBlocksIsRejected() {
        ModelDescriptorException error = assertThrows(ModelDescriptorException.class,
                () -> ModelDescriptor.parse("{\"targetBlocks\": -5}"));
        System.out.println("rejected: " + error.getMessage());
        assertTrue(error.getMessage().contains("targetBlocks"),
                "the message must name the key that is wrong: " + error.getMessage());
    }

    @Test
    @DisplayName("a descriptor's scale still multiplies the target, so the two stay composable")
    void scaleMultipliesTheTarget() throws Exception {
        ModelDescriptor doubled = ModelDescriptor.parse("{\"scale\": 2}");
        assertEquals(2.0f, doubled.scale(), 0.001f);

        // 40-block target times 2 gives 80; the pairing is what lets an author say "twice normal"
        // without knowing what normal is.
        assertEquals(80.0f, ModelScale.DEFAULT_TARGET_BLOCKS * doubled.scale(), 0.01f);
    }

    @Test
    @DisplayName("a model with no measurable extent falls back to 1 block per unit rather than dividing by zero")
    void zeroExtentFallsBack() {
        assertEquals(1.0f, ModelScale.forScene(sceneWith(0.0f)), 0.001f);
        assertEquals(1.0f, ModelScale.forScene(null), 0.001f);
    }
}
