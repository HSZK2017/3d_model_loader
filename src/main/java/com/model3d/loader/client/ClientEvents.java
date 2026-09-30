package com.model3d.loader.client;

import com.model3d.loader.Model3D;
import com.model3d.loader.resource.ModelLoadService;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Per-frame and level-lifecycle client hooks, on the <b>forge</b> event bus.
 *
 * <h2>Why this is a separate class from {@link ClientSetup}</h2>
 * A class can be auto-subscribed to exactly one bus: {@code @Mod.EventBusSubscriber}'s {@code bus}
 * attribute selects it and Forge's {@code AutomaticEventSubscriber} registers the class there and
 * nowhere else. {@link RenderLevelStageEvent} and {@link LevelEvent} are game events posted on
 * {@code MinecraftForge.EVENT_BUS}, while the registration events in {@link ClientSetup} are
 * {@code IModBusEvent}s posted on the mod bus. One class could only serve one of the two, so the
 * subscriptions are split by which bus carries them.
 *
 * <p>This split is the fix for a crash, not a tidy-up. The registration class declared
 * {@code bus = FORGE} while subscribing to mod-bus events, so the entity renderer was never
 * registered and the client crashed with a null renderer the first time it drew the entity. See
 * {@link ClientSetup}'s javadoc for the full sequence.
 */
@Mod.EventBusSubscriber(modid = Model3D.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    private ClientEvents() {
    }

    /**
     * Ticks per second, for converting the game's frame delta into the seconds every clock in this
     * mod is written in.
     *
     * <p>Named, and applied in exactly one place (the {@code advance} call below), because the two
     * units are indistinguishable at the call site - both are a small positive float - and the
     * failure is silent: an animation 20 times too fast still animates.
     *
     * <p>Verified in the mapped sources jar
     * {@code forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1-sources.jar}:
     * <pre>
     *   net/minecraft/client/Timer.java:14    this.msPerTick = 1000.0F / pTicksPerSecond;
     *   net/minecraft/client/Timer.java:19    this.tickDelta = (float)(pGameTime - this.lastMs) / this.msPerTick;
     *   net/minecraft/client/Minecraft.java:2695    return this.timer.tickDelta;   // getDeltaFrameTime
     * </pre>
     * i.e. {@code getDeltaFrameTime()} is elapsed milliseconds divided by 50 (the timer is built for
     * 20 ticks per second), so a 50 ms frame is 1.0 - not 0.05.
     */
    private static final float TICKS_PER_SECOND = 20.0f;

    /**
     * Advances every model instance once per frame.
     *
     * <p>{@code AFTER_ENTITIES} rather than a client tick, and rather than the entity renderer call
     * itself: a tick does not run once per frame (a frame can render without ticking, and a tick can
     * run without rendering), and advancing inside the renderer would make the pose depend on how
     * many entities share a model and on which of them vanilla happened to draw. Advancing once,
     * here, keeps the clock and the pose in the same frame as the draw that reads them, and keeps
     * {@link com.model3d.loader.api.ModelInstance} on its documented owner, the render thread.
     */
    /**
     * Points the loader's change hook at the GPU cache, on the client.
     *
     * <p>Installed here and not from {@code ModelLoadService} because that class is side-agnostic: it
     * also runs on a dedicated server, where there is no cache and no client class to name. A hook
     * rather than a direct call for the same reason, and a {@code Runnable} because the loader only
     * needs to say "things changed" - what that means for GPU resources is the client's business.
     */
    private static void installModelFolderHook() {
        ModelLoadService.INSTANCE.setOnModelFoldersChanged(
                () -> ClientModelManager.get().onResourceReload());
        Model3D.LOGGER.debug("Model3D: model folder hot reload wired to the GPU cache");
    }

    /** True once the hook is installed; the first frame installs it. */
    private static boolean hookInstalled;

    /**
     * Installs the hook on the first frame rather than during client setup.
     *
     * <p>{@code ClientModelManager}'s cache is created lazily and the render thread owns it, so
     * wiring here keeps installation and use on the same thread. Doing it in client setup would work
     * today and would break the moment the cache's construction moved.
     */
    private static void installModelFolderHookOnce() {
        if (!hookInstalled) {
            hookInstalled = true;
            installModelFolderHook();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        // Model folders are polled here rather than on a client tick because this is the thread that
        // owns the GPU resources the change invalidates: a change detected here can drop the meshes
        // immediately, whereas a tick handler would have to hand the work across threads.
        installModelFolderHookOnce();
        ModelLoadService.INSTANCE.checkForChanges(true);
        // getDeltaFrameTime is the wall-clock length of the previous frame; using the partial tick
        // instead would tie the animation speed to the camera's interpolation rather than to time.
        //
        // It is in TICKS and the API is in SECONDS - see TICKS_PER_SECOND above for the two vanilla
        // lines this was verified from - so the division here is the whole conversion. Feeding the
        // tick value straight in (what this call used to do) made every animation run about 20x too
        // fast, which nothing noticed because a model 20x too fast still animates; the per-call step
        // cap then turned the error into a frame-rate-dependent visible rate rather than a clean
        // factor of 20.
        //
        // This is a deliberate, observable change of animation rate for every existing consumer of
        // this mod: the documented parameter of ModelInstance#update is seconds, and after this
        // division it finally is. It also repairs the stall guard in ClientModelManager#advance,
        // which drops a frame whose delta exceeds 1.0 - a threshold that was 50 ms while the value
        // was ticks, so every frame below 20 fps used to freeze the clock rather than step it.
        ClientModelManager.get().advance(minecraft.getDeltaFrameTime() / TICKS_PER_SECOND);
        ClientModelManager.get().setLastPartialTick(event.getPartialTick());
    }

    /**
     * Frees GL resources when the level is unloaded - quitting to the title screen, leaving a world,
     * or switching dimensions' parent level.
     *
     * <p>{@code LevelEvent.Unload} is posted on the client too, and it is the only notification that
     * arrives before the client level is discarded. Without this, a player who leaves a world keeps
     * every mesh and texture of every model they saw, and the next world starts with a cache keyed on
     * handles that no longer exist.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        // The event can fire for a level that is not the client's own; only tear down for the one
        // whose models this cache actually describes.
        if (Minecraft.getInstance().level != event.getLevel()) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            // GL teardown must happen on the render thread; deferring the whole teardown to the next
            // frame is what ClientModelManager.pendingReload is for.
            ClientModelManager.get().onResourceReload();
            return;
        }
        ClientModelManager.get().onLevelUnload();
    }
}
