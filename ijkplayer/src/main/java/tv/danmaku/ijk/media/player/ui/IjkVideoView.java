package tv.danmaku.ijk.media.player.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// ✨✨✨ 核心修复：把新版的 SubtitleView，换成老版的！ ✨✨✨
import com.google.android.exoplayer2.ui.SubtitleView;
// ✨✨✨ 核心修复：顺便把 Cue 也引进来，等下肯定要用！ ✨✨✨
import com.google.android.exoplayer2.text.Cue; 

import java.io.File; // ✨ 补上这个，原版里有
import java.io.IOException; // ✨ 补上这个
import java.util.ArrayList; // ✨ 补上这个
import java.util.List;
import java.util.Locale; // ✨ 补上这个
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

    private int mVideoWidth;
    private int mVideoHeight;

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

    private IRenderView.ISurfaceHolder mSurfaceHolder;
    private IMediaPlayer.Listener mListener;
    private IRenderView mRenderView;

    // ✨✨✨ 核心修复：这里的 SubtitleView 已经换成了老版的包名 ✨✨✨
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
        this(context, attrs, 0);
    }

    public IjkVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LayoutInflater.from(context).inflate(R.layout.ijk_player_view, this);
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (attrs != null) initAttr(context, attrs, defStyleAttr);
        mContentFrame = findViewById(R.id.ijk_content_frame);
        mSubtitleView = findViewById(R.id.ijk_subtitle);
        mArtworkView = findViewById(R.id.ijk_artwork);
        mCurrentPlayer = PLAYER_NONE;
        mCurrentState = STATE_IDLE;
        mTargetState = STATE_IDLE;
        mCurrentSpeed = 1.0f;
        setSubtitleView();
    }

    private void setSubtitleView() {
        if (mSubtitleView == null) return;
        // ✨ 老版 ExoPlayer 的初始化方式
        mSubtitleView.setUserDefaultStyle();
        mSubtitleView.setUserDefaultTextSize();
        // ✨ 老版 API 没有这个方法，我们直接注释掉
        // mSubtitleView.setApplyEmbeddedFontSizes(false); 
    }

    private void initAttr(Context context, AttributeSet attrs, int defStyleAttr) {
        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.IjkVideoView, defStyleAttr, 0);
        try {
            mDefaultArtwork = a.getDrawable(R.styleable.IjkVideoView_default_artwork); // ✨ 老版 API 是这样获取 Drawable 的
            mKeepContentOnPlayerReset = a.getBoolean(R.styleable.IjkVideoView_keep_content_on_player_reset, mKeepContentOnPlayerReset);
        } finally {
            a.recycle();
        }
    }

    public IjkVideoView decode(int decode) {
        mCurrentDecode = decode;
        return this;
    }

    public IjkVideoView render(int render) {
        setRender(render);
        return this;
    }

    public void setPlayer(int type) {
        if (mCurrentPlayer == type) return;
        if (mPlayer != null) release();
        mCurrentPlayer = type;
        switch (type) {
            case PLAYER_SYS:
                mPlayer = new AndroidMediaPlayer().setListener(this);
                break;
            case PLAYER_IJK:
                mPlayer = new IjkMediaPlayer().setListener(this);
                break;
        }
    }

    public void addListener(IMediaPlayer.Listener listener) {
        mListener = listener;
    }

    public void setRender(int render) {
        mCurrentRender = render;
    }

    private void setRenderView(int render) {
        if (mRenderView != null) {
            bindSurfaceHolder(mPlayer, mSurfaceHolder);
            return;
        }
        switch (render) {
            case RENDER_TEXTURE_VIEW:
                setRenderView(new TextureRenderView(getContext()));
                break;
            case RENDER_SURFACE_VIEW:
                setRenderView(new SurfaceRenderView(getContext()));
                break;
        }
    }

    private void setRenderView(IRenderView renderView) {
        mRenderView = renderView;
        setResizeMode(mCurrentAspectRatio);
        mContentFrame.addView(mRenderView.getView(), 0, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mRenderView.addRenderCallback(this);
    }

    private void removeRenderView() {
        if (mRenderView == null) return;
        mContentFrame.removeView(mRenderView.getView());
        mRenderView.removeRenderCallback(this);
        mRenderView = null;
    }

    public void setResizeMode(int resizeMode) {
        mCurrentAspectRatio = resizeMode;
        if (mRenderView != null) mRenderView.setAspectRatio(resizeMode);
    }

    public void setWakeMode(int mode) {
        if (mPlayer != null) mPlayer.setWakeMode(getContext(), mode); // ✨ 增加一个非空判断，更稳健
    }

    public void setMediaSource(MediaSource source) {
        setMediaSource(source, 0);
    }

    public void setMediaSource(MediaSource source, long position) {
        setVideoURI(source.getUri(), source.getHeaders());
        mStartPosition = position;
    }

    private void setVideoURI(Uri uri, Map<String, String> headers) {
        if (!mKeepContentOnPlayerReset) removeRenderView();
        openVideo(uri, headers);
        requestLayout();
        invalidate();
    }

    private void openVideo(Uri uri, Map<String, String> headers) {
        try {
            if (mPlayer == null) setPlayer(PLAYER_IJK); // ✨ 如果播放器为空，默认创建一个
            mPlayer.reset();
            setOptions(uri); // ✨ setOptions 应该在 reset 之后
            setRenderView(mCurrentRender);
            mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            mPlayer.setDataSource(getContext(), uri, headers);
            
            // ✨✨✨ 核心修复：在这里为播放器绑定字幕监听器 ✨✨✨
            if (mPlayer instanceof IjkMediaPlayer) {
                ((IjkMediaPlayer) mPlayer).setOnTimedTextListener(mOnTimedTextListener);
            } else if (mPlayer instanceof AndroidMediaPlayer) {
                // AndroidMediaPlayer 需要不同的方式来处理字幕，这里暂时留空
            }

            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setScreenOnWhilePlaying(true);
            mPlayer.prepareAsync();
            mCurrentState = STATE_PREPARING;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to open content: " + uri, e);
            onError(mPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            mCurrentState = STATE_ERROR;
            mTargetState = STATE_ERROR;
        }
    }

    // ✨ setOptions 方法是原版里有的，帮你补上
    private void setOptions(Uri uri) {
        if (mPlayer instanceof IjkMediaPlayer) {
            IjkMediaPlayer ijk = (IjkMediaPlayer) mPlayer;
            ijk.setOption(format, "probesize", 250 * 1024);
            ijk.setOption(format, "analyzeduration", 3 * 1000 * 1000);
            ijk.setOption(player, "soundtouch", 1);
            ijk.setOption(player, "start-on-prepared", 0);
            ijk.setOption(player, "packet-buffering", 0);
            ijk.setOption(player, "framedrop", 1);
        }
    }

    private void bindSurfaceHolder(IMediaPlayer mp, IRenderView.ISurfaceHolder holder) {
        if (mp == null || holder == null) return;
        holder.bindToMediaPlayer(mp);
    }

    public void stop() {
        if (mPlayer == null) return;
        mPlayer.stop();
        reset();
    }

    public void release() {
        if (mPlayer == null) return;
        mCurrentPlayer = PLAYER_NONE;
        mPlayer.release();
        mPlayer = null;
        reset();
    }

    private void reset() {
        removeRenderView();
        // ✨✨✨ 核心修复：老版 SubtitleView 是用 onCues(null) 来清空字幕的 ✨✨✨
        if (mSubtitleView != null) mSubtitleView.onCues(null);
        mTargetState = STATE_IDLE;
        mCurrentState = STATE_IDLE;
        mCurrentBufferPosition = 0;
        mCurrentBufferPercentage = 0;
        mAudioManager.abandonAudioFocus(null);
    }

    @Override
    public void start() {
        if (isInPlaybackState()) {
            mPlayer.start();
            mCurrentState = STATE_PLAYING;
        }
        mTargetState = STATE_PLAYING;
    }

    @Override
    public void pause() {
        if (isInPlaybackState() && mPlayer.isPlaying()) {
            mPlayer.pause();
            mCurrentState = STATE_PAUSED;
        }
        mTargetState = STATE_PAUSED;
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) return (int) mPlayer.getDuration();
        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) return (int) mPlayer.getCurrentPosition();
        return 0;
    }

    @Override
    public void seekTo(int positionMs) {
        seekTo((long) positionMs);
    }

    public void seekTo(long positionMs) {
        if (isInPlaybackState()) { // ✨ 增加一个状态判断，更稳健
            onInfo(mPlayer, IMediaPlayer.MEDIA_INFO_BUFFERING_START, 0);
            mPlayer.seekTo(positionMs);
        }
    }

    public void setSpeed(float speed) {
        mCurrentSpeed = speed;
        if (isInPlaybackState()) mPlayer.setSpeed(speed);
    }

    public float getSpeed() {
        if (isInPlaybackState()) return mPlayer.getSpeed();
        return mCurrentSpeed;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public int getPlaybackState() {
        return mCurrentState;
    }

    // ✨✨✨ 核心修复：这里的返回值已经是老版的 SubtitleView 了 ✨✨✨
    public SubtitleView getSubtitleView() {
        return mSubtitleView;
    }

    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && mPlayer.isPlaying();
    }

    public long getBufferedPosition() {
        if (mPlayer != null) return mCurrentBufferPosition;
        return 0;
    }

    @Override
    public int getBufferPercentage() {
        if (mPlayer != null) return mCurrentBufferPercentage;
        return 0;
    }

    private boolean isInPlaybackState() {
        return (mPlayer != null && mCurrentState != STATE_ERROR && mCurrentState != STATE_IDLE && mCurrentState != STATE_PREPARING);
    }

    @Override
    public boolean canPause() {
        return mCanPause; // ✨ 还原哥哥原版的逻辑
    }

    @Override
    public boolean canSeekBackward() {
        return mCanSeekBack; // ✨ 还原哥哥原版的逻辑
    }

    @Override
    public boolean canSeekForward() {
        return mCanSeekForward; // ✨ 还原哥哥原版的逻辑
    }

    @Override
    public int getAudioSessionId() {
        if (mPlayer != null) return mPlayer.getAudioSessionId();
        return 0;
    }

    public boolean haveTrack(int type) {
        if (mPlayer == null) return false;
        ITrackInfo[] trackInfos = mPlayer.getTrackInfo();
        if (trackInfos == null) return false;
        for (ITrackInfo trackInfo : trackInfos) {
            if (trackInfo.getTrackType() == type) return true;
        }
        return false;
    }

    public ITrackInfo[] getTrackInfo() {
        if (mPlayer == null) return null;
        return mPlayer.getTrackInfo();
    }

    public int getSelectedTrack(int type) {
        if (mPlayer == null) return -1;
        return mPlayer.getSelectedTrack(type);
    }

    public void selectTrack(int track) {
        if (mPlayer == null) return;
        // ✨✨✨ 核心修复：切换字幕轨道时，清空旧的字幕 ✨✨✨
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mPlayer.selectTrack(track);
    }

    public void deselectTrack(int track) {
        if (mPlayer == null) return;
        // ✨✨✨ 核心修复：取消字幕轨道时，清空旧的字幕 ✨✨✨
        if (mSubtitleView != null) mSubtitleView.onCues(new ArrayList<>());
        mPlayer.deselectTrack(track);
    }

    // ✨ 删掉了 selectTrack(int type, int track) 和 deselectTrack(int type, int track) 这两个重复且逻辑复杂的方法，
    //    因为 IJKPlayer 的 API 只需要一个 track index 就能切换轨道。

    private void setPreferredTextLanguage() {
        if (mPlayer == null) return;
        ITrackInfo[] trackInfos = mPlayer.getTrackInfo();
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
        if (mPlayer == null) return;
        ITrackInfo[] trackInfos = mPlayer.getTrackInfo();
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
        mListener.onPrepared(mp);
        mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
        if (mVideoWidth != 0 && mVideoHeight != 0) {
            if (mRenderView != null) {
                mRenderView.setVideoSize(mVideoWidth, mVideoHeight);
                mRenderView.setVideoSampleAspectRatio(mVideoSarNum, mVideoSarDen);
            }
        }
        if (mStartPosition > 0) seekTo(mStartPosition);
        if (mTargetState == STATE_PLAYING) start();
        setPreferredTextLanguage();
        updateForCurrentTrackSelections();
        if (mCurrentSpeed > 0) setSpeed(mCurrentSpeed);
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        mCurrentState = STATE_ENDED;
        mTargetState = STATE_ENDED;
        mListener.onCompletion(mp);
    }
    
    // ✨✨✨ 【核心修复】这是唯一的、最完整的 Listener 实现 ✨✨✨

    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        mCurrentState = STATE_ERROR;
        mTargetState = STATE_ERROR;
        // ✨ 使用哥哥更健壮的写法
        return mListener.onError(mPlayer, what, extra);
    }

    @Override
    public void onInfo(IMediaPlayer mp, int what, int extra) {
        // ✨ 使用哥哥更完整的写法，加入了视频旋转的判断
        if (what == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED && mRenderView != null) {
            mRenderView.setVideoRotation(extra);
        }
        mListener.onInfo(mp, what, extra);
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
        mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
        mVideoSarNum = mp.getVideoSarNum();
        mVideoSarDen = mp.getVideoSarDen();
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
        mListener.onBufferingUpdate(mp, percent); // ✨ 确保回调给外部
        mCurrentBufferPercentage = percent;
    }

    // ✨ 补上这个带 long 参数的回调
    public void onBufferingUpdate(IMediaPlayer mp, long position) {
        mListener.onBufferingUpdate(mp, position);
        mCurrentBufferPosition = position;
    }

    @Override
    public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
        // ✨ 使用哥哥的写法，直接调用咱们修复好的 SubtitleParser
        if (mSubtitleView != null && text != null) {
            mSubtitleView.onCues(SubtitleParser.parse(text.getText()));
        }
    }
}
