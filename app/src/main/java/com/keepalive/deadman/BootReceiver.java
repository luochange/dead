package com.keepalive.deadman;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 更新时间 / 应用升级后重建闹钟和看门狗。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Notify.ensureChannels(context);
        try {
            Scheduler.scheduleAll(context);
        } catch (IllegalStateException ignored) {
            // WorkManager 尚未初始化完成时忽略，下次启动会重建
        }
    }
}
