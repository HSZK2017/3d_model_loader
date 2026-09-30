package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.scene.ModelScene;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code textureInfo.texCoord} degradation: the render path carries one UV set per vertex, so a
 * slot that asks for TEXCOORD_1 or above is sampled as TEXCOORD_0 and must be reported by name.
 *
 * <p>Unlike the OBJ reader, which takes an injectable {@code ParseLog}, the glTF reader reports
 * through {@link Model3D#LOGGER}, so the warning is observed by attaching a log4j appender to that
 * logger for the duration of one test. The capture is filtered by logger name, which keeps a
 * neighbouring logger's warnings out of the "nothing was reported" assertion below.
 *
 * <p>Each test also asserts what was loaded, so silence caused by a fixture that never reached the
 * check cannot pass as "no warning is correct".
 */
class GltfTexCoordTest {

    private final Warnings warnings = new Warnings();

    @BeforeEach
    void captureWarnings() {
        warnings.attach();
    }

    @AfterEach
    void releaseWarnings() {
        warnings.detach();
    }

    private static ModelScene parse(String json) throws Exception {
        return new GltfParser().parse(MemoryModelSource.gltf(json), "test");
    }

    /** Minimal single-mesh, single-node document around {@code members} (accessors, materials, ...). */
    private static String document(String members) {
        return "{\"asset\":{\"version\":\"2.0\"}," + members
                + ",\"nodes\":[{\"mesh\":0}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0}";
    }

    @Test
    @DisplayName("a slot asking for texCoord 1 warns once, naming the material, the slot and the set")
    void warnsForNonZeroTexCoord() throws Exception {
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "images":[{"uri":"textures/albedo.png"}],
                "textures":[{"source":0}],
                "materials":[{"name":"Skin","pbrMetallicRoughness":{
                  "baseColorTexture":{"index":0,"texCoord":1}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """));

        // The warning reports the degradation; it must not also cost the model its texture.
        assertEquals("textures/albedo.png", scene.materials()[0].baseColorTexture());
        assertEquals(1L, warnings.count("TEXCOORD_1"),
                () -> "exactly one warning for the set: " + warnings.messages());
        String warning = warnings.first("TEXCOORD_1");
        assertTrue(warning.contains("materials[0]"), "the warning must name the material: " + warning);
        assertTrue(warning.contains("baseColorTexture"), "the warning must name the slot: " + warning);
        assertTrue(warning.contains("TEXCOORD_0"),
                "the warning must say which set is sampled instead: " + warning);
    }

    @Test
    @DisplayName("texCoord absent and texCoord 0 both mean TEXCOORD_0 and warn nothing")
    void doesNotWarnForAbsentOrZeroTexCoord() throws Exception {
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "images":[{"uri":"textures/albedo.png"}],
                "textures":[{"source":0}],
                "materials":[
                  {"name":"Absent","pbrMetallicRoughness":{"baseColorTexture":{"index":0}}},
                  {"name":"Zero","normalTexture":{"index":0,"texCoord":0}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """));

        // Both slots resolved, so the silence below is not a document that never reached the check.
        assertEquals("textures/albedo.png", scene.materials()[0].baseColorTexture());
        assertEquals("textures/albedo.png", scene.materials()[1].normalTexture());
        assertTrue(warnings.messages().isEmpty(),
                () -> "the default set must warn nothing: " + warnings.messages());
    }

    @Test
    @DisplayName("one warning per distinct set, however many materials ask for it")
    void warnsOncePerSetAcrossMaterials() throws Exception {
        ModelScene scene = parse(document("""
                "accessors":[{"componentType":5126,"type":"VEC3","count":3}],
                "images":[{"uri":"textures/albedo.png"}],
                "textures":[{"source":0}],
                "materials":[
                  {"name":"A","pbrMetallicRoughness":{"baseColorTexture":{"index":0,"texCoord":1}}},
                  {"name":"B","normalTexture":{"index":0,"texCoord":1}},
                  {"name":"C","emissiveTexture":{"index":0,"texCoord":2}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
                """));

        assertEquals("textures/albedo.png", scene.materials()[0].baseColorTexture());
        assertEquals("textures/albedo.png", scene.materials()[1].normalTexture());
        assertEquals("textures/albedo.png", scene.materials()[2].emissiveTexture());
        assertEquals(1L, warnings.count("TEXCOORD_1"),
                () -> "two materials ask for set 1, which is one warning: " + warnings.messages());
        assertEquals(1L, warnings.count("TEXCOORD_2"),
                () -> "a second set is a second warning: " + warnings.messages());
        assertEquals(2, warnings.messages().size(),
                () -> "no other warning is expected: " + warnings.messages());
    }

    /**
     * A log4j appender over {@link Model3D#LOGGER}, attached for the length of one test.
     *
     * <p>The logger is named after the class {@code LogUtils.getLogger()} was called from, so it is
     * located by {@link Model3D#LOGGER}'s own name rather than by re-deriving that convention here.
     */
    private static final class Warnings extends AbstractAppender {

        private static final String NAME = "model3d-texcoord-test";

        private final String loggerName = Model3D.LOGGER.getName();
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private LoggerContext context;
        private LoggerConfig config;

        Warnings() {
            // No layout: only the raw message text is read back, never formatted by this appender.
            super(NAME, null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (loggerName.equals(event.getLoggerName())) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        void attach() {
            context = (LoggerContext) LogManager.getContext(false);
            config = context.getConfiguration().getLoggerConfig(loggerName);
            start();
            config.addAppender(this, Level.WARN, null);
            context.updateLoggers();
        }

        void detach() {
            config.removeAppender(NAME);
            context.updateLoggers();
            stop();
        }

        List<String> messages() {
            return messages;
        }

        long count(String fragment) {
            return messages.stream().filter(message -> message.contains(fragment)).count();
        }

        String first(String fragment) {
            return messages.stream().filter(message -> message.contains(fragment)).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no warning mentioning '" + fragment + "'; captured: " + messages));
        }
    }
}
