package com.model3d.loader.client.render;

import com.model3d.loader.resource.ModelLocation;
import com.model3d.loader.util.Ids;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the texture-path handling that crashed the client.
 *
 * <h2>The failure</h2>
 * {@code ClientTextureResolver.resolveBaseColorId} built a {@code ResourceLocation} straight from a path
 * taken out of a model file:
 *
 * <pre>
 *   ResourceLocation found = find(resourceManager, location.inModelRoot(relative));   // :550
 * </pre>
 *
 * <p>{@code ResourceLocation}'s constructor rejects anything outside {@code [a-z0-9/._-]} and
 * <b>throws</b>, while ordinary exporter output is exactly that: {@code Textures/Glass_Cockpit.jpeg}.
 * The throw happened while <i>building the argument</i>, so the case-insensitive retry inside
 * {@code find()} could never run, and the exception left the entity renderer -
 * {@code RenderTestModelEntity.render} has a {@code try}/{@code finally} and no catch - producing
 * {@code ReportedException: Rendering entity in world} and killing the client.
 *
 * <p>The first test is the negative control for the guard: it asserts that the unguarded
 * construction really does throw, so the guard is demonstrably not decoration.
 */
class TextureLocationTest {

    @Test
    @DisplayName("control: an exporter-cased path cannot be built into a ResourceLocation at all")
    void unguardedConstructionThrows() {
        // The pre-fix body of resolveBaseColorId, verbatim: ModelLocation#inModelRoot built exactly
        // this location, and Ids.of is the constructor it used. If this ever stops throwing, the
        // guard above has nothing left to guard and this test should be deleted with it.
        ResourceLocationException error = assertThrows(ResourceLocationException.class,
                () -> Ids.of("model3d", "model3d/su30/Textures/Glass_Cockpit.jpeg"),
                "the raw path must be rejected by ResourceLocation, which is why constructing it "
                        + "unguarded crashed the render loop");
        System.out.println("unguarded construction: " + error.getMessage());
    }

    @Test
    @DisplayName("control: a path with a space cannot be built into a ResourceLocation either")
    void unguardedConstructionThrowsOnSpace() {
        assertThrows(ResourceLocationException.class,
                () -> Ids.of("model3d", "model3d/su30/textures/my plane.png"));
    }

    @Test
    @DisplayName("an exporter-cased texture path lowers into a location the pack can be asked for")
    void exporterCasingIsLoweredNotThrown() {
        ModelLocation location = ModelLocation.of("model3d", "su30");
        ResourceLocation resolved = assertDoesNotThrow(
                () -> location.inModelRootOrNull("Textures/Glass_Cockpit.jpeg"));
        assertEquals(Ids.parse("model3d:model3d/su30/textures/glass_cockpit.jpeg"), resolved,
                "the lookup must be the lower-cased path the game's resource system uses");
    }

    @Test
    @DisplayName("a texture path under the namespace's texture root resolves the same way")
    void textureRootFallbackIsAlsoLowered() {
        ModelLocation location = ModelLocation.of("model3d", "su30");
        // The argument here is relative to the texture root, because that is what the method
        // prefixes; a model that writes "textures/x.png" goes through the model-root attempt first.
        assertEquals(Ids.parse("model3d:textures/glass.png"),
                location.inTextureRootOrNull("Glass.png"));
        assertEquals(Ids.parse("model3d:textures/glass.png"),
                location.inTextureRootOrNull("Glass.PNG"));
    }

    @Test
    @DisplayName("a path that no lowering can make addressable yields null, never an exception")
    void unaddressableNameYieldsNull() {
        ModelLocation location = ModelLocation.of("model3d", "su30");
        assertNull(location.inModelRootOrNull("textures/my plane.png"),
                "a space cannot appear in a resource path, so this is a miss to report, "
                        + "not an exception to throw out of a frame");
        assertNull(location.inTextureRootOrNull("textures/my plane.png"));
    }

    @Test
    @DisplayName("an already-valid path is returned unchanged, so the exact case still wins")
    void validPathIsNotRewritten() {
        ModelLocation location = ModelLocation.of("model3d", "su30");
        assertEquals(Ids.parse("model3d:model3d/su30/textures/glass.png"),
                location.inModelRootOrNull("textures/glass.png"));
    }
}
