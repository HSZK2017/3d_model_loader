package com.model3d.loader.api;

import net.minecraft.resources.ResourceLocation;

/**
 * An entity that carries a model loaded by this API.
 *
 * <h2>What implementing this buys you</h2>
 * Everything else is the loader's job. Once your entity implements this interface, the client
 * renderer creates a {@link ModelInstance} for it on first draw, advances the animation each frame,
 * applies the scale and pivot resolved from the model's descriptor, swaps the instance when the
 * attached model changes, releases it when the entity is removed, and re-sends the model description
 * to a player who starts tracking the entity.
 *
 * <h2>What the loader needs</h2>
 * Only these getters. Nothing about the entity's internals is assumed: the values are usually synced
 * data set on the server, but where they come from is the implementer's business.
 *
 * <p>This interface exists because the loader used to name a concrete test entity in its client
 * render path, which made the API unusable by any other mod: the renderer could only ever draw that
 * one entity type. The entity now lives in a separate test mod and is just the first implementation.
 *
 * <p>All methods are called from the client render thread except {@link #getId()}, {@link #modelId()},
 * {@link #modelScale()}, {@link #animationName()}, {@link #isAnimationLooping()} and
 * {@link #animationNames()}, which {@link ModelSync} reads on the server when it describes the entity
 * to a client.
 */
public interface ModelCarrier {

    /** True when a model is attached. A carrier with no model renders as the mod's fallback. */
    boolean hasModel();

    /**
     * The attached model's id, or null when none is attached.
     *
     * <p>The id names a model folder, not a file: the loader resolves it through the resource
     * manager and the user's {@code config/3dmodels/} folder. See the README's resolution order.
     */
    ResourceLocation modelId();

    /**
     * The animation to play, or null to use the descriptor's {@code autoAnimation}.
     *
     * <p>Null is the common case and does not mean "no animation": a model whose descriptor names an
     * animation starts it without a second command.
     */
    String animationName();

    /** Whether the selected animation loops. Ignored when {@link #animationName()} is null. */
    boolean isAnimationLooping();

    /**
     * Scale in blocks per model unit, resolved on the server from the descriptor.
     *
     * <p>Zero or negative means "not set" and the loader falls back to {@link ModelScale}. A
     * legitimate 1.0 is respected - it is a real scale, not a sentinel.
     */
    float modelScale();

    /**
     * Animation names the attached model exposes, in file order; empty when none are known.
     *
     * <p>Sent to the client with the model description so a UI can offer the model's animations
     * without parsing the file. An implementation that has no such list may return an empty array.
     */
    String[] animationNames();

    /**
     * The entity's network id. {@code Entity} already implements this, so an entity carrier gets it
     * for free; it is here because {@link ModelSync} needs it to address the packet.
     */
    int getId();

    /**
     * Applies a model description sent by {@link ModelSync}. Called on the client, on the render
     * thread, when the packet arrives.
     *
     * <p>This is the receiving half of the wire contract, and it exists so the loader can apply a
     * description without knowing how a carrier stores its state: whether the values go into synced
     * entity data, a capability or plain fields is the implementer's business. A typical
     * implementation stores them and forwards them to whatever its getters above return.
     *
     * <p>Called for a carrier that the server has described, so an entity id lookup that finds no
     * carrier (already removed, or a different mod's entity) never reaches this method.
     *
     * @param modelId     the attached model, never null
     * @param scale       blocks per model unit, resolved on the server
     * @param animation   animation to play, or null for the descriptor's default
     * @param animationLoop whether that animation loops
     * @param animations  animation names the model exposes, in file order; may be empty
     */
    void applyModelDescription(ResourceLocation modelId, float scale, String animation,
                               boolean animationLoop, java.util.List<String> animations);
}
