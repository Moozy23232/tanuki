package com.tanuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tanuki.runtime.*

@Composable
internal fun SetupOverview(model:TanukiUiModel,c:TanukiUiCallbacks,openDsh:()->Unit) {
    val s=model.snapshot
    val comp=model.components
    val toolsPassed=model.tools.passed && model.tools.ubuntuMarker.startsWith("${comp.ubuntuVersion} ") && comp.nodeInstalled
    val complete=listOf(comp.ubuntuInstalled,comp.nodeInstalled,toolsPassed,comp.dshInstalled)
    var step by rememberSaveable { mutableIntStateOf(complete.indexOfFirst { !it }.takeIf { it>=0 } ?: 3) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    val ready=complete.all { it } && s.environment==EnvironmentState.READY
    if(ready && !showSteps) {
        ReadyWorkspace(model,c,openDsh,onShowSteps={showSteps=true})
        return
    }
    BackHandler(enabled=step>0 || (ready && showSteps)) {
        if(step>0)step-- else showSteps=false
    }
    if(ready)TextButton(onClick={showSteps=false}) { Text("返回工作台") }
    LaunchedEffect(step,s.setupIdle,comp.selectedUbuntuVersion,comp.selectedNodeVersion,comp.ubuntuAutomatic,comp.nodeAutomatic) {
        if(s.setupIdle) {
            if(step==0 && comp.ubuntuAutomatic && !comp.ubuntuInstalled && model.ubuntuSpeed.measuredAtMillis==0L && !model.ubuntuSpeed.running)c.testSources(RuntimeComponent.UBUNTU)
            if(step==1 && comp.nodeAutomatic && !comp.nodeInstalled && model.nodeSpeed.measuredAtMillis==0L && !model.nodeSpeed.running)c.testSources(RuntimeComponent.NODE)
            if(step==3 && model.versions.isEmpty())c.checkUpdates()
        }
    }
    val steps=listOf(
        SetupGuideStep("ubuntu","Ubuntu","提供 DSH 所需的 Linux 系统与基础目录。",complete[0]),
        SetupGuideStep("node","Node.js","为 DSH 提供 JavaScript 运行环境。",complete[1]),
        SetupGuideStep("tools","基础工具","检查基础工具，只安装缺失的组件。",complete[2]),
        SetupGuideStep("dsh","DSH","安装完成后，即可配置模型并开始使用。",complete[3]))
    val prerequisite=when {
        step>0 && !comp.ubuntuInstalled -> 0
        step>1 && !comp.nodeInstalled -> 1
        step==3 && !toolsPassed -> 2
        else -> null
    }
    if(model.support.state==com.tanuki.web.WebViewSupportState.UPDATE_REQUIRED)WebViewCard(model.support,s.setupIdle,c)
    SetupGuide(steps,step,onStep={step=it},busy=s.setupBusy,
        isReady=ready,
        onOpenDsh={if(s.service!=ServiceState.RUNNING)c.command("START");openDsh()}) {
        if(s.service==ServiceState.RUNNING)SetupCaption("DSH 正在运行。停止服务后才能安装或更换组件，停止会中断当前任务。")
        if(prerequisite!=null)SetupPanel {
            Text("先完成 ${steps[prerequisite].title}",style=MaterialTheme.typography.titleMedium)
            SetupCaption("前一步就绪后才能安装${steps[step].title}。查看其他步骤不会中断当前安装。")
            OutlinedButton(onClick={step=prerequisite},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text("返回${steps[prerequisite].title}步骤")
            }
        } else if(step==2)FoundationTools(model,c) else EnvironmentSection(model,c,step)
        s.download?.takeIf { it.component!=steps[step].id }?.let { progress ->
            SetupPanel {
                Text("安装仍在继续",style=MaterialTheme.typography.titleMedium)
                ComponentDownloadProgress(ComponentProgressModel(s.stage,progress.source,progress.received,
                    progress.total,progress.bytesPerSecond,progress.reused))
                TextButton(onClick={step=steps.indexOfFirst { it.id==progress.component }.takeIf { it>=0 } ?: step}) {
                    Text("查看正在安装的步骤")
                }
            }
        }
        if(step==3 && comp.dshInstalled)SandboxGuidance(s)
    }
    OperationCard(s,c)
    RecoveryGuidance(s,c)
}

@Composable
private fun RecoveryGuidance(s:Snapshot,c:TanukiUiCallbacks) {
    if(s.update==UpdateState.RECOVERY_REQUIRED)SetupPanel {
        Text("上次更新需要确认",style=MaterialTheme.typography.titleMedium)
        SetupCaption("检查当前版本或恢复匹配快照后，再继续安装和启动。")
        TextButton(onClick={c.command("RESUME_UPDATE")},enabled=s.setupIdle) { Text("检查并保留当前版本") }
    }
}

@Composable
private fun ReadyWorkspace(model:TanukiUiModel,c:TanukiUiCallbacks,openDsh:()->Unit,onShowSteps:()->Unit) {
    val s=model.snapshot
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("你的移动开发空间",style=MaterialTheme.typography.headlineSmall)
        SetupCaption("管理本机项目，在 DeepSeek Harness 中开始工作。")
    }
    RecoveryGuidance(s,c)
    if(model.support.state==com.tanuki.web.WebViewSupportState.UPDATE_REQUIRED)WebViewCard(model.support,s.setupIdle,c)
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                        TanukiIcon(TanukiGlyph.Terminal,tint=MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                StatusPill(s.service.label,s.service==ServiceState.RUNNING)
            }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("DeepSeek Harness",style=MaterialTheme.typography.titleLarge)
                Text("DSH ${s.dsh}",style=MaterialTheme.typography.bodyMedium,
                    fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick={if(s.service!=ServiceState.RUNNING)c.command("START");openDsh()},
                enabled=!s.setupBusy && s.update!=UpdateState.RECOVERY_REQUIRED &&
                    model.support.state!=com.tanuki.web.WebViewSupportState.UPDATE_REQUIRED,
                shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                Text(if(s.service==ServiceState.RUNNING)"打开 DSH" else if(s.service==ServiceState.STARTING)"正在启动…" else "启动 DSH")
            }
        }
    }
    Surface(onClick=onShowSteps,shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            TanukiIcon(TanukiGlyph.Layers,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("运行环境已就绪",style=MaterialTheme.typography.titleSmall)
                SetupCaption("Ubuntu ${model.components.ubuntuVersion} · Node.js ${model.components.nodeVersion}")
                Text("查看安装步骤与版本",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            }
            TanukiIcon(TanukiGlyph.Chevron,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton(onClick={showPermissions=!showPermissions},modifier=Modifier.heightIn(min=48.dp)) { Text("文件与命令权限说明") }
    if(showPermissions)SandboxGuidance(s)
    OperationCard(s,c)
}

@Composable
private fun FoundationTools(model:TanukiUiModel,c:TanukiUiCallbacks) {
    val s=model.snapshot
    val current=model.tools.checked && model.tools.ubuntuMarker.startsWith("${model.components.ubuntuVersion} ")
    val groups=listOf(
        "Git、curl 与 HTTPS 证书" to setOf("git","curl","ca-certificates"),
        "Python、SSL 与虚拟环境" to setOf("python3","python3-venv"),
        "SSH 客户端与 OpenSSL" to setOf("openssh-client","openssl"),
        "代码搜索与 JSON" to setOf("ripgrep","jq"),
        "xz、ZIP 解压与压缩" to setOf("xz-utils","zip","unzip"),
        "文件识别、查看与同步" to setOf("file","less","rsync"),
        "补丁与轻量构建工具" to setOf("patch","make","pkg-config"),
        "工作区目录支持" to setOf("xdg-user-dirs"),
        "Node.js 运行库" to setOf("libatomic1","libstdc++6"))
    SetupPanel {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            groups.forEachIndexed { index, (name,ids) ->
                Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    Text(if(!current)"待检查" else if(model.tools.missing.any { it in ids })"需要补齐" else "已通过",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(index<groups.lastIndex)HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
        }
        SetupCaption(if(current && model.tools.passed)"检查已通过。安装 Ubuntu 和 Node 时也会自动补齐基础工具。" else "只安装缺失项，项目与 DSH 配置保留；首次升级会下载新增工具，请留意下方进度。")
        SetupCaption("只安装 SSH 客户端，不启动 SSH 服务或其他后台进程，也不会自动扩大 DSH 命令权限。")
        if(s.setupBusy && s.phasePercent!=null) {
            Text("${s.phasePercent}%",style=MaterialTheme.typography.headlineLarge,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress={s.phasePercent/100f},modifier=Modifier.fillMaxWidth())
            SetupCaption(s.stage)
        }
        Button(onClick={c.command("TOOLS")},enabled=s.setupIdle && model.components.ubuntuInstalled && model.components.nodeInstalled,
            modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.medium) {
            Text(if(s.setupBusy)"正在检查与补齐…" else if(current && model.tools.passed)"重新检查" else "检查并补齐")
        }
        if(s.service==ServiceState.RUNNING)SetupCaption("先停止 DSH，再检查和修复运行组件。")
    }
}

@Composable
internal fun SandboxGuidance(s:Snapshot) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("文件与命令权限",style=MaterialTheme.typography.titleMedium)
        when(s.confinement) {
            Confinement.UNAVAILABLE -> {
                SetupCaption("本机没有可用的命令沙箱。workspace-write 的文件工具可用，但 bash 会被阻止。")
                SetupCaption("需要执行命令时，在官方 DSH 的文件策略中明确选择 danger-full-access。该模式允许命令访问 App 权限范围内的其他文件与网络，仍是普通 Android App 权限。")
            }
            Confinement.PARTIAL -> SetupCaption("本机沙箱只有部分能力，请按 DSH 权限设置中的能力提示选择文件与命令策略。")
            Confinement.FULL -> SetupCaption("本机命令沙箱已检测可用，工具执行继续遵循 DSH 中的权限设置。")
            Confinement.UNKNOWN -> SetupCaption("沙箱能力尚未确认。若 DSH 提示 workspace-write 无法执行 bash，请先检查其文件策略与可用后端。")
        }
    }
}

@Composable
private fun SetupPanel(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable
private fun SetupCaption(text:String)=Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)

private val Snapshot.setupBusy:Boolean get()=operationActive
private val Snapshot.setupIdle:Boolean get()=!setupBusy && service in setOf(ServiceState.STOPPED,ServiceState.FAILED,ServiceState.INTERRUPTED)
