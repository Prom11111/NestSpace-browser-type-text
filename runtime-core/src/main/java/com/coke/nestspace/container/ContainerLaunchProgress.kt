package com.coke.nestspace.container

import com.coke.nestspace.container.runtime.AndroidStartupPhase

/**
 * Progress checkpoints reported by the real container startup path.
 *
 * The percentages are coarse phase weights, not synthetic timer estimates.
 * A long Application.onCreate call therefore leaves the indicator at the
 * matching checkpoint until that work actually completes.
 */
internal enum class LaunchProgressStage(val percent: Int) {
    PREPARING_RUNTIME(4),
    LOADING_CODE(10),
    LOADING_RESOURCES(16),
    PREPARING_IDENTITY(22),
    ATTACHING_APPLICATION(30),
    PROCESS_APPLICATION(36),
    CONTENT_PROVIDERS(46),
    APPLICATION_CREATE(58),
    APPLICATION_COMPATIBILITY(68),
    HOST_BRIDGES(75),
    EMBEDDED_APPLICATION_CREATE(80),
    STARTUP_COMPATIBILITY(86),
    CRASH_GUARD(90),
    READY(94),
    FIRST_ACTIVITY_SCHEDULED(97),
    FIRST_ACTIVITY_OPENED(100),
    FAILED(100);

    companion object {
        fun forStartupPhase(phase: AndroidStartupPhase): LaunchProgressStage = when (phase) {
            AndroidStartupPhase.CLASS_LOADER -> LOADING_CODE
            AndroidStartupPhase.APPLICATION_ATTACH -> ATTACHING_APPLICATION
            AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH -> ATTACHING_APPLICATION
            AndroidStartupPhase.PROCESS_APPLICATION -> PROCESS_APPLICATION
            AndroidStartupPhase.CONTENT_PROVIDERS -> CONTENT_PROVIDERS
            AndroidStartupPhase.APPLICATION_CREATE -> APPLICATION_CREATE
            AndroidStartupPhase.APPLICATION_COMPATIBILITY -> APPLICATION_COMPATIBILITY
            AndroidStartupPhase.HOST_BRIDGES -> HOST_BRIDGES
            AndroidStartupPhase.EMBEDDED_APPLICATION_CREATE ->
                EMBEDDED_APPLICATION_CREATE
            AndroidStartupPhase.STARTUP_COMPATIBILITY -> STARTUP_COMPATIBILITY
            AndroidStartupPhase.CRASH_GUARD -> CRASH_GUARD
            AndroidStartupPhase.READY -> READY
            AndroidStartupPhase.FIRST_ACTIVITY_SCHEDULED -> FIRST_ACTIVITY_SCHEDULED
            AndroidStartupPhase.FIRST_ACTIVITY_OPENED -> FIRST_ACTIVITY_OPENED
        }
    }
}

internal data class ContainerLaunchProgressSnapshot(
    val stage: LaunchProgressStage
) {
    val percent: Int get() = stage.percent
}

internal object ContainerLaunchProgress {
    @Volatile
    private var current = LaunchProgressStage.PREPARING_RUNTIME

    fun begin() {
        current = LaunchProgressStage.PREPARING_RUNTIME
    }

    fun advance(stage: LaunchProgressStage) {
        if (stage.percent >= current.percent) current = stage
    }

    fun phaseStarted(phase: AndroidStartupPhase) {
        advance(LaunchProgressStage.forStartupPhase(phase))
    }

    fun phaseCompleted(phase: AndroidStartupPhase) {
        advance(LaunchProgressStage.forStartupPhase(phase))
    }

    fun complete() {
        current = LaunchProgressStage.FIRST_ACTIVITY_OPENED
    }

    fun fail() {
        current = LaunchProgressStage.FAILED
    }

    fun snapshot(): ContainerLaunchProgressSnapshot =
        ContainerLaunchProgressSnapshot(current)
}
