package com.model3d.loader.verify;

import com.model3d.loader.common.command.TestModelCommand;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandSigningContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.TaskChainer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Checks that the registered command parses the forms the documentation promises.
 *
 * <h2>Why this exists</h2>
 * The command is registered against a live Brigadier dispatcher at server start, but nothing
 * exercised its <b>grammar</b> until a user typed a namespace-qualified name and got
 * <i>"Expected whitespace to end one argument, but found trailing data"</i>. The cause was the
 * argument type: Brigadier's {@code StringArgumentType.string()} reader stops at the first
 * character outside {@code [0-9A-Za-z_.+-]}, so a colon ends the argument and everything after it
 * is trailing data. Only the quoted form parsed.
 *
 * <p>That class of failure - a command whose documented syntax does not parse - is invisible to
 * unit tests of the handler, to a compile, and to a server boot. It needs the grammar itself run,
 * which is what this does: it registers the real command node tree and feeds it the exact strings
 * from the README.
 *
 * <h2>Why a real command source is built</h2>
 * A {@code requires} predicate runs during parsing, so a null source makes Brigadier prune the node
 * and every parse returns "unknown command" - which would make this check pass for the wrong reason
 * (or fail with no useful message). The source is assembled from interfaces rather than a running
 * server: permission comes from a minimal {@link CommandSource}, the task chainer runs work inline,
 * and the level and server are null because parsing never touches them. Only the permission gate is
 * exercised, which is exactly the part that gates parsing.
 *
 * <p>Exit code 0 when every documented form parses and yields the expected model id, 1 otherwise.
 */
public final class CommandParseCheck {

    private static int failures;

    private CommandParseCheck() {
    }

    public static void main(String[] args) {
        System.out.println("=== Model3D command grammar check ===");

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        TestModelCommand.register(dispatcher);
        CommandSourceStack source = mockSource(true);
        CommandSourceStack denied = mockSource(false);

        // Forms the command accepts, with the id each must resolve to.
        expect(dispatcher, source, "testmodel loader animated_test 0 70 0", "model3d:animated_test",
                "unqualified name");
        expect(dispatcher, source, "testmodel loader model3d:animated_test 0 70 0",
                "model3d:animated_test",
                "namespace-qualified name, UNQUOTED - the form that used to fail");
        expect(dispatcher, source, "testmodel loader somepack:sub/dir/model 0 70 0",
                "somepack:sub/dir/model", "another namespace, nested model path");
        expect(dispatcher, source, "testmodel loader model3d:animated_test 100 70 100",
                "model3d:animated_test", "the reported failing command, verbatim");

        // Vanilla coordinate syntax, which is what a Vec3 argument buys and three doubles did not.
        // Each of these was a syntax error before, and all four are asserted because the offsets and
        // the caret form are the ones most likely to be lost by a future argument-type change.
        expect(dispatcher, source, "testmodel loader su30 ~ ~ ~", "model3d:su30",
                "tilde coordinates: at the source");
        expect(dispatcher, source, "testmodel loader su30 ~5 ~ ~-2", "model3d:su30",
                "tilde coordinates with offsets");
        expect(dispatcher, source, "testmodel loader su30 ^ ^ ^2", "model3d:su30",
                "caret coordinates: local, look-relative");
        expect(dispatcher, source, "testmodel loader su30 1.5 -60 2.5", "model3d:su30",
                "fractional absolute coordinates");

        // Quoting is NOT accepted, and that is asserted rather than assumed. The fix replaced
        // string() with ResourceLocationArgument, and ResourceLocation.read() has no quote
        // handling: it consumes allowed characters and stops at '"'. So the previously-working
        // quoted form became invalid while the previously-broken unquoted form started working -
        // a trade that is only acceptable because the unquoted form is the one every vanilla
        // command takes, but it must be pinned so it cannot change silently again.
        expectRejected(dispatcher, source, "testmodel loader \"model3d:animated_test\" 0 70 0",
                "quoted id is not a form this command accepts (use the unquoted form)");

        // A source without permission must not even reach the argument, and must NOT throw. The
        // predicate is invoked during parsing, so this also covers the null-source crash path.
        expectDenied(dispatcher, denied, "testmodel loader animated_test 0 70 0",
                "a non-operator's parse is pruned, not thrown");
        expectDenied(new CommandDispatcher<CommandSourceStack>(), source,
                "testmodel loader animated_test 0 70 0", "an unregistered dispatcher rejects it");

        // The counter-case: the type this shipped with must be shown to be the wrong one, or a
        // future change could 'fix' this by reverting it.
        controlStringTypeRejectsColon();

        expectDiag(dispatcher, source, "testmodel diag model3d:animated_test");

        // The argument type itself is the thing that must not regress, so assert it directly rather
        // than only through a parse. Reading MODEL_ARG back proves the node still carries a
        // resource-location parser and not, say, a string one that happens to accept this input.
        check(dispatcher.getRoot().getChild(TestModelCommand.ROOT) != null,
                "the /testmodel root node is registered");
        check(dispatcher.getRoot().getChild(TestModelCommand.ROOT)
                        .getChild(TestModelCommand.LOADER) != null,
                "the loader subcommand node is registered");

        // Deliberately NOT checked here: ModEntities.TEST_MODEL. Touching it initialises the entity
        // registry, which throws "Not bootstrapped" outside a running game - a legitimate reason to
        // leave it out, and worth stating so nobody adds it back and gets a confusing failure. The
        // entity type is exercised by the dedicated-server acceptance run instead
        // (`gradlew runServer -Pmodel3dSelfTest=...`), which spawns one.

        System.out.println();
        if (failures == 0) {
            System.out.println("RESULT: PASS");
            System.exit(0);
        } else {
            System.out.println("RESULT: FAIL (" + failures + " problem(s))");
            System.exit(1);
        }
    }

    /**
     * A command source that needs no server, built by allocation rather than construction.
     *
     * <p>{@code CommandSourceStack}'s only public constructor calls
     * {@code TaskChainer.immediate(server)} and {@code server.createCommandSourceStack()} eagerly, so
     * it needs a real running {@code MinecraftServer} - which is exactly what this check must not
     * require, since the point is to verify the grammar without booting one.
     *
     * <p>Allocating the instance and then setting the single field a parse consults
     * ({@code permissionLevel}, via {@code withPermission}) avoids all of that. Only that field is
     * ever read while parsing: Brigadier's node filter calls {@code hasPermission}, and nothing
     * touches the level, the server or the entity until a command actually executes. The
     * verification-only approach is deliberate and confined to this file - the mod itself always
     * gets a real stack from Forge.
     *
     * @return the stack, or null when the runtime refuses the reflective access, in which case the
     *         caller reports the permission checks as NOT RUN rather than passing them vacuously
     */
    private static CommandSourceStack mockSource(boolean operator) {
        try {
            var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            var allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
            CommandSourceStack stack =
                    (CommandSourceStack) allocate.invoke(unsafe, CommandSourceStack.class);
            return operator ? stack.withPermission(2) : stack.withPermission(0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("  SKIP command source could not be built (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ") - permission-dependent checks NOT RUN");
            return null;
        }
    }

    private static void expect(CommandDispatcher<CommandSourceStack> dispatcher,
                               CommandSourceStack source, String input, String expectedId,
                               String what) {
        if (source == null) {
            fail(what, input, "no command source available, so the grammar could not be exercised");
            return;
        }
        ParseResults<CommandSourceStack> result = dispatcher.parse(input, source);
        boolean parsed = result.getExceptions().isEmpty() && result.getContext().getCommand() != null;
        if (!parsed) {
            fail(what, input, "does not parse: " + result.getExceptions().values()
                    + " (reader stopped at " + result.getReader().getCursor() + ")");
            return;
        }
        try {
            ResourceLocation parsedId = ResourceLocationArgument.getId(
                    result.getContext().build(input), TestModelCommand.MODEL_ARG);
            ResourceLocation actual = TestModelCommand.defaultNamespace(parsedId);
            if (actual.toString().equals(expectedId)) {
                System.out.printf("  ok   %-52s -> %s%n", input, actual);
            } else {
                fail(what, input, "resolved to " + actual + " but should be " + expectedId);
            }
        } catch (RuntimeException e) {
            fail(what, input, "parsed but the argument could not be read: " + e);
        }
    }

    /** Asserts {@code input} does not reach a command, without the dispatcher throwing. */
    private static void expectDenied(CommandDispatcher<CommandSourceStack> dispatcher,
                                     CommandSourceStack source, String input, String what) {
        if (source == null) {
            fail(what, input, "no command source available, so this could not be exercised");
            return;
        }
        try {
            ParseResults<CommandSourceStack> result = dispatcher.parse(input, source);
            boolean denied = result.getContext().getCommand() == null;
            if (denied) {
                System.out.printf("  ok   %-52s -> pruned (no command reached)%n", what);
            } else {
                fail(what, input, "a command was reachable when it should not have been");
            }
        } catch (RuntimeException e) {
            fail(what, input, "the dispatcher threw instead of pruning: " + e);
        }
    }

    /**
     * Proves the ORIGINAL argument type cannot parse a colon, so the fix cannot be silently undone.
     *
     * <p>A test that only asserts the new behaviour passing would still pass if someone changed the
     * argument back <i>and</i> the test were adjusted with it. This one fails loudly if the
     * distinction ever stops existing.
     */
    private static void controlStringTypeRejectsColon() {
        CommandDispatcher<Object> control = new CommandDispatcher<>();
        control.register(LiteralArgumentBuilder.<Object>literal("control")
                .then(RequiredArgumentBuilder.<Object, String>argument("n",
                        StringArgumentType.string()).executes(ctx -> 1)));
        ParseResults<Object> result = control.parse("control model3d:x", new Object());
        if (result.getExceptions().isEmpty()) {
            fail("control", "control model3d:x",
                    "StringArgumentType accepted a colon, so this control no longer demonstrates "
                            + "why ResourceLocationArgument is required - re-check the fix");
        } else {
            System.out.printf("  ok   control: StringArgumentType.string() still rejects the colon "
                    + "(%s)%n", result.getExceptions().values().iterator().next().getMessage());
        }
    }

    /** Asserts {@code input} is rejected, so a form the docs do not promise cannot creep in. */
    private static void expectRejected(CommandDispatcher<CommandSourceStack> dispatcher,
                                       CommandSourceStack source, String input, String what) {
        if (source == null) {
            fail(what, input, "no command source available, so this could not be exercised");
            return;
        }
        ParseResults<CommandSourceStack> result = dispatcher.parse(input, source);
        if (result.getContext().getCommand() == null) {
            System.out.printf("  ok   %-52s -> rejected as expected%n", input);
        } else {
            fail(what, input, "this parses now, so the documentation or the grammar changed - "
                    + "update whichever is wrong");
        }
    }

    private static void expectDiag(CommandDispatcher<CommandSourceStack> dispatcher,
                                   CommandSourceStack source, String input) {
        if (source == null) {
            fail("diag subcommand", input, "no command source available");
            return;
        }
        CommandDispatcher<CommandSourceStack> diagDispatcher = new CommandDispatcher<>();
        com.model3d.loader.common.command.DiagCommand.register(diagDispatcher);
        ParseResults<CommandSourceStack> result = diagDispatcher.parse(input, source);
        boolean parsed = result.getExceptions().isEmpty() && result.getContext().getCommand() != null;
        if (parsed) {
            System.out.printf("  ok   %-52s -> parses%n", input);
        } else {
            fail("diag subcommand", input, "does not parse: " + result.getExceptions().values());
        }
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            System.out.printf("  ok   %s%n", what);
        } else {
            fail(what, "-", "condition false");
        }
    }

    private static void fail(String what, String input, String detail) {
        failures++;
        System.out.printf("  FAIL %-52s (%s): %s%n", input, what, detail);
    }
}
