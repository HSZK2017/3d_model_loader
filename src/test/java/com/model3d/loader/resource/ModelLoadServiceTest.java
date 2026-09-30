package com.model3d.loader.resource;

import com.model3d.loader.util.Ids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the file selection that makes several loose models in one folder each loadable.
 *
 * <p>The case this exists for: {@code config/3dmodels/su30.glb} and {@code config/3dmodels/f16.glb}
 * sit in the SAME directory, so both resolve to the same folder. "Pick a model file from the folder"
 * would then hand {@code /testmodel loader f16} whichever of the two sorted first - a model that
 * loads under the wrong name, or fails to parse as the other format. The name the user typed has to
 * select the file, and that is what this pins.
 *
 * <p>Exercised through the real method rather than by reimplementing the matching, so a change to the
 * normalisation in {@link ModelLibrary} cannot make the two disagree silently.
 */
class ModelLoadServiceTest {

    private static final List<String> TWO_LOOSE_FILES =
            List.of("f16.glb", "Su-30 Flanker.glb", "model.json", "README.txt");

    @Test
    @DisplayName("a name selects its own file when several loose models share the folder")
    void selectsTheFileTheNameRefersTo() {
        ModelLoadService service = ModelLoadService.INSTANCE;

        assertEquals("Su-30 Flanker.glb",
                service.mainFileFromDroppedFile(ID.apply("su-30_flanker"), TWO_LOOSE_FILES),
                "the normalised model name must select the original file name");
        assertEquals("f16.glb", service.mainFileFromDroppedFile(ID.apply("f16"), TWO_LOOSE_FILES));
    }

    @Test
    @DisplayName("no matching file yields null, so normal auto-detection runs and reports properly")
    void unknownNameFallsBackToAutoDetection() {
        ModelLoadService service = ModelLoadService.INSTANCE;

        // Null is the important answer: it hands control back to chooseMainFile, which produces a
        // diagnostic naming the candidates. Returning a guessed file instead would silently load the
        // wrong model under the name the user typed.
        assertNull(service.mainFileFromDroppedFile(ID.apply("nonexistent"), TWO_LOOSE_FILES));
    }

    @Test
    @DisplayName("a folder holding one model defers to auto-detection rather than selecting")
    void singleFileDefersToAutoDetection() {
        ModelLoadService service = ModelLoadService.INSTANCE;

        // Null, deliberately: with nothing to disambiguate, chooseMainFile already picks the single
        // candidate and reports it. Answering here would duplicate that logic and, worse, would mean a
        // model whose file is named nothing like its folder could only be found if the two happened to
        // match. The null return is the "no opinion" value, not a failure.
        assertNull(service.mainFileFromDroppedFile(ID.apply("hot_added"), List.of("model.glb")),
                "one candidate needs no selection; auto-detection handles it");
    }

    @Test
    @DisplayName("a namespace other than this mod's is never served from the dropped-in folder")
    void otherNamespacesAreNotServedHere() {
        ModelLoadService service = ModelLoadService.INSTANCE;

        // A folder under config/3dmodels/ cannot express a namespace, so somepack:x must be left to
        // the pack path. Returning a file here would make a pack's model silently resolve to a
        // player's local file of the same name.
        assertNull(service.mainFileFromDroppedFile(
                ModelLocation.of(Ids.parse("somepack:su-30_flanker")), TWO_LOOSE_FILES));
    }

    /** This mod's namespace, which is the only one the dropped-in folders serve. */
    private static final java.util.function.Function<String, ModelLocation> ID =
            name -> ModelLocation.of(Ids.parse("model3d:" + name));

    @Test
    @DisplayName("the folder libraries are built once, so the per-tick poll shares their cache")
    void librariesAreBuiltOnce() {
        // ModelLibrary caches its directory signature inside the instance, and checkForChanges runs
        // on every server tick and every client frame. Building a fresh library per call - which is
        // what this used to do - made every one of those calls walk the whole model folder, so the
        // "cached inside ModelLibrary#signature()" the method documents was never true.
        List<ModelLibrary> first = ModelLoadService.libraries();
        List<ModelLibrary> second = ModelLoadService.libraries();
        assertSame(first, second, "the same list must come back, not a fresh one per call");
        if (!first.isEmpty()) {
            // Only meaningful once Forge has resolved the game directories, which a unit test does
            // not do; the assertion above is the part that holds headless.
            assertSame(first.get(0), second.get(0), "the library instance must be reused");
        }
    }
}
