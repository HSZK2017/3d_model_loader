package com.model3d.loader.common.command;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelHandle;
import com.model3d.loader.common.entity.TestModelEntity;
import com.model3d.loader.resource.ExternalModelPaths;
import com.model3d.loader.resource.ModelLoadService;
import com.model3d.loader.scene.ModelAnimation;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /testmodel ...} - the only way to obtain the API's test entity.
 *
 * <p>Command shape, as specified:
 * <pre>
 *   /testmodel loader &lt;model name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;
 * </pre>
 *
 * <h2>Coordinates use vanilla's coordinate syntax</h2>
 * The position is a single {@code Vec3Argument}, not three doubles, so every form a player expects
 * from {@code /tp} or {@code /summon} works: {@code 100 70 100} absolute, {@code ~ ~ ~} at the
 * source, {@code ~5 ~ ~-2} with offsets, and {@code ^ ^ ^2} local (look-relative). Three
 * {@code doubleArg()} arguments accepted only plain numbers, which made {@code ~ ~ ~} - the form a
 * player types by reflex when placing something at their own feet - a syntax error.
 *
 * <h2>Why the model name uses {@link ResourceLocationArgument}</h2>
 * A model name may be namespace-qualified ({@code somepack:thing}), and the obvious choice -
 * Brigadier's {@code StringArgumentType.string()} - <b>cannot parse a colon</b>. Its reader stops at
 * the first character outside {@code [0-9A-Za-z_.+-]}, so
 * {@code /testmodel loader model3d:animated_test 0 70 0} failed with
 * <i>"Expected whitespace to end one argument, but found trailing data at position 24"</i> and the
 * only working form was the quoted {@code "model3d:animated_test"}. Measured, not assumed: the same
 * parse through Brigadier 1.1.8 rejects the unquoted form and accepts the quoted one.
 *
 * <p>That is bad in both directions. A user who types the namespaced form gets an error pointing at
 * their own text, which reads as a typo; and a user who quotes it learns a habit that does not
 * generalise, because unqualified names parse fine unquoted. It also contradicted the mod's own
 * documentation, whose examples all showed the qualified form.
 *
 * <p>{@code ResourceLocationArgument} is what every vanilla command that takes an id uses
 * ({@code /give}, {@code /summon}, {@code /loot}), and its {@code ResourceLocation.read} consumes
 * the whole token including {@code :} and {@code /}. Using it makes both forms work identically and
 * matches the behaviour a player already expects from {@code /give minecraft:diamond}.
 *
 * <h2>Namespace defaulting</h2>
 * {@code ResourceLocation.tryParse("my_model")} yields {@code minecraft:my_model}, so an
 * unqualified name arrives wearing vanilla's namespace rather than "no namespace". Rather than
 * requiring the user to type {@code model3d:} - which they should not have to do to load the mod's
 * own shipped model - {@code minecraft:} is treated as "unqualified" and rewritten to this mod's
 * namespace. Nothing can live in the {@code minecraft} namespace anyway, so the rewrite is
 * unambiguous and cannot shadow a real model.
 */
public final class TestModelCommand {

    public static final String ROOT = "testmodel";
    public static final String LOADER = "loader";
    public static final String MODEL_ARG = "model name";

    private static final DynamicCommandExceptionType ERROR_NO_MODEL =
            new DynamicCommandExceptionType(name -> Component.literal(
                    "Model3D: no model named '" + name + "'. Use the tab-completion list; models "
                            + "live in data/<namespace>/model3d/<name>/ or "
                            + "<gamedir>/model3d/<namespace>/<name>/"));
    private static final DynamicCommandExceptionType ERROR_MODEL_BROKEN =
            new DynamicCommandExceptionType(name -> Component.literal(
                    "Model3D: model '" + name + "' exists but failed to load; see the server log "
                            + "for the parse error"));
    private static final DynamicCommandExceptionType ERROR_LOADER_IN_LEVEL =
            new DynamicCommandExceptionType(name -> Component.literal(
                    "Model3D: could not create the entity for '" + name + "'"));

    private TestModelCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(TestModelCommand::mayUse);
        root.then(Commands.literal(LOADER)
                .then(Commands.argument(MODEL_ARG, ResourceLocationArgument.id())
                        .suggests(TestModelCommand::suggestModels)
                        // One Vec3 argument rather than three doubles, so the position accepts the
                        // full vanilla coordinate syntax: "100 70 100" absolute, "~ ~ ~" relative to
                        // the source, "~5 ~ ~-2" with an offset, and "^ ^ ^" local (look-relative)
                        // coordinates. Three separate doubleArg()s accepted only plain numbers, so
                        // "~ ~ ~" - the form a player reaches for by reflex - was a syntax error,
                        // which is the wrong trade for a command whose whole job is placing an
                        // entity where you are looking.
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(TestModelCommand::spawnAt))));
        dispatcher.register(root);

        // A bare /testmodel with no subcommand lists what is available: the first thing a user
        // needs after installing a model, and the list is already indexed for suggestions.
        dispatcher.register(Commands.literal(ROOT)
                .requires(TestModelCommand::mayUse)
                .executes(TestModelCommand::listModels));

        // /testmodel reload: rescan the model folders now. Detection is automatic, so this is for
        // confirmation rather than necessity - a user who has just dropped a file wants to see it
        // listed, not wait a second and guess.
        dispatcher.register(Commands.literal(ROOT)
                .requires(TestModelCommand::mayUse)
                .then(Commands.literal("reload").executes(TestModelCommand::reload)));

        // /testmodel look <model> [distance]: spawn it in front of where the player is looking.
        //
        // The plain form takes coordinates, and a coordinate typed by hand is a place the player then
        // has to find - which produced a report of "the model does not load at all" for a model that had
        // loaded correctly twenty blocks away, off screen. This form cannot miss: it uses the player's
        // own look vector, the same calculation the client acceptance run uses, so the model is
        // guaranteed to be in the frustum when it appears.
        dispatcher.register(Commands.literal(ROOT)
                .requires(TestModelCommand::mayUse)
                .then(Commands.literal("look")
                        .then(Commands.argument(MODEL_ARG, ResourceLocationArgument.id())
                                .suggests(TestModelCommand::suggestModels)
                                .executes(context -> spawnInView(context, 6.0))
                                .then(Commands.argument("distance",
                                                DoubleArgumentType.doubleArg(2.0, 64.0))
                                        .executes(context -> spawnInView(context,
                                                DoubleArgumentType.getDouble(context, "distance")))))));
    }

    /**
     * Spawns a model along the player's own look vector.
     *
     * <p>Reads the entity's view vector rather than assigning a rotation first, because a rotation set
     * this tick does not move the camera until the next one - so a command that turned the player and
     * then spawned would place the model where the player was about to be looking, not where they are.
     */
    private static int spawnInView(CommandContext<CommandSourceStack> context, double distance)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player)) {
            source.sendFailure(Component.literal("Model3D: /testmodel look needs a player, so it can "
                    + "use your view direction; use the coordinate form instead"));
            return 0;
        }
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
        double x = eye.x + look.x * distance;
        double y = eye.y + look.y * distance;
        double z = eye.z + look.z * distance;
        return spawn(source, context, x, y, z);
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ModelLoadService.INSTANCE.forceRescan(false);
        Set<String> names = ModelLoadService.INSTANCE.knownServerModelNames(
                source.getServer().getResourceManager());
        String where = String.valueOf(ExternalModelPaths.configModels());
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Model3D: rescan found no models. Put a "
                    + ".glb/.gltf/.obj in " + where + " - see the README.txt in that folder."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Model3D: rescanned " + where + " - "
                + names.size() + " model(s): " + String.join(", ", names)), true);
        return names.size();
    }

    /**
     * Permission gate for this command: operator level 2, like every other mod command.
     *
     * <p>A {@code requires} predicate runs during <b>parsing</b>, not only at execution, so it must
     * tolerate a null source. Forge's own test harness parses with a null source
     * ({@code GameTestHelper} builds its command stack the same way), and a predicate that assumed
     * otherwise threw {@code NullPointerException: Cannot invoke "CommandSourceStack.hasPermission"}
     * from inside the dispatcher - a crash during tab-completion rather than a clean denial. A null
     * source means "no player attached", which for parsing purposes is a source with no permissions,
     * so it is denied rather than assumed trusted.
     */
    private static boolean mayUse(CommandSourceStack source) {
        return source != null && source.hasPermission(2);
    }

    private static CompletableFuture<Suggestions> suggestModels(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> names = knownModels(context);
        if (names.isEmpty()) {
            // Suggesting nothing is indistinguishable from a broken command, so say why.
            Model3D.LOGGER.info("Model3D: /{} {} has no suggestions - no models found by the "
                    + "server resource manager", ROOT, LOADER);
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static Set<String> knownModels(CommandContext<CommandSourceStack> context) {
        return ModelLoadService.INSTANCE.knownServerModelNames(
                context.getSource().getServer().getResourceManager());
    }

    private static int listModels(CommandContext<CommandSourceStack> context) {
        Set<String> names = knownModels(context);
        if (names.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Model3D: no models found. Put a .glb/.gltf/.obj under "
                            + "data/<namespace>/model3d/<name>/ in a mod or resource pack, or "
                            + "under <gamedir>/model3d/<namespace>/<name>/."), false);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(
                "Model3D: " + names.size() + " model(s): " + String.join(", ", names)), false);
        return names.size();
    }

    private static int spawnAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // Resolved against the source, so "~", "~5" and "^" all work and a console source or a
        // command block behaves the way a player expects from every other vanilla command.
        Vec3 position = Vec3Argument.getVec3(context, "pos");
        return spawn(context.getSource(), context, position.x, position.y, position.z);
    }

    /**
     * Spawns the named model at a world position. Shared by the coordinate and look-vector forms.
     */
    private static int spawn(CommandSourceStack source, CommandContext<CommandSourceStack> context,
                             double x, double y, double z) throws CommandSyntaxException {
        ResourceLocation requested = ResourceLocationArgument.getId(context, MODEL_ARG);

        ResourceLocation modelId = defaultNamespace(requested);
        String requestedText = modelId.toString();

        ModelHandle handle = ModelLoadService.INSTANCE.acquireServer(
                source.getServer().getResourceManager(), modelId);
        if (handle == null) {
            // Distinguish "no such name" from "the file is there but unusable": they need
            // completely different fixes, and both currently surface as "nothing appeared".
            if (knownModels(context).contains(requestedText)) {
                throw ERROR_MODEL_BROKEN.create(requestedText);
            }
            throw ERROR_NO_MODEL.create(requestedText);
        }

        try {
            ServerLevel level = source.getLevel();
            TestModelEntity entity = com.model3d.loader.common.registry.ModEntities.TEST_MODEL.get()
                    .create(level);
            if (entity == null) {
                throw ERROR_LOADER_IN_LEVEL.create(requestedText);
            }
            entity.moveTo(x, y, z, source.getRotation().y, 0.0f);
            List<String> animations = new ArrayList<>();
            for (ModelAnimation animation : handle.scene().animations()) {
                animations.add(animation.name());
            }
            float scale = com.model3d.loader.api.ModelScale.forHandle(handle);
            entity.setModel(modelId, scale, animations);
            entity.setAnimation(animations.isEmpty() ? null : animations.get(0), true);
            level.addFreshEntity(entity);

            // The entity has just been added, so it is not yet tracking any player; send the
            // model description explicitly rather than relying on a tracking event that has
            // already fired for this tick.
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(x, y, z) < 64.0 * 64.0) {
                    entity.sendModelSync(player);
                }
            }

            final float reportedScale = scale;
            final int animationCount = animations.size();
            // The distance from whoever ran the command, and a nudge if that is far enough that the
            // model will not be on screen.
            //
            // Added after a report of "the model does not load at all" turned out to be a model spawned
            // twenty blocks away and a player looking at their own feet. The spawn succeeded, the model
            // loaded, every primitive was drawn - and the person who asked for it had no way to know
            // where it went. A command that places something out of sight must say so.
            final double distance = Math.sqrt(source.getPosition().distanceToSqr(x, y, z));
            final String hint = distance > 24.0
                    ? " WARNING: " + fmt((float) distance) + " blocks from you - walk towards it, it will"
                            + " not be in view from here"
                    : "";
            source.sendSuccess(() -> Component.literal(
                    "Model3D: spawned '" + modelId + "' at " + fmt(x) + " " + fmt(y) + " " + fmt(z)
                            + " (nodes=" + handle.scene().nodeCount()
                            + ", meshes=" + handle.scene().meshes().length
                            + ", animations=" + animationCount
                            + ", scale=" + reportedScale + " blocks/unit, "
                            + fmt((float) distance) + " blocks away)" + hint), true);
            return Command.SINGLE_SUCCESS;
        } finally {
            // The entity holds only the model's id and descriptor values, so the server does not
            // need to keep this handle alive for the entity's lifetime.
            ModelLoadService.INSTANCE.releaseServer(modelId);
        }
    }

    /**
     * Rewrites vanilla's default namespace to this mod's, so an unqualified name resolves here.
     *
     * <p>{@code ResourceLocationArgument} parses {@code su30} as {@code minecraft:su30}, because
     * that is what {@code ResourceLocation} does with a bare path. Treating that as "the user did
     * not name a namespace" is what lets {@code /testmodel loader su30 ...} work, which is the form
     * the mod's own documentation uses. Nothing can genuinely live in the {@code minecraft}
     * namespace, so this cannot shadow a real model.
     *
     * <p>Public so the command-grammar verification task can assert the resolution rule without
     * going through a live server. It is a pure function of its argument and has no side effects.
     */
    public static ResourceLocation defaultNamespace(ResourceLocation id) {
        if (!id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
            return id;
        }
        return com.model3d.loader.util.Ids.of(Model3D.MOD_ID, id.getPath());
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}

