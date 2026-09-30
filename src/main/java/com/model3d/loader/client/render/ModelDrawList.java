package com.model3d.loader.client.render;

import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;

import java.util.ArrayList;
import java.util.List;

/**
 * Every primitive of a model paired with the node transform it hangs off - the flat list the
 * renderer actually walks.
 *
 * <h2>Why a walk instead of a flat mesh list</h2>
 * A glTF mesh is attached to a <i>node</i>, and that node's world transform is what places it. An
 * animation that rotates the nose wheel node moves the nose wheel mesh; a renderer that drew every
 * mesh in model space would show the animation only on skinned meshes and leave every rigid part
 * welded to the origin. It also has to be a walk rather than a map, because one mesh may be
 * referenced by several nodes and must then be drawn once per node.
 *
 * <h2>Skinned primitives ignore the node transform</h2>
 * A skinned primitive's vertices are already in skin space: the joint matrices in
 * {@code AnimationPlayer.apply} compose each joint's world transform with its inverse bind matrix,
 * which produces a model-space result. Multiplying that by the node transform as well would
 * double-apply it and fling the mesh away from the body - so {@link Drawable#usesNodeTransform()}
 * is false for skinned primitives and the renderer skips it.
 *
 * <p>Immutable after construction. Built on the render thread, once per {@code ModelHandle}.
 */
public final class ModelDrawList {

    /** One drawable primitive plus the placement it needs. */
    public static final class Drawable {

        private final ModelPrimitive primitive;
        private final ModelMesh mesh;
        private final String label;
        private final int nodeIndex;
        private final boolean usesNodeTransform;

        Drawable(ModelPrimitive primitive, ModelMesh mesh, String label, int nodeIndex,
                 boolean usesNodeTransform) {
            this.primitive = primitive;
            this.mesh = mesh;
            this.label = label;
            this.nodeIndex = nodeIndex;
            this.usesNodeTransform = usesNodeTransform;
        }

        public ModelPrimitive primitive() {
            return primitive;
        }

        public ModelMesh mesh() {
            return mesh;
        }

        /** Diagnostic name: {@code "mesh[2] 'Fuselage' primitive 0 (node 'Body')"}. */
        public String label() {
            return label;
        }

        public int nodeIndex() {
            return nodeIndex;
        }

        /** True when the node's world transform must be applied to this primitive. */
        public boolean usesNodeTransform() {
            return usesNodeTransform;
        }
    }

    private final Drawable[] drawables;
    private final int vertexCount;
    private final int indexCount;
    private final int triangleCount;
    private final int jointCount;

    private ModelDrawList(Drawable[] drawables, int vertexCount, int indexCount, int triangleCount,
                          int jointCount) {
        this.drawables = drawables;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.triangleCount = triangleCount;
        this.jointCount = jointCount;
    }

    /**
     * Walks {@code scene}'s node tree and collects every drawable primitive.
     *
     * @param skinned true when the scene declares a skin, in which case the first skin's joint
     *                count is reported and primitives carrying joint data skip the node transform
     */
    public static ModelDrawList build(ModelScene scene) {
        List<Drawable> collected = new ArrayList<>();
        int vertexCount = 0;
        int indexCount = 0;
        int triangleCount = 0;

        boolean[] visited = new boolean[scene.nodeCount()];
        for (int root : scene.rootNodes()) {
            if (root >= 0 && root < visited.length && !visited[root]) {
                walk(scene, root, collected, visited);
            }
        }

        // A mesh attached to no node is not addressable by the scene graph, but it is still
        // geometry the file asked to have drawn; hanging it off the model origin is the only
        // interpretation that does not silently drop it.
        for (int meshIndex = 0; meshIndex < scene.meshes().length; meshIndex++) {
            ModelMesh mesh = scene.meshes()[meshIndex];
            boolean attached = false;
            for (ModelNode node : scene.nodeTemplates()) {
                if (node.meshIndex() == meshIndex) {
                    attached = true;
                    break;
                }
            }
            if (!attached) {
                addMesh(scene, mesh, meshIndex, -1, "unattached", collected);
            }
        }

        for (Drawable drawable : collected) {
            vertexCount += drawable.primitive().vertexCount();
            indexCount += drawable.primitive().indexCount();
            triangleCount += drawable.primitive().indexCount() / 3;
        }
        int jointCount = scene.isSkinned() ? scene.skins()[0].jointCount() : 0;
        return new ModelDrawList(collected.toArray(new Drawable[0]), vertexCount, indexCount,
                triangleCount, jointCount);
    }

    private static void walk(ModelScene scene, int nodeIndex, List<Drawable> out, boolean[] visited) {
        visited[nodeIndex] = true;
        ModelNode node = scene.nodeTemplates()[nodeIndex];
        if (node.meshIndex() >= 0 && node.meshIndex() < scene.meshes().length) {
            addMesh(scene, scene.meshes()[node.meshIndex()], node.meshIndex(), nodeIndex,
                    node.name(), out);
        }
        for (ModelNode child : node.children()) {
            if (!visited[child.index()]) {
                walk(scene, child.index(), out, visited);
            }
        }
    }

    private static void addMesh(ModelScene scene, ModelMesh mesh, int meshIndex, int nodeIndex,
                                String nodeName, List<Drawable> out) {
        ModelPrimitive[] primitives = mesh.primitives();
        for (int i = 0; i < primitives.length; i++) {
            ModelPrimitive primitive = primitives[i];
            // Joint indices in the primitive address the skin's joint array, so a primitive with
            // joint data is skinned and its placement comes from the matrices, not from the node.
            boolean skinned = primitive.isSkinned() && scene.isSkinned();
            String label = "mesh[" + meshIndex + "] '" + mesh.name() + "' primitive " + i
                    + " (node '" + nodeName + "')";
            out.add(new Drawable(primitive, mesh, label, nodeIndex, !skinned));
        }
    }

    public Drawable[] drawables() {
        return drawables;
    }

    public int drawableCount() {
        return drawables.length;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /**
     * Total indices across every drawable: exactly how many vertices {@code ModelCpuRenderPath.fill}
     * writes, because the fill loop expands one vertex per index.
     *
     * <p>This is the number the streaming buffer must be sized from. The unique vertex count is not:
     * a cube is 8 vertices and 36 indices, so sizing from vertices alone overflows on the first
     * frame - see {@code CpuVertexWriterCapacityTest}.
     */
    public int indexCount() {
        return indexCount;
    }

    public int triangleCount() {
        return triangleCount;
    }

    /** Joint count of the scene's skin, or 0 when unskinned. */
    public int jointCount() {
        return jointCount;
    }

    /** True when any drawable carries joint data. */
    public boolean isSkinned() {
        for (Drawable drawable : drawables) {
            if (!drawable.usesNodeTransform()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ModelDrawList(drawables=" + drawables.length + " vertices=" + vertexCount
                + " triangles=" + triangleCount + " joints=" + jointCount + ")";
    }
}
