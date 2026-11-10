package com.coke.nestspace.container.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps target accounts inside the virtual instance.
 *
 * Android only accepts an account type when a matching authenticator is
 * installed for the real UID. Virtual applications are not installed in
 * system_server, so AccountManager rejects their first account write. A
 * process-local IAccountManager facade supplies the same basic account
 * semantics without sharing credentials with the host or another instance.
 */
object VirtualAccountManager {
    private const val TAG = "VirtualAccountManager"
    private const val PREFS = "nestspace_virtual_accounts"

    fun install(
        contexts: List<Context>,
        targetPackage: String,
        instanceId: String
    ): Boolean = runCatching {
        val iface = Class.forName("android.accounts.IAccountManager")
        val managers = contexts
            .mapNotNull {
                runCatching { it.getSystemService(Context.ACCOUNT_SERVICE) }.getOrNull()
            }
            .distinctBy { System.identityHashCode(it) }
        if (managers.isEmpty()) return false

        val store = AccountStore(
            contexts.first().applicationContext,
            targetPackage,
            instanceId
        )
        var installed = 0
        managers.forEach { manager ->
            val field = accountServiceFields(manager.javaClass, iface).firstOrNull()
                ?: return@forEach
            field.isAccessible = true
            val delegate = field.get(manager) ?: return@forEach
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                Handler(delegate, store)
            )
            field.set(manager, proxy)
            installed++
        }
        installed > 0
    }.onSuccess { installed ->
        Log.d(TAG, "virtual account manager installed=$installed")
    }.onFailure {
        Log.w(TAG, "virtual account manager install failed", it)
    }.getOrDefault(false)

    private fun accountServiceFields(type: Class<*>, iface: Class<*>): List<java.lang.reflect.Field> {
        val result = mutableListOf<java.lang.reflect.Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            result += current.declaredFields.filter { iface.isAssignableFrom(it.type) }
            current = current.superclass
        }
        return result
    }

    private class Handler(
        private val delegate: Any,
        private val store: AccountStore
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "VirtualAccountManager"
                }
            }

            val values = args.orEmpty()
            val account = values.firstOrNull() as? Account
            when {
                method.name == "addAccountExplicitly" && account != null -> {
                    return store.add(
                        account,
                        values.getOrNull(1) as? String,
                        values.getOrNull(2) as? android.os.Bundle
                    )
                }
                method.name == "removeAccount" && account != null -> {
                    return store.remove(account)
                }
                method.name.startsWith("getAccounts") &&
                    method.returnType == Array<Account>::class.java -> {
                    val type = values.firstOrNull() as? String
                    return store.accounts(type)
                }
                method.name == "getPassword" && account != null -> {
                    return store.password(account)
                }
                method.name == "setPassword" && account != null -> {
                    store.setPassword(account, values.getOrNull(1) as? String)
                    return Unit
                }
                method.name == "clearPassword" && account != null -> {
                    store.setPassword(account, null)
                    return Unit
                }
                method.name == "peekAuthToken" && account != null -> {
                    return store.authToken(account, values.getOrNull(1) as? String)
                }
                method.name == "setAuthToken" && account != null -> {
                    store.setAuthToken(
                        account,
                        values.getOrNull(1) as? String,
                        values.getOrNull(2) as? String
                    )
                    return Unit
                }
                method.name == "getUserData" && account != null -> {
                    return store.userData(account, values.getOrNull(1) as? String)
                }
                method.name == "setUserData" && account != null -> {
                    store.setUserData(
                        account,
                        values.getOrNull(1) as? String,
                        values.getOrNull(2) as? String
                    )
                    return Unit
                }
            }

            return try {
                method.invoke(delegate, *values)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    private class AccountStore(
        context: Context,
        packageName: String,
        instanceId: String
    ) {
        private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val key = "account_${packageName}_$instanceId"
        private val lock = Any()
        private var cached: JSONObject? = null

        fun accounts(type: String?): Array<Account> = synchronized(lock) {
            val root = load()
            root.keys().asSequence()
                .filter { type == null || it == type }
                .flatMap { accountType ->
                    root.optJSONArray(accountType).orEmptyObjects().mapNotNull { item ->
                        val name = item.optString("name").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        Account(name, accountType)
                    }
                }
                .toList()
                .toTypedArray()
        }

        fun add(account: Account, password: String?, extras: android.os.Bundle?): Boolean =
            synchronized(lock) {
                val root = load()
                val accounts = root.optJSONArray(account.type) ?: JSONArray()
                if (accounts.orEmptyObjects().any {
                        it.optString("name") == account.name &&
                            it.optString("type") == account.type
                    }
                ) {
                    return false
                }
                val item = JSONObject()
                    .put("name", account.name)
                    .put("type", account.type)
                    .put("password", password ?: JSONObject.NULL)
                    .put("tokens", JSONObject())
                    .put("user_data", JSONObject())
                extras?.keySet()?.forEach { key ->
                    item.getJSONObject("user_data").put(key, extras.get(key)?.toString())
                }
                accounts.put(item)
                root.put(account.type, accounts)
                save(root)
                Log.d(TAG, "virtual account added type=${account.type}")
                true
            }

        fun remove(account: Account): Boolean = synchronized(lock) {
            val root = load()
            val accounts = root.optJSONArray(account.type) ?: return false
            val retained = JSONArray()
            var removed = false
            accounts.orEmptyObjects().forEach { item ->
                if (item.optString("name") == account.name) {
                    removed = true
                } else {
                    retained.put(item)
                }
            }
            if (removed) {
                root.put(account.type, retained)
                save(root)
            }
            removed
        }

        fun password(account: Account): String? = item(account)
            ?.optString("password")
            ?.takeIf { it.isNotBlank() && it != "null" }

        fun setPassword(account: Account, password: String?) {
            update(account) { it.put("password", password ?: JSONObject.NULL) }
        }

        fun authToken(account: Account, tokenType: String?): String? =
            item(account)
                ?.optJSONObject("tokens")
                ?.optString(tokenType.orEmpty())
                ?.takeIf { it.isNotBlank() && it != "null" }

        fun setAuthToken(account: Account, tokenType: String?, token: String?) {
            update(account) { item ->
                val tokens = item.optJSONObject("tokens") ?: JSONObject()
                if (token == null) {
                    tokens.remove(tokenType.orEmpty())
                } else {
                    tokens.put(tokenType.orEmpty(), token)
                }
                item.put("tokens", tokens)
            }
        }

        fun userData(account: Account, dataKey: String?): String? =
            item(account)
                ?.optJSONObject("user_data")
                ?.optString(dataKey.orEmpty())
                ?.takeIf { it.isNotBlank() && it != "null" }

        fun setUserData(account: Account, dataKey: String?, value: String?) {
            update(account) { item ->
                val data = item.optJSONObject("user_data") ?: JSONObject()
                if (value == null) {
                    data.remove(dataKey.orEmpty())
                } else {
                    data.put(dataKey.orEmpty(), value)
                }
                item.put("user_data", data)
            }
        }

        private fun item(account: Account): JSONObject? = synchronized(lock) {
            load().optJSONArray(account.type)
                .orEmptyObjects()
                .firstOrNull { it.optString("name") == account.name }
        }

        private fun update(account: Account, block: (JSONObject) -> Unit) {
            synchronized(lock) {
                val root = load()
                val accounts = root.optJSONArray(account.type) ?: return
                val item = accounts.orEmptyObjects()
                    .firstOrNull { it.optString("name") == account.name }
                    ?: return
                block(item)
                save(root)
            }
        }

        private fun load(): JSONObject = cached ?: runCatching {
            JSONObject(preferences.getString(key, "{}").orEmpty())
        }.getOrDefault(JSONObject()).also { cached = it }

        private fun save(root: JSONObject) {
            cached = root
            preferences.edit().putString(key, root.toString()).commit()
        }

        private fun JSONArray?.orEmptyObjects(): List<JSONObject> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    optJSONObject(index)?.let(::add)
                }
            }
        }
    }
}
