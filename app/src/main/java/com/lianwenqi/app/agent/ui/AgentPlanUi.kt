package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.agent.data.entity.AgentBranchSource
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.agent.execution.AgentExecutionPolicy
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanStep
import com.lianwenqi.app.agent.model.AgentRiskLevel

/** 计划卡片：思考摘要默认折叠，步骤与风险等级清晰展示 */
@Composable
fun AgentPlanCard(
    planView: AgentPlanView,
    task: AgentTask,
    planning: Boolean,
    executing: Boolean,
    hasPendingWrites: Boolean = false,
    branchesAvailable: Boolean = false,
    planEditing: Boolean = false,
    editingPlan: AgentPlan? = null,
    planEditError: String? = null,
    onPlanChange: (AgentPlan) -> Unit = {},
    onStartPlanEdit: () -> Unit = {},
    onSavePlanEdit: () -> Unit = {},
    onCancelPlanEdit: () -> Unit = {},
    onOpenBranches: () -> Unit = {},
    onRegenerate: () -> Unit,
    onConfirm: () -> Unit,
    onStartExecution: () -> Unit,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onCancelTask: () -> Unit,
    onDeleteTask: () -> Unit
) {
    val plan = planView.plan
    var thinkingExpanded by remember(plan) { mutableStateOf(false) }
    val blockedSteps = plan.steps.filterNot {
        AgentExecutionPolicy.isCapabilityExecutable(it.capabilityKey)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.taskTitle.ifBlank { task.title },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    agentTaskStatusLabel(task.status, planning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agentWorkModeLabel(task.workMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task.parentTaskId != null) {
                    Text(
                        " · 分支任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (branchesAvailable && !planEditing) {
                    TextButton(onClick = onOpenBranches) { Text("分支") }
                }
            }
            if (plan.goalSummary.isNotBlank() && !planEditing) {
                Spacer(Modifier.height(4.dp))
                Text(
                    plan.goalSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            val draft = editingPlan
            if (planEditing && draft != null) {
                StructuredPlanEditor(
                    plan = draft,
                    error = planEditError,
                    onChange = onPlanChange,
                    onSave = onSavePlanEdit,
                    onCancel = onCancelPlanEdit
                )
                return@Column
            }

            if (plan.thinkingSummary.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { thinkingExpanded = !thinkingExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("思考摘要", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (thinkingExpanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (thinkingExpanded) "收起" else "展开"
                    )
                }
                if (thinkingExpanded) {
                    Text(
                        plan.thinkingSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (planView.validation.unknownCapabilities.isNotEmpty()) {
                Text(
                    "包含未登记能力：" + planView.validation.unknownCapabilities.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            val blockedCapabilities = planView.validation.notYetAvailableCapabilities
                .filterNot { AgentExecutionPolicy.isCapabilityExecutable(it) }
            if (blockedCapabilities.isNotEmpty()) {
                Text(
                    "以下能力当前阶段不可执行：" +
                        blockedCapabilities.joinToString("、") { key ->
                            AgentCapabilityCatalog.find(key)?.displayName ?: key
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (blockedSteps.isNotEmpty()) {
                Text(
                    "存在当前阶段不可执行的步骤：" +
                        blockedSteps.joinToString("、") { it.title },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "请先修改计划或重新生成，移除这些步骤后再开始执行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            plan.steps.forEachIndexed { index, step ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                AgentPlanStepRow(index + 1, step)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "执行阶段会调用工具；写入操作只生成预览，必须经你确认后才会修改小说数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (planView.confirmed) {
                Text(
                    when {
                        hasPendingWrites -> "存在待确认写入操作；请先批准或拒绝"
                        executing -> "计划已确认，正在执行"
                        task.status == AgentTaskStatus.COMPLETED -> "计划已确认，执行已完成"
                        else -> "计划已确认；开始执行需要单独点击下方按钮"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasPendingWrites) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (planning) {
                    TextButton(onClick = onCancelTask) { Text("停止规划") }
                } else {
                    if (planView.confirmed && task.status == AgentTaskStatus.WAITING_CONFIRMATION) {
                        TextButton(
                            onClick = onStartExecution,
                            enabled = !executing && !hasPendingWrites &&
                                !plan.needsClarification && blockedSteps.isEmpty()
                        ) { Text("开始执行") }
                    }
                    if (task.status == AgentTaskStatus.RUNNING) {
                        TextButton(onClick = onPauseTask) { Text("暂停任务") }
                    }
                    if (task.status == AgentTaskStatus.PAUSED) {
                        TextButton(onClick = onResumeTask) { Text("恢复任务") }
                    }
                    TextButton(
                        onClick = onRegenerate,
                        enabled = !executing && !hasPendingWrites
                    ) { Text("重新生成") }
                    TextButton(
                        onClick = onStartPlanEdit,
                        enabled = !executing && !hasPendingWrites && !plan.needsClarification
                    ) { Text("修改计划") }
                    TextButton(
                        onClick = onConfirm,
                        enabled = !executing && !hasPendingWrites &&
                            task.status == AgentTaskStatus.WAITING_CONFIRMATION &&
                            !planView.confirmed && !plan.needsClarification
                    ) { Text("确认计划") }
                    TextButton(
                        onClick = onCancelTask,
                        enabled = !executing && task.status != AgentTaskStatus.CANCELLED
                    ) { Text("取消任务") }
                    TextButton(
                        onClick = onDeleteTask,
                        enabled = !executing && !hasPendingWrites
                    ) { Text("删除任务") }
                }
            }
        }
    }
}

/** 工作模式展示文案 */
fun agentWorkModeLabel(mode: String): String = when (mode) {
    AgentWorkMode.AUTO -> "自动模式"
    AgentWorkMode.PLAN -> "计划模式"
    else -> mode
}

/** 分支来源展示文案 */
fun agentBranchSourceLabel(source: String): String = when (source) {
    AgentBranchSource.USER_EDIT -> "由提问编辑 / 重新生成创建"
    AgentBranchSource.PLAN_REGENERATE -> "由重新生成计划创建"
    AgentBranchSource.PLAN_EDIT -> "由修改计划创建"
    else -> "根任务"
}

/** 结构化计划编辑器：保持计划卡样式，按标题 / 目标 / 步骤分模块编辑 */
@Composable
private fun StructuredPlanEditor(
    plan: AgentPlan,
    error: String?,
    onChange: (AgentPlan) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = plan.taskTitle,
            onValueChange = { onChange(plan.copy(taskTitle = it)) },
            label = { Text("任务标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = plan.goalSummary,
            onValueChange = { onChange(plan.copy(goalSummary = it)) },
            label = { Text("目标概括") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = plan.thinkingSummary,
            onValueChange = { onChange(plan.copy(thinkingSummary = it)) },
            label = { Text("思考摘要") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        plan.steps.forEachIndexed { index, step ->
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            PlanStepEditor(
                index = index,
                step = step,
                canDelete = plan.steps.size > 1,
                onChange = { updated ->
                    onChange(
                        plan.copy(
                            steps = plan.steps.toMutableList().also { it[index] = updated }
                        )
                    )
                },
                onDelete = {
                    val remaining = plan.steps
                        .filterIndexed { i, _ -> i != index }
                        .map { item ->
                            item.copy(
                                dependencies = item.dependencies.filter { it != step.stepId }
                            )
                        }
                    onChange(plan.copy(steps = remaining))
                }
            )
        }

        TextButton(
            onClick = {
                onChange(
                    plan.copy(
                        steps = plan.steps + AgentPlanStep(
                            stepId = nextStepId(plan.steps),
                            title = "新步骤",
                            description = "",
                            capabilityKey = AgentCapabilityCatalog.all.firstOrNull()?.key.orEmpty(),
                            expectedOutput = "",
                            dependencies = emptyList(),
                            riskLevel = AgentRiskLevel.LOW
                        )
                    )
                )
            }
        ) { Text("+ 添加步骤") }

        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            "保存后会创建新的计划分支；当前计划保持不变，保存也不等于确认执行。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onSave,
                enabled = plan.taskTitle.isNotBlank() && plan.goalSummary.isNotBlank()
            ) { Text("保存为新分支") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 单个计划步骤的结构化编辑 */
@Composable
private fun PlanStepEditor(
    index: Int,
    step: AgentPlanStep,
    canDelete: Boolean,
    onChange: (AgentPlanStep) -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "步骤 ${index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (canDelete) {
                TextButton(onClick = onDelete) { Text("删除步骤") }
            }
        }
        OutlinedTextField(
            value = step.title,
            onValueChange = { onChange(step.copy(title = it)) },
            label = { Text("步骤标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = step.description,
            onValueChange = { onChange(step.copy(description = it)) },
            label = { Text("步骤说明") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        CapabilityPicker(
            currentKey = step.capabilityKey,
            onSelect = { onChange(step.copy(capabilityKey = it)) }
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = step.expectedOutput,
            onValueChange = { onChange(step.copy(expectedOutput = it)) },
            label = { Text("预期产出") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "风险等级",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AgentRiskLevel.selectable.forEach { risk ->
                FilterChip(
                    selected = AgentRiskLevel.normalize(step.riskLevel) == risk,
                    onClick = { onChange(step.copy(riskLevel = risk)) },
                    label = { Text(AgentRiskLevel.label(risk)) }
                )
            }
        }
        if (step.dependencies.isNotEmpty()) {
            Text(
                "依赖：${step.dependencies.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 能力选择：展示中文名称，底层仍保存 capabilityKey */
@Composable
private fun CapabilityPicker(currentKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val capability = AgentCapabilityCatalog.find(currentKey)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    capability?.displayName ?: currentKey.ifBlank { "选择能力" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (currentKey.isNotBlank()) {
                    Text(
                        currentKey,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择能力")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 260.dp)
                .heightIn(max = 360.dp)
        ) {
            AgentCapabilityCatalog.all.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.displayName)
                            Text(
                                item.key,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelect(item.key)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun nextStepId(steps: List<AgentPlanStep>): String {
    val max = steps
        .mapNotNull { it.stepId.removePrefix("step_").toIntOrNull() }
        .maxOrNull() ?: 0
    return "step_%03d".format(max + 1)
}

@Composable
private fun AgentPlanStepRow(index: Int, step: AgentPlanStep) {
    val capability = AgentCapabilityCatalog.find(step.capabilityKey)
    val executable = AgentExecutionPolicy.isCapabilityExecutable(step.capabilityKey)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$index. ${step.title}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            RiskBadge(step.riskLevel)
        }
        if (step.description.isNotBlank()) {
            Text(
                step.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (step.expectedOutput.isNotBlank()) {
            Text("预期产出：${step.expectedOutput}", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "能力：" + (capability?.displayName ?: step.capabilityKey) +
                "（" + when {
                    capability == null -> "未登记"
                    AgentExecutionPolicy.isWriteCapability(step.capabilityKey) ->
                        "可执行（写入需确认）"

                    executable -> "可执行（只读）"
                    else -> "当前阶段不可执行"
                } + "）",
            style = MaterialTheme.typography.labelSmall,
            color = if (capability == null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (step.dependencies.isNotEmpty()) {
            Text(
                "依赖：${step.dependencies.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RiskBadge(risk: String) {
    val color = when (risk) {
        AgentRiskLevel.CRITICAL, AgentRiskLevel.HIGH -> Color(0xFFE53935)
        AgentRiskLevel.MEDIUM -> Color(0xFFF57C00)
        else -> Color(0xFF2E7D32)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            AgentRiskLevel.label(risk),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 任务分支导航：父分支、同级分支与直接子分支 */
@Composable
fun AgentBranchDialog(
    current: AgentTask,
    parent: AgentTask?,
    siblings: List<AgentTask>,
    children: List<AgentTask>,
    onOpen: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务分支") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        current.title.ifBlank { "新任务" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        agentWorkModeLabel(current.workMode) + " · " +
                            agentBranchSourceLabel(current.branchSource),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (parent != null) {
                    item { BranchSectionTitle("父分支") }
                    item { BranchRow(parent, onOpen) }
                }
                if (siblings.isNotEmpty()) {
                    item { BranchSectionTitle("同级分支") }
                    items(siblings, key = { it.id }) { task -> BranchRow(task, onOpen) }
                }
                if (children.isNotEmpty()) {
                    item { BranchSectionTitle("子分支") }
                    items(children, key = { it.id }) { task -> BranchRow(task, onOpen) }
                }
                if (parent == null && siblings.isEmpty() && children.isEmpty()) {
                    item {
                        Text(
                            "暂无其他分支。编辑提问、重新生成或修改计划后会创建新分支。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun BranchSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun BranchRow(task: AgentTask, onOpen: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.userGoal.ifBlank { task.title },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                agentTaskStatusLabel(task.status, false) + " · " +
                    agentWorkModeLabel(task.workMode) + " · " +
                    agentBranchSourceLabel(task.branchSource),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = { onOpen(task.id) }) { Text("打开") }
    }
}

/** 当前任务存在分支关系时的入口条 */
@Composable
fun AgentBranchBar(
    current: AgentTask,
    parent: AgentTask?,
    siblingCount: Int,
    childCount: Int,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    append("分支 · ")
                    append(agentWorkModeLabel(current.workMode))
                    if (parent != null) append(" · 父：${parent.title}")
                    if (siblingCount > 0) append(" · 同级 $siblingCount")
                    if (childCount > 0) append(" · 子 $childCount")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpen) { Text("查看") }
        }
    }
}

/** 底部澄清选择框：内容区域可滚动，操作按钮固定在底部；使用应用内浮层避免弹窗窗口尺寸异常 */
@Composable
fun AgentClarificationSheet(
    clarification: AgentClarificationView,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var input by remember(clarification.id) { mutableStateOf("") }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        val maxSheetHeight = maxHeight * 0.85f
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .imePadding()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(8.dp))
                Text("Agent 需要你补充信息", style = MaterialTheme.typography.titleMedium)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(clarification.question, style = MaterialTheme.typography.bodyMedium)
                    if (clarification.options.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "可选回答",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        clarification.options.forEach { option ->
                            OutlinedButton(
                                onClick = { input = option },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            ) {
                                Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("自行输入补充信息") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
                    )
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("稍后再说") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(input.trim()) },
                        enabled = input.isNotBlank()
                    ) { Text("提交并继续") }
                }
            }
        }
    }
}

/** 待补充信息卡片：计划生成前只展示这个入口，不展示计划本身 */
@Composable
fun AgentClarificationPendingCard(
    clarification: AgentClarificationView,
    onOpen: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "等待你补充信息",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                clarification.question,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onOpen) { Text("回答 Agent 的问题") }
        }
    }
}

/** 任务列表：查看详情、打开任务、删除尚未执行的任务 */
@Composable
fun AgentTaskListDialog(
    tasks: List<AgentTask>,
    currentTaskId: Long?,
    planningTaskId: Long?,
    onOpen: (Long) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<AgentTask?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务列表") },
        text = {
            if (tasks.isEmpty()) {
                Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDetails(task.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title.ifBlank { "新任务" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (planningTaskId == task.id) {
                                        "规划中…"
                                    } else {
                                        agentTaskRuntimeStatusLabel(task, false)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (task.id == currentTaskId) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            TextButton(onClick = { onOpen(task.id) }) { Text("打开") }
                            TextButton(onClick = { pendingDelete = task }) { Text("删除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = {
                Text(
                    "将删除「${target.title.ifBlank { "新任务" }}」及其任务消息、计划事件。" +
                        "不会删除普通聊天或小说数据。已有执行记录的任务不能删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        pendingDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 任务状态展示；PLANNING 且不在当前请求中时提示可能已中断 */
fun agentTaskStatusLabel(status: String, planning: Boolean): String = when (status) {
    AgentTaskStatus.CREATED -> "待规划"
    AgentTaskStatus.PLANNING -> if (planning) "规划中…" else "规划可能已中断，可重试"
    AgentTaskStatus.RUNNING -> "只读执行中"
    AgentTaskStatus.WAITING_CONFIRMATION -> "等待审核计划"
    AgentTaskStatus.PAUSED -> "已暂停"
    AgentTaskStatus.COMPLETED -> "已完成"
    AgentTaskStatus.FAILED -> "失败"
    AgentTaskStatus.CANCELLED -> "已取消"
    else -> status
}
