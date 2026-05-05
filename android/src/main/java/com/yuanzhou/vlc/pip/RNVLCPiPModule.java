package com.yuanzhou.vlc.pip;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Picture-in-Picture bridge for libvlc-rendered video.
 *
 * Android PiP is an Activity-level feature: enterPictureInPictureMode() shrinks
 * the whole MainActivity into the system PiP window. The libvlc TextureView
 * keeps rendering inside it without any per-player wiring. This module exposes
 * the Activity API to JS plus a listener for mode changes and PiP overlay
 * RemoteAction (Play/Pause) buttons.
 *
 * Mode-change events come from MainActivity broadcasting ACTION_MODE_CHANGED.
 * RemoteAction clicks come from PendingIntents we create here and dispatch
 * through ACTION_CONTROL — JS receives them as 'RNVLCPiPAction' events.
 */
public class RNVLCPiPModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

    public static final String NAME = "RNVLCPiP";
    public static final String ACTION_MODE_CHANGED = "com.yuanzhou.vlc.pip.MODE_CHANGED";
    public static final String ACTION_CONTROL = "com.yuanzhou.vlc.pip.CONTROL";
    public static final String EXTRA_IS_IN_PIP = "isInPictureInPictureMode";
    public static final String EXTRA_CONTROL = "control";

    private static final String EVENT_MODE_CHANGED = "RNVLCPiPModeChanged";
    private static final String EVENT_ACTION = "RNVLCPiPAction";

    private static final int REQ_PLAY_PAUSE = 1001;

    private final ReactApplicationContext reactContext;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver pipReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d("RNVLCPiP", "Broadcast received: " + action);
            if (ACTION_MODE_CHANGED.equals(action)) {
                boolean isInPip = intent.getBooleanExtra(EXTRA_IS_IN_PIP, false);
                if (!isInPip) {
                    // PiP closed — could be either:
                    // (a) user expanded back to fullscreen via tap on the
                    //     PiP overlay (activity returns to foreground)
                    // (b) user X'd the PiP window (activity finishes)
                    //
                    // For (a), the orientation library wakes up after the
                    // activity foregrounds and may try to reset orientation
                    // (e.g., back to portrait) before the JS-side fullscreen
                    // logic re-asserts landscape. We hold the orientation
                    // guard for an additional 1500ms — long enough to cover
                    // the post-expand transition while short enough to not
                    // interfere with normal user-driven orientation changes
                    // (e.g., user navigating away from the player).
                    PipGuard.pipTransitionPending.set(false);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        PipGuard.freezeOrientationRequests.set(false);
                    }, 1500);
                }
                WritableMap params = Arguments.createMap();
                params.putBoolean("isInPictureInPictureMode", isInPip);
                emit(EVENT_MODE_CHANGED, params);
            } else if (ACTION_CONTROL.equals(action)) {
                String control = intent.getStringExtra(EXTRA_CONTROL);
                Log.d("RNVLCPiP", "Control received: " + control);
                WritableMap params = Arguments.createMap();
                params.putString("action", control != null ? control : "");
                emit(EVENT_ACTION, params);
            }
        }
    };

    public RNVLCPiPModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        context.addLifecycleEventListener(this);
        registerReceiver();
    }

    @NonNull
    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void isSupported(Promise promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            promise.resolve(false);
            return;
        }
        Activity activity = getCurrentActivity();
        Context ctx = activity != null ? activity : reactContext;
        boolean has = ctx.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        promise.resolve(has);
    }

    @ReactMethod
    public void enter(ReadableMap options, Promise promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            promise.reject("E_UNSUPPORTED", "PiP requires Android 8.0 (API 26)");
            return;
        }
        Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "No current activity");
            return;
        }
        // Single-activity inline PiP: MainActivity itself enters PiP mode,
        // its existing libvlc SurfaceView keeps rendering (no cold-restart,
        // no second VLC instance). PipGuard freezes orientation library
        // resets so the surface stays landscape across the transition.
        PipGuard.freezeOrientationRequests.set(true);
        PipGuard.pipTransitionPending.set(true);

        Configuration cfg = activity.getResources().getConfiguration();
        boolean alreadyLandscape =
                cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;

        if (alreadyLandscape) {
            // Activity is already landscape (RN root layout matches PiP overlay
            // aspect) — enter PiP immediately.
            tryEnterPip(activity, options, promise);
        } else {
            // Activity is portrait. Fullscreen player is only visually
            // landscape via orientation library's animated transform — actual
            // activity orientation stays portrait, RN root is 710x1280, and
            // the SurfaceView hardware overlay aniso-stretches its 16:9 buffer
            // onto portrait view bounds → vertical stretch in PiP window.
            //
            // Force real landscape rotation, then wait for it to take effect
            // before entering PiP. Without the delay, enterPictureInPictureMode
            // captures activity geometry while still portrait and the rotation
            // request is discarded by the time PiP is active (PiP doesn't
            // honor orientation changes).
            try {
                activity.setRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } catch (Exception ignored) {
            }
            // 400ms is enough for most devices to complete a rotation
            // (configChanges="orientation" means no full activity recreate,
            // just a layout pass). Tested on Pixel and Samsung devices.
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> tryEnterPip(activity, options, promise), 400);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void tryEnterPip(Activity activity, ReadableMap options, Promise promise) {
        // Activity may have been destroyed in the postDelayed window between
        // enter() and tryEnterPip (user navigates away, screen rotates and
        // the rotation actually recreates instead of going through onConfig
        // ChangeChanged, system kills app, etc). Bail with a clean reject
        // instead of crashing on enterPictureInPictureMode against a dead
        // activity.
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())) {
            PipGuard.freezeOrientationRequests.set(false);
            PipGuard.pipTransitionPending.set(false);
            promise.reject("E_NO_ACTIVITY",
                    "Activity destroyed before PiP entry could be attempted");
            return;
        }
        try {
            // Override the JS-passed aspect ratio to match the activity's
            // CURRENT orientation. If the activity is portrait but PiP is
            // 16:9, Android iso-scales the portrait window into the
            // landscape overlay with letterbox, plus the SurfaceView's
            // hardware overlay aniso-stretches the 16:9 buffer onto the
            // portrait view bounds — net effect is vertical stretch.
            // Matching PiP aspect to activity makes scaling 1:1 (no
            // letterbox, no aniso stretch). 16:9 video gets letterboxed
            // inside a portrait PiP overlay — visible black bars top/
            // bottom but the video itself is correctly proportioned.
            PictureInPictureParams.Builder b = newBuilder(options, activity);
            Configuration cfg = activity.getResources().getConfiguration();
            int wdp = cfg.screenWidthDp;
            int hdp = cfg.screenHeightDp;
            if (wdp > 0 && hdp > 0) {
                Rational ar = clampAspect(new Rational(wdp, hdp));
                b.setAspectRatio(ar);
                Log.d("RNVLCPiP", "tryEnterPip aspect=" + ar.getNumerator()
                        + ":" + ar.getDenominator()
                        + " (cfg " + wdp + "x" + hdp + " dp)");
            }
            PictureInPictureParams params = b.build();
            boolean entered = activity.enterPictureInPictureMode(params);
            if (!entered) {
                PipGuard.freezeOrientationRequests.set(false);
                PipGuard.pipTransitionPending.set(false);
            }
            promise.resolve(entered);
        } catch (IllegalStateException e) {
            PipGuard.freezeOrientationRequests.set(false);
            PipGuard.pipTransitionPending.set(false);
            promise.reject("E_PIP_FAILED", e.getMessage(), e);
        } catch (Exception e) {
            PipGuard.freezeOrientationRequests.set(false);
            PipGuard.pipTransitionPending.set(false);
            promise.reject("E_PIP_FAILED", e.getMessage(), e);
        }
    }

    @ReactMethod
    public void setParams(ReadableMap options, Promise promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            promise.reject("E_UNSUPPORTED", "PiP requires Android 8.0 (API 26)");
            return;
        }
        Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "No current activity");
            return;
        }
        try {
            activity.setPictureInPictureParams(buildParams(options, activity));
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("E_PIP_FAILED", e.getMessage(), e);
        }
    }

    /**
     * API 31+ only: when enabled, the activity auto-enters PiP on user-leave
     * (home button, recents) without needing onUserLeaveHint plumbing.
     * Pre-31 callers must implement onUserLeaveHint themselves.
     */
    @ReactMethod
    public void setAutoEnterEnabled(boolean enabled, ReadableMap options, Promise promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            promise.resolve(false);
            return;
        }
        Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("E_NO_ACTIVITY", "No current activity");
            return;
        }
        try {
            PictureInPictureParams.Builder b = newBuilder(options, activity);
            b.setAutoEnterEnabled(enabled);
            activity.setPictureInPictureParams(b.build());
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("E_PIP_FAILED", e.getMessage(), e);
        }
    }

    // RN requires these for NativeEventEmitter to work; bodies can be empty.
    @ReactMethod
    public void addListener(String eventName) {}

    @ReactMethod
    public void removeListeners(int count) {}

    private PictureInPictureParams buildParams(ReadableMap options, Activity activity) {
        return newBuilder(options, activity).build();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private PictureInPictureParams.Builder newBuilder(ReadableMap options, Activity activity) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        if (options == null) return builder;

        // Aspect ratio (clamped to OS-allowed [1:2.39, 2.39:1] — system throws otherwise)
        if (options.hasKey("aspectRatioWidth") && options.hasKey("aspectRatioHeight")) {
            int w = options.getInt("aspectRatioWidth");
            int h = options.getInt("aspectRatioHeight");
            if (w > 0 && h > 0) {
                builder.setAspectRatio(clampAspect(new Rational(w, h)));
            }
        }

        // sourceRect lets the OS animate the PiP transition from the player's
        // on-screen rect instead of a generic shrink. Coords are screen px.
        if (options.hasKey("sourceRect")) {
            ReadableMap r = options.getMap("sourceRect");
            if (r != null
                    && r.hasKey("x") && r.hasKey("y")
                    && r.hasKey("width") && r.hasKey("height")) {
                int x = r.getInt("x");
                int y = r.getInt("y");
                int w = r.getInt("width");
                int h = r.getInt("height");
                builder.setSourceRectHint(new Rect(x, y, x + w, y + h));
            }
        }

        // RemoteAction (Play/Pause overlay button). `playing` is the CURRENT
        // playback state — the icon shown is the OPPOSITE (you tap pause when
        // playing, tap play when paused).
        if (options.hasKey("playing")) {
            boolean playing = options.getBoolean("playing");
            List<RemoteAction> actions = new ArrayList<>();
            actions.add(makePlayPauseAction(playing, activity));
            builder.setActions(actions);
        }
        return builder;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private RemoteAction makePlayPauseAction(boolean isCurrentlyPlaying, Activity activity) {
        Context appCtx = activity.getApplicationContext();
        Intent intent = new Intent(ACTION_CONTROL)
                .putExtra(EXTRA_CONTROL, "playPause")
                .setPackage(appCtx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(appCtx, REQ_PLAY_PAUSE, intent, flags);
        Icon icon = Icon.createWithResource(appCtx,
                isCurrentlyPlaying
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
        String label = isCurrentlyPlaying ? "Pause" : "Play";
        return new RemoteAction(icon, label, label, pi);
    }

    private Rational clampAspect(Rational r) {
        double v = r.doubleValue();
        if (v < 1.0 / 2.39) return new Rational(100, 239);
        if (v > 2.39) return new Rational(239, 100);
        return r;
    }

    private void emit(String name, WritableMap params) {
        if (!reactContext.hasActiveCatalystInstance()) return;
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(name, params);
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_MODE_CHANGED);
        filter.addAction(ACTION_CONTROL);
        // API 33+ requires explicit exported flag. PiP broadcasts are internal-only.
        ContextCompat.registerReceiver(
                reactContext,
                pipReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try {
            reactContext.unregisterReceiver(pipReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }

    @Override
    public void onHostResume() {
        registerReceiver();
    }

    @Override
    public void onHostPause() {}

    @Override
    public void onHostDestroy() {
        unregisterReceiver();
    }

    @Override
    public void invalidate() {
        unregisterReceiver();
        super.invalidate();
    }
}
