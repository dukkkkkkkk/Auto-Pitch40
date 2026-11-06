package io.github.dukkkkkkkk.pitch40.module;

import io.github.dukkkkkkkk.pitch40.Pitch40Config.RocketMode;

/**
 * Follows the climb/dive cycle and decides when the next climb should spend a rocket.
 *
 * <p>After each climb tops out, a rocket is wanted if the peak fell more than {@code tolerance}
 * under the target height (KEEP and BOOST), or if it is a boost turn (BOOST, every Nth climb).
 * The target is the upper bound, or with no ceiling {@code climbGain} blocks above the highest peak so far,
 * so each climb aims higher than the last.
 */
final class GlideCycle {
    static final double VELOCITY_DEADBAND = 0.02;
    /** Pass as the ceiling to measure climbs against the best peak instead of a fixed height. */
    static final double NO_CEILING = Double.NaN;

    private boolean started;
    private boolean goingUp;
    private boolean toppedOut;
    private int climbs;
    private double peakY;
    private double lastPeakY = Double.NaN;
    private double bestPeakY = Double.NaN;

    boolean needsRocket;
    boolean rocketForBoost;

    void reset() {
        started = false;
        goingUp = false;
        toppedOut = false;
        climbs = 0;
        peakY = 0.0;
        lastPeakY = Double.NaN;
        bestPeakY = Double.NaN;
        needsRocket = false;
        rocketForBoost = false;
    }

    boolean goingUp() {
        return goingUp;
    }

    /** A climb reached its highest point on the last sample. */
    boolean toppedOut() {
        return toppedOut;
    }

    /** Y of the most recent climb's peak, or NaN before the first one. */
    double lastPeakY() {
        return lastPeakY;
    }

    /** Highest peak this flight, or NaN before the first one. */
    double bestPeakY() {
        return bestPeakY;
    }

    /**
     * Feed one tick of flight.
     *
     * @param ceiling   the upper bound, or {@link #NO_CEILING}
     * @param floor     the lower bound: a climb that peaks under it always counts as short
     * @param climbGain no ceiling only: how far above the best peak each climb should reach
     * @return true when a climb just topped out short of the target height
     */
    boolean sample(double y, double velocityY, RocketMode mode, double ceiling, double floor, double climbGain,
                   double tolerance, int climbsPerRocket) {
        toppedOut = false;
        if (!Double.isFinite(y) || !Double.isFinite(velocityY) || Double.isInfinite(ceiling)
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

        if (!wasGoingUp || goingUp) return false;

        toppedOut = true;
        climbs++;
        // with no ceiling, aim climbGain above the best peak before this one; the first climb sets it
        double target = Double.isNaN(ceiling) ? bestPeakY + Math.max(0.0, climbGain) : ceiling;
        // with a ceiling, only spend a rocket when climbs are actually getting lower, or peaking under the band
        // altogether; climbs that are short but holding inside the band recover by themselves. Under the band
        // they don't: they can level off there for good, so those always get a rocket
        boolean declining = !Double.isNaN(lastPeakY) && peakY < lastPeakY - 1.0;
        boolean underBand = peakY < floor;
        boolean mustDecline = !Double.isNaN(ceiling);
        lastPeakY = peakY;
        bestPeakY = Double.isNaN(bestPeakY) ? peakY : Math.max(bestPeakY, peakY);

        boolean shortApex = mode != RocketMode.OFF && !Double.isNaN(target) && target - peakY > tolerance
            && (!mustDecline || declining || underBand);
        boolean boostTurn = mode == RocketMode.BOOST && climbs % climbsPerRocket == 0;
        if (shortApex || boostTurn) {
            needsRocket = true;
            rocketForBoost = boostTurn;
        }
        return shortApex;
    }
}
