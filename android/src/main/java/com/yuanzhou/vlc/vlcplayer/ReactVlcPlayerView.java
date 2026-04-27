package com.yuanzhou.vlc.vlcplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
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
class ReactVlcPlayerView extends TextureView implements
        LifecycleEventListener,
        TextureView.SurfaceTextureListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "ReactVlcPlayerView";
    private final String tag = "ReactVlcPlayerView";

    private final VideoEventEmitter eventEmitter;
    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private boolean isSurfaceViewDestory;
    private String src;
    private boolean netStrTag;
    private ReadableMap srcMap;
    private int mVideoHeight = 0;
    private TextureView surfaceView;
    private Surface surfaceVideo;
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


    public ReactVlcPlayerView(ThemedReactContext context) {
        super(context);
        this.eventEmitter = new VideoEventEmitter(context);
        this.themedReactContext = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        screenWidth = dm.widthPixels;
        this.setSurfaceTextureListener(this);

        this.addOnLayoutChangeListener(onLayoutChangeListener);
    }


    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        //createPlayer();
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
                // vlcOut.setVideoSurface(this.getHolder().getSurface(), this.getHolder());
                vlcOut.attachViews(onNewVideoLayoutListener);
                isSurfaceViewDestory = false;
                isPaused = false;
                // this.getHolder().setKeepScreenOn(true);
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


    // AudioManager.OnAudioFocusChangeListener implementation
    @Override
    public void onAudioFocusChange(int focusChange) {
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
    }

    private void onStopPlayback() {
        setKeepScreenOn(false);
        audioManager.abandonAudioFocus(this);
    }

    private void createPlayer(boolean autoplayResume, boolean isResume) {
        releasePlayer();
        if (this.getSurfaceTexture() == null) {
            return;
        }
        try {
            final ArrayList<String> cOptions = new ArrayList<>();
            String uriString = srcMap.hasKey("uri") ? srcMap.getString("uri") : null;
            //String extension = srcMap.hasKey("type") ? srcMap.getString("type") : null;
            boolean isNetwork = srcMap.hasKey("isNetwork") ? srcMap.getBoolean("isNetwork") : false;
            boolean autoplay = srcMap.hasKey("autoplay") ? srcMap.getBoolean("autoplay") : true;
            int initType = srcMap.hasKey("initType") ? srcMap.getInt("initType") : 1;
            ReadableArray mediaOptions = srcMap.hasKey("mediaOptions") ? srcMap.getArray("mediaOptions") : null;
            ReadableArray initOptions = srcMap.hasKey("initOptions") ? srcMap.getArray("initOptions") : null;
            String userAgent = srcMap.hasKey("userAgent") ? srcMap.getString("userAgent") : null;
            boolean hwDecoderEnabled = srcMap.hasKey("hwDecoderEnabled") ? srcMap.getBoolean("hwDecoderEnabled") : false;
            boolean hwDecoderForced = srcMap.hasKey("hwDecoderForced") ? srcMap.getBoolean("hwDecoderForced") : false;

            // Retry config — read from source dict each time (setSrc may be called on remount).
            // reconnectAttempt is NOT reset here because createPlayer is also invoked *from*
            // the reconnect runnable itself; resetting would break exponential backoff.
            // It's reset on successful Playing event (see mPlayerListener).
            this.mediaType = srcMap.hasKey("mediaType") ? srcMap.getString("mediaType") : null;
            // isLive controls unlimited retries. Treat live + timeshift alike for backoff cap.
            this.isLiveStream = mediaType != null
                && ("live".equalsIgnoreCase(mediaType) || "timeshift".equalsIgnoreCase(mediaType));
            // Default auto-reconnect ONLY for "live" — timeshift consumers typically have
            // their own fallback URL logic and should opt-in explicitly.
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
                    String option = (String) options.get(i);
                    cOptions.add(option);
                }
            }
            // Create LibVLC
            if (initType == 1) {
                libvlc = new LibVLC(getContext());
            } else {
                libvlc = new LibVLC(getContext(), cOptions);
            }
            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(mPlayerListener);

            if (userAgent != null) {
                libvlc.setUserAgent(userAgent, userAgent);
            }

            //this.getHolder().setKeepScreenOn(true);
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (mVideoWidth > 0 && mVideoHeight > 0) {
                vlcOut.setWindowSize(mVideoWidth, mVideoHeight);
                applyAspectRatio();
            }

            DisplayMetrics dm = getResources().getDisplayMetrics();
            Media m = null;
            if (isNetwork) {
                Uri uri = Uri.parse(uriString);
                m = new Media(libvlc, uri);
            } else {
                m = new Media(libvlc, uriString);
            }
            m.setEventListener(mMediaListener);
            m.setHWDecoderEnabled(hwDecoderEnabled, hwDecoderForced);
            //添加media  option
            if (mediaOptions != null) {
                ArrayList options = mediaOptions.toArrayList();
                for (int i = 0; i < options.size() - 1; i++) {
                    String option = (String) options.get(i);
                    m.addOption(option);
                }
            }
            mMediaPlayer.setMedia(m);
            // https://github.com/razorRun/react-native-vlc-media-player/commit/dbbcff9ea08bf08dcfde506dc16e896bbf08b407
            m.release();
            mMediaPlayer.setScale(0);

            if (!vlcOut.areViewsAttached()) {
                vlcOut.addCallback(callback);
                // vlcOut.setVideoSurface(this.getSurfaceTexture());
                //vlcOut.setVideoSurface(this.getHolder().getSurface(), this.getHolder());
                //vlcOut.attachViews(onNewVideoLayoutListener);
                vlcOut.setVideoSurface(this.getSurfaceTexture());
                vlcOut.attachViews(onNewVideoLayoutListener);
                // vlcOut.attachSurfaceSlave(surfaceVideo,null,onNewVideoLayoutListener);
                //vlcOut.setVideoView(this);
                //vlcOut.attachViews(onNewVideoLayoutListener);
            }
            if (isResume) {
                if (autoplayResume) {
                    mMediaPlayer.play();
                }
            } else {
                if (autoplay) {
                    isPaused = false;
                    mMediaPlayer.play();
                }
            }
            eventEmitter.loadStart();

            setProgressUpdateRunnable();
        } catch (Exception e) {
            e.printStackTrace();
            //Toast.makeText(getContext(), "Error creating player!", Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        // Always cancel retry/stall timers on release — even if libvlc is null,
        // there may be a pending reconnect scheduled before first createPlayer
        stopStallDetection();
        cancelPendingReconnect();
        if (libvlc == null)
            return;
        // Detach listener before stop to avoid stale events racing reconnect
        try { mMediaPlayer.setEventListener(null); } catch (Exception ignored) {}
        mMediaPlayer.stop();
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(callback);
        vout.detachViews();
        //surfaceView.removeOnLayoutChangeListener(onLayoutChangeListener);
        // https://github.com/razorRun/react-native-vlc-media-player/commit/1382cd512a0b2a88bed363eca44de3f90acdc5c0
        mMediaPlayer.release();
        libvlc.release();
        libvlc = null;
        if(mProgressUpdateRunnable!=null){
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
        if (mMediaPlayer != null) {
            try {
                if (aspectRatio == null || aspectRatio.equals("original")) {
                    aspectRatio = null;
                } else if (aspectRatio.equals("fill")) {
                    if (mVideoWidth > 0 && mVideoHeight > 0) {
                        // vlcOut.setWindowSize(mVideoWidth, mVideoHeight);
                        mMediaPlayer.setAspectRatio(mVideoWidth + ":" + mVideoHeight);
                    }
                    return;
                }
                mMediaPlayer.setAspectRatio(aspectRatio);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setAspectRatio(String aspect) {
        aspectRatio = aspect;
        applyAspectRatio();
    }

    public void cleanUpResources() {
        if (surfaceView != null) {
            surfaceView.removeOnLayoutChangeListener(onLayoutChangeListener);
        }
        stopPlayback();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        surfaceVideo = new Surface(surface);
        createPlayer(true, false);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Log.i("onSurfaceTextureUpdated", "onSurfaceTextureUpdated");
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
