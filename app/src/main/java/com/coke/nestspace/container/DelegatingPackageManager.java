package com.coke.nestspace.container;

/**
 * Auto-generated delegation base: implements every abstract method of
 * android.content.pm.PackageManager by forwarding to the host PackageManager.
 * ContainerPackageManager overrides the target-aware subset.
 */
public class DelegatingPackageManager extends android.content.pm.PackageManager {

    protected final android.content.pm.PackageManager base;

    protected DelegatingPackageManager(android.content.pm.PackageManager base) {
        this.base = base;
    }

    @Override
    public void addPackageToPreferred(java.lang.String p0) {
        base.addPackageToPreferred(p0);
    }

    @Override
    public boolean addPermission(android.content.pm.PermissionInfo p0) {
        return base.addPermission(p0);
    }

    @Override
    public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) {
        return base.addPermissionAsync(p0);
    }

    @Override
    public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) {
        base.addPreferredActivity(p0, p1, p2, p3);
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return base.canRequestPackageInstalls();
    }

    @Override
    public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) {
        return base.canonicalToCurrentPackageNames(p0);
    }

    @Override
    public int checkPermission(java.lang.String p0, java.lang.String p1) {
        return base.checkPermission(p0, p1);
    }

    @Override
    public int checkSignatures(int p0, int p1) {
        return base.checkSignatures(p0, p1);
    }

    @Override
    public int checkSignatures(java.lang.String p0, java.lang.String p1) {
        return base.checkSignatures(p0, p1);
    }

    @Override
    public void clearInstantAppCookie() {
        base.clearInstantAppCookie();
    }

    @Override
    public void clearPackagePreferredActivities(java.lang.String p0) {
        base.clearPackagePreferredActivities(p0);
    }

    @Override
    public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) {
        return base.currentToCanonicalPackageNames(p0);
    }

    @Override
    public void extendVerificationTimeout(int p0, int p1, long p2) {
        base.extendVerificationTimeout(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityIcon(p0);
    }

    @Override
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityInfo(p0, p1);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getActivityLogo(p0);
    }

    @Override
    public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) {
        return base.getAllPermissionGroups(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) {
        return base.getApplicationBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getApplicationBanner(p0);
    }

    @Override
    public int getApplicationEnabledSetting(java.lang.String p0) {
        return base.getApplicationEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) {
        return base.getApplicationIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getApplicationIcon(p0);
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getApplicationInfo(p0, p1);
    }

    @Override
    public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) {
        return base.getApplicationLabel(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) {
        return base.getApplicationLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getApplicationLogo(p0);
    }

    @Override
    public android.content.pm.ChangedPackages getChangedPackages(int p0) {
        return base.getChangedPackages(p0);
    }

    @Override
    public int getComponentEnabledSetting(android.content.ComponentName p0) {
        return base.getComponentEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return base.getDefaultActivityIcon();
    }

    @Override
    public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return base.getDrawable(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int p0) {
        return base.getInstalledApplications(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int p0) {
        return base.getInstalledPackages(p0);
    }

    @Override
    public java.lang.String getInstallerPackageName(java.lang.String p0) {
        return base.getInstallerPackageName(p0);
    }

    @Override
    public byte[] getInstantAppCookie() {
        return base.getInstantAppCookie();
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return base.getInstantAppCookieMaxBytes();
    }

    @Override
    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getInstrumentationInfo(p0, p1);
    }

    @Override
    public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) {
        return base.getLaunchIntentForPackage(p0);
    }

    @Override
    public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) {
        return base.getLeanbackLaunchIntentForPackage(p0);
    }

    @Override
    public java.lang.String getNameForUid(int p0) {
        return base.getNameForUid(p0);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPackageGids(p0);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPackageGids(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInstaller getPackageInstaller() {
        return base.getPackageInstaller();
    }

    @Override
    public int getPackageUid(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPackageUid(p0, p1);
    }

    @Override
    public java.lang.String[] getPackagesForUid(int p0) {
        return base.getPackagesForUid(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) {
        return base.getPackagesHoldingPermissions(p0, p1);
    }

    @Override
    public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPermissionGroupInfo(p0, p1);
    }

    @Override
    public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getPermissionInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageManager.Property getProperty(java.lang.String p0, android.content.ComponentName p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getProperty(p0, p1);
    }

    @Override
    public android.content.pm.PackageManager.Property getProperty(java.lang.String p0, java.lang.String p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getProperty(p0, p1);
    }

    @Override
    public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) {
        return base.getPreferredActivities(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) {
        return base.getPreferredPackages(p0);
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getProviderInfo(p0, p1);
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getReceiverInfo(p0, p1);
    }

    @Override
    public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getResourcesForActivity(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getResourcesForApplication(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getResourcesForApplication(p0);
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.getServiceInfo(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) {
        return base.getSharedLibraries(p0);
    }

    @Override
    public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        return base.getSystemAvailableFeatures();
    }

    @Override
    public java.lang.String[] getSystemSharedLibraryNames() {
        return base.getSystemSharedLibraryNames();
    }

    @Override
    public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return base.getText(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) {
        return base.getUserBadgedDrawableForDensity(p0, p1, p2, p3);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) {
        return base.getUserBadgedIcon(p0, p1);
    }

    @Override
    public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) {
        return base.getUserBadgedLabel(p0, p1);
    }

    @Override
    public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return base.getXml(p0, p1, p2);
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0) {
        return base.hasSystemFeature(p0);
    }

    @Override
    public boolean hasSystemFeature(java.lang.String p0, int p1) {
        return base.hasSystemFeature(p0, p1);
    }

    @Override
    public boolean isInstantApp() {
        return base.isInstantApp();
    }

    @Override
    public boolean isInstantApp(java.lang.String p0) {
        return base.isInstantApp(p0);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) {
        return base.isPermissionRevokedByPolicy(p0, p1);
    }

    @Override
    public boolean isSafeMode() {
        return base.isSafeMode();
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) {
        return base.queryBroadcastReceivers(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) {
        return base.queryContentProviders(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) {
        return base.queryInstrumentation(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) {
        return base.queryIntentActivities(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) {
        return base.queryIntentActivityOptions(p0, p1, p2, p3);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) {
        return base.queryIntentContentProviders(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) {
        return base.queryIntentServices(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return base.queryPermissionsByGroup(p0, p1);
    }

    @Override
    public void removePackageFromPreferred(java.lang.String p0) {
        base.removePackageFromPreferred(p0);
    }

    @Override
    public void removePermission(java.lang.String p0) {
        base.removePermission(p0);
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) {
        return base.resolveActivity(p0, p1);
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) {
        return base.resolveContentProvider(p0, p1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) {
        return base.resolveService(p0, p1);
    }

    @Override
    public void setApplicationCategoryHint(java.lang.String p0, int p1) {
        base.setApplicationCategoryHint(p0, p1);
    }

    @Override
    public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) {
        base.setApplicationEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) {
        base.setComponentEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) {
        base.setInstallerPackageName(p0, p1);
    }

    @Override
    public void updateInstantAppCookie(byte[] p0) {
        base.updateInstantAppCookie(p0);
    }

    @Override
    public void verifyPendingInstall(int p0, int p1) {
        base.verifyPendingInstall(p0, p1);
    }

    /**
     * Present in the API 37 runtime but hidden from the compile-time stubs.
     * Declared without @Override so PackageManager subclasses still implement
     * the runtime abstract method.
     */
    public boolean shouldShowRequestPermissionRationale(java.lang.String permission) {
        return false;
    }

}
