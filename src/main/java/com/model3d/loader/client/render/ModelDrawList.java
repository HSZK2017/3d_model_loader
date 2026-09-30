package com.model3d.loader.client.render;

import com.model3d.loader.api.ModelInstance;
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
 * <h2>Visibility is decided here, per instance</h2>
 * {@link #build(ModelScene, ModelInstance)} skips a node whose instance visibility override hides
 * it, <b>and does not descend into it</b>: visibility is inherited, so a hidden gear-bay door takes
 * its children with it, and hiding a whole node keeps its geometry out of the list entirely rather
 * than leaving it there for a renderer to skip. That is why the filter belongs to the walk and not
 * to the submission loop - the walk is where "and its descendants" is cheap, and it is where the
 * decision costs no allocation (it is one boolean read per node; see {@code ModelNode#isVisible}).
 *
 * <p>The scene's own node templates are never touched: the overrides live on the instance's copy of
 * the tree, which is the whole reason two entities can share one {@link ModelScene} and still differ.
 * A caller that passes no instance - {@link #build(ModelScene)} - gets every primitive, which is what
 * the tools and the sizing tests want.
 *
 * <p>Immutable after construction. Built on the render thread, once per instance per frame by
 * {@code VanillaModelRenderer}.
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
     * Walks {@code scene}'s node tree and collects every drawable primitive, ignoring per-instance
     * visibility: the scene's own rest data is all this reads.
     *
     * <p>This is the form the tools and the sizing tests use - {@code CpuVertexWriterCapacityTest}
     * asserts against the whole model's arithmetic, hidden or not. Rendering goes through
     * {@link #build(ModelScene, ModelInstance)} so a hidden part is never submitted.
     */
    public static ModelDrawList build(ModelScene scene) {
        return build(scene, null);
    }

    /**
     * As {@link #build(ModelScene)}, but skipping the parts {@code instance} has hidden - a hidden
     * node's own primitives and every descendant's.
     *
     * <p>Structure comes from the scene's templates (a node's index, mesh and children are load-time
     * data), while <b>visibility comes from the instance's node tree</b>, read by index: the two trees
     * have the same length and the same indices by construction - see {@code ModelScene#instantiate()}
     * - so no mapping is needed.
     *
     * <p>An instance that does not belong to {@code scene}, or has no tree, is ignored and the full
     * list is returned. That is a caller bug, but this runs once per entity per frame on the render
     * thread: degrading to the pre-visibility behaviour (draw everything) is recoverable, while an
     * exception here takes the frame down. A <i>hidden</i> node, by contrast, must fail the other way -
     * so the two are not conflated: only a mismatch of tree and scene disables filtering.
     */
    public static ModelDrawList build(ModelScene scene, ModelInstance instance) {
        ModelNode[] instanceNodes = instanceNodes(instance, scene);
        List<Drawable> collected = new ArrayList<>();
        int vertexCount = 0;
        int indexCount = 0;
        int triangleCount = 0;

        boolean[] visited = new boolean[scene.nodeCount()];
        for (int root : scene.rootNodes()) {
            if (root >= 0 && root < visited.length && !visited[root]) {
                walk(scene, instanceNodes, root, collected, visited);
            }
        }

        // A mesh attached to no node is not addressable by the scene graph, but it is still
        // geometry the file asked to have drawn; hanging it off the model origin is the only
        // interpretation that does not silently drop it. It has no node, so no node's visibility
        // can speak for it either way and it is always collected.
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

    /**
     * The instance's node tree, when it is that of {@code scene} and can answer visibility; null
     * otherwise. See {@link #build(ModelScene, ModelInstance)} for why a mismatch degrades instead of
     * throwing.
     */
    private static ModelNode[] instanceNodes(ModelInstance instance, ModelScene scene) {
        if (instance == null || instance.scene() != scene) {
            return null;
        }
        ModelNode[] nodes = instance.nodes();
        if (nodes == null || nodes.length != scene.nodeCount()) {
            return null;
        }
        return nodes;
    }

    private static void walk(ModelScene scene, ModelNode[] instanceNodes, int nodeIndex,
                             List<Drawable> out, boolean[] visited) {
        visited[nodeIndex] = true;
        ModelNode instanceNode = instanceNodes == null ? null : instanceNodes[nodeIndex];
        if (instanceNode != null && !instanceNode.isVisible()) {
            // Hidden by this node's own override or by an ancestor's, and not walked further: a
            // hidden node hides everything under it. Returning here is also what keeps the check
            // O(1) per node instead of one ancestor walk per primitive.
            return;
        }
        ModelNode node = scene.nodeTemplates()[nodeIndex];
        if (node.meshIndex() >= 0 && node.meshIndex() < scene.meshes().length) {
            addMesh(scene, scene.meshes()[node.meshIndex()], node.meshIndex(), nodeIndex,
                    node.name(), out);
        }
        for (ModelNode child : node.children()) {
            if (!visited[child.index()]) {
                walk(scene, instanceNodes, child.index(), out, visited);
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
