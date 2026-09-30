package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link ModelSource} over in-memory content, so a test can hand the parser a hand-written glTF and
 * the exact bytes its accessors point at without writing a temporary directory.
 *
 * <p>Resolution is deliberately strict (exact path, no fallback) unlike the real resource layer:
 * a synthetic test that passes because of a case-insensitive fallback would be testing the fallback,
 * not the parser.
 */
final class MemoryModelSource implements ModelSource {

    private final String mainPath;
    private final byte[] main;
    private final Map<String, byte[]> siblings = new LinkedHashMap<>();

    MemoryModelSource(String mainPath, String mainContent) {
        this(mainPath, mainContent.getBytes(StandardCharsets.UTF_8));
    }

    MemoryModelSource(String mainPath, byte[] mainContent) {
        this.mainPath = mainPath;
        this.main = mainContent;
    }

    static MemoryModelSource gltf(String json) {
        return new MemoryModelSource("model.gltf", json);
    }

    /** A binary model (a {@code .glb} container), for the container tests. */
    static MemoryModelSource binary(String mainPath, byte[] content) {
        return new MemoryModelSource(mainPath, content);
    }

    /** Shares the source with a sibling file, e.g. {@code scene.bin}. */
    MemoryModelSource with(String relativePath, byte[] content) {
        siblings.put(relativePath, content);
        return this;
    }

    MemoryModelSource with(String relativePath, String content) {
        return with(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream openMain() {
        return new ByteArrayInputStream(main);
    }

    @Override
    public String mainPath() {
        return mainPath;
    }

    @Override
    public Reference open(String relativePath) {
        byte[] content = siblings.get(relativePath);
        if (content == null) {
            return Reference.missing("no file '" + relativePath + "' (present: " + siblings.keySet()
                    + ")");
        }
        return new Reference(new ByteArrayInputStream(content), relativePath, true, "exact");
    }

    @Override
    public List<String> listFiles() {
        return new ArrayList<>(siblings.keySet());
    }

    @Override
    public void close() throws IOException {
    }
}
