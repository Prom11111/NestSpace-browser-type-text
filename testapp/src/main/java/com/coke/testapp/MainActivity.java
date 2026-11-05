package com.coke.testapp;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView text;
    private TextView info;
    private TextView result;
    private TextView serviceResult;
    private TextView pmResult;
    private TextView broadcastResult;
    private TextView permissionResult;
    private TextView jobResult;
    private TextView notificationResult;
    private TextView systemResult;
    private TextView vpmsResult;
    private SharedPreferences prefs;
    private BroadcastReceiver dynamicReceiver;
    private BroadcastReceiver systemReceiver;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        logLaunchIntent(intent);
    }

    private void logLaunchIntent(Intent intent) {
        if (intent == null) return;
        android.util.Log.i(
            "ShortcutTest",
            "launch action=" + intent.getAction() +
                " data=" + intent.getDataString() +
                " from=" + intent.getStringExtra("from_shortcut")
        );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        publishDynamicShortcut();
        logLaunchIntent(getIntent());

        text = findViewById(R.id.text);
        info = findViewById(R.id.info);
        result = findViewById(R.id.result);
        serviceResult = findViewById(R.id.service_result);
        pmResult = findViewById(R.id.pm_result);
        broadcastResult = findViewById(R.id.broadcast_result);
        permissionResult = findViewById(R.id.permission_result);
        jobResult = findViewById(R.id.job_result);
        notificationResult = findViewById(R.id.notification_result);
        systemResult = findViewById(R.id.system_result);
        vpmsResult = findViewById(R.id.vpms_result);
        prefs = getSharedPreferences("counter", MODE_PRIVATE);

        final Uri observeUri = Uri.parse("content://com.coke.testapp.testprovider/ping");
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                android.util.Log.i("ObserverTest", "observed " + uri + " self=" + selfChange);
            }

            @Override
            public void onChange(boolean selfChange) {
                android.util.Log.i("ObserverTest", "observed legacy self=" + selfChange);
            }
        };
        getContentResolver().registerContentObserver(observeUri, true, observer);
        android.util.Log.i("ObserverTest", "registered " + observeUri);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                startActivity(new Intent(this, SubprocessActivity.class));
                android.util.Log.i("SubprocessTest", "launched");
            } catch (Throwable t) {
                android.util.Log.e("SubprocessTest", "launch failed", t);
            }
        }, 6000);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            getContentResolver().notifyChange(observeUri, null);
            getContentResolver().notifyChange(
                Uri.parse("content://com.coke.testapp.testprovider/ping/child"), null);
            android.util.Log.i("ObserverTest", "notified");
        }, 4000);

        dynamicReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String value = "dynamic -> " + intent.getStringExtra("value");
                broadcastResult.setText(value);
                android.util.Log.i("TestBroadcast", value);
            }
        };
        IntentFilter dynamicFilter = new IntentFilter("com.coke.testapp.DYNAMIC");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                dynamicReceiver,
                dynamicFilter,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(dynamicReceiver, dynamicFilter);
        }

        findViewById(R.id.open_second).setOnClickListener(v ->
            startActivity(new Intent(this, SecondActivity.class))
        );
        findViewById(R.id.open_second_result).setOnClickListener(v ->
            startActivityForResult(new Intent(this, SecondActivity.class), 42)
        );
        findViewById(R.id.open_splash).setOnClickListener(v ->
            startActivity(new Intent(this, SplashActivity.class))
        );
        findViewById(R.id.open_implicit).setOnClickListener(v ->
            startActivity(new Intent("com.coke.testapp.OPEN_SECOND"))
        );
        findViewById(R.id.implicit_service).setOnClickListener(v -> {
            try {
                startService(new Intent("com.coke.testapp.PING"));
                String value = "implicit start -> " + TestService.last(this);
                serviceResult.setText(value);
                android.util.Log.i("TestService", value);
            } catch (Throwable t) {
                serviceResult.setText("implicit service error: " + t);
                android.util.Log.e("TestService", "implicit start failed", t);
            }
        });
        findViewById(R.id.pm_check).setOnClickListener(v -> checkPackageManager());
        findViewById(R.id.send_static_broadcast).setOnClickListener(v -> {
            Intent intent = new Intent("com.coke.testapp.PING_RECEIVER")
                .putExtra("value", 11);
            sendBroadcast(intent);
            broadcastResult.setText("sent static");
            android.util.Log.i("TestBroadcast", "sent static");
        });
        findViewById(R.id.send_dynamic_broadcast).setOnClickListener(v -> {
            Intent intent = new Intent("com.coke.testapp.DYNAMIC")
                .putExtra("value", "dynamic-ok");
            sendBroadcast(intent);
            broadcastResult.setText("sent dynamic");
            android.util.Log.i("TestBroadcast", "sent dynamic");
        });
        findViewById(R.id.send_ordered_broadcast).setOnClickListener(v -> {
            BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String value = "ordered result=" + getResultCode()
                        + " data=" + getResultData();
                    broadcastResult.setText(value);
                    android.util.Log.i("TestBroadcast", value);
                }
            };
            sendOrderedBroadcast(
                new Intent("com.coke.testapp.ORDERED").putExtra("value", 33),
                null,
                resultReceiver,
                null,
                100,
                "initial",
                null
            );
            android.util.Log.i("TestBroadcast", "sent ordered");
        });
        findViewById(R.id.send_sticky_broadcast).setOnClickListener(v -> {
            Intent intent = new Intent("com.coke.testapp.STICKY")
                .putExtra("value", "sticky-ok");
            sendStickyBroadcast(intent);
            Intent sticky = registerReceiver(
                null,
                new IntentFilter("com.coke.testapp.STICKY")
            );
            String value = "sticky=" + (sticky == null
                ? "null"
                : sticky.getStringExtra("value"));
            broadcastResult.setText(value);
            android.util.Log.i("TestBroadcast", value);
        });

        systemReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.coke.testapp.AUTOTEST".equals(intent.getAction())) {
                    runSelfTest();
                    return;
                }
                if ("com.coke.testapp.CRASH".equals(intent.getAction())) {
                    android.util.Log.e("TestCrash", "killing container process");
                    android.os.Process.killProcess(android.os.Process.myPid());
                    return;
                }
                if ("com.coke.testapp.JAVA_CRASH".equals(intent.getAction())) {
                    new Thread(() -> {
                        throw new RuntimeException("target-java-crash");
                    }, "target-crash-thread").start();
                    return;
                }
                StringBuilder value = new StringBuilder("system -> ")
                    .append(intent.getAction());
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    value.append(" level=")
                        .append(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                        .append("/")
                        .append(intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
                }
                value.append(" value=").append(intent.getStringExtra("value"));
                systemResult.setText(value);
                android.util.Log.i("TestSystemBroadcast", value.toString());
            }
        };
        IntentFilter systemFilter = new IntentFilter();
        systemFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        systemFilter.addAction("com.coke.nestspace.HOST_PING");
        systemFilter.addAction("com.coke.testapp.AUTOTEST");
        systemFilter.addAction("com.coke.testapp.CRASH");
        systemFilter.addAction("com.coke.testapp.JAVA_CRASH");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemReceiver, systemFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(systemReceiver, systemFilter);
        }

        findViewById(R.id.request_permission).setOnClickListener(v -> {
            int before = checkSelfPermission(Manifest.permission.CAMERA);
            permissionResult.setText("before=" + before);
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
        });

        findViewById(R.id.schedule_job).setOnClickListener(v -> {
            try {
                JobScheduler scheduler = getSystemService(JobScheduler.class);
                PersistableBundle extras = new PersistableBundle();
                extras.putString("payload", "hello");
                JobInfo job = new JobInfo.Builder(4242,
                    new ComponentName(this, TestJobService.class))
                    .setMinimumLatency(300)
                    .setExtras(extras)
                    .build();
                int result = scheduler.schedule(job);
                jobResult.setText("schedule=" + result
                    + " pending=" + (scheduler.getPendingJob(4242) != null));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    String executed = getSharedPreferences("job", MODE_PRIVATE)
                        .getString("result", "not-run");
                    boolean stillPending = scheduler.getPendingJob(4242) != null;
                    jobResult.setText("schedule=" + result + " pending=" + stillPending
                        + " => " + executed);
                    android.util.Log.i("TestJob", "result=" + executed
                        + " pending=" + stillPending);
                }, 1500);
            } catch (Throwable t) {
                jobResult.setText("job error: " + t);
                android.util.Log.e("TestJob", "schedule failed", t);
            }
        });

        findViewById(R.id.post_notification).setOnClickListener(v -> {
            try {
                NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(new NotificationChannel(
                        "test",
                        "Test channel",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ));
                }
                Intent open = new Intent(this, MainActivity.class);
                PendingIntent content = PendingIntent.getActivity(
                    this,
                    0,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "test")
                    : new Notification.Builder(this);
                Notification notification = builder
                    .setSmallIcon(R.drawable.ic_test)
                    .setContentTitle("Stage B Test")
                    .setContentText("notification-ok")
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build();
                manager.notify("tag1", 1001, notification);
                notificationResult.setText("notification posted tag1/1001");
                android.util.Log.i("TestNotify", "posted");
            } catch (Throwable t) {
                notificationResult.setText("notification error: " + t);
                android.util.Log.e("TestNotify", "post failed", t);
            }
        });

        findViewById(R.id.vpms_query).setOnClickListener(v -> {
            try {
                PackageManager pm = getPackageManager();
                StringBuilder value = new StringBuilder("myUid=")
                    .append(android.os.Process.myUid())
                    .append(" selfCamera=")
                    .append(checkSelfPermission(Manifest.permission.CAMERA))
                    .append("\nvirtual apps:");
                String firstOther = null;
                for (PackageInfo info : pm.getInstalledPackages(
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES)) {
                    ApplicationInfo app = info.applicationInfo;
                    if (app != null && app.sourceDir != null
                        && app.sourceDir.contains("/vspace/apps/")) {
                        value.append("\n").append(info.packageName)
                            .append(" uid=").append(app.uid);
                        if (!getPackageName().equals(info.packageName)
                            && firstOther == null) {
                            firstOther = info.packageName;
                        }
                    }
                }
                if (firstOther == null) {
                    value.append(" (none)");
                } else {
                    PackageInfo other = pm.getPackageInfo(
                        firstOther,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
                    ApplicationInfo otherApp = pm.getApplicationInfo(firstOther, 0);
                    Intent launch = pm.getLaunchIntentForPackage(firstOther);
                    value.append("\nother=").append(other.packageName)
                        .append(" uid=").append(pm.getPackageUid(firstOther, 0))
                        .append(" act=").append(
                            other.activities == null ? -1 : other.activities.length)
                        .append(" label=").append(pm.getApplicationLabel(otherApp))
                        .append("\nlaunch=")
                        .append(launch == null ? "null" : launch.getComponent())
                        .append("\ncamera=")
                        .append(pm.checkPermission(Manifest.permission.CAMERA, firstOther));
                }
                vpmsResult.setText(value);
                android.util.Log.i("TestVPMS", value.toString());
            } catch (Throwable t) {
                vpmsResult.setText("vpms error: " + t);
                android.util.Log.e("TestVPMS", "query failed", t);
            }
        });

        findViewById(R.id.start_service).setOnClickListener(v -> {
            startService(new Intent(this, TestService.class));
            serviceResult.setText("start -> " + TestService.last(this));
        });
        findViewById(R.id.bind_service).setOnClickListener(v ->
            bindService(
                new Intent(this, TestService.class),
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        String value = binder instanceof TestService.LocalBinder
                            ? ((TestService.LocalBinder) binder).ping()
                            : "unexpected-binder";
                        serviceResult.setText("bind -> " + value);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        serviceResult.setText("service disconnected");
                    }
                },
                BIND_AUTO_CREATE
            )
        );
        findViewById(R.id.query_provider).setOnClickListener(v -> {
            Uri uri = Uri.parse("content://com.coke.testapp.testprovider/hello");
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    serviceResult.setText("provider -> " + cursor.getString(0));
                } else {
                    serviceResult.setText("provider -> empty");
                }
            } catch (Throwable t) {
                serviceResult.setText("provider error: " + t);
            }
        });

        Button button = findViewById(R.id.button);
        button.setOnClickListener(v -> {
            int fileCount = readFileCounter() + 1;
            writeFileCounter(fileCount);
            prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).apply();
            render();
        });

        render();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) {
            String value = "result="
                + (grantResults.length > 0 ? grantResults[0] : "empty")
                + " after=" + checkSelfPermission(Manifest.permission.CAMERA);
            permissionResult.setText(value);
            android.util.Log.i("TestPermission", value);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42) {
            result.setText(
                "result=" + resultCode + " from=" +
                    (data == null ? "null" : data.getStringExtra("from"))
            );
        }
    }

    /** Driven by the host bridge test broadcast; clicks the real UI handlers. */
    private void runSelfTest() {
        android.util.Log.i("TestSelf", "runSelfTest");
        findViewById(R.id.request_permission).performClick();
        findViewById(R.id.schedule_job).performClick();
        findViewById(R.id.post_notification).performClick();
        findViewById(R.id.vpms_query).performClick();
    }

    private void checkPackageManager() {
        try {
            PackageManager pm = getPackageManager();
            String pkg = getPackageName();
            int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS;
            PackageInfo info = pm.getPackageInfo(pkg, flags);
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            ActivityInfo second = pm.getActivityInfo(
                new ComponentName(this, SecondActivity.class), 0);

            Intent implicit = new Intent("com.coke.testapp.OPEN_SECOND")
                .addCategory(Intent.CATEGORY_DEFAULT);
            ResolveInfo resolved = pm.resolveActivity(implicit, PackageManager.MATCH_DEFAULT_ONLY);
            List<ResolveInfo> list = pm.queryIntentActivities(
                implicit, PackageManager.MATCH_DEFAULT_ONLY);
            ResolveInfo viewResolved = pm.resolveActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("nestspace://second")),
                PackageManager.MATCH_DEFAULT_ONLY);
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(
                new Intent("com.coke.testapp.PING_RECEIVER")
                    .addCategory(Intent.CATEGORY_DEFAULT),
                0);
            ActivityInfo receiverInfo = pm.getReceiverInfo(
                new ComponentName(this, TestReceiver.class), 0);

            String flagsCheck = "";
            if (Build.VERSION.SDK_INT >= 33) {
                PackageInfo flagsInfo = pm.getPackageInfo(
                    pkg, PackageManager.PackageInfoFlags.of(flags));
                ApplicationInfo flagsApp = pm.getApplicationInfo(
                    pkg, PackageManager.ApplicationInfoFlags.of(0));
                flagsCheck = "\nflagsPkg=" + flagsInfo.packageName
                    + " flagsApp=" + flagsApp.packageName;
            }
            boolean selfListed = false;
            for (PackageInfo installed : pm.getInstalledPackages(0)) {
                if (pkg.equals(installed.packageName)) selfListed = true;
            }
            int selfUid = pm.getPackageUid(pkg, 0);

            pmResult.setText(
                "pm pkg=" + info.packageName + " ver=" + info.versionName
                    + "\napp=" + app.packageName + " uid=" + app.uid
                    + " selfUid=" + selfUid + " listed=" + selfListed
                    + "\nact=" + (info.activities == null ? -1 : info.activities.length)
                    + " svc=" + (info.services == null ? -1 : info.services.length)
                    + " prov=" + (info.providers == null ? -1 : info.providers.length)
                    + "\nsecond=" + second.name
                    + "\nresolve=" + (resolved == null ? "null" : resolved.activityInfo.name)
                    + " q=" + list.size()
                    + "\nview=" + (viewResolved == null ? "null" : viewResolved.activityInfo.name)
                    + " recv=" + receivers.size() + "/" + receiverInfo.name
                    + "\nlaunch=" + (launch == null ? "null" : launch.getComponent().getClassName())
                    + "\nlabel=" + pm.getApplicationLabel(app)
                    + flagsCheck
            );
            android.util.Log.i("TestPM", pmResult.getText().toString());
        } catch (Throwable t) {
            pmResult.setText("pm error: " + t);
            android.util.Log.e("TestPM", "pm check failed", t);
        }
    }

    @Override
    protected void onDestroy() {
        if (dynamicReceiver != null) {
            try {
                unregisterReceiver(dynamicReceiver);
            } catch (Throwable ignored) {
            }
        }
        if (systemReceiver != null) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private void render() {
        text.setText(R.string.hello);
        info.setText(
            "package=" + getPackageName() + "\n" +
                "files=" + getFilesDir().getAbsolutePath() + "\n" +
                "file count=" + readFileCounter() +
                "  prefs count=" + prefs.getInt("count", 0)
        );
    }

    private void publishDynamicShortcut() {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager == null) return;
            Intent intent = new Intent(this, MainActivity.class)
                .setAction("com.coke.testapp.DYNAMIC")
                .putExtra("from_shortcut", "dynamic");
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "dyn-test")
                .setShortLabel("Dynamic Test")
                .setLongLabel("Dynamic Test Shortcut")
                .setIntent(intent)
                .build();
            manager.setDynamicShortcuts(Collections.singletonList(shortcut));
            List<ShortcutInfo> current = manager.getDynamicShortcuts();
            android.util.Log.i(
                "ShortcutTest",
                "dynamic shortcut published, count=" + current.size() +
                    " ids=" + (current.isEmpty() ? "" : current.get(0).getId())
            );
        } catch (Throwable t) {
            android.util.Log.w("ShortcutTest", "dynamic shortcut failed", t);
        }
    }

    private File counterFile() {
        return new File(getFilesDir(), "counter.txt");
    }

    private int readFileCounter() {
        try (BufferedReader reader = new BufferedReader(new FileReader(counterFile()))) {
            String line = reader.readLine();
            return line == null ? 0 : Integer.parseInt(line.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void writeFileCounter(int value) {
        try (FileWriter writer = new FileWriter(counterFile())) {
            writer.write(Integer.toString(value));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
