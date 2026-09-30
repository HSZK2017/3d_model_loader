package com.model3d.loader.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the {@code textures} alias map of {@code model.json} - the one descriptor feature that was
 * parsed, documented in the README, and then read by nothing at all.
 *
 * <p>The map is keyed by the path as written in the model file, which is the string a renderer holds
 * at the point of resolution; {@link ModelDescriptor#textureAlias(String)} is therefore the only
 * place the lookup can happen, and these tests pin its three answers: a mapped path is replaced, an
 * unmapped path is returned unchanged, and null stays null.
 */
class ModelDescriptorTest {

    /**
     * Built through the real parser rather than a constructor: the alias map is populated from JSON,
     * and a hand-built descriptor would not prove the parser reads the {@code textures} member.
     */
    private static ModelDescriptor parsed(String json) throws Exception {
        return ModelDescriptor.parse(json);
    }

    @Test
    @DisplayName("a written path with an alias resolves to the replacement")
    void mappedPathIsReplaced() throws Exception {
        ModelDescriptor descriptor = parsed("""
                { "textures": { "textures/glass-cockpit_5.jpeg": "textures/glass.png" } }
                """);
        assertEquals("textures/glass.png",
                descriptor.textureAlias("textures/glass-cockpit_5.jpeg"));
    }

    @Test
    @DisplayName("an unmapped path is returned unchanged, so a model keeps its own textures")
    void unmappedPathIsUnchanged() throws Exception {
        ModelDescriptor descriptor = parsed("""
                { "textures": { "a.png": "b.png" } }
                """);
        assertEquals("textures/hull.jpeg", descriptor.textureAlias("textures/hull.jpeg"));
    }

    @Test
    @DisplayName("a material with no texture stays null rather than becoming a lookup")
    void nullStaysNull() throws Exception {
        ModelDescriptor descriptor = parsed("""
                { "textures": { "a.png": "b.png" } }
                """);
        assertNull(descriptor.textureAlias(null));
    }

    @Test
    @DisplayName("a descriptor with no aliases at all is not a special case")
    void noAliasesIsAnIdentity() {
        ModelDescriptor descriptor = ModelDescriptor.defaults();
        assertEquals("textures/hull.jpeg", descriptor.textureAlias("textures/hull.jpeg"),
                "the default descriptor must pass every path through");
    }

    @Test
    @DisplayName("mirror is a per-model fact, and null means the loader's default")
    void mirrorIsPerModel() throws Exception {
        assertNull(parsed("{}").mirror(),
                "a model that says nothing gets null, which the renderer reads as its default");
        assertEquals("", parsed("{\"mirror\": \"none\"}").mirror(),
                "\"none\" is the model explicitly asking for no axes");
        assertEquals("xy", parsed("{\"mirror\": \"xy\"}").mirror());
        // Separator and case noise is tolerated: this key is written by hand in a text file.
        assertEquals("xyz", parsed("{\"mirror\": \"X, Y, Z\"}").mirror());
    }

    @Test
    @DisplayName("a mirror value that names no axis is refused, not silently treated as none")
    void invalidMirrorIsRefused() {
        // The whole point of the key is to make a per-model orientation fault visible. A typo that
        // quietly became "no mirror" would put the model back upside down with nothing in the log.
        assertThrows(ModelDescriptor.ModelDescriptorException.class,
                () -> parsed("{\"mirror\": \"sideways\"}"));
    }
}
