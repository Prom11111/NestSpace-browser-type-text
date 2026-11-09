package com.coke.testapp;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class TestWorkerService extends Service {
    static final String RESULT_FILE = "worker-service.txt";
    private final Binder binder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        record("service-created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        record("service-started");
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        record("service-bound");
        return binder;
    }

    private void record(String event) {
        String value = event
            + " pid=" + android.os.Process.myPid()
            + " process=" + ProcessInfo.name();
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), RESULT_FILE))) {
            writer.write(value);
        } catch (IOException ignored) {
        }
        android.util.Log.i("TestWorkerService", value);
    }
}
