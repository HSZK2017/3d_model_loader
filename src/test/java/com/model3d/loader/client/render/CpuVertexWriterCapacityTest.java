package com.model3d.loader.client.render;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.tools.TestModelGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.BufferOverflowException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the arithmetic that sizes the CPU render path's vertex buffer.
 *
 * <h2>Why this exists</h2>
 * The buffer was sized from {@code drawList.vertexCount() * 3} - unique vertices, times three -
 * while the fill loop writes <b>one vertex per index</b>. Those agree only for a triangle soup with
 * no shared vertices. The mod's own fixture is a cube: 8 vertices, 12 triangles, <b>36 indices</b>,
 * so the capacity was 24 and the 25th write threw {@code BufferOverflowException} out of the entity
 * renderer and killed the client:
 *
 * <pre>
 *   CpuVertexWriter.vertex(CpuVertexWriter.java:104)
 *   ModelCpuRenderPath.fill(ModelCpuRenderPath.java:248)
 *   VanillaModelRenderer.draw(VanillaModelRenderer.java:148)
 *   the entity renderer's draw call (now in the companion test mod: RenderTestModelEntity.render)
 *   Description: Rendering entity in world
 * </pre>
 *
 * <p>The case that hid it for so long is the one in {@code models/}: a 24 244-vertex aircraft with
 * 64 755 indices fits in {@code 24 244 * 3}. Nothing but counting the writes against the capacity
 * shows the difference, which is why this test needs no GL context - it only does arithmetic over a
 * real parsed model.
 */
class CpuVertexWriterCapacityTest {

    /**
     * The fixture's own numbers, from {@code TestModelGenerator}: one primitive, 8 vertices,
     * 12 triangles. Written out rather than derived from the file so a change to either the fixture
     * or the arithmetic is visible here instead of cancelling out.
     */
    private static final int FIXTURE_VERTICES = 8;
    private static final int FIXTURE_INDICES = 36;

    private static ModelDrawList drawList;

    @BeforeAll
    static void parseShippedFixture() throws Exception {
        // Generated, not read from src/main/resources: this test is about the arithmetic the
        // renderer derives from what the parser produced, and a stale committed copy could differ.
        Path output = Path.of("build", "test-fixture", "capacity_fixture.glb").toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.write(output, TestModelGenerator.build());
        ModelScene scene = ModelFormatRegistry.parse(
                new DirectoryModelSource(output.getParent(), "model3d", output.getFileName().toString()),
                "animated_test");
        drawList = ModelDrawList.build(scene);
    }

    /** How many vertices {@code ModelCpuRenderPath.fill} writes: one per index of every drawable. */
    private static int writtenVertices() {
        int writes = 0;
        for (ModelDrawList.Drawable drawable : drawList.drawables()) {
            writes += drawable.primitive().indexCount();
        }
        return writes;
    }

    @Test
    @DisplayName("the fixture is a case where one vertex per index exceeds unique vertices * 3")
    void fixtureIsTheDiscriminatingCase() {
        System.out.println("drawList = " + drawList + ", writes = " + writtenVertices());
        assertEquals(FIXTURE_VERTICES, drawList.vertexCount(), "fixture vertex count");
        assertEquals(FIXTURE_INDICES, writtenVertices(), "fixture index count");
        assertTrue(writtenVertices() > drawList.vertexCount() * 3,
                "the shipped fixture must be a case the old sizing gets wrong: "
                        + writtenVertices() + " writes vs " + (drawList.vertexCount() * 3) + " slots");
    }

    @Test
    @DisplayName("a writer sized for every written vertex holds the frame")
    void capacityMustHoldEveryWrittenVertex() {
        // The renderer's own rule, called rather than copied: ModelCpuRenderPath.draw passes this
        // to ModelCpuMesh.create. Pre-fix it returned uniqueVertices * 3 and the assertion below
        // failed; post-fix it returns the index count and passes.
        int capacity = ModelCpuRenderPath.bufferCapacityFor(drawList);
        assertTrue(capacity >= writtenVertices(),
                "capacity " + capacity + " cannot hold the " + writtenVertices()
                        + " vertices fill() writes for this model");

        CpuVertexWriter writer = new CpuVertexWriter(capacity);
        try {
            writeAll(writer);
            assertEquals(writtenVertices(), writer.writtenVertices());
        } finally {
            writer.free();
        }
    }

    @Test
    @DisplayName("control: the old unique-vertices * 3 sizing really does overflow here")
    void oldSizingOverflows() {
        // The negative control for the test above. If this stopped throwing, the case would no
        // longer discriminate and the guard would have become decoration.
        CpuVertexWriter writer = new CpuVertexWriter(drawList.vertexCount() * 3);
        try {
            assertThrows(BufferOverflowException.class, () -> writeAll(writer),
                    "unique vertices * 3 cannot hold one vertex per index - this is the exception "
                            + "that reached the entity renderer and crashed the client");
        } finally {
            writer.free();
        }
    }

    @Test
    @DisplayName("the draw list reports the index count the renderer now sizes from")
    void reportsTheIndexCount() {
        assertEquals(writtenVertices(), drawList.indexCount(),
                "indexCount() must be exactly what fill() writes, or the sizing is a guess again");
    }

    private static void writeAll(CpuVertexWriter writer) {
        for (int i = 0; i < writtenVertices(); i++) {
            writer.vertex(i, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        }
    }
}
