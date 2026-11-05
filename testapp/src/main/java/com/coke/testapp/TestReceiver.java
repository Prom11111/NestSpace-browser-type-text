package com.coke.testapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class TestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(
            "TestReceiver",
            "received action=" + action
                + " value=" + intent.getIntExtra("value", -1)
                + " ordered=" + isOrderedBroadcast()
        );
        if ("com.coke.testapp.ORDERED".equals(action)) {
            setResultCode(7);
            setResultData("ordered");
        }
    }
}
