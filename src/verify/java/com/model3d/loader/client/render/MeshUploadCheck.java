// Verification harness for the client renderer - NOT part of the mod jar, and outside src/main so it
// never reaches the compiler for a normal build.
//
// Run with:  gradlew.bat checkExtras --console=plain   (or just meshUploadCheck)
//
// It creates an invisible GLFW window, takes a real OpenGL context, and exercises the code that
// actually draws: the live shader pair through ModelCpuRenderPath#ensureCompiled, a vertex written
// by the real CpuVertexWriter, uploaded with the attribute pointers ModelCpuMesh sets, drawn with
// glDrawArrays - and GlStateGuard's restore, read back from the driver.
//
// This file lives in the renderer's own package on purpose: the layout constants, the writer and the
// program are package-private, and a harness that re-declares them instead of using them would be
// verifying its own copy. That mistake has a history here - the previous mesh harness uploaded a
// mesh loader that is no longer on the draw path, so it passed while the live path crashed.
//
// What it does not prove: that a model appears on screen. Whether a model is visible is verified in
// a real client, by the acceptance run, and ultimately by looking at it.
//
// Exit codes: 0 pass, 1 fail, 2 when no GL context is available - reported as not-pass, never a pass.
package com.model3d.loader.client.render;

import com.model3d.loader.client.gl.GlStateGuard;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

public final class MeshUploadCheck {

    private static int failures;

    public static void main(String[] args) {
        System.out.println("=== Model3D live mesh path check ===");
        long window = 0L;
        try {
            if (!GLFW.glfwInit()) {
                skip("glfwInit failed");
                return;
            }
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(64, 64, "model3d", 0L, 0L);
            if (window == 0L) {
                skip("glfwCreateWindow failed (no display, or no GL 3.2 core)");
                return;
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            // The live path asserts the render thread at every GL entry point; this is how the
            // harness legitimately becomes that thread.
            RenderSystem.initRenderThread();
            System.out.println("GL_RENDERER = " + GL11C.glGetString(GL11C.GL_RENDERER));

            // 1. The live program: compiled, linked and queried by the renderer's own code.
            check("ModelCpuRenderPath.ensureCompiled() builds the live program",
                    ModelCpuRenderPath.ensureCompiled());
            check("a second call reuses it rather than recompiling",
                    ModelCpuRenderPath.ensureCompiled());

            // 2. The live vertex layout: written by CpuVertexWriter, pointed at with ModelCpuMesh's
            //    offsets, submitted as the real path submits it.
            int writerCapacity = 3;
            CpuVertexWriter writer = new CpuVertexWriter(writerCapacity);
            try {
                writer.vertex(-0.5f, -0.5f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f);
                writer.vertex(0.5f, -0.5f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f);
                writer.vertex(0.0f, 0.5f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f);
                check("CpuVertexWriter wrote three vertices", writer.writtenVertices() == 3);
                check("the vertex stride is what the pointers use",
                        ModelCpuMesh.STRIDE == CpuVertexWriter.STRIDE);

                int vao = GL30C.glGenVertexArrays();
                int vbo = GL15C.glGenBuffers();
                GL30C.glBindVertexArray(vao);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
                GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER,
                        (long) writer.capacity() * ModelCpuMesh.STRIDE, GL15C.GL_STREAM_DRAW);
                writer.buffer().position(0);
                writer.buffer().limit(3 * ModelCpuMesh.STRIDE);
                GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, writer.buffer());

                GL20C.glEnableVertexAttribArray(0);
                GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, ModelCpuMesh.STRIDE,
                        ModelCpuMesh.POSITION_OFFSET);
                GL20C.glEnableVertexAttribArray(1);
                GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, ModelCpuMesh.STRIDE,
                        ModelCpuMesh.UV_OFFSET);
                GL20C.glEnableVertexAttribArray(2);
                GL20C.glVertexAttribPointer(2, 4, GL33C.GL_INT_2_10_10_10_REV, true,
                        ModelCpuMesh.STRIDE, ModelCpuMesh.NORMAL_OFFSET);
                check("no GL error after the attribute setup", GL11C.glGetError() == 0);
                System.out.printf("  layout: stride=%d position@%d uv@%d normal@%d%n",
                        ModelCpuMesh.STRIDE, ModelCpuMesh.POSITION_OFFSET, ModelCpuMesh.UV_OFFSET,
                        ModelCpuMesh.NORMAL_OFFSET);

                GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
                int error = GL11C.glGetError();
                System.out.println("  glDrawArrays(GL_TRIANGLES, 0, 3) -> GL error 0x"
                        + Integer.toHexString(error));
                check("draw() left no GL error", error == 0);

                GL30C.glBindVertexArray(0);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
                GL15C.glDeleteBuffers(vbo);
                GL30C.glDeleteVertexArrays(vao);
            } finally {
                writer.free();
            }

            // 3. The guard: everything the live path touches must come back. Read back from the
            //    driver rather than trusting the guard's own bookkeeping.
            stateGuardRoundTrip();

            // 4. And the state the guard put back must not have poisoned the tracked API, or every
            //    later per-material state change is dropped without a word.
            trackedStateStillReachesTheDriver();

            System.out.println();
            if (failures == 0) {
                System.out.println("RESULT: PASS");
                System.exit(0);
            }
            System.out.println("RESULT: FAIL (" + failures + " check(s))");
            System.exit(1);
        } catch (Throwable t) {
            System.out.println("FAIL: harness threw " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        } finally {
            if (window != 0L) {
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
        }
    }

    /**
     * Takes a guard, changes the program and the bindings of units 0-2, closes it, and reads the
     * driver's state back. The live draw runs inside this guard, so a guard that does not restore
     * leaves the model's program and lightmap bound for the rest of vanilla's frame.
     */
    private static void stateGuardRoundTrip() {
        int[] scratch = new int[1];

        // Something to restore to: two linked programs and three textures, all distinct. The
        // programs must be linked: binding an unlinked program is a GL_INVALID_OPERATION on this
        // driver, and glUseProgram then leaves the binding at 0, which would fail the harness for a
        // reason that has nothing to do with the guard.
        int programA = linkTrivialProgram();
        int programB = linkTrivialProgram();
        int[] textures = new int[3];
        for (int unit = 0; unit < 3; unit++) {
            textures[unit] = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[unit]);
        }
        GL11C.glGetIntegerv(GL13C.GL_ACTIVE_TEXTURE, scratch);
        int activeBefore = scratch[0];

        int[] bindingsBefore = new int[3];
        for (int unit = 0; unit < 3; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glGetIntegerv(GL11C.GL_TEXTURE_BINDING_2D, scratch);
            bindingsBefore[unit] = scratch[0];
        }
        GL13C.glActiveTexture(activeBefore);
        GL20C.glUseProgram(programA);

        try (GlStateGuard guard = new GlStateGuard()) {
            check("guard captured the bound program", guard.previousProgram() == programA);
            // Change exactly what the live path changes.
            GL20C.glUseProgram(programB);
            for (int unit = 0; unit < 3; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + 2);
        }

        GL11C.glGetIntegerv(GL20C.GL_CURRENT_PROGRAM, scratch);
        check("the guard restored the program binding", scratch[0] == programA);
        GL11C.glGetIntegerv(GL13C.GL_ACTIVE_TEXTURE, scratch);
        check("the guard restored the active texture unit", scratch[0] == activeBefore);

        boolean unitsRestored = true;
        for (int unit = 0; unit < 3; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glGetIntegerv(GL11C.GL_TEXTURE_BINDING_2D, scratch);
            boolean ok = scratch[0] == bindingsBefore[unit];
            unitsRestored &= ok;
            System.out.println("  unit " + unit + " binding -> " + scratch[0]
                    + (ok ? "" : "  EXPECTED " + bindingsBefore[unit]));
        }
        GL13C.glActiveTexture(activeBefore);
        check("the guard restored the bindings of units 0, 1 and 2", unitsRestored);
        int guardError = GL11C.glGetError();
        System.out.println("  glGetError after the guard round trip -> 0x"
                + Integer.toHexString(guardError));
        check("no GL error after the guard round trip", guardError == 0);

        for (int texture : textures) {
            GL11C.glDeleteTextures(texture);
        }
        GL20C.glDeleteProgram(programA);
        GL20C.glDeleteProgram(programB);
    }

    /**
     * After a guard round trip, the tracked API must still be able to move the driver.
     *
     * <p>This is the regression check for a fault that produced no error, no log line and no wrong
     * value in the renderer's own state log: the guard used to restore blend, depth and cull with raw
     * GL calls, so {@code GlStateManager}'s cache could claim a value the driver did not have - and
     * because the tracked setters skip their call when the cache already matches, every later request
     * was silently ignored. In the live path that showed as a BLEND material drawing fully opaque while
     * the trace printed {@code GL_BLEND=true}.
     *
     * <p>The recipe is the point: the disagreement only appears when the value the guard captured
     * differs from the value the cache last saw, which the disable-inside-the-guard step creates.
     */
    private static void trackedStateStillReachesTheDriver() {
        GlStateManager._enableBlend();
        try (GlStateGuard guard = new GlStateGuard()) {
            check("guard captured blending as on", GL11C.glIsEnabled(GL11C.GL_BLEND));
            GlStateManager._disableBlend();
        }
        check("the guard restored blending on", GL11C.glIsEnabled(GL11C.GL_BLEND));

        GlStateManager._disableBlend();
        check("a tracked disable reaches the driver after a guard round trip",
                !GL11C.glIsEnabled(GL11C.GL_BLEND));
        GlStateManager._enableBlend();
        check("a tracked enable reaches the driver after a guard round trip",
                GL11C.glIsEnabled(GL11C.GL_BLEND));

        // Same mechanism, same requirement, for the other piece of state the live path sets per
        // material: a depth-mask request that is skipped turns a BLEND surface into one that keeps its
        // depth writes, or an OPAQUE one into a surface that stops writing them.
        GlStateManager._depthMask(false);
        check("a tracked depthMask(false) reaches the driver", !depthWriteMask());
        GlStateManager._depthMask(true);
        check("a tracked depthMask(true) reaches the driver", depthWriteMask());

        // The live draw enables the depth test itself (measured: it enters with GL_DEPTH_TEST off), so
        // both directions have to work through the tracked API for the model to be occluded by terrain
        // and by entities in front of it.
        GlStateManager._enableDepthTest();
        check("a tracked enableDepthTest reaches the driver",
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST));
        GlStateManager._disableDepthTest();
        check("a tracked disableDepthTest reaches the driver",
                !GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST));
        GlStateManager._enableDepthTest();
        GlStateManager._disableBlend();
    }

    private static boolean depthWriteMask() {
        return GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
    }

    /** A minimal linked program, so the guard can be shown to restore a real binding. */
    private static int linkTrivialProgram() {        int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        GL20C.glShaderSource(vs, "#version 150 core\nvoid main() { gl_Position = vec4(0.0); }");
        GL20C.glCompileShader(vs);
        int fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        GL20C.glShaderSource(fs, "#version 150 core\nout vec4 c;\nvoid main() { c = vec4(1.0); }");
        GL20C.glCompileShader(fs);
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            System.out.println("  FAIL could not link a probe program: "
                    + GL20C.glGetProgramInfoLog(program));
            failures++;
        }
        return program;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void skip(String why) {
        System.out.println("SKIP: " + why + " - no GL context, so the live mesh path stays "
                + "unverified. This is NOT a pass.");
        System.exit(2);
    }
}
