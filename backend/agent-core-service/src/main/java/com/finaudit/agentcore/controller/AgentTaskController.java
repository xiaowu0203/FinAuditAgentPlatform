package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.pojo.vo.StepVO;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import com.finaudit.agentcore.pojo.vo.TaskVO;
import com.finaudit.agentcore.service.AgentOrchestrator;
import com.finaudit.agentcore.service.AgentTaskService;
import com.finaudit.agentcore.service.AgentTaskStepService;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Agent 任务", description = "任务提交 / 详情 / 步骤 / 分页 / 断点续跑")
@RestController
@RequestMapping("/api/v1/tasks")
public class AgentTaskController {

    private final AgentTaskService taskService;
    private final AgentTaskStepService stepService;
    private final AgentOrchestrator orchestrator;

    public AgentTaskController(AgentTaskService taskService, AgentTaskStepService stepService,
                               AgentOrchestrator orchestrator) {
        this.taskService = taskService;
        this.stepService = stepService;
        this.orchestrator = orchestrator;
    }

    @Operation(summary = "提交任务", description = "创建一条 Agent 任务（taskType 缺省 GENERIC 通用分析；报销单走 POST /reimbursements 自动标记 REIMBURSEMENT），返回任务详情")
    @ApiResponse(responseCode = "200", description = "操作成功，body 为 R 包装的 TaskVO")
    @PostMapping
    public R<TaskVO> submit(@Valid @RequestBody TaskSubmitRequest request,
                            @RequestHeader(name = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.success(taskService.createTask(request, tenantId));
    }

    @Operation(summary = "任务详情", description = "按任务 ID 查询任务状态与概要信息")
    @GetMapping("/{id}")
    public R<TaskVO> detail(@PathVariable Long id) {
        return R.success(taskService.getTask(id));
    }

    @Operation(summary = "任务步骤", description = "查询任务已生成/已执行的步骤列表")
    @GetMapping("/{id}/steps")
    public R<List<StepVO>> steps(@PathVariable Long id) {
        return R.success(stepService.listVoByTask(id));
    }

    @Operation(summary = "任务分页查询", description = "按状态分页查询任务，status 为空查全部")
    @GetMapping
    public R<Page<TaskVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                @RequestParam(defaultValue = "10") int pageSize,
                                @RequestParam(required = false) String status) {
        return R.success(taskService.pageTask(pageNum, pageSize, status));
    }

    @Operation(summary = "断点续跑", description = "失败任务从失败步骤继续执行")
    @PostMapping("/{id}/resume")
    public R<Void> resume(@PathVariable Long id) {
        orchestrator.resume(id);
        return R.success();
    }
}
