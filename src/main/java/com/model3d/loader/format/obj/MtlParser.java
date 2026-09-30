package com.model3d.loader.format.obj;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code .mtl} text into raw material definitions.
 *
 * <p>Two properties of this format drive the code below.
 *
 * <p><b>An MTL defect must never fail a load.</b> The geometry lives in the OBJ and is perfectly
 * usable without materials, so every problem here is a diagnostic rather than an exception. That is
 * the opposite of the OBJ reader, where a bad face index means the geometry itself is wrong.
 *
 * <p><b>Texture statements carry option flags <i>before</i> the path</b> - {@code -s 1 1 1},
 * {@code -o 0 0 0}, {@code -bm 0.2}, {@code -clamp on} - and the path may contain spaces and
 * backslashes. Splitting the line on whitespace and taking the last token therefore loses both the
 * flags and most of the filename ("My Texture.png" becomes "Texture.png"), which is the single most
 * common MTL parsing bug. The parser walks the option list and then rejoins everything that is left.
 */
final class MtlParser {

    /** Option flags whose argument count is fixed, counted without the flag itself. */
    private static final Map<String, Integer> FIXED_OPTIONS = Map.ofEntries(
            Map.entry("-blendu", 1),
            Map.entry("-blendv", 1),
            Map.entry("-bm", 1),
            Map.entry("-boost", 1),
            Map.entry("-cc", 1),
            Map.entry("-clamp", 1),
            Map.entry("-imfchan", 1),
            Map.entry("-texres", 1),
            Map.entry("-type", 1),
            Map.entry("-mm", 2));

    /** Longest option argument list, used to bound the numeric scan of {@code -o}/{@code -s}/{@code -t}. */
    private static final int MAX_VECTOR_OPTION_ARGUMENTS = 3;

    private MtlParser() {
    }

    /**
     * Parses one MTL file.
     *
     * @param text    file contents
     * @param mtlPath path the text came from; recorded so texture paths can be resolved relative to
     *                the MTL's own directory
     * @return definitions in declaration order, keyed by material name; a later {@code newmtl} with
     *         the same name replaces the earlier one
     */
    static Map<String, ObjMaterialDefinition> parse(String text, String mtlPath, ParseLog log) {
        Map<String, ObjMaterialDefinition> definitions = new LinkedHashMap<>();
        Set<String> reportedOnce = new HashSet<>();
        ObjMaterialDefinition current = null;
        for (ObjText.Statement statement : ObjText.statements(text)) {
            String[] tokens = statement.tokens();
            String keyword = tokens[0].toLowerCase(Locale.ROOT);
            if (keyword.equals("newmtl")) {
                String name = statement.remainder();
                if (name.isEmpty()) {
                    noteOnce(reportedOnce, "newmtl-empty", log, mtlPath,
                            "a newmtl statement has no name; its statements are ignored");
                    current = null;
                    continue;
                }
                current = new ObjMaterialDefinition(name, mtlPath);
                definitions.put(name, current);
                continue;
            }
            if (current == null) {
                // A header, a vendor block or a stray statement before the first newmtl has no
                // material to belong to; not worth a warning, but worth one line in a debug log.
                noteOnce(reportedOnce, "orphan", log, mtlPath,
                        "statement '" + keyword + "' appears before the first newmtl and is ignored");
                continue;
            }
            switch (keyword) {
                case "kd" -> {
                    float[] rgb = readColor(statement, mtlPath, log);
                    if (rgb != null) {
                        current.diffuse = rgb;
                    }
                }
                case "ke" -> {
                    float[] rgb = readColor(statement, mtlPath, log);
                    if (rgb != null) {
                        current.emissive = clampNonNegative(rgb);
                        current.hasEmissive = true;
                    }
                }
                case "ka", "ks" -> noteOnce(reportedOnce, "ka-ks-" + keyword, log, mtlPath,
                        "'" + tokens[0] + "' is ignored: ambient and specular colours have no slot in a "
                                + "metallic/roughness material, and faking them as emissive would light up the model");
                case "ns" -> {
                    Float ns = number(tokens.length > 1 ? tokens[1] : null);
                    if (ns == null) {
                        warn(log, mtlPath, statement, "Ns needs a number, found '" + tail(statement) + "'");
                    } else {
                        // Specular exponent -> roughness. There is no exact conversion between Phong's
                        // Ns and a metal/roughness workflow; sqrt(2/(Ns+2)) is the common approximation
                        // and it keeps both ends right: Ns=0 (no highlight) -> 1 (fully rough),
                        // Ns->infinity (mirror) -> 0.
                        double roughness = Math.sqrt(2.0 / (Math.max(ns, 0.0f) + 2.0));
                        current.roughness = clamp01((float) roughness);
                    }
                }
                case "d" -> {
                    Float dissolve = readDissolve(tokens);
                    if (dissolve == null) {
                        warn(log, mtlPath, statement, "d needs a number, found '" + tail(statement) + "'");
                    } else {
                        current.alpha = clamp01(dissolve);
                    }
                }
                case "tr" -> {
                    // Tr is the inverse of d: Tr = 1 - d. A few exporters wrote Tr as the transparency
                    // of an already-opaque surface, so both spellings are honoured and the last one in
                    // the file wins.
                    Float transparency = number(tokens.length > 1 ? tokens[1] : null);
                    if (transparency == null) {
                        warn(log, mtlPath, statement, "Tr needs a number, found '" + tail(statement) + "'");
                    } else {
                        current.alpha = clamp01(1.0f - transparency);
                    }
                }
                case "illum" -> {
                    Float value = number(tokens.length > 1 ? tokens[1] : null);
                    if (value == null) {
                        warn(log, mtlPath, statement, "illum needs a model number, found '" + tail(statement) + "'");
                    } else {
                        current.illumination = Math.round(value);
                        if (current.illumination < 0 || current.illumination > 10) {
                            noteOnce(reportedOnce, "illum-range", log, mtlPath,
                                    "illum " + current.illumination + " is outside the 0..10 models the spec defines; "
                                            + "it is recorded but not interpreted");
                        }
                    }
                }
                // Older exporters spell the bump map every way imaginable; all of them mean the same
                // texture slot. 'map_normal'/'norm' are read as well because some tools emit them.
                case "map_kd" -> current.baseColorTexture = readTexturePath(statement, mtlPath, log, reportedOnce);
                case "map_ke" -> current.emissiveTexture = readTexturePath(statement, mtlPath, log, reportedOnce);
                case "map_bump", "bump", "norm", "normal", "map_normal" ->
                        current.normalTexture = readTexturePath(statement, mtlPath, log, reportedOnce);
                case "map_d" -> {
                    // An alpha map belongs in the base colour's alpha channel, and ModelMaterial has no
                    // separate alpha texture slot; wiring it into baseColorTexture would tint the whole
                    // surface by a cutout mask. So it is reported instead of misapplied.
                    current.alphaTexture = readTexturePath(statement, mtlPath, log, reportedOnce);
                }
                case "map_ka", "map_ks", "map_ns", "map_pr", "map_refl", "refl",
                     "disp", "decal", "map_aat" -> noteOnce(reportedOnce, "map-" + keyword, log, mtlPath,
                        "'" + tokens[0] + "' texture slots are ignored: lighting and displacement maps from an "
                                + "OBJ-era material have no equivalent in the scene's material model");
                case "ni", "tf", "sharpness", "halo", "bump_multiplier", "pr", "pm" ->
                        noteOnce(reportedOnce, "scalar-" + keyword, log, mtlPath,
                                "'" + tokens[0] + "' is ignored: it has no equivalent in this material model");
                default -> noteOnce(reportedOnce, "stmt-" + keyword, log, mtlPath,
                        "unsupported MTL statement '" + keyword + "' is ignored");
            }
        }
        return definitions;
    }

    /**
     * Reads a colour, tolerating the grayscale shorthand ({@code Kd 0.5}) and rejecting the
     * {@code spectral}/{@code xyz} forms that no real-time renderer uses.
     */
    private static float[] readColor(ObjText.Statement statement, String mtlPath, ParseLog log) {
        String[] tokens = statement.tokens();
        String keyword = tokens[0];
        if (tokens.length > 1 && (tokens[1].equalsIgnoreCase("spectral") || tokens[1].equalsIgnoreCase("xyz"))) {
            warn(log, mtlPath, statement, keyword + " uses the '" + tokens[1]
                    + "' colour form, which this loader cannot interpret; the material keeps its default colour");
            return null;
        }
        if (tokens.length == 2) {
            Float gray = number(tokens[1]);
            if (gray != null) {
                return new float[] { clamp01(gray), clamp01(gray), clamp01(gray) };
            }
        }
        if (tokens.length >= 4) {
            Float r = number(tokens[1]);
            Float g = number(tokens[2]);
            Float b = number(tokens[3]);
            if (r != null && g != null && b != null) {
                return new float[] { clamp01(r), clamp01(g), clamp01(b) };
            }
        }
        warn(log, mtlPath, statement, keyword + " needs 1 or 3 numbers, found '" + tail(statement) + "'");
        return null;
    }

    /** {@code d}, including the non-standard {@code d -halo 0.5} spelling. */
    private static Float readDissolve(String[] tokens) {
        int index = 1;
        if (index < tokens.length && tokens[index].equalsIgnoreCase("-halo")) {
            index++;
        }
        return index < tokens.length ? number(tokens[index]) : null;
    }

    /**
     * Extracts the path of a texture statement, stepping over its option flags.
     *
     * <p>Everything after the options is the path, rejoined with single spaces so that a filename
     * containing spaces survives. Runs of whitespace inside such a filename are not preserved, which
     * no exporter has been seen to rely on.
     */
    private static String readTexturePath(ObjText.Statement statement, String mtlPath, ParseLog log,
                                          Set<String> reportedOnce) {
        String[] tokens = statement.tokens();
        String keyword = tokens[0];
        int index = 1;
        while (index < tokens.length && looksLikeOptionFlag(tokens[index])) {
            String flag = tokens[index].toLowerCase(Locale.ROOT);
            Integer fixed = FIXED_OPTIONS.get(flag);
            if (fixed != null) {
                index += fixed + 1;
                continue;
            }
            if (flag.equals("-o") || flag.equals("-s") || flag.equals("-t")) {
                // 1..3 numbers, and there is no way to tell from the flag alone how many. Scanning
                // while the tokens are numeric handles both '-s 1 1 1 path' and '-s 2 path'.
                index = skipNumbers(tokens, index + 1, MAX_VECTOR_OPTION_ARGUMENTS);
                continue;
            }
            // Unknown flag: step over it and any numeric arguments, so a vendor flag with numbers
            // does not swallow the path.
            index = skipNumbers(tokens, index + 1, Integer.MAX_VALUE);
            noteOnce(reportedOnce, "texflag-" + flag, log, mtlPath,
                    "unknown texture option '" + flag + "' on " + keyword + "; it is skipped");
        }
        if (index >= tokens.length) {
            warn(log, mtlPath, statement, keyword + " names no texture file");
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = index; i < tokens.length; i++) {
            if (path.length() > 0) {
                path.append(' ');
            }
            path.append(tokens[i]);
        }
        return path.toString();
    }

    /**
     * True for tokens that plausibly name an option flag: a dash followed by letters only. A path
     * such as {@code -texture.png} must not be mistaken for one, which is why a dot disqualifies it.
     */
    private static boolean looksLikeOptionFlag(String token) {
        if (token.length() < 2 || token.charAt(0) != '-') {
            return false;
        }
        for (int i = 1; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int skipNumbers(String[] tokens, int index, int limit) {
        int skipped = 0;
        while (index < tokens.length && skipped < limit && isNumber(tokens[index])) {
            index++;
            skipped++;
        }
        return index;
    }

    private static boolean isNumber(String token) {
        return number(token) != null;
    }

    private static Float number(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            float value = Float.parseFloat(token);
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float[] clampNonNegative(float[] value) {
        return new float[] { Math.max(value[0], 0.0f), Math.max(value[1], 0.0f), Math.max(value[2], 0.0f) };
    }

    private static float clamp01(float value) {
        return Math.min(Math.max(value, 0.0f), 1.0f);
    }

    private static String tail(ObjText.Statement statement) {
        String remainder = statement.remainder();
        return remainder.isEmpty() ? statement.text() : remainder;
    }

    private static void warn(ParseLog log, String mtlPath, ObjText.Statement statement, String message) {
        log.warn("MTL " + mtlPath + ":" + statement.line() + ": " + message);
    }

    private static void noteOnce(Set<String> reportedOnce, String key, ParseLog log, String mtlPath, String message) {
        if (reportedOnce.add(key)) {
            log.debug("MTL " + mtlPath + ": " + message);
        }
    }
}
