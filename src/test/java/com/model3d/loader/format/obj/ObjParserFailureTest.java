package com.model3d.loader.format.obj;

import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.Test;

import static com.model3d.loader.format.obj.ObjTestSupport.report;
import static com.model3d.loader.format.obj.ObjTestSupport.triangleCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Malformed OBJ input. The reader fails closed and names the line, because the alternative is a
 * scene that renders subtly wrong geometry with nothing in the log to explain it.
 */
class ObjParserFailureTest {

    private static final String MAIN = "models/test/model.obj";

    private final RecordingParseLog log = new RecordingParseLog();

    private ModelScene parse(String obj) throws Exception {
        return parse(obj, MAIN);
    }

    private ModelScene parse(String obj, String mainPath) throws Exception {
        InMemoryModelSource source = InMemoryModelSource.builder(mainPath).file(mainPath, obj).build();
        return new ObjParser(log).parse(source, "model");
    }

    private ModelParseException parseFailure(String obj) {
        return assertThrows(ModelParseException.class, () -> parse(obj));
    }

    /** A valid triangle whose last line is number 4, so line numbers in messages are easy to read. */
    private static String triangleWithFace(String face) {
        return "v 0 0 0\n" + "v 1 0 0\n" + "v 0 1 0\n" + face + "\n";
    }

    @Test
    void indexZeroIsRejectedWithTheLineNumber() {
        ModelParseException failure = parseFailure(triangleWithFace("f 0 1 2"));
        assertTrue(failure.getMessage().contains("models/test/model.obj:4"),
                "the message must name the file and line: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("1-based"), failure.getMessage());
        System.out.println("[obj-test] indexZeroIsRejectedWithTheLineNumber: " + failure.getMessage());
    }

    @Test
    void uvIndexZeroIsRejected() {
        ModelParseException failure = parseFailure("v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nf 1/0 2/1 3/1\n");
        assertTrue(failure.getMessage().contains("model.obj:5"), failure.getMessage());
        assertTrue(failure.getMessage().contains("texture coordinate index 0"), failure.getMessage());
        System.out.println("[obj-test] uvIndexZeroIsRejected: " + failure.getMessage());
    }

    @Test
    void outOfRangeIndexIsRejectedWithTheLineNumber() {
        ModelParseException failure = parseFailure(triangleWithFace("f 1 2 9"));
        assertTrue(failure.getMessage().contains("models/test/model.obj:4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("index 9 is out of range"), failure.getMessage());
        assertTrue(failure.getMessage().contains("only 3 vertex"), failure.getMessage());
        System.out.println("[obj-test] outOfRangeIndexIsRejectedWithTheLineNumber: " + failure.getMessage());
    }

    @Test
    void negativeIndexBeyondTheStartIsRejected() {
        ModelParseException failure = parseFailure(triangleWithFace("f -4 -2 -1"));
        assertTrue(failure.getMessage().contains("model.obj:4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("index -4 is out of range"), failure.getMessage());
        System.out.println("[obj-test] negativeIndexBeyondTheStartIsRejected: " + failure.getMessage());
    }

    @Test
    void normalIndexOutOfRangeIsRejected() {
        ModelParseException failure = parseFailure(triangleWithFace("f 1//1 2//1 3//1"));
        assertTrue(failure.getMessage().contains("normal index 1 is out of range"), failure.getMessage());
        assertTrue(failure.getMessage().contains("only 0 normal"), failure.getMessage());
        System.out.println("[obj-test] normalIndexOutOfRangeIsRejected: " + failure.getMessage());
    }

    @Test
    void nonNumericIndexIsRejected() {
        ModelParseException failure = parseFailure(triangleWithFace("f a b c"));
        assertTrue(failure.getMessage().contains("'a' is not a valid vertex index"), failure.getMessage());
        System.out.println("[obj-test] nonNumericIndexIsRejected: " + failure.getMessage());
    }

    @Test
    void faceWithTooFewCornersIsRejected() {
        ModelParseException failure = parseFailure(triangleWithFace("f 1 2"));
        assertTrue(failure.getMessage().contains("at least 3 vertex references"), failure.getMessage());
        System.out.println("[obj-test] faceWithTooFewCornersIsRejected: " + failure.getMessage());
    }

    @Test
    void vertexWithTooFewCoordinatesIsRejected() {
        ModelParseException failure = parseFailure("v 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        assertTrue(failure.getMessage().contains("models/test/model.obj:1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("3 coordinates"), failure.getMessage());
        System.out.println("[obj-test] vertexWithTooFewCoordinatesIsRejected: " + failure.getMessage());
    }

    @Test
    void nonNumericCoordinateIsRejected() {
        ModelParseException failure = parseFailure("v 0 zero 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
        assertTrue(failure.getMessage().contains("'zero' is not a number"), failure.getMessage());
        System.out.println("[obj-test] nonNumericCoordinateIsRejected: " + failure.getMessage());
    }

    @Test
    void missingObjIsAnException() {
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN).file("other.obj", "v 0 0 0\n").build();
        ModelParseException failure = assertThrows(ModelParseException.class,
                () -> new ObjParser(log).parse(source, "model"));
        assertTrue(failure.getMessage().contains("models/test/model.obj"), failure.getMessage());
        assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
        System.out.println("[obj-test] missingObjIsAnException: " + failure.getMessage());
    }

    @Test
    void objWithoutFacesFailsClosed() {
        ModelParseException failure = parseFailure("v 0 0 0\nv 1 0 0\n");
        assertTrue(failure.getMessage().contains("no faces were found"), failure.getMessage());
        assertTrue(failure.getMessage().contains("2 vertices"), failure.getMessage());
        System.out.println("[obj-test] objWithoutFacesFailsClosed: " + failure.getMessage());
    }

    @Test
    void objWithoutGeometryAtAllFailsClosed() {
        ModelParseException failure = parseFailure("# nothing but a comment\n\n");
        assertTrue(failure.getMessage().contains("no faces were found"), failure.getMessage());
        System.out.println("[obj-test] objWithoutGeometryAtAllFailsClosed: " + failure.getMessage());
    }

    @Test
    void unsupportedComponentsOnAFaceAreToleratedNotFatal() throws Exception {
        // A face reference with a fourth field is malformed but harmless: the first three components
        // are the ones the format defines, so the geometry can still be trusted.
        ModelScene scene = parse("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vn 0 0 1
                f 1/1/1/9 2/1/1/9 3/1/1/9
                """);
        assertEquals(1, triangleCount(scene));
        assertTrue(log.debugged("extra fields in a face reference"), log.debugMessages().toString());
        report("unsupportedComponentsOnAFaceAreToleratedNotFatal", scene);
    }

    @Test
    void emptyMtlStatementListIsNotFatal() throws Exception {
        ModelScene scene = parse("mtllib\n" + triangleWithFace("f 1 2 3"), MAIN);
        assertEquals(1, triangleCount(scene));
        assertTrue(log.debugged("'mtllib' with no file name"), log.debugMessages().toString());
        report("emptyMtlStatementListIsNotFatal", scene);
    }

    @Test
    void missingMtlIsNeverAnException() throws Exception {
        ModelScene scene = parse("mtllib absent.mtl\nusemtl absent_material\n" + triangleWithFace("f 1 2 3"),
                MAIN);
        assertEquals(1, triangleCount(scene));
        assertEquals(1, scene.materials().length, "the default material stands in");
        System.out.println("[obj-test] missingMtlIsNeverAnException: warns=" + log.warnMessages());
    }

    @Test
    void unreadableMtlIsNotFatal() throws Exception {
        // A source that throws on the sibling open models a pack with a corrupt entry; the geometry
        // is already in hand and must still load.
        InMemoryModelSource source = InMemoryModelSource.builder(MAIN)
                .file(MAIN, "mtllib hostile.mtl\n" + triangleWithFace("f 1 2 3"))
                .unreadable("hostile.mtl")
                .build();
        ModelScene scene = new ObjParser(log).parse(source, "model");
        assertEquals(1, triangleCount(scene), "an unreadable MTL never fails the load");
        assertTrue(log.warned("cannot open mtllib"), log.warnMessages().toString());
        report("unreadableMtlIsNotFatal", scene);
    }
}
