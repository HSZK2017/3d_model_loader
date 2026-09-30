package com.model3d.loader.util;

import net.minecraft.resources.ResourceLocation;

/**
 * The one place that constructs a {@link ResourceLocation}.
 *
 * <p>Exists because of a Forge migration detail that is invisible until it bites:
 * {@code new ResourceLocation(String, String)} is marked
 * {@code @Deprecated(forRemoval = true, since = "1.20.6")} in favour of
 * {@code ResourceLocation.fromNamespaceAndPath}, and this build treats deprecation-for-removal as
 * an error. Keeping the one call site here means one suppression with an explanation, instead of
 * a scattered annotation on every class that needs an id, and it gives a single place to move when
 * a future mapping removes the constructor.
 *
 * <p>Validation is deliberately not folded into the constructor helper: the ids this mod builds
 * come from user input (a model name typed into a command, a string read out of an untrusted model
 * file), so {@link #parse} returns null for a malformed id rather than throwing - "this id is
 * nonsense" has to be reportable, not fatal.
 */
public final class Ids {

    private Ids() {
    }

    /**
     * {@code namespace:path}, with both halves already known to be valid.
     *
     * <p>Deliberately the deprecated constructor rather than {@code fromNamespaceAndPath}: the
     * latter is a Forge addition, and relying on it would tie this class to Forge-specific
     * mappings for no functional gain. The suppression is the price of that portability and it is
     * paid in exactly one place.
     */
    @SuppressWarnings("removal")
    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    /** Parses {@code namespace:path}; returns null when the id is malformed. */
    public static ResourceLocation parse(String id) {
        return ResourceLocation.tryParse(id);
    }

}
