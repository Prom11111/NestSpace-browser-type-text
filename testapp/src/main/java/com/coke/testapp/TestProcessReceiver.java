package com.coke.testapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class TestProcessReceiver extends BroadcastReceiver {
    static final String RESULT_FILE = "worker-broadcast.txt";

    @Override
    public void onReceive(Context context, Intent intent) {
        String value = "action=" + intent.getAction()
            + " pid=" + android.os.Process.myPid()
            + " process=" + ProcessInfo.name();
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), RESULT_FILE))) {
            writer.write(value);
        } catch (IOException ignored) {
        }
        android.util.Log.i("TestProcessReceiver", value);
    }
}
