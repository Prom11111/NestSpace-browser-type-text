package com.coke.nestspace.container;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.IBinder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

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
    void sendTargetBroadcast(String packageName, String instanceId, String targetProcess, in Intent intent);
    Bundle queryTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            in String[] projection,
            String selection,
            in String[] selectionArgs,
            String sortOrder);
    Uri insertTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            in ContentValues values);
    int updateTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            in ContentValues values,
            String selection,
            in String[] selectionArgs);
    int deleteTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            String selection,
            in String[] selectionArgs);
    int bulkInsertTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            in ContentValues[] values);
    String getTypeTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri);
    Bundle callTargetProvider(
            String packageName,
            String instanceId,
            String targetProcess,
            String authority,
            String method,
            String arg,
            in Bundle extras);
    ParcelFileDescriptor openTargetProviderFile(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            String mode);
    AssetFileDescriptor openTargetProviderAssetFile(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            String mode);
    AssetFileDescriptor openTargetProviderTypedAssetFile(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri uri,
            String mimeTypeFilter,
            in Bundle opts);
    void notifyTargetContentChange(
            String packageName,
            String instanceId,
            String targetProcess,
            in Uri[] uris,
            int flags);
    boolean deliverTargetActivityIntent(
            String packageName,
            String instanceId,
            String targetProcess,
            String activityClass,
            in Intent intent);
}
