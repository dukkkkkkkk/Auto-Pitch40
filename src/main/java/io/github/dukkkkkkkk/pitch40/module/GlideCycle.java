package io.github.dukkkkkkkk.pitch40.module;

import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;

/**
 * Follows the climb/dive cycle and decides when the next climb should spend a rocket.
 *
 * <p>After each climb tops out, a rocket is wanted if the peak fell more than {@code tolerance}
 * under the upper bound (KEEP and BOOST), or if it is a boost turn (BOOST, every Nth climb).
 */
final class GlideCycle {
    static final double VELOCITY_DEADBAND = 0.02;

    private boolean started;
    private boolean goingUp;
    private int climbs;
    private double peakY;

    boolean needsRocket;
    boolean rocketForBoost;

    void reset() {
        started = false;
        goingUp = false;
        climbs = 0;
        peakY = 0.0;
        needsRocket = false;
        rocketForBoost = false;
    }

    boolean goingUp() {
        return goingUp;
    }

    /** Feed one tick of flight. Returns true when a climb just topped out short of the upper bound. */
    boolean sample(double y, double velocityY, RocketMode mode, double upper, double tolerance, int climbsPerRocket) {
        if (!Double.isFinite(y) || !Double.isFinite(velocityY) || !Double.isFinite(upper)
            || !Double.isFinite(tolerance) || tolerance < 0.0 || climbsPerRocket < 1) {
            reset();
            return false;
        }
        if (!started) {
            started = true;
            peakY = y;
            needsRocket = mode == RocketMode.BOOST;
            rocketForBoost = needsRocket;
        }

        boolean wasGoingUp = goingUp;
        if (velocityY > VELOCITY_DEADBAND) {
            goingUp = true;
        } else if (velocityY < -VELOCITY_DEADBAND) {
            goingUp = false;
        }

        if (!wasGoingUp && goingUp) peakY = y;
        if (wasGoingUp || goingUp) peakY = Math.max(peakY, y);

        boolean toppedOut = wasGoingUp && !goingUp;
        if (!toppedOut) return false;

        climbs++;
        boolean shortApex = mode != RocketMode.OFF && upper - peakY > tolerance;
        boolean boostTurn = mode == RocketMode.BOOST && climbs % climbsPerRocket == 0;
        if (shortApex || boostTurn) {
            needsRocket = true;
            rocketForBoost = boostTurn;
        }
        return shortApex;
    }
}
