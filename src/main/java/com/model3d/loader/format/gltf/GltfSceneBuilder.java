package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.math.Mat4;
import com.model3d.loader.scene.ModelAnimation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a {@link ModelScene} from a parsed glTF 2.0 JSON document. The core shared by
 * {@link GlbParser} and {@link GltfParser}, so the two containers cannot interpret a node, an
 * accessor or an animation differently.
 *
 * <p>The document is assumed to be JSON of the right shape; the <b>bytes</b> behind it are already
 * resolved (a GLB's BIN chunk, sibling {@code .bin} files, or inline data URIs), because that is the
 * only part the two containers disagree about.
 *
 * <h2>Decisions worth knowing</h2>
 * <ul>
 *   <li><b>Fail closed.</b> A primitive with no POSITION, an accessor pointing at a bufferView that
 *       does not exist, an index outside its vertex array, a material index with no material behind
 *       it - all of these are {@link ModelParseException}s naming the element. The alternative is a
 *       scene that loads, reports nothing, and draws nothing.</li>
 *   <li><b>Non-fatal gaps are logged, not swallowed.</b> Morph-target channels, textures whose bytes
 *       cannot be sliced out, texture-info {@code texCoord} selection above 0, and non-required
 *       extensions change appearance but not geometry, so they degrade with a warning instead of
 *       failing the model. An image <b>embedded</b> in the file is not a gap at all: it is sliced out
 *       and carried on the scene as a {@code ModelImage}, reachable through its reserved
 *       {@code embedded/<name>} path.</li>
 *   <li><b>Morph targets are not supported.</b> {@code ModelPrimitive} has no blend-shape storage, so
 *       {@code targets} is ignored and {@code target.path: "weights"} channels are skipped.</li>
 *   <li><b>A primitive is limited to 65536 vertices</b>, because {@code ModelPrimitive.indices} is a
 *       {@code short[]} whose widening view treats entries as unsigned 16-bit. A larger primitive is
 *       rejected by name rather than truncated.</li>
 * </ul>
 */
final class GltfSceneBuilder {

    /**
     * Extensions this loader implements on top of core glTF 2.0. Empty by design: every extension in
     * the wild either changes geometry (Draco, meshopt) - in which case the file cannot be read at
     * all and must be refused - or changes shading, in which case the core PBR values are a usable
     * approximation and are reported once per model.
     */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of();

    /** {@code ModelPrimitive.indices} is a {@code short[]}; see the class comment. */
    private static final int MAX_PRIMITIVE_VERTICES = 65536;

    /**
     * Weight sums within this of 1 are left alone; anything further off is rescaled. Quantized
     * (byte/short) weights commonly land ~1e-3 away after conversion, while float weights that were
     * authored correctly are exact, so this threshold separates "storage rounding" from "bad data".
     */
    private static final float WEIGHT_SUM_TOLERANCE = 1.0e-3f;

    private final JsonObject root;
    private final GltfBuffers buffers;
    private final AccessorReader accessors;
    private final int nodeCount;
    private final int meshCount;
    private final String sourceDescription;
    private final Set<String> ignoredExtensions = new LinkedHashSet<>();

    private GltfSceneBuilder(JsonObject root, GltfBuffers buffers, String sourceDescription)
            throws ModelParseException {
        this.root = root;
        this.buffers = buffers;
        this.sourceDescription = sourceDescription;
        JsonArray nodes = root.getArray("nodes");
        this.nodeCount = nodes == null ? 0 : nodes.size();
        JsonArray meshes = root.getArray("meshes");
        this.meshCount = meshes == null ? 0 : meshes.size();
        this.accessors = new AccessorReader(root, buffers);
    }

    static ModelScene build(JsonObject root, GltfBuffers buffers, String requestedName,
                            String sourceDescription) throws ModelParseException {
        return new GltfSceneBuilder(root, buffers, sourceDescription).buildScene(requestedName);
    }

    private ModelScene buildScene(String requestedName) throws ModelParseException {
        verifyVersion();
        verifyRequiredExtensions();
        collectIgnoredExtensions();

        // Images come first: both the materials and the scene need the same table, and a texture
        // embedded in the buffer has to be sliced out exactly once.
        ImageTable images = ImageTable.read(root, buffers);
        MaterialReader materialReader = new MaterialReader(root, images);
        MeshSet meshes = readMeshes(materialReader.declaredCount());
        ModelMaterial[] materials = materialReader.read(meshes.usedDefaultMaterial);
        ModelSkin[] skins = readSkins();
        ModelNode[] nodes = readNodes(skins.length);
        int[] roots = readRoots(nodes);
        List<ModelAnimation> animations = new AnimationReader(root, accessors, nodes.length).read();
        validateSkinning(nodes, meshes.meshes, skins);
        warnAboutIgnoredExtensions();
        images.warnUnusable(sourceDescription);

        float[] bounds = computeBounds(meshes.meshes);
        return new ModelScene(requestedName, roots, nodes, meshes.meshes, materials, skins, animations,
                bounds, sourceDescription, images.images());
    }

    // ------------------------------------------------------------------ document-level checks

    private void verifyVersion() throws ModelParseException {
        JsonObject asset = root.getObject("asset");
        if (asset == null) {
            throw ModelParseException.at(sourceDescription,
                    "no 'asset' object; a glTF file must declare asset.version");
        }
        String version = asset.getString("version");
        if (version == null) {
            throw ModelParseException.at(sourceDescription,
                    "asset has no 'version'; this reader implements glTF 2.0");
        }
        if (!version.startsWith("2")) {
            throw ModelParseException.at(sourceDescription, "asset.version '" + version
                    + "' is not glTF 2.0; this reader implements 2.0 only (glTF 1.0 uses a different "
                    + "scene, material and technique model)");
        }
    }

    private void verifyRequiredExtensions() throws ModelParseException {
        JsonArray required = root.getArray("extensionsRequired");
        if (required == null) {
            return;
        }
        for (int i = 0; i < required.size(); i++) {
            String extension = required.requireString(i);
            if (!SUPPORTED_EXTENSIONS.contains(extension)) {
                throw ModelParseException.at(sourceDescription, "extensionsRequired[" + i + "]: '"
                        + extension + "' is required by this file but not supported; loading it "
                        + "without the extension would silently change the model");
            }
        }
    }

    private void collectIgnoredExtensions() throws ModelParseException {
        JsonArray used = root.getArray("extensionsUsed");
        if (used == null) {
            return;
        }
        for (int i = 0; i < used.size(); i++) {
            String extension = used.requireString(i);
            if (!SUPPORTED_EXTENSIONS.contains(extension)) {
                ignoredExtensions.add(extension);
            }
        }
    }

    private void warnAboutIgnoredExtensions() {
        if (!ignoredExtensions.isEmpty()) {
            Model3D.LOGGER.warn("{}: ignoring {} non-required glTF extension(s) [{}]; materials that use "
                    + "them fall back to their core PBR values", sourceDescription,
                    ignoredExtensions.size(), String.join(", ", ignoredExtensions));
        }
    }

    // ------------------------------------------------------------------ meshes and primitives

    private MeshSet readMeshes(int declaredMaterials) throws ModelParseException {
        JsonArray meshes = root.getArray("meshes");
        if (meshes == null || meshes.isEmpty()) {
            throw ModelParseException.at(sourceDescription,
                    "the file declares no meshes, so there is nothing to draw");
        }
        ModelMesh[] out = new ModelMesh[meshes.size()];
        boolean usedDefaultMaterial = false;
        for (int i = 0; i < meshes.size(); i++) {
            JsonObject mesh = meshes.requireObject(i);
            String meshName = mesh.getString("name");
            String displayName = meshName == null ? "mesh[" + i + "]" : meshName;
            JsonArray primitives = mesh.getArray("primitives");
            if (primitives == null || primitives.isEmpty()) {
                throw ModelParseException.at("meshes[" + i + "]",
                        "no primitives; a mesh must draw something");
            }
            ModelPrimitive[] built = new ModelPrimitive[primitives.size()];
            for (int p = 0; p < primitives.size(); p++) {
                String element = "meshes[" + i + "] '" + displayName + "' primitive " + p;
                JsonObject primitive = primitives.requireObject(p);
                int materialIndex;
                if (primitive.has("material")) {
                    materialIndex = primitive.requireInt("material");
                    if (materialIndex < 0 || materialIndex >= declaredMaterials) {
                        throw ModelParseException.at(element, "material " + materialIndex
                                + " out of range (materials: " + declaredMaterials + ")");
                    }
                } else {
                    // The spec says a primitive without a material uses "the default material", which
                    // ModelPrimitive cannot express as "none" - so it is an explicit extra entry.
                    materialIndex = declaredMaterials;
                    usedDefaultMaterial = true;
                }
                built[p] = readPrimitive(primitive, element, materialIndex);
            }
            out[i] = new ModelMesh(displayName, built);
        }
        return new MeshSet(out, usedDefaultMaterial);
    }

    private ModelPrimitive readPrimitive(JsonObject primitive, String element, int materialIndex)
            throws ModelParseException {
        JsonObject attributes = primitive.getObject("attributes");
        if (attributes == null || attributes.isEmpty()) {
            throw ModelParseException.at(element, "no attributes; POSITION is required");
        }
        float[] positions = readAttribute(attributes, "POSITION", "VEC3", -1, true, element);
        int vertexCount = positions.length / 3;
        if (vertexCount == 0) {
            throw ModelParseException.at(element, "POSITION accessor has no elements");
        }
        if (vertexCount > MAX_PRIMITIVE_VERTICES) {
            throw ModelParseException.at(element, vertexCount + " vertices; this loader stores indices "
                    + "as 16-bit unsigned (ModelPrimitive.indices), so a primitive is limited to "
                    + MAX_PRIMITIVE_VERTICES);
        }
        float[] normals = readAttribute(attributes, "NORMAL", "VEC3", vertexCount, false, element);
        float[] uvs = readAttribute(attributes, "TEXCOORD_0", "VEC2", vertexCount, false, element);
        float[] tangents = readAttribute(attributes, "TANGENT", "VEC4", vertexCount, false, element);
        Joints joints = readJoints(attributes, vertexCount, element);

        int mode = primitive.getInt("mode", 4);
        int[] indexValues = readIndices(primitive, element, vertexCount, mode);
        short[] indices = new short[indexValues.length];
        for (int i = 0; i < indexValues.length; i++) {
            indices[i] = (short) indexValues[i];
        }
        return new ModelPrimitive(element, positions, normals, uvs, tangents, indices,
                joints == null ? null : joints.indices, joints == null ? null : joints.weights,
                materialIndex);
    }

    /**
     * Reads one vertex attribute. {@code expectedCount} of -1 means "this is the POSITION accessor;
     * it defines the vertex count"; a positive value is the count every other attribute must match.
     */
    private float[] readAttribute(JsonObject attributes, String name, String expectedType,
                                  int expectedCount, boolean required, String element)
            throws ModelParseException {
        if (!attributes.has(name)) {
            if (required) {
                throw ModelParseException.at(element, "missing required attribute " + name);
            }
            return null;
        }
        int accessorIndex = attributes.requireInt(name);
        String requester = element + " " + name;
        String type = accessors.typeOf(accessorIndex, requester);
        if (!type.equals(expectedType)) {
            throw ModelParseException.at(element, name + " accessor[" + accessorIndex + "] is " + type
                    + " but " + expectedType + " is required");
        }
        if (expectedCount >= 0) {
            int count = accessors.count(accessorIndex, requester);
            if (count != expectedCount) {
                throw ModelParseException.at(element, name + " accessor[" + accessorIndex + "] has "
                        + count + " elements but POSITION has " + expectedCount
                        + "; glTF attributes of one primitive are indexed by the same vertex array");
            }
        }
        return accessors.readFloats(accessorIndex, requester);
    }

    /**
     * Reads {@code JOINTS_0}/{@code WEIGHTS_0} and any further joint sets.
     *
     * <p>glTF allows more than four influences per vertex by adding {@code JOINTS_1}/{@code WEIGHTS_1}
     * and so on, while {@link ModelPrimitive} (and the renderer it feeds) supports exactly four. This
     * reader <b>keeps the four heaviest influences</b> across all sets and renormalizes them: the
     * alternative - refusing the file - would reject a perfectly loadable model over the fifth
     * influence, and the alternative of keeping the first four of {@code JOINTS_0} would drop the
     * strongest influence whenever an exporter sorted them differently.
     */
    private Joints readJoints(JsonObject attributes, int vertexCount, String element)
            throws ModelParseException {
        List<float[]> jointSets = new ArrayList<>(2);
        List<float[]> weightSets = new ArrayList<>(2);
        for (int set = 0; set < 16; set++) {
            String jointsName = "JOINTS_" + set;
            String weightsName = "WEIGHTS_" + set;
            boolean hasJoints = attributes.has(jointsName);
            boolean hasWeights = attributes.has(weightsName);
            if (!hasJoints && !hasWeights) {
                break;
            }
            if (hasJoints != hasWeights) {
                throw ModelParseException.at(element, (hasJoints ? jointsName : weightsName)
                        + " is present without its counterpart " + (hasJoints ? weightsName : jointsName));
            }
            jointSets.add(readAttribute(attributes, jointsName, "VEC4", vertexCount, true, element));
            weightSets.add(readAttribute(attributes, weightsName, "VEC4", vertexCount, true, element));
        }
        if (jointSets.isEmpty()) {
            return null;
        }

        float[] indices = new float[vertexCount * ModelPrimitive.MAX_JOINTS_PER_VERTEX];
        float[] weights = new float[vertexCount * ModelPrimitive.MAX_JOINTS_PER_VERTEX];
        boolean renormalized = false;
        boolean degenerate = false;
        float[] bestWeights = new float[ModelPrimitive.MAX_JOINTS_PER_VERTEX];
        float[] bestJoints = new float[ModelPrimitive.MAX_JOINTS_PER_VERTEX];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            for (int k = 0; k < ModelPrimitive.MAX_JOINTS_PER_VERTEX; k++) {
                bestWeights[k] = 0.0f;
                bestJoints[k] = 0.0f;
            }
            for (int set = 0; set < jointSets.size(); set++) {
                float[] setJoints = jointSets.get(set);
                float[] setWeights = weightSets.get(set);
                for (int c = 0; c < 4; c++) {
                    float weight = setWeights[vertex * 4 + c];
                    if (!Float.isFinite(weight)) {
                        throw ModelParseException.at(element, "WEIGHTS_" + set + " has a non-finite "
                                + "weight for vertex " + vertex);
                    }
                    if (weight <= 0.0f) {
                        continue;
                    }
                    insertByWeight(bestWeights, bestJoints, weight, setJoints[vertex * 4 + c]);
                }
            }
            float sum = bestWeights[0] + bestWeights[1] + bestWeights[2] + bestWeights[3];
            if (sum <= 0.0f) {
                // A vertex with no influence collapses toward the origin and looks like a broken
                // skeleton; binding it fully to joint 0 keeps it on the model instead.
                degenerate = true;
                bestWeights[0] = 1.0f;
                bestJoints[0] = 0.0f;
            } else if (Math.abs(sum - 1.0f) > WEIGHT_SUM_TOLERANCE) {
                renormalized = true;
                float inverse = 1.0f / sum;
                for (int k = 0; k < ModelPrimitive.MAX_JOINTS_PER_VERTEX; k++) {
                    bestWeights[k] *= inverse;
                }
            }
            System.arraycopy(bestWeights, 0, weights, vertex * 4, 4);
            System.arraycopy(bestJoints, 0, indices, vertex * 4, 4);
        }
        if (renormalized) {
            Model3D.LOGGER.debug("{}: renormalized vertex weights that did not sum to 1", element);
        }
        if (degenerate) {
            Model3D.LOGGER.warn("{}: at least one vertex has no joint influence at all; binding it to "
                    + "joint 0 so it stays attached to the model", element);
        }
        return new Joints(indices, weights);
    }

    /** Keeps the four heaviest influences, in descending weight order. */
    private static void insertByWeight(float[] weights, float[] joints, float weight, float joint) {
        int position = weights.length;
        for (int k = 0; k < weights.length; k++) {
            if (weight > weights[k]) {
                position = k;
                break;
            }
        }
        if (position == weights.length) {
            return;
        }
        for (int k = weights.length - 1; k > position; k--) {
            weights[k] = weights[k - 1];
            joints[k] = joints[k - 1];
        }
        weights[position] = weight;
        joints[position] = joint;
    }

    private int[] readIndices(JsonObject primitive, String element, int vertexCount, int mode)
            throws ModelParseException {
        int[] source;
        if (primitive.has("indices")) {
            int accessorIndex = primitive.requireInt("indices");
            source = accessors.readUnsignedScalars(accessorIndex, element + " indices");
            for (int i = 0; i < source.length; i++) {
                if (source[i] < 0 || source[i] >= vertexCount) {
                    throw ModelParseException.at(element, "index " + i + " of accessor[" + accessorIndex
                            + "] is " + source[i] + ", outside the " + vertexCount + " vertices");
                }
            }
        } else {
            // Non-indexed drawing is legal glTF and common for simple exporters; synthesizing the
            // implicit 0..n-1 sequence keeps one code path for mode conversion below.
            source = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                source[i] = i;
            }
        }

        switch (mode) {
            case 4:
                if (source.length % 3 != 0) {
                    throw ModelParseException.at(element, "mode TRIANGLES with " + source.length
                            + " indices, which is not a multiple of 3");
                }
                return source;
            case 5:
                return stripToTriangles(source, element);
            case 6:
                return fanToTriangles(source, element);
            default:
                throw ModelParseException.at(element, "mode " + mode + " (" + modeName(mode)
                        + ") is not supported; this loader draws triangles only (mode 4, or 5 and 6 "
                        + "which it converts to a triangle list)");
        }
    }

    /**
     * TRIANGLE_STRIP to triangle list. The winding flips on every other triangle (odd triangles are
     * swapped), and a triangle with two equal indices is dropped - exporters use those degenerate
     * triangles deliberately to stitch separate strips into one buffer, and keeping them would add
     * zero-area geometry.
     */
    private static int[] stripToTriangles(int[] strip, String element) throws ModelParseException {
        int triangles = Math.max(0, strip.length - 2);
        int[] out = new int[triangles * 3];
        int count = 0;
        for (int i = 0; i + 2 < strip.length; i++) {
            int a = strip[i];
            int b = strip[i + 1];
            int c = strip[i + 2];
            if (a == b || b == c || a == c) {
                continue;
            }
            if ((i & 1) == 0) {
                out[count++] = a;
                out[count++] = b;
                out[count++] = c;
            } else {
                out[count++] = b;
                out[count++] = a;
                out[count++] = c;
            }
        }
        if (count == 0) {
            throw ModelParseException.at(element, "mode TRIANGLE_STRIP with " + strip.length
                    + " indices produces no triangles");
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    /** TRIANGLE_FAN to triangle list: every triangle shares the first vertex. */
    private static int[] fanToTriangles(int[] fan, String element) throws ModelParseException {
        int triangles = Math.max(0, fan.length - 2);
        int[] out = new int[triangles * 3];
        int count = 0;
        for (int i = 1; i + 1 < fan.length; i++) {
            int a = fan[0];
            int b = fan[i];
            int c = fan[i + 1];
            if (a == b || b == c || a == c) {
                continue;
            }
            out[count++] = a;
            out[count++] = b;
            out[count++] = c;
        }
        if (count == 0) {
            throw ModelParseException.at(element, "mode TRIANGLE_FAN with " + fan.length
                    + " indices produces no triangles");
        }
        return count == out.length ? out : Arrays.copyOf(out, count);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case 0:
                return "POINTS";
            case 1:
                return "LINES";
            case 2:
                return "LINE_LOOP";
            case 3:
                return "LINE_STRIP";
            case 4:
                return "TRIANGLES";
            case 5:
                return "TRIANGLE_STRIP";
            case 6:
                return "TRIANGLE_FAN";
            default:
                return "unknown";
        }
    }

    // ------------------------------------------------------------------ nodes and scene roots

    /**
     * Reads {@code skins[]}. Done before nodes because a node's {@code skin} index has to be
     * range-checked while the node is being built, and joint indices only need the node <b>count</b>.
     */
    private ModelSkin[] readSkins() throws ModelParseException {
        JsonArray skins = root.getArray("skins");
        if (skins == null || skins.isEmpty()) {
            return new ModelSkin[0];
        }
        ModelSkin[] out = new ModelSkin[skins.size()];
        for (int i = 0; i < skins.size(); i++) {
            String element = "skins[" + i + "]";
            JsonObject skin = skins.requireObject(i);
            String name = skin.getString("name");
            JsonArray declaredJoints = skin.getArray("joints");
            if (declaredJoints == null || declaredJoints.isEmpty()) {
                throw ModelParseException.at(element, "no joints; a skin must name at least one joint "
                        + "node");
            }
            int[] joints = declaredJoints.toIntArray();
            for (int j = 0; j < joints.length; j++) {
                if (joints[j] < 0 || joints[j] >= nodeCount) {
                    throw ModelParseException.at(element, "joints[" + j + "] = " + joints[j]
                            + " out of range (nodes: " + nodeCount + ")");
                }
            }
            Mat4[] inverseBindMatrices;
            if (skin.has("inverseBindMatrices")) {
                int accessorIndex = skin.requireInt("inverseBindMatrices");
                inverseBindMatrices = accessors.readMat4s(accessorIndex, element);
                if (inverseBindMatrices.length != joints.length) {
                    throw ModelParseException.at(element, "inverseBindMatrices accessor[" + accessorIndex
                            + "] has " + inverseBindMatrices.length + " matrices but the skin has "
                            + joints.length + " joints");
                }
            } else {
                // Spec: an undefined inverseBindMatrices means identity for every joint.
                inverseBindMatrices = new Mat4[joints.length];
                Arrays.fill(inverseBindMatrices, Mat4.IDENTITY);
            }
            int skeleton = skin.getInt("skeleton", -1);
            if (skeleton >= nodeCount) {
                throw ModelParseException.at(element, "skeleton " + skeleton + " out of range (nodes: "
                        + nodeCount + ")");
            }
            out[i] = new ModelSkin(name == null ? "skin[" + i + "]" : name, joints, inverseBindMatrices,
                    skeleton);
        }
        return out;
    }

    private ModelNode[] readNodes(int skinCount) throws ModelParseException {
        JsonArray nodes = root.getArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw ModelParseException.at(sourceDescription,
                    "the file declares no nodes, so no mesh is placed in the scene");
        }
        int count = nodes.size();

        // Parents are resolved first: ModelNode takes its parent index as a final field, and a node
        // listed as a child by two different parents is a malformed file rather than a tie to break.
        int[] parents = new int[count];
        Arrays.fill(parents, -1);
        for (int i = 0; i < count; i++) {
            int[] children = nodes.requireObject(i).getIntArray("children");
            if (children == null) {
                continue;
            }
            for (int child : children) {
                if (child < 0 || child >= count) {
                    throw ModelParseException.at("nodes[" + i + "]", "child " + child
                            + " out of range (nodes: " + count + ")");
                }
                if (child == i) {
                    throw ModelParseException.at("nodes[" + i + "]", "lists itself as its own child");
                }
                if (parents[child] != -1) {
                    throw ModelParseException.at("nodes[" + i + "]", "node " + child
                            + " is already a child of node " + parents[child]);
                }
                parents[child] = i;
            }
        }
        for (int i = 0; i < count; i++) {
            int steps = 0;
            for (int ancestor = parents[i]; ancestor != -1; ancestor = parents[ancestor]) {
                if (++steps > count) {
                    throw ModelParseException.at("nodes[" + i + "]",
                            "the parent chain forms a cycle; the node would never be placed in "
                                    + "the scene");
                }
            }
        }

        ModelNode[] out = new ModelNode[count];
        for (int i = 0; i < count; i++) {
            JsonObject node = nodes.requireObject(i);
            String element = "nodes[" + i + "]";
            String name = node.getString("name");
            String displayName = name == null ? "node[" + i + "]" : name;
            float[] translation = { 0.0f, 0.0f, 0.0f };
            float[] rotation = { 0.0f, 0.0f, 0.0f, 1.0f };
            float[] scale = { 1.0f, 1.0f, 1.0f };
            float[] matrix = node.getFloatArray("matrix");
            if (matrix != null) {
                if (matrix.length != 16) {
                    throw ModelParseException.at(element, "matrix has " + matrix.length
                            + " floats; the spec requires 16 (column-major)");
                }
                // matrix wins over TRS when a file carries both: the spec forbids the combination, and
                // the matrix is the form that reproduces the exporter's intent exactly.
                TransformDecomposition.decompose(matrix, translation, rotation, scale,
                        element + " '" + displayName + "'");
            } else {
                copyVector(node, "translation", 3, translation, element);
                float[] declaredRotation = node.getFloatArray("rotation");
                if (declaredRotation != null) {
                    if (declaredRotation.length != 4) {
                        throw ModelParseException.at(element, "rotation has "
                                + declaredRotation.length + " components; the spec requires 4 (xyzw)");
                    }
                    System.arraycopy(normalizeQuaternion(declaredRotation, element, displayName), 0,
                            rotation, 0, 4);
                }
                copyVector(node, "scale", 3, scale, element);
            }

            int meshIndex = -1;
            if (node.has("mesh")) {
                meshIndex = node.requireInt("mesh");
                if (meshIndex < 0 || meshIndex >= meshCount) {
                    throw ModelParseException.at(element, "mesh " + meshIndex
                            + " out of range (meshes: " + meshCount + ")");
                }
            }
            int skinIndex = -1;
            if (node.has("skin")) {
                skinIndex = node.requireInt("skin");
                if (skinIndex < 0 || skinIndex >= skinCount) {
                    throw ModelParseException.at(element, "skin " + skinIndex
                            + " out of range (skins: " + skinCount + ")");
                }
                if (meshIndex < 0) {
                    throw ModelParseException.at(element, "has a skin but no mesh to skin");
                }
            }
            out[i] = new ModelNode(i, displayName, parents[i], meshIndex, skinIndex, translation,
                    rotation, scale);
        }
        // Relink children through the live list ModelNode exposes: ModelScene.updateWorldTransforms
        // and instantiate() both walk children(), and a template tree with empty child lists would
        // silently produce root-only world transforms.
        for (int i = 0; i < count; i++) {
            if (parents[i] >= 0) {
                out[parents[i]].children().add(out[i]);
            }
        }
        return out;
    }

    private static void copyVector(JsonObject node, String key, int expected, float[] target,
                                   String element) throws ModelParseException {
        float[] values = node.getFloatArray(key);
        if (values == null) {
            return;
        }
        if (values.length != expected) {
            throw ModelParseException.at(element, key + " has " + values.length + " components; the "
                    + "spec requires " + expected);
        }
        System.arraycopy(values, 0, target, 0, expected);
    }

    /**
     * Leaves a unit quaternion untouched and rescales one that is not: exporters round-trip
     * quaternions through float text and {@code Mat4.fromQuat} assumes unit length, where a 1% error
     * becomes a visible shear on everything below the node.
     */
    private static float[] normalizeQuaternion(float[] rotation, String element, String displayName) {
        float length = (float) Math.sqrt(rotation[0] * rotation[0] + rotation[1] * rotation[1]
                + rotation[2] * rotation[2] + rotation[3] * rotation[3]);
        if (length == 0.0f) {
            Model3D.LOGGER.warn("{} '{}': rotation is (0,0,0,0); using the identity rotation", element,
                    displayName);
            return new float[] { 0.0f, 0.0f, 0.0f, 1.0f };
        }
        if (Math.abs(length - 1.0f) <= 1.0e-4f) {
            return rotation;
        }
        return new float[] { rotation[0] / length, rotation[1] / length, rotation[2] / length,
                rotation[3] / length };
    }

    private int[] readRoots(ModelNode[] nodes) throws ModelParseException {
        JsonArray scenes = root.getArray("scenes");
        if (scenes != null && !scenes.isEmpty()) {
            int sceneIndex = root.getInt("scene", 0);
            if (sceneIndex < 0 || sceneIndex >= scenes.size()) {
                throw ModelParseException.at("scene", "index " + sceneIndex + " out of range (scenes: "
                        + scenes.size() + ")");
            }
            JsonObject scene = scenes.requireObject(sceneIndex);
            int[] declared = scene.getIntArray("nodes");
            if (declared != null && declared.length > 0) {
                for (int root : declared) {
                    if (root < 0 || root >= nodes.length) {
                        throw ModelParseException.at("scenes[" + sceneIndex + "]", "root node " + root
                                + " out of range (nodes: " + nodes.length + ")");
                    }
                    if (nodes[root].parentIndex() >= 0) {
                        Model3D.LOGGER.warn("scenes[{}]: node {} '{}' is a scene root but is also a "
                                + "child of node {}; its parent transform is ignored",
                                sceneIndex, root, nodes[root].name(), nodes[root].parentIndex());
                    }
                }
                return declared;
            }
            Model3D.LOGGER.warn("scenes[{}] declares no root nodes; using every node without a parent "
                    + "instead (this file is not a valid glTF scene)", sceneIndex);
        }
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i].parentIndex() < 0) {
                roots.add(i);
            }
        }
        if (roots.isEmpty()) {
            throw ModelParseException.at(sourceDescription, "no root nodes: every node is a child of "
                    + "another, so nothing would be drawn (a scene cycle?)");
        }
        int[] out = new int[roots.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = roots.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ cross-checks and bounds

    private void validateSkinning(ModelNode[] nodes, ModelMesh[] meshes, ModelSkin[] skins)
            throws ModelParseException {
        for (ModelNode node : nodes) {
            int meshIndex = node.meshIndex();
            if (meshIndex < 0) {
                continue;
            }
            boolean skinned = meshes[meshIndex].isSkinned();
            if (node.skinIndex() < 0) {
                if (skinned) {
                    Model3D.LOGGER.warn("nodes[{}] '{}': mesh '{}' carries joint data but the node names "
                            + "no skin; it will render in its rest pose", node.index(), node.name(),
                            meshes[meshIndex].name());
                }
                continue;
            }
            ModelSkin skin = skins[node.skinIndex()];
            for (ModelPrimitive primitive : meshes[meshIndex].primitives()) {
                int maxJoint = primitive.maxJointIndex();
                if (maxJoint >= skin.jointCount()) {
                    throw ModelParseException.at("nodes[" + node.index() + "] '" + node.name() + "'",
                            "primitive '" + primitive.name() + "' references joint " + maxJoint
                                    + " but skin '" + skin.name() + "' has only " + skin.jointCount()
                                    + " joints");
                }
            }
        }
    }

    /**
     * Union of every primitive's raw position bounds. Raw model space, not node space: the renderer
     * needs one box for culling and for the normalization scale, and applying the node hierarchy here
     * would mean the scene bounds change when an animation moves a node.
     */
    private static float[] computeBounds(ModelMesh[] meshes) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (ModelMesh mesh : meshes) {
            for (ModelPrimitive primitive : mesh.primitives()) {
                float[] bounds = primitive.computeBounds();
                minX = Math.min(minX, bounds[0]);
                minY = Math.min(minY, bounds[1]);
                minZ = Math.min(minZ, bounds[2]);
                maxX = Math.max(maxX, bounds[3]);
                maxY = Math.max(maxY, bounds[4]);
                maxZ = Math.max(maxZ, bounds[5]);
            }
        }
        if (!Float.isFinite(minX) || !Float.isFinite(maxX)) {
            return new float[] { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
        }
        return new float[] { minX, minY, minZ, maxX, maxY, maxZ };
    }

    /** Meshes plus the fact the material array needs a default appended. */
    private record MeshSet(ModelMesh[] meshes, boolean usedDefaultMaterial) {
    }

    /** Per-vertex joint influences, four per vertex. */
    private record Joints(float[] indices, float[] weights) {
    }
}
