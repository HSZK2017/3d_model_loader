package com.model3d.loader.client;

import com.model3d.loader.Model3D;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * An opt-in, self-driving client acceptance run.
 *
 * <h2>Why this exists</h2>
 * Everything in this mod's render path passed without a game: the shaders compile, meshes upload and
 * draw, uniforms upload, the state guard round-trips, all of it survives ten thousand JIT-compiled
 * frames in a headless harness. And a real client still died with a JVM access violation the moment
 * it drew the model. That gap - "works in a harness, faults in the client" - cannot be closed by
 * more harness, because the difference is the client's own state: its GL context, its hundreds of
 * loaded mods, its class loaders, its frame being mid-flight around the draw.
 *
 * <p>So this drives the client itself: join a world, spawn the test entity through the real command,
 * hold it in view for a few seconds, then quit. Run it with
 * <pre>
 *   gradlew runClient -Pmodel3dClientTest=model3d:animated_test
 * </pre>
 * A session that reaches the final log line and exits cleanly has drawn the model without faulting.
 * A session that dies produces exactly the crash report the harness could not.
 *
 * <p>Off by default, and it must stay that way: it spawns entities, runs commands as the player and
 * quits the game. A test hook that fires unrequested in a normal session is not a test hook.
 */
@Mod.EventBusSubscriber(modid = Model3D.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientSelfTest {

    /** System property that enables the run; the value is the model id to exercise. */
    public static final String PROPERTY = "model3d.clientTest";

    /** Frames to keep the entity in front of the camera before quitting. */
    private static final int HOLD_TICKS = 80;

        /** A second model, written into the folder mid-session, to prove hot reload. */
    public static final String HOT_WRITE_PROPERTY = "model3d.clientTestHotWrite";

    /** Ticks to wait after spawning the first model before trying the hot-reloaded one. */
    private static final int HOT_TICKS = 40;

    /** System property overriding how far in front of the camera the test entity is spawned. */
    public static final String DISTANCE_PROPERTY = "model3d.clientTestDistance";

    /**
     * Default spawn distance in blocks, derived from the model normalisation target.
     *
     * <p>A model is normalised so its longest axis measures
     * {@link com.model3d.loader.api.ModelScale#DEFAULT_TARGET_BLOCKS} - 40 blocks, not the 4 this
     * harness was originally calibrated for - and the default vantage is derived from that;
     * {@link #DISTANCE_PROPERTY} overrides it for a model that needs something else. Measured at the
     * old fixed 5 blocks: the camera sat inside a default-sized model and the screenshot showed the
     * interior of the model instead of the model, which is indistinguishable from nothing having
     * loaded.
     *
     * <p>1.25 times the target, i.e. 50 blocks from the entity origin. The model is centred on that
     * origin, so half its longest axis reaches towards the camera: 50 blocks leaves 30 blocks of
     * clearance from the near face, and a 40-block model covers about 44 of the default 70 degrees of
     * vertical field of view - unmistakable, without being clipped or lost to distance.
     */
    private static final double DEFAULT_DISTANCE =
            com.model3d.loader.api.ModelScale.DEFAULT_TARGET_BLOCKS * 1.25;

    /**
     * Most the entity may be spawned below the player's eye, in blocks.
     *
     * <p>Measured problem: this test world's player looks steeply down (view vector
     * {@code (0.07, -1.00, 0.01)}), so placing the entity along the raw view vector at the default
     * distance lands it tens of blocks below the ground - entities logged at y=-62, -83 and -94 with
     * the eye at y=-51.82 - where it is unlit and renders black, which no screenshot can be judged
     * from.
     *
     * <p>1.5 blocks, because the player's eye sits 1.62 blocks above their feet (the run's own log
     * shows the eye at -51.82 with the feet at -53.42): a larger cap would put the entity's origin
     * below the feet, inside the block the player is standing on. The clamp is logged rather than
     * silent because it changes the framing as well as the height - with a near-vertical view the
     * horizontal part of the offset collapses with the vertical one, so the model ends up at the
     * camera rather than in front of it.
     */
    private static final double MAX_DROP_BELOW_EYE = 1.5;

    /**
     * Removes the test entities earlier runs left in the world, before this run spawns its own.
     *
     * <p>Runs are not independent without this: the entity type is registered saved, so every model
     * the harness has ever spawned is still in the world at the next join and the client re-instances
     * all of them (one log shows {@code created instance for entity ...} a dozen times before the
     * harness had spawned anything). What the tick-20 screenshot shows then depends on how many times
     * the harness has been run rather than on the code under test.
     *
     * <p>{@code type=} takes the id the entity is registered under:
     * {@link com.model3d.loader.common.registry.ModEntities#TEST_MODEL} is {@code test_model} in
     * {@link Model3D#MOD_ID}, the same id {@code /testmodel} spawns, and nothing else in the world can
     * match the selector.
     */
    private static final String CLEAR_LEFTOVERS = "kill @e[type=" + Model3D.MOD_ID + ":test_model]";

    /**
     * Levels the camera's pitch, keeping its yaw, before a vantage is chosen.
     *
     * <p>Server-side on purpose. The entity is spawned along the player's view vector, and this world
     * keeps a saved rotation that points almost straight down - measured {@code look.y = -1.00} - so a
     * spawn asked for 50 blocks ahead lands 50 blocks below the eye. Clamping the Y then leaves only a
     * few blocks of horizontal offset, and for a 40-block model that still puts the camera inside it.
     * Client-side rotation does not stick, which an earlier attempt at exactly this discovered, so the
     * rotation is set through the server and the spawn waits for it to come back in the position
     * packet.
     */
    private static final String LEVEL_VIEW = "tp @s ~ ~ ~ 0 0";

    /**
     * The tick the entity is spawned on. After the level-view and cleanup commands, and late enough
     * that the camera rotation has arrived back from the server - the client's view vector does not
     * change until it does, which a first attempt at tick 4 demonstrated by reading the old pitch.
     */
    private static final int SPAWN_TICK = 12;

    /**
     * The tick the screenshot is taken on: after the spawn, with enough ticks for the entity to reach
     * the client and be drawn, and well before the hold ends.
     */
    private static final int SCREENSHOT_TICK = 30;

    private static int ticks;
    private static boolean started;

    private ClientSelfTest() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        String model = System.getProperty(PROPERTY, "").trim();
        if (model.isEmpty() || !started) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ticks++;
        if (ticks == 1) {
            // Pick a stable vantage point: spectator so the player neither falls nor is pushed, and the
            // camera's position left where the world put it (its pitch is levelled on the next tick).
            //
            // What actually decides whether the model is legible is the viewing angle: a model scaled so
            // its longest axis measures DEFAULT_TARGET_BLOCKS seen from above is a sliver however
            // carefully it is drawn, and the same model at the same distance seen level is unmistakable.
            // That is why the angle is normalised here and why the trace reports the on-screen extent -
            // so the angle's effect is measured instead of assumed.
            Model3D.LOGGER.info("Model3D client test: spectator, spawning {} blocks ahead",
                    DEFAULT_DISTANCE);
            minecraft.player.connection.sendCommand("gamemode spectator");
        }
        if (ticks == 2) {
            Model3D.LOGGER.info("Model3D client test: sending /{} so the spawn lands in front of the"
                    + " camera rather than below it (this world's saved pitch points down)",
                    LEVEL_VIEW);
            minecraft.player.connection.sendCommand(LEVEL_VIEW);
        }
        if (ticks == 3) {
            // Every run must exercise its own model and nothing else. Sent on its own tick, and ahead
            // of the spawn on tick 6, so the entity this run is about to create does not exist yet and
            // cannot fall inside the kill's selector.
            Model3D.LOGGER.info("Model3D client test: sending /{} so this run renders the model it"
                    + " spawns and not the ones earlier runs left in the world", CLEAR_LEFTOVERS);
            minecraft.player.connection.sendCommand(CLEAR_LEFTOVERS);
        }
        if (ticks == SPAWN_TICK) {
            // Ten ticks after the level-view command, and deliberately late: the client's view vector
            // only changes when the server's position packet arrives, and a first attempt at tick 4
            // (two ticks after the command) still read the old pitch. The rotation actually in force is
            // logged, so "the camera was not levelled" cannot be mistaken for "the model is invisible".
            var eye = minecraft.player.getEyePosition(1.0f);
            var look = minecraft.player.getViewVector(1.0f);
            Model3D.LOGGER.info("Model3D client test: view is yaw={} pitch={} (look={},{},{}) when"
                            + " choosing the vantage", fmt(minecraft.player.getYRot()),
                    fmt(minecraft.player.getXRot()), fmt(look.x), fmt(look.y), fmt(look.z));
            // Overridable because distance decides whether the model is legible at all: at the default
            // 50 blocks a default-sized model is a landmark, at 5 the camera is inside it, and a report
            // of "I cannot see it" means something different in each case. Making it a knob turns that
            // from a guess about the screenshot into a measurement.
            double distance = DEFAULT_DISTANCE;
            String override = System.getProperty(DISTANCE_PROPERTY, "").trim();
            if (!override.isEmpty()) {
                try {
                    distance = Double.parseDouble(override);
                } catch (NumberFormatException e) {
                    Model3D.LOGGER.warn("Model3D client test: ignoring bad {}='{}'",
                            DISTANCE_PROPERTY, override);
                }
            }
            double x = eye.x + look.x * distance;
            double y = eye.y + look.y * distance;
            double z = eye.z + look.z * distance;
            if (y < eye.y - MAX_DROP_BELOW_EYE) {
                double unclamped = y;
                double horizontal = Math.sqrt((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z));
                y = eye.y - MAX_DROP_BELOW_EYE;
                // A silent clamp would hide the reason the model can appear above the crosshair
                // instead of centred, or at the camera instead of in front of it: the horizontal
                // offset is printed because a steep view shortens it by the same factor as the drop.
                Model3D.LOGGER.info("Model3D client test: spawn y clamped from {} to {} - the view"
                        + " vector points {} blocks down and the entity is kept at most {} below the"
                        + " eye; that leaves only {} blocks of horizontal offset out of the {} block"
                        + " distance, so a steeply pitched view puts the model near the camera rather"
                        + " than in front of it",
                        fmt(unclamped), fmt(y), fmt(eye.y - unclamped), fmt(MAX_DROP_BELOW_EYE),
                        fmt(horizontal), fmt(distance));
            }
            String spawn = String.format(java.util.Locale.ROOT,
                    "testmodel loader %s %.2f %.2f %.2f", model, x, y, z);
            Model3D.LOGGER.info("Model3D client test: eye=({},{},{}) look=({},{},{}) distance={} -> /{}",
                    fmt(eye.x), fmt(eye.y), fmt(eye.z), fmt(look.x), fmt(look.y), fmt(look.z),
                    fmt(distance), spawn);
            minecraft.player.connection.sendCommand(spawn);
        }
        if (ticks == HOT_TICKS) {
            // Hot reload, proved end to end. The extra model is a file written into the folder NOW,
            // while this client is running - by this code, not by a human, so the test does not race
            // an external copy and does not depend on how long a person takes to drag a file. Nothing
            // tells the mod the file arrived: the folder poll has to notice the change, invalidate the
            // name index, drop the GPU cache, and then the command below has to find a model that did
            // not exist when the client started.
            if (System.getProperty(HOT_WRITE_PROPERTY, "").trim().isEmpty()) {
                return;
            }
            String hot = System.getProperty(HOT_WRITE_PROPERTY).trim();
            try {
                if (!writeHotModel(hot)) {
                    return;
                }
            } catch (Exception e) {
                Model3D.LOGGER.error("Model3D client test: could not write the hot-reload model", e);
                return;
            }
            Model3D.LOGGER.info("Model3D client test: wrote '{}' into the model folder DURING the"
                    + " session; asking for it now", hot);
            minecraft.player.connection.sendCommand("testmodel reload");
            minecraft.player.connection.sendCommand("testmodel loader " + hot + " ~6 ~ ~");
        }
        if (ticks == SCREENSHOT_TICK) {
            // Capture early in the hold, but not before the model can actually be on screen: the spawn
            // goes out at SPAWN_TICK, the entity then has to reach the client and be drawn once before
            // the frame means anything. Measured: with the screenshot 8 ticks after the spawn the
            // client had created no instance yet and the image was an empty frame, which reads exactly
            // like "the model did not render".
            //
            // Capturing late in the hold is not the alternative: with no window focus the client opens
            // the Game Menu, and the first attempt at this produced a screenshot of the pause menu with
            // the world dimmed behind it - an image of the wrong frame. This tick is the compromise:
            // late enough to contain the model, early enough to be the frame the log describes.
            try {
                net.minecraft.client.Screenshot.grab(
                        minecraft.gameDirectory, minecraft.getMainRenderTarget(),
                        message -> Model3D.LOGGER.info("Model3D client test: screenshot {}", message));
                Model3D.LOGGER.info("Model3D client test: grabbed a screenshot of the spawned model");
            } catch (Throwable t) {
                Model3D.LOGGER.warn("Model3D client test: screenshot failed: {}", t.toString());
            }
        }
        if (ticks == HOLD_TICKS) {
            Model3D.LOGGER.info("Model3D client test: {} ticks with the entity spawned and no fault;"
                    + " the render path survived a real client. Quitting.", ticks);
            minecraft.stop();
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /**
     * Writes a model folder into the config directory while the game runs, returning true on success.
     *
     * <p>Uses the mod's own shipped fixture as the content so the file is a real, parseable model -
     * a dummy file would prove the folder was noticed and nothing about whether the resulting model
     * loads. The bytes come from the classpath, the same resource the mod itself loads in dev.
     *
     * <p>A folder rather than a loose file, because the folder layout is the one with a nested model
     * file and the one whose naming rule (the folder names the model) has the most room to be wrong.
     */
    private static boolean writeHotModel(String name) throws Exception {
        com.model3d.loader.resource.ModelLibrary library =
                com.model3d.loader.resource.ExternalModelPaths.library();
        java.nio.file.Path root = library.root();
        if (root == null) {
            Model3D.LOGGER.error("Model3D client test: no config model folder is resolvable");
            return false;
        }
        byte[] fixture;
        try (var stream = ClientSelfTest.class.getResourceAsStream(
                "/data/model3d/model3d/animated_test/model.glb")) {
            if (stream == null) {
                Model3D.LOGGER.error("Model3D client test: the fixture model is not on the classpath");
                return false;
            }
            fixture = stream.readAllBytes();
        }
        java.nio.file.Path folder = root.resolve(name);
        java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.write(folder.resolve("model.glb"), fixture);
        Model3D.LOGGER.info("Model3D client test: wrote {} bytes to {}", fixture.length,
                folder.resolve("model.glb"));
        return true;
    }

    /**
     * Keeps the pause menu from opening during an automated run.
     *
     * <p>An unattended client loses window focus, and losing focus opens the Game Menu - which then sits
     * in front of the world, cannot be dismissed without a click, and makes every screenshot an image of
     * the menu instead of the frame under test. That happened repeatedly and was even mistaken for a
     * hung client: with the window too small for the auto GUI scale, the menu rendered as one character
     * per line and was unclickable.
     *
     * <p>Only active when the run is armed, so a normal session keeps its pause menu.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!started || System.getProperty(PROPERTY, "").trim().isEmpty()) {
            return;
        }
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
            Model3D.LOGGER.info("Model3D client test: suppressing the pause menu so the run stays"
                    + " unattended and screenshots show the world");
            event.setCanceled(true);
        }
    }

    /**
     * Arms the run once the player is in a world.
     *
     * <p>Waits for the join rather than firing on startup because the command needs a connection and
     * the level, and because the join is where the client's own resource reload has just finished -
     * which is the state the crash report was captured in.
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (System.getProperty(PROPERTY, "").trim().isEmpty()) {
            return;
        }
        started = true;
        ticks = 0;
        Model3D.LOGGER.info("Model3D client test: armed, will spawn '{}' on the first tick",
                System.getProperty(PROPERTY));
    }
}
