package com.model3d.loader.scene;

/**
 * One mesh: a named group of primitives that share a node attachment.
 *
 * <p>A glTF mesh with two materials becomes one {@code ModelMesh} with two primitives, which
 * is why primitives - not meshes - are the unit a renderer draws.
 */
public final class ModelMesh {

    private final String name;
    private final ModelPrimitive[] primitives;

    public ModelMesh(String name, ModelPrimitive[] primitives) {
        if (primitives == null || primitives.length == 0) {
            throw new IllegalArgumentException("Mesh '" + name + "' has no primitives");
        }
        this.name = name;
        this.primitives = primitives;
    }

    public String name() {
        return name;
    }

    public ModelPrimitive[] primitives() {
        return primitives;
    }

    public int primitiveCount() {
        return primitives.length;
    }

    /** True when any primitive carries joint data. */
    public boolean isSkinned() {
        for (ModelPrimitive primitive : primitives) {
            if (primitive.isSkinned()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ModelMesh('" + name + "' primitives=" + primitives.length + ")";
    }
}
