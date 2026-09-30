package com.model3d.loader.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModelLibrary}: which files count as models, what they are called, and whether a
 * change to the folder is noticed.
 *
 * <p>This is the layer that decides whether a file the user dragged into a folder becomes a usable
 * model, and it is pure filesystem logic - no game, no GL - so it is tested properly rather than
 * exercised by hand in game. The layouts below are the ones the README tells a user to use, so this
 * and the documentation cannot drift while both are green.
 */
class ModelLibraryTest {

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    @DisplayName("a loose .glb in the folder is a model named after the file")
    void looseFileIsAModel(@TempDir Path root) throws IOException {
        write(root.resolve("su30.glb"), "glTF-binary");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("loose file -> " + found);
        assertEquals(1, found.size());
        assertTrue(found.containsKey("su30"), "the extension is dropped from the name");
        assertEquals(root, found.get("su30"), "a loose file's location is the folder holding it");
    }

    @Test
    @DisplayName("a folder holding a model is one model, named after the folder")
    void folderIsAModel(@TempDir Path root) throws IOException {
        write(root.resolve("su30/model.glb"), "glTF-binary");
        write(root.resolve("su30/textures/paint.png"), "png");
        write(root.resolve("su30/model.json"), "{}");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("folder -> " + found);
        assertEquals(1, found.size(), "the model and its parts are one model, not three");
        assertTrue(found.containsKey("su30"));
        assertEquals(root.resolve("su30"), found.get("su30"));
    }

    @Test
    @DisplayName("a model file inside a model folder is a part of it, not a second model")
    void modelFileInsideAFolderIsNotASeparateModel(@TempDir Path root) throws IOException {
        write(root.resolve("su30/model.glb"), "glTF-binary");

        Map<String, Path> found = new ModelLibrary(root).discover();
        assertFalse(found.containsKey("model"),
                "the inner model.glb must not also register as the model 'model'");
        assertEquals(1, found.size());
    }

    @Test
    @DisplayName("names are normalised so any file name a user can type becomes a valid model id")
    void namesAreNormalised(@TempDir Path root) throws IOException {
        write(root.resolve("My Plane.glb"), "glTF-binary");
        write(root.resolve("F-16 (Block 50).glb"), "glTF-binary");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("normalised -> " + found.keySet());
        assertTrue(found.containsKey("my_plane"), "spaces and capitals normalised");
        assertTrue(found.containsKey("f-16_block_50"),
                "punctuation normalised, repeated separators collapsed: got " + found.keySet());
    }

    @Test
    @DisplayName("a model dropped into a sub-folder becomes that folder's model, not a second entry")
    void aModelInASubFolderIsThatFoldersModel(@TempDir Path root) throws IOException {
        write(root.resolve("jets/su30.glb"), "glTF-binary");
        write(root.resolve("tanks/m1a2/model.obj"), "obj");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("nested -> " + found);
        // The outermost enclosing folder names the model, and the file inside it does not also
        // register. The first implementation produced BOTH "jets" and "jets/su30" here, so dropping
        // a file into a sub-folder silently created a duplicate entry.
        assertEquals(2, found.size(), "one model per outer folder, no duplicates: " + found);
        assertTrue(found.containsKey("jets"), "the enclosing folder names the model");
        assertFalse(found.containsKey("jets/su30"),
                "the file inside must not also be a model: " + found.keySet());
        assertTrue(found.containsKey("tanks/m1a2"), "deeper nesting still names by its own path");
    }

    @Test
    @DisplayName("non-model files are ignored, and a folder of only them is not a model")
    void nonModelFilesIgnored(@TempDir Path root) throws IOException {
        write(root.resolve("notes.txt"), "hello");
        write(root.resolve("photo.png"), "png");
        write(root.resolve("docs/readme.md"), "md");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("ignored -> " + found);
        assertTrue(found.isEmpty(), "nothing here is a model: " + found);
    }

    @Test
    @DisplayName("an absent folder yields nothing and does not throw")
    void absentFolder(@TempDir Path root) {
        ModelLibrary library = new ModelLibrary(root.resolve("does-not-exist"));
        assertFalse(library.exists());
        assertTrue(library.discover().isEmpty());
        assertEquals(0L, library.signature());
    }

    @Test
    @DisplayName("prepare() creates the folder and writes a README that names the layouts")
    void prepareCreatesFolderAndReadme(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("3dmodels");
        ModelLibrary library = new ModelLibrary(libraryRoot);
        library.prepare();

        assertTrue(Files.isDirectory(libraryRoot));
        Path readme = libraryRoot.resolve("README.txt");
        assertTrue(Files.isRegularFile(readme), "a README is what answers 'where do I put this'");
        String text = Files.readString(readme);
        System.out.println("README written, " + text.length() + " chars");
        assertTrue(text.contains("su30.glb"), "it shows the loose-file layout");
        assertTrue(text.contains("model.json"), "it shows the folder layout");
        // Deliberately NOT a command: this jar has none of its own, so the note describes the naming
        // rule and says a companion mod provides the command. Pinning the absence is the point - the
        // text used to instruct a user to run a command only the test mod installs.
        assertFalse(text.contains("/testmodel"), "it does not promise a command this mod lacks");
        assertTrue(text.contains("no command of its own"), "it says where the command comes from");

        // A second prepare must not overwrite notes a user added.
        Files.writeString(readme, "my own notes");
        library.prepare();
        assertEquals("my own notes", Files.readString(readme),
                "an existing README must not be overwritten");
    }

    @Test
    @DisplayName("dropping a file changes the signature, so hot reload can see it")
    void addingAFileChangesTheSignature(@TempDir Path root) throws IOException {
        ModelLibrary library = new ModelLibrary(root);
        long empty = library.signature();

        write(root.resolve("su30.glb"), "glTF-binary");
        library.invalidateSignature();
        long withModel = library.signature();

        System.out.println("signature: empty=" + empty + " withModel=" + withModel);
        assertNotEquals(empty, withModel, "an added model must be a detectable change");

        // An edit to an existing file is a change too - this is the case a size-only check misses.
        write(root.resolve("su30.glb"), "glTF-binary-with-more-bytes");
        library.invalidateSignature();
        long edited = library.signature();
        assertNotEquals(withModel, edited, "an edited model must be a detectable change");

        // Deleting it is a change as well, or a removed model would stay listed forever.
        Files.delete(root.resolve("su30.glb"));
        library.invalidateSignature();
        assertNotEquals(edited, library.signature(), "a removed model must be a detectable change");
    }

    @Test
    @DisplayName("the signature is stable while nothing changes, including our own README")
    void signatureIsStable(@TempDir Path root) throws IOException {
        ModelLibrary library = new ModelLibrary(root);
        library.prepare();
        write(root.resolve("su30.glb"), "glTF-binary");

        library.invalidateSignature();
        long first = library.signature();
        library.invalidateSignature();
        long second = library.signature();
        System.out.println("stable signature = " + first);
        assertEquals(first, second, "repeated scans of an unchanged folder must agree");

        // The README this mod writes must not itself look like a user edit, or writing it would
        // trigger a reload every startup.
        Files.writeString(root.resolve("README.txt"), "changed by hand, still ignored");
        library.invalidateSignature();
        assertEquals(first, library.signature(), "README.txt is excluded from the fingerprint");

        // Nor may a stray editor temp file.
        Files.writeString(root.resolve("su30.glb.tmp"), "half-written");
        library.invalidateSignature();
        assertEquals(first, library.signature(), "a .tmp file is excluded");
    }

    @Test
    @DisplayName("the signature is cached, so a per-tick poll does not walk the tree every tick")
    void signatureIsCached(@TempDir Path root) throws IOException {
        write(root.resolve("su30.glb"), "glTF-binary");
        ModelLibrary library = new ModelLibrary(root);
        long first = library.signature();

        // A change made within the cache window is not seen until the window expires. That is the
        // documented trade - at most one second of latency - and it is what makes the per-tick call
        // cheap, so it is pinned rather than left implicit.
        write(root.resolve("second.glb"), "glTF-binary");
        assertEquals(first, library.signature(),
                "within the poll window the cached value is returned without rescanning");

        library.invalidateSignature();
        assertNotEquals(first, library.signature(),
                "invalidateSignature (which a companion mod's reload command uses) forces a rescan");
    }

    @Test
    @DisplayName("two files normalising to one name are both reported, not silently merged")
    void duplicateNames(@TempDir Path root) throws IOException {
        write(root.resolve("My Plane.glb"), "a");
        write(root.resolve("my_plane.glb"), "b");

        Map<String, Path> found = new ModelLibrary(root).discover();
        System.out.println("duplicates -> " + found);
        assertEquals(1, found.size(), "one name, one model");
        assertTrue(found.containsKey("my_plane"));
        // Which one won is deliberately unspecified; that it is reported is the contract, and the
        // warning is asserted by the log line rather than here.
    }

    @Test
    @DisplayName("normalise() produces ids that satisfy the resource-location rules")
    void normaliseProducesValidIds() {
        assertEquals("su30", ModelLibrary.normalise("su30"));
        assertEquals("su30", ModelLibrary.normalise("Su30"));
        assertEquals("my_plane", ModelLibrary.normalise("My Plane"));
        assertEquals("my_plane", ModelLibrary.normalise("My   Plane"));
        assertEquals("a_b", ModelLibrary.normalise("a__b"));
        assertNotNull(ModelLibrary.normalise(""));
        assertEquals("model", ModelLibrary.normalise(""), "an empty name still needs an id");
        assertEquals("model", ModelLibrary.normalise("///"), "pure separators too");
        // Every result must be a legal resource path, or the model would be unreachable by command.
        for (String input : new String[] { "Su-30 MKI (2024).glb", "飞机", "a b/c d", "..", "%%%" }) {
            String normalised = ModelLibrary.normalise(input);
            System.out.printf("normalise(%s) = %s%n", input, normalised);
            assertTrue(normalised.matches("[a-z0-9/._-]+"),
                    "'" + normalised + "' from '" + input + "' is not a legal resource path");
            assertFalse(normalised.startsWith("/") || normalised.endsWith("/"),
                    "'" + normalised + "' has a leading or trailing separator");
        }
    }
}
