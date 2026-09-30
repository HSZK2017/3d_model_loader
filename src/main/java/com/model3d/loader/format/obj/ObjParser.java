package com.model3d.loader.format.obj;

import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.ModelParser;
import com.model3d.loader.format.ModelSource;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelMesh;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import com.model3d.loader.scene.ModelSkin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wavefront OBJ, with materials from a sibling {@code .mtl}.
 *
 * <p>OBJ carries no animation and no skinning: the output scene has no skins and an empty
 * animation list, and every node is a static transform. An animation for such a model has to come
 * from elsewhere (the renderer's own procedural motion), which is why
 * {@link ModelFormat#OBJ} reports {@code isAnimated() == false}.
 *
 * <h2>How the file maps onto a scene</h2>
 * <p>Every distinct {@code o}/{@code g} pair becomes one {@link ModelNode} with one
 * {@link ModelMesh}; a material change inside that group splits its faces into separate
 * {@link ModelPrimitive}s, because a primitive is the unit a renderer draws and it carries exactly
 * one material.
 *
 * <h2>What this reader deliberately does not do</h2>
 * <ul>
 *   <li>{@code l} and {@code p} elements are counted and skipped: the scene model has no line or
 *       point primitives, and drawing them as degenerate triangles would be worse than not drawing
 *       them. One DEBUG line per file says how many were dropped.</li>
 *   <li>Free-form surface statements ({@code curv}, {@code surf}, {@code vp}, ...) are ignored with
 *       one DEBUG line per statement keyword.</li>
 *   <li>{@code s} smoothing groups are honoured; see {@link Loader#computeFaceNormals()}.</li>
 * </ul>
 *
 * <p>Instances are stateless: {@link #parse} keeps everything it needs in local objects, so the
 * single instance the format registry holds can serve concurrent loads.
 */
public final class ObjParser implements ModelParser {

    /**
     * Upper bound on the vertices of one {@link ModelPrimitive}.
     *
     * <p>A primitive stores indices as unsigned shorts, so it cannot address more than 65536
     * vertices. Wrapping the index instead would wire a mesh to unrelated vertices and read as
     * shredded geometry rather than as a documented limit, so larger groups are split into several
     * primitives that share a material.
     */
    static final int MAX_VERTICES_PER_PRIMITIVE = 65_536;

    /**
     * Smoothing group assumed when a file never says {@code s}.
     *
     * <p>The spec does not name a default and implementations disagree. Smooth is chosen because the
     * files that omit {@code s} are overwhelmingly hand-written or converted from CAD, where the
     * intent is a smooth surface, and because flat is one {@code s off} away for the files that want
     * it; a cube that renders smooth is a smaller surprise than an aircraft fuselage that renders
     * faceted.
     */
    private static final int DEFAULT_SMOOTHING_GROUP = 1;

    private static final String DEFAULT_GROUP_NAME = "default";

    private static final float[] NO_TRANSLATION = { 0.0f, 0.0f, 0.0f };
    private static final float[] IDENTITY_ROTATION = { 0.0f, 0.0f, 0.0f, 1.0f };
    private static final float[] UNIT_SCALE = { 1.0f, 1.0f, 1.0f };

    private final ParseLog log;

    public ObjParser() {
        this(ParseLog.slf4j(ObjParser.class));
    }

    /** Test seam: lets a test capture the diagnostics this parser reports. */
    ObjParser(ParseLog log) {
        this.log = log;
    }

    @Override
    public ModelFormat format() {
        return ModelFormat.OBJ;
    }

    @Override
    public ModelScene parse(ModelSource source, String requestedName) throws ModelParseException {
        String path = source.mainPath();
        String name = requestedName == null || requestedName.isBlank() ? path : requestedName;
        return new Loader(log, source, path, name).load(readMain(source, path));
    }

    /**
     * Reads the OBJ itself. A missing or unreadable OBJ is fatal - there is no geometry to fall back
     * on - which is the opposite of a missing MTL.
     */
    private static String readMain(ModelSource source, String path) throws ModelParseException {
        InputStream stream;
        try {
            stream = source.openMain();
        } catch (IOException e) {
            throw ModelParseException.at(path, "the OBJ file could not be opened", e);
        }
        if (stream == null) {
            throw ModelParseException.at(path, "the OBJ file does not exist");
        }
        String text;
        try (InputStream open = stream) {
            text = new String(open.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ModelParseException.at(path, "the OBJ file could not be read", e);
        }
        // Editors that write a UTF-8 BOM would otherwise turn the first keyword into "\uFEFFv" and
        // silently lose one vertex.
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** One OBJ load. Holds every piece of state the parse builds up, so nothing is shared. */
    private static final class Loader {

        private final ParseLog log;
        private final String path;
        private final String name;
        private final ObjMaterialResolver materials;

        /** Attribute arrays, in the order the file declares them (OBJ indices are positional). */
        private final FloatList positions = new FloatList();
        private final List<float[]> normals = new ArrayList<>();
        private final List<float[]> uvs = new ArrayList<>();

        /**
         * Unit form of every declared {@code vn}, built once per file.
         *
         * <p>Normals are directions, so a non-unit {@code vn} is normalised rather than passed
         * through: the renderer would otherwise shade that face at the wrong brightness, and there is
         * no information in the length that shading can use. Built in one pass so the corner
         * de-duplication below compares the values that will actually be uploaded.
         */
        private final List<float[]> shadingNormals = new ArrayList<>();

        private final Map<String, Section> sections = new LinkedHashMap<>();
        private final Map<Long, float[]> smoothedNormals = new HashMap<>();
        private final Set<String> notedOnce = new HashSet<>();

        /** Current {@code o}/{@code g}/{@code usemtl}/{@code s} state, as the spec defines it. */
        private String objectName;
        private String groupName;
        private String activeMaterial;
        private int smoothingGroup = DEFAULT_SMOOTHING_GROUP;

        private int degenerateTriangles;
        private int skippedElements;
        private int unknownStatements;

        Loader(ParseLog log, ModelSource source, String path, String name) {
            this.log = log;
            this.path = path;
            this.name = name;
            this.materials = new ObjMaterialResolver(source, log);
        }

        ModelScene load(String text) throws ModelParseException {
            for (ObjText.Statement statement : ObjText.statements(text)) {
                String[] tokens = statement.tokens();
                switch (tokens[0].toLowerCase(Locale.ROOT)) {
                    case "v" -> readPosition(statement, tokens);
                    case "vn" -> readNormal(statement, tokens);
                    case "vt" -> readTextureCoordinate(statement, tokens);
                    case "f" -> readFace(statement, tokens);
                    case "o" -> objectName = emptyToNull(statement.remainder());
                    case "g" -> readGroup(statement.remainder());
                    case "s" -> readSmoothingGroup(statement, tokens);
                    case "usemtl" -> activeMaterial = emptyToNull(statement.remainder());
                    case "mtllib" -> readMaterialLibraries(statement);
                    case "l", "p" -> skippedElements++;
                    default -> {
                        unknownStatements++;
                        noteOnce("statement-" + tokens[0].toLowerCase(Locale.ROOT),
                                "OBJ " + path + ":" + statement.line() + ": ignoring unsupported statement '" + tokens[0] + "'");
                    }
                }
            }
            return buildScene();
        }

        // ------------------------------------------------------------------ geometry statements

        private void readPosition(ObjText.Statement statement, String[] tokens) throws ModelParseException {
            if (tokens.length < 4) {
                throw fail(statement.line(), "a vertex needs 3 coordinates, found " + (tokens.length - 1));
            }
            positions.add(number(statement, tokens[1]));
            positions.add(number(statement, tokens[2]));
            positions.add(number(statement, tokens[3]));
            if (tokens.length > 4) {
                // The 4th component is a rational weight and anything beyond it is a vertex colour;
                // the scene has no use for either.
                noteOnce("vertex-extras", "OBJ " + path + ": ignoring extra components on 'v' lines "
                        + "(the rational weight and vertex colours are not part of this scene model)");
            }
        }

        private void readNormal(ObjText.Statement statement, String[] tokens) throws ModelParseException {
            if (tokens.length < 4) {
                throw fail(statement.line(), "a normal needs 3 components, found " + (tokens.length - 1));
            }
            normals.add(new float[] {
                    number(statement, tokens[1]), number(statement, tokens[2]), number(statement, tokens[3]) });
        }

        private void readTextureCoordinate(ObjText.Statement statement, String[] tokens) throws ModelParseException {
            if (tokens.length < 2) {
                throw fail(statement.line(), "a texture coordinate needs at least 1 component");
            }
            float u = number(statement, tokens[1]);
            // 'vt u' is legal and means v = 0; that is the spec's default for an omitted component,
            // not an invented value. The optional 3rd component is the depth of a 3D texture.
            float v = tokens.length > 2 ? number(statement, tokens[2]) : 0.0f;
            uvs.add(new float[] { u, v });
            if (tokens.length > 3) {
                noteOnce("vt-depth", "OBJ " + path + ": ignoring the 3rd component of 'vt' lines "
                        + "(3D texture coordinates are not used by this renderer)");
            }
        }

        private void readFace(ObjText.Statement statement, String[] tokens) throws ModelParseException {
            int corners = tokens.length - 1;
            if (corners < 3) {
                throw fail(statement.line(), "a face needs at least 3 vertex references, found " + corners);
            }
            int positionCount = positions.size() / 3;
            int[] vertexRefs = new int[corners];
            int[] uvRefs = new int[corners];
            int[] normalRefs = new int[corners];
            for (int corner = 0; corner < corners; corner++) {
                String[] parts = tokens[corner + 1].split("/", -1);
                if (parts.length > 3) {
                    noteOnce("face-extras", "OBJ " + path + ": ignoring extra fields in a face reference "
                            + "('" + tokens[corner + 1] + "'); only v, v/vt, v//vn and v/vt/vn are defined");
                }
                vertexRefs[corner] = resolveIndex(parts[0], positionCount, "vertex", statement.line());
                uvRefs[corner] = parts.length > 1 && !parts[1].isEmpty()
                        ? resolveIndex(parts[1], uvs.size(), "texture coordinate", statement.line()) : -1;
                normalRefs[corner] = parts.length > 2 && !parts[2].isEmpty()
                        ? resolveIndex(parts[2], normals.size(), "normal", statement.line()) : -1;
            }
            currentSection().facesFor(activeMaterial)
                    .add(new Face(vertexRefs, uvRefs, normalRefs, smoothingGroup, statement.line()));
        }

        /**
         * Turns a written face index into an array offset.
         *
         * <p>Negative indices are relative to the number of elements declared <b>above this line</b>,
         * which is why they are resolved here and not after the whole file is read: resolving them
         * against the final counts silently attaches faces to entirely different vertices, and the
         * result still looks like a plausible model.
         */
        private int resolveIndex(String token, int count, String what, int line) throws ModelParseException {
            int written;
            try {
                written = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw fail(line, "'" + token + "' is not a valid " + what + " index");
            }
            if (written == 0) {
                throw fail(line, what + " index 0 is invalid: OBJ indices are 1-based and negative indices "
                        + "count back from the most recently declared element");
            }
            int index = written > 0 ? written - 1 : count + written;
            if (index < 0 || index >= count) {
                throw fail(line, what + " index " + written + " is out of range: only " + count + " "
                        + what + "(s) are declared above this line");
            }
            return index;
        }

        // ------------------------------------------------------------------- grouping statements

        private void readGroup(String remainder) {
            if (remainder.isEmpty()) {
                // 'g' with no argument is how a file returns to the default group.
                groupName = null;
                return;
            }
            String[] names = remainder.split("\\s+");
            groupName = names[0];
            if (names.length > 1) {
                noteOnce("group-multiple", "OBJ " + path + ": 'g " + remainder + "' names several groups; '"
                        + names[0] + "' becomes the node name (a face may belong to several groups, a node may not)");
            }
        }

        private void readSmoothingGroup(ObjText.Statement statement, String[] tokens) {
            if (tokens.length < 2) {
                noteOnce("s-missing-value", "OBJ " + path + ":" + statement.line()
                        + ": 's' without a group number; keeping smoothing group " + smoothingGroup);
                return;
            }
            String value = tokens[1].toLowerCase(Locale.ROOT);
            if (value.equals("off")) {
                smoothingGroup = 0;
                return;
            }
            if (value.equals("on")) {
                smoothingGroup = 1;
                noteOnce("s-on", "OBJ " + path + ":" + statement.line()
                        + ": 's on' is not part of the format; treating it as smoothing group 1");
                return;
            }
            try {
                double group = Double.parseDouble(value);
                smoothingGroup = group <= 0.0 ? 0 : (int) Math.round(group);
            } catch (NumberFormatException e) {
                noteOnce("s-unreadable-" + value, "OBJ " + path + ":" + statement.line()
                        + ": unreadable smoothing group '" + value + "'; keeping " + smoothingGroup);
            }
        }

        private void readMaterialLibraries(ObjText.Statement statement) {
            String remainder = statement.remainder();
            if (remainder.isEmpty()) {
                noteOnce("mtllib-empty", "OBJ " + path + ":" + statement.line() + ": 'mtllib' with no file name");
                return;
            }
            // One statement may name several libraries; each token is a file. A library name
            // containing spaces cannot be expressed in the format and is therefore not attempted.
            for (String library : remainder.split("\\s+")) {
                materials.loadMtllib(library);
            }
        }

        private Section currentSection() {
            // Grouping by the (o, g) pair merges an exporter's repeated 'g' blocks for one surface
            // into a single node instead of thousands of identical ones, and still separates two
            // groups that happen to share a name under different objects.
            String key = (objectName == null ? "" : objectName) + '\u0000' + (groupName == null ? "" : groupName);
            return sections.computeIfAbsent(key, ignored -> new Section(
                    groupName != null ? groupName : objectName != null ? objectName : DEFAULT_GROUP_NAME));
        }

        // ------------------------------------------------------------------------------- normals

        /**
         * Fills in every face's geometric normal and the area-weighted per-vertex normals of each
         * smoothing group, and normalises the declared {@code vn}s.
         *
         * <p>Must run before any primitive is built - {@link #cornerNormal} reads both
         * {@link Face#normal} and {@link #shadingNormals} - which {@link #buildScene} guarantees by
         * calling it first.
         *
         * <p>Area weighting is not a refinement, it is what makes a smooth surface look smooth: a
         * vertex where a large triangle meets a small one must be dominated by the large one, and an
         * unweighted average visibly tilts the shading toward whichever side happens to be more
         * finely tessellated.
         *
         * <p>Accumulation is keyed by (smoothing group, vertex index) and is therefore file-wide: two
         * faces belong to the same surface when they name the same group, whether or not an
         * {@code o}/{@code g} statement sits between them.
         */
        private void computeFaceNormals() {
            for (float[] normal : normals) {
                shadingNormals.add(normalize(normal));
            }
            List<Face> smoothed = new ArrayList<>();
            for (Section section : sections.values()) {
                for (List<Face> faces : section.facesByMaterial.values()) {
                    for (Face face : faces) {
                        face.newell = newellNormal(face.vertexRefs);
                        face.normal = normalize(face.newell);
                        if (face.smoothingGroup > 0 && face.normal != null) {
                            smoothed.add(face);
                        }
                    }
                }
            }
            for (Face face : smoothed) {
                for (int vertex : distinctVertices(face.vertexRefs)) {
                    float[] accumulated = smoothedNormals.computeIfAbsent(
                            smoothKey(face.smoothingGroup, vertex), ignored -> new float[3]);
                    accumulated[0] += face.newell[0];
                    accumulated[1] += face.newell[1];
                    accumulated[2] += face.newell[2];
                }
            }
        }

        /**
         * Newell's method over a polygon: area-weighted and stable for the non-planar quads that
         * exporters regularly emit, where a single cross product of three corners would tilt the
         * normal toward an arbitrary half of the face.
         */
        private float[] newellNormal(int[] refs) {
            float nx = 0.0f;
            float ny = 0.0f;
            float nz = 0.0f;
            for (int i = 0; i < refs.length; i++) {
                int current = refs[i] * 3;
                int next = refs[(i + 1) % refs.length] * 3;
                float xi = positions.get(current);
                float yi = positions.get(current + 1);
                float zi = positions.get(current + 2);
                float xj = positions.get(next);
                float yj = positions.get(next + 1);
                float zj = positions.get(next + 2);
                nx += (yi - yj) * (zi + zj);
                ny += (zi - zj) * (xi + xj);
                nz += (xi - xj) * (yi + yj);
            }
            return new float[] { nx, ny, nz };
        }

        /**
         * The shading normal of one face corner, in order of authority: the file's own {@code vn},
         * the area-weighted smoothing-group average, the face normal, the triangle normal.
         */
        private float[] cornerNormal(Face face, int corner, float[] triangleNormal) {
            int normalRef = face.normalRefs[corner];
            if (normalRef >= 0) {
                float[] written = shadingNormals.get(normalRef);
                if (isUsable(written)) {
                    return written;
                }
                // A non-zero length is not enough: a NaN or infinite 'vn' has to fall through too,
                // because handing the renderer such a direction produces black or flickering shading.
                noteOnce("zero-normal", "OBJ " + path + ":" + face.line
                        + ": a 'vn' with no usable direction is ignored in favour of the computed normal");
            }
            if (face.smoothingGroup > 0) {
                float[] accumulated = smoothedNormals.get(smoothKey(face.smoothingGroup, face.vertexRefs[corner]));
                if (isUsable(accumulated)) {
                    return normalize(accumulated);
                }
            }
            return face.normal != null ? face.normal : triangleNormal;
        }

        // ------------------------------------------------------------------------ scene assembly

        private ModelScene buildScene() throws ModelParseException {
            computeFaceNormals();
            List<ModelNode> nodes = new ArrayList<>();
            List<ModelMesh> meshes = new ArrayList<>();
            List<ModelPrimitive> primitives = new ArrayList<>();
            for (Section section : sections.values()) {
                List<ModelPrimitive> sectionPrimitives = new ArrayList<>();
                for (Map.Entry<String, List<Face>> entry : section.facesByMaterial.entrySet()) {
                    sectionPrimitives.addAll(buildPrimitives(section, entry.getKey(), entry.getValue()));
                }
                if (sectionPrimitives.isEmpty()) {
                    // An 'o'/'g' statement with no faces (or only degenerate ones) is common and must
                    // not produce an empty mesh: ModelMesh rejects one.
                    continue;
                }
                int nodeIndex = nodes.size();
                int meshIndex = meshes.size();
                meshes.add(new ModelMesh(section.name, sectionPrimitives.toArray(new ModelPrimitive[0])));
                // Static transform, no skin, and meshIndex always names the mesh built with it, so a
                // renderer can walk nodes and draw without a remap table.
                nodes.add(new ModelNode(nodeIndex, section.name, -1, meshIndex, -1,
                        NO_TRANSLATION, IDENTITY_ROTATION, UNIT_SCALE));
                primitives.addAll(sectionPrimitives);
            }
            if (meshes.isEmpty()) {
                // Fail closed rather than return a scene whose mesh list is empty: the caller would
                // render nothing and have no way to tell that from a renderer bug.
                throw ModelParseException.at(path, "no faces were found (" + (positions.size() / 3)
                        + " vertices, " + normals.size() + " normals, " + uvs.size() + " texture coordinates, "
                        + skippedElements + " line/point elements)");
            }
            int[] rootNodes = new int[nodes.size()];
            for (int i = 0; i < rootNodes.length; i++) {
                rootNodes[i] = i;
            }
            int triangleCount = 0;
            for (ModelPrimitive primitive : primitives) {
                triangleCount += primitive.indexCount() / 3;
            }
            log.debug("OBJ " + path + ": " + nodes.size() + " nodes, " + meshes.size() + " meshes, "
                    + primitives.size() + " primitives, " + triangleCount + " triangles, "
                    + materials.materials().size() + " materials, " + (positions.size() / 3) + " positions");
            if (degenerateTriangles > 0) {
                log.debug("OBJ " + path + ": dropped " + degenerateTriangles
                        + " degenerate triangle(s) of zero area or with a repeated vertex index");
            }
            if (skippedElements > 0) {
                log.debug("OBJ " + path + ": skipped " + skippedElements + " line/point element(s); "
                        + "this loader draws triangles only and inventing geometry for them would be worse");
            }
            if (unknownStatements > 0) {
                log.debug("OBJ " + path + ": ignored " + unknownStatements + " unsupported statement(s)");
            }
            return new ModelScene(name, rootNodes, nodes.toArray(new ModelNode[0]),
                    meshes.toArray(new ModelMesh[0]), materials.materials().toArray(new ModelMaterial[0]),
                    new ModelSkin[0], List.of(), unionBounds(primitives), path);
        }

        /**
         * Builds the primitives of one (group, material) pair: fan-triangulates, drops degenerate
         * triangles, computes normals, and de-duplicates the (position, uv, normal) corners into an
         * indexed mesh.
         */
        private List<ModelPrimitive> buildPrimitives(Section section, String materialKey, List<Face> faces) {
            boolean anyUv = false;
            boolean missingUv = false;
            for (Face face : faces) {
                for (int uv : face.uvRefs) {
                    if (uv >= 0) {
                        anyUv = true;
                    } else {
                        missingUv = true;
                    }
                }
            }
            // A primitive carries either one uv per vertex or none. Filling the gaps with (0,0) would
            // pin those corners to a single texel of the texture, so a partially textured primitive
            // loses its uvs instead and the renderer falls back to its own default shading.
            boolean useUvs = anyUv && !missingUv;
            if (anyUv && missingUv) {
                noteOnce("mixed-uvs", "OBJ " + path + ": some faces of a group carry 'vt' references and others "
                        + "do not; uvs are dropped for the affected primitives rather than invented as (0,0)");
            }

            List<Corner> corners = new ArrayList<>();
            List<int[]> triangles = new ArrayList<>();
            Map<Corner, Integer> slots = new HashMap<>();
            for (Face face : faces) {
                // Fan triangulation from the first corner: a quad becomes two triangles, an n-gon n-2.
                // It is what every consumer of OBJ expects, and it is correct for the convex faces
                // exporters produce; a concave n-gon would need ear clipping, which no OBJ exporter of
                // triangle-friendly formats emits.
                for (int i = 1; i + 1 < face.vertexRefs.length; i++) {
                    int[] faceCorners = { 0, i, i + 1 };
                    if (isDegenerate(face, faceCorners)) {
                        degenerateTriangles++;
                        continue;
                    }
                    float[] triangleNormal = triangleNormal(face.vertexRefs[faceCorners[0]],
                            face.vertexRefs[faceCorners[1]], face.vertexRefs[faceCorners[2]]);
                    int[] triangle = new int[3];
                    for (int k = 0; k < 3; k++) {
                        int corner = faceCorners[k];
                        float[] normal = cornerNormal(face, corner, triangleNormal);
                        Corner key = new Corner(face.vertexRefs[corner], useUvs ? face.uvRefs[corner] : -1,
                                Float.floatToIntBits(normal[0]), Float.floatToIntBits(normal[1]),
                                Float.floatToIntBits(normal[2]));
                        Integer slot = slots.get(key);
                        if (slot == null) {
                            slot = corners.size();
                            slots.put(key, slot);
                            corners.add(key);
                        }
                        triangle[k] = slot;
                    }
                    triangles.add(triangle);
                }
            }
            if (triangles.isEmpty()) {
                return List.of();
            }
            // Resolved only once a triangle survives, so a material that a usemtl named for geometry
            // that turned out to be entirely degenerate does not appear in the scene's material list.
            int materialIndex = materials.indexOf(emptyToNull(materialKey));
            String label = section.name + " [" + (materialKey.isEmpty() ? DEFAULT_GROUP_NAME : materialKey) + "]";
            if (corners.size() <= MAX_VERTICES_PER_PRIMITIVE) {
                return List.of(buildPrimitive(label, corners, triangles, useUvs, materialIndex));
            }
            return splitIntoShortIndexedPrimitives(label, corners, triangles, useUvs, materialIndex);
        }

        /**
         * Splits a group that does not fit one short-indexed primitive into several, duplicating the
         * vertices that straddle a boundary. Visible only as extra draw calls, and much better than
         * an index that wraps to a vertex on the other side of the model.
         */
        private List<ModelPrimitive> splitIntoShortIndexedPrimitives(String label, List<Corner> corners,
                                                                    List<int[]> triangles, boolean useUvs,
                                                                    int materialIndex) {
            log.debug("OBJ " + path + ": '" + label + "' has " + corners.size() + " vertices, more than one"
                    + " unsigned-short-indexed primitive can address; splitting it into parts");
            List<ModelPrimitive> primitives = new ArrayList<>();
            List<Corner> partCorners = new ArrayList<>();
            List<int[]> partTriangles = new ArrayList<>();
            Map<Integer, Integer> remap = new HashMap<>();
            for (int[] triangle : triangles) {
                int needed = 0;
                for (int slot : triangle) {
                    if (!remap.containsKey(slot)) {
                        needed++;
                    }
                }
                if (partCorners.size() + needed > MAX_VERTICES_PER_PRIMITIVE) {
                    primitives.add(buildPrimitive(label + " part " + (primitives.size() + 1),
                            partCorners, partTriangles, useUvs, materialIndex));
                    partCorners = new ArrayList<>();
                    partTriangles = new ArrayList<>();
                    remap = new HashMap<>();
                }
                int[] remapped = new int[3];
                for (int i = 0; i < 3; i++) {
                    Integer mapped = remap.get(triangle[i]);
                    if (mapped == null) {
                        mapped = partCorners.size();
                        remap.put(triangle[i], mapped);
                        partCorners.add(corners.get(triangle[i]));
                    }
                    remapped[i] = mapped;
                }
                partTriangles.add(remapped);
            }
            primitives.add(buildPrimitive(label + " part " + (primitives.size() + 1),
                    partCorners, partTriangles, useUvs, materialIndex));
            return primitives;
        }

        private ModelPrimitive buildPrimitive(String label, List<Corner> corners, List<int[]> triangles,
                                              boolean useUvs, int materialIndex) {
            int vertexCount = corners.size();
            float[] primitivePositions = new float[vertexCount * 3];
            float[] primitiveNormals = new float[vertexCount * 3];
            float[] primitiveUvs = useUvs ? new float[vertexCount * 2] : null;
            short[] indices = new short[triangles.size() * 3];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                Corner corner = corners.get(vertex);
                int position = corner.position() * 3;
                primitivePositions[vertex * 3] = positions.get(position);
                primitivePositions[vertex * 3 + 1] = positions.get(position + 1);
                primitivePositions[vertex * 3 + 2] = positions.get(position + 2);
                primitiveNormals[vertex * 3] = Float.intBitsToFloat(corner.normalX());
                primitiveNormals[vertex * 3 + 1] = Float.intBitsToFloat(corner.normalY());
                primitiveNormals[vertex * 3 + 2] = Float.intBitsToFloat(corner.normalZ());
                if (useUvs) {
                    float[] uv = uvs.get(corner.uv());
                    primitiveUvs[vertex * 2] = uv[0];
                    primitiveUvs[vertex * 2 + 1] = uv[1];
                }
            }
            int index = 0;
            for (int[] triangle : triangles) {
                for (int slot : triangle) {
                    indices[index++] = (short) slot;
                }
            }
            return new ModelPrimitive(label, primitivePositions, primitiveNormals, primitiveUvs,
                    null, indices, null, null, materialIndex);
        }

        /**
         * True when a triangle has no area worth drawing: a repeated vertex, a zero-length edge, or
         * two edges so nearly parallel that the normal is numerical noise. The last test is relative,
         * so it behaves the same for a model authored in millimetres and one authored in metres.
         */
        private boolean isDegenerate(Face face, int[] corners) {
            int a = face.vertexRefs[corners[0]];
            int b = face.vertexRefs[corners[1]];
            int c = face.vertexRefs[corners[2]];
            if (a == b || b == c || a == c) {
                return true;
            }
            int ia = a * 3;
            int ib = b * 3;
            int ic = c * 3;
            float abx = positions.get(ib) - positions.get(ia);
            float aby = positions.get(ib + 1) - positions.get(ia + 1);
            float abz = positions.get(ib + 2) - positions.get(ia + 2);
            float acx = positions.get(ic) - positions.get(ia);
            float acy = positions.get(ic + 1) - positions.get(ia + 1);
            float acz = positions.get(ic + 2) - positions.get(ia + 2);
            float crossX = aby * acz - abz * acy;
            float crossY = abz * acx - abx * acz;
            float crossZ = abx * acy - aby * acx;
            float crossSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
            float abSquared = abx * abx + aby * aby + abz * abz;
            float acSquared = acx * acx + acy * acy + acz * acz;
            if (!(crossSquared > 0.0f) || !(abSquared > 0.0f) || !(acSquared > 0.0f)) {
                return true;
            }
            // |a x b|^2 = |a|^2|b|^2 sin^2(theta); a triangle thinner than about 1e-8 radians has no
            // usable orientation.
            return crossSquared <= 1e-16f * abSquared * acSquared;
        }

        private float[] triangleNormal(int a, int b, int c) {
            int ia = a * 3;
            int ib = b * 3;
            int ic = c * 3;
            float abx = positions.get(ib) - positions.get(ia);
            float aby = positions.get(ib + 1) - positions.get(ia + 1);
            float abz = positions.get(ib + 2) - positions.get(ia + 2);
            float acx = positions.get(ic) - positions.get(ia);
            float acy = positions.get(ic + 1) - positions.get(ia + 1);
            float acz = positions.get(ic + 2) - positions.get(ia + 2);
            return normalize(new float[] {
                    aby * acz - abz * acy, abz * acx - abx * acz, abx * acy - aby * acx });
        }

        private static float[] unionBounds(List<ModelPrimitive> primitives) {
            float[] bounds = null;
            for (ModelPrimitive primitive : primitives) {
                float[] candidate = primitive.computeBounds();
                if (bounds == null) {
                    bounds = candidate;
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    bounds[axis] = Math.min(bounds[axis], candidate[axis]);
                    bounds[3 + axis] = Math.max(bounds[3 + axis], candidate[3 + axis]);
                }
            }
            return bounds;
        }

        // --------------------------------------------------------------------------------- helpers

        private float number(ObjText.Statement statement, String token) throws ModelParseException {
            try {
                float value = Float.parseFloat(token);
                if (!Float.isFinite(value)) {
                    throw fail(statement.line(), "'" + token + "' is not a finite number");
                }
                return value;
            } catch (NumberFormatException e) {
                throw fail(statement.line(), "'" + token + "' is not a number");
            }
        }

        private ModelParseException fail(int line, String reason) {
            return ModelParseException.at(path + ":" + line, reason);
        }

        private void noteOnce(String key, String message) {
            if (notedOnce.add(key)) {
                log.debug(message);
            }
        }

        private static String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }

        private static int[] distinctVertices(int[] refs) {
            int[] distinct = new int[refs.length];
            int count = 0;
            for (int ref : refs) {
                boolean seen = false;
                for (int i = 0; i < count && !seen; i++) {
                    seen = distinct[i] == ref;
                }
                if (!seen) {
                    distinct[count++] = ref;
                }
            }
            return Arrays.copyOf(distinct, count);
        }

        /** Key for one (smoothing group, vertex) pair; group numbers are not bounded by the spec. */
        private static long smoothKey(int smoothingGroup, int vertex) {
            return ((long) smoothingGroup << 32) | (vertex & 0xFFFFFFFFL);
        }

        private static boolean isUsable(float[] vector) {
            return vector != null && lengthSquared(vector) > 0.0f && Float.isFinite(lengthSquared(vector));
        }

        private static float lengthSquared(float[] vector) {
            return vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2];
        }

        /** Unit vector, or null when the input has no direction to speak of. */
        private static float[] normalize(float[] vector) {
            double lengthSquared = (double) vector[0] * vector[0] + (double) vector[1] * vector[1]
                    + (double) vector[2] * vector[2];
            if (!(lengthSquared > 0.0) || !Double.isFinite(lengthSquared)) {
                return null;
            }
            float inverse = (float) (1.0 / Math.sqrt(lengthSquared));
            if (!Float.isFinite(inverse)) {
                return null;
            }
            return new float[] { vector[0] * inverse, vector[1] * inverse, vector[2] * inverse };
        }
    }

    /** One {@code o}/{@code g} section: a node, a mesh, and its faces split by active material. */
    private static final class Section {

        private final String name;
        private final Map<String, List<Face>> facesByMaterial = new LinkedHashMap<>();

        Section(String name) {
            this.name = name;
        }

        List<Face> facesFor(String material) {
            return facesByMaterial.computeIfAbsent(material == null ? "" : material, ignored -> new ArrayList<>());
        }
    }

    /**
     * One polygon as written in the file, with parallel per-corner attribute references.
     *
     * <p>{@code -1} means the corner named no such attribute. The geometric normal is computed in a
     * separate pass because a smoothing group needs all faces before any one of them can be shaded.
     */
    private static final class Face {

        private final int[] vertexRefs;
        private final int[] uvRefs;
        private final int[] normalRefs;
        private final int smoothingGroup;
        private final int line;

        private float[] newell;
        private float[] normal;

        Face(int[] vertexRefs, int[] uvRefs, int[] normalRefs, int smoothingGroup, int line) {
            this.vertexRefs = vertexRefs;
            this.uvRefs = uvRefs;
            this.normalRefs = normalRefs;
            this.smoothingGroup = smoothingGroup;
            this.line = line;
        }
    }

    /**
     * One emitted vertex, identified by everything that can make two corners differ. The normal is
     * compared by its bit pattern, which is exact and needs no epsilon: the same smoothing group and
     * vertex always produce the same accumulated normal, while a flat-shaded corner on a different
     * face differs and must stay a separate vertex.
     */
    private record Corner(int position, int uv, int normalX, int normalY, int normalZ) {
    }

    /** Growable float array: the attribute lists are appended to once per file statement. */
    private static final class FloatList {

        private float[] data = new float[1024];
        private int size;

        void add(float value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        float get(int index) {
            return data[index];
        }

        int size() {
            return size;
        }
    }
}
