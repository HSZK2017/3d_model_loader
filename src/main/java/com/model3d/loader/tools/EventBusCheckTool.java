package com.model3d.loader.tools;

import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks that every {@code @Mod.EventBusSubscriber} class subscribes to events its declared bus
 * actually carries.
 *
 * <h2>The failure this exists to prevent</h2>
 * Forge has two event buses, and {@code @Mod.EventBusSubscriber}'s {@code bus} attribute selects
 * <b>one</b>: {@code AutomaticEventSubscriber} registers the class on that bus and no other
 * ({@code busTarget.bus().get().register(...)}). So a class declaring {@code bus = FORGE} that
 * subscribes to an {@code IModBusEvent} - which Forge posts on the {@code MOD} bus - is
 * <b>never called</b>, with no error, no warning and no log line.
 *
 * <p>That happened here. The client registration class declared {@code bus = FORGE} while
 * subscribing to {@code EntityRenderersEvent.RegisterRenderers}, {@code FMLClientSetupEvent} and
 * {@code RegisterClientReloadListenersEvent}, all {@code IModBusEvent}s. Nothing registered the
 * entity's renderer, and the game crashed on the first frame that drew the entity:
 * <pre>
 *   NullPointerException: Cannot invoke "EntityRenderer.render(...)" because "entityrenderer" is null
 *     at EntityRenderDispatcher.render(EntityRenderDispatcher.java:127)
 * </pre>
 * The mod compiled, booted, accepted the command and spawned the entity. Only a client actually
 * drawing that entity could reveal it, so the defence is to check the annotation against the event
 * types - a static property requiring no game.
 *
 * <h2>Why the bus value is read from the class file</h2>
 * {@code type.getAnnotation(Mod.EventBusSubscriber.class)} looks like the obvious way to read it and
 * throws {@code ExceptionInInitializerError}: the annotation's default value is the enum constant
 * {@code Bus.FORGE}, so merely parsing the annotation initialises the {@code Bus} enum, whose
 * constants are {@code Bindings.getForgeBus()} and {@code FMLJavaModLoadingContext.get()...} - both
 * of which require a running Forge. The same applies to any class literal of {@code IModBusEvent},
 * whose interface initialisation also reaches for those bindings.
 *
 * <p>So both facts are read without triggering class initialisation: the annotation's {@code bus}
 * element is decoded straight out of the class file's constant pool, and the bus-membership question
 * is answered with {@code Class.forName(..., false, ...)} plus {@code isAssignableFrom}, which loads
 * types without initialising them.
 *
 * <p>Exit code 0 when every subscriber is consistent, 1 otherwise, with each offending method named.
 *
 * <h2>Using this from another mod</h2>
 * It is shipped in the jar for exactly that: any mod that registers event subscribers has this failure
 * mode, and it is invisible at runtime. Point it at your own compiled classes - as an argument, or
 * through the {@link #CLASSES_PROPERTY} system property - and it reports every class whose declared
 * bus cannot deliver the events it subscribes to.
 *
 * <pre>
 *   java -cp &lt;your runtime classpath&gt; com.model3d.loader.tools.EventBusCheckTool build/classes/java/main
 * </pre>
 */

public final class EventBusCheckTool {

    /** System property giving the compiled classes directory; set by the Gradle task. */
    public static final String CLASSES_PROPERTY = "model3d.verify.classes";

    /** The marker interface Forge uses to route an event to the mod bus. */
    private static final String MOD_BUS_EVENT = "net.minecraftforge.fml.event.IModBusEvent";

    /** The annotation under test, by name so no class literal is needed. */
    private static final String SUBSCRIBER_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";

    private static int subscribersSeen;
    private static int methodsChecked;
    private static int failures;

    private EventBusCheckTool() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println("=== event bus check ===");
        // The directory to scan: the first argument, else the system property, else the conventional
        // Gradle output path. An argument is what lets another mod use this without a property.
        String configured = args.length > 0 && !args[0].isBlank()
                ? args[0]
                : System.getProperty(CLASSES_PROPERTY, "build/classes/java/main");
        Path classes = Path.of(configured).toAbsolutePath().normalize();

        if (!Files.isDirectory(classes)) {
            System.out.println("RESULT: FAIL - compiled classes not found at " + classes);
            System.exit(1);
            return;
        }

        Class<?> modBusMarker;
        try {
            // initialize=false: loading is enough to ask isAssignableFrom, and initialising it would
            // reach for Forge's bus bindings and fail outside a game.
            modBusMarker = Class.forName(MOD_BUS_EVENT, false, EventBusCheckTool.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            System.out.println("RESULT: FAIL - cannot load " + MOD_BUS_EVENT + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classes)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .sorted()
                    .toList();
        }
        System.out.println("scanning " + classFiles.size() + " class(es) under " + classes);

        for (Path file : classFiles) {
            String className = classNameOf(classes, file);
            String declaredBus = declaredBus(file);
            if (declaredBus == null) {
                continue;
            }
            inspect(file, className, declaredBus, modBusMarker);
        }

        System.out.println();
        System.out.printf("checked %d subscriber class(es), %d @SubscribeEvent method(s)%n",
                subscribersSeen, methodsChecked);
        if (failures > 0) {
            System.out.println("RESULT: FAIL (" + failures + " problem(s))");
            System.exit(1);
            return;
        }
        // A scan that found nothing is a broken scan, not a clean bill of health. Without this the
        // first version of this file reported PASS while inspecting zero classes - the exact
        // vacuous-assertion failure it is meant to catch elsewhere.
        if (subscribersSeen == 0) {
            System.out.println("RESULT: FAIL - no @Mod.EventBusSubscriber class was found at all, so"
                    + " nothing was verified. Is the classes directory correct?");
            System.exit(1);
            return;
        }
        System.out.println("RESULT: PASS");
        System.exit(0);
    }

    private static String classNameOf(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

    private static void inspect(Path classFile, String className, String declaredBus,
                                Class<?> modBusMarker) {
        Class<?> type;
        try {
            // initialize=false: @SubscribeEvent and the event parameter types are readable from the
            // metadata without running any static initialiser, which is what keeps this usable
            // outside a game.
            type = Class.forName(className, false, EventBusCheckTool.class.getClassLoader());
        } catch (Throwable t) {
            return;
        }
        subscribersSeen++;
        boolean modBus = "MOD".equals(declaredBus);
        List<Method> subscriberMethods = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                subscriberMethods.add(method);
            }
        }
        if (subscriberMethods.isEmpty()) {
            System.out.printf("  note %s declares bus = %s but subscribes to nothing%n",
                    type.getSimpleName(), declaredBus);
        }
        for (Method method : subscriberMethods) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1) {
                fail(type, method, "@SubscribeEvent needs exactly one event parameter, has "
                        + parameters.length);
                continue;
            }
            methodsChecked++;
            Class<?> eventType = parameters[0];
            boolean isModBusEvent = modBusMarker.isAssignableFrom(eventType);
            boolean matches = (modBus == isModBusEvent);
            System.out.printf("  %s %s#%s(%s) - declared %s bus, event is %s%n",
                    matches ? "ok  " : "FAIL", type.getSimpleName(), method.getName(),
                    eventType.getSimpleName(), declaredBus,
                    isModBusEvent ? "a MOD-bus (IModBusEvent) event" : "a FORGE-bus game event");
            if (!matches) {
                fail(type, method, "declared bus = " + declaredBus + " but "
                        + eventType.getSimpleName() + " is "
                        + (isModBusEvent ? "an IModBusEvent, which Forge posts on the MOD bus"
                                : "a game event, which Forge posts on the FORGE bus")
                        + " - a subscriber on the wrong bus is never called, silently");
            }
        }
    }

    private static void fail(Class<?> type, Method method, String detail) {
        failures++;
        System.out.printf("  FAIL %s#%s: %s%n", type.getName(), method.getName(), detail);
    }

    // ------------------------------------------------------------------
    // Class-file reader for the annotation's bus value
    // ------------------------------------------------------------------

    /**
     * Reads the {@code bus} element of a {@code @Mod.EventBusSubscriber} annotation, or null when
     * the class is not annotated with it.
     *
     * <p>Deliberately a small constant-pool walk rather than reflective annotation access: see the
     * class comment for why reflection cannot be used here without initialising Forge. Only the two
     * strings needed are extracted, so this stays a targeted parser rather than a class-file library.
     */
    private static String declaredBus(Path classFile) {
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(classFile)))) {
            if (in.readInt() != 0xCAFEBABE) {
                return null;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int poolCount = in.readUnsignedShort();
            String[] utf8 = new String[poolCount];
            for (int i = 1; i < poolCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // long and double take two constant-pool slots
                    }
                    default -> {
                        return null; // unknown tag: not a class file this reader understands
                    }
                }
            }
            in.skipBytes(6); // access flags, this class, super class
            int interfaces = in.readUnsignedShort();
            in.skipBytes(interfaces * 2);
            skipMember(in); // fields
            skipMember(in); // methods
            int attributes = in.readUnsignedShort();
            for (int i = 0; i < attributes; i++) {
                int nameIndex = in.readUnsignedShort();
                long length = in.readInt() & 0xFFFFFFFFL;
                String name = utf8[nameIndex];
                if (name != null && name.endsWith("Annotations")) {
                    byte[] body = new byte[(int) length];
                    in.readFully(body);
                    String bus = busFromAnnotations(body, utf8);
                    if (bus != null) {
                        return bus;
                    }
                } else {
                    in.skipBytes((int) length);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Decodes the {@code bus} element out of the annotation body.
     *
     * <p>A proper walk of {@code element_value_pairs} rather than a byte scan, and that distinction
     * is not academic: the first version of this scanned raw bytes for a "bus" constant-pool index
     * followed by a {@code MOD}/{@code FORGE} index, and found a false match inside the
     * {@code value = {Dist.CLIENT}} array of the very same annotation - reporting
     * {@code checked 0 subscriber class(es)} and a vacuous PASS. The real layout, verified against a
     * dump of the compiled class, is:
     *
     * <pre>
     *   u2 num_annotations
     *   per annotation: u2 type_index, u2 num_pairs, then per pair:
     *       u2 element_name_index
     *       element_value: u1 tag, then per tag:
     *           's' -> u2 const_index                        (5 bytes total)
     *           'c' -> u2 class_info_index                   (5)
     *           'e' -> u2 type_name_index, u2 const_name_index (7)
     *           '[' -> u2 num_values, then that many values  (5 + values)
     *           'Z'/'B'/'C'/'S'/'I'/'J'/'F'/'D' -> u2 const   (5)
     * </pre>
     *
     * @return "MOD", "FORGE", or null when the class is not a {@code @Mod.EventBusSubscriber} at all.
     *         A subscriber annotation with no {@code bus} element returns "FORGE": that is Forge's
     *         default, and skipping those classes instead - which this did at first - leaves the most
     *         dangerous case unchecked, because writing {@code @Mod.EventBusSubscriber(modid = ...)}
     *         and subscribing to an {@code IModBusEvent} is exactly the mistake that compiles, boots
     *         and never fires. Measured on the companion mod: 3 subscriber classes exist and only 2
     *         were reported.
     */
    private static String busFromAnnotations(byte[] body, String[] utf8) {
        int annotations = u2(body, 0);
        int offset = 2;
        for (int a = 0; a < annotations && offset + 4 <= body.length; a++) {
            boolean isSubscriberAnnotation = isSubscriberAnnotation(utf8, body, offset);
            offset += 2; // annotation type index
            int pairs = u2(body, offset);
            offset += 2;
            for (int p = 0; p < pairs && offset + 3 <= body.length; p++) {
                String elementName = utf8At(utf8, body, offset);
                int valueOffset = offset + 2;
                int next = skipElementValue(body, valueOffset);
                if (next < 0) {
                    return null; // unknown tag: give up rather than guess a layout
                }
                if ("bus".equals(elementName)) {
                    String enumType = utf8At(utf8, body, valueOffset + 1);
                    // Guard on the declared enum type as well as the constant name, so a nested
                    // annotation with its own MOD-valued enum cannot be mistaken for the bus.
                    if (enumType != null && enumType.endsWith("EventBusSubscriber$Bus;")) {
                        return utf8At(utf8, body, valueOffset + 3);
                    }
                    return null;
                }
                offset = next;
            }
            if (isSubscriberAnnotation) {
                // Annotated, but the default bus was taken. Forge's default is FORGE.
                return "FORGE";
            }
        }
        return null;
    }

    /**
     * Whether the annotation at {@code offset} in the body is {@code @Mod.EventBusSubscriber}.
     *
     * <p>The type index is the first u2 of an annotation, and it resolves to a descriptor like
     * {@code Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;}. Compared by suffix so the check
     * does not depend on which class loader or remapping produced the file.
     */
    private static boolean isSubscriberAnnotation(String[] utf8, byte[] body, int offset) {
        String type = utf8At(utf8, body, offset);
        return type != null && type.endsWith("Mod$EventBusSubscriber;");
    }

    /**
     * Returns the offset just past the element_value starting at {@code offset}, or -1 for a tag
     * this decoder does not handle.
     */
    private static int skipElementValue(byte[] body, int offset) {
        if (offset + 3 > body.length) {
            return -1;
        }
        char tag = (char) body[offset];
        return switch (tag) {
            // String, class info, and the numeric primitives all carry a single u2 constant index.
            case 's', 'c', 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> offset + 3;
            // Enum: type_name and const_name.
            case 'e' -> offset + 5;
            case '[' -> {
                int elements = u2(body, offset + 1);
                int inner = offset + 3;
                for (int i = 0; i < elements; i++) {
                    int next = skipElementValue(body, inner);
                    if (next < 0) {
                        yield -1;
                    }
                    inner = next;
                }
                yield inner;
            }
            default -> -1;
        };
    }

    private static int u2(byte[] body, int offset) {
        return ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
    }

    /**
     * Reads a big-endian u2 at {@code offset} and resolves it against the constant pool, or null
     * when the index is absent or does not name a UTF8 entry.
     */
    private static String utf8At(String[] utf8, byte[] body, int offset) {
        if (offset + 1 >= body.length) {
            return null;
        }
        int index = u2(body, offset);
        return index > 0 && index < utf8.length ? utf8[index] : null;
    }

    private static void skipMember(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6); // access, name, descriptor
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.skipBytes(2);
                int length = in.readInt();
                in.skipBytes(length);
            }
        }
    }
}
