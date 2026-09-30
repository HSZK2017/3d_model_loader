package com.model3d.loader.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A {@link com.model3d.loader.format.ModelSource} backed by a directory on disk.
 *
 * <p>Two things use this: dropping an unpacked model into {@code <gamedir>/model3d/<name>/} for
 * iteration without rebuilding a jar, and the offline tools/tests, which have no
 * {@code ResourceManager} at all.
 *
 * <p>Resolution tries the exact case first and then a case-insensitive walk. The order matters
 * on a case-sensitive filesystem (Linux, and a zip read through a jar), where two files whose
 * names differ only in case are two different files, and the exact match is the one the model
 * file actually asked for.
 */
public final class DirectoryModelSource extends AbstractModelSource {

    private final Path root;
    /** Every path in the tree, real case, relative and {@code /}-separated. Built on first use. */
    private List<String> allFiles;

    public DirectoryModelSource(Path root, String namespace, String mainRelativePath) {
        super(namespace, root.toString().replace('\\', '/'), mainRelativePath);
        this.root = root;
    }

    @Override
    protected List<String> listRelativeFiles() throws IOException {
        if (allFiles != null) {
            return allFiles;
        }
        if (!Files.isDirectory(root)) {
            allFiles = List.of();
            return allFiles;
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.add(relative);
            });
        }
        files.sort(String::compareTo);
        this.allFiles = files;
        return allFiles;
    }

    @Override
    protected InputStream openExact(String relativePath) throws IOException {
        Path direct = root.resolve(relativePath);
        if (Files.isRegularFile(direct)) {
            return Files.newInputStream(direct);
        }
        // Case-insensitive pass. Done against the walked tree rather than with a filesystem
        // lookup so the behaviour is identical on Windows (case-insensitive anyway) and on a
        // case-sensitive filesystem where the caller's case is merely wrong.
        for (String candidate : listRelativeFiles()) {
            if (candidate.equalsIgnoreCase(relativePath)) {
                return Files.newInputStream(root.resolve(candidate));
            }
        }
        return null;
    }
}
