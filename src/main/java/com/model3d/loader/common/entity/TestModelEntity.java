package com.model3d.loader.common.entity;

import com.model3d.loader.Model3D;
import com.model3d.loader.common.network.ModelSyncPacket;
import com.model3d.loader.common.network.NetworkHandler;
import com.model3d.loader.util.Ids;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The API's proof-of-work entity: a live attachment point for a loaded 3D model.
 *
 * <p>Two jobs, and they are deliberately the same object:
 * <ol>
 *   <li>It demonstrates that the API is usable end to end - a model is resolved on the server,
 *       synced to clients, parsed once, posed once per frame and drawn on this entity.</li>
 *   <li>It is the <b>fallback marker</b>. With no model attached it renders as a vanilla pig,
 *       which is a deliberate choice rather than a placeholder: a pig at the spawn coordinates
 *       makes the entity's position and orientation readable at a glance, which is exactly what
 *       you need while diagnosing whether a model failed to load or merely failed to draw. An
 *       invisible entity would be indistinguishable from a crash or a missing spawn.</li>
 * </ol>
 *
 * <h2>Spawning</h2>
 * Registered with {@code EntityType.Builder#noSummon()}, plus {@link #isPickable()} left true and
 * no spawn placement registration, so the entity cannot appear through vanilla {@code /summon},
 * a spawn egg, natural spawning or a mob spawner. The only way in is
 * {@code /testmodel loader <model> <x> <y> <z>}, which is what makes it a test fixture rather
 * than a gameplay entity.
 *
 * <h2>Model state and the network</h2>
 * The model name is authoritative on the server and is <b>not</b> a synced entity data value.
 * It travels in {@link ModelSyncPacket} instead, because the client needs more than a name: it
 * needs the animation list to validate a requested animation, and a {@code SynchedEntityData}
 * entry would mean one string per field and a per-tick diff for data that changes once. The
 * packet is sent on spawn and to every player who starts tracking the entity.
 */
public class TestModelEntity extends PathfinderMob {

    /**
     * The model this entity should render, or empty for "none".
     *
     * <p>An empty string rather than a nullable value: {@code SynchedEntityData} has no null
     * representation for strings, and a sentinel value that also means "absent" is precisely the
     * sentinel-mixing mistake this field avoids - the meaning is carried by
     * {@link #hasModel()}, never inferred from the string.
     */
    private static final EntityDataAccessor<String> DATA_MODEL_ID =
            SynchedEntityData.defineId(TestModelEntity.class, EntityDataSerializers.STRING);

    /**
     * Which animation the client should play, or empty for the descriptor's default.
     *
     * <p>Synced, unlike the model list, because this is the value a player changes at runtime
     * (right-click cycles the animations) and it must reach the renderer on the same tick.
     */
    private static final EntityDataAccessor<String> DATA_ANIMATION =
            SynchedEntityData.defineId(TestModelEntity.class, EntityDataSerializers.STRING);

    /**
     * The model's animation names, replicated from the server so the client can offer and
     * validate them without re-parsing the model file.
     *
     * <p>One string, names separated by {@link #ANIMATION_SEPARATOR}, rather than a list value:
     * {@code EntityDataSerializers} has no list-of-strings serializer, and the alternatives are
     * worse - a {@code CompoundTag} costs a tag build per write, and encoding the absence of
     * animations as an empty list is indistinguishable from a packet that has not arrived yet.
     * The separator is a newline because glTF animation names are arbitrary strings that may
     * contain almost anything, but a newline in an animation name would break the JSON of any
     * exporter that emitted one.
     */
    private static final String ANIMATION_SEPARATOR = "\n";
    private static final EntityDataAccessor<String> DATA_ANIMATIONS =
            SynchedEntityData.defineId(TestModelEntity.class, EntityDataSerializers.STRING);

    /** Scale in blocks per model unit, synced from the server's descriptor. */
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(TestModelEntity.class, EntityDataSerializers.FLOAT);

    /**
     * The value of {@link #DATA_SCALE} that means "no scale has been set".
     *
     * <p>A dedicated sentinel rather than 1.0: one block per model unit is a perfectly legitimate
     * scale (a model authored in blocks, or a descriptor whose {@code targetBlocks} works out to one
     * block per unit), so using it to mean "unset" silently replaced those models with the loader's
     * normalisation. Nothing legitimate is zero or negative, so this costs no expressiveness.
     */
    public static final float UNSET_SCALE = 0.0f;

    private static final String NBT_MODEL = "Model3DId";
    private static final String NBT_ANIMATION = "Model3DAnimation";
    private static final String NBT_ANIMATION_LOOP = "Model3DAnimationLoop";
    private static final String NBT_SCALE = "Model3DScale";
    private static final String NBT_ANIMATIONS = "Model3DAnimations";

    private boolean animationLoop = true;

    public TestModelEntity(EntityType<? extends TestModelEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        // No gravity, so the entity stays exactly where it is put.
        //
        // This is a marker for inspecting a model, and a marker that falls is worse than useless: asked
        // to appear at eye level it drops to the ground before anyone sees it, so the model is never
        // where it was placed and "the model is not where I put it" and "the model did not load" become
        // the same observation. That is not hypothetical - a spawn at eye height was measured 88 blocks
        // away and far below its placement in this mod's own test runs.
        this.setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_MODEL_ID, "");
        this.entityData.define(DATA_ANIMATION, "");
        this.entityData.define(DATA_ANIMATIONS, "");
        this.entityData.define(DATA_SCALE, UNSET_SCALE);
    }

    @Override
    protected void registerGoals() {
        // The entity stands still, and that is a decision made after watching it wander.
        //
        // It used to carry FloatGoal and RandomStrollGoal, on the reasoning that a walking entity proves
        // the render transform follows body yaw. In practice a model being inspected drifted away from
        // where it was spawned, which made "the model is in the wrong place" and "the model is where I
        // put it but has walked off" impossible to tell apart - and a test fixture that moves is a
        // worse fixture than one that does not. Yaw still changes when the player looks at it, because
        // LookAtPlayerGoal and RandomLookAroundGoal only turn the head and body in place.
        this.goalSelector.addGoal(0, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(1, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------
    // Model state
    // ------------------------------------------------------------------

    /** True when a model is attached; false means "render the pig fallback". */
    public boolean hasModel() {
        return !this.entityData.get(DATA_MODEL_ID).isEmpty();
    }

    /**
     * The attached model id, or null when none.
     *
     * <p>Returns null rather than a dummy id so a caller cannot accidentally load a model at
     * {@code model3d:} - an empty-path {@link ResourceLocation} is exactly the kind of value
     * that parses silently and then resolves to nothing.
     */
    @Nullable
    public ResourceLocation modelId() {
        String value = this.entityData.get(DATA_MODEL_ID);
        return value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }

    /** Animation to play, or null when the renderer should use the descriptor's default. */
    @Nullable
    public String animationName() {
        String value = this.entityData.get(DATA_ANIMATION);
        return value.isEmpty() ? null : value;
    }

    /** Animation names known for the attached model; empty until the sync packet arrives. */
    public String[] animationNames() {
        String packed = this.entityData.get(DATA_ANIMATIONS);
        if (packed.isEmpty()) {
            return new String[0];
        }
        return packed.split(ANIMATION_SEPARATOR, -1);
    }

    public boolean isAnimationLooping() {
        return this.animationLoop;
    }

    public float modelScale() {
        return this.entityData.get(DATA_SCALE);
    }

    /**
     * Attaches a model. Server side only - the synced values drive the client.
     *
     * @param animations animation names the model exposes, in file order; may be empty
     */
    public void setModel(@Nullable ResourceLocation modelId, float scale, List<String> animations) {
        this.entityData.set(DATA_MODEL_ID, modelId == null ? "" : modelId.toString());
        this.entityData.set(DATA_SCALE, scale);
        this.entityData.set(DATA_ANIMATIONS, String.join(ANIMATION_SEPARATOR, animations));
        Model3D.LOGGER.info("Model3D: test entity {} model={} scale={} animations={}",
                this.getId(), modelId, scale, animations);
    }

    /** Selects the animation to play; server side only. */
    public void setAnimation(@Nullable String animation, boolean loop) {
        this.entityData.set(DATA_ANIMATION, animation == null ? "" : animation);
        this.animationLoop = loop;
    }

    /**
     * Cycles to the next animation the model offers - the in-game way to check that every
     * animation in a file actually plays, without editing files or reissuing a command.
     *
     * @return the newly selected animation name, or null when the model has none
     */
    @Nullable
    public String cycleAnimation() {
        String[] names = animationNames();
        if (names.length == 0) {
            return null;
        }
        String current = animationName();
        int index = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(current)) {
                index = (i + 1) % names.length;
                break;
            }
        }
        setAnimation(names[index], true);
        return names[index];
    }

    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player,
                                                               net.minecraft.world.InteractionHand hand) {
        if (!this.level().isClientSide && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            String selected = cycleAnimation();
            if (selected != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Model3D: animation -> " + selected), true);
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Model3D: this model has no animations"), true);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    // ------------------------------------------------------------------
    // Persistence and sync
    // ------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        String model = this.entityData.get(DATA_MODEL_ID);
        if (!model.isEmpty()) {
            tag.putString(NBT_MODEL, model);
            tag.putFloat(NBT_SCALE, modelScale());
            tag.putString(NBT_ANIMATION, this.entityData.get(DATA_ANIMATION));
            tag.putBoolean(NBT_ANIMATION_LOOP, animationLoop);
            tag.putString(NBT_ANIMATIONS, this.entityData.get(DATA_ANIMATIONS));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(NBT_MODEL)) {
            String packed = tag.getString(NBT_ANIMATIONS);
            List<String> names = packed.isEmpty()
                    ? List.of() : List.of(packed.split(ANIMATION_SEPARATOR, -1));
            setModel(ResourceLocation.tryParse(tag.getString(NBT_MODEL)),
                    tag.contains(NBT_SCALE) ? tag.getFloat(NBT_SCALE) : UNSET_SCALE, names);
            setAnimation(tag.getString(NBT_ANIMATION), tag.getBoolean(NBT_ANIMATION_LOOP));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /**
     * Sends the model description to one client.
     *
     * <p>Needed in addition to the spawn packet because the entity exists before a model is
     * attached in the common case: {@code /testmodel loader} re-targets or attaches a model to
     * an already-spawned entity, and the client would otherwise keep rendering the pig forever.
     */
    public void sendModelSync(ServerPlayer player) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ModelSyncPacket(this.getId(), modelId(), modelScale(), animationName(),
                        animationLoop, List.of(animationNames())));
    }
}
