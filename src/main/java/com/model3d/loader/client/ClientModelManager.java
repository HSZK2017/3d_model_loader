package com.model3d.loader.client;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelHandle;
import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.api.ModelScale;
import com.model3d.loader.client.render.ClientTextureResolver;
import com.model3d.loader.client.render.VanillaModelRenderer;
import com.model3d.loader.api.ModelCarrier;
import com.model3d.loader.resource.ModelLoadService;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The client's single owner of loaded models: the {@link ModelHandle} per model id, the
 * {@link ModelInstance} per entity, and the GPU resources behind both.
 *
 * <h2>When instances are advanced</h2>
 * Once per frame, from {@code ClientSetup}'s {@code RenderLevelStageEvent} handler at
 * {@code Stage.AFTER_ENTITIES} - not from a client tick.
 *
 * <p>The reason is that the animation clock and the pose that the renderer reads must be produced
 * in the same frame as the draw, or the two disagree: a tick-based advance would leave the pose
 * one tick stale and, worse, would sample zero times on a frame that renders more than once (a
 * screenshot, a portal, the inventory's entity preview). Advancing at the end of the entity stage
 * also means the work happens on the render thread, where {@link ModelInstance} requires it, and
 * after vanilla has drawn its own entities so this mod's instances cannot perturb them.
 *
 * <h2>When GPU resources are freed</h2>
 * On a resource reload and when the level unloads. Both events arrive on the render thread, which
 * is where {@code glDelete*} must happen. Both are handled by
 * {@link VanillaModelRenderer#releaseGpuResources()}, which also deletes the shader program - a reload
 * may have changed the GLSL sources, so keeping the old program would make the reload silently do
 * nothing.
 *
 * <p>Render thread only. Everything in this class asserts that where GL is involved.
 */
public final class ClientModelManager {

    private static final ClientModelManager INSTANCE = new ClientModelManager();

    public static ClientModelManager get() {
        return INSTANCE;
    }

    /**
     * One entity's live instance, the handle it holds a reference to, and the settings that were
     * last applied to it.
     *
     * <p>The handle reference is owned here. {@code ModelHandle.release()} is what lets the
     * resource layer evict a model, so a manager that acquired on demand and never released would
     * pin every model it had ever seen - the reference count would climb by one per frame and no
     * model would ever be evictable.
     */
    private static final class Entry {
        ResourceLocation modelId;
        ModelHandle handle;
        ModelInstance instance;
        String appliedAnimation;
        boolean appliedLoop;
        float appliedScale = Float.NaN;
        boolean broken;

        Entry(ResourceLocation modelId, ModelHandle handle, ModelInstance instance) {
            this.modelId = modelId;
            this.handle = handle;
            this.instance = instance;
        }

        /** Drops this entry's reference to its model, if it holds one. */
        void releaseHandle() {
            if (handle != null) {
                ModelLoadService.INSTANCE.releaseClient(modelId);
                handle = null;
            }
        }
    }

    /** Material texture-path resolution; the GPU state lives behind {@link #vanillaRenderer}. */
    private final ClientTextureResolver textureResolver = new ClientTextureResolver();

    /** The renderer that draws: CPU skinning into a streamed VBO, with this mod's own shader pair. */
    private final VanillaModelRenderer vanillaRenderer = new VanillaModelRenderer(textureResolver);

    /** The renderer that draws a model instance. */
    public VanillaModelRenderer vanillaRenderer() {
        return vanillaRenderer;
    }
    /** Keyed by entity id. Entities are not stable map keys; their ids are. */
    private final Map<Integer, Entry> entries = new HashMap<>();
    /** Model ids that failed to load, so a broken model is reported once and not re-parsed. */
    private final Set<ResourceLocation> failedLoads = new HashSet<>();

    /** Set by the reload listener, consumed on the render thread. */
    private volatile boolean pendingReload;

    private float lastPartialTick = -1.0f;

    private ClientModelManager() {
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /**
     * Acquires a reference to a loaded model, or null when it is absent or failed to parse.
     *
     * <p><b>Every non-null return adds a reference that the caller must give back</b> through
     * {@link #releaseHandle(ResourceLocation)}. Prefer {@link #instanceFor}, which manages that
     * lifetime itself; this method exists for callers that want the {@code ModelScene} directly.
     *
     * <p>A thin cache over {@link ModelLoadService}, which already keys one handle per model id
     * and reference-counts it. This method adds no second cache on purpose: two caches for one
     * fact is how a model ends up loaded twice with two sets of GPU buffers.
     *
     * <p>A failed id is remembered as failed. Without that, an entity carrying a broken model
     * would re-attempt the parse on every frame it is visible - and the parse logs an error - so
     * one bad file would spam the log at 60 Hz and stall the frame retrying.
     */
    @Nullable
    public ModelHandle handleFor(@Nullable ResourceLocation modelId) {
        if (modelId == null || failedLoads.contains(modelId)) {
            return null;
        }
        ModelHandle handle = ModelLoadService.INSTANCE.acquireClient(resourceManager(), modelId);
        if (handle == null) {
            failedLoads.add(modelId);
            Model3D.LOGGER.warn("Model3D: no model at {}; entities asking for it will use the "
                    + "pig fallback until the next resource reload", modelId);
        }
        return handle;
    }

    /** Gives back a reference taken by {@link #handleFor}. */
    /**
     * Evicts one entity's entry: drops its handle reference and, when it was the last instance of
     * that model, frees the model's GPU resources.
     *
     * <p>The mesh is keyed by model id and shared by every entity using that model, so freeing it
     * unconditionally would delete the stream buffer another entity is still drawing from. The check
     * therefore runs against the entries that remain, which is why callers remove the entry from the
     * map <b>before</b> calling this.
     *
     * <p>Without this, a per-model VAO and VBO lived until a resource reload or a level unload: the
     * model folder is a drop-in directory, so a session that cycles through many models leaks one
     * buffer per model it has ever drawn. Nothing in the scene knows how many entities still use it -
     * the count lives in the cache, which is why the release belongs here.
     */
    private void evict(Entry entry) {
        entry.releaseHandle();
        ResourceLocation modelId = entry.modelId;
        if (modelId == null) {
            return;
        }
        for (Entry remaining : entries.values()) {
            if (modelId.equals(remaining.modelId)) {
                return;
            }
        }
        vanillaRenderer.releaseModel(modelId);
    }
    public void releaseHandle(ResourceLocation modelId) {
        if (modelId != null) {
            ModelLoadService.INSTANCE.releaseClient(modelId);
        }
    }

    /**
     * The live instance for {@code entity}, created on first request.
     *
     * <p>Returns null when the entity has no model, the model is absent, or loading it failed -
     * the caller renders the fallback in every one of those cases.
     *
     * <p>Not advanced here: {@link #advance} owns the clock, so a renderer that asks for the
     * instance twice in one frame does not advance the animation twice.
     */
    @Nullable
    public ModelInstance instanceFor(net.minecraft.world.entity.Entity entity) {
        // ModelCarrier, not a concrete entity: this used to name the mod's own test entity, which
        // meant the render path could only ever draw that one type and the API was unusable by any
        // other mod. The interface is now the only thing the loader knows about a carrier.
        if (!(entity instanceof ModelCarrier carrier) || !carrier.hasModel()) {
            return null;
        }
        ResourceLocation modelId = carrier.modelId();
        if (modelId == null) {
            return null;
        }
        Entry existing = entries.get(entity.getId());
        if (existing != null && existing.modelId.equals(modelId)) {
            return existing.broken ? null : existing.instance;
        }
        // A different model on an existing entity: swap the instance and move the handle reference.
        if (existing != null) {
            entries.remove(entity.getId());
            evict(existing);
        }
        ModelHandle handle = handleFor(modelId);
        if (handle == null) {
            return null;
        }
        ModelInstance instance = new ModelInstance(handle.scene(), handle.name());
        Entry entry = new Entry(modelId, handle, instance);
        try {
            configure(instance, handle, carrier, entry);
        } catch (RuntimeException e) {
            // instanceFor is called from the entity renderer, so a malformed animation state or a
            // broken scene must not escape into the render loop. Dropping the handle makes the
            // entity fall back to the pig marker, which is visible and diagnosable.
            entry.releaseHandle();
            Model3D.LOGGER.error("Model3D: configuring entity {} with model {} failed; using the "
                    + "pig marker", entity.getId(), modelId, e);
            return null;
        }
        entries.put(entity.getId(), entry);
        Model3D.LOGGER.info("Model3D: created instance for entity {} -> {} ({})", entity.getId(),
                modelId, instance);
        return entry.broken ? null : instance;
    }

    /**
     * Applies the entity's animation selection and scale to {@code instance}, but only when a
     * value actually changed.
     *
     * <p>Restarting the animation every frame is the obvious bug here: {@code play} resets the
     * clock, so a model would freeze on its first keyframe while looking like it was playing
     * (the state says "playing") - a failure that is very hard to read off the code.
     */
    private void configure(ModelInstance instance, ModelHandle handle, ModelCarrier entity,
                           Entry entry) {
        if (handle == null) {
            return;
        }
        String wanted = entity.animationName();
        if (wanted == null || wanted.isEmpty()) {
            // No explicit choice: the descriptor's auto animation, which is what makes a bare
            // /testmodel loader spawn an animated model with no second command.
            wanted = handle.descriptor().autoAnimation();
        }
        boolean loop = entity.animationName() == null || entity.animationName().isEmpty()
                ? handle.descriptor().autoAnimationLoop() : entity.isAnimationLooping();

        if (wanted != null && !wanted.equals(entry.appliedAnimation)) {
            if (instance.play(wanted, loop)) {
                entry.appliedAnimation = wanted;
                entry.appliedLoop = loop;
                Model3D.LOGGER.info("Model3D: entity {} playing animation '{}' (loop={})",
                        entity.getId(), wanted, loop);
            } else if (entry.appliedAnimation == null) {
                // Only report a miss once per instance; the entity's animation list may still be
                // in flight from the server, and re-reporting would spam.
                entry.appliedAnimation = "";
                Model3D.LOGGER.warn("Model3D: entity {} asked for animation '{}' but {} provides "
                                + "{}; no animation will play", entity.getId(), wanted,
                        handle.name(), instance.animationNames());
            }
        } else if (wanted == null && entry.appliedAnimation == null) {
            entry.appliedAnimation = "";
        }

        // The entity carries the scale resolved on the server from the descriptor, so it is
        // authoritative. Zero or negative means "never set" (ModelCarrier#modelScale), and only that is
        // replaced by ModelScale: a legitimate 1.0 - a model authored in blocks, or a descriptor whose
        // targetBlocks works out to one block per unit - used to be indistinguishable from "unset" and
        // was silently replaced by the normalisation.
        float scale = entity.modelScale();
        if (!(scale > 0.0f)) {
            scale = ModelScale.forHandle(handle);
        }
        if (scale != entry.appliedScale) {
            entry.appliedScale = scale;
            instance.setScale(scale);
            float[] pivot = handle.descriptor().pivot();
            instance.setPivot(pivot[0], pivot[1], pivot[2]);
            instance.setYawOffset((float) Math.toRadians(handle.descriptor().yawOffsetDegrees()));
            Model3D.LOGGER.info("Model3D: entity {} model '{}' scale={} (blocks per model unit), "
                            + "pivot=({}, {}, {}) yawOffset={}deg, model longestExtent={}",
                    entity.getId(), handle.name(), scale, pivot[0], pivot[1], pivot[2],
                    handle.descriptor().yawOffsetDegrees(), handle.scene().longestExtent());
        }
    }

    // ------------------------------------------------------------------
    // Per-frame
    // ------------------------------------------------------------------

    /**
     * Advances every live instance's animation clock by {@code deltaSeconds}.
     *
     * <p>Called once per frame from the {@code RenderLevelStageEvent} hook. Instances for entities
     * that are gone are dropped here rather than needing an unload hook per entity.
     */
    public void advance(float deltaSeconds) {
        RenderSystem.assertOnRenderThread();
        if (pendingReload) {
            pendingReload = false;
            onResourcesReloaded();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        float dt = deltaSeconds;
        // A frame longer than a second is a stall (loading, a world change), not gameplay; feeding
        // it to the clock would jump every animation forward by that much in one step.
        if (!(dt > 0.0f) || dt > 1.0f) {
            dt = 0.0f;
        }

        Iterator<Map.Entry<Integer, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Entry> mapped = iterator.next();
            Entry entry = mapped.getValue();
            net.minecraft.world.entity.Entity entity = minecraft.level.getEntity(mapped.getKey());
            if (entity == null || entity.isRemoved()) {
                // Remove from the map first: evict() frees the mesh only when no other entry is
                // still drawing that model, and it has to see the state after this removal.
                iterator.remove();
                evict(entry);
                continue;
            }
            if (entry.broken) {
                continue;
            }
            if (entity instanceof ModelCarrier carrier && carrier.hasModel()) {
                // peek, not acquire: this entry already holds the manager's reference, and taking a
                // second one every frame would pin the model forever.
                ModelHandle handle = ModelLoadService.INSTANCE.peekClient(carrier.modelId());
                if (handle != null) {
                    configure(entry.instance, handle, carrier, entry);
                }
            }
            try {
                entry.instance.update(dt);
            } catch (RuntimeException e) {
                // An animation that throws must not take down the frame. The instance is marked
                // broken and its handle reference released, so the entity falls back to the pig
                // marker - visible, and clearly "this model is not rendering" rather than a
                // half-animated wreck or a crash loop.
                entry.broken = true;
                // iterator.remove(), not entries.remove(key): this is a fail-fast HashMap inside the
                // loop started above, so a direct removal makes the next next() throw
                // ConcurrentModificationException out of the render-stage handler - the one path
                // that is supposed to contain a broken model rather than break the frame.
                iterator.remove();
                evict(entry);
                Model3D.LOGGER.error("Model3D: animating entity {} with model {} failed; falling "
                        + "back to the pig marker", mapped.getKey(), entry.modelId, e);
            }
        }
    }

    public void setLastPartialTick(float partialTick) {
        this.lastPartialTick = partialTick;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Marks that the pack set changed; the GL teardown happens on the next frame. */
    public void onResourceReload() {
        pendingReload = true;
    }

    /**
     * Frees every GL resource and forgets every handle.
     *
     * <p>Render thread only. Called from {@link #advance} when a reload was flagged, and directly
     * on level unload.
     *
     * <p>Entry handle references are deliberately <b>not</b> released here: this path runs
     * immediately before {@link ModelLoadService#clearClient()}, which drops the cache's own
     * reference to every handle and evicts them. Releasing them here as well would double-release
     * and could drive a count negative, so the whole set is invalidated in one place instead - and
     * {@link #pendingReload} is cleared last, so an entry that somehow outlives this call cannot
     * release a handle that no longer exists.
     */
    public void onResourcesReloaded() {
        RenderSystem.assertOnRenderThread();
        int failed = failedLoads.size();
        // The live path owns its own GL objects: one VAO+VBO per model id, the compiled program, and
        // every embedded texture registered with the texture manager. Without this call nothing
        // frees them - a session would keep every mesh it had drawn, and a reload that changed the
        // GLSL would keep the old program.
        vanillaRenderer.releaseGpuResources();
        entries.clear();
        failedLoads.clear();
        pendingReload = false;
        var evicted = ModelLoadService.INSTANCE.clearClient();
        Model3D.LOGGER.info("Model3D: client model cache reset; {} handle(s) evicted, {} failed "
                + "id(s) forgotten", evicted.size(), failed);
    }

    /** Level unload: same teardown as a reload. */
    public void onLevelUnload() {
        RenderSystem.assertOnRenderThread();
        onResourcesReloaded();
    }

    private static ResourceManager resourceManager() {
        return Minecraft.getInstance().getResourceManager();
    }
}
