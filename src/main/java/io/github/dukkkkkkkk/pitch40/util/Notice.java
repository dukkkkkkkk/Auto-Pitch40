package io.github.dukkkkkkkk.pitch40.util;

/**
 * A message that should be shown once until the condition behind it clears.
 */
public final class Notice {
    private boolean shown = false;

    /** Returns true the first time it is called after a {@link #clear()}. */
    public boolean show() {
        if (shown) return false;
        shown = true;
        return true;
    }

    public void clear() {
        shown = false;
    }
}
