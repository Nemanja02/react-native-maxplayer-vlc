package com.yuanzhou.vlc.pip;

import android.content.pm.ActivityInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-module flags coordinating PiP transitions between the host activity,
 * the JS-facing PiP module, and the libvlc render surface.
 *
 * - {@link #freezeOrientationRequests}: when true, MainActivity's overridden
 *   setRequestedOrientation rejects any portrait/unspecified request. This
 *   stops orientation libraries (e.g. react-native-orientation-director) from
 *   flipping the activity back to portrait during the PiP critical section,
 *   which was the source of recurring stretch — surface ends up portrait at
 *   the moment the OS captures PiP geometry.
 *
 * - {@link #pipTransitionPending}: when true, ReactVlcPlayerView's
 *   surfaceChanged callback ignores incoming portrait dims and keeps using
 *   the last known landscape geometry for libvlc. Cleared once we've seen a
 *   landscape surfaceChanged after PiP entry, or on PiP exit.
 */
public final class PipGuard {
    public static final AtomicBoolean freezeOrientationRequests = new AtomicBoolean(false);
    public static final AtomicBoolean pipTransitionPending = new AtomicBoolean(false);

    private PipGuard() {}

    /**
     * True for any orientation request that effectively unlocks back to
     * portrait. Includes UNSPECIFIED because most orientation libraries
     * "unlock" by passing UNSPECIFIED, and on portrait-default activities
     * that resolves to portrait.
     */
    public static boolean isPortraitishOrReset(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR;
    }
}
