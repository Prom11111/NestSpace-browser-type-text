package com.coke.nestspace.container.application

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationBootstrapResolverTest {

    @Test
    fun externalWrapperDetectionDoesNotNeedPackagerNamespace() {
        assertTrue(
            BootstrapClassNamePolicy.isExternalApplicationClass(
                "vendor.wrapper.MyApplication",
                "com.example.player"
            )
        )
        assertTrue(
            BootstrapClassNamePolicy.isExternalApplicationClass(
                "another.vendor.FutureWrapper",
                "com.example.reader"
            )
        )
        assertFalse(
            BootstrapClassNamePolicy.isExternalApplicationClass(
                "com.example.player.Application",
                "com.example.player"
            )
        )
    }

    @Test
    fun embeddedApplicationRankingWorksForAnyTargetPackage() {
        val playerRoot = BootstrapClassNamePolicy.embeddedApplicationScore(
            "com.example.player.PlayerApplication",
            "com.example.player"
        )
        val playerNested = BootstrapClassNamePolicy.embeddedApplicationScore(
            "com.example.player.helper.PlayerApplication",
            "com.example.player"
        )
        val readerRoot = BootstrapClassNamePolicy.embeddedApplicationScore(
            "com.example.reader.ReaderApplication",
            "com.example.reader"
        )

        assertTrue(playerRoot > playerNested)
        assertTrue(playerRoot > 0)
        assertTrue(readerRoot > 0)
        assertTrue(
            BootstrapClassNamePolicy.embeddedApplicationScore(
                "com.example.other.OtherApplication",
                "com.example.player"
            ) < 0
        )
    }

    @Test
    fun applicationLikeRankingDoesNotDependOnClassNamesFromOneApp() {
        val mediaLike = BootstrapClassNamePolicy.applicationLikeScore(
            "com.example.media.MediaApplicationLike",
            "com.example.media"
        )
        val novelLike = BootstrapClassNamePolicy.applicationLikeScore(
            "com.example.novel.NovelApplicationLike",
            "com.example.novel"
        )

        assertTrue(mediaLike > 0)
        assertTrue(novelLike > 0)
        assertTrue(
            BootstrapClassNamePolicy.applicationLikeScore(
                "com.other.ApplicationLike",
                "com.example.media"
            ) < 0
        )
    }

    @Test
    fun applicationLikeConstructorIsMatchedByShape() {
        val parameters = listOf(
            Application::class.java,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Intent::class.java
        )

        assertTrue(
            ApplicationBootstrapResolver.matchesApplicationLikeConstructor(parameters)
        )
        assertFalse(
            ApplicationBootstrapResolver.matchesApplicationLikeConstructor(
                parameters.dropLast(1)
            )
        )
        assertFalse(
            ApplicationBootstrapResolver.matchesApplicationLikeConstructor(
                parameters.mapIndexed { index, type ->
                    if (index == 2) Int::class.javaPrimitiveType!! else type
                }
            )
        )
    }

    @Test
    fun realApplicationIsMatchedByLikeSupertype() {
        val parameters = listOf(
            Application::class.java,
            LikeFamily::class.java
        )

        assertTrue(
            ApplicationBootstrapResolver.matchesRealApplicationConstructor(
                parameters,
                ExampleApplicationLike::class.java
            )
        )
        assertFalse(
            ApplicationBootstrapResolver.matchesRealApplicationConstructor(
                parameters,
                UnrelatedLike::class.java
            )
        )
    }

    @Test
    fun tinkerWrapperRequiresApplicationDelegateField() {
        assertTrue(
            ApplicationBootstrapResolver.hasTinkerDelegateField(
                listOf(String::class.java, Application::class.java)
            )
        )
        assertFalse(
            ApplicationBootstrapResolver.hasTinkerDelegateField(
                listOf(String::class.java, ExampleApplicationLike::class.java)
            )
        )
    }

    @Test
    fun frameworkDetectionUsesTypeHierarchyInsteadOfPackageName() {
        assertTrue(
            ApplicationBootstrapResolver.hierarchyContains(
                ExampleApplicationLike::class.java,
                "LikeFamily"
            )
        )
        assertFalse(
            ApplicationBootstrapResolver.hierarchyContains(
                UnrelatedLike::class.java,
                "LikeFamily"
            )
        )
    }

    private interface LikeFamily

    private class ExampleApplicationLike : LikeFamily

    private class UnrelatedLike
}
