package com.coke.nestspace.container;

import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;

/**
 * Implements the package-private abstract provider hooks of ContentResolver.
 *
 * ContentResolver is abstract in the platform, but the hooks are hidden from
 * the public SDK stubs, so a Kotlin subclass compiles to an abstract class and
 * throws AbstractMethodError when Bilibili reaches acquireProvider through a
 * path that does not consult mWrapped. The signatures below are the framework
 * ones; android.content.IContentProvider is resolved from the boot class
 * loader at runtime. mWrapped handles normal data operations.
 */
public abstract class VirtualContentResolverBase extends ContentResolver {

    private final ContentResolver delegate;

    public VirtualContentResolverBase(Context context, ContentResolver delegate) {
        super(context);
        this.delegate = delegate;
    }

    private Object callDelegate(String method, Class<?>[] types, Object... args) {
        if (delegate == null || delegate == this) return null;
        try {
            java.lang.reflect.Method m =
                    ContentResolver.class.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(delegate, args);
        } catch (Throwable t) {
            android.util.Log.w("VirtualContentResolver", method + " failed", t);
            return null;
        }
    }

    protected IContentProvider acquireProvider(Context c, String name) {
        return (IContentProvider) callDelegate(
                "acquireProvider", new Class<?>[]{Context.class, String.class}, c, name);
    }

    public boolean releaseProvider(IContentProvider icp) {
        Object result = callDelegate(
                "releaseProvider", new Class<?>[]{IContentProvider.class}, icp);
        return result instanceof Boolean && (Boolean) result;
    }

    protected IContentProvider acquireUnstableProvider(Context c, String name) {
        return (IContentProvider) callDelegate(
                "acquireUnstableProvider", new Class<?>[]{Context.class, String.class}, c, name);
    }

    public boolean releaseUnstableProvider(IContentProvider icp) {
        Object result = callDelegate(
                "releaseUnstableProvider", new Class<?>[]{IContentProvider.class}, icp);
        return result instanceof Boolean && (Boolean) result;
    }

    public void unstableProviderDied(IContentProvider icp) {
        callDelegate("unstableProviderDied", new Class<?>[]{IContentProvider.class}, icp);
    }
}
