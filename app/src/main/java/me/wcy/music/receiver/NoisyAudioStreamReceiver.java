package me.wcy.music.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.wcy.music.constants.Actions;
import me.wcy.music.service.PlayService;

/**
 * 来电/耳机拔出时暂停播放,注意：播放时注册广播接收器，暂停时取消注册即可。
 * Created by wcy on 2016/1/23.
 */
public class NoisyAudioStreamReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PlayService.startCommand(context, Actions.ACTION_MEDIA_PLAY_PAUSE);
    }
}
