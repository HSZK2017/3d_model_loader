package com.model3d.loader.scene;

import java.util.List;

/**
 * A surface's material, reduced to what a renderer can actually honour.
 *
 * <p>Textures are referenced by <b>model-relative path string</b>, not by a Minecraft
 * {@code ResourceLocation}: the parser has no idea which namespace or pack a model came from.
 * Resolving a path string against the pack that produced the model is
 * {@code resource.ModelAssetResolver}'s job, which keeps the parsers free of Minecraft types
 * and therefore unit-testable without booting the game.
 *
 * <p>Defaults follow the glTF 2.0 spec: a material with no explicit values is white, fully
 * opaque, metallic 1, roughness 1. Note that a file omitting {@code metallicFactor} gets
 * metallic 1.0, not 0.0 - a mesh that renders black or mirror-like usually means the exporter
 * relied on a default that no longer applies.
 *
 * <h2>Known limitation: only texture coordinate set 0</h2>
 * glTF's {@code textureInfo.texCoord} selects which {@code TEXCOORD_n} set a texture samples, and
 * this class has nowhere to record it, so every texture samples UV0. That is not hypothetical: the
 * 43 MB {@code pbr_sukhoi_su-30.glb} in the reference corpus uses {@code texCoord} 1 and 2 on some
 * slots, so those few surfaces will sample the wrong UV set. The parser reports the slot rather
 * than silently dropping the texture, and the model still renders - but a two-UV-set model is a
 * case this API does not yet support, which is why it is written down here instead of being left
 * to be discovered from a wrong-looking texture.
 */
public final class ModelMaterial {

    public enum AlphaMode {
        /** Fully opaque; alpha in the texture is ignored. */
        OPAQUE,
        /** Alpha-tested against {@link #alphaCutoff}. */
        MASK,
        /** Alpha-blended. */
        BLEND
    }

    private final String name;
    private final float[] baseColorFactor;
    private final String baseColorTexture;
    private final String metallicRoughnessTexture;
    private final String normalTexture;
    private final String occlusionTexture;
    private final String emissiveTexture;
    private final float[] emissiveFactor;
    private final float metallicFactor;
    private final float roughnessFactor;
    private final AlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;
    private final float normalScale;
    private final float occlusionStrength;

    private ModelMaterial(Builder builder) {
        this.name = builder.name;
        this.baseColorFactor = builder.baseColorFactor;
        this.baseColorTexture = builder.baseColorTexture;
        this.metallicRoughnessTexture = builder.metallicRoughnessTexture;
        this.normalTexture = builder.normalTexture;
        this.occlusionTexture = builder.occlusionTexture;
        this.emissiveTexture = builder.emissiveTexture;
        this.emissiveFactor = builder.emissiveFactor;
        this.metallicFactor = builder.metallicFactor;
        this.roughnessFactor = builder.roughnessFactor;
        this.alphaMode = builder.alphaMode;
        this.alphaCutoff = builder.alphaCutoff;
        this.doubleSided = builder.doubleSided;
        this.normalScale = builder.normalScale;
        this.occlusionStrength = builder.occlusionStrength;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Default material used when a primitive names no material, or an OBJ has no MTL. */
    public static ModelMaterial defaultMaterial() {
        return builder("default").build();
    }

    public String name() {
        return name;
    }

    /** RGBA in linear space, length 4. */
    public float[] baseColorFactor() {
        return baseColorFactor.clone();
    }

    public float[] emissiveFactor() {
        return emissiveFactor.clone();
    }

    public float metallicFactor() {
        return metallicFactor;
    }

    public float roughnessFactor() {
        return roughnessFactor;
    }

    public AlphaMode alphaMode() {
        return alphaMode;
    }

    public float alphaCutoff() {
        return alphaCutoff;
    }

    public boolean doubleSided() {
        return doubleSided;
    }

    public float normalScale() {
        return normalScale;
    }

    public float occlusionStrength() {
        return occlusionStrength;
    }

    public String baseColorTexture() {
        return baseColorTexture;
    }

    public String metallicRoughnessTexture() {
        return metallicRoughnessTexture;
    }

    public String normalTexture() {
        return normalTexture;
    }

    public String occlusionTexture() {
        return occlusionTexture;
    }

    public String emissiveTexture() {
        return emissiveTexture;
    }

    public boolean hasAnyTexture() {
        return baseColorTexture != null || metallicRoughnessTexture != null
                || normalTexture != null || occlusionTexture != null || emissiveTexture != null;
    }

    @Override
    public String toString() {
        return "ModelMaterial('" + name + "' baseColorTex=" + baseColorTexture
                + " alpha=" + alphaMode + " doubleSided=" + doubleSided + ")";
    }

    public static final class Builder {

        private final String name;
        private float[] baseColorFactor = { 1, 1, 1, 1 };
        private String baseColorTexture;
        private String metallicRoughnessTexture;
        private String normalTexture;
        private String occlusionTexture;
        private String emissiveTexture;
        private float[] emissiveFactor = { 0, 0, 0 };
        private float metallicFactor = 1.0f;
        private float roughnessFactor = 1.0f;
        private AlphaMode alphaMode = AlphaMode.OPAQUE;
        private float alphaCutoff = 0.5f;
        private boolean doubleSided;
        private float normalScale = 1.0f;
        private float occlusionStrength = 1.0f;

        private Builder(String name) {
            this.name = name == null ? "unnamed" : name;
        }

        public Builder baseColorFactor(float r, float g, float b, float a) {
            this.baseColorFactor = new float[] { r, g, b, a };
            return this;
        }

        public Builder baseColorTexture(String path) {
            this.baseColorTexture = path;
            return this;
        }

        public Builder metallicRoughnessTexture(String path) {
            this.metallicRoughnessTexture = path;
            return this;
        }

        public Builder normalTexture(String path, float scale) {
            this.normalTexture = path;
            this.normalScale = scale;
            return this;
        }

        public Builder occlusionTexture(String path, float strength) {
            this.occlusionTexture = path;
            this.occlusionStrength = strength;
            return this;
        }

        public Builder emissiveTexture(String path) {
            this.emissiveTexture = path;
            return this;
        }

        public Builder emissiveFactor(float r, float g, float b) {
            this.emissiveFactor = new float[] { r, g, b };
            return this;
        }

        public Builder metallicFactor(float value) {
            this.metallicFactor = value;
            return this;
        }

        public Builder roughnessFactor(float value) {
            this.roughnessFactor = value;
            return this;
        }

        public Builder alphaMode(AlphaMode mode) {
            this.alphaMode = mode;
            return this;
        }

        public Builder alphaCutoff(float cutoff) {
            this.alphaCutoff = cutoff;
            return this;
        }

        public Builder doubleSided(boolean value) {
            this.doubleSided = value;
            return this;
        }

        public ModelMaterial build() {
            return new ModelMaterial(this);
        }
    }
}
