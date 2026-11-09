package com.coke.nestspace.container.compat

import com.coke.nestspace.container.compat.p74332.ProfileP74332
import com.coke.nestspace.runtime.RuntimeApplicationFactories
import com.coke.nestspace.runtime.RuntimePluginInstaller

class CompatRuntimePlugin : RuntimePluginInstaller {
    override fun install() {
        RuntimeApplicationFactories.compat = CompatApplicationFactory
        CompatibilityProfiles.register { spec, loader ->
            ProfileP74332.resolve(spec, loader)
        }
    }
}
