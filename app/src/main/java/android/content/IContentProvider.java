package android.content;

/**
 * Compile-time stand-in for the hidden framework interface.
 *
 * The real android.content.IContentProvider lives in the boot class loader and
 * always wins at runtime. This stub exists only so VirtualContentResolverBase
 * can declare the hidden ContentResolver abstract methods with the exact JVM
 * descriptors the framework expects. Do not add implementation code here.
 */
public interface IContentProvider {
}
