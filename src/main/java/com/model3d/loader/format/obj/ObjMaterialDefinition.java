package com.model3d.loader.format.obj;

/**
 * Raw fields of one MTL {@code newmtl} block, before any texture path has been resolved.
 *
 * <p>Kept separate from {@link com.model3d.loader.scene.ModelMaterial} on purpose: turning this into
 * a material needs a {@link com.model3d.loader.format.ModelSource} and can fail (a texture may not
 * exist), while reading the text cannot. The split also keeps the resolver's caches keyed by
 * material name instead of by whichever MTL file happened to define it.
 *
 * <p>Defaults match what {@code ModelMaterial} assumes for an unspecified value, with one
 * deliberate exception documented at the {@code Ns} and {@code illum} statements in {@link MtlParser}.
 */
final class ObjMaterialDefinition {

    /** MTL file this block came from; texture paths inside it are relative to that file's directory. */
    final String mtlPath;

    final String name;

    float[] diffuse = { 1.0f, 1.0f, 1.0f };
    float[] emissive = { 0.0f, 0.0f, 0.0f };
    boolean hasEmissive;

    /** 1 = opaque; {@code d} and {@code Tr} write here. */
    float alpha = 1.0f;

    /** From {@code Ns}; 1.0 (fully rough) when the file says nothing. */
    float roughness = 1.0f;

    /** From {@code illum}; -1 when the file says nothing. */
    int illumination = -1;

    String baseColorTexture;
    String normalTexture;
    String emissiveTexture;

    /** {@code map_d}: parsed so it can be reported, never assigned to a texture slot. */
    String alphaTexture;

    ObjMaterialDefinition(String name, String mtlPath) {
        this.name = name;
        this.mtlPath = mtlPath;
    }
}
