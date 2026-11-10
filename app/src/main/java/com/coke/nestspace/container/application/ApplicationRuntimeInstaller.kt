package com.coke.nestspace.container.application

import com.coke.nestspace.runtime.RuntimeApplicationFactories
import com.coke.nestspace.runtime.RuntimePluginInstaller

class ApplicationRuntimeInstaller : RuntimePluginInstaller {
    override fun install() {
        RuntimeApplicationFactories.target = TargetApplicationFactory
    }
}
