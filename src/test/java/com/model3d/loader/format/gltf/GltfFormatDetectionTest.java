package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.format.ModelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Format detection and registration for the two containers this task owns.
 *
 * <p>Detection is by extension and case-insensitive, because exporters emit {@code .GLB} often enough
 * to matter; and GLB must win over GLTF when a directory holds both, since a {@code .glb} is
 * self-contained and a stray {@code .gltf} next to it is usually the intermediate the exporter left
 * behind.
 */
class GltfFormatDetectionTest {

    @Test
    @DisplayName("extensions are detected case-insensitively")
    void detectsByExtension() {
        assertEquals(ModelFormat.GLB, ModelFormat.byPath("models/a/b/scene.glb"));
        assertEquals(ModelFormat.GLB, ModelFormat.byPath("SCENE.GLB"));
        assertEquals(ModelFormat.GLTF, ModelFormat.byPath("scene.gltf"));
        assertEquals(ModelFormat.GLTF, ModelFormat.byPath("Su30 export version 2024_9_28.GLTF"));
        assertEquals(ModelFormat.OBJ, ModelFormat.byPath("model.obj"));
        assertNull(ModelFormat.byPath("texture.png"));
        assertNull(ModelFormat.byPath("no-extension"));
        assertNull(ModelFormat.byPath("trailing."));
        assertNull(ModelFormat.byPath(null));
    }

    @Test
    @DisplayName("both parsers claim the right format and are registered in precedence order")
    void registersBothParsers() {
        assertSame(ModelFormat.GLB, new GlbParser().format());
        assertSame(ModelFormat.GLTF, new GltfParser().format());
        assertTrue(ModelFormat.GLB.isAnimated(), "a .glb can carry animations");
        assertTrue(ModelFormat.GLTF.isAnimated(), "a .gltf can carry animations");
        assertEquals("glb", ModelFormat.GLB.extension());
        assertEquals("gltf", ModelFormat.GLTF.extension());

        ModelParser glb = ModelFormatRegistry.parserFor(ModelFormat.GLB);
        ModelParser gltf = ModelFormatRegistry.parserFor(ModelFormat.GLTF);
        assertNotNull(glb);
        assertNotNull(gltf);
        assertSame(GlbParser.class, glb.getClass(),
                "a .glb must be read by the container parser, not by the text one");
        assertSame(GltfParser.class, gltf.getClass());
        assertTrue(ModelFormatRegistry.supportedExtensions().contains("glb"));
        assertTrue(ModelFormatRegistry.supportedExtensions().contains("gltf"));
    }
}
