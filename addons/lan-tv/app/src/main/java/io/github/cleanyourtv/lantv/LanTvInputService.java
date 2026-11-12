package io.github.cleanyourtv.lantv;

import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

/**
 * System TV input: Live TV tunes a channel URI; this session plays the HDHomeRun / ErsatzTV
 * MPEG-TS-over-HTTP stream with a modest buffer and keep-alive retries while tuned.
 */
@UnstableApi
public class LanTvInputService extends TvInputService {
    private static final String TAG = "LanTv.Player";
    private static final long RETRY_BASE_MS = 800L;
    private static final long RETRY_MAX_MS = 8_000L;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        Ui.registerInput(this);
    }

    @Override
    public Session onCreateSession(String inputId) {
        Log.i(TAG, "onCreateSession " + inputId);
        try {
            return new SessionImpl(this);
        } catch (Throwable t) {
            Log.e(TAG, "onCreateSession failed", t);
            return null;
        }
    }

    private static final class SessionImpl extends TvInputService.Session implements Player.Listener {
        private final Context ctx;
        private final Handler main = new Handler(Looper.getMainLooper());
        private ExoPlayer player;
        private Surface surface;
        private String streamUrl;
        private int retries;
        private boolean availableOnce;
        private final Runnable retryRunnable = () -> {
            if (streamUrl != null) startPlayback(streamUrl, false);
        };

        SessionImpl(Context ctx) {
            super(ctx);
            this.ctx = ctx.getApplicationContext();
            Log.i(TAG, "Session created");
        }

        @Override
        public void onRelease() {
            Log.i(TAG, "Session release");
            main.removeCallbacks(retryRunnable);
            releasePlayer();
            streamUrl = null;
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            this.surface = surface;
            if (player != null) player.setVideoSurface(surface);
            return true;
        }

        @Override
        public void onSetStreamVolume(float volume) {
            if (player != null) player.setVolume(Math.max(0f, Math.min(1f, volume)));
        }

        @Override
        public boolean onTune(Uri channelUri) {
            Log.i(TAG, "onTune " + channelUri);
            main.removeCallbacks(retryRunnable);
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            releasePlayer();
            availableOnce = false;
            long channelId;
            try {
                channelId = android.content.ContentUris.parseId(channelUri);
            } catch (Exception e) {
                Log.e(TAG, "Bad channel uri " + channelUri, e);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            String url = ChannelSync.streamUrl(ctx, channelId);
            if (url == null || url.isEmpty()) {
                Log.e(TAG, "No stream URL for channel " + channelId);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return false;
            }
            streamUrl = url;
            retries = 0;
            main.post(() -> startPlayback(url, true));
            return true;
        }

        private void startPlayback(String url, boolean fresh) {
            try {
                ExoPlayer p = player;
                if (p == null) {
                    p = buildPlayer();
                    player = p;
                } else {
                    p.stop();
                    p.clearMediaItems();
                }
                if (surface != null) p.setVideoSurface(surface);

                MediaItem.Builder item = new MediaItem.Builder().setUri(url);
                if (Hdhr.isHls(url)) {
                    // ErsatzTV segmenter HLS: allow a small catch-up speed after rebuffers.
                    item.setLiveConfiguration(
                            new MediaItem.LiveConfiguration.Builder()
                                    .setMaxPlaybackSpeed(1.04f)
                                    .build());
                }
                p.setMediaItem(item.build());
                p.prepare();
                p.setPlayWhenReady(true);
                Log.i(TAG, (fresh ? "Tune " : "Retry " + retries + " ") + url);
            } catch (Throwable t) {
                Log.e(TAG, "startPlayback failed", t);
                scheduleRetry("startPlayback");
            }
        }

        private ExoPlayer buildPlayer() {
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent("LAN-TV/0.4")
                    .setConnectTimeoutMs(20_000)
                    // Live TS can pause briefly between segments; don't kill the socket for that.
                    .setReadTimeoutMs(90_000)
                    .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data = new DefaultDataSource.Factory(ctx, http);

            LoadErrorHandlingPolicy errors = new DefaultLoadErrorHandlingPolicy() {
                @Override
                public long getRetryDelayMsFor(LoadErrorInfo loadErrorInfo) {
                    int n = loadErrorInfo.errorCount;
                    long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * (1L << Math.min(n, 4)));
                    Log.w(TAG, "Load error #" + n + ", retry in " + delay + "ms: "
                            + loadErrorInfo.exception);
                    return delay;
                }

                @Override
                public int getMinimumLoadableRetryCount(int dataType) {
                    return Integer.MAX_VALUE;
                }
            };

            // Keep buffers modest: a 180s max buffer OOMs low-RAM Sony sets and leaves the
            // system TV-input binding DEAD until reboot (no further onCreateSession calls).
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            /* minBufferMs */ 15_000,
                            /* maxBufferMs */ 50_000,
                            /* bufferForPlaybackMs */ 2_500,
                            /* bufferForPlaybackAfterRebufferMs */ 5_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();

            DefaultRenderersFactory renderers = new DefaultRenderersFactory(ctx)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .setEnableDecoderFallback(true);

            ExoPlayer p = new ExoPlayer.Builder(ctx)
                    .setRenderersFactory(renderers)
                    .setMediaSourceFactory(
                            new DefaultMediaSourceFactory(data).setLoadErrorHandlingPolicy(errors))
                    .setLoadControl(loadControl)
                    .build();
            p.setWakeMode(C.WAKE_MODE_NETWORK);
            p.addListener(this);
            return p;
        }

        private void releasePlayer() {
            ExoPlayer p = player;
            player = null;
            if (p != null) {
                p.removeListener(this);
                p.setVideoSurface(null);
                p.release();
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
                retries = 0;
                availableOnce = true;
                notifyVideoAvailable();
            } else if (playbackState == Player.STATE_BUFFERING) {
                // After first frame, some OEM Live TV UIs treat BUFFERING as a hard stop.
                if (!availableOnce) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
                }
            } else if (playbackState == Player.STATE_ENDED) {
                scheduleRetry("ended");
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.w(TAG, "Player error: " + error.getErrorCodeName() + " " + error.getMessage());
            scheduleRetry(error.getErrorCodeName());
        }

        private void scheduleRetry(String why) {
            if (streamUrl == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                return;
            }
            retries++;
            if (!availableOnce) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
            }
            long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS * Math.min(retries, 8));
            Log.w(TAG, "Retry #" + retries + " in " + delay + "ms (" + why + ")");
            main.removeCallbacks(retryRunnable);
            main.postDelayed(retryRunnable, delay);
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // Media3 still picks embedded captions when present.
        }
    }
}
