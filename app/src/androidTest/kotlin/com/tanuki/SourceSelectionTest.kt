package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses only public archive samples and an isolated preference directory. */
class SourceSelectionTest {
    private class FixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }

    @Test fun npmArchiveSamplingVerifiesBytesAndPreservesManualChoice(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val directory = File(context.cacheDir, "npm-source-selection-${System.nanoTime()}")
        val fixture = FixtureContext(context, directory)
        val manager = RuntimeManager(fixture)
        try {
            val sources = manager.npmSources
            sources.prepare(RuntimeManager.DSH)
            val sample = withTimeout(25000) { sources.speed.state.first { !it.running && it.measuredAtMillis > 0 } }
            val best = requireNotNull(sample.bestSourceId) { "No reachable npm archive source: ${sample.results}" }
            val order = sources.orderedForInstall(RuntimeManager.DSH)
            assertEquals(best, order.first().id)
            val official = "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-${RuntimeManager.DSH}.tgz"
            val archive = File(directory, "verified-dsh.tgz")
            com.tanuki.download.Downloader { _, _ -> }
                .fetchIntegrity(order.first().urlFor(official), archive, RuntimeManager.DSH_INTEGRITY)
            val digest = java.security.MessageDigest.getInstance("SHA-512").digest(archive.readBytes())
            assertEquals(RuntimeManager.DSH_INTEGRITY, "sha512-" + java.util.Base64.getEncoder().encodeToString(digest))
            sources.prepare(RuntimeManager.DSH)
            assertEquals("reuse the measured release", sample.measuredAtMillis, sources.speed.state.value.measuredAtMillis)
            assertFalse(sources.speed.state.value.running)
            sources.speed.clear()
            sources.prepare(RuntimeManager.DSH)
            sources.select("huawei")
            withTimeout(25000) { sources.speed.state.first { !it.running } }
            assertFalse(sources.state.value.automatic)
            assertEquals("huawei", sources.orderedForInstall(RuntimeManager.DSH).first().id)
            val reopened = RuntimeManager(fixture)
            try {
                assertFalse(reopened.npmSources.state.value.automatic)
                assertEquals("huawei", reopened.npmSources.state.value.id)
            } finally { reopened.scope.cancel(); reopened.scope.coroutineContext[kotlinx.coroutines.Job]?.join() }
            Log.i("TANUKI_PROBE", "NPM_AUTO_SOURCE selected=$best successful=${sample.results.values.count { it.status == SourceProbeStatus.SUCCESS }} integrity_verified=true manual_override_persisted=true")
        } finally {
            manager.npmSources.speed.cancel()
            manager.scope.cancel()
            manager.scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            RuntimeManager.deleteTree(directory)
        }
    }

    @Test fun automaticSamplingSelectsMeasuredBestAndHonorsManualOverride(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val directory = File(context.cacheDir, "source-selection-${System.nanoTime()}")
        val manager = RuntimeManager(FixtureContext(context, directory))
        try {
            val components = manager.components
            for (component in RuntimeComponent.entries) components.prepareSources(component)
            for (component in RuntimeComponent.entries) {
                val sample = withTimeout(25000) {
                    components.sourceTests.getValue(component).state.first { !it.running && it.measuredAtMillis > 0 }
                }
                assertEquals(8, sample.results.size)
                assertFalse(sample.results.values.any { it.status == SourceProbeStatus.TESTING })
                val best = requireNotNull(sample.bestSourceId) { "$component has no reachable source: ${sample.results}" }
                assertEquals(SourceProbeStatus.SUCCESS, sample.results.getValue(best).status)
                withTimeout(5000) {
                    while (true) {
                        val inventory = components.state.value
                        val saved = if (component == RuntimeComponent.UBUNTU) inventory.ubuntuSource else inventory.nodeSource
                        if (saved == best) break
                        delay(25)
                    }
                }
                assertEquals(best, components.sourceFor(component).id)
                components.prepareSources(component)
                assertEquals("fresh automatic samples should be reused", sample.measuredAtMillis,
                    components.sourceTests.getValue(component).state.value.measuredAtMillis)
                assertFalse(components.sourceTests.getValue(component).state.value.running)
                Log.i("TANUKI_PROBE", "AUTO_SOURCE component=$component selected=$best speed=${sample.results.getValue(best).bytesPerSecond} successful=${sample.results.values.count { it.status == SourceProbeStatus.SUCCESS }}")
            }
            // A late automatic result must never undo an explicit selection made while probing.
            components.sourceTests.getValue(RuntimeComponent.NODE).clear()
            components.prepareSources(RuntimeComponent.NODE)
            components.selectSource(RuntimeComponent.NODE, "official")
            withTimeout(25000) { components.sourceTests.getValue(RuntimeComponent.NODE).state.first { !it.running } }
            delay(100)
            assertFalse(components.state.value.nodeAutomatic)
            assertEquals("official", components.sourceFor(RuntimeComponent.NODE).id)
            assertTrue(components.state.value.ubuntuAutomatic)
            Log.i("TANUKI_PROBE", "AUTO_SOURCE manual_override_preserved=true other_component_automatic=true")
        } finally {
            manager.cancel()
            manager.scope.cancel()
            RuntimeManager.deleteTree(directory)
        }
    }
}
