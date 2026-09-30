package com.model3d.loader.resource;

import com.model3d.loader.Model3D;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * The folders a player can drop a model into, resolved once and cached.
 *
 * <h2>Search order, and why this order</h2>
 * <ol>
 *   <li><b>{@code config/3dmodels/}</b> - the folder this mod creates and documents. First because it
 *       is the one a user is told about: a model placed there must win, or the documented answer to
 *       "where do I put this" would be wrong whenever anything else has the same name.</li>
 *   <li>{@code <gamedir>/model3d/<namespace>/<name>/} - the original layout, kept so models already
 *       placed there keep working. It is namespaced, which makes it the right place for a pack that
 *       ships several namespaces, and the wrong place to tell a new user about.</li>
 * </ol>
 *
 * <p>Both are real directories, so both are scanned with {@link ModelLibrary} and both hot reload.
 * A pack-shipped model inside a jar is found the same way as before, through the resource manager.
 *
 * <p>Separated from {@link ModelLoadService} because the game directory only exists once Forge has
 * bootstrapped, while that class may be constructed earlier: {@link #configModels()} returns null in
 * that window rather than throwing, so an offline tool or a unit test sees "no external models"
 * instead of a crash.
 */
public final class ExternalModelPaths {

    /** The folder under {@code config/} that this mod owns. */
    public static final String CONFIG_MODELS = ModelLibrary.DIRECTORY_NAME;

    /** The original folder directly under the game directory, still supported. */
    public static final String LEGACY_MODELS = "model3d";

    private static volatile Path configModels;
    private static volatile Path legacyModels;
    private static volatile boolean resolved;

    private ExternalModelPaths() {
    }

    /**
     * The primary folder: {@code config/3dmodels/}, or null before Forge knows the config directory.
     *
     * <p>{@code FMLPaths.CONFIGDIR} rather than {@code GAMEDIR/config} because the config directory is
     * relocatable: mods and servers routinely move it, and writing to a hard-coded path would put the
     * folder somewhere the user does not look and where a launcher may not persist it.
     */
    public static Path configModels() {
        resolve();
        return configModels;
    }

    /** The legacy folder: {@code <gamedir>/model3d/}, or null before bootstrap. */
    public static Path legacyModels() {
        resolve();
        return legacyModels;
    }

    /** The primary {@link ModelLibrary}, never null; its root may be null before bootstrap. */
    public static ModelLibrary library() {
        return new ModelLibrary(configModels());
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        try {
            Path config = FMLPaths.CONFIGDIR.get().resolve(CONFIG_MODELS);
            Path legacy = FMLPaths.GAMEDIR.get().resolve(LEGACY_MODELS);
            configModels = config;
            legacyModels = legacy;
            resolved = true;
            Model3D.LOGGER.debug("Model3D: model folders resolved to {} and {}", config, legacy);
        } catch (Throwable t) {
            // FMLPaths throws when Forge has not initialized. That is a legitimate state for the
            // offline tools and the unit tests, not an error worth propagating - but it must not be
            // cached as resolved, or a later call after bootstrap would keep seeing null.
            Model3D.LOGGER.debug("Model3D: game paths not available yet ({})", t.toString());
        }
    }
}
