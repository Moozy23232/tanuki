package com.tanuki

import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.storage.SafeTar
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

class ArchiveSafetyTest {
    @Test fun acceptsRootfsLinksAndRejectsEscapingLinks(): Unit = runBlocking {
        val base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "archive-${System.nanoTime()}").apply { mkdirs() }
        fun archive(name: String, malicious: Boolean): File {
            val file = File(base,name)
            TarArchiveOutputStream(GZIPOutputStream(file.outputStream())).use { tar ->
                val data = "ordinary program".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("usr/bin/program").apply { size=data.size.toLong(); mode=493 });tar.write(data);tar.closeArchiveEntry()
                tar.putArchiveEntry(TarArchiveEntry("bin", '2'.code.toByte()).apply { linkName=if(malicious) "../../outside" else "usr/bin" });tar.closeArchiveEntry()
            }
            return file
        }
        val valid = File(base,"valid")
        SafeTar.extract(archive("valid.tar.gz",false),valid)
        assertEquals("ordinary program",File(valid,"bin/program").readText())
        try { SafeTar.extract(archive("escape.tar.gz",true),File(base,"escape"));fail("accepted escaping link") }catch(_:IllegalArgumentException){ }
        assertFalse(File(base.parentFile,"outside").exists())
        com.tanuki.runtime.RuntimeManager.deleteTree(base)
    }
}
