package com.coke.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class StackActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 80);
        TextView text = new TextView(this);
        text.setTextSize(18);
        text.setText("standard stack pid=" + Process.myPid());
        layout.addView(text);
        Button back = new Button(this);
        back.setText("Return to singleTask");
        back.setOnClickListener(v -> startActivity(
            new Intent(this, SingleTaskActivity.class)
                .putExtra("value", "from-stack")
        ));
        layout.addView(
            back,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );
        setContentView(layout);
    }
}
