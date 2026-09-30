package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.ModelParser;
import com.model3d.loader.format.ModelSource;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.format.json.JsonParser;
import com.model3d.loader.scene.ModelScene;

import java.io.IOException;
import java.io.InputStream;

/**
 * Text glTF 2.0 ({@code .gltf}): JSON that references external {@code .bin} buffers and image files
 * by relative path. Shares {@link GltfSceneBuilder} with {@link GlbParser}.
 *
 * <p>Every reference is resolved through {@link ModelSource}, and never by touching the file system:
 * a model may live in a jar, a zip, a datapack or a plain directory, and the case-insensitive
 * fallback that makes {@code Textures/Glass_Cockpit.jpeg} resolvable is the source's job. What this
 * class owns is the JSON and the {@code buffers[]} table; what it refuses is a uri that points
 * outside the model's own directory, since a model file is untrusted input.
 */
public final class GltfParser implements ModelParser {

    @Override
    public ModelFormat format() {
        return ModelFormat.GLTF;
    }

    @Override
    public ModelScene parse(ModelSource source, String requestedName) throws ModelParseException {
        String path = source.mainPath();
        InputStream main;
        try {
            main = source.openMain();
        } catch (IOException e) {
            throw ModelParseException.at(path, "cannot open the model file: " + e.getMessage(), e);
        }
        if (main == null) {
            throw ModelParseException.at(path, "the model file does not exist");
        }
        JsonObject root;
        try (InputStream stream = main) {
            root = JsonParser.parseObject(stream, path);
        } catch (IOException e) {
            throw ModelParseException.at(path, "cannot read the model file: " + e.getMessage(), e);
        }
        GltfBuffers buffers = GltfBuffers.fromFiles(root, source);
        return GltfSceneBuilder.build(root, buffers, requestedName, path);
    }
}
