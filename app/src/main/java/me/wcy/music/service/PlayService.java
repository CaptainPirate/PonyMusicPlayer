package me.wcy.music.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import me.wcy.music.application.AppCache;
import me.wcy.music.constants.Actions;
import me.wcy.music.enums.PlayModeEnum;
import me.wcy.music.model.Music;
import me.wcy.music.receiver.NoisyAudioStreamReceiver;
import me.wcy.music.utils.MusicUtils;
import me.wcy.music.utils.Preferences;
import me.wcy.music.utils.SystemUtils;

/**
 * 音乐播放后台服务
 * Created by wcy on 2015/11/27.
 */
public class PlayService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "Service";
    private static final int NOTIFICATION_ID = 0x111;
    private static final long TIME_UPDATE = 100L;
    private List<Music> mMusicList;
    private MediaPlayer mPlayer = new MediaPlayer();
    private IntentFilter mNoisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    private NoisyAudioStreamReceiver mNoisyReceiver = new NoisyAudioStreamReceiver();
    private Handler mHandler = new Handler();
    private AudioManager mAudioManager;
    private NotificationManager mNotificationManager;
    private OnPlayerEventListener mListener;
    // 正在播放的歌曲[本地|网络]
    private Music mPlayingMusic;
    // 正在播放的本地歌曲的序号
    private int mPlayingPosition;
    private boolean isPausing;
    private boolean isPreparing;
    private long quitTimerRemain;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: " + getClass().getSimpleName());
        mMusicList = AppCache.getMusicList();
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mPlayer.setOnCompletionListener(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new PlayBinder();
    }

    public static void startCommand(Context context, String action) {
        Intent intent = new Intent(context, PlayService.class);
        intent.setAction(action);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case Actions.ACTION_MEDIA_PLAY_PAUSE:
                    playPause();
                    break;
                case Actions.ACTION_MEDIA_NEXT://耳机线控的下一首
                    next();
                    break;
                case Actions.ACTION_MEDIA_PREVIOUS://耳机线控的上一首
                    prev();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    public static boolean isRunning(Context context) {
        return SystemUtils.isServiceRunning(context, PlayService.class);
    }

    /**
     * 每次启动时扫描音乐
     */
    public void updateMusicList() {
        MusicUtils.scanMusic(this, mMusicList);
        if (mMusicList.isEmpty()) {
            return;
        }
        updatePlayingPosition();
        mPlayingMusic = (mPlayingMusic == null) ? mMusicList.get(mPlayingPosition) : mPlayingMusic;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        next();//播放完毕，开始下一曲
    }

    public void setOnPlayEventListener(OnPlayerEventListener listener) {//设置音乐播放进度监听器
        mListener = listener;
    }

    public void play(int position) {
        if (mMusicList.isEmpty()) {
            return;
        }

        if (position < 0) {//防错措施
            position = mMusicList.size() - 1;
        } else if (position >= mMusicList.size()) {
            position = 0;
        }

        mPlayingPosition = position;
        Music music = mMusicList.get(mPlayingPosition);
        Preferences.saveCurrentSongId(music.getId());//保存当前播放的歌曲ID
        play(music);
    }

    public void play(Music music) {
        mPlayingMusic = music;
        try {
            mPlayer.reset();
            mPlayer.setDataSource(music.getPath());
            mPlayer.prepareAsync();
            isPreparing = true;
            mPlayer.setOnPreparedListener(mPreparedListener);
            if (mListener != null) {
                mListener.onChange(music);//歌曲改变
            }
            updateNotification(music);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
            isPreparing = false;
            start();//准备完毕就播放
        }
    };

    public void playPause() {
        if (isPreparing()) {//判断是否在Preparing状态
            return;
        }

        if (isPlaying()) {//当前为播放状态，则暂停音乐
            pause();
        } else if (isPausing()) {//当前为暂停状态，恢复之前的播放
            resume();
        } else {
            play(getPlayingPosition());
        }
    }

    private void start() {
        mPlayer.start();
        isPausing = false;
        mHandler.post(mBackgroundRunnable);//更新播放时间
        updateNotification(mPlayingMusic);
        mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        registerReceiver(mNoisyReceiver, mNoisyFilter);
    }

    private void pause() {
        if (!isPlaying()) {
            return;
        }

        mPlayer.pause();
        isPausing = true;
        mHandler.removeCallbacks(mBackgroundRunnable);
        cancelNotification(mPlayingMusic);//取消前台机制，以便于内存不够用时回收
        mAudioManager.abandonAudioFocus(this);//放弃音频焦点
        unregisterReceiver(mNoisyReceiver);//注销来点时的广播
        if (mListener != null) {
            mListener.onPlayerPause();
        }
    }

    private void resume() {
        if (!isPausing()) {
            return;
        }

        start();//继续播放
        if (mListener != null) {
            mListener.onPlayerResume();
        }
    }

    /**
     * 根据模式播放下一曲
     */
    public void next() {
        if (mMusicList.isEmpty()) {
            return;
        }

        PlayModeEnum mode = PlayModeEnum.valueOf(Preferences.getPlayMode());
        switch (mode) {
            case SHUFFLE:
                mPlayingPosition = new Random().nextInt(mMusicList.size());//需改进，应该改为伪随机
                play(mPlayingPosition);
                break;
            case SINGLE:
                play(mPlayingPosition);
                break;
            case LOOP:
            default:
                play(mPlayingPosition + 1);
                break;
        }
    }

    /**
     * 根据模式播放上一曲
     */
    public void prev() {
        if (mMusicList.isEmpty()) {
            return;
        }

        PlayModeEnum mode = PlayModeEnum.valueOf(Preferences.getPlayMode());
        switch (mode) {
            case SHUFFLE:
                mPlayingPosition = new Random().nextInt(mMusicList.size());
                play(mPlayingPosition);
                break;
            case SINGLE:
                play(mPlayingPosition);
                break;
            case LOOP:
            default:
                play(mPlayingPosition - 1);
                break;
        }
    }

    /**
     * 跳转到指定的时间位置
     *
     * @param msec 时间
     */
    public void seekTo(int msec) {
        if (isPlaying() || isPausing()) {
            mPlayer.seekTo(msec);
            if (mListener != null) {
                mListener.onPublish(msec);
            }
        }
    }

    public boolean isPlaying() {
        return mPlayer != null && mPlayer.isPlaying();
    }

    public boolean isPausing() {
        return mPlayer != null && isPausing;
    }

    public boolean isPreparing() {
        return mPlayer != null && isPreparing;
    }

    /**
     * 获取正在播放的本地歌曲的序号
     */
    public int getPlayingPosition() {
        return mPlayingPosition;
    }

    /**
     * 获取正在播放的歌曲[本地|网络]
     */
    public Music getPlayingMusic() {
        return mPlayingMusic;
    }

    /**
     * 删除或下载歌曲后刷新正在播放的本地歌曲的序号，若删除当前播放中的歌曲，需要改进
     */
    public void updatePlayingPosition() {
        int position = 0;
        long id = Preferences.getCurrentSongId();
        for (int i = 0; i < mMusicList.size(); i++) {
            if (mMusicList.get(i).getId() == id) {
                position = i;
                break;
            }
        }
        mPlayingPosition = position;
        Preferences.saveCurrentSongId(mMusicList.get(mPlayingPosition).getId());
    }

    /**
     * 更新通知栏,启动前台机制，播放时调用
     */
    private void updateNotification(Music music) {
        mNotificationManager.cancel(NOTIFICATION_ID);
        startForeground(NOTIFICATION_ID, SystemUtils.buildNotification(this, music));
    }

    /**
     * 取消前台机制，暂停时调用
     */
    private void cancelNotification(Music music) {
        stopForeground(true);
        mNotificationManager.notify(NOTIFICATION_ID, SystemUtils.buildNotification(this, music));
    }

    private Runnable mBackgroundRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying() && mListener != null) {
                mListener.onPublish(mPlayer.getCurrentPosition());
            }
            mHandler.postDelayed(this, TIME_UPDATE);
        }
    };

    /**
     * 播放时请求焦点，如果请求成功，其他播放器将失去焦点，暂停播放，暂停时丢弃焦点。
     * @param focusChange
     */
    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                /*if (isPlaying()) {
                    pause();
                }*/
                pause();
                break;
        }
    }

    /**
     * 不看
     * @param milli
     */
    public void startQuitTimer(long milli) {
        stopQuitTimer();
        if (milli > 0) {
            quitTimerRemain = milli + DateUtils.SECOND_IN_MILLIS;
            mHandler.post(mQuitRunnable);
        } else {
            quitTimerRemain = 0;
            if (mListener != null) {
                mListener.onTimer(quitTimerRemain);
            }
        }
    }

    /**
     * 不看
     */
    private void stopQuitTimer() {
        mHandler.removeCallbacks(mQuitRunnable);
    }

    /**
     * 不看
     */
    private Runnable mQuitRunnable = new Runnable() {
        @Override
        public void run() {
            quitTimerRemain -= DateUtils.SECOND_IN_MILLIS;
            if (quitTimerRemain > 0) {
                if (mListener != null) {
                    mListener.onTimer(quitTimerRemain);
                }
                mHandler.postDelayed(this, DateUtils.SECOND_IN_MILLIS);
            } else {
                AppCache.clearStack();
                stop();
            }
        }
    };

    @Override
    public void onDestroy() {
        AppCache.setPlayService(null);
        super.onDestroy();
        Log.i(TAG, "onDestroy: " + getClass().getSimpleName());
    }

    public void stop() {
        pause();
        stopQuitTimer();
        mPlayer.reset();
        mPlayer.release();
        mPlayer = null;
        mNotificationManager.cancel(NOTIFICATION_ID);
        AppCache.setPlayService(null);
        stopSelf();
    }

    public class PlayBinder extends Binder {
        public PlayService getService() {
            return PlayService.this;
        }
    }
}
