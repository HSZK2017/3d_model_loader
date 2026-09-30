package com.model3d.loader.client.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the contract between the live shader pair and the Java that drives it, without needing a GL
 * context.
 *
 * <h2>The failure this exists for</h2>
 * Every uniform the renderer uses is fetched by name: {@code GL20.glGetUniformLocation(program,
 * "u_color")} returns -1 for a name the shader does not declare, and every setter in this codebase
 * is written as {@code if (loc >= 0) {...}} - so a misspelt or deleted uniform is silently ignored
 * and the model draws wrong. {@code u_color} was that failure in its worst form: the name existed in
 * both files, but nothing ever uploaded it, and the link-time default (0,0,0,0) multiplied every
 * fragment to transparent black while the draw reported success.
 *
 * <p>So this test reads both files as text and asserts that the set of uniform names the Java side
 * fetches is a subset of the set the shaders declare. It cannot prove a value is uploaded (that is
 * the GL harness's job, and ultimately the client acceptance run's), but it catches the drift that
 * no compiler and no single-file test can see: a name that exists on one side only.
 */
class LiveShaderContractTest {

    private static final Path VERTEX = Path.of("src", "main", "resources", "assets", "model3d",
            "shaders", "model_cpu.vsh");
    private static final Path FRAGMENT = Path.of("src", "main", "resources", "assets", "model3d",
            "shaders", "model_cpu.fsh");
    private static final Path RENDER_PATH = Path.of("src", "main", "java", "com", "model3d",
            "loader", "client", "render", "ModelCpuRenderPath.java");

    /** Uniform declarations in GLSL: {@code uniform <type> <name>[;}. */
    private static final Pattern GLSL_UNIFORM =
            Pattern.compile("^\\s*uniform\\s+\\w+\\s+(\\w+)", Pattern.MULTILINE);

    /** Names the Java side asks the driver for: {@code glGetUniformLocation(program, "name")}. */
    private static final Pattern JAVA_LOOKUP =
            Pattern.compile("glGetUniformLocation\\(\\s*\\w+\\s*,\\s*\"([^\"]+)\"");

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing file: " + path.toAbsolutePath()
                + " (this test resolves paths from the project directory)");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("every uniform the render path fetches by name is declared by the live shaders")
    void everyLookedUpUniformIsDeclared() throws IOException {
        Set<String> declared = matches(GLSL_UNIFORM, read(VERTEX));
        declared.addAll(matches(GLSL_UNIFORM, read(FRAGMENT)));
        Set<String> lookedUp = matches(JAVA_LOOKUP, read(RENDER_PATH));

        System.out.println("declared by the shaders: " + declared);
        System.out.println("fetched by the renderer: " + lookedUp);

        assertFalse(lookedUp.isEmpty(), "the render path must fetch uniforms by name; if this is "
                + "empty the test is looking at the wrong file or the wrong pattern");
        for (String name : lookedUp) {
            assertTrue(declared.contains(name),
                    "ModelCpuRenderPath fetches '" + name + "', which the shader pair does not "
                            + "declare - the location would be -1 and the setter would silently do "
                            + "nothing. Declared: " + declared);
        }
    }

    @Test
    @DisplayName("the alpha test and the colour multiplier are both driven per material")
    void perMaterialUniformsArePresent() throws IOException {
        // The two that carried the correctness bugs: a colour multiplier nobody uploaded, and an
        // alpha mode hard-coded to 1 for every material regardless of the file. Where each is
        // declared matters: u_color feeds v_color through minecraft_mix_light in the vertex stage,
        // while the discard tests that consume the alpha pair are in the fragment stage.
        Set<String> vertexDeclared = matches(GLSL_UNIFORM, read(VERTEX));
        Set<String> fragmentDeclared = matches(GLSL_UNIFORM, read(FRAGMENT));
        Set<String> lookedUp = matches(JAVA_LOOKUP, read(RENDER_PATH));

        assertTrue(vertexDeclared.contains("u_color"), "u_color must be declared in the vertex shader");
        assertTrue(fragmentDeclared.contains("u_alphaMode"),
                "u_alphaMode must be declared in the fragment shader, where it is tested");
        assertTrue(fragmentDeclared.contains("u_alphaCutoff"),
                "u_alphaCutoff must be declared in the fragment shader, where the mask test uses it");
        for (String name : new String[] {"u_color", "u_alphaMode", "u_alphaCutoff"}) {
            assertTrue(lookedUp.contains(name), name + " must be fetched by the render path");
        }
    }

    @Test
    @DisplayName("the cullable varying is gone: culling is GL state, not a shader branch")
    void noDeadCullVarying() throws IOException {
        // It was declared in both shaders, set to a constant 0.0 in the vertex shader, and read in a
        // branch that could therefore never fire - while its comment claimed both faces were drawn.
        // Back-face handling now lives in applyMaterialState (doubleSided -> cull on/off).
        String vertex = read(VERTEX);
        String fragment = read(FRAGMENT);
        assertFalse(vertex.contains("v_cullable"), "model_cpu.vsh still declares v_cullable");
        assertFalse(fragment.contains("v_cullable"), "model_cpu.fsh still reads v_cullable");
    }
}
