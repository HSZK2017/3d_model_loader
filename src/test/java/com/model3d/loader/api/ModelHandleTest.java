package com.model3d.loader.api;

import com.model3d.loader.scene.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the reference-count contract the public API hands to other mods.
 *
 * <h2>Why the boolean matters</h2>
 * {@code release()} answers one question - "was that the last reference?" - and callers act on it by
 * evicting the model and freeing resources. The implementation used to answer {@code <= 0}, so a
 * second release of the same handle reported {@code true} again: a caller with a double-release bug
 * would free twice, or evict a model another holder was still rendering, and the API would look like
 * it had sanctioned that. The contract is now exact, and a breach is reported rather than answered.
 */
class ModelHandleTest {

    /**
     * A minimal scene: the handle only stores it, and these tests are about the count and the
     * descriptor, not about geometry.
     */
    private static ModelScene scene() {
        return new ModelScene("test", new int[0], new com.model3d.loader.scene.ModelNode[0],
                new com.model3d.loader.scene.ModelMesh[0],
                new com.model3d.loader.scene.ModelMaterial[0],
                new com.model3d.loader.scene.ModelSkin[0], java.util.List.of(), new float[6],
                "unit test");
    }

    private static ModelHandle handle() {
        return new ModelHandle("test", scene(), "unit test");
    }

    @Test
    @DisplayName("the first release of the last reference reports true, a second reports false")
    void secondReleaseIsNotTheLastOne() {
        ModelHandle handle = handle();
        assertEquals(1, handle.referenceCount(), "a new handle starts with the cache's reference");

        assertTrue(handle.release(), "the first release drops the last reference");
        assertFalse(handle.release(),
                "a second release must not claim to be the last one again - that is how a "
                        + "double-release caller frees a model twice");
        assertEquals(-1, handle.referenceCount(),
                "the count goes negative, which is the visible evidence of the caller's bug");
    }

    @Test
    @DisplayName("acquire and release balance, and only the last release reports true")
    void acquireAndReleaseBalance() {
        ModelHandle handle = handle();
        handle.acquire();
        handle.acquire();
        assertEquals(3, handle.referenceCount());

        assertFalse(handle.release(), "one of three");
        assertFalse(handle.release(), "two of three");
        assertTrue(handle.release(), "the last one");
    }

    @Test
    @DisplayName("a handle carries a descriptor and never null")
    void descriptorIsNeverNull() {
        ModelHandle handle = new ModelHandle("test", scene(), "unit test", null);
        assertNotNull(handle.descriptor(),
                "a null descriptor is replaced by the defaults rather than propagated");
    }
}
