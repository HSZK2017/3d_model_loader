package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A {@link ModelSource} over a real directory, used for the licensed corpus models.
 *
 * <p>It implements the full path contract of {@link ModelSource} - {@code ../} refusal, percent
 * decoding, and the case-insensitive / file-name fallback - because the corpus files depend on it:
 * {@code scene.gltf} names six textures by relative path, and a parser that quietly used a different
 * resolution rule than the mod's resource layer would pass its tests and fail in game.
 */
final class DirectoryModelSource implements ModelSource {

    private final Path root;
    private final Path main;

    DirectoryModelSource(Path root, Path main) {
        this.root = root.toAbsolutePath().normalize();
        this.main = main.toAbsolutePath().normalize();
    }

    @Override
    public InputStream openMain() throws IOException {
        return Files.newInputStream(main);
    }

    @Override
    public String mainPath() {
        return main.toString();
    }

    @Override
    public Reference open(String relativePath) throws IOException {
        String decoded = percentDecode(relativePath);
        Path resolved = root.resolve(decoded).normalize();
        if (!resolved.startsWith(root)) {
            return Reference.missing("'" + relativePath + "' escapes the model directory");
        }
        if (Files.isRegularFile(resolved)) {
            return new Reference(Files.newInputStream(resolved), decoded,
                    decoded.equals(relativePath), "exact");
        }
        String lower = decoded.toLowerCase(Locale.ROOT);
        for (String candidate : listFiles()) {
            if (candidate.equals(lower)) {
                Path path = root.resolve(candidate);
                return new Reference(Files.newInputStream(path), candidate, false,
                        "case-insensitive match for '" + relativePath + "'");
            }
        }
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        String unique = null;
        for (String candidate : listFiles()) {
            if (candidate.endsWith("/" + fileName) || candidate.equals(fileName)) {
                if (unique != null) {
                    unique = null;
                    break;
                }
                unique = candidate;
            }
        }
        if (unique != null) {
            return new Reference(Files.newInputStream(root.resolve(unique)), unique, false,
                    "file-name match for '" + relativePath + "'");
        }
        return Reference.missing("no file '" + relativePath + "' under " + root);
    }

    @Override
    public List<String> listFiles() throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.add(relative.toLowerCase(Locale.ROOT));
            });
        }
        return files;
    }

    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
