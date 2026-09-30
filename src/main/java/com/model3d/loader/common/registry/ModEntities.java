package com.model3d.loader.common.registry;

import com.model3d.loader.Model3D;
import com.model3d.loader.common.entity.TestModelEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Entity registration.
 *
 * <p>One entity, registered {@code noSummon()} - see {@link TestModelEntity} for why that is a
 * requirement rather than a preference.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Model3D.MOD_ID);

    public static final RegistryObject<EntityType<TestModelEntity>> TEST_MODEL =
            ENTITIES.register("test_model",
                    () -> EntityType.Builder.<TestModelEntity>of(TestModelEntity::new, MobCategory.MISC)
                            // Keep the entity out of vanilla /summon, spawn eggs and mob spawners:
                            // /testmodel is the documented and only supported way in.
                            .noSummon()
                            // Saved, so a model attached by command survives a reload; without
                            // this the entity reverts to the pig marker after a world restart,
                            // which is easy to misread as the model having failed to load.
                            .sized(1.0f, 1.0f)
                            .clientTrackingRange(32)
                            .updateInterval(1)
                            .build("test_model"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
    }

    /**
     * Attributes must be supplied here rather than in the entity's constructor; Forge added this
     * event so a {@code Mob}'s attribute map is built once per type instead of per instance.
     */
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(TEST_MODEL.get(), TestModelEntity.createAttributes().build());
    }

    /** Convenience for the mod bus subscription, so no class needs to know the event type. */
    public static final class AttributeEvents {
        private AttributeEvents() {
        }

        @SubscribeEvent
        public static void onAttributes(EntityAttributeCreationEvent event) {
            onEntityAttributeCreation(event);
        }
    }
}
