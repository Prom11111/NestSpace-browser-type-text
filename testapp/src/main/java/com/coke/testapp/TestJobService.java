package com.coke.testapp;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Minimal target JobService used to verify JobScheduler virtualization.
 *
 * It records execution in SharedPreferences so MainActivity can display the
 * result after scheduling a job with a short minimum latency.
 */
public class TestJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        SharedPreferences prefs = getSharedPreferences("job", MODE_PRIVATE);
        String payload = params.getExtras() == null
            ? ""
            : params.getExtras().getString("payload", "");
        String value = "job-ran id=" + params.getJobId()
            + " payload=" + payload;
        prefs.edit().putString("result", value).apply();
        android.util.Log.i("TestJob", value);
        // Exercise asynchronous completion: the virtual scheduler must learn
        // about jobFinished through the JobParameters callback.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            android.util.Log.i("TestJob", "jobFinished " + params.getJobId());
            jobFinished(params, false);
        }, 300);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        android.util.Log.i("TestJob", "job stopped " + params.getJobId());
        return false;
    }
}
