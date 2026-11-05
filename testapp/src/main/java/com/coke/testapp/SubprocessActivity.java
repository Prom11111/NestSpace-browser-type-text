package com.coke.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;

public class SubprocessActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String process = getApplicationInfo() != null
            ? android.app.Application.getProcessName()
            : "?";
        String value = "subprocess pid=" + Process.myPid() + " process=" + process;
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(18);
        setContentView(text);
        android.util.Log.i("SubprocessTest", value);
    }
}
