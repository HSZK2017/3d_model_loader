package com.model3d.loader.format;

import com.model3d.loader.scene.ModelScene;

/**
 * Reads one interchange format into a {@link ModelScene}.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li><b>Side-agnostic</b> - no Minecraft, no OpenGL, no client-only class anywhere in the
 *       call graph, so the same parser runs in JUnit and on a dedicated server.</li>
 *   <li><b>Re-entrant</b> - several entities may ask for models while a resource reload is
 *       running. No mutable static state; every field of a loaded scene is owned by its caller.</li>
 *   <li><b>Fail-closed</b> - throw {@link ModelParseException} with the offending element named,
 *       rather than returning a scene with a silently empty mesh list.</li>
 * </ul>
 */
public interface ModelParser {

    /** Which format this parser handles. */
    ModelFormat format();

    /**
     * Parses {@code source} into a scene.
     *
     * @param source       access to the model's own file and its siblings; not closed by the parser
     * @param requestedName the model name as the user typed it, used only for the scene's name and
     *                     diagnostics - never for resolving files
     * @throws ModelParseException when the file is malformed, or references a file that is absent
     */
    ModelScene parse(ModelSource source, String requestedName) throws ModelParseException;
}
