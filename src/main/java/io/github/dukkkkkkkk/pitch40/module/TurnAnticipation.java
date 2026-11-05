package io.github.dukkkkkkkk.pitch40.module;

/**
 * Learns how far the bot overshoots each turn and starts the next turn that much earlier, so the swing stays
 * between the bounds.
 *
 * <p>Swinging the pitch from -40 to +40 takes a second at the default rotation speed. At the upper bound the bot
 * is still climbing fast, so it keeps rising for most of that second (around 24 blocks with 360-420 bounds),
 * then dives all the way down: about 90 blocks instead of 60. At the bottom it overshoots a little too.
 *
 * <p>After each turn it measures the actual peak or low against the target and moves the turn point by half the
 * error. It settles in two or three cycles and follows changes in speed or settings by itself.
 */
final class TurnAnticipation {
    /** Fraction of each overshoot to correct per cycle. Lower is smoother, higher reacts faster. */
    private static final double GAIN = 0.5;

    private double topLead = 0.0;
    private double bottomLead = 0.0;
    private double prevVelocityY = 0.0;
    private boolean havePrev = false;
    /** The current climb was turned at the upper bound, so its peak tells us the top overshoot. */
    private boolean awaitingPeak = false;
    /** Y the current dive is meant to bottom out at, or NaN when not diving. */
    private double bottomTarget = Double.NaN;

    /** Blocks under the upper bound to start pitching down. */
    double topLead() {
        return topLead;
    }

    /** Blocks above the dive's target to start pulling up. */
    double bottomLead() {
        return bottomLead;
    }

    /** Call every diving tick with the Y the dive should bottom out at. */
    void diving(double target) {
        bottomTarget = target;
    }

    /** Call when a climb is turned into a dive at the upper bound. */
    void turnedAtCeiling() {
        awaitingPeak = true;
    }

    /**
     * Call once per flight tick. Learns from the turning points: the low after a dive and the peak after a
     * turn at the upper bound.
     *
     * @param maxLead the most it may turn early, so a bad reading can't collapse the swing
     */
    void sample(double y, double velocityY, double upperBound, double maxLead) {
        if (havePrev) {
            boolean low = prevVelocityY < 0 && velocityY >= 0;
            boolean peak = prevVelocityY > 0 && velocityY <= 0;
            if (low && !Double.isNaN(bottomTarget)) {
                bottomLead = clamp(bottomLead + GAIN * (bottomTarget - y), maxLead);
                bottomTarget = Double.NaN;
            }
            if (peak && awaitingPeak) {
                topLead = clamp(topLead + GAIN * (y - upperBound), maxLead);
                awaitingPeak = false;
            }
        }
        prevVelocityY = velocityY;
        havePrev = true;
    }

    void reset() {
        topLead = 0.0;
        bottomLead = 0.0;
        havePrev = false;
        awaitingPeak = false;
        bottomTarget = Double.NaN;
    }

    private static double clamp(double lead, double maxLead) {
        return Math.max(0.0, Math.min(Math.max(0.0, maxLead), lead));
    }
}
