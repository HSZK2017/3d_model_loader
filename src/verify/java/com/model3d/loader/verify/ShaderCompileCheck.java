// Verification harness for the client renderer - NOT part of the mod jar, and outside src/main so it
// never reaches the compiler for a normal build.
//
// Run with:  gradlew.bat checkExtras --console=plain   (or just shaderCheck)
//
// It creates an invisible GLFW window, takes a real OpenGL context from the driver, and compiles the
// shader pair the live render path actually loads - assets/model3d/shaders/model_cpu.vsh and .fsh,
// read from the same classpath resource names ModelCpuRenderPath#read uses. That is the only way to
// know those shaders compile on this machine: a syntax or version error is otherwise invisible until
// someone runs the game and reports "the model does not appear".
//
// It also asserts that every uniform name the Java side fetches by name is active in the linked
// program. A renamed or optimised-away uniform comes back as location -1 and the Java setter
// silently does nothing - which is precisely how u_color stayed unset while every draw reported
// success. The attribute slots are pinned with glBindAttribLocation before linking, exactly as
// ModelCpuRenderPath does, so a driver that would assign different locations is still tested.
package com.model3d.loader.verify;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ShaderCompileCheck {

    /** The live pair, by the classpath names the renderer loads. */
    private static final String VERTEX_RESOURCE = "/assets/model3d/shaders/model_cpu.vsh";
    private static final String FRAGMENT_RESOURCE = "/assets/model3d/shaders/model_cpu.fsh";

    /**
     * Attribute slots pinned before linking, index = slot. The same three names and slots as
     * {@code ModelCpuRenderPath.ensureCompiled} and the pointers in {@code ModelCpuMesh}.
     */
    private static final String[] ATTRIBUTES = { "a_position", "a_uv", "a_normal" };

    /**
     * Every uniform {@code ModelCpuRenderPath} looks up by name. Kept here rather than derived,
     * because the point is to notice when the two lists disagree; {@code LiveShaderContractTest}
     * additionally checks the Java side's names against the shader sources without needing a driver.
     */
    private static final String[] UNIFORMS = {
            "u_proj", "u_mv", "u_ivr", "u_color", "u_packedOverlay", "u_fogStart", "u_fogEnd",
            "u_fogColor", "u_fogShape", "u_light0", "u_light1", "u_alphaMode", "u_alphaCutoff",
            "u_packedLight", "Sampler0", "Sampler1", "Sampler2" };

    private static int failures;

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) {
            System.out.println("SKIP: glfwInit failed - no GL context can be created here. "
                    + "This is NOT a pass; the shaders remain unverified.");
            System.exit(2);
        }
        GLFW.glfwDefaultWindowHints();
        // Exactly the profile Minecraft 1.20.1 requests, so the compile result transfers.
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

        long window = GLFW.glfwCreateWindow(64, 64, "model3d-shader-check", 0L, 0L);
        if (window == 0L) {
            System.out.println("SKIP: glfwCreateWindow failed - no usable GL context here. "
                    + "This is NOT a pass; the shaders remain unverified.");
            GLFW.glfwTerminate();
            System.exit(2);
        }
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            System.out.println("GL_VERSION  = " + GL11C.glGetString(GL11C.GL_VERSION));
            System.out.println("GL_VENDOR   = " + GL11C.glGetString(GL11C.GL_VENDOR));
            System.out.println("GL_RENDERER = " + GL11C.glGetString(GL11C.GL_RENDERER));
            System.out.println("GLSL        = " + GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION));

            String vertex;
            String fragment;
            try {
                vertex = read(VERTEX_RESOURCE);
                fragment = read(FRAGMENT_RESOURCE);
            } catch (IOException e) {
                // Not a skip: a shader the renderer cannot load is a broken renderer.
                System.out.println("FAIL: " + e.getMessage());
                System.out.println("The live shader pair must be on the classpath the mod loads it "
                        + "from; a missing resource means ModelCpuRenderPath#read would throw.");
                System.exit(1);
                return;
            }
            System.out.println("compiling " + VERTEX_RESOURCE + " + " + FRAGMENT_RESOURCE);

            int program = compileAndLink(vertex, fragment);
            if (program != 0) {
                System.out.println("LINKED program id=" + program);
                for (int slot = 0; slot < ATTRIBUTES.length; slot++) {
                    int location = GL20C.glGetAttribLocation(program, ATTRIBUTES[slot]);
                    System.out.println("  attribute " + ATTRIBUTES[slot] + " -> " + location
                            + " (pinned " + slot + ")" + (location != slot ? "  MISMATCH" : ""));
                    check("attribute '" + ATTRIBUTES[slot] + "' is active at slot " + slot,
                            location == slot);
                }
                for (String name : UNIFORMS) {
                    int location = GL20C.glGetUniformLocation(program, name);
                    System.out.println("  uniform   " + name + " -> " + location
                            + (location < 0 ? "  ABSENT" : ""));
                    check("uniform '" + name + "' is active", location >= 0);
                }
                GL20C.glDeleteProgram(program);
            }

            // Negative control: a deliberately broken shader must fail to compile. Without this, a
            // harness that never actually compiled anything would report success.
            int rejected = compileQuiet(
                    "#version 150 core\nvoid main() { gl_Position = vec4(this is not glsl); }");
            check("negative control is rejected (the compiler really ran)", rejected == 0);
            if (rejected != 0) {
                GL20C.glDeleteShader(rejected);
            }
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }

        System.out.println(failures == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + failures + " check(s))");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = ShaderCompileCheck.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("shader resource not found on the classpath: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compileAndLink(String vertex, String fragment) {
        int vs = compile("model_cpu.vsh", GL20C.GL_VERTEX_SHADER, vertex);
        if (vs == 0) {
            return 0;
        }
        int fs = compile("model_cpu.fsh", GL20C.GL_FRAGMENT_SHADER, fragment);
        if (fs == 0) {
            GL20C.glDeleteShader(vs);
            return 0;
        }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        // Pinned before linking, exactly as ModelCpuRenderPath does: GLSL 150 has no
        // layout(location = N), so the slot each attribute lands in is decided here. Hard-coding the
        // driver's choice instead is how positions once got uploaded into the joint buffer.
        for (int slot = 0; slot < ATTRIBUTES.length; slot++) {
            GL20C.glBindAttribLocation(program, slot, ATTRIBUTES[slot]);
        }
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            System.out.println("LINK FAILED:\n" + GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            failures++;
            return 0;
        }
        check("program links", true);
        return program;
    }

    private static int compile(String label, int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        boolean ok = GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL11C.GL_FALSE;
        System.out.println((ok ? "COMPILED " : "FAILED   ") + label);
        if (!ok) {
            System.out.println(GL20C.glGetShaderInfoLog(shader));
            failures++;
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Compiles a shader whose failure is the expected outcome; returns the id, or 0. */
    private static int compileQuiet(String source) {
        int shader = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
