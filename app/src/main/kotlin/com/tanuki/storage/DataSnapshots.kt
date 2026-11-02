package com.tanuki.storage

import com.tanuki.runtime.Paths
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

object DataSnapshots {
    /** Private snapshots contain credentials. Android auto backup and public export exclude them. */
    suspend fun create(paths: Paths): File {
        val id = "snapshot-${System.currentTimeMillis()}"
        val stage = File(paths.backups, "$id.staging").apply { mkdirs() }
        copy(paths.data, File(stage, "userdata"), paths.base)
        File(stage, "installed.json").writeText(paths.metadata().toString())
        File(stage, "complete").writeText("private snapshot; does not contain workspaces or Ubuntu")
        val final = File(paths.backups, id)
        check(stage.renameTo(final))
        paths.atomic("last-snapshot", id)
        return final
    }
    suspend fun restore(paths: Paths) {
        val id = paths.read("last-snapshot") ?: error("没有可恢复快照")
        require(id.matches(Regex("snapshot-[0-9]+")))
        val backup = File(paths.backups, id)
        check(File(backup, "complete").exists()) { "快照未完成" }
        val metadata = JSONObject(File(backup, "installed.json").readText())
        val version=metadata.getString("dsh")
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")))
        check(File(paths.engines, "$version/.installed").exists()) { "快照匹配的 DSH 程序缺失" }
        check(metadata.getString("node") == paths.metadata().getString("node")) { "Node.js 不匹配，停止自动回退" }
        check(metadata.getString("ubuntu") == paths.metadata().getString("ubuntu")) { "Ubuntu 版本不匹配，停止自动回退" }
        val stage = File(paths.base, "userdata.restore")
        RuntimeManager.deleteTree(stage); copy(File(backup, "userdata"), stage, paths.base)
        val retained = File(paths.base, "userdata.before-restore")
        check(!retained.exists()) { "先前恢复记录未处理，保留数据并停止" }
        val token=java.util.UUID.randomUUID().toString()
        File(stage,".tanuki-restore-token").writeText(token)
        paths.atomic("restore-transaction", JSONObject().put("snapshot", id).put("token",token).put("previous",paths.metadata()).put("phase", "prepared").toString())
        check(paths.data.renameTo(retained))
        check(stage.renameTo(paths.data)) { "数据切换中断，保留 before-restore 目录" }
        paths.atomic("installed.json", metadata.toString())
        paths.atomic("restore-transaction", JSONObject().put("snapshot", id).put("token",token).put("phase", "switched").toString())
        // Keep the displaced userdata until the user has verified the restored version.
        File(paths.state, "update-transaction").delete()
    }
    /** Finish only a fully prepared local restore; no model or project operation is replayed. */
    fun recoverPreparedRestore(paths:Paths):String? {
        val raw=paths.read("restore-transaction") ?: return null
        val journal=JSONObject(raw)
        if(journal.optString("phase")!="prepared")return null
        val id=journal.getString("snapshot");require(id.matches(Regex("snapshot-[0-9]+")))
        val token=journal.getString("token")
        val backup=File(paths.backups,id);check(File(backup,"complete").exists())
        val metadata=JSONObject(File(backup,"installed.json").readText())
        val version=metadata.getString("dsh");require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")))
        check(File(paths.engines,"$version/.installed").exists())
        check(metadata.getString("node")==paths.metadata().getString("node"))
        check(metadata.getString("ubuntu")==paths.metadata().getString("ubuntu"))
        val stage=File(paths.base,"userdata.restore")
        val retained=File(paths.base,"userdata.before-restore")
        fun marked(file:File)=runCatching { File(file,".tanuki-restore-token").readText()==token }.getOrDefault(false)
        if(!marked(paths.data)) {
            check(marked(stage)) { "恢复暂存数据不完整，已保留所有副本；需要人工检查恢复记录" }
            if(retained.exists()) {
                val scaffold=paths.data.listFiles().orEmpty()
                check(scaffold.all { it.name in setOf("home","dsh") && it.isDirectory && it.list()?.isEmpty()==true }) { "恢复目录冲突，已停止覆盖" }
                scaffold.forEach { check(it.delete()) }
                check(paths.data.delete())
            }else check(paths.data.renameTo(retained))
            check(stage.renameTo(paths.data))
        }
        paths.atomic("installed.json",metadata.toString())
        paths.atomic("restore-transaction",journal.put("phase","switched").toString())
        File(paths.state,"update-transaction").delete()
        return "已完成上次准备好的数据恢复；服务保持停止，等待启动检查"
    }
    suspend fun copy(source: File, destination: File, allowedLinkRoot: File = source) {
        val coroutine = currentCoroutineContext()
        val from = source.toPath()
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive(); Files.createDirectories(destination.toPath().resolve(from.relativize(dir))); return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                val to = destination.toPath().resolve(from.relativize(path))
                val payload = L2sStore.payload(path, allowedLinkRoot.toPath())
                if (payload != null) {
                    check(destination.usableSpace > Files.size(payload) + 64L * 1024 * 1024) { "备份空间不足" }
                    // Snapshot the bytes, never an absolute link to a mutable live inode store.
                    Files.copy(payload, to, StandardCopyOption.COPY_ATTRIBUTES)
                } else if (attrs.isSymbolicLink) {
                    val target = Files.readSymbolicLink(path)
                    val resolved = if (target.isAbsolute) target.normalize() else path.parent.resolve(target).normalize()
                    val guestRoots = listOf("/opt/engines", "/opt/node", "/opt/pnpm", "/opt/node-cache", "/workspaces", "/home/tanuki", "/dsh-data", "/tmp", "/dev/shm").map { java.nio.file.Paths.get(it) }
                    require(resolved.startsWith(allowedLinkRoot.toPath()) || (target.isAbsolute && guestRoots.any { resolved.startsWith(it) })) { "备份拒绝指向应用目录或已知客体映射之外的链接" }
                    Files.createSymbolicLink(to, target)
                } else if (attrs.isRegularFile) {
                    check(destination.usableSpace > attrs.size() + 64L * 1024 * 1024) { "备份空间不足" }
                    Files.copy(path, to, StandardCopyOption.COPY_ATTRIBUTES)
                } else error("备份不支持设备或 socket 文件: ${path.fileName}")
                return FileVisitResult.CONTINUE
            }
        })
    }
}
