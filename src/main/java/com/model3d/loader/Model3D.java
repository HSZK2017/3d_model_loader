package com.model3d.loader;

import com.mojang.logging.LogUtils;
import com.model3d.loader.common.network.NetworkHandler;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.util.Ids;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.List;

/**
 * Entry point of the Model3D Loader API.
 *
 * <p>This mod loads common interchange 3D formats (glTF 2.0 / GLB, Wavefront OBJ / MTL) at
 * runtime, plays the animation data those files carry, and renders the result onto ordinary
 * Minecraft entities. It is an <b>API mod</b>: the point is that another mod can register a model
 * and attach it to its own entity without touching this mod's internals. The public surface is
 * everything under {@code com.model3d.loader.api}, plus the {@code resource} package's
 * {@code ModelLoadService}.
 *
 * <h2>Side discipline</h2>
 * This is the single most important thing to know about the codebase, because getting it wrong
 * is what turns a working mod into a dedicated-server crash:
 * <ul>
 *   <li><b>Geometry and animation</b> ({@code format}, {@code json}, {@code math}, {@code scene},
 *       {@code animation}) are side-agnostic plain Java. They name no Minecraft class at all, so
 *       the same code runs on a dedicated server, in this mod's JUnit suite and in the offline
 *       model inspector - which is what makes the parsers testable without booting a game.</li>
 *   <li><b>Resource loading</b> ({@code resource}) needs only the vanilla {@code ResourceManager}
 *       and therefore runs on both sides. Model files live under {@code data/<ns>/model3d/<name>/},
 *       with {@code assets/<ns>/model3d/<name>/} tried as a client-side fallback: a dedicated
 *       server's resource manager is built with {@code PackType.SERVER_DATA}, so it serves
 *       {@code data/} and never {@code assets/} - measured, and the reason this order is what it is
 *       (see {@code ModelLocation}). That is what lets a server resolve and parse a model, which is
 *       which is how a companion mod's command can report a bad model name at command time instead
 *       of leaving the client to fail silently.</li>
 *   <li><b>{@code client}</b> is the only package that touches OpenGL, and it is only ever
 *       reached from the client's render thread.</li>
 * </ul>
 */
@Mod(Model3D.MOD_ID)
public class Model3D {

    public static final String MOD_ID = "model3d";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * {@code FMLJavaModLoadingContext.get()} is deprecated-for-removal in this Forge version in
     * favour of constructor injection, which does not exist yet on 1.20.1. One class-level
     * suppression keeps the single unavoidable use of it visible and local instead of scattering
     * {@code @SuppressWarnings} across the codebase.
     */
    public Model3D() {
        // No registries and no event subscriptions of its own: the network channel is a static field,
        // and a carrier entity belongs to the mod that owns it (see ModelCarrier). The companion test
        // mod is the reference implementation, and it is also what keeps this jar free of test code.
        NetworkHandler.register();

        List<String> extensions = ModelFormatRegistry.supportedExtensions();
        // The order matters and is documented in ModelLocation: a dedicated server serves data/ only,
        // so naming assets/ first in the line a user reads at startup is how a model ends up somewhere
        // the server cannot see.
        LOGGER.info("Model3D Loader API: formats={}, models resolve from config/3dmodels/, "
                        + "data/<namespace>/model3d/<name>/ (assets/ as a client fallback) and "
                        + "<gamedir>/model3d/<namespace>/<name>/",
                extensions);
    }

    /** Builds a {@link ResourceLocation} in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return Ids.of(MOD_ID, path);
    }
}
