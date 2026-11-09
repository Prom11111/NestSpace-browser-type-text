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

public class SingleTaskActivity extends Activity {
    private static final AtomicInteger CREATED = new AtomicInteger();
    private static final AtomicInteger NEW_INTENTS = new AtomicInteger();
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CREATED.incrementAndGet();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 80);
        text = new TextView(this);
        text.setTextSize(18);
        layout.addView(text);
        Button stack = new Button(this);
        stack.setText("Open standard stack");
        stack.setOnClickListener(v ->
            startActivity(new Intent(this, StackActivity.class))
        );
        layout.addView(
            stack,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        setContentView(layout);
        render("none");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        NEW_INTENTS.incrementAndGet();
        render(intent == null ? "none" : intent.getStringExtra("value"));
    }

    private void render(String value) {
        String result = "singleTask pid=" + Process.myPid()
            + " created=" + CREATED.get()
            + " newIntents=" + NEW_INTENTS.get()
            + " value=" + value;
        text.setText(result);
        android.util.Log.i("SingleTaskTest", result);
    }
}
