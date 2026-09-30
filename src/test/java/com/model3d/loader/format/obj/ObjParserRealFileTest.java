package com.model3d.loader.format.obj;

import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static com.model3d.loader.format.obj.ObjTestSupport.primitives;
import static com.model3d.loader.format.obj.ObjTestSupport.triangleCount;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in: parses every real {@code .obj} under {@code models/}, if any.
 *
 * <p>The hand-written fixtures above define what the parser must do; this test is the reminder that
 * they are hand-written. It is skipped when the repository has no OBJ (its current state - the model
 * folder holds glTF/GLB assets only), and it never fails the suite because a real file is odd: it
 * asserts only the invariants that must hold for any loadable model.
 */
class ObjParserRealFileTest {

    @Test
    void parsesEveryRealObjInTheModelDirectory() throws Exception {
        List<Path> modelFiles = findObjFiles(Paths.get("models"));
        Assumptions.assumeTrue(!modelFiles.isEmpty(),
                "no .obj found under models/ - opt-in real-file test skipped");

        RecordingParseLog log = new RecordingParseLog();
        for (Path modelFile : modelFiles) {
            ModelScene scene = new ObjParser(log)
                    .parse(DirectoryModelSource.of(modelFile), modelFile.getFileName().toString());
            assertTrue(triangleCount(scene) > 0, modelFile + " produced no triangles");
            for (ModelPrimitive primitive : primitives(scene)) {
                assertNotNull(primitive.normals(), modelFile + ": OBJ output must always carry normals");
                for (int index : primitive.indicesInt()) {
                    assertTrue(index >= 0 && index < primitive.vertexCount(),
                            modelFile + ": index " + index + " out of range");
                }
            }
            System.out.println("[obj-test] real file " + modelFile + ": nodes=" + scene.nodeCount()
                    + " meshes=" + scene.meshes().length + " primitives=" + primitives(scene).size()
                    + " triangles=" + triangleCount(scene) + " materials=" + scene.materials().length);
        }
        log.print("parsesEveryRealObjInTheModelDirectory");
    }

    private static List<Path> findObjFiles(Path directory) throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj"))
                    .forEach(found::add);
        }
        return found;
    }
}
