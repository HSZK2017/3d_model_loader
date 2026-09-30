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
 * Binary glTF 2.0 ({@code .glb}): a 12-byte header, an optional JSON chunk and a binary buffer chunk
 * in a single file.
 *
 * <p>Container parsing lives here; everything after the JSON/BIN pair is {@link GltfSceneBuilder},
 * shared with the text {@code .gltf} reader, so the two formats cannot diverge in how they interpret
 * a node, an accessor or an animation.
 *
 * <p>The BIN chunk is handed to {@link GltfBuffers} rather than to the builder directly: a {@code .glb}
 * may also reference sibling buffers by uri, and both routes end up in the same {@code buffers[]}
 * table, so only the container knows which are which.
 */
public final class GlbParser implements ModelParser {

    @Override
    public ModelFormat format() {
        return ModelFormat.GLB;
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
        // GlbContainer closes the stream, and takes it as a whole: the header's declared length is
        // only checkable against the real length once the file has been read.
        GlbContainer container = GlbContainer.read(main, path);
        JsonObject root = JsonParser.parseObject(container.json(), GlbContainer.jsonChunkName(path));
        GltfBuffers buffers = GltfBuffers.fromGlb(root, container.bin(), source);
        return GltfSceneBuilder.build(root, buffers, requestedName, path);
    }
}
