package com.coke.nestspace.container.compat

import com.coke.nestspace.runtime.RuntimeApplicationFactories
import com.coke.nestspace.runtime.RuntimePluginInstaller

class CompatRuntimePlugin : RuntimePluginInstaller {
    override fun install() {
        RuntimeApplicationFactories.compat = CompatApplicationFactory
    }

    override fun uninstall() {
        RuntimeApplicationFactories.compat = null
    }
}
