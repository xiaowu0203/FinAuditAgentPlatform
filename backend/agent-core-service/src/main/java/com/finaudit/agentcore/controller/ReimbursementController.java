package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.pojo.dto.ReimbursementSubmitRequest;
import com.finaudit.agentcore.pojo.vo.ReimbursementDetailVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementVO;
import com.finaudit.agentcore.service.ReimbursementService;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 报销单接口（单据闭环：提交 → 落单 → 服务内直调建审核任务）。
 */
@Tag(name = "报销单", description = "报销单提交与审核闭环")
@RestController
@RequestMapping("/api/v1/reimbursements")
public class ReimbursementController {

    private final ReimbursementService reimbursementService;

    public ReimbursementController(ReimbursementService reimbursementService) {
        this.reimbursementService = reimbursementService;
    }

    @Operation(summary = "提交报销单（生成审核任务）")
    @PostMapping
    public R<ReimbursementVO> submit(@Valid @RequestBody ReimbursementSubmitRequest request,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) Long applicantId) {
        if (tenantId == null) {
            throw new BizException("缺少租户标识 X-Tenant-Id，请通过网关访问");
        }
        if (applicantId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        return R.success(reimbursementService.submit(request, tenantId, applicantId));
    }

    @Operation(summary = "报销单分页查询", description = "仅本人，status 为空查全部")
    @GetMapping
    public R<Page<ReimbursementVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "10") int pageSize,
                                         @RequestParam(required = false) String status,
                                         @RequestHeader(value = "X-User-Id", required = false) Long applicantId) {
        return R.success(reimbursementService.page(pageNum, pageSize, status, applicantId));
    }

    @Operation(summary = "报销单详情", description = "含明细 + 附件预签名 URL")
    @GetMapping("/{id}")
    public R<ReimbursementDetailVO> detail(@PathVariable Long id,
                                           @RequestHeader(value = "X-User-Id", required = false) Long applicantId) {
        return R.success(reimbursementService.detail(id, applicantId));
    }
}
