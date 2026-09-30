package com.model3d.loader.api;

import com.model3d.loader.Model3D;
import com.model3d.loader.scene.ModelScene;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loaded, shared model plus its reference count - what the cache hands out and what the API
 * accepts.
 *
 * <p>Why a handle rather than the {@link ModelScene} directly: the scene is immutable and shared
 * by every entity using the model, but the GPU resources hanging off it are not free, and
 * nothing in the scene knows how many users it has. The count lives here, and releasing the
 * last reference is what lets the resource layer evict the model.
 *
 * <p>What "evict" frees is the parsed scene from the cache - not GPU buffers per handle. The client
 * frees its GPU resources in bulk, when the model folders change and on a level unload or resource
 * reload (see {@code ClientModelManager#onResourcesReloaded}); the count here decides when a model
 * stops being pinned, not when a VAO is deleted.
 *
 * <p>Not thread-safe by itself beyond the counter; the intended usage is on the client's render
 * thread, with acquire/release from the same thread that owns the client model manager.
 */
public final class ModelHandle {

    private final String name;
    private final ModelScene scene;
    private final AtomicInteger references = new AtomicInteger(1);

    /** Where the model was loaded from, for diagnostics: a pack path, a jar entry, a file. */
    private final String sourceDescription;

    /**
     * The descriptor the model was loaded with, never null.
     *
     * <p>Carried on the handle rather than re-read by each consumer: the descriptor decides the
     * scale, the pivot and the auto animation, and every one of those is consumed somewhere
     * different (the command, the renderer, the spawn logic). Re-reading it in three places
     * would mean three chances to disagree about what the model's settings are.
     */
    private final com.model3d.loader.resource.ModelDescriptor descriptor;

    /** Computed on first request; the scene never changes, so neither does this. */
    private volatile ModelSummary summary;

    public ModelHandle(String name, ModelScene scene, String sourceDescription) {
        this(name, scene, sourceDescription, com.model3d.loader.resource.ModelDescriptor.defaults());
    }

    public ModelHandle(String name, ModelScene scene, String sourceDescription,
                       com.model3d.loader.resource.ModelDescriptor descriptor) {
        this.name = name;
        this.scene = scene;
        this.sourceDescription = sourceDescription;
        this.descriptor = descriptor == null
                ? com.model3d.loader.resource.ModelDescriptor.defaults() : descriptor;
    }

    /** The model's {@code model.json} settings; defaults when the model has no descriptor. */
    public com.model3d.loader.resource.ModelDescriptor descriptor() {
        return descriptor;
    }

    /** The requested model name, as a user would type it. */
    public String name() {
        return name;
    }

    /**
     * The model's own inventory - nodes, meshes, triangles, materials, animations and size.
     *
     * <p>This is the supported way to inspect a loaded model; it is computed once, on first request,
     * and the scene is immutable, so the same value is returned afterwards. See {@link ModelSummary}
     * for why the API publishes this instead of the scene.
     */
    public ModelSummary summary() {
        ModelSummary cached = summary;
        if (cached == null) {
            cached = ModelSummary.of(scene);
            summary = cached;
        }
        return cached;
    }

    /** The animation names this model provides, in file order. */
    public List<String> animationNames() {
        return summary().animationNames();
    }

    /** The model's longest bounding-box axis in the file's own units; 0 when it has no geometry. */
    public float longestExtent() {
        return summary().longestExtent();
    }

    /**
     * The parsed scene - <b>internal shape, not part of the API contract</b>.
     *
     * <p>Its type lives in a package this mod does not publish, so a caller cannot name it: no
     * import, no field, no parameter. It is here because the render path needs it. Use
     * {@link #summary()} for anything a caller wants to read or keep; a consumer that reaches
     * through this method is coupled to internals that may change between releases.
     */
    public ModelScene scene() {
        return scene;
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    /** Number of live references, starting at 1 for the cache's own reference. */
    public int referenceCount() {
        return references.get();
    }

    /**
     * Adds a reference.
     *
     * @return this handle, for chaining at acquisition points
     */
    public ModelHandle acquire() {
        references.incrementAndGet();
        return this;
    }

    /**
     * Drops a reference.
     *
     * <p>The contract is exactly "this release dropped the last reference": a second release of the
     * same handle is a bug in the caller, and it is reported as one rather than answered with the
     * same {@code true} - which is what {@code <= 0} used to do, inviting a caller that frees GPU
     * resources on the boolean to free them twice, or to evict a model another holder is still
     * rendering.
     *
     * @return true when this release dropped the last reference, i.e. the model may now be evicted
     */
    public boolean release() {
        int remaining = references.decrementAndGet();
        if (remaining < 0) {
            Model3D.LOGGER.error("Model3D: {} released more times than it was acquired (count {}) - "
                    + "the releasing caller has a double-release bug", name, remaining);
            return false;
        }
        return remaining == 0;
    }

    @Override
    public String toString() {
        return "ModelHandle('" + name + "' refs=" + references.get() + " " + summary() + ")";
    }
}
