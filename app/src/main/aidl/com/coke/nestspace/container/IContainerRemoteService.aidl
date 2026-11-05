package com.coke.nestspace.container;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * Bridge to a target Service hosted in a dedicated container slot process.
 * The returned IBinder is the target Service's own onBind result, not a
 * wrapper, so target-side AIDL interfaces keep working across processes.
 */
interface IContainerRemoteService {
    IBinder bindTargetService(String packageName, String instanceId, String targetProcess, in Intent intent);
    void unbindTargetService(String packageName, String instanceId, String targetProcess, in Intent intent);
    ComponentName startTargetService(String packageName, String instanceId, String targetProcess, in Intent intent, boolean foreground);
    void stopTargetService(String packageName, String instanceId, String targetProcess, in Intent intent);
}
