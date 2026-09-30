package com.model3d.loader.api;

import com.model3d.loader.Model3D;
import com.model3d.loader.common.network.ModelSyncPacket;
import com.model3d.loader.common.network.NetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * Sends a {@link ModelCarrier}'s model description to a client.
 *
 * <h2>Why a model description is synced at all</h2>
 * The model file itself is never sent: both sides have the same packs and the same
 * {@code config/3dmodels/} folder, so the client can load the geometry itself. What the client cannot
 * know is <i>which</i> model an entity carries, how big it should be, and which animation to play -
 * that is server-authoritative state, and this is the one packet that carries it.
 *
 * <h2>When to call it</h2>
 * <ul>
 *   <li>When the model is attached, for the players who can already see the entity.</li>
 *   <li>When a player <b>starts tracking</b> the entity. This is the case that is easy to miss: the
 *       entity was described when it spawned, which happened before that player was connected, so
 *       without this they render the fallback until something else re-syncs. The mod calls it for you
 *       from its start-tracking hook, so a carrier only needs this method if it syncs for another
 *       reason - a model changed by command, say.</li>
 * </ul>
 *
 * <p>Server side only, and harmless to call for a carrier with no model: nothing is sent.
 */
public final class ModelSync {

    private ModelSync() {
    }

    /**
     * Describes {@code carrier} to {@code player}.
     *
     * <p>Addressing one player rather than broadcasting is deliberate: the packet is only meaningful
     * to a client that can see the entity, and a broadcast to everyone would scale with player count
     * for no benefit.
     */
    public static void send(ModelCarrier carrier, ServerPlayer player) {
        ResourceLocation modelId = carrier.modelId();
        if (modelId == null) {
            // Nothing attached: stay silent rather than sending a packet that says "no model", which
            // would be indistinguishable on the client from a description that has not arrived yet.
            return;
        }
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ModelSyncPacket(carrier.getId(), modelId, carrier.modelScale(),
                        carrier.animationName(), carrier.isAnimationLooping(),
                        List.of(carrier.animationNames())));
        Model3D.LOGGER.debug("Model3D: described entity {} as model {} to {}",
                carrier.getId(), modelId, player.getGameProfile().getName());
    }
}
