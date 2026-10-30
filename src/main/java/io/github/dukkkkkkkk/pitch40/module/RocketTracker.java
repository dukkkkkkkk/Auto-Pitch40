package io.github.dukkkkkkkk.pitch40.module;

import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.OptionalInt;

import static com.zenith.Globals.CACHE;

/**
 * Stops a second rocket being fired before the first one's entity shows up attached to the player.
 */
final class RocketTracker {
    static final int CONFIRM_TIMEOUT_TICKS = 40;
    /** Firework rocket entity metadata index holding the id of the entity it is boosting. */
    private static final int ATTACHED_ENTITY_METADATA = 9;

    enum Observation { NONE, CONFIRMED, UNCONFIRMED }

    private boolean pending;
    private int age;
    private int fired;

    /** Call when a rocket use went through. */
    void fired() {
        pending = true;
        age = 0;
        fired++;
    }

    /** Call once per tick. */
    Observation tick() {
        if (!pending) return Observation.NONE;
        if (isAttached()) {
            pending = false;
            return Observation.CONFIRMED;
        }
        if (++age >= CONFIRM_TIMEOUT_TICKS) {
            pending = false;
            return Observation.UNCONFIRMED;
        }
        return Observation.NONE;
    }

    /** A rocket is boosting us, or one was just fired and hasn't been seen yet. */
    boolean inFlight() {
        return pending || isAttached();
    }

    boolean pending() {
        return pending;
    }

    int firedCount() {
        return fired;
    }

    void clearPending() {
        pending = false;
        age = 0;
    }

    void reset() {
        clearPending();
        fired = 0;
    }

    private static boolean isAttached() {
        int selfId = CACHE.getPlayerCache().getEntityId();
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.isRemoved() || entity.getEntityType() != EntityType.FIREWORK_ROCKET) continue;
            OptionalInt attached = entity.getMetadataValue(ATTACHED_ENTITY_METADATA, MetadataTypes.OPTIONAL_VARINT, OptionalInt.class);
            if (attached != null && attached.isPresent() && attached.getAsInt() == selfId) return true;
        }
        return false;
    }
}
