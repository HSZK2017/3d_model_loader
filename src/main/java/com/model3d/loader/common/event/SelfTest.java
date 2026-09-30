package com.model3d.loader.common.event;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelHandle;
import com.model3d.loader.api.ModelScale;
import com.model3d.loader.common.entity.TestModelEntity;
import com.model3d.loader.resource.ModelLoadService;
import com.model3d.loader.scene.ModelAnimation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.server.ServerStartedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * An opt-in, self-verifying acceptance run on a dedicated server.
 *
 * <h2>Why this exists</h2>
 * Most of this mod's behaviour needs a display, so "verified" is otherwise limited to "compiles and
 * the parsers pass their unit tests". That leaves the most valuable question unanswered: does the
 * mod's <b>real</b> resource path - the one that goes through a live {@code ResourceManager},
 * a running server, the mod's own jar and the actual command - work end to end? It does not need a
 * GPU to answer: resolution, the descriptor, parsing from inside the jar, the animation inventory
 * and the entity spawn are all server-side.
 *
 * <p>So the same code the command runs is executed at server start when
 * {@code -Dmodel3d.selfTest=<model>} is given, and the result is judged by the process exit code.
 * That makes "the mod boots on a dedicated server and can load a model from its own jar" a
 * machine-checkable fact rather than a claim:
 * <pre>
 *   gradlew runServer -Dmodel3d.selfTest=model3d:animated_test
 * </pre>
 *
 * <p>Off by default, because it spawns entities and stops the server. A test hook that runs
 * unrequested in production is not a test hook, it is a bug waiting for a user.
 */
public final class SelfTest {

    /** System property that enables the run; value is the model id to exercise. */
    public static final String PROPERTY = "model3d.selfTest";

    private SelfTest() {
    }

    /**
     * Runs the acceptance checks and stops the server. Called from {@link CommonEvents}.
     *
     * @return true when a self-test was requested and has been started
     */
    public static boolean maybeRun(ServerStartedEvent event) {
        String requested = System.getProperty(PROPERTY, "").trim();
        if (requested.isEmpty()) {
            return false;
        }
        MinecraftServer server = event.getServer();
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();

        try {
            ResourceLocation modelId = requested.indexOf(':') >= 0
                    ? ResourceLocation.tryParse(requested)
                    : new ResourceLocation(Model3D.MOD_ID, requested);
            check(modelId != null, failures, "model id '" + requested + "' did not parse");
            if (modelId == null) {
                return finish(server, failures, report);
            }
            report.append("model: ").append(modelId).append('\n');

            // 1. The name index must see it, which is what tab-completion and the command's
            //    "no such model" versus "model is broken" distinction both depend on.
            var known = ModelLoadService.INSTANCE.knownServerModelNames(server.getResourceManager());
            check(known.contains(modelId.toString()), failures,
                    "model is absent from the server's name index; known models: " + known);
            report.append("name index: ").append(known.size()).append(" model(s)\n");
            // Always include the by-path versus listing diagnosis: when the two disagree it is the
            // only thing that explains the failure, and when they agree it costs four lines.
            report.append(com.model3d.loader.common.command.DiagCommand.report(
                    server.getResourceManager(), requested)).append('\n');

            // 2. The full resource path: descriptor, source resolution, parse, animation inventory.
            ModelHandle handle = ModelLoadService.INSTANCE.acquireServer(
                    server.getResourceManager(), modelId);
            check(handle != null, failures, "acquireServer returned null (see the parse error above)");
            if (handle == null) {
                return finish(server, failures, report);
            }
            try {
                var scene = handle.scene();
                report.append("parsed: ").append(scene).append('\n');
                check(scene.meshes().length > 0, failures, "model parsed with no meshes");
                check(scene.nodeCount() > 0, failures, "model parsed with no nodes");
                int triangles = 0;
                int vertices = 0;
                for (var mesh : scene.meshes()) {
                    for (var primitive : mesh.primitives()) {
                        triangles += primitive.indexCount() / 3;
                        vertices += primitive.vertexCount();
                    }
                }
                check(triangles > 0, failures, "model has zero triangles");
                report.append("geometry: ").append(vertices).append(" vertices, ")
                        .append(triangles).append(" triangles, ")
                        .append(scene.materials().length).append(" materials, ")
                        .append(scene.skins().length).append(" skins, ")
                        .append(scene.embeddedImages().size()).append(" embedded images\n");
                float scale = ModelScale.forHandle(handle);
                check(scale > 0.0f && Float.isFinite(scale), failures,
                        "computed scale is not a usable number: " + scale);
                report.append("scale: ").append(scale).append(" blocks per model unit (longest axis ")
                        .append(scene.longestExtent()).append(")\n");
                for (ModelAnimation animation : scene.animations()) {
                    report.append("animation: '").append(animation.name()).append("' tracks=")
                            .append(animation.trackCount()).append(" duration=")
                            .append(animation.duration()).append("s\n");
                }

                // 3. The entity, spawned the way the command spawns it, then ticked. A model can
                //    parse and still be unusable if the entity rejects the scale or the animation
                //    name, so this is checked rather than assumed.
                ServerLevel level = server.overworld();
                TestModelEntity entity = com.model3d.loader.common.registry.ModEntities.TEST_MODEL.get()
                        .create(level);
                check(entity != null, failures, "the entity type refused to create an instance");
                if (entity != null) {
                    List<String> names = new ArrayList<>();
                    for (ModelAnimation animation : scene.animations()) {
                        names.add(animation.name());
                    }
                    entity.moveTo(level.getSharedSpawnPos().getX() + 2.5, level.getSharedSpawnPos().getY(),
                            level.getSharedSpawnPos().getZ() + 2.5, 0.0f, 0.0f);
                    entity.setModel(modelId, scale, names);
                    entity.setAnimation(names.isEmpty() ? null : names.get(0), true);
                    check(level.addFreshEntity(entity), failures, "addFreshEntity returned false");
                    check(entity.hasModel(), failures, "the entity reports no model after setModel");
                    check(modelId.equals(entity.modelId()), failures,
                            "the entity's model id round-tripped as " + entity.modelId());
                    check(entity.animationNames().length == names.size(), failures,
                            "the entity's synced animation list is " + entity.animationNames().length
                                    + " long but the model has " + names.size());
                    if (!names.isEmpty()) {
                        String cycled = entity.cycleAnimation();
                        check(cycled != null, failures, "cycling animations returned null");
                        report.append("entity: id=").append(entity.getId()).append(" model=")
                                .append(entity.modelId()).append(" anims=")
                                .append(java.util.Arrays.toString(entity.animationNames()))
                                .append(" cycled to '").append(cycled).append("'\n");
                    }
                    // Tick it once so a crash inside entity ticking would surface here rather than
                    // on the first player's frame.
                    entity.tick();
                }
            } finally {
                ModelLoadService.INSTANCE.releaseServer(modelId);
            }
        } catch (Throwable t) {
            // A throwable here is exactly what this hook exists to catch, so it is recorded rather
            // than allowed to escape into Forge's event bus and be reported as an unrelated crash.
            failures.add("unexpected " + t.getClass().getName() + ": " + t.getMessage());
            for (StackTraceElement element : t.getStackTrace()) {
                report.append("    at ").append(element).append('\n');
            }
        }
        return finish(server, failures, report);
    }

    private static void check(boolean condition, List<String> failures, String message) {
        if (!condition) {
            failures.add(message);
        }
    }

    private static boolean finish(MinecraftServer server, List<String> failures, StringBuilder report) {
        System.out.println("=========== Model3D self-test ===========");
        System.out.print(report);
        if (failures.isEmpty()) {
            System.out.println("RESULT: PASS");
        } else {
            System.out.println("RESULT: FAIL (" + failures.size() + " problem(s))");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
        }
        System.out.println("========================================");
        // The exit code is the verdict, so a CI-style caller needs no log parsing.
        //
        // The stop must be *scheduled* onto the server thread, not performed here: this method runs
        // inside the ServerStartedEvent, i.e. on the server thread already, and calling
        // stopServer() re-entrantly from another thread mid-tick tears down chunk storage under the
        // running tick - which surfaces as `ReportedException: Exception ticking world` and a
        // fastutil NPE, i.e. a crash report that has nothing to do with the mod. The schedule call
        // returns immediately and the shutdown runs after the current tick completes.
        final int code = failures.isEmpty() ? 0 : 1;
        server.execute(() -> {
            server.halt(false);
            // halt() asks for a clean stop; force the exit code afterwards, because Forge's own
            // shutdown path exits 0 regardless and would erase a FAIL verdict.
            new Thread(() -> {
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(code);
            }, "model3d-selftest-exit").start();
        });
        return true;
    }
}
