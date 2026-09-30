package com.model3d.loader.format.obj;

import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelPrimitive;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.model3d.loader.format.obj.ObjTestSupport.DELTA;
import static com.model3d.loader.format.obj.ObjTestSupport.assertSceneShape;
import static com.model3d.loader.format.obj.ObjTestSupport.primitives;
import static com.model3d.loader.format.obj.ObjTestSupport.report;
import static com.model3d.loader.format.obj.ObjTestSupport.triangleCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MTL parsing, texture path resolution and material fallbacks. */
class ObjParserMaterialTest {

    private static final String MAIN = "models/test/model.obj";

    /** One triangle, declared so that faces may follow in a concatenated fixture. */
    private static final String TRIANGLE = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
            """;

    private static final String VERTICES = "v 0 0 0\nv 1 0 0\nv 0 1 0\n";

    private final RecordingParseLog log = new RecordingParseLog();

    private ModelScene parse(String obj, Map<String, String> extraFiles) throws Exception {
        InMemoryModelSource.Builder builder = InMemoryModelSource.builder(MAIN).file(MAIN, obj);
        for (Map.Entry<String, String> file : extraFiles.entrySet()) {
            builder.file(file.getKey(), file.getValue());
        }
        return new ObjParser(log).parse(builder.build(), "model");
    }

    private ModelScene parse(String obj, String mtlPath, String mtl) throws Exception {
        return parse(obj, Map.of(mtlPath, mtl));
    }

    // ----------------------------------------------------------------------- texture path parsing

    @Test
    void mtlParserStripsOptionFlagsAndKeepsTheWholePath() {
        // The classic MTL bug: splitting on whitespace and taking the last token turns this into
        // "Texture.png", and every surface ends up textured from the wrong file.
        String mtl = """
                newmtl flags
                map_Kd -s 1 1 1 -o 0 0 0 C:\\tex\\My Texture.png
                """;
        ObjMaterialDefinition definition = MtlParser.parse(mtl, "materials.mtl", log).get("flags");
        assertNotNull(definition, "the material must have been parsed");
        assertEquals("C:\\tex\\My Texture.png", definition.baseColorTexture,
                "the flags are stripped and the path - spaces, backslashes and all - is preserved");
        assertEquals("C:/tex/My Texture.png", ObjPaths.normalize(definition.baseColorTexture),
                "backslashes become separators only when the path is resolved");
        log.print("mtlParserStripsOptionFlagsAndKeepsTheWholePath");
    }

    @Test
    void mtlParserHandlesEveryOptionShape() {
        String mtl = """
                newmtl options
                map_Kd -blendu on -blendv off -clamp on -bm 0.5 -imfchan r -texres 512 -mm 0 1 -o 0.1 -s 2 2 2 -t 1 1 1 textures\\plain.png
                map_Bump -s 1 1 no_flags_after.png
                """;
        ObjMaterialDefinition definition = MtlParser.parse(mtl, "materials.mtl", log).get("options");
        assertNotNull(definition);
        assertEquals("textures\\plain.png", definition.baseColorTexture);
        assertEquals("no_flags_after.png", definition.normalTexture, "a short -s must not eat the path");
        log.print("mtlParserHandlesEveryOptionShape");
    }

    @Test
    void mtlParserKeepsAQuotedPathWithSpaces() {
        ObjMaterialDefinition definition = MtlParser.parse(
                "newmtl quoted\nmap_Kd \"textures/my base.png\"\n", "materials.mtl", log).get("quoted");
        assertNotNull(definition);
        assertEquals("textures/my base.png", ObjPaths.normalize(definition.baseColorTexture));
        log.print("mtlParserKeepsAQuotedPathWithSpaces");
    }

    @Test
    void texturePathIsResolvedThroughTheModelSource() throws Exception {
        String obj = """
                mtllib materials.mtl
                usemtl camo
                """ + TRIANGLE;
        // The file on disk is lower-case; the MTL names it with a capital letter and a space.
        String mtl = """
                newmtl camo
                map_Kd -s 1 1 1 -o 0 0 0 textures\\Su30 Skin.png
                """;
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, obj)
                .file("materials.mtl", mtl)
                .file("textures/su30 skin.png", "not really a png")
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");

        assertEquals("textures/su30 skin.png", scene.materials()[0].baseColorTexture(),
                "the material must carry the path that actually resolved, not the written one");
        assertTrue(source.requestedPaths().contains("textures/Su30 Skin.png"),
                "the parser must ask for the whole file name, not the part after the last space: "
                        + source.requestedPaths());
        assertFalse(log.warned("was not found"), "a resolvable texture must not warn: " + log.warnMessages());
        report("texturePathIsResolvedThroughTheModelSource", scene);
    }

    @Test
    void texturePathIsResolvedRelativeToTheMtlDirectory() throws Exception {
        // Per the spec an MTL's texture paths are relative to the MTL itself, which here lives in a
        // subdirectory, and the path climbs back out with '..'.
        String obj = """
                mtllib mtl/materials.mtl
                usemtl body
                """ + TRIANGLE;
        String mtl = """
                newmtl body
                map_Kd ../textures/sub.png
                """;
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, obj)
                .file("mtl/materials.mtl", mtl)
                .file("textures/sub.png", "png")
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");
        assertEquals("textures/sub.png", scene.materials()[0].baseColorTexture());
        assertFalse(log.warned("was not found"), log.warnMessages().toString());
        report("texturePathIsResolvedRelativeToTheMtlDirectory", scene);
    }

    @Test
    void unresolvableTextureKeepsTheWrittenPathAndWarnsOnce() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl missing
                f 1 2 3
                usemtl missing
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl missing
                map_Kd C:\\tex\\My Texture.png
                """);

        assertEquals("C:/tex/My Texture.png", scene.materials()[0].baseColorTexture(),
                "an unresolved path is kept so the resource layer can report the same miss");
        assertEquals(1, log.warnCount("My Texture.png"),
                "one warning per unresolved texture, not one per use: " + log.warnMessages());
        assertTrue(log.firstWarning("My Texture.png").contains("C:/tex/My Texture.png"),
                "the warning must name the path the model wanted");
        report("unresolvableTextureKeepsTheWrittenPathAndWarnsOnce", scene);
    }

    // ----------------------------------------------------------------------------- mtllib handling

    @Test
    void severalMtlFilesCanBeLoaded() throws Exception {
        String obj = VERTICES + """
                mtllib a.mtl b.mtl
                mtllib c.mtl
                usemtl fromA
                f 1 2 3
                usemtl fromB
                f 1 2 3
                usemtl fromC
                f 1 2 3
                """;
        ModelScene scene = parse(obj, Map.of(
                "a.mtl", "newmtl fromA\nKd 1 0 0\n",
                "b.mtl", "newmtl fromB\nKd 0 1 0\n",
                "c.mtl", "newmtl fromC\nKd 0 0 1\n"));

        assertEquals(3, scene.materials().length);
        assertEquals("fromA", scene.materials()[0].name());
        assertEquals("fromB", scene.materials()[1].name());
        assertEquals("fromC", scene.materials()[2].name());
        assertFalse(log.warned("was not found"), "all three libraries exist: " + log.warnMessages());
        report("severalMtlFilesCanBeLoaded", scene);
    }

    @Test
    void missingMtlWarnsButStillLoadsTheGeometry() throws Exception {
        ModelScene scene = parse("mtllib not_shipped.mtl\n" + TRIANGLE, Map.of());

        assertEquals(1, triangleCount(scene), "geometry never depends on the material library");
        assertEquals(1, log.warnCount("not_shipped.mtl"));
        assertEquals(1, scene.materials().length);
        assertEquals("default", scene.materials()[0].name());
        assertEquals(1.0f, scene.materials()[0].baseColorFactor()[0], DELTA);
        assertSceneShape(scene);
        report("missingMtlWarnsButStillLoadsTheGeometry", scene);
    }

    @Test
    void objWithoutMtlUsesTheDefaultMaterial() throws Exception {
        ModelScene scene = parse(TRIANGLE, Map.of());
        assertEquals(1, scene.materials().length);
        ModelMaterial material = scene.materials()[0];
        assertEquals("default", material.name());
        assertEquals(1.0f, material.baseColorFactor()[3], DELTA, "alpha of the default material");
        assertEquals(ModelMaterial.AlphaMode.OPAQUE, material.alphaMode());
        assertEquals(1.0f, material.metallicFactor(), DELTA, "the glTF default, untouched without an MTL");
        assertTrue(log.warnMessages().isEmpty(), "an OBJ without materials is not a problem: " + log.warnMessages());
        report("objWithoutMtlUsesTheDefaultMaterial", scene);
    }

    @Test
    void unknownUsemtlFallsBackToTheDefaultMaterialWithOneWarningPerName() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl ghost
                f 1 2 3
                usemtl ghost
                f 1 2 3
                usemtl other_ghost
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", "newmtl known\nKd 1 0 0\n");

        assertEquals(1, scene.materials().length, "both unknown names share the one default material");
        assertEquals("default", scene.materials()[0].name());
        assertEquals(1, log.warnCount("usemtl 'ghost' matches no material"), "two uses, one warning");
        assertEquals(1, log.warnCount("usemtl 'other_ghost' matches no material"),
                "one warning per distinct name");
        assertEquals(2, log.warnMessages().size(), "no other warning is expected: " + log.warnMessages());
        report("unknownUsemtlFallsBackToTheDefaultMaterialWithOneWarningPerName", scene);
    }

    @Test
    void unusedMtlMaterialsAreNotAddedToTheScene() throws Exception {
        String obj = "mtllib materials.mtl\nusemtl used\n" + TRIANGLE;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl unused
                Kd 1 0 0
                newmtl used
                Kd 0 1 0
                """);
        assertEquals(1, scene.materials().length);
        assertEquals("used", scene.materials()[0].name());
        report("unusedMtlMaterialsAreNotAddedToTheScene", scene);
    }

    // ---------------------------------------------------------------------------- material fields

    @Test
    void nsMapsToRoughness() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl glossy
                f 1 2 3
                usemtl rough
                f 1 2 3
                usemtl mirror
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl glossy
                Ns 2
                newmtl rough
                Ns 0
                newmtl mirror
                Ns 1000
                """);
        assertEquals(0.70710678f, scene.materials()[0].roughnessFactor(), DELTA, "sqrt(2/(2+2))");
        assertEquals(1.0f, scene.materials()[1].roughnessFactor(), DELTA, "Ns 0 is fully rough");
        assertEquals(Math.sqrt(2.0 / 1002.0), scene.materials()[2].roughnessFactor(), DELTA, "sqrt(2/(1000+2))");
        System.out.println("[obj-test] nsMapsToRoughness: Ns=2 -> " + scene.materials()[0].roughnessFactor()
                + ", Ns=0 -> " + scene.materials()[1].roughnessFactor()
                + ", Ns=1000 -> " + scene.materials()[2].roughnessFactor());
    }

    @Test
    void dissolveAndTransparencyDriveAlpha() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl half
                f 1 2 3
                usemtl opaque
                f 1 2 3
                usemtl transparent
                f 1 2 3
                usemtl overridden
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl half
                d 0.5
                newmtl opaque
                d 1.0
                newmtl transparent
                Tr 0.25
                newmtl overridden
                d 0.5
                Tr 0.0
                """);
        assertEquals(0.5f, scene.materials()[0].baseColorFactor()[3], DELTA, "d 0.5");
        assertEquals(ModelMaterial.AlphaMode.BLEND, scene.materials()[0].alphaMode());
        assertEquals(1.0f, scene.materials()[1].baseColorFactor()[3], DELTA, "d 1.0");
        assertEquals(ModelMaterial.AlphaMode.OPAQUE, scene.materials()[1].alphaMode());
        assertEquals(0.75f, scene.materials()[2].baseColorFactor()[3], DELTA, "Tr 0.25 means alpha 0.75");
        assertEquals(ModelMaterial.AlphaMode.BLEND, scene.materials()[2].alphaMode());
        assertEquals(1.0f, scene.materials()[3].baseColorFactor()[3], DELTA, "the last of d/Tr wins");
        assertEquals(ModelMaterial.AlphaMode.OPAQUE, scene.materials()[3].alphaMode());
        System.out.println("[obj-test] dissolveAndTransparencyDriveAlpha: alphas="
                + scene.materials()[0].baseColorFactor()[3] + "," + scene.materials()[1].baseColorFactor()[3]
                + "," + scene.materials()[2].baseColorFactor()[3] + "," + scene.materials()[3].baseColorFactor()[3]);
    }

    @Test
    void colorsAndIlluminationAreMapped() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl painted
                f 1 2 3
                usemtl unlit
                f 1 2 3
                usemtl diffuseOnly
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl painted
                Kd 0.2 0.4 0.6
                Ka 1 1 1
                Ks 0.5 0.5 0.5
                Ke 0.1 0.2 0.3
                newmtl unlit
                Kd 1 0 0
                illum 0
                newmtl diffuseOnly
                Kd 0 1 0
                illum 1
                """);
        ModelMaterial painted = scene.materials()[0];
        assertEquals(0.2f, painted.baseColorFactor()[0], DELTA);
        assertEquals(0.4f, painted.baseColorFactor()[1], DELTA);
        assertEquals(0.6f, painted.baseColorFactor()[2], DELTA);
        assertEquals(1.0f, painted.baseColorFactor()[3], DELTA, "Kd sets no alpha");
        assertEquals(0.1f, painted.emissiveFactor()[0], DELTA, "Ke is the emissive factor");
        assertEquals(0.3f, painted.emissiveFactor()[2], DELTA);
        assertEquals(0.0f, painted.metallicFactor(), DELTA, "an MTL surface is a dielectric");
        assertEquals(1.0f, painted.roughnessFactor(), DELTA, "no Ns means fully rough");
        assertTrue(log.debugged("'Ks' is ignored"), "Ks has no slot and must be reported: " + log.debugMessages());

        ModelMaterial unlit = scene.materials()[1];
        assertEquals(0.0f, unlit.metallicFactor(), DELTA);
        assertEquals(1.0f, unlit.emissiveFactor()[0], DELTA, "illum 0 is a constant colour: emissive = Kd");
        assertTrue(log.debugged("illum 0 (unlit)"), "the unlit approximation must be stated");

        ModelMaterial diffuseOnly = scene.materials()[2];
        assertEquals(0.0f, diffuseOnly.metallicFactor(), DELTA);
        assertEquals(0.0f, diffuseOnly.emissiveFactor()[0], DELTA, "illum 1 is lit, so it must not glow");
        report("colorsAndIlluminationAreMapped", scene);
    }

    @Test
    void textureSlotsAreMappedAndUnsupportedOnesReported() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl bumpy
                f 1 2 3
                usemtl ambient
                f 1 2 3
                usemtl cutout
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", """
                newmtl bumpy
                map_Kd textures/base.png
                bump textures/normal.png
                newmtl ambient
                map_Ka textures/ambient.png
                map_Ks textures/specular.png
                newmtl cutout
                map_d textures/mask.png
                """);

        ModelMaterial bumpy = scene.materials()[0];
        assertEquals("textures/base.png", bumpy.baseColorTexture());
        assertEquals("textures/normal.png", bumpy.normalTexture(), "'bump' is a normal map spelling");
        assertEquals(1.0f, bumpy.normalScale(), DELTA);

        ModelMaterial ambient = scene.materials()[1];
        assertFalse(ambient.hasAnyTexture(), "map_Ka/map_Ks have no slot and must not be guessed into one");
        assertTrue(log.debugged("'map_Ka' texture slots are ignored"));
        assertTrue(log.debugged("'map_Ks' texture slots are ignored"));

        ModelMaterial cutout = scene.materials()[2];
        assertFalse(cutout.hasAnyTexture(), "map_d must not be applied to the wrong slot");
        assertEquals(1, log.warnCount("map_d"), "map_d is unsupported and reported as such: " + log.warnMessages());
        assertTrue(log.firstWarning("map_d").contains("cutout"), "the warning must name the material");
        report("textureSlotsAreMappedAndUnsupportedOnesReported", scene);
    }

    @Test
    void mapKeBecomesTheEmissiveTextureWithAWhiteFactor() throws Exception {
        // Emissive maps are multiplied by the emissive factor, whose glTF default is black: an MTL that
        // ships only the map must get a white factor or the surface stays dark.
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl glow
                f 1 2 3
                usemtl tinted
                f 1 2 3
                """;
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, obj)
                .file("materials.mtl", """
                        newmtl glow
                        map_Ke textures/glow.png
                        newmtl tinted
                        Ke 0.5 0 0
                        map_Ke textures/glow.png
                        """)
                .file("textures/glow.png", "png")
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");

        assertEquals("textures/glow.png", scene.materials()[0].emissiveTexture());
        assertEquals(1.0f, scene.materials()[0].emissiveFactor()[0], DELTA, "map without Ke needs a white factor");
        assertEquals(1.0f, scene.materials()[0].emissiveFactor()[1], DELTA);
        assertEquals(0.5f, scene.materials()[1].emissiveFactor()[0], DELTA, "an explicit Ke wins over the default");
        assertEquals(0.0f, scene.materials()[1].emissiveFactor()[1], DELTA);
        assertFalse(log.debugged("'map_Ke' texture slots are ignored"), "map_Ke is mapped, not ignored");
        report("mapKeBecomesTheEmissiveTextureWithAWhiteFactor", scene);
    }

    @Test
    void mapBumpAndNormalSpellingsAllBecomeTheNormalTexture() {
        for (String statement : new String[] { "map_Bump textures/n.png", "bump textures/n.png",
                "normal textures/n.png", "map_bump -bm 0.5 textures/n.png" }) {
            ObjMaterialDefinition definition = MtlParser.parse("newmtl m\n" + statement + "\n",
                    "materials.mtl", log).get("m");
            assertNotNull(definition, statement);
            assertNotNull(definition.normalTexture, statement + " must set the normal texture");
            assertEquals("textures/n.png", definition.normalTexture.replace('\\', '/'), statement);
        }
        log.print("mapBumpAndNormalSpellingsAllBecomeTheNormalTexture");
    }

    @Test
    void malformedMtlValuesAreReportedNotFatal() throws Exception {
        String obj = "mtllib materials.mtl\nusemtl broken\n" + TRIANGLE;
        ModelScene scene = parse(obj, "materials.mtl", """
                # a header comment
                Kd 1 1 1
                newmtl broken
                Kd 0.1 0.2 0.3 trailing-garbage
                Ns not-a-number
                d
                map_Kd
                illum
                """);

        assertEquals(1, triangleCount(scene), "a broken MTL must not stop the geometry from loading");
        assertEquals(0.1f, scene.materials()[0].baseColorFactor()[0], DELTA);
        assertEquals(0.3f, scene.materials()[0].baseColorFactor()[2], DELTA);
        assertEquals(1.0f, scene.materials()[0].roughnessFactor(), DELTA, "an unreadable Ns keeps the default");
        assertEquals(1.0f, scene.materials()[0].baseColorFactor()[3], DELTA, "'d' with no value keeps alpha 1");
        assertTrue(log.warned("d needs a number"), "the bad value must be reported: " + log.warnMessages());
        assertTrue(log.warned("map_Kd names no texture file"),
                "a texture statement without a path must be reported: " + log.warnMessages());
        assertTrue(log.debugged("before the first newmtl"), "a statement outside a material must be reported");
        report("malformedMtlValuesAreReportedNotFatal", scene);
    }

    @Test
    void mtllibPathWithBackslashesIsNormalized() throws Exception {
        String obj = "mtllib sub\\materials.mtl\nusemtl body\n" + TRIANGLE;
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, obj)
                .file("sub/materials.mtl", "newmtl body\nKd 0 0 1\n")
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");
        assertEquals(1, scene.materials().length);
        assertEquals("body", scene.materials()[0].name());
        assertEquals(0.0f, scene.materials()[0].baseColorFactor()[0], DELTA);
        report("mtllibPathWithBackslashesIsNormalized", scene);
    }

    @Test
    void materialNamesWithSpacesSurvive() throws Exception {
        String obj = "mtllib materials.mtl\nusemtl My Material\n" + TRIANGLE;
        ModelScene scene = parse(obj, "materials.mtl", "newmtl My Material\nKd 0 1 1\n");
        assertEquals("My Material", scene.materials()[0].name());
        assertEquals(1.0f, scene.materials()[0].baseColorFactor()[1], DELTA);
        report("materialNamesWithSpacesSurvive", scene);
    }

    @Test
    void repeatedUsemtlReusesOneMaterialIndex() throws Exception {
        String obj = VERTICES + """
                mtllib materials.mtl
                usemtl body
                f 1 2 3
                g second
                usemtl body
                f 1 2 3
                """;
        ModelScene scene = parse(obj, "materials.mtl", "newmtl body\nKd 0.5 0.5 0.5\n");
        assertEquals(1, scene.materials().length);
        for (ModelPrimitive primitive : primitives(scene)) {
            assertEquals(0, primitive.materialIndex());
        }
        report("repeatedUsemtlReusesOneMaterialIndex", scene);
    }
}
