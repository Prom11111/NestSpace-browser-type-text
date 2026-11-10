package com.coke.nestspace.container

import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * Stabilizes target FragmentPagerAdapters before every changed layout.
 *
 * Fresh target accounts can queue the current StaggeredFeedTab add twice
 * during ViewPager's first populate. AndroidX then rejects the second add with
 * "Fragment already added". Installing the current Fragment once under the
 * exact AndroidX tag makes populate reuse it instead of queueing another add.
 *
 * The repair runs immediately before the target is measured. It tracks the
 * adapter's fragment identities so returning from a page, restoring state or
 * rebuilding tabs can be repaired again without rescanning an unchanged
 * adapter on every frame. Duplicate item IDs and repeated Fragment instances
 * are pruned from equal-sized data lists.
 */
internal object TargetFragmentPagerHook {
    private const val TAG = "TargetFragmentPagerHook"
    private const val VIEW_PAGER_CLASS = "androidx.viewpager.widget.ViewPager"
    private const val FRAGMENT_PAGER_ADAPTER_CLASS =
        "androidx.fragment.app.FragmentPagerAdapter"
    private const val FRAGMENT_CLASS = "androidx.fragment.app.Fragment"

    private val normalizedAdapters =
        Collections.synchronizedMap(WeakHashMap<Any, AdapterSnapshot>())
    private val trackedViews =
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    fun normalize(root: View?) {
        root ?: return
        visit(root)
    }

    private fun visit(view: View) {
        if (hasType(view.javaClass, VIEW_PAGER_CLASS)) {
            normalizeAdapter(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visit(view.getChildAt(index))
            }
        }
    }

    private fun normalizeAdapter(view: View) {
        val adapter = runCatching {
            view.javaClass.getMethod("getAdapter").invoke(view)
        }.getOrNull() ?: return
        if (!hasType(adapter.javaClass, FRAGMENT_PAGER_ADAPTER_CLASS)) {
            return
        }
        trackedViews[view] = true
        repairAdapterFragmentManager(view, adapter)

        val listFields = adapterFields(adapter.javaClass)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val list = field.get(adapter) as? MutableList<Any?> ?: return@runCatching null
                    FieldList(field.name, list, list.size)
                }.getOrNull()
            }
        val fragmentLists = listFields.filter { candidate ->
            candidate.values.any { value ->
                value != null && hasType(value.javaClass, FRAGMENT_CLASS)
            }
        }
        repairPendingFragmentTransactions(adapter)
        if (fragmentLists.isEmpty()) return
        repairAdapterFragmentManagerFromFragments(adapter, fragmentLists)
        reconcileAddedFragments(adapter, fragmentLists)
        val adapterCount = runCatching {
            adapter.javaClass.getMethod("getCount").invoke(adapter) as Int
        }.getOrNull()
        val primary = fragmentLists.firstOrNull { it.name == "mFragments" }
            ?: fragmentLists.firstOrNull { adapterCount != null && it.values.size == adapterCount }
            ?: fragmentLists.first()
        val fragments = primary.values
        val primaryFragmentSignature = fragments.map { value ->
            value?.let(System::identityHashCode) ?: 0
        }
        val fragmentSignature = fragmentLists.flatMap { candidate ->
            listOf(-1) + candidate.values.map { value ->
                value?.let(System::identityHashCode) ?: 0
            }
        }
        val itemIdMethod = adapter.javaClass.methods.firstOrNull {
            it.name == "getItemId" && it.parameterCount == 1
        } ?: return
        val itemIds = fragments.indices.map { index ->
            runCatching { itemIdMethod.invoke(adapter, index) }.getOrNull()
        }
        val currentItem = currentItem(view)
        val snapshot = AdapterSnapshot(fragmentSignature, itemIds, currentItem)
        val adapterChanged = normalizedAdapters[adapter] != snapshot
        if (!adapterChanged) return
        val duplicateIndexes = duplicatePositions(itemIds, primaryFragmentSignature)
        if (duplicateIndexes.isNotEmpty()) {
            val originalSize = fragments.size
            duplicateIndexes.asReversed().forEach { duplicateIndex ->
                fragmentLists.forEach { candidate ->
                    if (candidate.originalSize == originalSize &&
                        duplicateIndex < candidate.values.size
                    ) {
                        runCatching { candidate.values.removeAt(duplicateIndex) }
                    }
                }
            }
            Log.d(
                TAG,
                "removed duplicate Fragment positions=${duplicateIndexes.asReversed()} " +
                    "adapter=${adapter.javaClass.name}"
            )
        }

        preAttachVisibleFragments(view, adapter, fragments)
        normalizedAdapters[adapter] = AdapterSnapshot(
            fragmentLists.flatMap { candidate ->
                listOf(-1) + candidate.values.map { value ->
                    value?.let(System::identityHashCode) ?: 0
                }
            },
            fragments.indices.map { index ->
                runCatching { itemIdMethod.invoke(adapter, index) }.getOrNull()
            },
            currentItem
        )
    }

    internal fun duplicatePositions(
        itemIds: List<Any?>,
        fragmentIdentities: List<Int>
    ): List<Int> {
        val seenItemIds = HashSet<Any>()
        val seenFragments = HashSet<Int>()
        val duplicates = ArrayList<Int>()
        itemIds.indices.forEach { index ->
            val duplicateItemId = itemIds[index]?.let { !seenItemIds.add(it) } == true
            val fragmentIdentity = fragmentIdentities.getOrNull(index) ?: 0
            val duplicateFragment = fragmentIdentity != 0 &&
                !seenFragments.add(fragmentIdentity)
            if (duplicateItemId || duplicateFragment) duplicates += index
        }
        return duplicates
    }

    /**
     * ViewPager populates the current page and its offscreen neighbours as one
     * batch. Pre-installing the same window under the exact FragmentPagerAdapter
     * tags makes instantiateItem queue ATTACH operations and prevents a second
     * ADD for the same Fragment instance before finishUpdate commits.
     */
    private fun preAttachVisibleFragments(
        view: View,
        adapter: Any,
        fragments: List<Any?>
    ) {
        if (view.id == View.NO_ID || fragments.isEmpty()) return
        val fragmentManager = adapterTypeField(adapter.javaClass, "mFragmentManager")
            ?.get(adapter) ?: return
        val itemIdMethod = adapter.javaClass.methods.firstOrNull {
            it.name == "getItemId" && it.parameterCount == 1
        } ?: return
        val current = runCatching {
            view.javaClass.getMethod("getCurrentItem").invoke(view) as Int
        }.getOrNull() ?: return
        if (current !in fragments.indices) return
        val offscreen = runCatching {
            view.javaClass.getMethod("getOffscreenPageLimit").invoke(view) as Int
        }.getOrDefault(1).coerceAtLeast(1)
        val first = (current - offscreen).coerceAtLeast(0)
        val last = (current + offscreen).coerceAtMost(fragments.lastIndex)
        val transaction = runCatching {
            fragmentManager.javaClass
                .getMethod("beginTransaction")
                .invoke(fragmentManager)
        }.onFailure {
            Log.w(TAG, "beginTransaction failed adapter=${adapter.javaClass.name}", it)
        }.getOrNull() ?: return
        var installed = 0
        for (position in first..last) {
            val itemId = runCatching {
                itemIdMethod.invoke(adapter, position)
            }.getOrNull() ?: continue
            val tag = "android:switcher:${view.id}:$itemId"
            val existing = runCatching {
                findFragmentByTag(fragmentManager, tag)
            }.getOrNull()
            if (existing != null) continue

            val fragment = fragments[position] ?: continue
            when (prepareFragmentForAdd(fragmentManager, fragment, tag, view.id)) {
                FragmentAttachment.PRESENT, FragmentAttachment.BLOCKED -> continue
                FragmentAttachment.ADDABLE -> Unit
            }
            val fragmentType =
                typeByName(fragment.javaClass, FRAGMENT_CLASS) ?: continue
            runCatching {
                transaction.javaClass
                    .getMethod(
                        "add",
                        Int::class.javaPrimitiveType,
                        fragmentType,
                        String::class.java
                    )
                    .invoke(transaction, view.id, fragment, tag)
                installed++
            }.onFailure {
                Log.w(TAG, "pre-attach Fragment failed position=$position tag=$tag", it)
            }
        }
        if (installed == 0) return
        runCatching {
            transaction.javaClass
                .getMethod("commitNowAllowingStateLoss")
                .invoke(transaction)
            Log.d(
                TAG,
                "pre-attached ${installed} Fragment(s) current=$current " +
                    "window=$first..$last"
            )
        }.onFailure {
            Log.w(TAG, "pre-attach Fragment commit failed current=$current", it)
        }
    }

    /**
     * Tab fragments can move an existing ViewPager to a newly created parent
     * Fragment while its FragmentPagerAdapter keeps the old manager reference.
     * Looking up the new container tag then fails even though the Fragment is
     * already added elsewhere, causing a duplicate ADD. Resolve the manager
     * from the ViewPager's owning Fragment before any fragment work.
     */
    private fun repairAdapterFragmentManager(view: View, adapter: Any) {
        val field = adapterTypeField(adapter.javaClass, "mFragmentManager") ?: return
        val current = runCatching { field.get(adapter) }.getOrNull() ?: return
        val resolved = resolveOwnerFragmentManager(view) ?: return
        if (resolved === current) return
        runCatching {
            field.set(adapter, resolved)
            adapterTypeField(adapter.javaClass, "mCurTransaction")
                ?.set(adapter, null)
            normalizedAdapters.remove(adapter)
            Log.d(
                TAG,
                "repaired FragmentManager adapter=${adapter.javaClass.name} " +
                    "old=${current.javaClass.name} new=${resolved.javaClass.name}"
            )
        }.onFailure {
            Log.w(TAG, "repair FragmentManager failed adapter=${adapter.javaClass.name}", it)
        }
    }

    private fun resolveOwnerFragmentManager(view: View): Any? {
        val managerClass = runCatching {
            Class.forName(
                "androidx.fragment.app.FragmentManager",
                false,
                view.javaClass.classLoader
            )
        }.getOrNull() ?: return null
        val findFragment = managerClass.methods.firstOrNull {
            it.name == "findFragment" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == View::class.java
        } ?: return null
        val owner = runCatching {
            findFragment.invoke(null, view)
        }.getOrNull() ?: return null
        return runCatching {
            owner.javaClass.methods.firstOrNull {
                it.name == "getChildFragmentManager" && it.parameterCount == 0
            }?.invoke(owner)
        }.getOrNull()
    }

    private fun repairAdapterFragmentManagerFromFragments(
        adapter: Any,
        fragmentLists: List<FieldList>
    ) {
        val field = adapterTypeField(adapter.javaClass, "mFragmentManager") ?: return
        val current = runCatching { field.get(adapter) }.getOrNull() ?: return
        val addedFragment = fragmentLists.asSequence()
            .flatMap { it.values.asSequence() }
            .filterNotNull()
            .firstOrNull { fragment ->
                val added = runCatching {
                    fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean
                }.getOrNull() == true
                added && fragmentTag(fragment) != null
            } ?: return
        val tag = fragmentTag(addedFragment) ?: return
        if (findFragmentByTag(current, tag) === addedFragment) return
        val owner = runCatching {
            addedFragment.javaClass.methods.firstOrNull {
                it.name == "getParentFragmentManager" && it.parameterCount == 0
            }?.invoke(addedFragment)
        }.getOrNull() ?: return
        if (owner === current || findFragmentByTag(owner, tag) !== addedFragment) return
        runCatching {
            field.set(adapter, owner)
            adapterTypeField(adapter.javaClass, "mCurTransaction")
                ?.set(adapter, null)
            normalizedAdapters.remove(adapter)
            Log.d(
                TAG,
                "repaired FragmentManager from attached Fragment " +
                    "adapter=${adapter.javaClass.name} tag=$tag"
            )
        }.onFailure {
            Log.w(TAG, "repair FragmentManager from Fragment failed", it)
        }
    }

    private fun fragmentTag(fragment: Any): String? =
        runCatching {
            fragment.javaClass.getMethod("getTag").invoke(fragment) as? String
        }.getOrNull()

    private fun reconcileAddedFragments(
        adapter: Any,
        fragmentLists: List<FieldList>
    ) {
        val manager = adapterTypeField(adapter.javaClass, "mFragmentManager")
            ?.let { runCatching { it.get(adapter) }.getOrNull() }
            ?: return
        val orphans = fragmentLists.asSequence()
            .flatMap { it.values.asSequence() }
            .filterNotNull()
            .distinctBy(System::identityHashCode)
            .filter { fragment ->
                val added = runCatching {
                    fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean
                }.getOrNull() == true
                val tag = fragmentTag(fragment)
                added && tag != null && findFragmentByTag(manager, tag) !== fragment
            }
            .toList()
        if (orphans.isEmpty()) return
        adapterTypeField(adapter.javaClass, "mCurTransaction")
            ?.let { runCatching { it.set(adapter, null) } }
        val reset = orphans.count(::resetOrphanAddedFragment)
        normalizedAdapters.remove(adapter)
        Log.d(
            TAG,
            "reconciled orphan added Fragments=$reset adapter=${adapter.javaClass.name}"
        )
    }

    /**
     * FragmentPagerAdapter keys fragments by getItemId(position). Some targets
     * rebuild or reorder a tab list and return the same live Fragment under a
     * new item ID. The Fragment is still in FragmentManager's active list, so a
     * fresh ADD throws "Fragment already added"; rebinding its public tag makes
     * instantiateItem find it and queue ATTACH instead.
     */
    private fun prepareFragmentForAdd(
        fragmentManager: Any,
        fragment: Any,
        expectedTag: String,
        containerId: Int
    ): FragmentAttachment {
        val added = runCatching {
            fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean
        }.getOrNull() == true
        if (!added) return FragmentAttachment.ADDABLE
        val currentTag = runCatching {
            fragment.javaClass.getMethod("getTag").invoke(fragment) as? String
        }.getOrNull()
        if (currentTag == expectedTag) {
            if (findFragmentByTag(fragmentManager, expectedTag) === fragment) {
                return FragmentAttachment.PRESENT
            }
            return if (resetOrphanAddedFragment(fragment)) {
                FragmentAttachment.ADDABLE
            } else {
                FragmentAttachment.BLOCKED
            }
        }
        val rebound = runCatching {
            fieldByName(fragment, "mTag")?.set(fragment, expectedTag)
            fieldByName(fragment, "mContainerId")?.setInt(fragment, containerId)
            fieldByName(fragment, "mTag") != null &&
                fieldByName(fragment, "mContainerId") != null
        }.getOrDefault(false)
        if (!rebound) {
            Log.w(
                TAG,
                "could not rebind added Fragment tag=$currentTag -> $expectedTag"
            )
            return FragmentAttachment.BLOCKED
        }
        val found = findFragmentByTag(fragmentManager, expectedTag) === fragment
        if (found) {
            Log.d(TAG, "rebound added Fragment tag=$currentTag -> $expectedTag")
            return FragmentAttachment.PRESENT
        }
        return if (resetOrphanAddedFragment(fragment)) {
            FragmentAttachment.ADDABLE
        } else {
            FragmentAttachment.BLOCKED
        }
    }

    /**
     * A rebuilt Fragment can retain mAdded=true after its previous manager has
     * discarded the active record. No manager can resolve it by tag, yet a new
     * ADD still trips the duplicate guard. Clear only that stale flag and let
     * the regular transaction attach it to the current manager.
     */
    private fun resetOrphanAddedFragment(fragment: Any): Boolean =
        runCatching {
            val addedField = fieldByName(fragment, "mAdded")
                ?: error("Fragment.mAdded is unavailable")
            addedField.setBoolean(fragment, false)
            Log.d(
                TAG,
                "reset orphan added Fragment ${fragment.javaClass.name}@" +
                    System.identityHashCode(fragment)
            )
            val added = fragment.javaClass.getMethod("isAdded")
                .invoke(fragment) as? Boolean
            added != true
        }.onFailure {
            Log.w(TAG, "reset orphan added Fragment failed", it)
        }.getOrDefault(false)

    private fun fieldByName(owner: Any, name: String): Field? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findFragmentByTag(fragmentManager: Any, tag: String): Any? =
        runCatching {
            fragmentManager.javaClass
                .getMethod("findFragmentByTag", String::class.java)
                .invoke(fragmentManager, tag)
        }.getOrNull()

    fun recoverFromFragmentAlreadyAdded(throwable: Throwable): Boolean {
        val message = throwable.message ?: return false
        if (!message.contains("Fragment already added")) return false
        val expectedTag = FRAGMENT_TAG_PATTERN.find(message)?.groupValues?.getOrNull(1)
        var recoveredAdapters = 0
        normalizedAdapters.keys.toList().forEach { adapter ->
            val fragments = adapterFields(adapter.javaClass)
                .flatMap { field ->
                    runCatching {
                        field.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        (field.get(adapter) as? List<Any?>).orEmpty()
                    }.getOrDefault(emptyList())
                }
                .filterNotNull()
            val candidates = if (expectedTag != null) {
                fragments.filter { fragmentTag(it) == expectedTag }
            } else {
                fragments.filter { fragment ->
                    runCatching {
                        fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean
                    }.getOrNull() == true
                }
            }
            if (candidates.isEmpty() &&
                expectedTag != null &&
                fragments.none { fragmentTag(it) == expectedTag }
            ) {
                return@forEach
            }
            adapterTypeField(adapter.javaClass, "mCurTransaction")
                ?.let { runCatching { it.set(adapter, null) } }
            adapterTypeField(adapter.javaClass, "mExecutingFinishUpdate")
                ?.let { runCatching { it.setBoolean(adapter, false) } }
            candidates.forEach { resetOrphanAddedFragment(it) }
            normalizedAdapters.remove(adapter)
            recoveredAdapters++
        }
        if (recoveredAdapters > 0) {
            trackedViews.keys.toList().forEach { view ->
                view.post { view.requestLayout() }
            }
            Log.w(
                TAG,
                "recovered FragmentPagerAdapter transaction " +
                    "tag=$expectedTag adapters=$recoveredAdapters"
            )
        }
        return recoveredAdapters > 0
    }

    /**
     * A target ViewPager can call instantiateItem twice for one position before
     * finishUpdate commits. Both calls return the same Fragment instance and
     * enqueue ADD under the same tag, which AndroidX rejects as "Fragment
     * already added". Remove stale ADD operations before the transaction runs.
     */
    private fun repairPendingFragmentTransactions(adapter: Any): Int {
        val transaction = adapterTypeField(adapter.javaClass, "mCurTransaction")
            ?.let { field ->
                runCatching {
                    field.get(adapter)
                }.getOrNull()
            } ?: return 0
        val operations = mutableListField(transaction) ?: return 0
        val pending = operations.map(::pendingAdd)
        val stale = stalePendingAddPositions(pending)
        if (stale.isEmpty()) return 0
        stale.asReversed().forEach { index ->
            runCatching { operations.removeAt(index) }
        }
        Log.d(
            TAG,
            "removed stale pending Fragment ADD positions=${stale.asReversed()} " +
                "adapter=${adapter.javaClass.name}"
        )
        return stale.size
    }

    private fun mutableListField(owner: Any): MutableList<Any?>? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull {
                it.name == "mOps" && MutableList::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return runCatching {
                    field.get(owner) as? MutableList<Any?>
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun pendingAdd(operation: Any?): PendingFragmentAdd? {
        operation ?: return null
        val command = intField(operation, "mCmd")
            ?: intField(operation, "mCommand")
            ?: return null
        if (command != ADD_OPERATION) return null
        val fragment = fragmentField(operation) ?: return null
        val tag = runCatching {
            fragment.javaClass.getMethod("getTag").invoke(fragment) as? String
        }.getOrNull()
        val added = runCatching {
            fragment.javaClass.getMethod("isAdded").invoke(fragment) as? Boolean
        }.getOrNull() == true
        return PendingFragmentAdd(
            fragmentIdentity = System.identityHashCode(fragment),
            tag = tag,
            alreadyAdded = added
        )
    }

    private fun fragmentField(operation: Any): Any? {
        var current: Class<*>? = operation.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull {
                it.name == "mFragment" && hasType(it.type, FRAGMENT_CLASS)
            }
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(operation) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun intField(owner: Any, name: String): Int? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            val field = current.declaredFields.firstOrNull {
                it.name == name && it.type == Int::class.javaPrimitiveType
            }
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.getInt(owner) }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    internal fun stalePendingAddPositions(
        operations: List<PendingFragmentAdd?>
    ): List<Int> {
        val seenFragments = HashSet<Int>()
        val seenTags = HashSet<String>()
        val stale = ArrayList<Int>()
        operations.forEachIndexed { index, operation ->
            operation ?: return@forEachIndexed
            val duplicateFragment = !seenFragments.add(operation.fragmentIdentity)
            val duplicateTag = operation.tag?.let { !seenTags.add(it) } == true
            if (operation.alreadyAdded || duplicateFragment || duplicateTag) {
                stale += index
            }
        }
        return stale
    }

    private fun currentItem(view: View): Int =
        runCatching {
            view.javaClass.getMethod("getCurrentItem").invoke(view) as Int
        }.getOrDefault(0)

    private fun adapterFields(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields.filter {
                MutableList::class.java.isAssignableFrom(it.type)
            }
            current = current.superclass
        }
        return fields
    }

    private fun adapterTypeField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun hasType(type: Class<*>, target: String): Boolean {
        return typeByName(type, target) != null
    }

    private fun typeByName(type: Class<*>, target: String): Class<*>? {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == target) return current
            current = current.superclass
        }
        return null
    }

    private data class FieldList(
        val name: String,
        val values: MutableList<Any?>,
        val originalSize: Int
    )

    private data class AdapterSnapshot(
        val fragmentSignature: List<Int>,
        val itemIds: List<Any?>,
        val currentItem: Int
    )

    internal data class PendingFragmentAdd(
        val fragmentIdentity: Int,
        val tag: String?,
        val alreadyAdded: Boolean
    )

    private enum class FragmentAttachment {
        PRESENT,
        ADDABLE,
        BLOCKED
    }

    private const val ADD_OPERATION = 1
    private val FRAGMENT_TAG_PATTERN = Regex("""(android:switcher:[^\s}]+)""")
}
