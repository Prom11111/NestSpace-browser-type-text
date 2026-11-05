package com.coke.nestspace.container;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Log;
import com.coke.nestspace.container.intent.ContainerIntentResolver;
import com.coke.nestspace.container.vpm.ContainerPermissions;
import com.coke.nestspace.container.vpm.VirtualPackageRegistry;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PackageManager seen by code running inside a ContainerSession.
 *
 * The target APK is not installed on the host, so querying the host's
 * PackageManager for its package throws NameNotFoundException and implicit
 * Intents declared in its manifest cannot be resolved. This wrapper answers
 * those questions from the launch descriptor and delegates everything else to
 * the host's real PackageManager (system features, other installed apps,
 * permissions, installer, etc.).
 */
public class ContainerPackageManager extends DelegatingPackageManager {

    private static final String TAG = "ContainerPM";
    private static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";

    private final Context hostContext;
    private final ContainerLaunchSpec spec;
    private final Resources targetResources;
    private final ContainerIntentResolver intents;
    private final String targetPackage;
    private final String hostPackage;
    private final ApplicationInfo targetAppInfo;
    private final PackageInfo targetPackageInfo;
    private final VirtualPackageRegistry virtualPackages;
    private final ContainerPermissions permissions;
    private final String targetSignatureDigest;

    public ContainerPackageManager(
            Context hostContext,
            ContainerLaunchSpec spec,
            Resources targetResources,
            ContainerIntentResolver intents,
            VirtualPackageRegistry virtualPackages,
            ContainerPermissions permissions
    ) {
        super(hostContext.getPackageManager());
        this.hostContext = hostContext;
        this.spec = spec;
        this.targetResources = targetResources;
        this.intents = intents;
        this.targetPackage = spec.getPackageName();
        this.hostPackage = hostContext.getPackageName();
        this.targetAppInfo = spec.getApplicationInfo();
        this.targetPackageInfo = spec.getPackageInfo();
        this.virtualPackages = virtualPackages;
        this.permissions = permissions;
        this.targetSignatureDigest = signatureDigest(targetPackageInfo);
        Log.d(TAG, "target " + targetPackage + " version "
                + targetPackageInfo.versionName + " (" + targetPackageInfo.versionCode + ")");
    }

    /**
     * Hidden framework method called by ActivityThread while launching every
     * Activity. The base PackageManager implementation throws
     * "Not implemented"; returning the real sandbox package name keeps normal
     * (non-sandbox) activity launches working.
     */
    public String getSdkSandboxPackageName() {
        return "com.android.sdksandbox";
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException {
        if (isTarget(packageName)) {
            return copyPackageInfo();
        }
        if (isVirtualPackage(packageName)) {
            boolean full = needsComponentData(flags);
            PackageInfo copy = copyVirtualPackageInfo(packageName, full);
            if (copy != null) return copy;
        }
        return super.getPackageInfo(packageName, flags);
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags)
            throws NameNotFoundException {
        if (versionedPackage != null && isTarget(versionedPackage.getPackageName())) {
            return copyPackageInfo();
        }
        if (versionedPackage != null && isVirtualPackage(versionedPackage.getPackageName())) {
            boolean full = needsComponentData(flags);
            PackageInfo copy = copyVirtualPackageInfo(
                    versionedPackage.getPackageName(), full);
            if (copy != null) return copy;
        }
        return super.getPackageInfo(versionedPackage, flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        if (isTarget(packageName)) {
            return copyApplicationInfo();
        }
        if (isVirtualPackage(packageName)) {
            ApplicationInfo copy = copyVirtualApplicationInfo(
                    packageName, needsComponentData(flags));
            if (copy != null) return copy;
        }
        return super.getApplicationInfo(packageName, flags);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ActivityInfo info = intents.findActivity(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyActivityInfo(info);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyVirtualActivityInfo(info, component.getPackageName());
        }
        return super.getActivityInfo(component, flags);
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ServiceInfo info = intents.findService(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyServiceInfo(info);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ServiceInfo info = virtualPackages.findServiceInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyVirtualServiceInfo(info, component.getPackageName());
        }
        return super.getServiceInfo(component, flags);
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ProviderInfo info = findProviderByName(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyProviderInfo(info);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ProviderInfo info = virtualPackages.findProviderInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyVirtualProviderInfo(info, component.getPackageName());
        }
        return super.getProviderInfo(component, flags);
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags)
            throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ActivityInfo info = intents.findReceiver(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyActivityInfo(info);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findReceiverInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return copyVirtualActivityInfo(info, component.getPackageName());
        }
        return super.getReceiverInfo(component, flags);
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        Map<String, PackageInfo> byName = new LinkedHashMap<>();
        for (PackageInfo info : super.getInstalledPackages(flags)) {
            if (info != null && !isVirtualPackage(info.packageName)) {
                byName.put(info.packageName, info);
            }
        }
        byName.put(targetPackage, copyPackageInfo());
        for (String packageName : virtualPackages.allNames()) {
            PackageInfo info = copyVirtualPackageInfo(packageName, false);
            if (info != null) byName.put(packageName, info);
        }
        return new ArrayList<>(byName.values());
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        Map<String, ApplicationInfo> byName = new LinkedHashMap<>();
        for (ApplicationInfo info : super.getInstalledApplications(flags)) {
            if (info != null && !isVirtualPackage(info.packageName)) {
                byName.put(info.packageName, info);
            }
        }
        byName.put(targetPackage, copyApplicationInfo());
        for (String packageName : virtualPackages.allNames()) {
            ApplicationInfo info = copyVirtualApplicationInfo(packageName, false);
            if (info != null) byName.put(packageName, info);
        }
        return new ArrayList<>(byName.values());
    }

    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
        // Querying other virtual apps here would require parsing every APK
        // just to read requestedPermissions. Explicit per-package permission
        // checks still answer for any virtual app.
        List<PackageInfo> result = new ArrayList<>(super.getPackagesHoldingPermissions(permissions, flags));
        if (holdsAllPermissions(targetPackage, permissions)) result.add(copyPackageInfo());
        return result;
    }

    @Override
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        if (isTarget(packageName)) return Process.myUid();
        if (isVirtualPackage(packageName)) {
            Integer uid = virtualPackages.packageUid(packageName);
            if (uid != null) return uid;
        }
        return super.getPackageUid(packageName, flags);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
        if (isTarget(packageName) || isVirtualPackage(packageName)) {
            try {
                return super.getPackageGids(hostPackage, flags);
            } catch (NameNotFoundException e) {
                return new int[0];
            }
        }
        return super.getPackageGids(packageName, flags);
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        if (uid == Process.myUid()) return new String[]{targetPackage};
        String packageForUid = virtualPackages.packageForUid(uid);
        if (packageForUid != null) return new String[]{packageForUid};
        return super.getPackagesForUid(uid);
    }

    @Override
    public String getNameForUid(int uid) {
        if (uid == Process.myUid()) return targetPackage;
        String packageForUid = virtualPackages.packageForUid(uid);
        if (packageForUid != null) return packageForUid;
        return super.getNameForUid(uid);
    }

    @Override
    public int checkPermission(String permName, String packageName) {
        if (isVirtualPackage(packageName)) {
            return permissions.checkPackagePermission(packageName, permName);
        }
        return super.checkPermission(permName, packageName);
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String permission) {
        return false;
    }

    /**
     * PackageManager declares these with a "must override" body rather than
     * abstractly, and they are hidden from the SDK stubs, so the generated
     * delegation base did not cover them and we cannot use @Override.
     * Activity.requestPermissions still dispatches here at runtime; without
     * them the request throws "Not implemented. Must override in a subclass."
     */
    @SuppressWarnings("unused")
    public String getPermissionControllerPackageName() {
        return runCatchingPermissionController();
    }

    @SuppressWarnings("unused")
    public Intent buildRequestPermissionsIntent(String[] permissions) {
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        intent.putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, permissions);
        String controller = runCatchingPermissionController();
        if (controller != null) intent.setPackage(controller);
        return intent;
    }

    private String runCatchingPermissionController() {
        try {
            return (String) Class.forName("android.app.ApplicationPackageManager")
                    .getMethod("getPermissionControllerPackageName")
                    .invoke(base);
        } catch (Throwable t) {
            Log.w(TAG, "permission controller lookup failed", t);
            return "com.android.permissioncontroller";
        }
    }

    @Override
    public int checkSignatures(String packageName1, String packageName2) {
        if (isVirtualPackage(packageName1) || isVirtualPackage(packageName2)) {
            if (packageName1 != null && packageName1.equals(packageName2)) {
                return PackageManager.SIGNATURE_MATCH;
            }
            String first = signatureOf(packageName1);
            String second = signatureOf(packageName2);
            if (first != null && first.equals(second)) {
                return PackageManager.SIGNATURE_MATCH;
            }
            return PackageManager.SIGNATURE_NO_MATCH;
        }
        return super.checkSignatures(packageName1, packageName2);
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        String first = packageForUid(uid1);
        String second = packageForUid(uid2);
        if (first != null || second != null) {
            if (first != null && first.equals(second)) return PackageManager.SIGNATURE_MATCH;
            String digest1 = signatureOf(first);
            String digest2 = signatureOf(second);
            if (digest1 != null && digest1.equals(digest2)) {
                return PackageManager.SIGNATURE_MATCH;
            }
            return PackageManager.SIGNATURE_NO_MATCH;
        }
        return super.checkSignatures(uid1, uid2);
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        if (isVirtualPackage(packageName)) return COMPONENT_ENABLED_STATE_ENABLED;
        return super.getApplicationEnabledSetting(packageName);
    }

    @Override
    public int getComponentEnabledSetting(ComponentName component) {
        if (component != null && isVirtualPackage(component.getPackageName())) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        }
        return super.getComponentEnabledSetting(component);
    }

    @Override
    public void setComponentEnabledSetting(ComponentName component, int newState, int flags) {
        if (component != null && isVirtualPackage(component.getPackageName())) return;
        super.setComponentEnabledSetting(component, newState, flags);
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        if (isVirtualPackage(packageName)) return;
        super.setApplicationEnabledSetting(packageName, newState, flags);
    }

    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint) {
        if (isVirtualPackage(packageName)) return;
        super.setApplicationCategoryHint(packageName, categoryHint);
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        if (isVirtualPackage(packageName)) return null;
        return super.getInstallerPackageName(packageName);
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        if (isTarget(packageName)) return intents.launcherIntent();
        if (isVirtualPackage(packageName)) return virtualPackages.launchIntentFor(packageName);
        return super.getLaunchIntentForPackage(packageName);
    }

    @Override
    public Intent getLeanbackLaunchIntentForPackage(String packageName) {
        if (isTarget(packageName)) {
            Intent probe = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setPackage(targetPackage);
            ContainerIntentResolver.ActivityMatch match =
                    intents.resolveActivity(probe, 0);
            if (match == null) return null;
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(targetPackage, match.getInfo().name);
        }
        if (isVirtualPackage(packageName)) return null;
        return super.getLeanbackLaunchIntentForPackage(packageName);
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        if (info != null && isTarget(info.packageName)) return spec.getLabel();
        if (info != null && isVirtualPackage(info.packageName)) {
            CharSequence label = virtualPackages.labelFor(info.packageName);
            if (label != null) return label;
        }
        return super.getApplicationLabel(info);
    }

    @Override
    public Drawable getApplicationIcon(ApplicationInfo info) {
        if (info != null && isTarget(info.packageName)) {
            return targetDrawable(info.icon, true);
        }
        if (info != null && isVirtualPackage(info.packageName)) {
            // Do not parse an APK just to decorate an enumerated app list.
            Drawable icon = virtualPackages.cachedIconFor(info.packageName);
            if (icon != null) return icon;
            return super.getDefaultActivityIcon();
        }
        return super.getApplicationIcon(info);
    }

    @Override
    public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
        if (isTarget(packageName)) return targetDrawable(targetAppInfo.icon, true);
        if (isVirtualPackage(packageName)) {
            Drawable icon = virtualPackages.iconFor(packageName);
            return icon != null ? icon : super.getDefaultActivityIcon();
        }
        return super.getApplicationIcon(packageName);
    }

    @Override
    public Drawable getApplicationBanner(ApplicationInfo info) {
        if (info != null && isTarget(info.packageName)) {
            return targetDrawable(info.banner, info.icon != 0);
        }
        if (info != null && isVirtualPackage(info.packageName)) {
            Drawable icon = virtualPackages.cachedIconFor(info.packageName);
            return icon != null ? icon : super.getDefaultActivityIcon();
        }
        return super.getApplicationBanner(info);
    }

    @Override
    public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
        if (isTarget(packageName)) return targetDrawable(targetAppInfo.banner, targetAppInfo.icon != 0);
        if (isVirtualPackage(packageName)) {
            Drawable icon = virtualPackages.iconFor(packageName);
            return icon != null ? icon : super.getDefaultActivityIcon();
        }
        return super.getApplicationBanner(packageName);
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        if (info != null && isTarget(info.packageName)) {
            return targetDrawable(info.logo, false);
        }
        if (info != null && isVirtualPackage(info.packageName)) {
            Drawable icon = virtualPackages.cachedIconFor(info.packageName);
            return icon != null ? icon : super.getDefaultActivityIcon();
        }
        return super.getApplicationLogo(info);
    }

    @Override
    public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
        if (isTarget(packageName)) return targetDrawable(targetAppInfo.logo, false);
        if (isVirtualPackage(packageName)) {
            Drawable icon = virtualPackages.iconFor(packageName);
            return icon != null ? icon : super.getDefaultActivityIcon();
        }
        return super.getApplicationLogo(packageName);
    }

    @Override
    public Drawable getActivityIcon(ComponentName component) throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ActivityInfo info = intents.findActivity(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return targetDrawable(info.icon, true);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.icon, true);
        }
        return super.getActivityIcon(component);
    }

    @Override
    public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
        ContainerIntentResolver.ActivityMatch match = intents.resolveActivity(intent, 0);
        if (match != null) return targetDrawable(match.getInfo().icon, true);
        ComponentName component = intent != null ? intent.getComponent() : null;
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.icon, true);
        }
        return super.getActivityIcon(intent);
    }

    @Override
    public Drawable getActivityBanner(ComponentName component) throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ActivityInfo info = intents.findActivity(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return targetDrawable(info.banner, info.icon != 0);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.banner, info.icon != 0);
        }
        return super.getActivityBanner(component);
    }

    @Override
    public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
        ContainerIntentResolver.ActivityMatch match = intents.resolveActivity(intent, 0);
        if (match != null) return targetDrawable(match.getInfo().banner, match.getInfo().icon != 0);
        ComponentName component = intent != null ? intent.getComponent() : null;
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.banner, info.icon != 0);
        }
        return super.getActivityBanner(intent);
    }

    @Override
    public Drawable getActivityLogo(ComponentName component) throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            ActivityInfo info = intents.findActivity(component.getClassName());
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return targetDrawable(info.logo, false);
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.logo, false);
        }
        return super.getActivityLogo(component);
    }

    @Override
    public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
        ContainerIntentResolver.ActivityMatch match = intents.resolveActivity(intent, 0);
        if (match != null) return targetDrawable(match.getInfo().logo, false);
        ComponentName component = intent != null ? intent.getComponent() : null;
        if (component != null && isVirtualPackage(component.getPackageName())) {
            ActivityInfo info = virtualPackages.findActivityInfo(component);
            if (info == null) throw new NameNotFoundException(component.flattenToString());
            return virtualDrawable(component.getPackageName(), info.logo, false);
        }
        return super.getActivityLogo(intent);
    }

    @Override
    public Drawable getDrawable(String packageName, int resid, ApplicationInfo appInfo) {
        String pkg = appInfo != null && isVirtualPackage(appInfo.packageName)
                ? appInfo.packageName : packageName;
        if (isVirtualPackage(pkg)) {
            Resources resources = resourcesFor(pkg);
            if (resources == null) return null;
            {
                try {
                    return resources.getDrawable(resid, null);
                } catch (Throwable t) {
                    Log.w(TAG, "virtual drawable failed: 0x" + Integer.toHexString(resid), t);
                    return null;
                }
            }
        }
        return super.getDrawable(packageName, resid, appInfo);
    }

    @Override
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
        String pkg = appInfo != null && isVirtualPackage(appInfo.packageName)
                ? appInfo.packageName : packageName;
        if (isVirtualPackage(pkg)) {
            Resources resources = resourcesFor(pkg);
            if (resources == null) return null;
            {
                try {
                    return resources.getText(resid);
                } catch (Throwable t) {
                    Log.w(TAG, "virtual text failed: 0x" + Integer.toHexString(resid), t);
                    return null;
                }
            }
        }
        return super.getText(packageName, resid, appInfo);
    }

    @Override
    public android.content.res.XmlResourceParser getXml(
            String packageName, int resid, ApplicationInfo appInfo) {
        String pkg = appInfo != null && isVirtualPackage(appInfo.packageName)
                ? appInfo.packageName : packageName;
        if (isVirtualPackage(pkg)) {
            Resources resources = resourcesFor(pkg);
            if (resources == null) return null;
            {
                try {
                    return resources.getXml(resid);
                } catch (Throwable t) {
                    Log.w(TAG, "virtual xml failed: 0x" + Integer.toHexString(resid), t);
                    return null;
                }
            }
        }
        return super.getXml(packageName, resid, appInfo);
    }

    @Override
    public Resources getResourcesForApplication(ApplicationInfo appInfo)
            throws NameNotFoundException {
        if (appInfo != null && isTarget(appInfo.packageName)) return targetResources;
        if (appInfo != null && isVirtualPackage(appInfo.packageName)) {
            Resources resources = resourcesFor(appInfo.packageName);
            if (resources != null) return resources;
        }
        return super.getResourcesForApplication(appInfo);
    }

    @Override
    public Resources getResourcesForApplication(String packageName)
            throws NameNotFoundException {
        if (isTarget(packageName)) return targetResources;
        if (isVirtualPackage(packageName)) {
            Resources resources = resourcesFor(packageName);
            if (resources != null) return resources;
        }
        return super.getResourcesForApplication(packageName);
    }

    @Override
    public Resources getResourcesForActivity(ComponentName component)
            throws NameNotFoundException {
        if (component != null && isTarget(component.getPackageName())) {
            if (intents.findActivity(component.getClassName()) != null) return targetResources;
            throw new NameNotFoundException(component.flattenToString());
        }
        if (component != null && isVirtualPackage(component.getPackageName())) {
            if (virtualPackages.findActivityInfo(component) == null) {
                throw new NameNotFoundException(component.flattenToString());
            }
            Resources resources = resourcesFor(component.getPackageName());
            if (resources != null) return resources;
        }
        return super.getResourcesForActivity(component);
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        ContainerIntentResolver.ActivityMatch match =
                intents.resolveActivity(intent, flags);
        if (match != null) return intents.activityResolveInfo(match);
        List<ResolveInfo> virtual = virtualPackages.queryActivities(intent, flags);
        if (!virtual.isEmpty()) return virtual.get(0);
        if (isVirtualScoped(intent)) return null;
        ResolveInfo external = super.resolveActivity(intent, flags);
        return isVirtualResolveInfo(external) ? null : external;
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ContainerIntentResolver.ActivityMatch match
                : intents.queryActivities(intent, flags)) {
            result.add(intents.activityResolveInfo(match));
        }
        result.addAll(virtualPackages.queryActivities(intent, flags));
        if (!isVirtualScoped(intent)) {
            for (ResolveInfo info : super.queryIntentActivities(intent, flags)) {
                if (!isVirtualResolveInfo(info)) result.add(info);
            }
        }
        return result;
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        ContainerIntentResolver.ServiceMatch match = intents.resolveService(intent, flags);
        if (match != null) return intents.serviceResolveInfo(match);
        List<ResolveInfo> virtual = virtualPackages.queryServices(intent, flags);
        if (!virtual.isEmpty()) return virtual.get(0);
        if (isVirtualScoped(intent)) return null;
        ResolveInfo external = super.resolveService(intent, flags);
        return isVirtualResolveInfo(external) ? null : external;
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ContainerIntentResolver.ServiceMatch match
                : intents.queryServices(intent, flags)) {
            result.add(intents.serviceResolveInfo(match));
        }
        result.addAll(virtualPackages.queryServices(intent, flags));
        if (!isVirtualScoped(intent)) {
            for (ResolveInfo info : super.queryIntentServices(intent, flags)) {
                if (!isVirtualResolveInfo(info)) result.add(info);
            }
        }
        return result;
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags) {
        ProviderInfo local = intents.findProvider(authority);
        if (local != null) return copyProviderInfo(local);
        ProviderInfo virtual = virtualPackages.findProviderByAuthority(authority);
        if (virtual != null) return copyVirtualProviderInfo(virtual, virtual.packageName);
        ProviderInfo external = super.resolveContentProvider(authority, flags);
        return isVirtualProvider(external) ? null : external;
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        List<ProviderInfo> result = new ArrayList<>();
        for (ProviderInfo info : spec.getProviders()) {
            result.add(copyProviderInfo(info));
        }
        for (ProviderInfo info : virtualPackages.queryContentProviders(processName, uid, flags)) {
            result.add(copyVirtualProviderInfo(info, info.packageName));
        }
        for (ProviderInfo info : super.queryContentProviders(processName, uid, flags)) {
            if (!isVirtualProvider(info)) result.add(info);
        }
        return result;
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        ProviderInfo local = intents.providerMatches(intent);
        if (local != null) result.add(intents.providerResolveInfo(local));
        result.addAll(virtualPackages.queryProviders(intent, flags));
        if (!isVirtualScoped(intent)) {
            for (ResolveInfo info : super.queryIntentContentProviders(intent, flags)) {
                if (!isVirtualResolveInfo(info)) result.add(info);
            }
        }
        return result;
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<>();
        for (ContainerIntentResolver.ActivityMatch match
                : intents.queryReceivers(intent, flags)) {
            result.add(intents.activityResolveInfo(match));
        }
        result.addAll(virtualPackages.queryReceivers(intent, flags));
        if (!isVirtualScoped(intent)) {
            for (ResolveInfo info : super.queryBroadcastReceivers(intent, flags)) {
                if (!isVirtualResolveInfo(info)) result.add(info);
            }
        }
        return result;
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags)
            throws NameNotFoundException {
        PermissionInfo local = findVirtualPermission(name);
        if (local != null) return local;
        return super.getPermissionInfo(name, flags);
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
            throws NameNotFoundException {
        List<PermissionInfo> result = new ArrayList<>();
        for (PermissionInfo info : allDeclaredPermissions()) {
            if (group == null || group.equals(info.group)) result.add(info);
        }
        try {
            result.addAll(super.queryPermissionsByGroup(group, flags));
        } catch (NameNotFoundException ignored) {
            if (result.isEmpty()) throw ignored;
        }
        return result;
    }

    @Override
    public boolean isInstantApp() {
        return false;
    }

    @Override
    public boolean isInstantApp(String packageName) {
        if (isVirtualPackage(packageName)) return false;
        return super.isInstantApp(packageName);
    }

    @Override
    public boolean isPackageStopped(String packageName) throws NameNotFoundException {
        if (isVirtualPackage(packageName)) return false;
        return super.isPackageStopped(packageName);
    }

    @Override
    public int getTargetSdkVersion(String packageName) throws NameNotFoundException {
        if (isTarget(packageName)) return targetAppInfo.targetSdkVersion;
        if (isVirtualPackage(packageName)) {
            Integer sdk = virtualPackages.targetSdkOf(packageName);
            if (sdk != null) return sdk;
        }
        return super.getTargetSdkVersion(packageName);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageInfo(packageName, (int) flags.getValue());
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageInfo(versionedPackage, (int) flags.getValue());
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, ApplicationInfoFlags flags)
            throws NameNotFoundException {
        return getApplicationInfo(packageName, (int) flags.getValue());
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getActivityInfo(component, (int) flags.getValue());
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getServiceInfo(component, (int) flags.getValue());
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getProviderInfo(component, (int) flags.getValue());
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, ComponentInfoFlags flags)
            throws NameNotFoundException {
        return getReceiverInfo(component, (int) flags.getValue());
    }

    @Override
    public List<PackageInfo> getInstalledPackages(PackageInfoFlags flags) {
        return getInstalledPackages((int) flags.getValue());
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(ApplicationInfoFlags flags) {
        return getInstalledApplications((int) flags.getValue());
    }

    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, PackageInfoFlags flags) {
        return getPackagesHoldingPermissions(permissions, (int) flags.getValue());
    }

    @Override
    public int[] getPackageGids(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageGids(packageName, (int) flags.getValue());
    }

    @Override
    public int getPackageUid(String packageName, PackageInfoFlags flags)
            throws NameNotFoundException {
        return getPackageUid(packageName, (int) flags.getValue());
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, ResolveInfoFlags flags) {
        return resolveActivity(intent, (int) flags.getValue());
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, ResolveInfoFlags flags) {
        return queryIntentActivities(intent, (int) flags.getValue());
    }

    @Override
    public ResolveInfo resolveService(Intent intent, ResolveInfoFlags flags) {
        return resolveService(intent, (int) flags.getValue());
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, ResolveInfoFlags flags) {
        return queryIntentServices(intent, (int) flags.getValue());
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(
            Intent intent, ResolveInfoFlags flags) {
        return queryIntentContentProviders(intent, (int) flags.getValue());
    }

    @Override
    public ProviderInfo resolveContentProvider(
            String authority, ComponentInfoFlags flags) {
        return resolveContentProvider(authority, (int) flags.getValue());
    }

    @Override
    public List<ProviderInfo> queryContentProviders(
            String processName, int uid, ComponentInfoFlags flags) {
        return queryContentProviders(processName, uid, (int) flags.getValue());
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(
            Intent intent, ResolveInfoFlags flags) {
        return queryBroadcastReceivers(intent, (int) flags.getValue());
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(
            ComponentName caller, List<Intent> specifics, Intent target,
            ResolveInfoFlags flags) {
        Intent[] array = specifics == null
                ? null
                : specifics.toArray(new Intent[0]);
        return queryIntentActivityOptions(caller, array, target, (int) flags.getValue());
    }

    @Override
    public List<android.content.pm.SharedLibraryInfo> getSharedLibraries(
            PackageInfoFlags flags) {
        return getSharedLibraries((int) flags.getValue());
    }

    @Override
    public PackageInfo getPackageArchiveInfo(String archiveFilePath,
            PackageInfoFlags flags) {
        return base.getPackageArchiveInfo(archiveFilePath, (int) flags.getValue());
    }

    /*
     * Hidden PackageManager abstract methods.
     *
     * android.jar omits @hide methods, but the framework PackageManager class
     * still has abstract AsUser methods. Without these declarations a target
     * calling e.g. getPackageInfoAsUser would hit AbstractMethodError. They are
     * intentionally declared without @Override; the runtime dispatch matches by
     * name and descriptor. A container is single-user, so userId is ignored.
     */
    public PackageInfo getPackageInfoAsUser(
            String packageName, int flags, int userId) throws NameNotFoundException {
        return getPackageInfo(packageName, flags);
    }

    public PackageInfo getPackageInfoAsUser(
            String packageName, PackageInfoFlags flags, int userId) throws NameNotFoundException {
        return getPackageInfo(packageName, (int) flags.getValue());
    }

    public ApplicationInfo getApplicationInfoAsUser(
            String packageName, int flags, int userId) throws NameNotFoundException {
        return getApplicationInfo(packageName, flags);
    }

    public ApplicationInfo getApplicationInfoAsUser(
            String packageName, ApplicationInfoFlags flags, int userId)
            throws NameNotFoundException {
        return getApplicationInfo(packageName, (int) flags.getValue());
    }

    public int getPackageUidAsUser(String packageName, int userId)
            throws NameNotFoundException {
        return getPackageUid(packageName, 0);
    }

    public int getPackageUidAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        return getPackageUid(packageName, flags);
    }

    public int getPackageUidAsUser(
            String packageName, PackageInfoFlags flags, int userId)
            throws NameNotFoundException {
        return getPackageUid(packageName, (int) flags.getValue());
    }

    public List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        return getInstalledPackages(flags);
    }

    public List<PackageInfo> getInstalledPackagesAsUser(
            PackageInfoFlags flags, int userId) {
        return getInstalledPackages((int) flags.getValue());
    }

    public List<ApplicationInfo> getInstalledApplicationsAsUser(int flags, int userId) {
        return getInstalledApplications(flags);
    }

    public List<ApplicationInfo> getInstalledApplicationsAsUser(
            ApplicationInfoFlags flags, int userId) {
        return getInstalledApplications((int) flags.getValue());
    }

    public ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId) {
        return resolveActivity(intent, flags);
    }

    public ResolveInfo resolveActivityAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return resolveActivity(intent, (int) flags.getValue());
    }

    public List<ResolveInfo> queryIntentActivitiesAsUser(
            Intent intent, int flags, int userId) {
        return queryIntentActivities(intent, flags);
    }

    public List<ResolveInfo> queryIntentActivitiesAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return queryIntentActivities(intent, (int) flags.getValue());
    }

    public ResolveInfo resolveServiceAsUser(Intent intent, int flags, int userId) {
        return resolveService(intent, flags);
    }

    public ResolveInfo resolveServiceAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return resolveService(intent, (int) flags.getValue());
    }

    public List<ResolveInfo> queryIntentServicesAsUser(
            Intent intent, int flags, int userId) {
        return queryIntentServices(intent, flags);
    }

    public List<ResolveInfo> queryIntentServicesAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return queryIntentServices(intent, (int) flags.getValue());
    }

    public List<ResolveInfo> queryBroadcastReceiversAsUser(
            Intent intent, int flags, int userId) {
        return queryBroadcastReceivers(intent, flags);
    }

    public List<ResolveInfo> queryBroadcastReceiversAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return queryBroadcastReceivers(intent, (int) flags.getValue());
    }

    public List<ResolveInfo> queryIntentContentProvidersAsUser(
            Intent intent, int flags, int userId) {
        return queryIntentContentProviders(intent, flags);
    }

    public List<ResolveInfo> queryIntentContentProvidersAsUser(
            Intent intent, ResolveInfoFlags flags, int userId) {
        return queryIntentContentProviders(intent, (int) flags.getValue());
    }

    public ProviderInfo resolveContentProviderAsUser(
            String authority, int flags, int userId) {
        return resolveContentProvider(authority, flags);
    }

    public ProviderInfo resolveContentProviderAsUser(
            String authority, ComponentInfoFlags flags, int userId) {
        return resolveContentProvider(authority, (int) flags.getValue());
    }

    public List<android.content.pm.SharedLibraryInfo> getSharedLibrariesAsUser(
            int flags, int userId) {
        return getSharedLibraries(flags);
    }

    public List<android.content.pm.SharedLibraryInfo> getSharedLibrariesAsUser(
            PackageInfoFlags flags, int userId) {
        return getSharedLibraries((int) flags.getValue());
    }

    public Resources getResourcesForApplicationAsUser(String packageName, int userId)
            throws NameNotFoundException {
        return getResourcesForApplication(packageName);
    }

    public String[] getNamesForUids(int[] uids) {
        if (uids == null) return null;
        String[] names = new String[uids.length];
        for (int i = 0; i < uids.length; i++) names[i] = getNameForUid(uids[i]);
        return names;
    }

    public Intent getCarLaunchIntentForPackage(String packageName) {
        return getLaunchIntentForPackage(packageName);
    }

    public int getInstantAppCookieMaxSize() {
        return getInstantAppCookieMaxBytes();
    }

    private boolean isTarget(String packageName) {
        return packageName != null && targetPackage.equals(packageName);
    }

    /** True for the running package or any other app installed in the space. */
    private boolean isVirtualPackage(String packageName) {
        if (packageName == null) return false;
        return isTarget(packageName) || virtualPackages.isVirtualPackage(packageName);
    }

    /**
     * Only queries that ask for components/signatures/meta-data need the APK
     * parsed. Plain package/version queries use the cheap install index so
     * enumerating apps in the space does not parse 100MB+ APKs.
     */
    private static boolean needsComponentData(int flags) {
        int componentFlags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_SIGNATURES
                | PackageManager.GET_SIGNING_CERTIFICATES;
        return (flags & componentFlags) != 0;
    }

    private boolean isVirtualScoped(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component != null) return isVirtualPackage(component.getPackageName());
        return isVirtualPackage(intent.getPackage());
    }

    private boolean isVirtualResolveInfo(ResolveInfo info) {
        if (info == null) return false;
        if (isVirtualPackage(info.resolvePackageName)) return true;
        if (info.activityInfo != null && isVirtualPackage(info.activityInfo.packageName)) return true;
        if (info.serviceInfo != null && isVirtualPackage(info.serviceInfo.packageName)) return true;
        if (info.providerInfo != null && isVirtualPackage(info.providerInfo.packageName)) return true;
        return false;
    }

    private boolean isVirtualProvider(ProviderInfo info) {
        return info != null && (isVirtualPackage(info.packageName) ||
                isVirtualPackage(info.applicationInfo == null
                        ? null : info.applicationInfo.packageName));
    }

    private ProviderInfo findProviderByName(String className) {
        String wanted = intents.normalize(className);
        for (ProviderInfo info : spec.getProviders()) {
            if (wanted.equals(intents.normalize(info.name))) return info;
        }
        return null;
    }

    private ActivityInfo findReceiver(String className) {
        String wanted = intents.normalize(className);
        if (spec.getReceivers() == null) return null;
        for (ActivityInfo info : spec.getReceivers()) {
            if (wanted.equals(intents.normalize(info.name))) return info;
        }
        return null;
    }


    private PermissionInfo findVirtualPermission(String name) {
        if (name == null) return null;
        if (targetPackageInfo.permissions != null) {
            for (PermissionInfo info : targetPackageInfo.permissions) {
                if (name.equals(info.name)) return info;
            }
        }
        // Custom permissions are declared under the declaring package's
        // namespace, so only parse the matching app instead of all of them.
        for (String packageName : virtualPackages.allNames()) {
            if (!name.equals(packageName) && !name.startsWith(packageName + ".")) {
                continue;
            }
            for (PermissionInfo info : virtualPackages.declaredPermissions(packageName)) {
                if (name.equals(info.name)) return info;
            }
        }
        return null;
    }

    private List<PermissionInfo> allDeclaredPermissions() {
        List<PermissionInfo> result = new ArrayList<>();
        if (targetPackageInfo.permissions != null) {
            result.addAll(Arrays.asList(targetPackageInfo.permissions));
        }
        return result;
    }

    private boolean holdsAllPermissions(String packageName, String[] permissionNames) {
        if (permissionNames == null || permissionNames.length == 0) return true;
        for (String permission : permissionNames) {
            if (permissions.checkPackagePermission(packageName, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String signatureOf(String packageName) {
        if (packageName == null) return null;
        if (isTarget(packageName)) return targetSignatureDigest;
        return virtualPackages.signatureOf(packageName);
    }

    private String packageForUid(int uid) {
        if (uid == Process.myUid()) return targetPackage;
        return virtualPackages.packageForUid(uid);
    }

    private Drawable virtualDrawable(String packageName, int resourceId, boolean fallbackToIcon) {
        Resources resources = resourcesFor(packageName);
        if (resources != null) {
            if (resourceId != 0) {
                try {
                    return resources.getDrawable(resourceId, null);
                } catch (Throwable t) {
                    Log.w(TAG, "virtual drawable failed: 0x" + Integer.toHexString(resourceId), t);
                }
            }
            if (fallbackToIcon) {
                ApplicationInfo info = copyVirtualApplicationInfo(packageName, true);
                if (info != null && info.icon != 0 && info.icon != resourceId) {
                    try {
                        return resources.getDrawable(info.icon, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        Drawable icon = virtualPackages.iconFor(packageName);
        return icon != null ? icon : super.getDefaultActivityIcon();
    }

    private Resources resourcesFor(String packageName) {
        if (isTarget(packageName)) return targetResources;
        return virtualPackages.resourcesFor(packageName);
    }

    private Drawable targetDrawable(int resourceId, boolean fallbackToIcon) {
        if (resourceId != 0) {
            try {
                return targetResources.getDrawable(resourceId, null);
            } catch (Throwable t) {
                Log.w(TAG, "target drawable failed: 0x" + Integer.toHexString(resourceId), t);
            }
        }
        if (fallbackToIcon && resourceId != targetAppInfo.icon) {
            return targetDrawable(targetAppInfo.icon, false);
        }
        return super.getDefaultActivityIcon();
    }

    private PackageInfo copyVirtualPackageInfo(String packageName, boolean full) {
        PackageInfo source = full
                ? virtualPackages.findFullPackageInfo(packageName)
                : virtualPackages.findPackageInfo(packageName);
        if (source == null) return null;
        PackageInfo copy = cloneParcelable(source, PackageInfo.CREATOR);
        ApplicationInfo app = copyVirtualApplicationInfo(packageName, full);
        copy.applicationInfo = app;
        if (copy.activities != null) {
            for (int i = 0; i < copy.activities.length; i++) {
                copy.activities[i] = cloneParcelable(copy.activities[i], ActivityInfo.CREATOR);
                copy.activities[i].applicationInfo = app;
            }
        }
        if (copy.receivers != null) {
            for (int i = 0; i < copy.receivers.length; i++) {
                copy.receivers[i] = cloneParcelable(copy.receivers[i], ActivityInfo.CREATOR);
                copy.receivers[i].applicationInfo = app;
            }
        }
        if (copy.services != null) {
            for (int i = 0; i < copy.services.length; i++) {
                copy.services[i] = cloneParcelable(copy.services[i], ServiceInfo.CREATOR);
                copy.services[i].applicationInfo = app;
            }
        }
        if (copy.providers != null) {
            for (int i = 0; i < copy.providers.length; i++) {
                copy.providers[i] = cloneParcelable(copy.providers[i], ProviderInfo.CREATOR);
                copy.providers[i].applicationInfo = app;
            }
        }
        return copy;
    }

    private ApplicationInfo copyVirtualApplicationInfo(String packageName, boolean full) {
        ApplicationInfo source = full
                ? virtualPackages.findFullApplicationInfo(packageName)
                : virtualPackages.findApplicationInfo(packageName);
        if (source == null) return null;
        ApplicationInfo copy = cloneParcelable(source, ApplicationInfo.CREATOR);
        Integer uid = virtualPackages.packageUid(packageName);
        if (uid != null) copy.uid = uid;
        return copy;
    }

    private ActivityInfo copyVirtualActivityInfo(ActivityInfo info, String packageName) {
        ActivityInfo copy = cloneParcelable(info, ActivityInfo.CREATOR);
        copy.applicationInfo = copyVirtualApplicationInfo(packageName, true);
        return copy;
    }

    private ServiceInfo copyVirtualServiceInfo(ServiceInfo info, String packageName) {
        ServiceInfo copy = cloneParcelable(info, ServiceInfo.CREATOR);
        copy.applicationInfo = copyVirtualApplicationInfo(packageName, true);
        return copy;
    }

    private ProviderInfo copyVirtualProviderInfo(ProviderInfo info, String packageName) {
        ProviderInfo copy = cloneParcelable(info, ProviderInfo.CREATOR);
        copy.applicationInfo = copyVirtualApplicationInfo(packageName, true);
        return copy;
    }

    private PackageInfo copyPackageInfo() {
        PackageInfo copy = cloneParcelable(targetPackageInfo, PackageInfo.CREATOR);
        ApplicationInfo app = copyApplicationInfo();
        copy.applicationInfo = app;
        if (copy.activities != null) {
            for (int i = 0; i < copy.activities.length; i++) {
                copy.activities[i] = cloneParcelable(copy.activities[i], ActivityInfo.CREATOR);
                copy.activities[i].applicationInfo = app;
            }
        }
        if (copy.receivers != null) {
            for (int i = 0; i < copy.receivers.length; i++) {
                copy.receivers[i] = cloneParcelable(copy.receivers[i], ActivityInfo.CREATOR);
                copy.receivers[i].applicationInfo = app;
            }
        }
        if (copy.services != null) {
            for (int i = 0; i < copy.services.length; i++) {
                copy.services[i] = cloneParcelable(copy.services[i], ServiceInfo.CREATOR);
                copy.services[i].applicationInfo = app;
            }
        }
        if (copy.providers != null) {
            for (int i = 0; i < copy.providers.length; i++) {
                copy.providers[i] = cloneParcelable(copy.providers[i], ProviderInfo.CREATOR);
                copy.providers[i].applicationInfo = app;
            }
        }
        copy.applicationInfo.uid = Process.myUid();
        return copy;
    }

    private ApplicationInfo copyApplicationInfo() {
        ApplicationInfo copy = cloneParcelable(targetAppInfo, ApplicationInfo.CREATOR);
        copy.uid = Process.myUid();
        if (copy.sourceDir == null) copy.sourceDir = spec.getBaseApkPath();
        if (copy.publicSourceDir == null) copy.publicSourceDir = spec.getBaseApkPath();
        return copy;
    }

    private ActivityInfo copyActivityInfo(ActivityInfo info) {
        ActivityInfo copy = cloneParcelable(info, ActivityInfo.CREATOR);
        copy.applicationInfo = copyApplicationInfo();
        return copy;
    }

    private ServiceInfo copyServiceInfo(ServiceInfo info) {
        ServiceInfo copy = cloneParcelable(info, ServiceInfo.CREATOR);
        copy.applicationInfo = copyApplicationInfo();
        return copy;
    }

    private ProviderInfo copyProviderInfo(ProviderInfo info) {
        ProviderInfo copy = cloneParcelable(info, ProviderInfo.CREATOR);
        copy.applicationInfo = copyApplicationInfo();
        return copy;
    }

    @SuppressWarnings("deprecation")
    private static String signatureDigest(PackageInfo info) {
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                signatures = info.signingInfo.getSigningCertificateHistory();
            }
        }
        if (signatures == null || signatures.length == 0) signatures = info.signatures;
        if (signatures == null || signatures.length == 0) return null;
        try {
            // Must match ApkInspector.signatureDigest: sort by toCharsString,
            // then hash the raw signature bytes.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Signature[] sorted = signatures.clone();
            Arrays.sort(sorted, (a, b) -> a.toCharsString().compareTo(b.toCharsString()));
            for (Signature signature : sorted) {
                digest.update(signature.toByteArray());
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Throwable t) {
            Log.w(TAG, "compute signature digest failed", t);
            return null;
        }
    }

    private static <T extends Parcelable> T cloneParcelable(T value, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
