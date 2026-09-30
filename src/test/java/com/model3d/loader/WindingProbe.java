package com.model3d.loader;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Measures which way a model's triangles face, to decide whether back-face culling can show it at all.
 *
 * <p>Motivated by a symptom nothing else explained: a model whose geometry uploads, whose textures
 * decode, and whose primitives are all drawn, but of which only the ground shadow is visible. If a
 * model's winding is inverted relative to OpenGL's front-face convention, every triangle is culled and
 * that is exactly what you see. Reasoning about which convention an exporter used is unreliable -
 * counting is not.
 *
 * <p>The method: for a closed surface, the signed volume of a triangle fan about the origin is positive
 * when triangles wind counter-clockwise seen from outside. Summed over a primitive, the sign says which
 * way that primitive faces, so a model that is predominantly negative has inverted winding relative to
 * {@code glFrontFace(GL_CCW)}, which is OpenGL's default and what this mod relies on.
 */
class WindingProbe {

    @Test
    void measure() throws Exception {
        Path file = Path.of("models/sukhoi_su-30_flanker_c.glb");
        if (!Files.isRegularFile(file)) {
            System.out.println("WINDING: corpus model not present, skipping");
            return;
        }
        ModelScene scene;
        try (DirectoryModelSource source = new DirectoryModelSource(file.getParent(), "model3d", file.getFileName().toString())) {
            scene = ModelFormatRegistry.parse(source, file.getFileName().toString());
        }

        int positive = 0;
        int negative = 0;
        int degenerate = 0;
        double totalPositive = 0;
        double totalNegative = 0;

        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                float[] positions = primitive.positions();
                short[] indices = primitive.indices();
                double signedVolume = 0;
                for (int i = 0; i + 2 < indices.length; i += 3) {
                    int a = indices[i] * 3;
                    int b = indices[i + 1] * 3;
                    int c = indices[i + 2] * 3;
                    double ax = positions[a];
                    double ay = positions[a + 1];
                    double az = positions[a + 2];
                    double bx = positions[b];
                    double by = positions[b + 1];
                    double bz = positions[b + 2];
                    double cx = positions[c];
                    double cy = positions[c + 1];
                    double cz = positions[c + 2];
                    // signed volume of the tetrahedron (origin, a, b, c): the scalar triple product / 6
                    signedVolume += (ax * (by * cz - bz * cy)
                            + ay * (bz * cx - bx * cz)
                            + az * (bx * cy - by * cx)) / 6.0;
                }
                if (signedVolume > 0) {
                    positive++;
                    totalPositive += signedVolume;
                } else if (signedVolume < 0) {
                    negative++;
                    totalNegative += -signedVolume;
                } else {
                    degenerate++;
                }
            }
        }

        System.out.printf("WINDING primitives: %d counter-clockwise, %d clockwise, %d degenerate%n",
                positive, negative, degenerate);
        System.out.printf("WINDING volumes: +%.2f / -%.2f%n", totalPositive, totalNegative);
        System.out.println(negative > positive
                ? "WINDING VERDICT: predominantly CLOCKWISE - with the default glFrontFace(GL_CCW),"
                        + " back-face culling culls this model entirely"
                : "WINDING VERDICT: predominantly COUNTER-CLOCKWISE - culling is NOT the explanation");

        float[] bounds = scene.bounds();
        System.out.printf("WINDING bounds=[%.2f %.2f %.2f .. %.2f %.2f %.2f]%n",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }
}
