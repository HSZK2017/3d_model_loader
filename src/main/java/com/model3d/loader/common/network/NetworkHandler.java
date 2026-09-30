package com.model3d.loader.common.network;

import com.model3d.loader.Model3D;
import com.model3d.loader.util.Ids;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * The mod's network channel.
 *
 * <p>One channel, one packet. The mod has no other server-authoritative state worth syncing:
 * everything about how a model <i>looks</i> is derived on the client from the model file, which
 * both sides already have.
 */
public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Ids.of(Model3D.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private NetworkHandler() {
    }

    /** Registers the packet. Called from mod construction, before any entity can exist. */
    public static void register() {
        int id = 0;
        // The six-argument overload, with the direction stated. The five-argument one passes
        // Optional.empty(), which Forge's NetworkHooks compares as "no expectation" - so the channel
        // accepted this packet from either side, and a client that completed the handshake could
        // send a server-directed payload the mod never meant to receive. The packet is server to
        // client only (it describes an entity's model to that entity's viewers).
        CHANNEL.registerMessage(id++, ModelSyncPacket.class, ModelSyncPacket::encode,
                ModelSyncPacket::decode, ModelSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        Model3D.LOGGER.debug("Model3D: network channel registered (protocol {})", PROTOCOL_VERSION);
    }
}
