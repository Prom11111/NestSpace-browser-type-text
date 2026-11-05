package com.coke.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Emulates the common splash pattern: start the real screen, then finish.
 * In a broken container the finish() would also close the host and the next
 * screen would never be visible.
 */
public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, SecondActivity.class));
        finish();
    }
}
