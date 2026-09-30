package com.model3d.loader.common.command;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelHandle;
import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.resource.ModelLoadService;
import com.model3d.loader.resource.ModelLocation;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /testmodel diag <model>} - answers "why can the server not see my model".
 *
 * <h2>Why a command rather than a log line</h2>
 * Model resolution has four independent, differently-silent failure points: the pack tree may not
 * expose a directory listing at all, the file may be reachable by exact path while invisible to
 * listing, the descriptor may point somewhere absent, and the parser may reject what was found.
 * From the outside all four read as "no such model", and the loader's own log line naming the
 * failure comes from only one of them.
 *
 * <p>The listing check is the one that earns its keep. A resource-manager directory listing and a
 * direct get-by-path can disagree - and when they do, name discovery and tab-completion go quiet
 * while the file itself would still have loaded, which is exactly the confusion this command was
 * written to resolve.
 */
public final class DiagCommand {

    private DiagCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(TestModelCommand.ROOT)
                // The same permission gate the loader subcommand uses, so a user who can run one can
                // run the other - a diagnostic that refuses to run for the person seeing the
                // problem is not a diagnostic.
                .requires(source -> source != null && source.hasPermission(2))
                .then(Commands.literal("diag")
                        // Same argument type as the loader subcommand: a diagnostic that accepted a
                        // different syntax from the command it diagnoses would be worse than none.
                        .then(Commands.argument("model", ResourceLocationArgument.id())
                                .executes(DiagCommand::run))));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation requested =
                ResourceLocationArgument.getId(context, "model");
        String report = report(source.getServer().getResourceManager(), requested.toString());
        if (report == null) {
            source.sendFailure(Component.literal("Model3D: '" + requested + "' is not a valid id"));
            return 0;
        }
        Model3D.LOGGER.info("diag report:\n{}", report);
        for (String line : report.split("\n")) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Builds the report for {@code requested}. Exposed separately from the command so the
     * dedicated-server self-test can print the same diagnosis without a command source - which is
     * the situation where it is most needed, since there is no console attached.
     *
     * @return the report, or null when the id does not parse
     */
    public static String report(ResourceManager resources, String requested) {
        ResourceLocation modelId = requested.indexOf(':') >= 0
                ? ResourceLocation.tryParse(requested)
                : ResourceLocation.tryParse(Model3D.MOD_ID + ":" + requested);
        if (modelId == null) {
            return null;
        }

        ModelLocation location = ModelLocation.of(modelId);
        // A resource-manager path is relative to a pack root and carries the namespace as its first
        // segment, so it never contains "data/" or "assets/" - see ModelLocation.resourcePath().
        String relativeRoot = location.resourcePath();

        StringBuilder out = new StringBuilder("Model3D diag for " + modelId + "\n");
        out.append("  namespace ").append(modelId.getNamespace())
                .append(", relative root \"").append(relativeRoot).append("\"\n");

        // Which pack roots this side actually serves. This is the single most useful line in the
        // report: `assets/` is a client concept, and a dedicated server's resource manager loads
        // `data/` - so a model stored under assets/ is invisible to the server even though it sits
        // in the same jar and the client can see it perfectly. Probing a known vanilla file in
        // each tree settles which root is live on this side, rather than inferring it.
        out.append("  root probe (namespace minecraft): ")
                .append("data/loot_tables=")
                .append(resources.getResource(new ResourceLocation("minecraft",
                        "loot_tables/empty.json")).isPresent())
                .append(", assets/models=")
                .append(resources.getResource(new ResourceLocation("minecraft",
                        "models/block/stone.json")).isPresent())
                .append('\n');
        // The exact strings the loader builds, so the report cannot disagree with it by a character.
        out.append("  loader resource path: \"").append(location.resourcePath()).append("\"\n");
        out.append("  listResources(name-index root \"").append(ModelLocation.MODEL3D_ROOT)
                .append("\") -> ")
                .append(resources.listResources(ModelLocation.MODEL3D_ROOT, p -> true).keySet())
                .append('\n');
        out.append("  listResources(loader path) -> ")
                .append(resources.listResources(relativeRoot, p -> true).size())
                .append(" entr(ies)\n");
        out.append("  getResource(loader path + \"/model.glb\") -> ")
                .append(resources.getResource(new ResourceLocation(modelId.getNamespace(),
                        relativeRoot + "/model.glb")).isPresent()).append('\n');
        // If even vanilla files miss, the problem is not this mod's resources at all - it is that
        // this resource manager is not the one that was used to load the server. Report the packs
        // it actually holds so that distinction is visible instead of inferred.
        out.append("  packs: ");
        resources.listPacks().forEach(pack -> out.append('[').append(pack.packId()).append(']'));
        out.append('\n');
        // Per pack: does it even declare the namespace we need, and does it hold the directory we
        // are asking for? A pack that declares neither is not a resource-pack wiring problem and
        // cannot be fixed by changing paths; a pack that declares the namespace but not the
        // directory means the file never reached the mod's resource output.
        resources.listPacks().forEach(pack -> {
            java.util.Set<String> serverNamespaces = pack.getNamespaces(
                    net.minecraft.server.packs.PackType.SERVER_DATA);
            java.util.Set<String> clientNamespaces = pack.getNamespaces(
                    net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
            out.append("    ").append(pack.packId())
                    .append(": SERVER_DATA ns=").append(serverNamespaces)
                    .append(" CLIENT_RESOURCES ns=").append(clientNamespaces)
                    .append('\n');
        });
        out.append("  vanilla getResource(\"minecraft:pack.mcmeta\"): ")
                .append(resources.getResource(new ResourceLocation("minecraft", "pack.mcmeta"))
                        .isPresent()).append('\n');
        // One known file from this mod's own jar, requested through the same manager. Its absence
        // and the pack list together say whether the mod's resource output reached this server at
        // all - which is a build-side fact, not something a path change can fix.
        out.append("  this mod's own file: getResource(\"model3d:model3d/animated_test/model.glb\")=")
                .append(resources.getResource(
                        new ResourceLocation(Model3D.MOD_ID,
                                ModelLocation.MODEL3D_ROOT + "/" + ModelLocation.MODEL3D_ROOT
                                        + "/animated_test/model.glb")).isPresent())
                .append('\n');
        out.append("  classes: ResourceManager=")
                .append(resources.getClass().getName()).append('\n');
        // Can the manager serve a *vanilla data* path it must have served to boot at all? If this
        // is non-zero while the mod's own paths are zero, the manager works and the problem is
        // specific to this mod's resources; if it is zero too, this manager object is not the live
        // one and nothing about mod resources can be concluded from it.
        for (String path : new String[] { "loot_tables", "recipes", "tags", "advancements" }) {
            out.append("  listResources(\"").append(path).append("\") -> ")
                    .append(resources.listResources(path, p -> true).size()).append('\n');
        }
        for (String ns : new String[] { "minecraft", Model3D.MOD_ID }) {
            // `models` is an assets-only directory, so it also serves as a tree probe: non-zero
            // means this manager serves assets (client), zero means data-only (dedicated server).
            String root = ns.equals("minecraft") ? "models" : ModelLocation.MODEL3D_ROOT;
            out.append("  listResources(\"").append(root).append("\") [ns ")
                    .append(ns).append("] -> ")
                    .append(resources.listResources(root, p -> p.getNamespace().equals(ns)).size())
                    .append('\n');
        }

        // Direct get-by-path, for the descriptor and for each conventional model file name. A hit
        // here proves the pack tree can serve the path, independently of listing.
        out.append("  get ").append(relativeRoot).append("/model.json: ")
                .append(resources.getResource(new ResourceLocation(modelId.getNamespace(),
                        relativeRoot + "/model.json")).isPresent()).append('\n');
        for (ModelFormat format : ModelFormat.values()) {
            String file = "model." + format.extension();
            Optional<Resource> found = resources.getResource(
                    new ResourceLocation(modelId.getNamespace(), relativeRoot + "/" + file));
            out.append("  get ").append(relativeRoot).append('/').append(file).append(": ")
                    .append(found.isPresent()).append('\n');
        }
        // Every file the directory actually exposes, so an unconventional model file name such as
        // "Su30 export version 2024_9_28.glb" is visible rather than merely absent from the
        // conventional-name checks above.
        Map<ResourceLocation, Resource> directory = resources.listResources(relativeRoot, p -> true);
        out.append("  listResources(\"").append(relativeRoot).append("\") -> ")
                .append(directory.size()).append(" entr(ies)\n");
        directory.keySet().forEach(id -> out.append("      ").append(id).append('\n'));

        if (directory.isEmpty()) {
            // Broadening the query separates "the namespace is not registered" from "the namespace
            // is registered but this directory is invisible". They need different fixes.
            Map<ResourceLocation, Resource> broad = resources.listResources(
                    ModelLocation.MODEL3D_ROOT, p -> true);
            out.append("  listResources(\"").append(ModelLocation.MODEL3D_ROOT)
                    .append("\") -> ").append(broad.size()).append(" entr(ies)\n");
            broad.keySet().stream().limit(30).forEach(id -> out.append("      ").append(id).append('\n'));
            Map<ResourceLocation, Resource> all = resources.listResources("", p -> true);
            out.append("  listResources(\"\") (whole tree) -> ").append(all.size())
                    .append(" entr(ies)\n");
        }

        // The name index the command's completion and validation use, then a real load - the only
        // step that can prove the parser accepts the file.
        ModelLoadService.INSTANCE.invalidateNameIndex();
        out.append("  name index: ")
                .append(ModelLoadService.INSTANCE.knownServerModelNames(resources)).append('\n');
        ModelHandle handle = ModelLoadService.INSTANCE.acquireServer(resources, modelId);
        out.append("  load: ").append(handle == null
                ? "FAILED (see the log for the parse error)"
                : "OK " + handle.scene() + " from " + handle.sourceDescription()).append('\n');
        if (handle != null) {
            ModelLoadService.INSTANCE.releaseServer(modelId);
        }
        return out.toString();
    }
}
