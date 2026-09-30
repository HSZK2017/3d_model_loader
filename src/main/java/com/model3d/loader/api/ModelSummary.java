package com.model3d.loader.api;

import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a loaded model contains, as plain numbers.
 *
 * <h2>Why this exists</h2>
 * Everything a caller wants to say about a model - how many nodes it has, how big it is, what
 * animations it carries - used to be reachable only through {@link ModelHandle#scene()}, whose type
 * belongs to a package the API does not publish. A consumer could call it, but could not name the
 * result: no import, no field, no method parameter. Reading it meant {@code var} and inference,
 * which a compiler accepts and a reviewer cannot check, and which broke silently when the internal
 * shape changed.
 *
 * <p>So this is the API's own account of a model, and it is the supported way to inspect one. It is
 * a value: computed once, immutable, safe to hold and to pass around. The counts are the model file's
 * own inventory after parsing - a mesh with two primitives counts as one mesh and two primitives, and
 * {@code triangles} counts the primitives' index triples rather than their vertices.
 *
 * @param nodes          nodes in the model's node tree, including roots
 * @param meshes         mesh groups ({@code meshes[]} in glTF, {@code o}/{@code g} groups in OBJ)
 * @param primitives     drawable primitives across all meshes
 * @param triangles      index triples across all primitives
 * @param materials      materials, including the appended default when the file declares none
 * @param skins          skins ({@code skins[]}); the loader renders the first
 * @param images         images embedded in the model file
 * @param longestExtent  the model's longest bounding-box axis, in the file's own units; 0 when empty
 * @param skinned        whether the model carries skinning data
 * @param animations     animations in file order; empty when the model has none
 */
public record ModelSummary(int nodes, int meshes, int primitives, int triangles, int materials,
                           int skins, int images, float longestExtent, boolean skinned,
                           List<Animation> animations) {

    /** One animation, as much of it as a caller can act on without reading the keyframes. */
    public record Animation(String name, int tracks, float durationSeconds) {

        @Override
        public String toString() {
            return "'" + name + "' (" + tracks + " tracks, " + durationSeconds + "s)";
        }
    }

    /** Compact canonical constructor: the animation list is a value here, not a live view. */
    public ModelSummary {
        animations = animations == null
                ? List.of() : Collections.unmodifiableList(new ArrayList<>(animations));
    }

    /** True when the model carries at least one non-empty animation. */
    public boolean animated() {
        return !animations.isEmpty();
    }

    /** The animation names, in file order - the list a UI or a command offers. */
    public List<String> animationNames() {
        List<String> names = new ArrayList<>(animations.size());
        for (Animation animation : animations) {
            names.add(animation.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Builds the summary from a parsed scene.
     *
     * <p>Package-private on purpose: {@link ModelScene} is internal shape, and a public factory would
     * put it back into the API's surface - the exact problem this type exists to remove.
     */
    static ModelSummary of(ModelScene scene) {
        if (scene == null) {
            return new ModelSummary(0, 0, 0, 0, 0, 0, 0, 0.0f, false, List.of());
        }
        ModelMesh[] meshes = scene.meshes() == null ? new ModelMesh[0] : scene.meshes();
        int primitives = 0;
        int triangles = 0;
        for (ModelMesh mesh : meshes) {
            ModelPrimitive[] meshPrimitives = mesh == null ? null : mesh.primitives();
            if (meshPrimitives == null) {
                continue;
            }
            for (ModelPrimitive primitive : meshPrimitives) {
                if (primitive == null) {
                    continue;
                }
                primitives++;
                short[] indices = primitive.indices();
                triangles += indices == null ? 0 : indices.length / 3;
            }
        }
        List<ModelAnimation> sceneAnimations = scene.animations() == null
                ? List.of() : scene.animations();
        List<Animation> animations = new ArrayList<>(sceneAnimations.size());
        for (ModelAnimation animation : sceneAnimations) {
            if (animation == null || animation.isEmpty()) {
                continue;
            }
            animations.add(new Animation(animation.name(), animation.trackCount(),
                    animation.duration()));
        }
        return new ModelSummary(
                scene.nodeCount(),
                meshes.length,
                primitives,
                triangles,
                scene.materials() == null ? 0 : scene.materials().length,
                scene.skins() == null ? 0 : scene.skins().length,
                scene.embeddedImages() == null ? 0 : scene.embeddedImages().size(),
                scene.longestExtent(),
                scene.isSkinned(),
                animations);
    }
}
