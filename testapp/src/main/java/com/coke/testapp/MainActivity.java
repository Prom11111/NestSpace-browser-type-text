package com.coke.testapp;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.app.AppOpsManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.session.MediaSession;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    private TextView nativeResult;
    private TextView storageResult;
    private TextView accountResult;
    private TextView processResult;
    private TextView attributionResult;
    private TextView servicesResult;
    private TextView imeResult;
    private SharedPreferences prefs;
    private View menuRoot;
    private View sectionDetail;
    private TextView sectionTitle;
    private String selectedSection;
    private BroadcastReceiver dynamicReceiver;
    private BroadcastReceiver systemReceiver;
    private ContentObserver workerProviderObserver;
    private final AtomicInteger workerObserverHits = new AtomicInteger();

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
        setupSections();
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
        nativeResult = findViewById(R.id.native_result);
        storageResult = findViewById(R.id.storage_result);
        accountResult = findViewById(R.id.account_result);
        processResult = findViewById(R.id.process_result);
        attributionResult = findViewById(R.id.attribution_result);
        servicesResult = findViewById(R.id.services_result);
        imeResult = findViewById(R.id.ime_result);
        NativeProbe.ensureLoaded();
        prefs = getSharedPreferences("counter", MODE_PRIVATE);

        workerProviderObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                int count = workerObserverHits.incrementAndGet();
                android.util.Log.i(
                    "WorkerObserver",
                    "change=" + uri + " count=" + count + " mainPid=" + android.os.Process.myPid()
                );
            }
        };
        getContentResolver().registerContentObserver(
            Uri.parse("content://com.coke.testapp.workerprovider"),
            true,
            workerProviderObserver
        );

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
        findViewById(R.id.run_compatibility_suite).setOnClickListener(v ->
            runCompatibilitySuite()
        );
        findViewById(R.id.send_process_broadcast).setOnClickListener(v -> {
            processResult.setText("worker broadcast sent");
            sendBroadcast(
                new Intent("com.coke.testapp.PROCESS_PING")
                    .setPackage(getPackageName())
                    .putExtra("value", "worker")
            );
        });
        findViewById(R.id.start_worker_service).setOnClickListener(v -> {
            try {
                startService(new Intent(this, TestWorkerService.class));
                processResult.setText("worker service started");
            } catch (Throwable t) {
                processResult.setText("worker service error: " + t);
            }
        });
        findViewById(R.id.open_subprocess_result).setOnClickListener(v ->
            startActivityForResult(
                new Intent(this, SubprocessActivity.class),
                43
            )
        );
        findViewById(R.id.open_single_top).setOnClickListener(v ->
            startActivity(new Intent(this, SingleTopActivity.class))
        );
        findViewById(R.id.test_system_attribution).setOnClickListener(v -> {
            String media;
            MediaSession mediaSession = null;
            try {
                mediaSession = new MediaSession(this, "testapp-media");
                mediaSession.setActive(true);
                media = "ok";
            } catch (Throwable t) {
                media = "error " + t.getClass().getSimpleName();
            } finally {
                if (mediaSession != null) mediaSession.release();
            }
            String appOps;
            try {
                AppOpsManager manager = getSystemService(AppOpsManager.class);
                manager.checkPackage(android.os.Process.myUid(), getPackageName());
                int mode = manager.unsafeCheckOpNoThrow(
                    "android:coarse_location",
                    android.os.Process.myUid(),
                    getPackageName()
                );
                appOps = "ok mode=" + mode;
            } catch (Throwable t) {
                appOps = "error " + t;
            }
            String value = "mediaSession=" + media + " appOps=" + appOps;
            attributionResult.setText(value);
            android.util.Log.i("TestAttribution", value);
        });
        findViewById(R.id.test_services).setOnClickListener(v -> {
            String network;
            try {
                ConnectivityManager manager =
                    getSystemService(ConnectivityManager.class);
                Network[] allNetworks = manager.getAllNetworks();
                Network activeNetwork = manager.getActiveNetwork();
                network = (activeNetwork == null ? "none" : "active")
                    + " all=" + allNetworks.length;
            } catch (Throwable t) {
                network = "error " + t;
            }
            String clipboard;
            try {
                ClipboardManager manager = getSystemService(ClipboardManager.class);
                manager.setPrimaryClip(
                    ClipData.newPlainText("nestspace-test", "clip-ok")
                );
                ClipData clip = manager.getPrimaryClip();
                clipboard = clip != null && clip.getItemCount() > 0
                    ? clip.getItemAt(0).coerceToText(this).toString()
                    : "empty";
            } catch (Throwable t) {
                clipboard = "error " + t.getClass().getSimpleName();
            }
            String value = "network=" + network + " clipboard=" + clipboard;
            servicesResult.setText(value);
            android.util.Log.i("TestServices", value);
        });
        findViewById(R.id.test_ime).setOnClickListener(v -> {
            try {
                EditText input = findViewById(R.id.ime_input);
                InputMethodManager manager =
                    getSystemService(InputMethodManager.class);
                List<InputMethodInfo> methods = manager.getInputMethodList();
                input.requestFocus();
                boolean shown = manager.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                );
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    String value = "imeList=" + methods.size() + " shown=" + shown;
                    imeResult.setText(value);
                    android.util.Log.i("TestIme", value);
                }, 800);
            } catch (Throwable t) {
                String value = "error " + t;
                imeResult.setText(value);
                android.util.Log.e("TestIme", value, t);
            }
        });
        findViewById(R.id.query_worker_provider).setOnClickListener(v -> {
            try (Cursor cursor = getContentResolver().query(
                Uri.parse("content://com.coke.testapp.workerprovider/hello"),
                null,
                null,
                null,
                null
            )) {
                String value = cursor != null && cursor.moveToFirst()
                    ? cursor.getString(0)
                    : "empty";
                processResult.setText(value);
                android.util.Log.i("TestWorkerProvider", value);
            } catch (Throwable t) {
                processResult.setText("worker provider error: " + t);
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
        } else if (requestCode == 43) {
            String value = "worker-result=" + resultCode + " from=" +
                (data == null ? "null" : data.getStringExtra("from"));
            result.setText(value);
            android.util.Log.i("SubprocessTest", value);
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

    private void runCompatibilitySuite() {
        showSection("compatibility");
        runStorageChecks();
        runAccountChecks();
        runNativeChecks();
        runProcessChecks();
        render();
    }

    private void setupSections() {
        menuRoot = findViewById(R.id.menu_root);
        sectionDetail = findViewById(R.id.section_detail);
        sectionTitle = findViewById(R.id.section_title);
        int[] menuIds = {
            R.id.menu_overview,
            R.id.menu_components,
            R.id.menu_system,
            R.id.menu_compatibility,
            R.id.menu_process,
            R.id.menu_identity
        };
        String[] tags = {
            "overview", "components", "system", "compatibility", "process", "identity"
        };
        for (int i = 0; i < menuIds.length; i++) {
            final String tag = tags[i];
            findViewById(menuIds[i]).setOnClickListener(v -> showSection(tag));
        }
        findViewById(R.id.back_to_menu).setOnClickListener(v -> showMenu());
        showMenu();
    }

    private void showSection(String tag) {
        String[] tags = {
            "overview", "components", "system", "compatibility", "process", "identity"
        };
        int[] contents = {
            R.id.tab_overview,
            R.id.tab_components,
            R.id.tab_system,
            R.id.tab_compatibility,
            R.id.tab_process,
            R.id.tab_identity
        };
        int selected = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(tag)) {
                selected = i;
                break;
            }
        }
        selectedSection = tags[selected];
        int[] titles = {
            R.string.tab_overview,
            R.string.tab_components,
            R.string.tab_system,
            R.string.tab_compatibility,
            R.string.tab_process,
            R.string.tab_identity
        };
        sectionTitle.setText(titles[selected]);
        for (int i = 0; i < contents.length; i++) {
            findViewById(contents[i]).setVisibility(i == selected ? View.VISIBLE : View.GONE);
        }
        menuRoot.setVisibility(View.GONE);
        sectionDetail.setVisibility(View.VISIBLE);
    }

    private void showMenu() {
        selectedSection = null;
        menuRoot.setVisibility(View.VISIBLE);
        sectionDetail.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (selectedSection != null) {
            showMenu();
            return;
        }
        super.onBackPressed();
    }

    private void runStorageChecks() {
        StringBuilder value = new StringBuilder();
        File credential = new File(getFilesDir(), "storage-probe.txt");
        value.append("credential=").append(writeProbe(credential))
            .append(" dir=").append(getDataDir().getAbsolutePath());

        try {
            Context device = createDeviceProtectedStorageContext();
            File deviceFile = new File(device.getFilesDir(), "storage-probe.txt");
            value.append("\ndevice=").append(writeProbe(deviceFile))
                .append(" protected=").append(device.isDeviceProtectedStorage())
                .append(" dir=").append(device.getDataDir().getAbsolutePath());
        } catch (Throwable t) {
            value.append("\ndevice=error ").append(t);
        }

        File external = getExternalFilesDir("compatibility");
        value.append("\nexternal=")
            .append(external == null
                ? "null"
                : writeProbe(new File(external, "storage-probe.txt")))
            .append(" dir=").append(external == null ? "null" : external.getAbsolutePath());
        storageResult.setText(value);
        android.util.Log.i("TestStorage", value.toString());
    }

    private void runAccountChecks() {
        try {
            AccountManager manager = AccountManager.get(this);
            Account account = new Account("container-user", getPackageName() + ".compat");
            boolean added = manager.addAccountExplicitly(account, "password", null);
            manager.setUserData(account, "probe", "user-data");
            manager.setAuthToken(account, "probe", "token-value");
            Account[] accounts = manager.getAccountsByType(account.type);
            String value = "added=" + added
                + " count=" + accounts.length
                + " password=" + manager.getPassword(account)
                + " token=" + manager.peekAuthToken(account, "probe")
                + " userData=" + manager.getUserData(account, "probe");
            accountResult.setText(value);
            android.util.Log.i("TestAccount", value);
        } catch (Throwable t) {
            accountResult.setText("account error: " + t);
            android.util.Log.e("TestAccount", "failed", t);
        }
    }

    private void runNativeChecks() {
        try {
            String environment = NativeProbe.nativeEnvironmentSummary();
            File targetFile = new File(getFilesDir(), "native-open.txt");
            writeProbe(targetFile, "native-open-ok");
            String virtualPath = "/data/user/0/" + getPackageName()
                + "/files/native-open.txt";
            String open = NativeProbe.nativeOpenPath(virtualPath);
            String fopen = NativeProbe.nativeFopenPath("/proc/self/status");
            String syscallOpen = NativeProbe.nativeSyscallOpenPath(virtualPath);
            String fdPath = NativeProbe.nativeFdPath("/proc/self/cmdline");
            String value = environment
                + "\nopen=" + oneLine(open)
                + "\nfopenStatus=" + oneLine(fopen).contains("Uid:")
                + "\nsyscallOpen=" + oneLine(syscallOpen)
                + "\nfdPath=" + fdPath;
            nativeResult.setText(value);
            android.util.Log.i("TestNative", value);
        } catch (Throwable t) {
            nativeResult.setText("native error: " + t);
            android.util.Log.e("TestNative", "failed", t);
        }
    }

    private void runProcessChecks() {
        deleteFile(TestProcessReceiver.RESULT_FILE);
        deleteFile(TestWorkerService.RESULT_FILE);
        processResult.setText("worker checks running...");
        sendBroadcast(
            new Intent("com.coke.testapp.PROCESS_PING")
                .setPackage(getPackageName())
                .putExtra("value", "worker")
        );
        startService(new Intent(this, TestWorkerService.class));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String broadcast = readText(new File(getFilesDir(), TestProcessReceiver.RESULT_FILE));
            String service = readText(new File(getFilesDir(), TestWorkerService.RESULT_FILE));
            String provider;
            String providerCrud;
            try (Cursor cursor = getContentResolver().query(
                Uri.parse("content://com.coke.testapp.workerprovider/hello"),
                null,
                null,
                null,
                null
            )) {
                provider = cursor != null && cursor.moveToFirst()
                    ? cursor.getString(0)
                    : "empty";
            } catch (Throwable t) {
                provider = "error " + t;
            }
            try {
                Uri workerUri = Uri.parse(
                    "content://com.coke.testapp.workerprovider/hello"
                );
                ContentValues values = new ContentValues();
                values.put("value", "inserted");
                Uri inserted = getContentResolver().insert(workerUri, values);
                int updated = getContentResolver().update(
                    workerUri,
                    values,
                    null,
                    null
                );
                int deleted = getContentResolver().delete(workerUri, null, null);
                ContentValues[] bulk = new ContentValues[]{
                    new ContentValues(values),
                    new ContentValues(values)
                };
                int bulkInserted = getContentResolver().bulkInsert(workerUri, bulk);
                Bundle call = getContentResolver().call(
                    workerUri,
                    "echo",
                    "from-main",
                    Bundle.EMPTY
                );
                String providerFile;
                try (ParcelFileDescriptor descriptor =
                         getContentResolver().openFileDescriptor(workerUri, "r")) {
                    providerFile = descriptor == null
                        ? "null"
                        : readFirstLine(
                            new ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        );
                }
                String providerAsset;
                try (AssetFileDescriptor asset =
                         getContentResolver().openAssetFileDescriptor(workerUri, "r")) {
                    providerAsset = asset == null
                        ? "null"
                        : readFirstLine(asset.createInputStream());
                }
                String workerType = getContentResolver().getType(workerUri);
                String providerTypedAsset;
                try (AssetFileDescriptor typed =
                         getContentResolver().openTypedAssetFileDescriptor(
                             workerUri,
                             workerType,
                             null
                         )) {
                    providerTypedAsset = typed == null
                        ? "null"
                        : readFirstLine(typed.createInputStream());
                }
                providerCrud = "insert=" + (inserted != null)
                    + " update=" + updated
                    + " delete=" + deleted
                    + " bulk=" + bulkInserted
                    + " type=" + workerType
                    + " file=" + providerFile
                    + " asset=" + providerAsset
                    + " typed=" + providerTypedAsset
                    + " call=" + (call == null ? "null" : call.getString("value"));
            } catch (Throwable t) {
                providerCrud = "error " + t;
            }
            String value = "broadcast=" + broadcast
                + "\nservice=" + service
                + "\nprovider=" + provider
                + "\nproviderCrud=" + providerCrud;
            processResult.setText(value);
            android.util.Log.i("TestProcess", value);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                String complete = value
                    + "\nobserverHits=" + workerObserverHits.get();
                processResult.setText(complete);
                android.util.Log.i("TestProcess", complete);
            }, 1200);
        }, 1800);
    }

    private String writeProbe(File file) {
        return writeProbe(file, "storage-ok");
    }

    private String writeProbe(File file, String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
            return readText(file).equals(content) ? "ok" : "read-mismatch";
        } catch (Throwable t) {
            return "error " + t.getClass().getSimpleName();
        }
    }

    private String readText(File file) {
        if (!file.isFile()) return "missing";
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (value.length() > 0) value.append('\n');
                value.append(line);
            }
            return value.toString();
        } catch (Throwable t) {
            return "error " + t;
        }
    }

    private String readFirstLine(InputStream input) {
        try (BufferedReader reader = new BufferedReader(
            new java.io.InputStreamReader(input)
        )) {
            String line = reader.readLine();
            return line == null ? "empty" : line;
        } catch (IOException error) {
            return "error " + error;
        }
    }

    private String oneLine(String value) {
        return value == null ? "null" : value.replace('\n', ' ').trim();
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
        if (workerProviderObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(workerProviderObserver);
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
