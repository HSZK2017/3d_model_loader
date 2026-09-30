package com.model3d.loader.common.network;

import com.model3d.loader.common.entity.TestModelEntity;
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
        if (!(entity instanceof TestModelEntity testEntity)) {
            return;
        }
        testEntity.setModel(packet.modelId(), packet.scale(), packet.animations());
        testEntity.setAnimation(packet.animation().isEmpty() ? null : packet.animation(),
                packet.animationLoop());
        ModelSyncPacket.logApplied(packet);
    }
}
