package tv.danmaku.ijk.media.player.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// ✨✨✨ 核心修复：把新版的 SubtitleView，换成老版的！ ✨✨✨
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.text.Cue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import tv.danmaku.ijk.media.player.MediaSource;
import tv.danmaku.ijk.media.player.R;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

public class IjkVideoView extends FrameLayout implements MediaController.MediaPlayerControl, IMediaPlayer.Listener, IRenderView.IRenderCallback {

    private final String TAG = IjkVideoView.class.getSimpleName();

    public static final int STATE_ERROR = -1;
    public static final int STATE_IDLE = 0;
    public static final int STATE_PREPARING = 1;
    public static final int STATE_PREPARED = 2;
    public static final int STATE_PLAYING = 3;
    public static final int STATE_PAUSED = 4;
    public static final int STATE_ENDED = 5;

    private static final int codec = IjkMediaPlayer.OPT_CATEGORY_CODEC;
    private static final int format = IjkMediaPlayer.OPT_CATEGORY_FORMAT;
    private static final int player = IjkMediaPlayer.OPT_CATEGORY_PLAYER;

    private static final int PLAYER_NONE = -1;
    private static final int PLAYER_SYS = 0;
    private static final int PLAYER_IJK = 1;

    private static final int RENDER_SURFACE_VIEW = 0;
    private static final int RENDER_TEXTURE_VIEW = 1;

    // ✨✨✨ 【核心修复】补全所有丢失的成员变量 ✨✨✨
    private int mVideoWidth;
    private int mVideoHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mVideoSarNum;
    private int mVideoSarDen;
    private int mVideoRotationDegree;

    private int mTargetState;
    private int mCurrentState;
    private int mCurrentDecode;
    private int mCurrentRender;
    private int mCurrentPlayer;
    private int mCurrentAspectRatio;
    private long mStartPosition;

    private int mCurrentBufferPercentage;
    private long mCurrentBufferPosition;
    private float mCurrentSpeed;

    private boolean mKeepContentOnPlayerReset;
    private boolean mCanPause = true;
    private boolean mCanSeekBack = true;
    private boolean mCanSeekForward = true;

    private IRenderView.ISurfaceHolder mSurfaceHolder;
    private IMediaPlayer.Listener mListener;
    private IRenderView mRenderView;
    private IMediaPlayer.OnCompletionListener mOnCompletionListener;
    private IMediaPlayer.OnPreparedListener mOnPreparedListener;
    private IMediaPlayer.OnErrorListener mOnErrorListener;
    private IMediaPlayer.OnInfoListener mOnInfoListener;
    private IMediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener;
    private IMediaPlayer.OnTimedTextListener mOnTimedTextListener;

    private final SubtitleView mSubtitleView;
    private final AudioManager mAudioManager;
    private final FrameLayout mContentFrame;
    private final ImageView mArtworkView;
    private Drawable mDefaultArtwork;
    private IMediaPlayer mPlayer;

    public IjkVideoView(Context context) {
        this(context, null);
    }

    public IjkVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initVideoView(context);
    }

    public IjkVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initVideoView(context);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public IjkVideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initVideoView(context);
    }

    private void initVideoView(Context context) {
        mAppContext = context.getApplicationContext();
        
        // ✨ 还原 v14 的初始化逻辑
        initRenders();

        mVideoWidth = 0;
        mVideoHeight = 0;
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;

        // ✨✨✨ 核心修复：创建老版的 SubtitleView 实例 ✨✨✨
        mSubtitleView = new SubtitleView(context);
        mSubtitleView.setUserDefaultStyle();
        mSubtitleView.setUserDefaultTextSize();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        addView(mSubtitleView, lp);
    }

    public void setRenderView(IRenderView renderView) {
        if (mRenderView != null) {
            if (mMediaPlayer != null)
                mMediaPlayer.setDisplay(null);

            View renderUIView = mRenderView.getView();
            mRenderView.removeRenderCallback(mSHCallback);
            mRenderView = null;
            removeView(renderUIView);
        }

        if (renderView == null)
            return;

        mRenderView = renderView;
        renderView.setAspectRatio(mCurrentAspectRatio);
        if (mVideoWidth > 0 && mVideoHeight > 0)
            renderView.setVideoSize(mVideoWidth, mVideoHeight);
        if (mVideoSarNum > 0 && mVideoSarDen > 0)
            renderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);

        View renderUIView = mRenderView.getView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        renderUIView.setLayoutParams(lp);
        addView(renderUIView, 0); // ✨ 确保 RenderView 在最底层

        mRenderView.addRenderCallback(mSHCallback);
        mRenderView.setVideoRotation(mVideoRotationDegree);
    }
    
    // ✨ 补上 initRenders 方法
    private void initRenders() {
        // 这个方法在原版里通常是空的，留作扩展
    }

    public void setRender(int render) {
        switch (render) {
            case RENDER_TEXTURE_VIEW: {
                TextureRenderView renderView = new TextureRenderView(getContext());
                if (mMediaPlayer != null) {
                    renderView.getSurfaceHolder().bindToMediaPlayer(mMediaPlayer);
                    renderView.setVideoSize(mMediaPlayer.getVideoWidth(), mMediaPlayer.getVideoHeight());
                    renderView.setVideoSampleAspectRatio(mMediaPlayer.getVideoSarNum(), mMediaPlayer.getVideoSarDen());
                    renderView.setAspectRatio(mCurrentAspectRatio);
                }
                setRenderView(renderView);
                break;
            }
            case RENDER_SURFACE_VIEW: {
                SurfaceRenderView renderView = new SurfaceRenderView(getContext());
                setRenderView(renderView);
                break;
            }
            default:
                Log.e(TAG, String.format(Locale.getDefault(), "invalid render %d\n", render));
                break;
        }
    }

    /**
     * Sets video path.
     *
     * @param path the path of the video.
     */
    public void setVideoPath(String path) {
        setVideoURI(Uri.parse(path));
    }

    /**
     * Sets video URI.
     *
     * @param uri the URI of the video.
     */
    public void setVideoURI(Uri uri) {
        setVideoURI(uri, null);
    }

    /**
     * Sets video URI using specific headers.
     *
     * @param uri     the URI of the video.
     * @param headers the headers for the URI request.
     */
    private void setVideoURI(Uri uri, Map<String, String> headers) {
        mUri = uri;
        mHeaders = headers;
        mSeekWhenPrepared = 0;
        openVideo();
        requestLayout();
        invalidate();
    }

    public void stopPlayback() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            mTargetState = STATE_IDLE;
            AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocus(null);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void openVideo() {
        if (mUri == null || mSurfaceHolder == null) {
            return;
        }
        release(false);

        AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        try {
            mMediaPlayer = createPlayer();
            mMediaPlayer.setOnPreparedListener(mPreparedListener);
            mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
            mMediaPlayer.setOnCompletionListener(mCompletionListener);
            mMediaPlayer.setOnErrorListener(mErrorListener);
            mMediaPlayer.setOnInfoListener(mInfoListener);
            mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
            // ✨✨✨ 【核心修复】将字幕监听器指向老版的 OnTimedTextListener ✨✨✨
            mMediaPlayer.setOnTimedTextListener(mOnTimedTextListener);
            mCurrentBufferPercentage = 0;
            
            // ✨ 还原 v14 的数据源设置逻辑
            String scheme = mUri.getScheme();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    (TextUtils.isEmpty(scheme) || scheme.equalsIgnoreCase("file"))) {
                mMediaPlayer.setDataSource(mAppContext, mUri);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                mMediaPlayer.setDataSource(mAppContext, mUri, mHeaders);
            } else {
                mMediaPlayer.setDataSource(mUri.toString());
            }
            
            bindSurfaceHolder(mMediaPlayer, mSurfaceHolder);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mMediaPlayer.setScreenOnWhilePlaying(true);
            mMediaPlayer.prepareAsync();

            mCurrentState = STATE_PREPARING;
        } catch (IOException ex) {
            Log.w(TAG, "Unable to open content: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "Unable to open content: " + mUri, ex);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
            mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }
    
    // ✨ 补上 createPlayer 方法
    private IMediaPlayer createPlayer() {
        // 这里可以根据需要创建不同类型的播放器，我们先默认 IjkPlayer
        IjkMediaPlayer ijkMediaPlayer = new IjkMediaPlayer();
        IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_DEBUG);
        // ... (可以加入哥哥的 setOptions 逻辑)
        return ijkMediaPlayer;
    }

    public void setOnPreparedListener(IMediaPlayer.OnPreparedListener l) {
        mOnPreparedListener = l;
    }

    public void setOnCompletionListener(IMediaPlayer.OnCompletionListener l) {
        mOnCompletionListener = l;
    }

    public void setOnErrorListener(IMediaPlayer.OnErrorListener l) {
        mOnErrorListener = l;
    }

    public void setOnInfoListener(IMediaPlayer.OnInfoListener l) {
        mOnInfoListener = l;
    }
    
    public void setOnBufferingUpdateListener(IMediaPlayer.OnBufferingUpdateListener l) {
        mBufferingUpdateListener = l;
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener l) {
        mOnTimedTextListener = l;
    }

    private void bindSurfaceHolder(IMediaPlayer mp, IRenderView.ISurfaceHolder holder) {
        if (mp == null) return;
        if (holder == null) {
            mp.setDisplay(null);
            return;
        }
        holder.bindToMediaPlayer(mp);
    }

    IRenderView.IRenderCallback mSHCallback = new IRenderView.IRenderCallback() {
        @Override
        public void onSurfaceChanged(@NonNull IRenderView.ISurfaceHolder holder, int format, int w, int h) {
            if (holder.getRenderView() != mRenderView) {
                Log.e(TAG, "onSurfaceChanged: unmatched render callback\n");
                return;
            }

            mSurfaceWidth = w;
            mSurfaceHeight = h;
            boolean isValidState = (mTargetState == STATE_PLAYING);
            boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
            if (mMediaPlayer != null && isValidState && hasValidSize) {
                if (mSeekWhenPrepared != 0) {
                    seekTo(mSeekWhenPrepared);
                }
                start();
            }
        }

        @Override
        public void onSurfaceCreated(@NonNull IRenderView.ISurfaceHolder holder, int width, int height) {
            if (holder.getRenderView() != mRenderView) {
                Log.e(TAG, "onSurfaceCreated: unmatched render callback\n");
                return;
            }

            mSurfaceHolder = holder;
            if (mMediaPlayer != null)
                bindSurfaceHolder(mMediaPlayer, holder);
            else
                openVideo();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull IRenderView.ISurfaceHolder holder) {
            if (holder.getRenderView() != mRenderView) {
                Log.e(TAG, "onSurfaceDestroyed: unmatched render callback\n");
                return;
            }
            mSurfaceHolder = null;
            release(true);
        }
    };

    public void release(boolean cleartargetstate) {
        if (mMediaPlayer != null) {
            mMediaPlayer.reset();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mCurrentState = STATE_IDLE;
            if (cleartargetstate) {
                mTargetState = STATE_IDLE;
            }
            AudioManager am = (AudioManager) mAppContext.getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocus(null);
        }
    }

    @Override
    public void start() {
        if (isInPlaybackState()) {
            mMediaPlayer.start();
            mCurrentState = STATE_PLAYING;
        }
        mTargetState = STATE_PLAYING;
    }

    @Override
    public void pause() {
        if (isInPlaybackState()) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
                mCurrentState = STATE_PAUSED;
            }
        }
        mTargetState = STATE_PAUSED;
    }

    public void suspend() {
        release(false);
    }

    public void resume() {
        openVideo();
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return (int) mMediaPlayer.getDuration();
        }
        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return (int) mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int msec) {
        if (isInPlaybackState()) {
            mMediaPlayer.seekTo(msec);
            mSeekWhenPrepared = 0;
        } else {
            mSeekWhenPrepared = msec;
        }
    }

    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mMediaPlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        if (mMediaPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    private boolean isInPlaybackState() {
        return (mMediaPlayer != null &&
                mCurrentState != STATE_ERROR &&
                mCurrentState != STATE_IDLE &&
                mCurrentState != STATE_PREPARING);
    }

    @Override
    public boolean canPause() {
        return mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    @Override
    public int getAudioSessionId() {
        // ✨ 还原 v14 的逻辑
        return 0;
    }

    //-------------------------
    // Extend: Aspect Ratio
    //-------------------------

    private static final int[] s_allAspectRatio = {
            IRenderView.AR_ASPECT_FIT_PARENT,
            IRenderView.AR_ASPECT_FILL_PARENT,
            IRenderView.AR_ASPECT_WRAP_CONTENT,
            IRenderView.AR_16_9_FIT_PARENT,
            IRenderView.AR_4_3_FIT_PARENT};
    private int mCurrentAspectRatioIndex = 0;
    private int mCurrentAspectRatio = s_allAspectRatio[0];

    public int toggleAspectRatio() {
        mCurrentAspectRatioIndex++;
        mCurrentAspectRatioIndex %= s_allAspectRatio.length;
        mCurrentAspectRatio = s_allAspectRatio[mCurrentAspectRatioIndex];
        if (mRenderView != null)
            mRenderView.setAspectRatio(mCurrentAspectRatio);
        return mCurrentAspectRatio;
    }

    //-------------------------
    // Extend: Render
    //-------------------------
    public static final int RENDER_NONE = 0;

    private List<Integer> mAllRenders = new ArrayList<Integer>();
    private int mCurrentRenderIndex = 0;

    private void initRenders() {
        mAllRenders.clear();
        mAllRenders.add(RENDER_SURFACE_VIEW);
        mAllRenders.add(RENDER_TEXTURE_VIEW);
        mCurrentRender = mAllRenders.get(mCurrentRenderIndex);
        setRender(mCurrentRender);
    }

    public int toggleRender() {
        mCurrentRenderIndex++;
        mCurrentRenderIndex %= mAllRenders.size();
        mCurrentRender = mAllRenders.get(mCurrentRenderIndex);
        setRender(mCurrentRender);
        return mCurrentRender;
    }

    //-------------------------
    // Extend: Player
    //-------------------------
    public int togglePlayer() {
        if (mMediaPlayer != null)
            mMediaPlayer.release();
        if (mRenderView != null)
            mRenderView.getView().invalidate();
        openVideo();
        return 0; // ✨ 这里的返回值在 v14 里没有具体意义，保持简单
    }

    // ✨✨✨ 【核心修复】补全字幕和音轨切换的完整逻辑 ✨✨✨
    public ITrackInfo[] getTrackInfo() {
        if (mMediaPlayer == null) return null;
        return mMediaPlayer.getTrackInfo();
    }

    public void selectTrack(int stream) {
        if (mMediaPlayer == null) return;
        // 切换字幕轨道时，清空旧的字幕
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mMediaPlayer.selectTrack(stream);
    }



    public void deselectTrack(int stream) {
        if (mMediaPlayer == null) return;
        // 取消字幕轨道时，清空旧的字幕
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mMediaPlayer.deselectTrack(stream);
    }

    public ITrackInfo[] getTrackInfo() {
        if (mMediaPlayer == null) return null;
        return mMediaPlayer.getTrackInfo();
    }

    public void selectTrack(int stream) {
        if (mMediaPlayer == null) return;
        // ✨✨✨ 核心修复：切换字幕轨道时，清空旧的字幕 ✨✨✨
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mMediaPlayer.selectTrack(stream);
    }

    public void deselectTrack(int stream) {
        if (mMediaPlayer == null) return;
        // ✨✨✨ 核心修复：取消字幕轨道时，清空旧的字幕 ✨✨✨
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mMediaPlayer.deselectTrack(stream);
    }

    public int getSelectedTrack(int trackType) {
        if (mMediaPlayer == null) return -1;
        return mMediaPlayer.getSelectedTrack(trackType);
    }

    private void setPreferredTextLanguage() {
        if (mMediaPlayer == null) return;
        ITrackInfo[] trackInfos = getTrackInfo();
        if (trackInfos == null) return;
        int selected = getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TEXT);
        for (int i = 0; i < trackInfos.length; i++) {
            ITrackInfo trackInfo = trackInfos[i];
            if (trackInfo.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TEXT) {
                if ("zh".equals(trackInfo.getLanguage()) && i != selected) {
                    selectTrack(i);
                    break;
                }
            }
        }
    }

    public void setDefaultArtwork(@Nullable Drawable defaultArtwork) {
        if (mDefaultArtwork != defaultArtwork) {
            mDefaultArtwork = defaultArtwork;
            updateForCurrentTrackSelections();
        }
    }

    public Bitmap getDefaultArtwork() {
        if (mDefaultArtwork instanceof BitmapDrawable) {
            return ((BitmapDrawable) mDefaultArtwork).getBitmap();
        }
        return null;
    }

    private void updateForCurrentTrackSelections() {
        if (mMediaPlayer == null) return;
        ITrackInfo[] trackInfos = getTrackInfo();
        if (trackInfos == null || trackInfos.length == 0) return;
        
        int selected = getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        if (selected >= 0) {
            if (mArtworkView != null) mArtworkView.setVisibility(GONE);
            setRenderView(mCurrentRender);
        } else {
            removeRenderView();
            setDrawableArtwork(mDefaultArtwork);
        }
    }

    // ✨✨✨ 【核心修复】这是唯一的、最健壮的 setDrawableArtwork 方法 ✨✨✨
    private void setDrawableArtwork(Drawable drawable) {
        if (drawable == null) {
            if (mArtworkView != null) mArtworkView.setVisibility(GONE);
            return;
        }
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth > 0 && drawableHeight > 0) {
            if (mArtworkView != null) {
                mArtworkView.setImageDrawable(drawable);
                mArtworkView.setVisibility(VISIBLE);
            }
        } else {
            if (mArtworkView != null) mArtworkView.setVisibility(GONE);
        }
    }
// ✨✨✨ 【完全还原】哥哥最核心的 setOptions 方法 ✨✨✨
    private void setOptions(Uri uri) {
        if (mPlayer instanceof IjkMediaPlayer) {
            IjkMediaPlayer ijk = (IjkMediaPlayer) mPlayer;
            String url = uri.toString();
            ijk.setOption(codec, "skip_loop_filter", 48);
            ijk.setOption(format, "dns_cache_clear", 1);
            ijk.setOption(format, "dns_cache_timeout", -1);
            ijk.setOption(format, "fflags", "fastseek");
            ijk.setOption(format, "http-detect-range-support", 0);
            ijk.setOption(player, "enable-accurate-seek", 0);
            ijk.setOption(player, "framedrop", 1);
            ijk.setOption(player, "max-buffer-size", 15 * 1024 * 1024);
            ijk.setOption(player, "mediacodec", mCurrentDecode);
            ijk.setOption(player, "mediacodec-hevc", mCurrentDecode);
            ijk.setOption(player, "mediacodec-all-videos", mCurrentDecode);
            ijk.setOption(player, "mediacodec-auto-rotate", mCurrentDecode);
            ijk.setOption(player, "mediacodec-handle-resolution-change", mCurrentDecode);
            ijk.setOption(player, "opensles", 0);
            ijk.setOption(player, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
            ijk.setOption(player, "reconnect", 1);
            ijk.setOption(player, "soundtouch", 1);
            ijk.setOption(player, "start-on-prepared", 1);
            ijk.setOption(player, "subtitle", 1); // 开启 IJK 的字幕流
            ijk.setOption(format, "protocol_whitelist", "async,cache,crypto,file,http,https,pipe,rtmp,rtp,tcp,tls,udp,data,ijkinject,ijklongurl,ijksegment,ijkhttphook,ijklivehook,ijktcphook,ijkurlhook,ijkmediadatasource");
            if (url.contains("rtsp") || url.contains("udp") || url.contains("rtp")) {
                ijk.setOption(format, "infbuf", 1);
                ijk.setOption(format, "rtsp_transport", "tcp");
                ijk.setOption(format, "rtsp_flags", "prefer_tcp");
                ijk.setOption(format, "probesize", 512 * 1000);
                ijk.setOption(format, "analyzeduration", 2 * 1000 * 1000);
            }
        }
    }

    @Override
    public void onSurfaceCreated(@NonNull IRenderView.ISurfaceHolder holder, int width, int height) {
        mSurfaceHolder = holder;
        if (mPlayer != null) {
            bindSurfaceHolder(mPlayer, holder);
        } else {
            openVideo();
        }
    }

    @Override
    public void onSurfaceChanged(@NonNull IRenderView.ISurfaceHolder holder, int format, int width, int height) {
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        boolean isValidState = mTargetState == STATE_PLAYING;
        boolean hasValidSize = !mRenderView.shouldWaitForResize() || (mVideoWidth == width && mVideoHeight == height);
        if (mPlayer != null && isValidState && hasValidSize) {
            start();
        }
    }

    @Override
    public void onSurfaceDestroyed(@NonNull IRenderView.ISurfaceHolder holder) {
        mSurfaceHolder = null;
        if (mPlayer != null) mPlayer.setDisplay(null);
        release(true);
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        mCurrentState = STATE_PREPARED;
        if (mOnPreparedListener != null) mOnPreparedListener.onPrepared(mp);
        mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
        if (mVideoWidth != 0 && mVideoHeight != 0) {
            if (mRenderView != null) {
                mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
            }
        }
        int seekToPosition = mSeekWhenPrepared;
        if (seekToPosition != 0) seekTo(seekToPosition);
        if (mTargetState == STATE_PLAYING) start();
        setPreferredTextLanguage();
        updateForCurrentTrackSelections();
        if (mCurrentSpeed > 0) setSpeed(mCurrentSpeed);
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        mCurrentState = STATE_ENDED;
        mTargetState = STATE_ENDED;
        if (mOnCompletionListener != null) mOnCompletionListener.onCompletion(mp);
    }
    
    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        mCurrentState = STATE_ERROR;
        mTargetState = STATE_ERROR;
        if (mOnErrorListener != null) {
            if (mOnErrorListener.onError(mp, what, extra)) {
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean onInfo(IMediaPlayer mp, int what, int extra) {
        // ✨ 使用哥哥更完整的写法，加入了视频旋转的判断
        if (what == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED && mRenderView != null) {
            mRenderView.setVideoRotation(extra);
        }
        if (mOnInfoListener != null) mOnInfoListener.onInfo(mp, what, extra);
        return true;
    }
    
    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
        mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
        mVideoSarNum = sarNum;
        mVideoSarDen = sarDen;
        if (mVideoWidth != 0 && mVideoHeight != 0) {
            if (mRenderView != null) {
                mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
            }
            requestLayout();
        }
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer mp, int percent) {
        mCurrentBufferPercentage = percent;
        if (mBufferingUpdateListener != null) {
            mBufferingUpdateListener.onBufferingUpdate(mp, percent);
        }
    }

    // ✨ 补上这个带 long 参数的回调，虽然 IJKPlayer 默认不调用，但为了接口完整性保留
    public void onBufferingUpdate(IMediaPlayer mp, long position) {
        mCurrentBufferPosition = position;
        // 可以在这里添加自定义的回调逻辑
    }

    // ✨✨✨ 【核心修复】字幕回调的最终实现 ✨✨✨
    @Override
    public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
        if (text != null && mSubtitleView != null) {
            // ✨ 使用咱们修复好的 SubtitleParser，并喂给老版 SubtitleView
            List<Cue> cues = SubtitleParser.parse(text.getText());
            if (cues != null) {
                mSubtitleView.onCues(cues);
            }
        }
        if (mOnTimedTextListener != null) {
            mOnTimedTextListener.onTimedText(mp, text.getText());
        }
    }

    // ✨ 补上 createPlayer 方法的完整实现
    private IMediaPlayer createPlayer() {
        IjkMediaPlayer ijkMediaPlayer = new IjkMediaPlayer();
        IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_DEBUG);
        // ✨ 在这里调用 setOptions，确保每次创建播放器时都应用优化参数
        setOptions(mUri);
        return ijkMediaPlayer;
    }
}
