package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.math.Mat4;

/**
 * Reads {@code accessors[]} out of {@code bufferViews[]} and {@code buffers[]}.
 *
 * <p>This is where all of glTF's data-layout rules meet, and every one of them is load-bearing for
 * the real corpus:
 * <ul>
 *   <li><b>byteStride / interleaving.</b> {@code sukhoi_su-30_flanker_c.glb} puts all 22 primitives'
 *       positions and normals in one {@code byteStride: 12} bufferView and their UVs in one
 *       {@code byteStride: 8} view, with each accessor's own {@code byteOffset} selecting its slice.
 *       Reading an accessor as if it were tightly packed there returns another primitive's vertices,
 *       which renders as a plausible-looking but wrong model.</li>
 *   <li><b>byteOffset accumulation.</b> The byte an element starts at is
 *       {@code bufferView.byteOffset + accessor.byteOffset + i * stride}.</li>
 *   <li><b>Sparse accessors.</b> A base value with individual elements overridden - the normal way a
 *       file says "these 40 vertices have no normals" without shipping a second full array.</li>
 *   <li><b>Normalized integers.</b> The common compact form for normals and skin weights, where a
 *       byte holds {@code -1..1} rather than {@code -128..127}: the divisor is the type's positive
 *       maximum (127/255/32767/65535) and the extra negative value clamps to {@code -1}.</li>
 * </ul>
 *
 * <p>Every range is checked with {@code long} arithmetic before a single element is read, and the
 * output array is allocated only after that check passes: a hostile {@code count} of two billion
 * must be a reported parse failure, not an {@code OutOfMemoryError}.
 */
final class AccessorReader {

    static final int BYTE = 5120;
    static final int UNSIGNED_BYTE = 5121;
    static final int SHORT = 5122;
    static final int UNSIGNED_SHORT = 5123;
    static final int UNSIGNED_INT = 5125;
    static final int FLOAT = 5126;

    /**
     * Cap on an accessor that has no bufferView and must therefore be materialized from sparse data
     * alone. There is no view length to bound such a count, so without this a file could ask for a
     * 2-billion-element zero array. 16 M elements is far beyond any real model.
     */
    private static final int MAX_UNBACKED_ELEMENTS = 1 << 24;

    private final JsonArray accessors;
    private final JsonArray bufferViews;
    private final GltfBuffers buffers;

    AccessorReader(JsonObject root, GltfBuffers buffers) throws ModelParseException {
        this.accessors = root.getArray("accessors");
        this.bufferViews = root.getArray("bufferViews");
        this.buffers = buffers;
    }

    /** Element count of {@code accessor[accessorIndex]}. */
    int count(int accessorIndex, String requester) throws ModelParseException {
        return accessor(accessorIndex, requester).requireInt("count");
    }

    /** glTF {@code type} string of an accessor, validated. */
    String typeOf(int accessorIndex, String requester) throws ModelParseException {
        return accessor(accessorIndex, requester).requireString("type");
    }

    /** glTF {@code componentType} code of an accessor (5120..5126), validated. */
    int componentTypeOf(int accessorIndex, String requester) throws ModelParseException {
        JsonObject accessor = accessor(accessorIndex, requester);
        int componentType = accessor.requireInt("componentType");
        // Validates the code and throws a message naming the accessor when it is not one of the six.
        componentSize(componentType, element(accessorIndex), requester);
        return componentType;
    }

    /** True when the accessor declares {@code normalized}. */
    boolean isNormalized(int accessorIndex, String requester) throws ModelParseException {
        return accessor(accessorIndex, requester).getBoolean("normalized", false);
    }

    /**
     * Reads any accessor as floats, applying normalization. Integer accessors that are not
     * normalized are widened; every integer component type in glTF fits exactly in a float at the
     * magnitudes that occur (positions, joint indices, UVs).
     */
    float[] readFloats(int accessorIndex, String requester) throws ModelParseException {
        Numbers numbers = read(accessorIndex, requester);
        if (numbers.floats != null) {
            return numbers.floats;
        }
        float[] out = new float[numbers.ints.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = numbers.ints[i];
        }
        return out;
    }

    /**
     * Reads an accessor that must be a scalar of an unsigned integer type - an index buffer or a
     * sparse index list. A float index accessor is not a lenient case to coerce: it is a file that
     * disagrees with the spec about the one field the whole draw call depends on.
     */
    int[] readUnsignedScalars(int accessorIndex, String requester) throws ModelParseException {
        String element = element(accessorIndex);
        JsonObject accessor = accessor(accessorIndex, requester);
        int componentType = accessor.requireInt("componentType");
        if (componentType != UNSIGNED_BYTE && componentType != UNSIGNED_SHORT
                && componentType != UNSIGNED_INT) {
            throw fail(element, requester, "indices must use an unsigned integer componentType "
                    + "(UNSIGNED_BYTE, UNSIGNED_SHORT or UNSIGNED_INT) but use "
                    + componentTypeName(componentType));
        }
        String type = accessor.requireString("type");
        if (!type.equals("SCALAR")) {
            throw fail(element, requester, "indices must be SCALAR but are " + type);
        }
        if (accessor.getBoolean("normalized", false)) {
            throw fail(element, requester, "indices must not be normalized");
        }
        Numbers numbers = read(accessorIndex, requester);
        if (numbers.ints != null) {
            return numbers.ints;
        }
        // Unreachable for the component types allowed above, but keeping it explicit means a future
        // relaxation cannot turn a float index range into a silent rounding.
        throw fail(element, requester, "indices must use an unsigned integer componentType");
    }

    /** Reads a MAT4 FLOAT accessor (inverse bind matrices) as {@link Mat4} instances. */
    Mat4[] readMat4s(int accessorIndex, String requester) throws ModelParseException {
        String element = element(accessorIndex);
        JsonObject accessor = accessor(accessorIndex, requester);
        String type = accessor.requireString("type");
        if (!type.equals("MAT4")) {
            throw fail(element, requester, "matrix data must be MAT4 but is " + type);
        }
        int componentType = accessor.requireInt("componentType");
        if (componentType != FLOAT) {
            throw fail(element, requester, "matrix data must use FLOAT components but uses "
                    + componentTypeName(componentType));
        }
        int count = accessor.requireInt("count");
        float[] values = readFloats(accessorIndex, requester);
        Mat4[] matrices = new Mat4[count];
        float[] slice = new float[16];
        for (int i = 0; i < count; i++) {
            System.arraycopy(values, i * 16, slice, 0, 16);
            // Mat4 takes ownership, and glTF stores MAT4 column-major - the same layout, so no
            // transpose happens on this boundary.
            matrices[i] = new Mat4(slice.clone());
        }
        return matrices;
    }

    private Numbers read(int accessorIndex, String requester) throws ModelParseException {
        String element = element(accessorIndex);
        JsonObject accessor = accessor(accessorIndex, requester);
        String type = accessor.requireString("type");
        int components = componentCount(type, element, requester);
        int componentType = accessor.requireInt("componentType");
        int componentSize = componentSize(componentType, element, requester);
        int count = accessor.requireInt("count");
        if (count < 0) {
            throw fail(element, requester, "count " + count + " is negative");
        }
        boolean normalized = accessor.getBoolean("normalized", false);
        if (normalized && componentType == UNSIGNED_INT) {
            throw fail(element, requester, "normalized is not allowed on an UNSIGNED_INT accessor "
                    + "(the spec defines normalization only against a type's positive maximum)");
        }
        if (normalized && componentType == FLOAT) {
            // The spec forbids this; the values are already floats, so the flag is meaningless
            // rather than dangerous and refusing the file would be pedantry.
            normalized = false;
        }
        long budget = (long) count * components;
        if (budget > Integer.MAX_VALUE - 8) {
            throw fail(element, requester, "count " + count + " x " + type
                    + " is too large to load");
        }

        JsonObject sparse = accessor.getObject("sparse");
        // Both guards run BEFORE the output array exists. The unbacked cap used to be checked after
        // the allocation, which made it useless against the input it exists for: `count` is a number
        // in an untrusted file, and "count": 536870912 at VEC3 is a 6 GiB request that throws
        // OutOfMemoryError - an Error, so it escapes the loader's catch (…RuntimeException) and takes
        // the load or the resource reload with it, instead of being reported as a bad model.
        boolean backed = accessor.has("bufferView");
        if (!backed && budget > MAX_UNBACKED_ELEMENTS) {
            throw fail(element, requester, "count " + count + " x " + type + " has no bufferView "
                    + "and is too large to materialize from sparse data alone");
        }
        boolean integerOutput = componentType != FLOAT && !normalized;
        Numbers numbers = integerOutput ? Numbers.ints(count, components) : Numbers.floats(count, components);

        if (backed) {
            int rows = matrixRows(type, components);
            int columns = components / rows;
            Placement placement = placement(accessor, element, requester, componentType, rows,
                    columns, count, true, isMatrix(type));
            decode(placement, componentType, components, rows, componentSize, count, numbers);
        } else {
            if (accessor.getInt("byteOffset", 0) != 0) {
                throw fail(element, requester, "byteOffset is defined without a bufferView");
            }
        }
        if (sparse != null) {
            applySparse(sparse, element, requester, componentType, components, count,
                    componentSize, numbers);
        }
        return numbers;
    }

    /** Applies {@code sparse.indices} and {@code sparse.values} over the already-read base values. */
    private void applySparse(JsonObject sparse, String element, String requester, int componentType,
                             int components, int count, int componentSize, Numbers numbers)
            throws ModelParseException {
        String sparseElement = element + ".sparse";
        int sparseCount = sparse.requireInt("count");
        if (sparseCount < 0) {
            throw fail(sparseElement, requester, "count " + sparseCount + " is negative");
        }
        if (sparseCount == 0) {
            return;
        }
        if (sparseCount > count) {
            throw fail(sparseElement, requester, "count " + sparseCount
                    + " exceeds the accessor's element count " + count);
        }

        JsonObject indices = sparse.requireObject("indices");
        String indicesElement = sparseElement + ".indices";
        int indexComponentType = indices.requireInt("componentType");
        if (indexComponentType != UNSIGNED_BYTE && indexComponentType != UNSIGNED_SHORT
                && indexComponentType != UNSIGNED_INT) {
            throw fail(indicesElement, requester, "componentType must be UNSIGNED_BYTE, "
                    + "UNSIGNED_SHORT or UNSIGNED_INT but is "
                    + componentTypeName(indexComponentType));
        }
        Placement indexPlacement = placement(indices, indicesElement, requester, indexComponentType,
                1, 1, sparseCount, false, false);
        int[] sparseIndices = new int[sparseCount];
        long o = indexPlacement.start;
        for (int i = 0; i < sparseCount; i++) {
            sparseIndices[i] = readInteger(indexPlacement.buffer.data(), o, indexComponentType);
            o += indexPlacement.stride;
        }

        JsonObject values = sparse.requireObject("values");
        String valuesElement = sparseElement + ".values";
        Placement valuePlacement = placement(values, valuesElement, requester, componentType,
                components, 1, sparseCount, false, false);
        long base = valuePlacement.start;
        for (int i = 0; i < sparseCount; i++) {
            int target = sparseIndices[i];
            if (target < 0 || target >= count) {
                throw fail(indicesElement, requester, "sparse index " + target + " (element " + i
                        + ") is out of range for an accessor of " + count + " elements");
            }
            long elementStart = base + (long) i * valuePlacement.stride;
            for (int c = 0; c < components; c++) {
                long at = elementStart + (long) c * componentSize;
                int slot = target * components + c;
                if (numbers.floats != null) {
                    // floats is only used for FLOAT components or a normalized integer accessor,
                    // so exactly one of the two branches below applies to any given accessor.
                    numbers.floats[slot] = componentType == FLOAT
                            ? readFloat(valuePlacement.buffer.data(), at)
                            : normalize(readInteger(valuePlacement.buffer.data(), at, componentType),
                                    componentType);
                } else {
                    numbers.ints[slot] = readInteger(valuePlacement.buffer.data(), at, componentType);
                }
            }
        }
    }

    private void decode(Placement placement, int componentType, int components, int rows,
                        int componentSize, int count, Numbers numbers) {
        byte[] data = placement.buffer.data();
        int columnStride = placement.columnStride;
        long start = placement.start;
        for (int i = 0; i < count; i++) {
            long elementStart = start + (long) i * placement.stride;
            int out = i * components;
            for (int c = 0; c < components; c++) {
                int column = c / rows;
                int row = c % rows;
                long at = elementStart + (long) column * columnStride + (long) row * componentSize;
                if (numbers.floats != null) {
                    numbers.floats[out + c] = componentType == FLOAT
                            ? readFloat(data, at)
                            : normalize(readInteger(data, at, componentType), componentType);
                } else {
                    numbers.ints[out + c] = readInteger(data, at, componentType);
                }
            }
        }
    }

    /**
     * Resolves {@code owner}'s {@code bufferView} + {@code byteOffset} and checks every byte the read
     * will touch. {@code ownerElement} names the offending object in errors ({@code accessor[7]} or
     * {@code accessor[7].sparse.values}), so the message points at the element that is wrong rather
     * than at the accessor that referenced it.
     *
     * @param allowStride       honour {@code byteStride} from the bufferView; false for sparse data,
     *                          which the spec always stores tightly packed
     * @param padMatrixColumns  apply glTF's 4-byte column padding for MAT2/MAT3, which is part of
     *                          the element layout only when byteStride is involved
     */
    private Placement placement(JsonObject owner, String ownerElement, String requester,
                                int componentType, int rows, int columns, int count,
                                boolean allowStride, boolean padMatrixColumns)
            throws ModelParseException {
        int viewIndex = owner.requireInt("bufferView");
        if (bufferViews == null) {
            throw fail(ownerElement, requester, "bufferView " + viewIndex
                    + " out of range (the file declares no bufferViews)");
        }
        if (viewIndex < 0 || viewIndex >= bufferViews.size()) {
            throw fail(ownerElement, requester, "bufferView " + viewIndex + " out of range (bufferViews: "
                    + bufferViews.size() + ")");
        }
        JsonObject view = bufferViews.requireObject(viewIndex);
        int viewOffset = view.getInt("byteOffset", 0);
        int viewLength = view.requireInt("byteLength");
        int bufferIndex = view.requireInt("buffer");
        GltfBuffers.Buffer buffer = buffers.require(bufferIndex, ownerElement);

        if (viewOffset < 0 || viewLength < 0
                || (long) viewOffset + viewLength > buffer.declaredLength()) {
            throw fail(ownerElement, requester, "bufferView " + viewIndex + " spans bytes ["
                    + viewOffset + ", " + ((long) viewOffset + viewLength) + ") but buffer "
                    + bufferIndex + " (" + buffer.description() + ") is only " + buffer.declaredLength()
                    + " bytes");
        }

        int componentSize = componentSize(componentType, ownerElement, requester);
        int rowsPerColumn = Math.max(1, rows);
        int columnStride = componentSize * rowsPerColumn;
        if (padMatrixColumns) {
            columnStride = (columnStride + 3) & ~3;
        }
        int tightElementSize = columnStride * Math.max(1, columns);
        int stride = tightElementSize;
        if (allowStride && view.has("byteStride")) {
            stride = view.requireInt("byteStride");
            if (stride < tightElementSize) {
                throw fail(ownerElement, requester, "bufferView " + viewIndex + " declares byteStride "
                        + stride + " but one element needs " + tightElementSize + " bytes");
            }
            if (stride % componentSize != 0) {
                throw fail(ownerElement, requester, "bufferView " + viewIndex + " declares byteStride "
                        + stride + ", which is not a multiple of the " + componentSize
                        + "-byte component size");
            }
        }
        int accessorOffset = owner.getInt("byteOffset", 0);
        if (accessorOffset < 0) {
            throw fail(ownerElement, requester, "byteOffset " + accessorOffset + " is negative");
        }
        long required = count == 0
                ? accessorOffset
                : (long) accessorOffset + (long) (count - 1) * stride + tightElementSize;
        if (required > viewLength) {
            throw fail(ownerElement, requester, "needs " + required + " bytes from bufferView "
                    + viewIndex + " but the view provides " + viewLength + " ("
                    + (count == 0 ? "empty accessor" : count + " elements at stride " + stride) + ")");
        }
        long start = (long) viewOffset + accessorOffset;
        // `required` is measured from the accessor's own offset, so the absolute end of the read is
        // viewOffset + required. Adding it to `start` would count accessorOffset twice, which
        // rejects legal files whose view sits late in a small buffer - exactly what the corpus text
        // .gltf does, where the vertex views start at byte 259020 of a 1 170 636-byte buffer.
        long absoluteEnd = (long) viewOffset + required;
        if (absoluteEnd > buffer.data().length) {
            throw fail(ownerElement, requester, "reads past the end of buffer " + bufferIndex
                    + " at byte " + absoluteEnd + " (" + buffer.data().length + " bytes)");
        }
        return new Placement(buffer, start, stride, columnStride);
    }

    private JsonObject accessor(int index, String requester) throws ModelParseException {
        String element = element(index);
        if (accessors == null) {
            throw fail(element, requester, "the file declares no accessors");
        }
        if (index < 0 || index >= accessors.size()) {
            throw fail(element, requester, "accessor index out of range (accessors: "
                    + accessors.size() + ")");
        }
        return accessors.requireObject(index);
    }

    private static String element(int accessorIndex) {
        return "accessor[" + accessorIndex + "]";
    }

    private static int componentCount(String type, String element, String requester)
            throws ModelParseException {
        switch (type) {
            case "SCALAR":
                return 1;
            case "VEC2":
                return 2;
            case "VEC3":
                return 3;
            case "VEC4":
                return 4;
            case "MAT2":
                return 4;
            case "MAT3":
                return 9;
            case "MAT4":
                return 16;
            default:
                throw fail(element, requester, "unknown accessor type '" + type
                        + "'; expected SCALAR, VEC2, VEC3, VEC4, MAT2, MAT3 or MAT4");
        }
    }

    /** Rows per column: 2/3/4 for a matrix, the component count for a vector. */
    private static int matrixRows(String type, int components) {
        switch (type) {
            case "MAT2":
                return 2;
            case "MAT3":
                return 3;
            case "MAT4":
                return 4;
            default:
                return components;
        }
    }

    private static boolean isMatrix(String type) {
        return type.equals("MAT2") || type.equals("MAT3") || type.equals("MAT4");
    }

    private static int componentSize(int componentType, String element, String requester)
            throws ModelParseException {
        switch (componentType) {
            case BYTE:
            case UNSIGNED_BYTE:
                return 1;
            case SHORT:
            case UNSIGNED_SHORT:
                return 2;
            case UNSIGNED_INT:
            case FLOAT:
                return 4;
            default:
                throw fail(element, requester, "unknown componentType " + componentType
                        + "; expected 5120 (BYTE), 5121 (UNSIGNED_BYTE), 5122 (SHORT), "
                        + "5123 (UNSIGNED_SHORT), 5125 (UNSIGNED_INT) or 5126 (FLOAT)");
        }
    }

    private static String componentTypeName(int componentType) {
        switch (componentType) {
            case BYTE:
                return "BYTE (5120)";
            case UNSIGNED_BYTE:
                return "UNSIGNED_BYTE (5121)";
            case SHORT:
                return "SHORT (5122)";
            case UNSIGNED_SHORT:
                return "UNSIGNED_SHORT (5123)";
            case UNSIGNED_INT:
                return "UNSIGNED_INT (5125)";
            case FLOAT:
                return "FLOAT (5126)";
            default:
                return Integer.toString(componentType);
        }
    }

    private static int readInteger(byte[] data, long offset, int componentType) {
        int at = (int) offset;
        switch (componentType) {
            case BYTE:
                return data[at];
            case UNSIGNED_BYTE:
                return data[at] & 0xFF;
            case SHORT:
                return (short) ((data[at] & 0xFF) | (data[at + 1] << 8));
            case UNSIGNED_SHORT:
                return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
            default:
                return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                        | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
        }
    }

    private static float readFloat(byte[] data, long offset) {
        int at = (int) offset;
        int bits = (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
        return Float.intBitsToFloat(bits);
    }

    /**
     * Normalizes an integer component to {@code 0..1} (unsigned) or {@code -1..1} (signed), dividing
     * by the type's positive maximum. The signed types' extra negative value (-128/-32768) would
     * otherwise give -1.008 and -1.0000305: out of range, and visible as a black seam on a normal
     * map, so it clamps.
     */
    private static float normalize(int value, int componentType) {
        switch (componentType) {
            case BYTE:
                return Math.max(value / 127.0f, -1.0f);
            case UNSIGNED_BYTE:
                return (value & 0xFF) / 255.0f;
            case SHORT:
                return Math.max(value / 32767.0f, -1.0f);
            case UNSIGNED_SHORT:
                return (value & 0xFFFF) / 65535.0f;
            default:
                return value;
        }
    }

    static ModelParseException fail(String where, String requester, String detail) {
        String prefix = requester == null || requester.isEmpty() ? "" : requester + ": ";
        return ModelParseException.at(prefix + where, detail);
    }

    /**
     * A resolved, range-checked byte region for one accessor (or sparse list): the buffer it lives
     * in, the absolute byte offset of its first element, and the strides to walk it with.
     */
    private record Placement(GltfBuffers.Buffer buffer, long start, int stride, int columnStride) {
    }

    /** Result of a read: exactly one of the two arrays is non-null. */
    private static final class Numbers {
        final float[] floats;
        final int[] ints;

        private Numbers(float[] floats, int[] ints) {
            this.floats = floats;
            this.ints = ints;
        }

        static Numbers floats(int count, int components) {
            return new Numbers(new float[count * components], null);
        }

        static Numbers ints(int count, int components) {
            return new Numbers(null, new int[count * components]);
        }
    }
}
