package com.coke.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class SecondActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_second);

        findViewById(R.id.open_third).setOnClickListener(v ->
            startActivity(new Intent(this, ThirdActivity.class))
        );
        findViewById(R.id.finish_second).setOnClickListener(v -> finish());
        findViewById(R.id.result_second).setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra("from", "second"));
            finish();
        });
    }
}
