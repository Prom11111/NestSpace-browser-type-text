package com.coke.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

public class SubprocessActivity extends Activity {
    private static final AtomicInteger CREATED = new AtomicInteger();
    private static final AtomicInteger NEW_INTENTS = new AtomicInteger();
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CREATED.incrementAndGet();
        String process = getApplicationInfo() != null
            ? android.app.Application.getProcessName()
            : "?";
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 80);
        text = new TextView(this);
        text.setTextSize(18);
        layout.addView(text);
        Button again = new Button(this);
        again.setText("Launch worker singleTop again");
        again.setOnClickListener(v -> startActivity(
            new Intent(this, SubprocessActivity.class)
                .putExtra("value", String.valueOf(NEW_INTENTS.get() + 1))
        ));
        layout.addView(
            again,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        Button result = new Button(this);
        result.setText("Set worker result");
        result.setOnClickListener(v -> {
            setResult(
                RESULT_OK,
                new Intent().putExtra("from", "worker")
            );
            finish();
        });
        layout.addView(
            result,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        setContentView(layout);
        render(process, "none");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        NEW_INTENTS.incrementAndGet();
        String process = android.app.Application.getProcessName();
        String value = intent == null ? "none" : intent.getStringExtra("value");
        render(process, value);
    }

    private void render(String process, String value) {
        String result = "subprocess pid=" + Process.myPid()
            + " process=" + process
            + " created=" + CREATED.get()
            + " newIntents=" + NEW_INTENTS.get()
            + " value=" + value;
        text.setText(result);
        android.util.Log.i("SubprocessTest", result);
    }
}
