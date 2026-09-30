package com.model3d.loader.common.event;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelCarrier;
import com.model3d.loader.api.ModelSync;
import com.model3d.loader.resource.ModelLoadService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge bus events, all on the game bus (server and client).
 *
 * <p>Everything here is server-authoritative lifecycle wiring: when the model index is built,
 * when it is invalidated, and when a client needs the description of a model it has just started
 * tracking. None of it touches the renderer.
 *
 * <p>Deliberately free of any concrete entity: the hooks are stated in terms of {@link ModelCarrier},
 * so a mod that uses this API gets the resync behaviour without this mod knowing what its entities
 * are. The command that spawns a carrier for testing lives in a separate test mod.
 */
@Mod.EventBusSubscriber(modid = Model3D.MOD_ID)
public final class CommonEvents {

    private CommonEvents() {
    }

    /**
     * Builds the model index once the server's resource packs are loaded.
     *
     * <p>Doing it here rather than on first use means the index is warm before any command runs,
     * and the "N models available" log line appears at boot - which is the single most useful
     * line when a model does not show up: either it is not in the list (a path problem) or it is
     * and the problem is downstream.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Folders first, so the very first scan already sees whatever the user has dropped in and
        // the "N models available" line below reflects it.
        ModelLoadService.INSTANCE.prepareModelFolders();
        ModelLoadService.INSTANCE.invalidateNameIndex();
        ModelLoadService.INSTANCE.knownServerModelNames(event.getServer().getResourceManager());
    }

    /**
     * Polls the model folders for changes once per second.
     *
     * <p>Hot reload is a tick poll rather than a {@code WatchService}. A watch service is
     * event-driven and catches changes faster, but it needs its own thread, it is unreliable on
     * network shares and inside editors that write-then-rename, and it fires for a file that is still
     * being written - so a poll that compares size and modification time is both simpler and, for
     * files a human drops in by hand, more reliable. {@code ModelLibrary.signature()} caches the walk
     * for a second, so this costs a comparison on most ticks.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ModelLoadService.INSTANCE.checkForChanges(false);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Parsed scenes are pure CPU data and hold no GL resources, but they are also large; a
        // fresh server should not inherit a previous world's cache, and a resource pack change
        // between worlds must not be served stale geometry.
        ModelLoadService.INSTANCE.clearServer();
    }

    /**
     * A datapack or resource reload can add or remove models, so the cached name index is no
     * longer evidence of anything.
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        ModelLoadService.INSTANCE.invalidateNameIndex();
    }

    /** Same invalidation for a resource reload, which arrives through the datapack sync event. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ModelLoadService.INSTANCE.invalidateNameIndex();
    }

    /**
     * Sends the tracked entity's model description to a player who has just started seeing it.
     *
     * <p>Without this, a player joining a world where a model-bearing entity already exists
     * renders the fallback until something else re-syncs the entity: the model description was sent
     * when the entity spawned, which happened before this player was connected.
     *
     * <p>Stated in terms of the API's own interface rather than any concrete entity, so a mod that
     * implements {@code ModelCarrier} gets this without writing an event handler - which is the
     * whole point of the API owning the wire contract.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ModelCarrier carrier
                && event.getEntity() instanceof ServerPlayer player) {
            ModelSync.send(carrier, player);
        }
    }
}
