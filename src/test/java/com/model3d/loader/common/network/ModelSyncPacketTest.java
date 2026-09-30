package com.model3d.loader.common.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decode-side tests for the one packet this mod sends.
 *
 * <h2>Why a decoder needs its own test</h2>
 * {@code decode} runs on a netty thread, before {@code handle} gets to look at the side or the
 * sender, so everything it does with an attacker-controlled number happens on the server's network
 * threads. The animation list was read as {@code int count = buffer.readVarInt(); new
 * ArrayList<>(count);} with no bound at all, and the channel was registered through the
 * five-argument {@code registerMessage}, whose direction is {@code Optional.empty()} - no direction
 * check - so a client that completes the mod's handshake could send it to a server. A five-byte
 * payload could therefore ask a dedicated server to pre-allocate an array of up to
 * {@code Integer.MAX_VALUE} entries, and a negative count made the constructor throw.
 *
 * <p>The counts used here are deliberately large but survivable: the pre-fix code allocated them
 * successfully and then failed on the read, so this test fails as an assertion rather than taking
 * the whole test worker down with an {@code OutOfMemoryError}.
 */
class ModelSyncPacketTest {

    /** Built through {@code tryParse}: the two-argument constructor is deprecated-for-removal. */
    private static final ResourceLocation MODEL = ResourceLocation.tryParse("model3d:animated_test");

    private static FriendlyByteBuf packetBuffer(int animationCount) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(42);              // entity id
        buffer.writeBoolean(true);           // has a model
        buffer.writeResourceLocation(MODEL);
        buffer.writeFloat(4.0f);             // scale
        buffer.writeUtf("spin");             // animation name
        buffer.writeBoolean(true);           // looping
        buffer.writeVarInt(animationCount);  // the field under test
        return buffer;
    }

    @Test
    @DisplayName("a well-formed packet round-trips")
    void roundTrips() {
        ModelSyncPacket sent = new ModelSyncPacket(42, MODEL, 4.0f, "spin", true,
                List.of("spin", "bob"));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ModelSyncPacket.encode(sent, buffer);
        ModelSyncPacket received = ModelSyncPacket.decode(buffer);

        assertEquals(sent.entityId(), received.entityId());
        assertEquals(sent.modelId(), received.modelId());
        assertEquals(sent.scale(), received.scale());
        assertEquals(sent.animation(), received.animation());
        assertEquals(List.of("spin", "bob"), received.animations());
    }

    @Test
    @DisplayName("an oversized animation count is refused before anything is allocated for it")
    void refusesOversizedCount() {
        FriendlyByteBuf buffer = packetBuffer(1_000_000);
        assertThrows(DecoderException.class, () -> ModelSyncPacket.decode(buffer),
                "a count no real model can produce must be rejected on the count itself");
    }

    @Test
    @DisplayName("a negative animation count is refused, not turned into an exception from ArrayList")
    void refusesNegativeCount() {
        FriendlyByteBuf buffer = packetBuffer(-5);
        assertThrows(DecoderException.class, () -> ModelSyncPacket.decode(buffer));
    }

    @Test
    @DisplayName("the bound admits a model with a realistic number of animations")
    void admitsRealisticCounts() {
        // The bound must not be so tight that a busy model cannot be described: one name per
        // animation in the file, and files with a few dozen animations are ordinary.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(42);
        buffer.writeBoolean(false);   // no model attached
        buffer.writeFloat(1.0f);
        buffer.writeUtf("");
        buffer.writeBoolean(false);
        buffer.writeVarInt(32);
        for (int i = 0; i < 32; i++) {
            buffer.writeUtf("animation_" + i);
        }
        ModelSyncPacket packet = ModelSyncPacket.decode(buffer);
        assertEquals(32, packet.animations().size());
        assertEquals("animation_31", packet.animations().get(31));
    }
}
