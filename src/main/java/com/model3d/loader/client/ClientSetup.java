package com.model3d.loader.client;

import com.model3d.loader.Model3D;
import com.model3d.loader.client.render.RenderTestModelEntity;
import com.model3d.loader.common.registry.ModEntities;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Client startup wiring, on the <b>mod</b> event bus.
 *
 * <h2>The bus distinction, which is not a style choice</h2>
 * Forge has two buses and they are not interchangeable:
 * <pre>
 *   Bus.FORGE -> MinecraftForge.EVENT_BUS        (game events: ticks, rendering, level load)
 *   Bus.MOD   -> FMLJavaModLoadingContext's bus  (lifecycle and registration events, IModBusEvent)
 * </pre>
 * {@code @Mod.EventBusSubscriber}'s {@code bus} attribute selects <b>one</b> of them, and Forge's
 * {@code AutomaticEventSubscriber} registers the class on that bus and no other:
 * {@code busTarget.bus().get().register(...)}. A class annotated {@code bus = FORGE} therefore never
 * sees an {@code IModBusEvent}, because those are posted on the mod bus.
 *
 * <p>This class originally declared {@code bus = FORGE} while subscribing to
 * {@code EntityRenderersEvent.RegisterRenderers}, {@code FMLClientSetupEvent} and
 * {@code RegisterClientReloadListenersEvent} - all three of which are {@code IModBusEvent}s. None of
 * them fired. The entity's renderer was never registered, so {@code EntityRenderDispatcher} held a
 * null entry for it and the game crashed on the first frame that tried to draw the entity:
 * <pre>
 *   java.lang.NullPointerException: Cannot invoke
 *     "EntityRenderer.render(...)" because "entityrenderer" is null
 *     at EntityRenderDispatcher.render(EntityRenderDispatcher.java:127)
 * </pre>
 * The failure was silent at every earlier stage - the command ran, the entity spawned, the server
 * accepted it - and only appeared once a client looked at it.
 *
 * <p>Its javadoc used to claim the annotation "routes these itself, because they are
 * {@code IModBusEvent}s". That sentence was the bug: the classification was right and the
 * conclusion was backwards, since being an {@code IModBusEvent} is precisely why a {@code FORGE}
 * subscriber cannot receive it.
 *
 * <p>{@link ClientEvents} holds the forge-bus subscriptions. The split is by bus, because a class
 * can only be on one.
 */
@Mod.EventBusSubscriber(modid = Model3D.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {
    }

    /**
     * Registers the test entity's renderer.
     *
     * <p>The provider lambda cannot be evaluated earlier than this event: it bakes the pig model
     * layer, and {@code EntityRendererProvider.Context#bakeLayer} needs the model set that only
     * exists once the client has started.
     *
     * <p>Logged at INFO because "was the renderer registered" is the question that took a crash
     * report to answer: without this line there is no way to tell a missing registration from a
     * renderer that threw.
     */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.TEST_MODEL.get(), RenderTestModelEntity::new);
        Model3D.LOGGER.info("Model3D: registered the entity renderer for {} (test model when one is "
                + "attached, vanilla pig model otherwise)", ModEntities.TEST_MODEL.getId());
    }

    /**
     * Client startup. Nothing to build here - the shader program is compiled on first draw, when a
     * GL context is guaranteed - so this reports that the client side is live, which is the first
     * line to look for when a model does not render.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Says what the live path is: CPU skinning into a streamed VBO, drawn with this mod's
        // model_cpu shader pair. It used to announce "GPU skinning, 128 joint matrices", which
        // described the retired custom-GL renderer and sent every later investigation the wrong way.
        Model3D.LOGGER.info("Model3D: client setup complete; model rendering enabled "
                + "(CPU skinning, streamed VBO, GLSL 150 core model_cpu shader pair)");
    }

    /**
     * Hooks a listener that invalidates the model cache when the pack set is reloaded.
     *
     * <p>Registered after the shader reload listeners so that by the time this runs, the reload that
     * replaced the packs has finished - the flag it sets is consumed on the next frame, on the
     * render thread, where the {@code glDelete*} calls are legal.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ModelCacheReloadListener());
    }

    /**
     * Marks the model cache stale when resource packs reload.
     *
     * <p>Runs its work in {@code apply} rather than {@code prepare} so it happens on the render
     * thread after the new packs are in place. The actual {@code glDelete*} is deferred by one frame
     * through {@link ClientModelManager#onResourceReload()} - a flag, not a GL call, because a reload
     * listener that touches GL directly is one refactor away from running off-thread.
     */
    private static final class ModelCacheReloadListener implements PreparableReloadListener {

        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                              ProfilerFiller preparationsProfiler,
                                              ProfilerFiller reloadProfiler,
                                              Executor backgroundExecutor,
                                              Executor gameExecutor) {
            // The barrier's argument is the previous listener's stage result; this listener is
            // stateless and has nothing to consume, so any completed value will do.
            return barrier.wait(CompletableFuture.completedFuture(null)).thenRunAsync(() -> {
                ClientModelManager.get().onResourceReload();
                Model3D.LOGGER.info("Model3D: resource reload detected; model GPU resources will "
                        + "be rebuilt on the next frame");
            }, gameExecutor);
        }
    }
}
