package com.model3d.loader.tools;

import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.resource.DirectoryModelSource;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelImage;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Offline model inspector: parse a model file and print everything a renderer would consume.
 *
 * <h2>Why this exists</h2>
 * Most of this mod cannot be verified without a display. Geometry, node hierarchies, skinning data
 * and animation inventories can be - they are pure data - and this tool is the harness that makes
 * them observable. It runs the <b>same</b> parser classes, through the same
 * {@link ModelFormatRegistry} dispatch and the same {@code ModelSource} implementation, that the
 * mod runs in game; the only difference is that no Minecraft class is on the path.
 *
 * <p>That last property is the point. It means a parser change is verifiable in seconds with
 * {@code gradlew modelInspect --args="models/foo/source/bar.glb"}, against real third-party assets,
 * with no game launch - and, when the numbers look wrong in game, it separates "the file was read
 * wrong" from "the file was drawn wrong", which are otherwise very hard to tell apart.
 *
 * <h2>Usage</h2>
 * <pre>
 *   gradlew modelInspect --args="models/sukhoi_su-30-flanker-c/source/'Su30 export version 2024_9_28.glb'"
 *   gradlew modelInspect --args="path/to/model.gltf full"
 * </pre>
 * A second argument of {@code full} also dumps every node and primitive instead of a summary.
 */
public final class ModelInspectTool {

    private ModelInspectTool() {
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        if (args.length == 0) {
            out.println("usage: modelInspect <model-file> [full]");
            out.println("supported extensions: " + ModelFormatRegistry.supportedExtensions());
            System.exit(2);
            return;
        }

        Path modelFile = Path.of(args[0]).toAbsolutePath().normalize();
        boolean verbose = args.length > 1 && args[1].equalsIgnoreCase("full");

        if (!Files.isRegularFile(modelFile)) {
            out.println("FAIL: no such file: " + modelFile);
            System.exit(2);
            return;
        }

        // The parser resolves sibling buffers and images relative to the model's own directory,
        // so the source root is that directory and the "main path" is just the file name.
        Path directory = modelFile.getParent();
        String fileName = modelFile.getFileName().toString();
        DirectoryModelSource source = new DirectoryModelSource(directory, "inspect", fileName);

        out.println("=== Model3D offline inspection ===");
        out.println("file      : " + modelFile);
        out.println("directory : " + directory);
        out.println();

        ModelScene scene;
        long startNanos = System.nanoTime();
        try {
            scene = ModelFormatRegistry.parse(source, fileName);
        } catch (ModelParseException e) {
            out.println("PARSE FAILED: " + e.getMessage());
            if (e.getCause() != null) {
                out.println("cause       : " + e.getCause());
            }
            System.exit(1);
            return;
        } catch (RuntimeException e) {
            out.println("PARSE CRASHED (a bug, not a bad file): " + e);
            e.printStackTrace(out);
            System.exit(1);
            return;
        }
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;

        printSummary(out, scene, millis);
        if (verbose) {
            printNodes(out, scene);
            printMeshes(out, scene);
        }
        printMaterials(out, scene);
        printEmbeddedImages(out, scene);
        printSkin(out, scene);
        printAnimations(out, scene);

        out.println();
        out.println("=== end ===");
    }

    private static void printSummary(PrintStream out, ModelScene scene, long millis) {
        float[] bounds = scene.bounds();
        int triangles = 0;
        int vertices = 0;
        int primitives = 0;
        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                primitives++;
                triangles += primitive.indexCount() / 3;
                vertices += primitive.vertexCount();
            }
        }
        out.printf(Locale.ROOT, "parsed in    : %d ms%n", millis);
        out.println("scene        : " + scene.name());
        out.println("source       : " + scene.sourceDescription());
        out.printf(Locale.ROOT, "nodes        : %d (roots: %d)%n", scene.nodeCount(),
                scene.rootNodes().length);
        out.printf(Locale.ROOT, "meshes       : %d, primitives: %d%n", scene.meshes().length,
                primitives);
        out.printf(Locale.ROOT, "vertices     : %d, triangles: %d%n", vertices, triangles);
        out.printf(Locale.ROOT, "materials    : %d, skins: %d, animations: %d, embedded images: %d%n",
                scene.materials().length, scene.skins().length, scene.animations().size(),
                scene.embeddedImages().size());
        out.printf(Locale.ROOT, "bounds min   : (%.3f, %.3f, %.3f)%n", bounds[0], bounds[1], bounds[2]);
        out.printf(Locale.ROOT, "bounds max   : (%.3f, %.3f, %.3f)%n", bounds[3], bounds[4], bounds[5]);
        out.printf(Locale.ROOT, "longest axis : %.3f model units%n", scene.longestExtent());
        // The number the renderer actually needs: how much to scale before it is Minecraft-sized.
        out.printf(Locale.ROOT, "scale @ 4 blk: %.6f blocks/unit%n", scene.scaleForTargetSize(4.0f));
        out.println();
    }

    private static void printNodes(PrintStream out, ModelScene scene) {
        out.println("--- nodes ---");
        ModelNode[] nodes = scene.nodeTemplates();
        for (int i = 0; i < nodes.length; i++) {
            ModelNode node = nodes[i];
            StringBuilder line = new StringBuilder();
            line.append(String.format(Locale.ROOT, "  [%3d] ", i));
            int depth = 0;
            for (int parent = node.parentIndex(); parent >= 0; parent = nodes[parent].parentIndex()) {
                depth++;
                if (depth > 64) {
                    line.append("(cycle!)");
                    break;
                }
            }
            line.append("  ".repeat(Math.min(depth, 24)));
            line.append('\'').append(node.name()).append('\'');
            if (node.meshIndex() >= 0) {
                line.append(" mesh=").append(node.meshIndex());
            }
            if (node.skinIndex() >= 0) {
                line.append(" skin=").append(node.skinIndex());
            }
            if (!node.children().isEmpty()) {
                line.append(" children=").append(node.children().size());
            }
            line.append(" restT=").append(node.restTranslation());
            out.println(line);
        }
        out.println();
    }

    private static void printMeshes(PrintStream out, ModelScene scene) {
        out.println("--- meshes ---");
        for (int m = 0; m < scene.meshes().length; m++) {
            ModelMesh mesh = scene.meshes()[m];
            out.printf(Locale.ROOT, "  mesh[%d] '%s' skinned=%s%n", m, mesh.name(), mesh.isSkinned());
            for (int p = 0; p < mesh.primitiveCount(); p++) {
                ModelPrimitive primitive = mesh.primitives()[p];
                out.printf(Locale.ROOT,
                        "    prim[%d] %s verts=%d tris=%d mat=%d normals=%s uvs=%s skinned=%s maxJoint=%d%n",
                        p, primitive.name(), primitive.vertexCount(), primitive.indexCount() / 3,
                        primitive.materialIndex(), primitive.normals() != null,
                        primitive.uvs() != null, primitive.isSkinned(), primitive.maxJointIndex());
            }
        }
        out.println();
    }

    private static void printMaterials(PrintStream out, ModelScene scene) {
        out.println("--- materials ---");
        if (scene.materials().length == 0) {
            out.println("  (none)");
        }
        for (int i = 0; i < scene.materials().length; i++) {
            ModelMaterial material = scene.materials()[i];
            float[] base = material.baseColorFactor();
            out.printf(Locale.ROOT,
                    "  [%2d] '%s' base=(%.3f, %.3f, %.3f, %.3f) metal=%.3f rough=%.3f alpha=%s "
                            + "cutoff=%.2f doubleSided=%s%n",
                    i, material.name(), base[0], base[1], base[2], base[3],
                    material.metallicFactor(), material.roughnessFactor(), material.alphaMode(),
                    material.alphaCutoff(), material.doubleSided());
            printTexture(out, "baseColor", material.baseColorTexture());
            printTexture(out, "metalRough", material.metallicRoughnessTexture());
            printTexture(out, "normal", material.normalTexture());
            printTexture(out, "occlusion", material.occlusionTexture());
            printTexture(out, "emissive", material.emissiveTexture());
        }
        out.println();
    }

    private static void printTexture(PrintStream out, String slot, String path) {
        if (path != null) {
            out.println("        " + slot + ": " + path);
        }
    }

    private static void printEmbeddedImages(PrintStream out, ModelScene scene) {
        out.println("--- embedded images ---");
        if (scene.embeddedImages().isEmpty()) {
            out.println("  (none: every texture is a sibling file)");
        }
        for (ModelImage image : scene.embeddedImages()) {
            out.printf(Locale.ROOT, "  '%s' %s %d bytes  (referred to as '%s')%n",
                    image.name(), image.mimeType().isEmpty() ? "unknown-type" : image.mimeType(),
                    image.byteSize(), image.texturePath());
        }
        out.println();
    }

    private static void printSkin(PrintStream out, ModelScene scene) {
        out.println("--- skins ---");
        if (scene.skins().length == 0) {
            out.println("  (none: this model is rigid, so no skinning will be applied)");
        }
        for (int i = 0; i < scene.skins().length; i++) {
            ModelSkin skin = scene.skins()[i];
            out.printf(Locale.ROOT, "  [%d] '%s' joints=%d skeletonRoot=%d%n", i, skin.name(),
                    skin.jointCount(), skin.skeletonRoot());
        }
        out.println();
    }

    private static void printAnimations(PrintStream out, ModelScene scene) {
        out.println("--- animations ---");
        List<ModelAnimation> animations = scene.animations();
        if (animations.isEmpty()) {
            out.println("  (none: this file carries no animation data)");
            out.println();
            return;
        }
        for (ModelAnimation animation : animations) {
            out.printf(Locale.ROOT, "  '%s' tracks=%d duration=%.4fs keyframes=%d%n",
                    animation.name(), animation.trackCount(), animation.duration(),
                    animation.times().length);
            for (ModelAnimation.Track track : animation.tracks()) {
                out.printf(Locale.ROOT,
                        "      node[%3d] %-11s %-11s keys=%d first=%d values@%d x%d perKey=%d%n",
                        track.targetNode(), track.path(), track.interpolation(),
                        track.keyframeCount(), track.firstKeyframe(), track.valueOffset(),
                        track.valueComponents(), track.componentsPerKey());
            }
        }
        out.println();
    }
}
