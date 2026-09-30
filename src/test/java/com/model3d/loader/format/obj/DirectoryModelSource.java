package com.model3d.loader.format.obj;

import com.model3d.loader.format.ModelSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A {@link ModelSource} over a directory on disk, used only by the opt-in test that runs against real
 * model files.
 *
 * <p>Same contract as {@link InMemoryModelSource} - relative paths, case-insensitive fallback,
 * {@code ../} refusal - because a parser that only works under the in-memory double would not be
 * evidence of anything.
 */
final class DirectoryModelSource implements ModelSource {

    private final Path mainFile;
    private final Path root;
    private final List<String> files = new ArrayList<>();

    private DirectoryModelSource(Path mainFile) throws IOException {
        this.mainFile = mainFile;
        this.root = mainFile.toAbsolutePath().getParent();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path ->
                    files.add(root.relativize(path).toString().replace('\\', '/').toLowerCase(Locale.ROOT)));
        }
    }

    static DirectoryModelSource of(Path modelFile) throws IOException {
        return new DirectoryModelSource(modelFile);
    }

    @Override
    public InputStream openMain() throws IOException {
        return Files.isRegularFile(mainFile) ? Files.newInputStream(mainFile) : null;
    }

    @Override
    public String mainPath() {
        return mainFile.toString();
    }

    @Override
    public Reference open(String relativePath) throws IOException {
        String path = relativePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("..")) {
            return Reference.missing("'" + relativePath + "' escapes the model root");
        }
        Path exact = root.resolve(path);
        if (Files.isRegularFile(exact)) {
            return new Reference(Files.newInputStream(exact), path, true, "exact match");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (files.contains(lower)) {
            return new Reference(Files.newInputStream(root.resolve(lower)), lower, false,
                    "case-insensitive match for '" + path + "'");
        }
        return Reference.missing("no file '" + path + "' under " + root);
    }

    @Override
    public List<String> listFiles() {
        return List.copyOf(files);
    }
}
