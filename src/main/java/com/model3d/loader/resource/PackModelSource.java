package com.model3d.loader.resource;

import com.model3d.loader.util.Ids;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link com.model3d.loader.format.ModelSource} backed by Minecraft's {@link ResourceManager}.
 *
 * <p>This is how models shipped inside a mod jar, a datapack or a resource pack are read.
 *
 * <h2>Which tree, and why it is not a detail</h2>
 * A resource-manager path is relative to a pack <b>root</b> and is not prefixed by that root's
 * name, and the two roots are not interchangeable: a dedicated server builds its resource manager
 * with {@code PackType.SERVER_DATA} and therefore serves {@code data/} only, while the client loads
 * {@code assets/} too. So the root is a constructor parameter rather than a constant baked into the
 * path. {@link ModelLocation}'s class comment records the measurement that established this - a
 * server whose resource manager served 1091 loot tables and 0 {@code assets} entries, which is why
 * a model under {@code assets/} was invisible to the command that was supposed to validate it.
 *
 * <p>Listing goes through {@code listResources}; the index it feeds is what lets a reference to
 * {@code Textures/Glass.jpeg} find {@code textures/glass.jpeg}, since a direct {@code getResource}
 * lookup is case-sensitive by construction.
 */
public final class PackModelSource extends AbstractModelSource {

    private final ResourceManager resourceManager;
    private final ResourceLocation resourceRoot;

    /**
     * @param root         which pack tree to read, one of {@link ModelLocation#roots()}
     * @param mainRelative model file path relative to the model directory, or "" to list only
     */
    public PackModelSource(ResourceManager resourceManager, ModelLocation location, String root,
                           String mainRelative) {
        super(location.namespace(), location.rootPath(root), mainRelative);
        this.resourceManager = resourceManager;
        // The resource-manager path drops the root prefix: "data/model3d/model3d/su30" is requested
        // as "model3d/model3d/su30", because the manager already knows which root it is reading.
        this.resourceRoot = Ids.of(location.namespace(), location.resourcePath());
    }

    /** Reads the canonical {@code data} root; used by diagnostics and tests. */
    public PackModelSource(ResourceManager resourceManager, ModelLocation location,
                           String mainRelative) {
        this(resourceManager, location, ModelLocation.DATA_ROOT, mainRelative);
    }

    @Override
    protected List<String> listRelativeFiles() {
        String prefix = resourceRoot.getPath() + "/";
        List<String> files = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry
                : resourceManager.listResources(resourceRoot.getPath(), path -> true).entrySet()) {
            String path = entry.getKey().getPath();
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                files.add(path.substring(prefix.length()));
            }
        }
        return files;
    }

    @Override
    protected InputStream openExact(String relativePath) throws IOException {
        Optional<Resource> resource = resourceManager.getResource(
                Ids.of(resourceRoot.getNamespace(), resourceRoot.getPath() + "/" + relativePath));
        if (resource.isEmpty()) {
            return null;
        }
        return resource.get().open();
    }
}
