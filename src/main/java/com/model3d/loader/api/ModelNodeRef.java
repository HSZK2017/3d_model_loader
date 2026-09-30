package com.model3d.loader.api;

import com.model3d.loader.scene.ModelNode;

/**
 * A handle on one node of one {@link ModelInstance} - one part of one model in the world.
 *
 * <p>This is how a mod drives the parts of a model from its entity's state, which is what a flight
 * model needs and what "play a whole animation clip or nothing" cannot express: elevator, aileron and
 * rudder deflection from pitch/roll/yaw, a nozzle that expands and a plume that brightens under
 * afterburner, landing gear and gear-bay doors that move as they retract, anti-collision lights that
 * blink. Each of those is one node, or one material, driven from a value the caller already has.
 *
 * <h2>Overrides, and what they override</h2>
 * Every setter here installs an <b>override</b>: a value that is re-applied on top of the node's
 * animated pose once per frame, after the animation has written the tree. That ordering is the whole
 * design and it is worth stating plainly, because the alternative fails in a way that looks like the
 * feature doing nothing:
 * <ul>
 *   <li>the pose pipeline resets every node to the file's rest pose and then samples the active clip
 *       (an animation is routinely partial - see {@code AnimationPlayer}), so a value written into a
 *       node <i>before</i> {@code update()} is overwritten by the next sample;</li>
 *   <li>an override is therefore applied <i>after</i> the sample, which also means it reaches the
 *       derived state: world transforms, {@code pose.worldMatrices()} and the skinning matrices. A
 *       driven node that is a skin joint moves the skin, not only its own matrix;</li>
 *   <li>it survives {@code update()} calls and does not have to be re-set each frame. Set it when
 *       the entity's state changes, not every frame - though setting it every frame is harmless.</li>
 * </ul>
 *
 * <h2>Per instance, never per model</h2>
 * An override lives on this instance's own node tree - the copy {@code ModelScene#instantiate()}
 * made - never on the scene's {@code nodeTemplates()}, which every entity using that model shares.
 * Two aircraft flying in formation must be able to hold different deflections; an override written
 * to the template would move both, and the symptom reads as a networking bug rather than as an
 * aliasing bug. The same holds for {@link ModelInstance#setMaterialTint}.
 *
 * <p>Three of these take effect at the next {@link ModelInstance#update(float)}, because that is
 * where a pose is produced from the tree - set a deflection and it is on screen from that frame on,
 * and it stays there until {@link #clear()}. {@link #setVisible} is the exception: it is
 * re-propagated the moment it is set, so a caller that hides a part and builds a draw list in the
 * same tick sees it hidden (there is no pose to wait for).
 *
 * <h2>Clearing</h2>
 * {@link #clear()} drops every override on the node and returns it to whatever the file and the
 * animation say. {@code clear()} on a node that has no overrides is a no-op, not an error: a caller
 * that clears state unconditionally each tick should not have to track what it set.
 *
 * <h2>Threading</h2>
 * CPU-only and <b>not thread-safe</b>, like the {@link ModelInstance} it belongs to: client render
 * thread only, never a netty thread and never the server tick.
 */
public final class ModelNodeRef {

    /** The instance whose tree this handle addresses; used to keep inherited visibility correct. */
    private final ModelInstance instance;

    /** Index of the node in the instance's tree; kept so propagation can look up its parent. */
    private final int nodeIndex;

    /** The node itself, so the plain reads and writes need no array lookup. */
    private final ModelNode node;

    ModelNodeRef(ModelInstance instance, int nodeIndex, ModelNode node) {
        this.instance = instance;
        this.nodeIndex = nodeIndex;
        this.node = node;
    }

    /** The node's name, as the model file wrote it. */
    public String name() {
        return node.name();
    }

    /** True when this node carries any override: rotation, translation, scale or visibility. */
    public boolean isOverridden() {
        return node.hasOverrides();
    }

    /**
     * Replaces this node's own local rotation with the Euler angles {@code (x, y, z)} in degrees.
     *
     * <p>Angles are applied <b>x first, then y, then z</b>, each about the node's own axes at rest,
     * which is the same thing as the matrix product {@code Rz * Ry * Rx} applied to a column vector -
     * the common "XYZ Euler" order. The order is stated because it is observable and because three
     * axes of an aircraft are not symmetric: an elevator (x) and a rudder (y) composed in the other
     * order put the surface somewhere else, and neither order is more "correct" than the other, only
     * documented or not.
     *
     * <p>The rest of the node's pose is untouched: this is only the node's own rotation. A parent
     * chain still applies on top, which is what makes a control surface deflect relative to the wing
     * that carries it.
     */
    public ModelNodeRef setRotation(float xDegrees, float yDegrees, float zDegrees) {
        // Halved angles for the quaternion product q = qz * qy * qx, written out component by
        // component rather than built from three temporary quaternions: a handful of sin/cos calls
        // and no allocation, on a method a caller may well call once per frame per surface.
        float hx = (float) Math.toRadians(xDegrees) * 0.5f;
        float hy = (float) Math.toRadians(yDegrees) * 0.5f;
        float hz = (float) Math.toRadians(zDegrees) * 0.5f;
        float sx = (float) Math.sin(hx);
        float cx = (float) Math.cos(hx);
        float sy = (float) Math.sin(hy);
        float cy = (float) Math.cos(hy);
        float sz = (float) Math.sin(hz);
        float cz = (float) Math.cos(hz);
        node.setRotationOverride(
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz);
        return this;
    }

    /**
     * Adds a translation offset in the node's own local space, in the model file's units (not
     * blocks - the instance's scale is applied later, as it is for the node's authored offset).
     *
     * <p><b>Additive</b>, and deliberately so: a gear leg extends from wherever the animation put
     * it, and a part driven from an entity's state should not have to restate the pose the file
     * authored. The offset moves with the node's parent, so a part driven relative to its parent
     * (which is what a hierarchy of parts means) follows it.
     */
    public ModelNodeRef setTranslation(float x, float y, float z) {
        node.setTranslationOverride(x, y, z);
        return this;
    }

    /**
     * Scales this node by a uniform factor about its own origin - the exhaust nozzle that expands
     * under afterburner, the landing gear that shrinks as it retracts.
     *
     * <p>This replaces the node's own local scale, so it wins over a scale channel of the active
     * clip, exactly as {@link #setRotation} wins over a rotation channel. The scale sits in the
     * node's local transform, so the node's <b>children inherit it</b> - which is what "the nozzle
     * assembly expands" means in a transform hierarchy, and is the reason a uniform factor is applied
     * rather than a per-axis one.
     */
    public ModelNodeRef setScale(float uniform) {
        node.setScaleOverride(uniform);
        return this;
    }

    /**
     * Shows or hides this node and, because visibility is inherited, everything parented to it: a
     * gear-bay door node hides the door, its hinges and anything else under it. The part is not
     * merely scaled away or moved out of sight - its primitives are not submitted at all.
     *
     * <p>Hiding is per instance: the model's shared templates are untouched, so the other aircraft
     * with the same model still shows its gear.
     *
     * <p>A side effect worth knowing when a driven part is a skin joint: hiding stops the
     * <b>geometry attached to that node and its descendants</b> from being drawn, but it does not
     * collapse the skin of other meshes that those joints deform. That is the useful behaviour -
     * hiding a canopy frame should not tear the fuselage skin - but it does mean "hide the bone"
     * and "hide the part" are the same thing only when the part's mesh hangs off the node.
     */
    public ModelNodeRef setVisible(boolean visible) {
        instance.setNodeVisible(nodeIndex, node, visible);
        return this;
    }

    /**
     * Effective visibility: false when this node or any ancestor is hidden.
     *
     * <p>Effective rather than "what was last passed to {@link #setVisible}", so it answers the
     * question a caller actually has: is this part drawn? Hiding a parent makes this false without
     * anyone having called {@code setVisible} on this node.
     *
     * <p>Valid immediately after {@link #setVisible}, not only after the next
     * {@link ModelInstance#update(float)}.
     */
    public boolean visible() {
        return node.isVisible();
    }

    /**
     * Drops every override on this node - rotation, translation, scale and visibility - returning it
     * to whatever the animation and the file say, and re-revealing its subtree if it had been hidden.
     *
     * <p>A no-op when there is nothing to clear, so a caller that clears unconditionally is safe.
     */
    public ModelNodeRef clear() {
        node.clearOverrides();
        // The effective flag depends on the ancestors, which only the instance can see - so the
        // clearing is completed by the same propagation that setVisible uses. Without it a cleared
        // node would keep reporting (and drawing) the hidden state it had before.
        instance.setNodeVisible(nodeIndex, node, true);
        return this;
    }

    @Override
    public String toString() {
        return "ModelNodeRef('" + node.name() + "' overridden=" + node.hasOverrides()
                + " visible=" + node.isVisible() + ")";
    }
}
