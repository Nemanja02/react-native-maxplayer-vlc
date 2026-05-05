package com.yuanzhou.vlc.vlcplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.core.content.ContextCompat;

import com.yuanzhou.vlc.pip.PipGuard;
import com.yuanzhou.vlc.pip.RNVLCPiPModule;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;

import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;





@SuppressLint("ViewConstructor")
class ReactVlcPlayerView extends SurfaceView implements
        LifecycleEventListener,
        SurfaceHolder.Callback,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "ReactVlcPlayerView";
    private final String tag = "ReactVlcPlayerView";

    private final VideoEventEmitter eventEmitter;
    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private boolean isSurfaceViewDestory;
    private boolean isSurfaceCreated = false;
    private String src;
    private boolean netStrTag;
    private ReadableMap srcMap;
    private int mVideoHeight = 0;
    private int mVideoWidth = 0;
    private int mVideoVisibleHeight = 0;
    private int mVideoVisibleWidth = 0;
    private int mSarNum = 0;
    private int mSarDen = 0;
    private int screenWidth = 0;
    private int screenHeight = 0;

    private boolean isPaused = true;
    private boolean isHostPaused = false;
    private int preVolume = 200;
    private String aspectRatio = null;

    private float mProgressUpdateInterval = 150;
    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;

    // Retry / reconnect state — mirrors cordova VLCPlayer.java
    private static final int RECONNECT_INITIAL_DELAY_MS = 2000;
    private static final int RECONNECT_MAX_DELAY_MS = 8000; // cap at 8s per user preference
    private static final int DEFAULT_MAX_RECONNECTS = 10;
    private static final int STALL_TIMEOUT_MS = 12000;
    private static final int STALL_CHECK_INTERVAL_MS = 5000;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Handler stallHandler = new Handler(Looper.getMainLooper());
    private Runnable stallCheckRunnable;
    private Runnable pendingReconnectRunnable;
    private long lastTimeChangedMs = 0;
    private int reconnectAttempt = 0;
    private boolean autoReconnect = false;
    private int maxReconnectAttempts = DEFAULT_MAX_RECONNECTS;
    private boolean isLiveStream = false;
    private String mediaType = null;
    // When true, do not pause the player when the host activity is backgrounded
    // (used for radio streams). Audio keeps playing while the activity is in
    // STOP state, which works as long as the OS does not reclaim the process.
    private boolean playInBackground = false;

    private final ThemedReactContext themedReactContext;
    private final AudioManager audioManager;

    // Picture-in-Picture entry forces a window-size resync on libvlc.
    // TextureView commits its new surface dimensions late during the PiP
    // transition, so without this libvlc keeps rendering with the old
    // (full-screen) window size into the now-shrunk surface — visible as
    // the top-left quadrant of the original frame in the PiP window. We
    // listen to RNVLCPiPModule's mode-change broadcast and force a
    // setWindowSize + applyAspectRatio after the OS settles the geometry.
    private BroadcastReceiver pipModeReceiver;
    private boolean pipReceiverRegistered = false;
    private boolean inPipMode = false;
    private int lastSyncedW = 0;
    private int lastSyncedH = 0;
    // OS-reported PiP window pixel dims, populated from MainActivity's
    // MODE_CHANGED broadcast. These are the AUTHORITATIVE PiP overlay size;
    // SurfaceView.getWidth/Height during PiP returns the activity's pre-PiP
    // fullscreen dims (RN UIManager doesn't relayout for PiP), which would
    // give wrong values to setWindowSize/setAspectRatio.
    private int pipWidthPx = 0;
    private int pipHeightPx = 0;
    // Saved LayoutParams width/height to restore on PiP exit. We override
    // them on entry to force the SurfaceView's view bounds to match the
    // OS-reported PiP overlay dims (RN's JS layout otherwise shrinks the
    // player view to a portrait shape inside a landscape activity, causing
    // hardware-overlay aniso stretch).
    private int savedLayoutWidth = 0;
    private int savedLayoutHeight = 0;
    // Last landscape surface dimensions seen — used to keep VLC rendering
    // at landscape geometry even if the OS briefly reports a portrait surface
    // during the PiP transition (which would otherwise stretch the video).
    private int lastLandscapeW = 0;
    private int lastLandscapeH = 0;
    private ViewTreeObserver.OnGlobalLayoutListener pipLayoutListener;

    public ReactVlcPlayerView(ThemedReactContext context) {
        super(context);
        this.eventEmitter = new VideoEventEmitter(context);
        this.themedReactContext = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        screenWidth = dm.widthPixels;
        // Android 14+: keep the underlying Surface alive while attached even
        // if visibility briefly fluctuates (RN/Fabric can toggle visibility
        // mid-PiP-transition, which would otherwise tear down the surface
        // and force libvlc to re-init at a bad moment).
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
            } catch (Throwable ignored) {
            }
        }
        // SurfaceHolder.Callback — fires on surface creation/resize/destroy.
        getHolder().addCallback(this);
        // Place SurfaceView's hardware-overlay layer above the activity's
        // window background but BELOW any sibling views rendered after this
        // in the React tree (PlayerControls, loader, PiP button). Without
        // this, those overlays would render *under* the SurfaceView and be
        // invisible. ZOrderOnTop would put SurfaceView above everything,
        // which we don't want.
        setZOrderMediaOverlay(true);

        this.addOnLayoutChangeListener(onLayoutChangeListener);
        registerPipReceiver();
    }

    private void registerPipReceiver() {
        if (pipReceiverRegistered) return;
        pipModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (RNVLCPiPModule.ACTION_MODE_CHANGED.equals(action)) {
                    inPipMode = intent.getBooleanExtra(
                            RNVLCPiPModule.EXTRA_IS_IN_PIP, false);
                    pipWidthPx = intent.getIntExtra("widthPx", 0);
                    pipHeightPx = intent.getIntExtra("heightPx", 0);
                    Log.d("RNVLCPiP", "MODE_CHANGED inPip=" + inPipMode
                            + " pipDims=" + pipWidthPx + "x" + pipHeightPx);
                    if (inPipMode && pipWidthPx > 0 && pipHeightPx > 0) {
                        // Two-pronged fix to match the rendered video aspect
                        // to the PiP overlay's actual aspect:
                        //
                        // 1) setFixedSize forces the surface BUFFER to the
                        //    PiP overlay dims. VLC's "fill" aspect computes
                        //    from these → setAspectRatio matches the surface
                        //    so vout doesn't reconfigure → no black screen.
                        //
                        // 2) setLayoutParams forces the SurfaceView's view
                        //    BOUNDS to the same PiP dims. Without this, RN's
                        //    JS layout shrinks the player view to ~710x1280
                        //    (portrait shape inside a landscape activity) so
                        //    the SurfaceView hardware overlay aniso-stretches
                        //    its 711x399 16:9 buffer onto a 710x1280 portrait
                        //    rect → vertical stretch in the PiP overlay.
                        //    Forcing the LayoutParams to match the buffer
                        //    makes overlay-to-view scaling 1:1 (no aniso).
                        try {
                            getHolder().setFixedSize(pipWidthPx, pipHeightPx);
                        } catch (Exception e) {
                            Log.e("RNVLCPiP", "setFixedSize failed", e);
                        }
                        // Apply view-level scale transform to compensate for
                        // RN-driven view bounds mismatch with buffer aspect.
                        // Re-applied via onLayoutChangeListener and the
                        // handlePipPlayPause path on subsequent layout
                        // changes.
                        applySurfaceScalingMode(2);
                        try {
                            ViewGroup.LayoutParams lp = getLayoutParams();
                            if (lp != null) {
                                savedLayoutWidth = lp.width;
                                savedLayoutHeight = lp.height;
                                // MATCH_PARENT: fill whatever the parent
                                // is. JS layout typically constrains the
                                // player to a portrait-shaped wrapper
                                // (~710x1280) inside the landscape activity
                                // during the PiP optimistic flip; forcing
                                // MATCH_PARENT bypasses that constraint and
                                // lets the SurfaceView span the full parent
                                // (or activity) so its hardware overlay
                                // renders at the same shape as the activity
                                // → after iso scale to PiP, it fills the
                                // overlay 1:1 with the buffer aspect.
                                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                                setLayoutParams(lp);
                            }
                        } catch (Exception e) {
                            Log.e("RNVLCPiP", "setLayoutParams failed", e);
                        }
                    } else if (!inPipMode) {
                        // Release the fixed size so the surface returns to
                        // tracking the layout-driven view bounds (fullscreen
                        // for the inline player).
                        try {
                            getHolder().setSizeFromLayout();
                        } catch (Exception e) {
                            Log.e("RNVLCPiP", "setSizeFromLayout failed", e);
                        }
                        // Restore identity transform.
                        applySurfaceScalingMode(1);
                        try {
                            ViewGroup.LayoutParams lp = getLayoutParams();
                            if (lp != null && savedLayoutWidth != 0) {
                                lp.width = savedLayoutWidth;
                                lp.height = savedLayoutHeight;
                                setLayoutParams(lp);
                            }
                        } catch (Exception e) {
                            Log.e("RNVLCPiP", "restore LayoutParams failed", e);
                        }
                        pipWidthPx = 0;
                        pipHeightPx = 0;
                    }
                } else if (RNVLCPiPModule.ACTION_CONTROL.equals(action)) {
                    // PiP overlay Play/Pause tap. We toggle the VLC player
                    // directly here instead of routing through JS — RN's
                    // UIManager defers view prop updates while the activity
                    // is paused (which it is during PiP), so the `paused`
                    // prop change from JS doesn't actually reach
                    // setPausedModifier until the activity resumes. Direct
                    // native toggle bypasses that. JS still receives an
                    // event from RNVLCPiPModule and updates its mirrored
                    // paused state for icon-sync purposes.
                    String control = intent.getStringExtra(
                            RNVLCPiPModule.EXTRA_CONTROL);
                    if ("playPause".equals(control)) {
                        handlePipPlayPause();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(RNVLCPiPModule.ACTION_MODE_CHANGED);
        filter.addAction(RNVLCPiPModule.ACTION_CONTROL);
        ContextCompat.registerReceiver(
                getContext().getApplicationContext(),
                pipModeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        pipReceiverRegistered = true;
    }

    /**
     * Apply a view-level scale transform to make the SurfaceView's visible
     * rect match the buffer's aspect ratio (PiP overlay aspect). Modern
     * Android (API 28+) propagates View transforms onto SurfaceView's
     * hardware overlay positioning, so this effectively shrinks the
     * overlay rect to the correct aspect rather than aniso-stretching the
     * buffer to fill the view bounds.
     *
     * @param mode  2 to apply (PiP enter), 1 to reset (PiP exit)
     */
    private void applySurfaceScalingMode(int mode) {
        if (mode == 1) {
            // Reset to identity
            setScaleX(1f);
            setScaleY(1f);
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight() / 2f);
            return;
        }
        // mode == 2: apply aspect-preserving scale
        if (pipWidthPx <= 0 || pipHeightPx <= 0) return;
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float bufferAspect = (float) pipWidthPx / pipHeightPx;
        float viewAspect = (float) viewW / viewH;
        // Pivot at top-left so the visible region anchors there (better
        // than centered for PiP overlay because the activity's iso scale
        // to PiP fills from top-left).
        setPivotX(0);
        setPivotY(0);
        if (Math.abs(viewAspect - bufferAspect) < 0.01f) {
            // Aspects match — no transform needed
            setScaleX(1f);
            setScaleY(1f);
        } else if (viewAspect < bufferAspect) {
            // View is taller than buffer aspect (e.g., portrait view with
            // 16:9 buffer) — shrink height so visible area is buffer-aspect
            setScaleX(1f);
            setScaleY(viewAspect / bufferAspect);
        } else {
            // View is wider than buffer aspect — shrink width
            setScaleX(bufferAspect / viewAspect);
            setScaleY(1f);
        }
    }

    private void unregisterPipReceiver() {
        if (!pipReceiverRegistered || pipModeReceiver == null) return;
        try {
            getContext().getApplicationContext().unregisterReceiver(pipModeReceiver);
        } catch (Exception ignored) {
        }
        pipReceiverRegistered = false;
        pipModeReceiver = null;
    }

    private void enablePipLayoutListener() {
        if (pipLayoutListener != null) return;
        pipLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (!inPipMode) return;
                int[] dims = resolvePipDimensions();
                int w = dims[0];
                int h = dims[1];
                if (w <= 0 || h <= 0) return;
                // Debounce: only resync when the dimensions actually change.
                // Without this, this listener fires on every layout pass and
                // we'd thrash setWindowSize/applyAspectRatio at draw rate.
                if (w == lastSyncedW && h == lastSyncedH) return;
                lastSyncedW = w;
                lastSyncedH = h;
                forceVlcResyncRunnable.run();
            }
        };
        getViewTreeObserver().addOnGlobalLayoutListener(pipLayoutListener);
    }

    private void disablePipLayoutListener() {
        if (pipLayoutListener == null) return;
        try {
            getViewTreeObserver().removeOnGlobalLayoutListener(pipLayoutListener);
        } catch (Exception ignored) {
        }
        pipLayoutListener = null;
    }

    /**
     * Micro translationX nudge that forces a SurfaceControl/BLAST transaction
     * flush without triggering measure/layout. This mimics what user-dragging
     * the PiP window does naturally — kicks the system into emitting
     * surfaceChanged with the actual PiP window dims (instead of the stale
     * fullscreen 2856x1280 it shipped at PiP entry).
     */
    private void scheduleSurfaceNudge(final long delayMs) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                final float originalTx = getTranslationX();
                Log.d("RNVLCPiP", "Surface nudge fire @" + delayMs
                        + "ms inPip=" + inPipMode
                        + " size=" + getWidth() + "x" + getHeight());
                setTranslationX(originalTx + 0.5f);
                postOnAnimation(new Runnable() {
                    @Override
                    public void run() {
                        setTranslationX(originalTx);
                        postInvalidateOnAnimation();
                    }
                });
            }
        }, delayMs);
    }

    private void handlePipPlayPause() {
        if (mMediaPlayer == null) return;
        try {
            if (mMediaPlayer.isPlaying()) {
                isPaused = true;
                mMediaPlayer.pause();
            } else {
                isPaused = false;
                requestAudioFocusForPlayback();
                mMediaPlayer.play();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // VLC pause causes a brief layout/redraw pass that can reset our
        // setScaleX/Y. Re-apply immediately AND after a short delay to
        // catch any deferred layout pass triggered by the play/pause
        // state change (RN render → onLayoutChange → potential clobber).
        applySurfaceScalingMode(2);
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (inPipMode) {
                    applySurfaceScalingMode(2);
                }
            }
        }, 100);
    }

    private int[] resolvePipDimensions() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || isLikelyFullScreen(w, h)) {
            Activity activity = themedReactContext.getCurrentActivity();
            if (activity != null) {
                int dw = activity.getWindow().getDecorView().getWidth();
                int dh = activity.getWindow().getDecorView().getHeight();
                if (dw > 0 && dh > 0) {
                    w = dw;
                    h = dh;
                }
            }
        }
        return new int[] { w, h };
    }

    private final Runnable forceVlcResyncRunnable = new Runnable() {
        @Override
        public void run() {
            // VLC-only resync: push current dims into libvlc, no Android
            // view manipulations (requestLayout/invalidate trigger an
            // orientation flip that flips surface to portrait, which then
            // makes 'fill' aspect stretch the video).
            //
            // During PiP: use OS-reported PiP window dims (from MainActivity
            // broadcast). SurfaceView's getWidth/Height returns activity
            // fullscreen dims (~2856x1280) since RN's view tree doesn't
            // shrink for PiP, but the actual rendered overlay is small
            // (e.g. 480x270 for 16:9 PiP). Feeding the wrong dims to libvlc
            // makes "fill" aspect stretch the video.
            if (mMediaPlayer == null) return;
            int w, h;
            if (inPipMode && pipWidthPx > 0 && pipHeightPx > 0) {
                w = pipWidthPx;
                h = pipHeightPx;
            } else {
                w = getWidth();
                h = getHeight();
            }
            if (w <= 0 || h <= 0) return;
            try {
                mVideoWidth = w;
                mVideoHeight = h;
                IVLCVout vlcOut = mMediaPlayer.getVLCVout();
                vlcOut.setWindowSize(w, h);
                applyAspectRatio();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    // Heuristic: if the reported size matches the original screen dimensions
    // we recorded at construction, we're probably reading stale values (the
    // OS hasn't committed the new PiP layout yet). Fall back to decor view.
    private boolean isLikelyFullScreen(int w, int h) {
        return (w >= screenWidth - 16 && h >= screenHeight - 64)
                || (w >= screenHeight - 64 && h >= screenWidth - 16);
    }


    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPlayback();
    }

    // LifecycleEventListener implementation

    @Override
    public void onHostResume() {
        if (mMediaPlayer != null && isSurfaceViewDestory && isHostPaused) {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (!vlcOut.areViewsAttached()) {
                vlcOut.attachViews(onNewVideoLayoutListener);
                isSurfaceViewDestory = false;
                isPaused = false;
                requestAudioFocusForPlayback();
                mMediaPlayer.play();
            }
        }
    }


    @Override
    public void onHostPause() {
        if (playInBackground) {
            // Keep audio running while app is backgrounded (radio streams).
            return;
        }
        // During the inline PiP transition (single-Activity flow), MainActivity
        // briefly emits onPause as it shrinks into the PiP window. Skip the
        // mediaPlayer.pause() so playback continues seamlessly into the
        // overlay — the isInPictureInPictureMode() check below would also
        // catch this, but the PipGuard flag is set earlier (the moment
        // enterPictureInPictureMode is called) so it's the more reliable gate.
        if (PipGuard.pipTransitionPending.get()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Activity activity = themedReactContext.getCurrentActivity();
                if (activity != null && activity.isInPictureInPictureMode()) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (!isPaused && mMediaPlayer != null) {
            isPaused = true;
            isHostPaused = true;
            mMediaPlayer.pause();
            // this.getHolder().setKeepScreenOn(false);
            WritableMap map = Arguments.createMap();
            map.putString("type", "Paused");
            eventEmitter.onVideoStateChange(map);
        }
        // Log.i("onHostPause", "---------onHostPause------------>");
    }

    public void setPlayInBackground(boolean value) {
        this.playInBackground = value;
    }


    @Override
    public void onHostDestroy() {
        stopPlayback();
    }


    // True if VLC was playing before an audio focus loss interruption.
    // Used to auto-resume on focus regain.
    private boolean wasPlayingBeforeFocusLoss = false;

    // AudioManager.OnAudioFocusChangeListener implementation
    @Override
    public void onAudioFocusChange(int focusChange) {
        if (mMediaPlayer == null || libvlc == null) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Permanent loss (e.g., other app started long playback).
                // Pause and abandon focus — don't auto-resume.
                if (mMediaPlayer.isPlaying()) {
                    wasPlayingBeforeFocusLoss = false;
                    isPaused = true;
                    mMediaPlayer.pause();
                }
                audioManager.abandonAudioFocus(this);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Transient loss (incoming call, alarm, navigation prompt).
                // Pause but remember to auto-resume on regain. We treat
                // CAN_DUCK same as LOSS_TRANSIENT — for video, ducking is
                // less appropriate than pausing.
                if (mMediaPlayer.isPlaying()) {
                    wasPlayingBeforeFocusLoss = true;
                    isPaused = true;
                    mMediaPlayer.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (wasPlayingBeforeFocusLoss && !mMediaPlayer.isPlaying()) {
                    isPaused = false;
                    mMediaPlayer.play();
                }
                wasPlayingBeforeFocusLoss = false;
                break;
        }
    }

    /**
     * Request audio focus before starting playback. Without an active focus
     * request, onAudioFocusChange never fires and we miss interruptions
     * (incoming calls, alarms, etc.) — VLC keeps playing audio in the
     * background unaware that another app or the OS is trying to take
     * over the audio stream.
     */
    private void requestAudioFocusForPlayback() {
        try {
            audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        } catch (Throwable ignored) {
        }
    }

    // private void setProgressUpdateRunnable() {
    //     if (mMediaPlayer != null){
    //         mProgressUpdateRunnable = new Runnable() {
    //             @Override
    //             public void run() {
    //                 if (mMediaPlayer != null && !isPaused) {
    //                     long currentTime = 0;
    //                     long totalLength = 0;
    //                     WritableMap event = Arguments.createMap();
    //                     boolean isPlaying = mMediaPlayer.isPlaying();
    //                     currentTime = mMediaPlayer.getTime();
    //                     float position = mMediaPlayer.getPosition();
    //                     totalLength = mMediaPlayer.getLength();
    //                     WritableMap map = Arguments.createMap();
    //                     map.putBoolean("isPlaying", isPlaying);
    //                     map.putDouble("position", position);
    //                     map.putDouble("currentTime", currentTime);
    //                     map.putDouble("duration", totalLength);
    //                     eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_PROGRESS);
    //                 }
    //                 mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));    
    //             }
    //         };
    //         mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable,0);
    //     }
            
    // }

        private void setProgressUpdateRunnable() {
        if (mMediaPlayer != null && mProgressUpdateInterval > 0){
            new Thread() {
                @Override
                public void run() {
                    super.run();

                    mProgressUpdateRunnable = () -> {
                        if (mMediaPlayer != null && !isPaused) {
                            long currentTime = 0;
                            long totalLength = 0;
                            WritableMap event = Arguments.createMap();
                            boolean isPlaying = mMediaPlayer.isPlaying();
                            currentTime = mMediaPlayer.getTime();
                            float position = mMediaPlayer.getPosition();
                            totalLength = mMediaPlayer.getLength();
                            WritableMap map = Arguments.createMap();
                            map.putBoolean("isPlaying", isPlaying);
                            map.putDouble("position", position);
                            map.putDouble("currentTime", currentTime);
                            map.putDouble("duration", totalLength);
                            eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_PROGRESS);
                        }

                        mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));
                    };

                    mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, 0);
                }
            }.start();
        }
    }




    /*************
     * Events  Listener
     *************/

    private View.OnLayoutChangeListener onLayoutChangeListener = new View.OnLayoutChangeListener() {

        @Override
        public void onLayoutChange(View view, int i, int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                mVideoWidth = view.getWidth(); // 获取宽度
                mVideoHeight = view.getHeight(); // 获取高度
                if (mMediaPlayer != null) {
                    try {
                        IVLCVout vlcOut = mMediaPlayer.getVLCVout();
                        vlcOut.setWindowSize(mVideoWidth, mVideoHeight);
                        applyAspectRatio();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                // Re-apply PiP scale transform if in PiP mode — RN's
                // layout pass can clobber our setScaleX/Y, especially
                // around play/pause state changes that trigger view
                // re-renders. Reapplying here keeps the overlay rect
                // pinned to the correct aspect.
                if (inPipMode) {
                    applySurfaceScalingMode(2);
                }
            }
        }
    };

    /**
     * 播放过程中的时间事件监听
     */
    private MediaPlayer.EventListener mPlayerListener = new MediaPlayer.EventListener() {
        long currentTime = 0;
        long totalLength = 0;

        @Override
        public void onEvent(MediaPlayer.Event event) {
            // System.out.println("onEvent: " + event.type);
            boolean isPlaying = mMediaPlayer.isPlaying();
            currentTime = mMediaPlayer.getTime();
            float position = mMediaPlayer.getPosition();
            totalLength = mMediaPlayer.getLength();
            WritableMap map = Arguments.createMap();
            map.putBoolean("isPlaying", isPlaying);
            map.putDouble("position", position);
            map.putDouble("currentTime", currentTime);
            map.putDouble("duration", totalLength);

            switch (event.type) {
                case MediaPlayer.Event.EndReached:
                    map.putString("type", "Ended");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_END);
                    if (autoReconnect && isLiveStream) {
                        // Live stream ended unexpectedly — provider dropped or playlist exhausted
                        scheduleReconnect("EndReached on live");
                    } else {
                        // VOD finished naturally — stop stall detection so it doesn't
                        // misinterpret "no more TimeChanged" as a stall
                        stopStallDetection();
                        cancelPendingReconnect();
                    }
                    break;
                case MediaPlayer.Event.Playing:
                    map.putString("type", "Playing");
                    // Snapshot of currently selected SPU/audio track at the moment
                    // playback begins. By this time VLC has applied its default
                    // selection (locale-based or stream-flagged), so this is a
                    // reliable signal for the JS side to mirror in its UI state.
                    try {
                        map.putInt("currentSubtitle", mMediaPlayer.getSpuTrack());
                    } catch (Exception e) {
                        map.putInt("currentSubtitle", -1);
                    }
                    try {
                        map.putInt("currentAudio", mMediaPlayer.getAudioTrack());
                    } catch (Exception e) {
                        map.putInt("currentAudio", -1);
                    }
                    map.putString("source", "playing");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_IS_PLAYING);
                    // Successful playback — reset reconnect counter, start stall detection
                    reconnectAttempt = 0;
                    lastTimeChangedMs = System.currentTimeMillis();
                    if (autoReconnect) startStallDetection();
                    break;
                case MediaPlayer.Event.Opening:
                    map.putString("type", "Opening");
                    lastTimeChangedMs = System.currentTimeMillis();
                    // eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_OPEN);
                    break;
                case MediaPlayer.Event.Paused:
                    map.putString("type", "Paused");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_PAUSED);
                    stopStallDetection();
                    break;
                case MediaPlayer.Event.Buffering:
                    float bufferPercent = event.getBuffering();
                    map.putDouble("bufferRate", bufferPercent);
                    map.putString("type", "Buffering");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_VIDEO_BUFFERING);
                    if (bufferPercent >= 100.0f) {
                        // Buffer full after seek — VLC may not re-emit Playing, so
                        // reset stall timer here to avoid false positive
                        lastTimeChangedMs = System.currentTimeMillis();
                    }
                    break;
                case MediaPlayer.Event.Stopped:
                    map.putString("type", "Stopped");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_VIDEO_STOPPED);
                    stopStallDetection();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    map.putString("type", "Error");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_ERROR);
                    if (autoReconnect) {
                        scheduleReconnect("EncounteredError");
                    }
                    break;
                case MediaPlayer.Event.TimeChanged:
                    map.putString("type", "TimeChanged");
                    lastTimeChangedMs = System.currentTimeMillis();
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_SEEK);
                    break;
                case MediaPlayer.Event.ESSelected:
                    // Fires whenever an elementary stream gets selected (auto by libVLC
                    // on default-pick, or manually). Emits a fresh snapshot of current
                    // SPU/audio so JS can stay in sync. Wrapped defensively in case the
                    // libVLC build doesn't expose track getters at this point.
                    map.putString("type", "ESSelected");
                    try {
                        map.putInt("currentSubtitle", mMediaPlayer.getSpuTrack());
                    } catch (Exception e) {
                        map.putInt("currentSubtitle", -1);
                    }
                    try {
                        map.putInt("currentAudio", mMediaPlayer.getAudioTrack());
                    } catch (Exception e) {
                        map.putInt("currentAudio", -1);
                    }
                    map.putString("source", "esSelected");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_IS_PLAYING);
                    break;
                default:
                    map.putString("type", event.type + "");
                    eventEmitter.onVideoStateChange(map);
                    break;
            }

        }
    };

    private IVLCVout.OnNewVideoLayoutListener onNewVideoLayoutListener = new IVLCVout.OnNewVideoLayoutListener() {
        @Override
        public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
            if (width * height == 0)
                return;
            // store video size
            mVideoWidth = width;
            mVideoHeight = height;
            mVideoVisibleWidth = visibleWidth;
            mVideoVisibleHeight = visibleHeight;
            mSarNum = sarNum;
            mSarDen = sarDen;
            WritableMap map = Arguments.createMap();
            map.putInt("mVideoWidth", mVideoWidth);
            map.putInt("mVideoHeight", mVideoHeight);
            map.putInt("mVideoVisibleWidth", mVideoVisibleWidth);
            map.putInt("mVideoVisibleHeight", mVideoVisibleHeight);
            map.putInt("mSarNum", mSarNum);
            map.putInt("mSarDen", mSarDen);
            map.putString("type", "onNewVideoLayout");
            eventEmitter.onVideoStateChange(map);
        }
    };

    IVLCVout.Callback callback = new IVLCVout.Callback() {
        @Override
        public void onSurfacesCreated(IVLCVout ivlcVout) {
            isSurfaceViewDestory = false;
        }

        @Override
        public void onSurfacesDestroyed(IVLCVout ivlcVout) {
            isSurfaceViewDestory = true;
        }

    };

    private void stopPlayback() {
        onStopPlayback();
        releasePlayer();
        unregisterPipReceiver();
        disablePipLayoutListener();
        removeCallbacks(forceVlcResyncRunnable);
    }

    private void onStopPlayback() {
        setKeepScreenOn(false);
        audioManager.abandonAudioFocus(this);
    }

    private void createPlayer(boolean autoplayResume, boolean isResume) {
        releasePlayer();
        if (!isSurfaceCreated || getHolder().getSurface() == null
                || !getHolder().getSurface().isValid()) {
            return;
        }
        try {
            final ArrayList<String> cOptions = new ArrayList<>();
            String uriString = srcMap.hasKey("uri") ? srcMap.getString("uri") : null;
            boolean isNetwork = srcMap.hasKey("isNetwork") ? srcMap.getBoolean("isNetwork") : false;
            boolean autoplay = srcMap.hasKey("autoplay") ? srcMap.getBoolean("autoplay") : true;
            int initType = srcMap.hasKey("initType") ? srcMap.getInt("initType") : 1;
            ReadableArray mediaOptions = srcMap.hasKey("mediaOptions") ? srcMap.getArray("mediaOptions") : null;
            ReadableArray initOptions = srcMap.hasKey("initOptions") ? srcMap.getArray("initOptions") : null;
            String userAgent = srcMap.hasKey("userAgent") ? srcMap.getString("userAgent") : null;
            boolean hwDecoderEnabled = srcMap.hasKey("hwDecoderEnabled") ? srcMap.getBoolean("hwDecoderEnabled") : false;
            boolean hwDecoderForced = srcMap.hasKey("hwDecoderForced") ? srcMap.getBoolean("hwDecoderForced") : false;

            this.mediaType = srcMap.hasKey("mediaType") ? srcMap.getString("mediaType") : null;
            this.isLiveStream = mediaType != null
                && ("live".equalsIgnoreCase(mediaType) || "timeshift".equalsIgnoreCase(mediaType));
            boolean defaultAutoReconnect = "live".equalsIgnoreCase(mediaType);
            this.autoReconnect = srcMap.hasKey("autoReconnect")
                ? srcMap.getBoolean("autoReconnect")
                : defaultAutoReconnect;
            this.maxReconnectAttempts = srcMap.hasKey("maxReconnectAttempts")
                ? srcMap.getInt("maxReconnectAttempts")
                : DEFAULT_MAX_RECONNECTS;

            if (initOptions != null) {
                ArrayList options = initOptions.toArrayList();
                for (int i = 0; i < options.size(); i++) {
                    cOptions.add((String) options.get(i));
                }
            }
            if (initType == 1) {
                libvlc = new LibVLC(getContext());
            } else {
                libvlc = new LibVLC(getContext(), cOptions);
            }
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(mPlayerListener);

            if (userAgent != null) {
                libvlc.setUserAgent(userAgent, userAgent);
            }

            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (mVideoWidth > 0 && mVideoHeight > 0) {
                vlcOut.setWindowSize(mVideoWidth, mVideoHeight);
                applyAspectRatio();
            }

            Media m;
            if (isNetwork) {
                m = new Media(libvlc, Uri.parse(uriString));
            } else {
                m = new Media(libvlc, uriString);
            }
            m.setEventListener(mMediaListener);
            m.setHWDecoderEnabled(hwDecoderEnabled, hwDecoderForced);
            if (mediaOptions != null) {
                ArrayList options = mediaOptions.toArrayList();
                for (int i = 0; i < options.size() - 1; i++) {
                    m.addOption((String) options.get(i));
                }
            }
            mMediaPlayer.setMedia(m);
            m.release();
            mMediaPlayer.setScale(0);

            if (!vlcOut.areViewsAttached()) {
                vlcOut.addCallback(callback);
                vlcOut.setVideoView(this);
                vlcOut.attachViews(onNewVideoLayoutListener);
            }
            if (isResume) {
                if (autoplayResume) {
                    requestAudioFocusForPlayback();
                    mMediaPlayer.play();
                }
            } else if (autoplay) {
                isPaused = false;
                requestAudioFocusForPlayback();
                mMediaPlayer.play();
            }
            eventEmitter.loadStart();
            setProgressUpdateRunnable();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releasePlayer() {
        stopStallDetection();
        cancelPendingReconnect();
        if (libvlc == null) return;
        try { mMediaPlayer.setEventListener(null); } catch (Exception ignored) {}
        mMediaPlayer.stop();
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(callback);
        vout.detachViews();
        mMediaPlayer.release();
        libvlc.release();
        libvlc = null;
        if (mProgressUpdateRunnable != null) {
            mProgressUpdateHandler.removeCallbacks(mProgressUpdateRunnable);
        }
    }

    // ==================== Retry / reconnect ====================

    private void startStallDetection() {
        if (stallCheckRunnable != null) return; // already running
        stallCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (mMediaPlayer == null) return;
                long sinceLastUpdate = System.currentTimeMillis() - lastTimeChangedMs;
                if (lastTimeChangedMs > 0 && sinceLastUpdate > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "Stall detected: no TimeChanged for " + sinceLastUpdate + "ms");
                    stopStallDetection();
                    scheduleReconnect("stall");
                    return;
                }
                stallHandler.postDelayed(this, STALL_CHECK_INTERVAL_MS);
            }
        };
        stallHandler.postDelayed(stallCheckRunnable, STALL_CHECK_INTERVAL_MS);
    }

    private void stopStallDetection() {
        stallHandler.removeCallbacksAndMessages(null);
        stallCheckRunnable = null;
    }

    private void cancelPendingReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null);
        pendingReconnectRunnable = null;
    }

    private void scheduleReconnect(String reason) {
        if (!autoReconnect || srcMap == null) return;
        // Cancel any pending work so we don't stack up reconnects
        cancelPendingReconnect();
        stopStallDetection();

        // Live = unlimited. VOD = maxReconnectAttempts.
        if (!isLiveStream && reconnectAttempt >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnect attempts (" + maxReconnectAttempts + ") reached, giving up");
            WritableMap failed = Arguments.createMap();
            failed.putInt("attempts", reconnectAttempt);
            eventEmitter.sendEvent(failed, VideoEventEmitter.EVENT_ON_RECONNECT_FAILED);
            return;
        }

        reconnectAttempt++;
        // Exponential backoff: 2s, 4s, 8s, 8s, 8s ... (capped at RECONNECT_MAX_DELAY_MS)
        int shiftBy = Math.min(reconnectAttempt - 1, 4);
        int delay = Math.min(RECONNECT_INITIAL_DELAY_MS * (1 << shiftBy), RECONNECT_MAX_DELAY_MS);
        Log.w(TAG, "Reconnect attempt " + reconnectAttempt + " in " + delay + "ms (reason=" + reason
                + ", live=" + isLiveStream + ")");

        WritableMap payload = Arguments.createMap();
        payload.putInt("attempt", reconnectAttempt);
        payload.putInt("maxAttempts", isLiveStream ? -1 : maxReconnectAttempts);
        payload.putInt("delayMs", delay);
        payload.putString("reason", reason);
        eventEmitter.sendEvent(payload, VideoEventEmitter.EVENT_ON_RECONNECTING);

        pendingReconnectRunnable = new Runnable() {
            @Override
            public void run() {
                pendingReconnectRunnable = null;
                if (srcMap == null) return;
                try {
                    // Recreate player with same source to force fresh connection
                    createPlayer(true, false);
                } catch (Exception e) {
                    Log.e(TAG, "Reconnect error", e);
                    // If createPlayer threw, schedule next attempt
                    scheduleReconnect("reconnect-exception");
                }
            }
        };
        reconnectHandler.postDelayed(pendingReconnectRunnable, delay);
    }

    /**
     * 视频进度调整
     *
     * @param time
     */
    public void seekTo(long time) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setTime(time);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setSubtitleTrack(int trackIndex) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setSpuTrack(trackIndex);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setAudioTrack(int trackIndex) {
        if (mMediaPlayer == null) return;
        // Ignore audioTrack prop until VLC has parsed audio tracks. Otherwise
        // initial audioTrack=0 from JS hits libvlc as "Disable" (id 0 = off
        // when no real ids exist yet) and audio stays muted until manual toggle.
        try {
            MediaPlayer.TrackDescription[] tracks = mMediaPlayer.getAudioTracks();
            if (tracks == null || tracks.length == 0) return;
            boolean found = false;
            for (MediaPlayer.TrackDescription t : tracks) {
                if (t.id == trackIndex) { found = true; break; }
            }
            if (!found) return;
            mMediaPlayer.setAudioTrack(trackIndex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setPosition(float position) {
        if (mMediaPlayer != null) {
            if (position >= 0 && position <= 1) {
                try {
                    mMediaPlayer.setPosition(position);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 设置资源路径
     *
     * @param uri
     * @param isNetStr
     */
    public void setSrc(String uri, boolean isNetStr, boolean autoplay) {
        this.src = uri;
        this.netStrTag = isNetStr;
        createPlayer(autoplay, false);
    }

    public void setSrc(ReadableMap src) {
        this.srcMap = src;
        // New source = fresh retry budget (exponential backoff doesn't carry over
        // from a previous URL's failures)
        this.reconnectAttempt = 0;
        if (inPipMode) {
            // Source change while PiP is active is uncommon but possible
            // (notification deep link, scheduled channel switch, etc).
            // VLC release+recreate causes a brief surface flicker visible
            // in the PiP overlay. Logging so we can correlate user reports
            // of "PiP went black" with deep-link / external source change
            // events.
            Log.w("RNVLCPiP", "setSrc called during active PiP — expect "
                    + "brief flicker in overlay during VLC re-init");
        }
        createPlayer(true, false);

    }


    /**
     * 改变播放速率
     *
     * @param rateModifier
     */
    public void setRateModifier(float rateModifier) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setRate(rateModifier);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setmProgressUpdateInterval(float interval) {
       mProgressUpdateInterval = interval;
    }


    /**
     * 改变声音大小
     *
     * @param volumeModifier
     */
    public void setVolumeModifier(int volumeModifier) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.setVolume(volumeModifier);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 改变静音状态
     *
     * @param muted
     */
    public void setMutedModifier(boolean muted) {
        if (mMediaPlayer != null) {
            try {
                if (muted) {
                    this.preVolume = mMediaPlayer.getVolume();
                    mMediaPlayer.setVolume(0);
                } else {
                    mMediaPlayer.setVolume(this.preVolume);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 改变播放状态
     *
     * @param paused
     */
    public void setPausedModifier(boolean paused) {
        // Log.i("paused:", "" + paused + ":" + mMediaPlayer);
        if (mMediaPlayer != null) {
            try {
                if (paused) {
                    isPaused = true;
                    mMediaPlayer.pause();
                } else {
                    isPaused = false;
                    requestAudioFocusForPlayback();
                    mMediaPlayer.play();
                    // Log.i("do play:", true + "");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            createPlayer(!paused, false);
        }
    }


    /**
     * 截图
     *
     * @param path
     */
    public void doSnapshot(String path) {
        return;
    }


    /**
     * 重新加载视频
     *
     * @param autoplay
     */
    public void doResume(boolean autoplay) {
        createPlayer(autoplay, true);
    }


    public void setRepeatModifier(boolean repeat) {
    }


    public void applyAspectRatio() {
        if (mMediaPlayer == null) return;
        try {
            if (aspectRatio == null || aspectRatio.equals("original")) {
                aspectRatio = null;
            } else if (aspectRatio.equals("fill")) {
                if (mVideoWidth > 0 && mVideoHeight > 0) {
                    mMediaPlayer.setAspectRatio(mVideoWidth + ":" + mVideoHeight);
                }
                return;
            }
            mMediaPlayer.setAspectRatio(aspectRatio);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setAspectRatio(String aspect) {
        aspectRatio = aspect;
        applyAspectRatio();
    }

    public void cleanUpResources() {
        removeOnLayoutChangeListener(onLayoutChangeListener);
        stopPlayback();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceCreated = true;
        if (getWidth() > 0 && getHeight() > 0) {
            mVideoWidth = getWidth();
            mVideoHeight = getHeight();
        }
        // Mirrors onSurfaceTextureAvailable behavior: kick off the player
        // once we have a valid surface to render into.
        createPlayer(true, false);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("RNVLCPiP", "surfaceChanged raw=" + width + "x" + height
                + " inPip=" + inPipMode);
        mVideoWidth = width;
        mVideoHeight = height;
        if (mMediaPlayer == null) return;
        try {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            vlcOut.setWindowSize(width, height);
            applyAspectRatio();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceCreated = false;
        // libvlc's IVLCVout will detach automatically via its callback when
        // the surface goes away; we don't tear down the player here so it
        // can resume cleanly on next surfaceCreated (e.g. after PiP exit).
    }

    private final Media.EventListener mMediaListener = new Media.EventListener() {
        @Override
        public void onEvent(Media.Event event) {
            WritableMap map = Arguments.createMap();
            switch (event.type) {
                case Media.Event.MetaChanged:
                    Log.i(tag, "Media.Event.MetaChanged:  =" + event.getMetaId());
                    break;
                case Media.Event.ParsedChanged:
                    try {
                        MediaPlayer.TrackDescription[] titles = mMediaPlayer.getSpuTracks();
                        WritableMap tracks = Arguments.createMap();
                        if(mMediaPlayer.getSpuTracksCount() > 0) {
                            for (int i = 0; i < titles.length; i++) {
                                tracks.putString(titles[i].id + "", titles[i].name);
                                System.out.println("subtitles: " + titles[i].id + " " + titles[i].name);
                            }
                        }

                        map.putMap("subtitles", tracks);
                    } catch (Exception e) {
                        WritableMap tracks = Arguments.createMap();
                        map.putMap("subtitles", tracks);
                        e.printStackTrace();
                    }
                    try {
                        MediaPlayer.TrackDescription[] titles = mMediaPlayer.getAudioTracks();
                        WritableMap tracks = Arguments.createMap();
                        if(mMediaPlayer.getAudioTracksCount() > 0) {
                            for (int i = 0; i < titles.length; i++) {
                                tracks.putString(titles[i].id + "", titles[i].name);
                                System.out.println("audio_tracks: " + titles[i].id + " " + titles[i].name);
                            }
                        }
                        map.putMap("audio_tracks", tracks);
                    } catch (Exception e) {
                        WritableMap tracks = Arguments.createMap();
                        map.putMap("audio_tracks", tracks);
                        e.printStackTrace();
                    }

                    try {
                        long duration = mMediaPlayer.getLength();
                        map.putDouble("duration", duration);
                    } catch (Exception e) {
                        map.putDouble("duration", -1);
                    }

                    // Expose VLC's currently-selected subtitle and audio track IDs so
                    // the JS side can mirror what the player is actually rendering
                    // (VLC may auto-select a default track based on stream metadata).
                    try {
                        map.putInt("currentSubtitle", mMediaPlayer.getSpuTrack());
                    } catch (Exception e) {
                        map.putInt("currentSubtitle", -1);
                    }
                    try {
                        map.putInt("currentAudio", mMediaPlayer.getAudioTrack());
                    } catch (Exception e) {
                        map.putInt("currentAudio", -1);
                    }

                    Log.e(tag, "Media.Event.ParsedChanged  =" + event.getMetaId());
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_OPEN);
                    break;
                case Media.Event.StateChanged:
                    // Log.i(tag, "StateChanged   =" + event.getMetaId());
                    break;
                default:
                    // Log.i(tag, "Media.Event.type=" + event.type + "   eventgetParsedStatus=" + event.getParsedStatus());
                    break;

            }
        }
    };

    /*private void changeSurfaceSize(boolean message) {

        if (mMediaPlayer != null) {
            final IVLCVout vlcVout = mMediaPlayer.getVLCVout();
            vlcVout.setWindowSize(screenWidth, screenHeight);
        }

        double displayWidth = screenWidth, displayHeight = screenHeight;

        if (screenWidth < screenHeight) {
            displayWidth = screenHeight;
            displayHeight = screenWidth;
        }

        // sanity check
        if (displayWidth * displayHeight <= 1 || mVideoWidth * mVideoHeight <= 1) {
            return;
        }

        // compute the aspect ratio
        double aspectRatio, visibleWidth;
        if (mSarDen == mSarNum) {
            *//* No indication about the density, assuming 1:1 *//*
            visibleWidth = mVideoVisibleWidth;
            aspectRatio = (double) mVideoVisibleWidth / (double) mVideoVisibleHeight;
        } else {
            *//* Use the specified aspect ratio *//*
            visibleWidth = mVideoVisibleWidth * (double) mSarNum / mSarDen;
            aspectRatio = visibleWidth / mVideoVisibleHeight;
        }

        // compute the display aspect ratio
        double displayAspectRatio = displayWidth / displayHeight;

        counter ++;

        switch (mCurrentSize) {
            case SURFACE_BEST_FIT:
                if(counter > 2)
                    Toast.makeText(getContext(), "Best Fit", Toast.LENGTH_SHORT).show();
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_FIT_HORIZONTAL:
                Toast.makeText(getContext(), "Fit Horizontal", Toast.LENGTH_SHORT).show();
                displayHeight = displayWidth / aspectRatio;
                break;
            case SURFACE_FIT_VERTICAL:
                Toast.makeText(getContext(), "Fit Horizontal", Toast.LENGTH_SHORT).show();
                displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_FILL:
                Toast.makeText(getContext(), "Fill", Toast.LENGTH_SHORT).show();
                break;
            case SURFACE_16_9:
                Toast.makeText(getContext(), "16:9", Toast.LENGTH_SHORT).show();
                aspectRatio = 16.0 / 9.0;
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_4_3:
                Toast.makeText(getContext(), "4:3", Toast.LENGTH_SHORT).show();
                aspectRatio = 4.0 / 3.0;
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_ORIGINAL:
                Toast.makeText(getContext(), "Original", Toast.LENGTH_SHORT).show();
                displayHeight = mVideoVisibleHeight;
                displayWidth = visibleWidth;
                break;
        }

        // set display size
        int finalWidth = (int) Math.ceil(displayWidth * mVideoWidth / mVideoVisibleWidth);
        int finalHeight = (int) Math.ceil(displayHeight * mVideoHeight / mVideoVisibleHeight);

        SurfaceHolder holder = this.getHolder();
        holder.setFixedSize(finalWidth, finalHeight);

        ViewGroup.LayoutParams lp = this.getLayoutParams();
        lp.width = finalWidth;
        lp.height = finalHeight;
        this.setLayoutParams(lp);
        this.invalidate();
    }*/
}
