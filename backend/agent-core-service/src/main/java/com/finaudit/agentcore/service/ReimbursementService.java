package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.ExpenseType;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.mapper.ExpenseReimbursementMapper;
import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementSubmitRequest;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.agentcore.pojo.vo.AttachmentVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementDetailVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementItemVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementVO;
import com.finaudit.agentcore.pojo.vo.TaskVO;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.FileServiceFeign;
import com.finaudit.starter.web.feign.dto.FileRecordVO;
import com.finaudit.starter.web.result.R;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报销单业务服务
 * 核心能力：报销单提交、分页查询、单据详情查询；
 * 提交流程具备完整事务原子性：单据入库→附件绑定→生成审核任务→回填任务ID，任一异常整体回滚；
 * 依赖能力：附件关联、文件远程校验、Agent审核任务创建、多租户自动隔离；
 * 安全约束：前端金额不信任、附件权限校验、单据访问人身份鉴权，禁止跨租户/跨员工查看单据。
 */
@Service
public class ReimbursementService {

    private final ExpenseReimbursementMapper reimbursementMapper;
    private final AttachmentService attachmentService;
    /** 文件服务远程Feign客户端，用于校验附件归属与存在性 */
    private final FileServiceFeign fileServiceFeign;
    /** Agent审核任务服务，负责创建单据自动审核流水线任务 */
    private final AgentTaskService taskService;

    public ReimbursementService(ExpenseReimbursementMapper reimbursementMapper,
                                AttachmentService attachmentService,
                                FileServiceFeign fileServiceFeign,
                                AgentTaskService taskService) {
        this.reimbursementMapper = reimbursementMapper;
        this.attachmentService = attachmentService;
        this.fileServiceFeign = fileServiceFeign;
        this.taskService = taskService;
    }

    /**
     * 提交报销单主流程，全事务原子操作
     * 流程：参数校验→附件权限校验→后端重算总额→报销单入库→绑定附件→生成审核任务→回填任务ID；
     * 全部操作在同一事务，任意步骤异常全部回滚，保证单据、附件、任务数据一致性；
     * 安全设计：不依赖前端传递总金额，远程校验附件归属租户，仅传递文件ID不携带OSS原始路径。
     * @param request 前端报销提交请求（明细、附件ID、费用类型、标题等）
     * @param tenantId 当前操作租户ID
     * @param applicantId 提交人员工ID
     * @return 报销单基础VO信息
     * @throws BizException 费用类型非法、附件不存在/跨租户、明细为空、文件服务调用失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ReimbursementVO submit(ReimbursementSubmitRequest request, Long tenantId, Long applicantId) {
        // 1. 校验费用类型枚举合法性，非法值直接抛业务异常
        ExpenseType.of(request.expenseType());

        // 2. 远程调用文件服务，校验附件存在且归属当前租户
        List<Long> fileRecordIds = request.fileRecordIds();
        List<FileRecordVO> files = fetchFiles(tenantId, fileRecordIds);

        // 3. 服务端重新计算报销总金额，不信任前端传入total，防止前端篡改金额
        BigDecimal total = computeTotal(request.items());

        // 4. 组装报销单实体并插入数据库
        ExpenseReimbursement reimb = ExpenseReimbursement.from(request, tenantId, applicantId, total);
        reimbursementMapper.insert(reimb);

        // 5. 将附件ID与当前报销单做关联绑定（file_record 引用；fileType 默认 OTHER，分类归 P2b OCR）
        attachmentService.attachToReimb(fileRecordIds, reimb.getId(), tenantId);

        // 6. 构造Agent任务快照入参，仅存储文件ID，OSS路径不在任务快照中透传
        Map<String, Object> inputParams = buildInputParams(request, reimb, total, files);

        // 7. 创建审核任务（显式标记业务类型 REIMBURSEMENT，规划器按业务注入财务提示词/工具），同事务内同步调用；
        //    申请人即任务创建人，落 agent_task.created_by
        TaskVO task = taskService.createTask(new TaskSubmitRequest(request.title(), inputParams, TaskType.REIMBURSEMENT),
                tenantId, applicantId);

        // 8. 将生成的审核任务ID回填至报销单，建立单据与任务关联关系
        reimb.applyTaskId(task.getId());
        reimbursementMapper.updateById(reimb);

        return ReimbursementVO.from(reimb);
    }

    /**
     * 后端计算报销明细总金额
     * 包内静态方法，提供单元测试入口；
     * 双重校验：JSR303前端校验+服务端空明细拦截，防止无明细单据提交。
     * @param items 报销明细列表
     * @return 明细金额累加总和
     * @throws BizException 明细集合为空时抛出
     */
    static BigDecimal computeTotal(List<ReimbursementItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("报销明细不能为空");
        }
        return items.stream()
                .map(ReimbursementItemRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 远程调用文件服务批量查询附件并校验权限
     * 校验规则：传入附件ID去重后，查询结果数量必须与去重数量一致；
     * 不一致代表存在文件不存在、文件归属其他租户的情况，拦截提交。
     * @param tenantId 当前租户ID
     * @param fileRecordIds 前端传入附件主键ID列表
     * @return 校验通过的附件元数据VO列表
     */
    private List<FileRecordVO> fetchFiles(Long tenantId, List<Long> fileRecordIds) {
        // 根据租户ID、文件id列表查询文件内容
        R<List<FileRecordVO>> resp = fileServiceFeign.getFiles(tenantId, fileRecordIds);
        if (resp.getCode() != 0) {
            throw new BizException("读取附件失败: " + resp.getMessage());
        }
        List<FileRecordVO> files = resp.getData();
        // 获取文件数量
        long distinct = fileRecordIds.stream().distinct().count();
        // 数量不匹配：附件不存在 / 附件属于其他租户
        if (files == null || files.size() != distinct) {
            throw new BizException("附件不存在或不属于当前租户");
        }
        return files;
    }

    /**
     * 构造Agent审核任务快照入参
     * 存储单据基础信息、明细、附件引用ID；
     * 设计规范：不存储OSS对象Key，审核流水线通过文件服务Feign远程拉取附件，减少隐私路径透传。
     * @param request 提交请求
     * @param reimb 已入库报销单实体
     * @param total 服务端计算报销总额
     * @param files 校验通过附件列表
     * @return 结构化任务入参Map
     */
    private Map<String, Object> buildInputParams(ReimbursementSubmitRequest request, ExpenseReimbursement reimb,
                                                 BigDecimal total, List<FileRecordVO> files) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reimbId", reimb.getId());
        params.put("reimbNo", reimb.getReimbNo());
        params.put("title", reimb.getTitle());
        params.put("expenseType", reimb.getExpenseType());
        params.put("deptName", reimb.getDeptName());
        params.put("claimDate", reimb.getClaimDate());
        params.put("applicantId", reimb.getApplicantId());
        params.put("items", ExpenseReimbursement.itemsToMaps(request.items()));
        params.put("attachments", fileRefs(files));
        params.put("claimedTotal", total);
        return params;
    }

    /**
     * 构造任务快照内附件简易引用结构
     * 仅保留文件主键ID、默认附件类型OTHER；
     * 票据OCR识别阶段会重新区分发票/行程单等细分类型。
     * @param files 附件元数据VO
     * @return 附件引用Map列表
     */
    private static List<Map<String, Object>> fileRefs(List<FileRecordVO> files) {
        List<Map<String, Object>> refs = new ArrayList<>(files.size());
        for (FileRecordVO f : files) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("id", f.id());
            ref.put("fileType", "OTHER");
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 员工分页查询本人报销单
     * 多租户拦截器自动隔离其他租户数据；
     * 筛选规则：指定申请人仅查询本人单据，status传空则查询全部状态单据；
     * 排序：单据ID倒序，最新单据靠前。
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @param status 单据审核状态（为空查全部）
     * @param applicantId 当前登录员工ID
     * @return 分页报销单基础VO
     */
    public Page<ReimbursementVO> page(int pageNum, int pageSize, String status, Long applicantId) {
        LambdaQueryWrapper<ExpenseReimbursement> wrapper = new LambdaQueryWrapper<ExpenseReimbursement>()
                .orderByDesc(ExpenseReimbursement::getId)
                .eq(applicantId != null, ExpenseReimbursement::getApplicantId, applicantId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ExpenseReimbursement::getStatus, status);
        }
        Page<ExpenseReimbursement> page = reimbursementMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<ReimbursementVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(ReimbursementVO::from).toList());
        return voPage;
    }

    /**
     * 报销单详情查询（含明细+附件预览链接）
     * 鉴权：仅单据提交人可查看，其他员工禁止访问；
     * 租户隔离由MybatisPlus多租户拦截器自动控制。
     * @param id 报销单主键ID
     * @param applicantId 当前登录员工ID
     * @return 完整详情VO（单据信息+明细+附件带预签名URL）
     * @throws BizException 单据不存在、查看人非单据提交人时抛出
     */
    public ReimbursementDetailVO detail(Long id, Long applicantId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(id);
        if (reimb == null) {
            throw new BizException("报销单不存在: " + id);
        }
        if (applicantId != null && !applicantId.equals(reimb.getApplicantId())) {
            throw new BizException("无权查看他人报销单");
        }
        List<ReimbursementItemVO> items = reimb.getItems() == null ? List.of()
                : reimb.getItems().stream().map(ReimbursementItemVO::from).toList();
        List<AttachmentVO> attachments = attachmentService.listVOsByReimbId(id);
        return ReimbursementDetailVO.from(reimb, items, attachments);
    }
}
