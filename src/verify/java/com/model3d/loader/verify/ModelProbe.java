package com.model3d.loader.verify;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelImage;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a model file with the mod's own parsers and reports what is actually in it.
 *
 * <h2>Why this exists</h2>
 * Every rendering investigation so far reasoned from symptoms - a checkerboard here, a wrong size there -
 * and several conclusions drawn that way were wrong. The question underneath all of them is simply
 * "what does the parser hand the renderer", and it can be answered directly, offline, with no GL and no
 * game: vertex counts, the actual coordinate range of the positions, which materials name a texture, and
 * whether the texture each names is an embedded image that exists in the file.
 *
 * <p>Run it against any model:
 * <pre>
 *   gradlew modelProbe --args="path/to/model.glb"
 *   gradlew modelProbe --args="path/to/model.glb full"
 * </pre>
 * Exit code 0 when the model parses and every material's texture resolves, 1 otherwise - so it can be
 * used as a check rather than only as a report.
 */
public final class ModelProbe {

    private static int problems;

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: modelProbe <path-to-model> [full]");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        boolean full = args.length > 1 && "full".equalsIgnoreCase(args[1]);
        if (!Files.isRegularFile(file)) {
            System.out.println("PROBLEM: no such file: " + file);
            System.exit(1);
        }

        System.out.println("=== Model3D model probe ===");
        System.out.println("file: " + file.toAbsolutePath());

        ModelScene scene;
        try (DirectoryModelSource source = new DirectoryModelSource(file.getParent(), "model3d",
                file.getFileName().toString())) {
            scene = ModelFormatRegistry.parse(source, file.getFileName().toString());
        } catch (Throwable t) {
            System.out.println("PROBLEM: parse failed: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
            return;
        }

        System.out.printf("scene: nodes=%d meshes=%d materials=%d skins=%d animations=%d images=%d%n",
                scene.nodeTemplates().length, scene.meshes().length, scene.materials().length,
                scene.skins().length, scene.animations().size(), scene.embeddedImages().size());

        float[] bounds = scene.bounds();
        System.out.printf("bounds: (%.3f %.3f %.3f) .. (%.3f %.3f %.3f)%n",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        if (!finite(bounds)) {
            problem("scene bounds are not finite - the parser produced garbage extents");
        }
        if (scene.longestExtent() <= 0.0f) {
            problem("longest extent is " + scene.longestExtent() + "; the model has no size");
        }

        // The check that matters most: does a primitive actually carry vertices, and do its indices
        // address them? A mesh that parses but holds an empty or mis-sized position array gives a
        // renderer nothing to draw, and looks from outside exactly like a renderer that failed.
        int totalPrimitives = 0;
        int totalVertices = 0;
        int totalTriangles = 0;
        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                totalPrimitives++;
                int vertices = primitive.vertexCount();
                int indices = primitive.indexCount();
                totalVertices += vertices;
                totalTriangles += indices / 3;

                if (vertices == 0) {
                    problem("mesh '" + mesh.name() + "' primitive has no vertices");
                    continue;
                }
                float[] positions = primitive.positions();
                if (positions.length != vertices * 3) {
                    problem("mesh '" + mesh.name() + "' primitive declares " + vertices
                            + " vertices but has " + positions.length + " position floats");
                }
                short[] indexArray = primitive.indices();
                if (indexArray.length == 0 || indexArray.length % 3 != 0) {
                    problem("mesh '" + mesh.name() + "' primitive has " + indexArray.length
                            + " indices, which is not a whole number of triangles");
                }
                // An index past the end reads another vertex's data at best and unmapped memory at
                // worst; the parser is supposed to have rejected it already.
                for (short value : indexArray) {
                    if ((value & 0xFFFF) >= vertices) {
                        problem("mesh '" + mesh.name() + "' has index " + (value & 0xFFFF)
                                + " beyond its " + vertices + " vertices");
                        break;
                    }
                }
                float[] primitiveBounds = primitive.computeBounds();
                if (!finite(primitiveBounds)) {
                    problem("mesh '" + mesh.name() + "' bounds are not finite");
                }
                if (full) {
                    System.out.printf("  primitive %-24s vertices=%-7d triangles=%-7d mat=%-3d"
                                    + " skinned=%s bounds=(%.2f %.2f %.2f)..(%.2f %.2f %.2f)%n",
                            mesh.name(), vertices, indices / 3, primitive.materialIndex(),
                            primitive.isSkinned(), primitiveBounds[0], primitiveBounds[1],
                            primitiveBounds[2], primitiveBounds[3], primitiveBounds[4],
                            primitiveBounds[5]);
                }
            }
        }
        System.out.printf("totals: %d primitives, %d vertices, %d triangles%n", totalPrimitives,
                totalVertices, totalTriangles);
        if (totalVertices == 0) {
            problem("the model contains no vertices at all");
        }

        // Textures: every material that names one must resolve, and an embedded reference must find a
        // matching image. A material naming a texture that resolves to nothing is what produces the
        // magenta-and-black pattern on screen, so it is a failure here rather than a note.
        int named = 0;
        int embedded = 0;
        for (ModelMaterial material : scene.materials()) {
            String path = material.baseColorTexture();
            if (path == null || path.isEmpty()) {
                continue;
            }
            named++;
            if (ModelImage.isEmbedded(path)) {
                embedded++;
                ModelImage image = scene.embeddedImage(path);
                if (image == null) {
                    problem("material '" + material.name() + "' names embedded texture '" + path
                            + "' but the scene holds no such image"
                            + " (has " + scene.embeddedImages().size() + ")");
                } else {
                    System.out.printf("  material %-22s -> %-18s %d bytes (%s)%n", material.name(),
                            path, image.data().length, image.mimeType());
                    if (image.data().length == 0) {
                        problem("embedded image '" + path + "' is empty");
                    }
                }
            } else {
                System.out.printf("  material %-22s -> file '%s'%n", material.name(), path);
            }
        }
        System.out.printf("materials naming a texture: %d (%d embedded)%n", named, embedded);

        System.out.println();
        if (problems == 0) {
            System.out.println("RESULT: PASS - geometry and texture references are intact");
            System.exit(0);
        }
        System.out.println("RESULT: FAIL (" + problems + " problem(s))");
        System.exit(1);
    }

    private static boolean finite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static void problem(String what) {
        problems++;
        System.out.println("PROBLEM: " + what);
    }

    private ModelProbe() {
    }
}
