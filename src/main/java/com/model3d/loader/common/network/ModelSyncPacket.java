package com.model3d.loader.common.network;

import com.model3d.loader.Model3D;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client description of a test entity's model.
 *
 * <p>Sent when a model is attached and when a player starts tracking the entity, so a client
 * that missed the original change still converges. Idempotent by construction: it replaces the
 * entity's model description wholesale rather than mutating it, so receiving it twice is
 * harmless and a lost packet is fixed by the next one.
 */
public final class ModelSyncPacket {

    /**
     * Largest animation list this packet will carry or accept.
     *
     * <p>A bound rather than a policy: files with a few dozen animations are ordinary, a few
     * hundred would be remarkable, and the decoder must not allocate on an unvalidated number. The
     * encoder clamps to it (and says so) so both ends agree even for an absurd file.
     */
    public static final int MAX_ANIMATIONS = 256;

    private final int entityId;
    private final boolean hasModel;
    private final ResourceLocation modelId;
    private final float scale;
    private final String animation;
    private final boolean animationLoop;
    private final List<String> animations;

    public ModelSyncPacket(int entityId, ResourceLocation modelId, float scale, String animation,
                           boolean animationLoop, List<String> animations) {
        this.entityId = entityId;
        this.hasModel = modelId != null;
        this.modelId = modelId;
        this.scale = scale;
        this.animation = animation == null ? "" : animation;
        this.animationLoop = animationLoop;
        this.animations = List.copyOf(animations);
    }

    public static void encode(ModelSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        // FriendlyByteBuf has no nullable-string write, so absence gets its own flag rather than
        // being encoded as an empty resource location - which would parse and then resolve to
        // nothing, the exact silent failure this flag prevents.
        buffer.writeBoolean(packet.hasModel);
        if (packet.hasModel) {
            buffer.writeResourceLocation(packet.modelId);
        }
        buffer.writeFloat(packet.scale);
        buffer.writeUtf(packet.animation);
        buffer.writeBoolean(packet.animationLoop);
        // Clamped rather than written as-is: the decoder refuses anything above the bound, and a
        // packet the receiver rejects would cost the whole description - model id, scale and
        // animation included - for the sake of a list that is only used to validate names.
        int count = Math.min(packet.animations.size(), MAX_ANIMATIONS);
        if (count != packet.animations.size()) {
            Model3D.LOGGER.warn("Model3D: entity {} declares {} animations; syncing the first {}",
                    packet.entityId, packet.animations.size(), MAX_ANIMATIONS);
        }
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeUtf(packet.animations.get(i));
        }
    }

    public static ModelSyncPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        boolean hasModel = buffer.readBoolean();
        ResourceLocation modelId = hasModel ? buffer.readResourceLocation() : null;
        float scale = buffer.readFloat();
        String animation = buffer.readUtf();
        boolean animationLoop = buffer.readBoolean();
        int count = buffer.readVarInt();
        // The count is attacker-controlled and this runs on a netty thread, before handle() can look
        // at the side or the sender: an unbounded ArrayList<>(count) let a five-byte payload ask for
        // up to Integer.MAX_VALUE entries, and a negative count threw IllegalArgumentException out of
        // the decoder. Bounded here, with the allocation after the check.
        if (count < 0 || count > MAX_ANIMATIONS) {
            throw new DecoderException("ModelSyncPacket: animation count " + count
                    + " is outside 0.." + MAX_ANIMATIONS);
        }
        List<String> animations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            animations.add(buffer.readUtf());
        }
        return new ModelSyncPacket(entityId, modelId, scale, animation, animationLoop, animations);
    }

    /**
     * Applies the description on the client.
     *
     * <p>The client-only half is behind {@link DistExecutor#unsafeRunWhenOn}: this class is
     * loaded on a dedicated server too, and a direct reference to {@code Minecraft} here would
     * fail the whole channel registration on the server - a failure that looks like a networking
     * bug and is actually a side-loading one.
     */
    public static void handle(ModelSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientModelSyncHandler.apply(packet)));
        context.setPacketHandled(true);
    }

    public int entityId() {
        return entityId;
    }

    public ResourceLocation modelId() {
        return modelId;
    }

    public float scale() {
        return scale;
    }

    public String animation() {
        return animation;
    }

    public boolean animationLoop() {
        return animationLoop;
    }

    public List<String> animations() {
        return animations;
    }

    @Override
    public String toString() {
        return "ModelSyncPacket(entity=" + entityId + " model=" + modelId + " scale=" + scale
                + " animations=" + animations + ")";
    }

    /** Logged once per distinct entity so a repeated sync does not become log spam. */
    static void logApplied(ModelSyncPacket packet) {
        Model3D.LOGGER.info("Model3D: entity {} -> model={} ({} animations), animation='{}' loop={}",
                packet.entityId, packet.modelId, packet.animations.size(), packet.animation,
                packet.animationLoop);
    }
}
