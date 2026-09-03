package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.ExpenseType;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.mapper.ExpenseReimbursementMapper;
import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitResult;
import com.finaudit.agentcore.pojo.dto.ReimbursementSubmitRequest;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import com.finaudit.agentcore.pojo.entity.ExpenseAttachment;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.agentcore.pojo.vo.AttachmentVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementDetailVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementItemVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementVO;
import com.finaudit.agentcore.pojo.vo.TaskVO;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.feign.FileServiceFeign;
import com.finaudit.starter.web.feign.TenantServiceFeign;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
import com.finaudit.starter.web.feign.dto.DuplicateItemVO;
import com.finaudit.starter.web.feign.dto.FileRecordVO;
import com.finaudit.starter.web.mask.util.MaskUtil;
import com.finaudit.starter.web.result.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 报销单业务服务
 * 核心能力：报销单提交、分页查询、单据详情查询；
 * 提交流程具备完整事务原子性：单据入库→附件绑定→生成审核任务→回填任务ID，任一异常整体回滚；
 * 依赖能力：附件关联、文件远程校验、Agent审核任务创建、多租户自动隔离；
 * 安全约束：前端金额不信任、附件权限校验、单据访问人身份鉴权，禁止跨租户/跨员工查看单据。
 */
@Service
public class ReimbursementService {

    private static final Logger log = LoggerFactory.getLogger(ReimbursementService.class);

    private final ExpenseReimbursementMapper reimbursementMapper;
    private final AttachmentService attachmentService;
    /** 文件服务远程Feign客户端，用于校验附件归属与存在性 */
    private final FileServiceFeign fileServiceFeign;
    /** Agent审核任务服务，负责创建单据自动审核流水线任务 */
    private final AgentTaskService taskService;
    /** tenant-service 部门契约（P3.5b 提交时部门存在性校验） */
    private final TenantServiceFeign tenantServiceFeign;

    public ReimbursementService(ExpenseReimbursementMapper reimbursementMapper,
                                AttachmentService attachmentService,
                                FileServiceFeign fileServiceFeign,
                                AgentTaskService taskService,
                                TenantServiceFeign tenantServiceFeign) {
        this.reimbursementMapper = reimbursementMapper;
        this.attachmentService = attachmentService;
        this.fileServiceFeign = fileServiceFeign;
        this.taskService = taskService;
        this.tenantServiceFeign = tenantServiceFeign;
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

        // 2.5 P3.5b 部门校验：传了 deptId 则必须为租户内真实部门，且须等于本人部门或持有 budget:viewAll
        validateDept(request, tenantId);

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
        params.put("deptId", reimb.getDeptId());
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
     * 报销单分页查询（P3b 可见性统一：finance 看本租户全量，普通用户仅本人）。
     * 多租户拦截器自动隔离其他租户数据；status 传空则查询全部状态单据；单据 ID 倒序。
     *
     * @param pageNum     页码
     * @param pageSize    每页条数
     * @param status      单据审核状态（为空查全部）
     * @param applicantId 当前登录员工 ID
     * @param finance     财务角色（admin/auditor）：跳过申请人过滤，看本租户全量
     */
    public Page<ReimbursementVO> page(int pageNum, int pageSize, String status, Long applicantId, boolean finance) {
        LambdaQueryWrapper<ExpenseReimbursement> wrapper = new LambdaQueryWrapper<ExpenseReimbursement>()
                .orderByDesc(ExpenseReimbursement::getId)
                .eq(!finance && applicantId != null, ExpenseReimbursement::getApplicantId, applicantId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ExpenseReimbursement::getStatus, status);
        }
        Page<ExpenseReimbursement> page = reimbursementMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<ReimbursementVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(ReimbursementVO::from).toList());
        return voPage;
    }

    /**
     * 报销单详情查询（含明细+附件预览链接）。
     * 鉴权（P3b 可见性统一）：非财务角色仅单据提交人可查看；财务可看本租户任意单据
     * （租户隔离由多租户拦截器自动控制，防跨租户）。
     *
     * @param id          报销单主键 ID
     * @param applicantId 当前登录员工 ID
     * @param finance     财务角色（admin/auditor）：跳过本人校验
     */
    public ReimbursementDetailVO detail(Long id, Long applicantId, boolean finance) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(id);
        if (reimb == null) {
            throw new BizException("报销单不存在: " + id);
        }
        if (!finance && applicantId != null && !applicantId.equals(reimb.getApplicantId())) {
            throw new BizException("无权查看他人报销单");
        }
        return toDetail(reimb);
    }

    /**
     * 按任务 ID 查报销单详情（P3b 工单详情供财务查看，**无申请人过滤**——鉴权由工单读接口控制）。
     * GENERIC 任务无关联报销单时返回 null。
     */
    public ReimbursementDetailVO detailByTaskId(Long taskId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectOne(new LambdaQueryWrapper<ExpenseReimbursement>()
                .eq(ExpenseReimbursement::getTaskId, taskId)
                .last("limit 1"));
        return reimb == null ? null : toDetail(reimb);
    }

    /**
     * 按报销单 ID 查关联任务 ID（P3b 提交人动作入口：报销单 → task_id 反写 → 工单 uk_task 解析）。
     * <p>报销单不存在或未关联任务返回 null，由调用方（工单状态机）落业务异常。</p>
     */
    public Long getTaskIdByReimbId(Long reimbId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(reimbId);
        return reimb == null ? null : reimb.getTaskId();
    }

    /** 按 ID 查报销单（可空；跨域越权校验用——budget_query 归属判定、工单等）。 */
    public ExpenseReimbursement getByReimbId(Long reimbId) {
        return reimbursementMapper.selectById(reimbId);
    }

    /**
     * P3.5b 提交部门校验（仅请求带 deptId 时生效，旧前端不传则沿用 dept_id null + 快照名）：
     * ① sys_dept 存在且属于租户（防虚构部门）；② 提交者须为本部门，或有 budget:viewAll 豁免任意部门。
     */
    private void validateDept(ReimbursementSubmitRequest request, Long tenantId) {
        Long deptId = request.deptId();
        if (deptId == null) {
            return;
        }
        // 判断租户ID+部门ID是否存在
        R<Boolean> exists = tenantServiceFeign.deptExists(tenantId, deptId);
        if (exists.getCode() != 0) {
            throw new BizException("部门校验失败: " + exists.getMessage());
        }
        if (!Boolean.TRUE.equals(exists.getData())) {
            throw new BizException("部门不存在或不属于当前租户");
        }
        // 获取上下文
        UserContext user = UserContextHolder.get();
        // 判断是否为本人部门或是否有 budget:viewAll 权限
        boolean viewAll = user != null && user.hasPerm("budget:viewAll");
        if (!viewAll) {
            Long ownDept = user == null ? null : user.getDeptId();
            if (!Objects.equals(ownDept, deptId)) {
                throw new BizException("只能为本部门提交报销（当前用户未绑定该部门），或需 budget:viewAll 权限");
            }
        }
    }

    /**
     * amend 重跑覆盖报销单明细与总额（P3b）：明细转 JSON Map + total_amount 重算覆盖 + 状态回 RUNNING。
     * <p>由 {@code AuditTicketService.amend} 在工单动作事务内调用；报销单不存在仅告警（GENERIC 任务无单据）。</p>
     */
    @Transactional
    public void updateAmountAndItemsByTaskId(Long taskId, List<ReimbursementItemRequest> items, BigDecimal total) {
        ExpenseReimbursement reimb = reimbursementMapper.selectOne(new LambdaQueryWrapper<ExpenseReimbursement>()
                .eq(ExpenseReimbursement::getTaskId, taskId)
                .last("limit 1"));
        if (reimb == null) {
            log.warn("任务 {} 无关联报销单，跳过明细/总额覆盖", taskId);
            return;
        }
        reimb.setItems(ExpenseReimbursement.itemsToMaps(items));
        reimb.setTotalAmount(total);
        reimb.setStatus(ReimbursementStatus.RUNNING.name());
        reimbursementMapper.updateById(reimb);
    }

    /**
     * 修改重跑覆盖报销单（P3b 工作流重设计，提交人 resubmit 的数据侧）。
     * <p>与 {@link #submit} 同事务语义：附件权限校验 → 服务端重算总额 → 覆盖明细相关字段
     * （expenseType/claimDate/remark/items/totalAmount/status=RUNNING）→ 附件重绑（解绑移除项 +
     * 绑定新增项）→ 组装新任务快照入参。</p>
     * <p><b>title / deptName 服务端强制沿用库内旧值</b>（请求体不含这两字段，用户确认决策 2），
     * 防提交人借重跑改标题/部门。</p>
     *
     * @param reimbId  报销单 ID（须已关联审核任务，否则无法定位同单续跑的工单）
     * @param request  修改后明细请求（无 title/deptName）
     * @param tenantId 租户 ID
     * @return 重跑编排契约：taskId + 新任务快照入参 + 重算总额（供工单状态机继续）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReimbursementResubmitResult resubmit(Long reimbId, ReimbursementResubmitRequest request,
                                                Long tenantId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(reimbId);
        if (reimb == null) {
            throw new BizException("报销单不存在: " + reimbId);
        }
        if (reimb.getTaskId() == null) {
            throw new BizException("报销单未关联审核任务，无法修改重跑: " + reimbId);
        }
        // 1. 费用类型枚举合法性
        ExpenseType.of(request.expenseType());
        // 2. 附件存在且归属租户校验
        List<FileRecordVO> files = fetchFiles(tenantId, request.fileRecordIds());
        // 3. 服务端重算总额（不信任前端）
        BigDecimal total = computeTotal(request.items());
        // 4. 覆盖明细相关字段（title/deptName 保留库内旧值，见方法注释）
        reimb.setExpenseType(request.expenseType());
        reimb.setClaimDate(request.claimDate());
        reimb.setRemark(request.remark());
        reimb.setItems(ExpenseReimbursement.itemsToMaps(request.items()));
        reimb.setTotalAmount(total);
        reimb.setStatus(ReimbursementStatus.RUNNING.name());
        reimbursementMapper.updateById(reimb);
        // 5. 附件重绑（解绑移除项 + 绑定新增项，未变项不动）
        attachmentService.rebindForReimb(request.fileRecordIds(), reimb.getId(), tenantId);
        // 6. 组装新任务快照入参（字段结构与提交链路一致，供 RuleBasedFlowEngine 重规划投影）
        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("reimbId", reimb.getId());
        inputParams.put("reimbNo", reimb.getReimbNo());
        inputParams.put("title", reimb.getTitle());
        inputParams.put("expenseType", reimb.getExpenseType());
        inputParams.put("deptName", reimb.getDeptName());
        inputParams.put("deptId", reimb.getDeptId());
        inputParams.put("claimDate", reimb.getClaimDate());
        inputParams.put("applicantId", reimb.getApplicantId());
        inputParams.put("items", ExpenseReimbursement.itemsToMaps(request.items()));
        inputParams.put("attachments", fileRefs(files));
        inputParams.put("claimedTotal", total);
        return new ReimbursementResubmitResult(reimb.getTaskId(), inputParams, total);
    }

    /**
     * 构建报销单数据快照（P3b 快照留痕）：顶层字段 + 明细 + 附件结构化信息。
     * <p>用于 audit_record.before_data/after_data：<b>不含预签名 URL 与 OSS 路径</b>
     * （项目约定不透传）；日期转字符串，避免快照序列化遇 LocalDate 缺 JavaTimeModule 报错。</p>
     */
    public Map<String, Object> buildSnapshot(Long reimbId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(reimbId);
        if (reimb == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reimbId", reimb.getId());
        snapshot.put("reimbNo", reimb.getReimbNo());
        snapshot.put("title", reimb.getTitle());
        snapshot.put("expenseType", reimb.getExpenseType());
        snapshot.put("deptName", reimb.getDeptName());
        snapshot.put("totalAmount", reimb.getTotalAmount());
        snapshot.put("claimDate", reimb.getClaimDate() == null ? null : reimb.getClaimDate().toString());
        snapshot.put("remark", reimb.getRemark());
        snapshot.put("status", reimb.getStatus());
        snapshot.put("items", reimb.getItems());
        List<Map<String, Object>> atts = new ArrayList<>();
        for (ExpenseAttachment a : attachmentService.listByReimbId(reimb.getId())) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("fileRecordId", a.getFileRecordId());
            ref.put("fileType", a.getFileType());
            ref.put("ocrStatus", a.getOcrStatus());
            // OCR结果进行脱敏
            ref.put("ocrResult", MaskUtil.maskSensitiveMap(a.getOcrResult(), null));
            atts.add(ref);
        }
        snapshot.put("attachments", atts);
        return snapshot;
    }

    /**
     * 查询报销单归属租户
     * <p>仅返回 tenant_id，跨域只读，数据访问收敛本类；不存在返回 null（由调用方判定为越权/不存在）。</p>
     * @param reimbId 报销单ID
     * @return 该报销单的 tenantId；不存在返回 null
     */
    public Long findTenantIdByReimb(Long reimbId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectById(reimbId);
        return reimb == null ? null : reimb.getTenantId();
    }

    /** 公共详情组装：明细 + 附件（含 OCR 字段）。 */
    private ReimbursementDetailVO toDetail(ExpenseReimbursement reimb) {
        List<ReimbursementItemVO> items = reimb.getItems() == null ? List.of()
                : reimb.getItems().stream().map(ReimbursementItemVO::from).toList();
        List<AttachmentVO> attachments = attachmentService.listVOsByReimbId(reimb.getId());
        return ReimbursementDetailVO.from(reimb, items, attachments);
    }

    /**
     * 按任务ID回写报销单审核状态（任务终态同步，报销单→任务单向关联的反向闭环）。
     * 幂等：目标状态与当前状态相同则跳过；报销单不存在仅告警不影响调用方。
     * 状态值含义见 {@link ReimbursementStatus}；仅 REIMBURSEMENT 类任务由编排器触发本方法。
     * @param taskId 关联 agent_task.id（提交时反写）
     * @param status 目标审核状态
     */
    public void updateStatusByTaskId(Long taskId, ReimbursementStatus status) {
        ExpenseReimbursement reimb = reimbursementMapper.selectOne(
                new LambdaQueryWrapper<ExpenseReimbursement>()
                        .eq(ExpenseReimbursement::getTaskId, taskId)
                        .last("limit 1"));
        if (reimb == null) {
            log.warn("任务 {} 无关联报销单，跳过状态回写", taskId);
            return;
        }
        String oldStatus = reimb.getStatus();
        if (status.name().equals(oldStatus)) {
            return;
        }
        reimb.setStatus(status.name());
        reimbursementMapper.updateById(reimb);
        log.info("报销单 {} 审核状态回写: {} → {}", reimb.getReimbNo(), oldStatus, status);
    }

    /**
     * 按任务 ID 作废报销单（P3b：提交人撤回 / 财务同意撤销）：状态置 CANCELLED + 附件解绑。
     * <p>作废即释放附件占用（同 file_record 可复用），重复报销检测已排除 CANCELLED 单据。</p>
     * <p><b>TODO（后置，登记于 future-roadmap §2）</b>：产品诉求「废单也可查看当时的票据」未落地。
     * 现状：作废解绑后废单详情附件区为空，历史票据引用仅存在于审计留痕
     * （audit_record.before_data 快照，含 fileRecordId/fileType/ocrStatus/ocrResult，无路径/预签名 URL）。
     * 候选落地：作废时把当前附件引用快照持久到报销单新增 JSON 列（如 attachments_snapshot），
     * toDetail 遇状态 CANCELLED 且活附件为空时按快照重建 VO（联取元数据 + 预签名 URL）——
     * 无新服务依赖、无循环；勿回改作废解绑语义（作废为终态，保留绑定会锁死 file_record 复用到新单）。</p>
     */
    @Transactional
    public void markCancelledByTaskId(Long taskId) {
        ExpenseReimbursement reimb = reimbursementMapper.selectOne(
                new LambdaQueryWrapper<ExpenseReimbursement>()
                        .eq(ExpenseReimbursement::getTaskId, taskId)
                        .last("limit 1"));
        if (reimb == null) {
            log.warn("任务 {} 无关联报销单，跳过作废回写", taskId);
            return;
        }
        if (!ReimbursementStatus.CANCELLED.name().equals(reimb.getStatus())) {
            reimb.setStatus(ReimbursementStatus.CANCELLED.name());
            reimbursementMapper.updateById(reimb);
        }
        attachmentService.unbindByReimb(reimb.getId());
        log.info("报销单 {} 已作废（CANCELLED），附件解绑", reimb.getReimbNo());
    }

    /**
     * 重复报销检测工具方法（P2b duplicate_check 工具委托，报销域数据收敛本类）。
     * 风控审计Agent依赖能力：检索同一员工、同金额、前后30天内的历史报销单，匹配商户判定疑似重复单据
     * 判定规则：
     * 1. 同一申请人、排除当前单据、金额完全一致、报销日期前后30天区间
     * 2. 匹配附件OCR解析出的商户名称（大小写忽略），标记疑似重复
     * @param tenantId 租户ID（MybatisPlus多租户拦截器自动过滤，方法入参预留扩展）
     * @param reimbId 当前待校验报销单主键ID
     * @return 重复检测结果VO，无疑似单据返回空对象
     * @throws BizException 报销单不存在时抛出业务异常
     */
    public DuplicateCheckVO queryDuplicates(Long tenantId, Long reimbId) {
        // 查询当前待校验报销单主记录
        ExpenseReimbursement current = reimbursementMapper.selectById(reimbId);
        if (current == null) {
            throw new BizException("报销单不存在: " + reimbId);
        }

        // 提取当前单据第一张有效OCR识别的商户名称
        String currentMerchant = firstMerchant(current.getId());

        // 计算日期区间：报销日期前后各30天；无报销日期则区间条件失效
        LocalDate from = current.getClaimDate() == null ? null : current.getClaimDate().minusDays(30);
        LocalDate to = current.getClaimDate() == null ? null : current.getClaimDate().plusDays(30);

        // 批量筛选疑似候选报销单
        List<ExpenseReimbursement> candidates = reimbursementMapper.selectList(
                new LambdaQueryWrapper<ExpenseReimbursement>()
                        // 同一申请人
                        .eq(ExpenseReimbursement::getApplicantId, current.getApplicantId())
                        // 排除自身单据
                        .ne(ExpenseReimbursement::getId, reimbId)
                        // 排除已作废单据（撤回/撤销后不得再当疑似重复候选）
                        .ne(ExpenseReimbursement::getStatus, ReimbursementStatus.CANCELLED.name())
                        // 报销总金额完全相等
                        .eq(ExpenseReimbursement::getTotalAmount, current.getTotalAmount())
                        // 有日期才加30天区间过滤
                        .between(from != null && to != null, ExpenseReimbursement::getClaimDate, from, to)
                        // 最新单据排在前面
                        .orderByDesc(ExpenseReimbursement::getId));

        // 无候选单据，直接返回空结果
        if (candidates.isEmpty()) {
            return DuplicateCheckVO.empty();
        }

        // 遍历候选单据，匹配商户生成疑似重复条目
        List<DuplicateItemVO> suspected = new ArrayList<>(candidates.size());
        for (ExpenseReimbursement c : candidates) {
            // 商户名称大小写一致则标记为高度疑似重复
            String merchant = firstMerchant(c.getId());
            boolean matched = currentMerchant != null && !currentMerchant.isBlank()
                    && merchant != null && currentMerchant.equalsIgnoreCase(merchant);
            suspected.add(new DuplicateItemVO(c.getId(), c.getReimbNo(), c.getTitle(), c.getTotalAmount(),
                    c.getClaimDate() == null ? null : c.getClaimDate().toString(), merchant, matched));
        }
        return new DuplicateCheckVO(suspected);
    }

    /**
     * 根据报销单ID，读取附件OCR结果，获取第一个非空商户名称
     * 业务约束：报销明细无商户字段，商户信息仅能从票据OCR解析数据获取
     * 匹配逻辑：遍历该单据全部附件，取第一条ocrResult内merchant字段，无则返回null
     * @param reimbId 报销单主键
     * @return 识别到的商户名称，无OCR数据/无商户字段返回null
     */
    private String firstMerchant(Long reimbId) {
        // 查询该报销单全部附件
        for (ExpenseAttachment a : attachmentService.listByReimbId(reimbId)) {
            // 附件无OCR识别结果，跳过
            if (a.getOcrResult() == null) {
                continue;
            }
            // 读取OCR json中的商户字段
            Object merchant = a.getOcrResult().get("merchant");
            if (merchant != null && !merchant.toString().isBlank()) {
                return merchant.toString();
            }
        }
        return null;
    }
}
