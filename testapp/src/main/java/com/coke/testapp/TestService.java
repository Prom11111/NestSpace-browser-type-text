package com.coke.testapp;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;

public class TestService extends Service {

    public class LocalBinder extends Binder {
        public String ping() {
            return "binder-ok";
        }
    }

    private final LocalBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        record("service-onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        record("service-onStartCommand");
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        record("service-onBind");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        record("service-onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        record("service-onDestroy");
        super.onDestroy();
    }

    private void record(String value) {
        getSharedPreferences("service", MODE_PRIVATE)
            .edit()
            .putString("last", value)
            .apply();
    }

    public static String last(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("service", Context.MODE_PRIVATE);
        return prefs.getString("last", "none");
    }
}
