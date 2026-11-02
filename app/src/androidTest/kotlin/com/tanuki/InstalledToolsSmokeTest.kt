package com.tanuki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Existing public runtime only: no download, model, real project or credential access. */
class InstalledToolsSmokeTest {
    @Test fun officialCredentialProviderPersistsSyntheticKeyInTheDshHomeBind(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val runtime = (context.applicationContext as TanukiApp).runtime
        val version = runtime.paths.metadata().optString("dsh")
        assumeTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) &&
            File(runtime.paths.engines, "$version/node_modules/@deepseek-ai/dsh-credentials-local/lib/index.js").isFile)
        val scratch = File(context.cacheDir, "credential-probe-${System.nanoTime()}").apply { check(mkdirs()) }
        val home = File(scratch, "home").apply { check(mkdirs()) }
        val dsh = File(scratch, "dsh").apply { check(mkdirs()) }
        val workspace = File(scratch, "workspace").apply { check(mkdirs()) }
        suspend fun node(operation: String): String {
            val result = runtime.launcher.run(runtime.paths.root, listOf("/opt/node/bin/node", "--input-type=module", "-e", """
                import {createRequire} from 'node:module';
                const req=createRequire('/opt/engines/$version/node_modules/@deepseek-ai/dsh/lib/bin.js');
                const {LocalCredentialProvider}=await import(req.resolve('@deepseek-ai/dsh-credentials-local'));
                const cordis=await import(req.resolve('@deepseek-ai/cordis'));
                const Context=cordis.Context??cordis.default?.Context;
                const provider=new LocalCredentialProvider(new Context(),{dshHome:'/dsh-data',watch:false});
                await provider.loadInitial();
                $operation
            """.trimIndent()), timeoutMs=120000,home=home,dshHome=dsh,workspace=workspace,engineHome=runtime.paths.engines)
            assertEquals("Synthetic credential probe: ${result.stderr.takeLast(1000)}", 0, result.exitCode)
            return result.stdout
        }
        try {
            assertTrue(node("""
                await provider.set('TANUKI_SYNTHETIC_KEY','public-fixture-not-a-real-key');
                if((await provider.resolve('TANUKI_SYNTHETIC_KEY'))?.value!=='public-fixture-not-a-real-key')throw Error('save/read mismatch');
                const {stat}=await import('node:fs/promises');
                if(((await stat('/dsh-data/.credentials.yaml')).mode&0o777)!==0o600)throw Error('credential mode not private');
                console.log('CREDENTIAL_SAVE_OK');
            """.trimIndent()).contains("CREDENTIAL_SAVE_OK"))
            assertTrue(node("""
                if((await provider.resolve('TANUKI_SYNTHETIC_KEY'))?.value!=='public-fixture-not-a-real-key')throw Error('restart read mismatch');
                await provider.set('TANUKI_SYNTHETIC_KEY','public-fixture-updated');
                console.log('CREDENTIAL_RESTART_UPDATE_OK');
            """.trimIndent()).contains("CREDENTIAL_RESTART_UPDATE_OK"))
            assertTrue(node("""
                if((await provider.resolve('TANUKI_SYNTHETIC_KEY'))?.value!=='public-fixture-updated')throw Error('update persistence mismatch');
                await provider.unset('TANUKI_SYNTHETIC_KEY');
                console.log('CREDENTIAL_DELETE_OK');
            """.trimIndent()).contains("CREDENTIAL_DELETE_OK"))
            Log.i("TANUKI_PROBE", "CREDENTIAL_FIXTURE save=true restart=true update=true delete=true mode600=true model=false real_key=false")
        } finally { RuntimeManager.deleteTree(scratch) }
    }

    @Test fun installedBashAndOfficialFileModuleWriteAndReadAcrossProcesses(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck")) { "Run only in the verification app" }
        val runtime = (context.applicationContext as TanukiApp).runtime
        val version = runtime.paths.metadata().optString("dsh")
        assumeTrue("Requires the already installed official DSH", version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) &&
            File(runtime.paths.engines, "$version/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
        val scratch = File(context.cacheDir, "installed-tool-probe-${System.nanoTime()}").apply { check(mkdirs()) }
        val home = File(scratch, "home").apply { check(mkdirs()) }
        val dsh = File(scratch, "dsh").apply { check(mkdirs()) }
        val workspace = File(scratch, "workspace").apply { check(mkdirs()) }
        suspend fun guest(args: List<String>): String {
            val result = runtime.launcher.run(runtime.paths.root, args, timeoutMs = 120_000,
                home = home, dshHome = dsh, workspace = workspace, engineHome = runtime.paths.engines)
            assertEquals("isolated tool probe: ${result.stderr.takeLast(1000)}", 0, result.exitCode)
            return result.stdout
        }
        try {
            val shell = guest(listOf("/bin/bash", "-lc", """
                set -euo pipefail
                cd /workspaces
                printf 'BASH_WRITE_OK\n' > bash-write.txt
                test "${'$'}(cat bash-write.txt)" = BASH_WRITE_OK
                mkdir -p nested
                printf 'NESTED_WRITE_OK\n' > nested/result.txt
                printf 'BASH_EXEC_AND_WRITE_OK\n'
            """.trimIndent()))
            assertTrue(shell.contains("BASH_EXEC_AND_WRITE_OK"))
            assertEquals("BASH_WRITE_OK\n", File(workspace, "bash-write.txt").readText())
            val official = guest(listOf("/opt/node/bin/node", "--input-type=module", "-e", """
                import {createRequire} from 'node:module';
                const req=createRequire('/opt/engines/$version/node_modules/@deepseek-ai/dsh/lib/bin.js');
                const mod=await import(req.resolve('@deepseek-ai/dsh-fs-local'));
                const cordis=await import(req.resolve('@deepseek-ai/cordis'));
                const LocalFileSystem=mod.LocalFileSystem??mod.default?.LocalFileSystem??mod.default?.default??mod.default;
                const Context=cordis.Context??cordis.default?.Context??cordis.default?.default??cordis.default;
                const fs=new LocalFileSystem(new Context(),{cwd:'/workspaces',diffBasisMaxBytes:10485760});
                const target=await fs.resolve('official-created.txt');
                await fs.writeText(target,'OFFICIAL_CREATE_OK',{kind:'createIfAbsent'});
                if(String(await fs.readText(target))!=='OFFICIAL_CREATE_OK')throw Error('official read after create failed');
                await fs.writeText(target,'OFFICIAL_UPDATE_OK');
                if(String(await fs.readText(target))!=='OFFICIAL_UPDATE_OK')throw Error('official read after update failed');
                const {spawnSync}=await import('node:child_process');
                const child=spawnSync('/bin/bash',['-lc','printf NODE_BASH_OK'],{encoding:'utf8'});
                if(child.status!==0||child.stdout!=='NODE_BASH_OK')throw Error('Node spawning Bash failed');
                console.log('OFFICIAL_FILE_CREATE_UPDATE_READ_OK NODE_SPAWNS_BASH_OK');
            """.trimIndent()))
            assertTrue(official.contains("OFFICIAL_FILE_CREATE_UPDATE_READ_OK NODE_SPAWNS_BASH_OK"))
            val reread = guest(listOf("/bin/bash", "-lc", """
                set -euo pipefail
                test "${'$'}(cat /workspaces/bash-write.txt)" = BASH_WRITE_OK
                test "${'$'}(cat /workspaces/nested/result.txt)" = NESTED_WRITE_OK
                test "${'$'}(cat /workspaces/official-created.txt)" = OFFICIAL_UPDATE_OK
                printf 'NEW_PROCESS_READ_OK\n'
            """.trimIndent()))
            assertTrue(reread.contains("NEW_PROCESS_READ_OK"))
            Log.i("TANUKI_PROBE", "INSTALLED_TOOLS bash=true node_spawn=true official_create=true official_update=true persisted_read=true model=false user_data=false")
        } finally { RuntimeManager.deleteTree(scratch) }
    }
}
