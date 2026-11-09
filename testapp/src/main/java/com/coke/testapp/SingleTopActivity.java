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

public class SingleTopActivity extends Activity {
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
        Button again = new Button(this);
        again.setText("Launch singleTop again");
        again.setOnClickListener(v -> startActivity(
            new Intent(this, SingleTopActivity.class)
                .putExtra("value", String.valueOf(NEW_INTENTS.get() + 1))
        ));
        layout.addView(
            again,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        setContentView(layout);
        render(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        NEW_INTENTS.incrementAndGet();
        render(intent);
    }

    private void render(Intent intent) {
        String value = intent == null ? "none" : intent.getStringExtra("value");
        String result = "singleTop pid=" + Process.myPid()
            + " created=" + CREATED.get()
            + " newIntents=" + NEW_INTENTS.get()
            + " value=" + value;
        text.setText(result);
        android.util.Log.i("SingleTopTest", result);
    }
}
