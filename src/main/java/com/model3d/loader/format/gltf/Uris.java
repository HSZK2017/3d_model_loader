package com.model3d.loader.format.gltf;

import com.model3d.loader.format.ModelParseException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * URI handling shared by buffer and image references.
 *
 * <p>glTF allows a reference to be a relative path, a {@code data:} URI, or - in files that expect a
 * networked viewer - an absolute or remote URL. This mod loads models from a resource pack it owns,
 * so only the first two can be served; the third must be reported rather than silently treated as a
 * missing file, because "texture not found" and "this model needs the internet" are different
 * problems for the person reading the log.
 *
 * <p>Escape checking happens <b>after</b> percent-decoding: {@code %2e%2e%2f} is {@code ../}, and a
 * check that runs on the raw string would wave it through. A model file is untrusted input.
 */
final class Uris {

    private Uris() {
    }

    /** True when the reference is a {@code data:} URI rather than a path. */
    static boolean isDataUri(String uri) {
        return uri.regionMatches(true, 0, "data:", 0, 5);
    }

    /**
     * Decodes a {@code data:} URI to bytes, for both buffer payloads and embedded images.
     *
     * <p>Base64 payloads go through the MIME decoder, which tolerates the line wrapping some
     * exporters add; non-base64 payloads are percent-decoded raw bytes. Both forms are used in the
     * wild, and a {@code data:} URI that decodes to the encoded bytes of a PNG/JPEG is exactly what
     * {@code ModelImage} wants - the same bytes a sibling file would have held.
     */
    static byte[] decodeDataUri(String uri, String element) throws ModelParseException {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            throw ModelParseException.at(element, "the data uri has no ',' separating the header from "
                    + "the payload");
        }
        String header = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        boolean base64 = header.length() >= 7
                && header.regionMatches(true, header.length() - 7, ";base64", 0, 7);
        if (base64) {
            try {
                return Base64.getMimeDecoder().decode(decodePercent(payload, element));
            } catch (IllegalArgumentException e) {
                throw ModelParseException.at(element, "the data uri payload is not valid base64: "
                        + e.getMessage(), e);
            }
        }
        return percentDecodeToBytes(payload, element);
    }

    /**
     * Decodes {@code %XX} escapes to raw bytes, leaving everything else as its UTF-8 encoding.
     *
     * <p><b>Byte-exact by construction.</b> A non-base64 {@code data:} URI carries arbitrary binary
     * as percent-escapes, and decoding it via a {@code String} first would run the bytes through a
     * UTF-8 round trip and replace every invalid sequence with U+FFFD - silently corrupting the
     * buffer, which then fails to parse as geometry somewhere far away from the cause. Characters
     * written literally are UTF-8 encoded so that a URI holding a non-ASCII path still resolves.
     */
    static byte[] percentDecodeToBytes(String value, String element) throws ModelParseException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw ModelParseException.at(element,
                            "percent-escape at the end of '" + value + "' is incomplete");
                }
                int high = hex(value.charAt(i + 1));
                int low = hex(value.charAt(i + 2));
                if (high < 0 || low < 0) {
                    throw ModelParseException.at(element, "'" + value.substring(i, i + 3)
                            + "' is not a valid percent-escape in '" + value + "'");
                }
                out.write((high << 4) | low);
                i += 2;
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return out.toByteArray();
    }

    /**
     * Decodes {@code %XX} escapes, interpreting multi-byte sequences as UTF-8. Used for paths, where
     * the result has to be text; binary payloads use {@link #percentDecodeToBytes}.
     */
    static String decodePercent(String value, String element) throws ModelParseException {
        if (value.indexOf('%') < 0) {
            return value;
        }
        return new String(percentDecodeToBytes(value, element), StandardCharsets.UTF_8);
    }

    /**
     * Why {@code decodedUri} cannot be resolved as a sibling file, or null when it can.
     * Returns a complete sentence fragment naming the offending uri.
     */
    static String unsupportedReason(String decodedUri) {
        if (decodedUri.isEmpty()) {
            return "the uri is empty";
        }
        if (decodedUri.startsWith("/") || decodedUri.startsWith("\\")) {
            return "the uri '" + decodedUri + "' is an absolute path; model files may only reference "
                    + "files next to them";
        }
        if (decodedUri.startsWith("\\\\")) {
            return "the uri '" + decodedUri + "' is a UNC path; model files may only reference files "
                    + "next to them";
        }
        int colon = decodedUri.indexOf(':');
        if (colon > 0 && isScheme(decodedUri.substring(0, colon))) {
            return "the uri '" + decodedUri + "' uses the scheme '" + decodedUri.substring(0, colon)
                    + "'; this loader cannot fetch remote or platform-specific locations";
        }
        if (escapesRoot(decodedUri)) {
            return "the uri '" + decodedUri + "' escapes the model's own directory, which is refused "
                    + "because model files are untrusted input";
        }
        return null;
    }

    /** True when the path climbs above its own root once {@code .} and {@code ..} are resolved. */
    static boolean escapesRoot(String path) {
        int depth = 0;
        int start = 0;
        while (start <= path.length()) {
            int slash = path.indexOf('/', start);
            String segment = slash < 0 ? path.substring(start) : path.substring(start, slash);
            if (segment.equals("..")) {
                if (--depth < 0) {
                    return true;
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                depth++;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return false;
    }

    private static boolean isScheme(String candidate) {
        if (candidate.isEmpty() || !Character.isLetter(candidate.charAt(0))) {
            return false;
        }
        for (int i = 1; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
