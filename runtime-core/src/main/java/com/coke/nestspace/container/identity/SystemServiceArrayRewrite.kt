package com.coke.nestspace.container.identity

/**
 * Rewrites object-array elements without changing the array's runtime type.
 * System service proxies must preserve types such as Network[] because the
 * generated Binder proxy performs a checked cast on every return value.
 */
internal object SystemServiceArrayRewrite {
    fun rewrite(
        value: Array<*>,
        transform: (Any?) -> Any?
    ): Any {
        val componentType = value.javaClass.componentType ?: Any::class.java
        val rewritten = java.lang.reflect.Array.newInstance(
            componentType,
            value.size
        )
        value.indices.forEach { index ->
            java.lang.reflect.Array.set(
                rewritten,
                index,
                transform(value[index])
            )
        }
        return rewritten
    }
}
