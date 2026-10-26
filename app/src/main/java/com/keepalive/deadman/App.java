package com.keepalive.deadman;

import android.app.Application;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.ensureChannels(this);
        try {
            Prefs p = Prefs.get(this);
            if (p.enabled()) {
                Scheduler.scheduleAll(this);
            } else {
                Scheduler.cancelAlarms(this);
            }
        } catch (IllegalStateException ignored) {
            // WorkManager 未就绪时忽略
        }
    }
}
