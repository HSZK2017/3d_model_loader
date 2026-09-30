package com.model3d.loader.format.gltf;

import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Locates the licensed model corpus and prints a reproducible inventory of what was parsed.
 *
 * <p>The corpus is third-party material that cannot ship with the repository, so every test that
 * needs it <b>skips</b> (JUnit assumption) when it is absent instead of failing: a red build on a
 * machine without the assets teaches nothing. Resolution order is the documented system property,
 * then a {@code model3d-corpus.properties} on the test classpath, then the repository's own
 * {@code models/} directory found by walking up from the working directory.
 */
final class Corpus {

    private static final String MODELS_DIRECTORY = "models";

    private static boolean resolved;
    private static Path root;

    private Corpus() {
    }

    /** The corpus root, or null when this machine has none. */
    static synchronized Path root() {
        if (!resolved) {
            root = locate();
            resolved = true;
        }
        return root;
    }

    private static Path locate() {
        String property = System.getProperty("model3d.corpus", "");
        if (!property.isBlank()) {
            Path candidate = Path.of(property);
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        try (InputStream in = Corpus.class.getResourceAsStream("/model3d-corpus.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                for (String key : new String[] { "corpus.dir", "model3d.corpus", "dir" }) {
                    String value = properties.getProperty(key);
                    if (value != null && !value.isBlank() && Files.isDirectory(Path.of(value))) {
                        return Path.of(value).toAbsolutePath().normalize();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A malformed corpus pointer must not fail a test run; fall through to the repo lookup.
        }
        Path directory = Path.of("").toAbsolutePath().normalize();
        while (directory != null) {
            Path models = directory.resolve(MODELS_DIRECTORY);
            if (Files.isDirectory(models)) {
                return models;
            }
            directory = directory.getParent();
        }
        return null;
    }

    /** A corpus-relative path, skipping the calling test when the file is not there. */
    static Path require(String relativePath, String what) {
        Path base = root();
        assumeTrue(base != null, "model corpus not available: pass -Dmodel3d.corpus=<dir> with the "
                + "licensed " + MODELS_DIRECTORY + "/ assets to run this test");
        Path path = base.resolve(relativePath);
        assumeTrue(Files.isRegularFile(path), what + " is not part of this corpus: " + path);
        return path;
    }

    /** A {@link DirectoryModelSource} rooted at the model's own directory. */
    static DirectoryModelSource open(Path modelFile) {
        return new DirectoryModelSource(modelFile.getParent(), modelFile);
    }

    static int primitiveCount(ModelScene scene) {
        int count = 0;
        for (ModelMesh mesh : scene.meshes()) {
            count += mesh.primitiveCount();
        }
        return count;
    }

    /** Triangles in the scene as loaded: every primitive is a triangle list after conversion. */
    static int triangleCount(ModelScene scene) {
        int triangles = 0;
        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                triangles += primitive.indexCount() / 3;
            }
        }
        return triangles;
    }

    static int vertexCount(ModelScene scene) {
        int vertices = 0;
        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                vertices += primitive.vertexCount();
            }
        }
        return vertices;
    }

    static int skinnedPrimitiveCount(ModelScene scene) {
        int skinned = 0;
        for (ModelMesh mesh : scene.meshes()) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                if (primitive.isSkinned()) {
                    skinned++;
                }
            }
        }
        return skinned;
    }

    static int materialsWith(ModelScene scene, String slot) {
        int count = 0;
        for (ModelMaterial material : scene.materials()) {
            switch (slot) {
                case "baseColor":
                    count += material.baseColorTexture() != null ? 1 : 0;
                    break;
                case "metallicRoughness":
                    count += material.metallicRoughnessTexture() != null ? 1 : 0;
                    break;
                case "normal":
                    count += material.normalTexture() != null ? 1 : 0;
                    break;
                case "occlusion":
                    count += material.occlusionTexture() != null ? 1 : 0;
                    break;
                case "emissive":
                    count += material.emissiveTexture() != null ? 1 : 0;
                    break;
                default:
                    throw new IllegalArgumentException("unknown slot " + slot);
            }
        }
        return count;
    }

    /** Writes the inventory table to stdout, so the numbers a test asserts on are in the log. */
    static void printInventory(String label, String format, ModelScene scene) {
        float[] bounds = scene.bounds();
        Map<String, Integer> slots = new TreeMap<>();
        slots.put("baseColorTexture", materialsWith(scene, "baseColor"));
        slots.put("metallicRoughnessTexture", materialsWith(scene, "metallicRoughness"));
        slots.put("normalTexture", materialsWith(scene, "normal"));
        slots.put("occlusionTexture", materialsWith(scene, "occlusion"));
        slots.put("emissiveTexture", materialsWith(scene, "emissive"));
        Map<ModelMaterial.AlphaMode, Integer> alphaModes = new EnumMap<>(ModelMaterial.AlphaMode.class);
        int doubleSided = 0;
        for (ModelMaterial material : scene.materials()) {
            alphaModes.merge(material.alphaMode(), 1, Integer::sum);
            if (material.doubleSided()) {
                doubleSided++;
            }
        }
        int joints = 0;
        for (ModelSkin skin : scene.skins()) {
            joints += skin.jointCount();
        }

        System.out.println("--- inventory: " + label + " (" + format + ")");
        System.out.printf(Locale.ROOT, "    nodes=%d meshes=%d primitives=%d vertices=%d triangles=%d "
                        + "materials=%d skins=%d joints=%d animations=%d skinnedPrimitives=%d%n",
                scene.nodeCount(), scene.meshes().length, primitiveCount(scene), vertexCount(scene),
                triangleCount(scene), scene.materials().length, scene.skins().length, joints,
                scene.animations().size(), skinnedPrimitiveCount(scene));
        System.out.println("    material texture slots: " + slots + " alphaModes=" + alphaModes
                + " doubleSided=" + doubleSided);
        System.out.printf(Locale.ROOT, "    bounds=[%.4f, %.4f, %.4f, %.4f, %.4f, %.4f] longestExtent=%.5f%n",
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                scene.longestExtent());
        System.out.println("    rootNodes=" + java.util.Arrays.toString(scene.rootNodes())
                + " sceneName='" + scene.name() + "'");
        List<ModelAnimation> animations = scene.animations();
        if (animations.isEmpty()) {
            System.out.println("    animations: none");
        } else {
            for (ModelAnimation animation : animations) {
                System.out.printf(Locale.ROOT, "    animation '%s': tracks=%d keyframes=%d "
                                + "duration=%.4fs interpolation=%s%n",
                        animation.name(), animation.trackCount(), animation.times().length,
                        animation.duration(), interpolationSummary(animation));
            }
        }
        System.out.flush();
    }

    private static String interpolationSummary(ModelAnimation animation) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ModelAnimation.Track track : animation.tracks()) {
            counts.merge(track.interpolation() + ":" + track.path(), 1, Integer::sum);
        }
        return counts.toString();
    }
}
