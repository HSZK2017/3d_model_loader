package com.model3d.loader.common.network;

import com.model3d.loader.api.ModelCarrier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-only half of {@link ModelSyncPacket}.
 *
 * <p>Split into its own class so the packet's decoder can be shared with a dedicated server
 * without that server ever loading a class that mentions {@code Minecraft}. The packet handler
 * routes here through {@code DistExecutor.unsafeRunWhenOn}, which is what keeps the reference
 * out of the server's call graph and out of the server's class-loading path.
 *
 * <p>The description is applied to whatever entity the packet names, through {@link ModelCarrier},
 * so this class knows no concrete entity type. It used to name this mod's own test entity, which
 * made the whole wire contract unusable by any other mod - the packet could only ever describe that
 * one type.
 */
@OnlyIn(Dist.CLIENT)
final class ClientModelSyncHandler {

    private ClientModelSyncHandler() {
    }

    static void apply(ModelSyncPacket packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // A packet for a level that has already been unloaded is normal during a dimension
            // change or a disconnect; there is nothing to update and nothing to warn about.
            return;
        }
        Entity entity = level.getEntity(packet.entityId());
        if (!(entity instanceof ModelCarrier carrier)) {
            // Not a carrier this API knows about: an entity that has already been removed, or one
            // from a mod that does not use the API. Silence is correct - warning here would fire on
            // every dimension change.
            return;
        }
        carrier.applyModelDescription(packet.modelId(), packet.scale(),
                packet.animation().isEmpty() ? null : packet.animation(),
                packet.animationLoop(), packet.animations());
        ModelSyncPacket.logApplied(packet);
    }
}
