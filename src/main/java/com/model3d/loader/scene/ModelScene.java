package com.model3d.loader.scene;

import com.model3d.loader.math.Mat4;

import java.util.ArrayList;
import java.util.List;

/**
 * A loaded model: immutable load-time data plus a factory for the mutable per-instance pose.
 *
 * <p>The split matters. {@link #nodes()} built by a parser are the <b>rest pose templates</b>
 * - they are read-only after load and may be shared by every entity using the model, because
 * what an animation actually mutates is a per-instance copy produced by {@link #instantiate()}.
 * Two Su-30s flying different loops will otherwise fight over one node tree, and the symptom
 * (both aircraft snapping to whichever one was animated last) reads like a network bug.
 *
 * <p>Units and axes are those of the source file. glTF and OBJ are both Y-up, right-handed,
 * but nothing guarantees a metre or a Minecraft block, so the renderer scales by
 * {@link #normalizationScale()} to bring the model's largest extent down to a sane block size.
 */
public final class ModelScene {

    private final String name;

    /** Root nodes in scene declaration order. */
    private final int[] rootNodes;

    private final ModelNode[] nodeTemplates;
    private final ModelMesh[] meshes;
    private final ModelMaterial[] materials;
    private final ModelSkin[] skins;
    private final List<ModelAnimation> animations;

    /**
     * Images carried inside the model file, addressable through {@link ModelImage#SCHEME}.
     *
     * <p>Empty for a model whose textures are all sibling files, which is the OBJ case and the
     * text-glTF case. Never null, so consumers can iterate without a null check.
     */
    private final List<ModelImage> embeddedImages;

    /** Bounding box over all meshes in rest pose: {@code [minX,minY,minZ,maxX,maxY,maxZ]}. */
    private final float[] bounds;

    /** Longest axis of {@link #bounds}; 0 when the model has no geometry. */
    private final float longestExtent;

    private final String sourceDescription;

    public ModelScene(String name,
                      int[] rootNodes,
                      ModelNode[] nodeTemplates,
                      ModelMesh[] meshes,
                      ModelMaterial[] materials,
                      ModelSkin[] skins,
                      List<ModelAnimation> animations,
                      float[] bounds,
                      String sourceDescription) {
        this(name, rootNodes, nodeTemplates, meshes, materials, skins, animations, bounds,
                sourceDescription, List.of());
    }

    public ModelScene(String name,
                      int[] rootNodes,
                      ModelNode[] nodeTemplates,
                      ModelMesh[] meshes,
                      ModelMaterial[] materials,
                      ModelSkin[] skins,
                      List<ModelAnimation> animations,
                      float[] bounds,
                      String sourceDescription,
                      List<ModelImage> embeddedImages) {
        this.name = name;
        this.rootNodes = rootNodes;
        this.nodeTemplates = nodeTemplates;
        this.meshes = meshes;
        this.materials = materials;
        this.skins = skins;
        this.animations = List.copyOf(animations);
        this.embeddedImages = List.copyOf(embeddedImages == null ? List.of() : embeddedImages);
        this.bounds = bounds.clone();
        float extentX = bounds[3] - bounds[0];
        float extentY = bounds[4] - bounds[1];
        float extentZ = bounds[5] - bounds[2];
        this.longestExtent = Math.max(extentX, Math.max(extentY, extentZ));
        this.sourceDescription = sourceDescription;
    }

    public String name() {
        return name;
    }

    /** Indices of this scene's root nodes. */
    public int[] rootNodes() {
        return rootNodes;
    }

    /**
     * Rest-pose node templates. Read-only after load; do not animate these.
     *
     * <p>One asymmetry worth knowing, because it is invisible from the signatures and a parser
     * author tripped over it once: {@code children} lists on templates are populated <b>by the
     * parser</b> as it builds its own node objects ({@code parents.get(i).children().add(child)}).
     * This class never derives them from {@link ModelNode#parentIndex()}, so templates whose
     * children were never linked have correct parent indices and empty child lists - and walking
     * them from the roots then yields only root world transforms.
     * {@link #instantiate()} <b>does</b> rebuild {@code children} from {@code parentIndex}, so
     * every per-instance tree is correct whatever the templates look like. That is why the mod
     * animates instantiations and never the templates: see {@link #instantiate()}'s javadoc for
     * the concurrency reason, which is the other half of the same rule.
     */
    public ModelNode[] nodeTemplates() {
        return nodeTemplates;
    }

    public int nodeCount() {
        return nodeTemplates.length;
    }

    public ModelMesh[] meshes() {
        return meshes;
    }

    public ModelMaterial[] materials() {
        return materials;
    }

    public ModelSkin[] skins() {
        return skins;
    }

    public List<ModelAnimation> animations() {
        return animations;
    }

    /**
     * Clone of the rest-pose bounds, {@code [minX, minY, minZ, maxX, maxY, maxZ]} in model units.
     */
    public float[] bounds() {
        return bounds.clone();
    }

    /** Longest axis of {@link #bounds()}, in model units. 0 for an empty model. */
    public float longestExtent() {
        return longestExtent;
    }

    /** Where this model came from, for log lines: a resource path, a jar entry, a file path. */
    public String sourceDescription() {
        return sourceDescription;
    }

    public boolean isAnimated() {
        return !animations.isEmpty();
    }

    public boolean isSkinned() {
        return skins.length > 0;
    }

    /**
     * Images carried inside the model file, in file order; empty when every texture is a sibling
     * file. Referenced from a material through {@link ModelImage#SCHEME}.
     */
    public List<ModelImage> embeddedImages() {
        return embeddedImages;
    }

    /** The embedded image a material's texture path refers to, or null for a file reference. */
    public ModelImage embeddedImage(String texturePath) {
        return ModelImage.find(embeddedImages, texturePath);
    }

    /** Finds an animation by exact glTF name; returns null when absent. */
    public ModelAnimation animation(String animationName) {
        for (ModelAnimation animation : animations) {
            if (animation.name().equals(animationName)) {
                return animation;
            }
        }
        return null;
    }

    /**
     * Scale that maps this model's longest axis to {@code targetBlocks}. Returns 1 for an
     * empty or degenerate model rather than infinity.
     */
    public float scaleForTargetSize(float targetBlocks) {
        if (longestExtent <= 0.0f || !Float.isFinite(longestExtent)) {
            return 1.0f;
        }
        return targetBlocks / longestExtent;
    }

    /**
     * Deep-copies the node tree into an independent, animatable instance.
     *
     * <p>Children are relinked against the copied nodes; node indices are preserved so that
     * meshes, skins and animation channels - all of which refer to nodes by index - keep
     * pointing at the right node without any remapping.
     *
     * <p>The copy starts with every node visible and no per-node overrides: those (see
     * {@link ModelNode#hasOverrides()}) are per-instance state, and a new instance must never inherit
     * them from the templates or from another instance - which is why the copies are built from the
     * templates' rest values rather than copied wholesale.
     */
    public ModelNode[] instantiate() {
        ModelNode[] copies = new ModelNode[nodeTemplates.length];
        for (int i = 0; i < nodeTemplates.length; i++) {
            ModelNode template = nodeTemplates[i];
            copies[i] = new ModelNode(template.index(), template.name(), template.parentIndex(),
                    template.meshIndex(), template.skinIndex(),
                    template.restTranslationArray(), template.restRotationArray(),
                    template.restScaleArray());
        }
        for (int i = 0; i < nodeTemplates.length; i++) {
            int parent = nodeTemplates[i].parentIndex();
            if (parent >= 0) {
                copies[parent].addChild(copies[i]);
            }
        }
        // Joint indices come from the skin, and a node may be a joint of at most one skin in a
        // well-formed file; the last skin that claims a node wins, which only matters for the
        // malformed case where two skins share joints.
        for (ModelSkin skin : skins) {
            int[] joints = skin.joints();
            for (int joint = 0; joint < joints.length; joint++) {
                int nodeIndex = joints[joint];
                if (nodeIndex >= 0 && nodeIndex < copies.length) {
                    copies[nodeIndex].setJointIndex(joint);
                }
            }
        }
        return copies;
    }

    /**
     * Recomputes world-space transforms for a node tree from {@code roots} down, in
     * parent-before-child order.
     *
     * <p>Ordering is not optional: a child's world transform is its parent's world transform
     * times its own local transform, so a node visited before its parent silently keeps last
     * frame's world matrix. That failure looks like one limb lagging a frame behind the body.
     *
     * <p>Writes {@link ModelNode#globalTransform()} on every node in the tree.
     */
    public static void updateWorldTransforms(ModelNode[] nodes, int[] roots) {
        for (int root : roots) {
            updateSubtree(nodes, root, null);
        }
    }

    /**
     * As {@link #updateWorldTransforms(ModelNode[], int[])}, using an internal reusable scratch
     * buffer so a full-tree recompute does not allocate per node.
     *
     * <p>This is the path the per-frame animation code should use: the allocating version produces
     * one {@code float[16]} and one {@code Mat4} per non-root node per frame, which for a
     * character-scale skeleton is a few hundred small objects every frame - a steady GC load for
     * no reason.
     *
     * <p>Still writes a fresh immutable {@link Mat4} per node, because {@link ModelNode} exposes
     * matrices immutably and callers hold them across the frame. What is avoided is the transient
     * {@code float[16]} from {@link Mat4#multiply}, which is the bulk of the garbage.
     *
     * <p>Not thread-safe: the scratch array is shared. Call this from one thread - for this mod,
     * the client render thread - which is where poses are sampled anyway.
     */
    public static void updateWorldTransformsInPlace(ModelNode[] nodes, int[] roots) {
        for (int root : roots) {
            updateSubtreeScratch(nodes, root, null);
        }
    }

    /** Reused across nodes within one recompute. See {@link #updateWorldTransformsInPlace}. */
    private static final float[] SCRATCH = new float[16];

    private static void updateSubtreeScratch(ModelNode[] nodes, int index, Mat4 parentTransform) {
        ModelNode node = nodes[index];
        node.updateLocalTransform();
        Mat4 global;
        if (parentTransform == null) {
            // A root's world transform is its local transform; no product, so no scratch needed.
            global = node.localTransform();
        } else {
            Mat4.mul(parentTransform.raw(), node.localTransform().raw(), SCRATCH);
            // Copy out: SCRATCH is reused by the next node, and a node's world transform must
            // outlive this call.
            global = new Mat4(SCRATCH.clone());
        }
        node.setGlobalTransform(global);
        List<ModelNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            updateSubtreeScratch(nodes, children.get(i).index(), global);
        }
    }

    /**
     * @param parentTransform the parent's world matrix, or null when the node is a root
     */
    private static void updateSubtree(ModelNode[] nodes, int index, Mat4 parentTransform) {
        ModelNode node = nodes[index];
        node.updateLocalTransform();
        Mat4 global = parentTransform == null
                ? node.localTransform()
                : parentTransform.multiply(node.localTransform());
        node.setGlobalTransform(global);
        List<ModelNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            updateSubtree(nodes, children.get(i).index(), global);
        }
    }

    /** Collects this scene's root nodes as objects; convenience for tests and tools. */
    public List<ModelNode> rootNodeObjects() {
        List<ModelNode> roots = new ArrayList<>(rootNodes.length);
        for (int index : rootNodes) {
            roots.add(nodeTemplates[index]);
        }
        return roots;
    }

    @Override
    public String toString() {
        return "ModelScene('" + name + "' nodes=" + nodeTemplates.length
                + " meshes=" + meshes.length + " materials=" + materials.length
                + " skins=" + skins.length + " animations=" + animations.size()
                + " embeddedImages=" + embeddedImages.size()
                + " longestExtent=" + longestExtent + ")";
    }
}
