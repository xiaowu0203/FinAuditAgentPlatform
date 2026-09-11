package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.InvoiceRecordMapper;
import com.finaudit.agentcore.mapper.InvoiceReimbLinkMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceRecord;
import com.finaudit.agentcore.pojo.entity.InvoiceReimbLink;
import com.finaudit.starter.web.feign.dto.InvoiceMatchVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发票标识符投影服务（invoice_record 实体数据访问仅允许在本类，见 AGENTS.md §5.9）。
 *
 * <p><b>存在意义（P3.8 R2 / 业务走查 B-3）</b>：发票代码与号码在 OCR 链路里早已解析出来，
 * 却既没进 {@code OcrExtractTool.normalize} 的输出，也没有可索引的落点，导致下游查重
 * 只能靠「同申请人 + 金额完全相等 + 日期±30天」的启发式，把正常单据误判为重复。
 * 本服务把票号投影为普通列，使「同一张票是否已报销」可用唯一索引直接判定。</p>
 *
 * <p><b>写入口径</b>：唯一键 {@code (tenant_id, invoice_code, invoice_num, deleted)}。
 * 票号缺失（火车票/打车票等无票号的票据，或 OCR 未识别出）时<b>整体跳过</b>——
 * 若用空串占位落库，同租户内所有无票号票据会挤在同一条唯一键上互相冲突。</p>
 *
 * <p><b>依赖方向</b>：本服务<b>不</b>注入 {@code AttachmentService}。调用方
 * （{@code AttachmentService.updateOcrResult}）手里已有附件实体，{@code reimbId} 由入参传入；
 * 若反向注入会形成 {@code AttachmentService ⇄ InvoiceRecordService} 构造器循环依赖，
 * Spring 在禁止循环引用（默认行为）下会直接启动失败。</p>
 */
@Service
public class InvoiceRecordService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceRecordService.class);

    /** 金额比对容差（R3-3）：票面与明细拆分难免四舍五入，0.01 以内视为一致 */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** 合法发票代码位数（R3-5 离线验真）：10=增值税专票/普票（旧），12=普票/卷票，20=电子发票 */
    private static final Set<Integer> INVOICE_CODE_LENGTHS = Set.of(10, 12, 20);

    /** 开票日期最大可接受年限（R3-5）：超过视为明显异常 */
    private static final int INVOICE_MAX_AGE_YEARS = 10;

    private final InvoiceRecordMapper invoiceRecordMapper;
    /** 发票—报销单归属（R3 修复：承接一票多单，见 InvoiceReimbLink 类注释） */
    private final InvoiceReimbLinkMapper invoiceReimbLinkMapper;

    public InvoiceRecordService(InvoiceRecordMapper invoiceRecordMapper,
                                InvoiceReimbLinkMapper invoiceReimbLinkMapper) {
        this.invoiceRecordMapper = invoiceRecordMapper;
        this.invoiceReimbLinkMapper = invoiceReimbLinkMapper;
    }

    /**
     * 从 OCR 结果投影发票标识符（幂等）。
     *
     * <p>幂等语义：同一张票再次被识别（重复上传同一发票 / 任务重跑再次 OCR）不新增行，
     * 只累加 {@code seen_count} 并刷新来源附件与归属单据；并发插入由唯一键兜底，
     * 撞键后转为「读取既有行 + 累加」，不会让调用方失败。</p>
     *
     * @param attachmentId 来源 {@code expense_attachment.id}
     * @param fileRecordId 来源 {@code file_record.id}
     * @param reimbId      归属报销单ID（调用方从附件实体取；OCR 可能先于绑定完成，此处为 null）
     * @param ocrResult    OCR 归一化结果（含 invoiceCode/invoiceNum/taxNo/amount/ocrDate）
     */
    @Transactional
    public void project(Long attachmentId, Long fileRecordId, Long reimbId, Map<String, Object> ocrResult) {
        String invoiceCode = asText(ocrResult, "invoiceCode");
        String invoiceNum = asText(ocrResult, "invoiceNum");
        // 无票号不投影：无票号票据无法参与按票查重，占位落库只会制造唯一键冲突
        if (invoiceNum == null) {
            log.debug("OCR 结果无发票号码，跳过投影: attachmentId={}, fileRecordId={}", attachmentId, fileRecordId);
            return;
        }

        InvoiceRecord existing = findByInvoiceKey(invoiceCode, invoiceNum);
        if (existing != null) {
            existing.applySeenAgain(attachmentId, fileRecordId, reimbId);
            invoiceRecordMapper.updateById(existing);
            // R3 修复：同时建立「本票 → 本单」归属（投影行只能记一个 reimb_id，多对多靠本表）
            linkToReimb(existing.getId(), reimbId, fileRecordId);
            log.info("发票投影命中既有记录，累加识别次数: invoiceNum={}, seenCount={}, reimbId={}",
                    invoiceNum, existing.getSeenCount(), existing.getReimbId());
            return;
        }

        InvoiceRecord record = InvoiceRecord.from(invoiceCode, invoiceNum,
                asText(ocrResult, "taxNo"), reimbId, fileRecordId, attachmentId,
                asAmount(ocrResult == null ? null : ocrResult.get("amount")), asDate(ocrResult, "ocrDate"));
        try {
            invoiceRecordMapper.insert(record);
            linkToReimb(record.getId(), reimbId, fileRecordId);
            log.info("发票投影落库: invoiceCode={}, invoiceNum={}, reimbId={}, amount={}",
                    record.getInvoiceCode(), record.getInvoiceNum(), reimbId, record.getAmount());
        } catch (DuplicateKeyException e) {
            // 并发投影同一张票：唯一键兜底，转为累加既有行（不视为业务失败）
            log.warn("发票投影并发撞键，转为累加既有记录: invoiceCode={}, invoiceNum={}", invoiceCode, invoiceNum);
            InvoiceRecord concurrent = findByInvoiceKey(invoiceCode, invoiceNum);
            if (concurrent != null) {
                concurrent.applySeenAgain(attachmentId, fileRecordId, reimbId);
                invoiceRecordMapper.updateById(concurrent);
                linkToReimb(concurrent.getId(), reimbId, fileRecordId);
            }
        }
    }

    /**
     * 建立/累加「发票 → 报销单」归属（R3 修复的核心）。
     *
     * <p>同一张票被多张单共用时，{@code invoice_record} 只能记一个 {@code reimb_id}，
     * 故归属关系落在 {@code invoice_reimb_link}：同 (票, 单) 幂等累加 {@code seen_count}，
     * 并发撞唯一键时转为累加既有行。</p>
     *
     * @param invoiceRecordId 发票投影ID
     * @param reimbId         报销单ID（为空时跳过——OCR 可能早于绑定完成，后续重新投影会补上）
     * @param fileRecordId    来源附件 file_record.id
     */
    private void linkToReimb(Long invoiceRecordId, Long reimbId, Long fileRecordId) {
        if (invoiceRecordId == null || reimbId == null) {
            return;
        }
        InvoiceReimbLink existing = invoiceReimbLinkMapper.selectOne(new LambdaQueryWrapper<InvoiceReimbLink>()
                .eq(InvoiceReimbLink::getInvoiceRecordId, invoiceRecordId)
                .eq(InvoiceReimbLink::getReimbId, reimbId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.applySeenAgain(fileRecordId);
            invoiceReimbLinkMapper.updateById(existing);
            return;
        }
        try {
            invoiceReimbLinkMapper.insert(InvoiceReimbLink.of(invoiceRecordId, reimbId, fileRecordId));
        } catch (DuplicateKeyException e) {
            // 并发建立同一归属：唯一键兜底，转为累加既有行
            InvoiceReimbLink concurrent = invoiceReimbLinkMapper.selectOne(new LambdaQueryWrapper<InvoiceReimbLink>()
                    .eq(InvoiceReimbLink::getInvoiceRecordId, invoiceRecordId)
                    .eq(InvoiceReimbLink::getReimbId, reimbId)
                    .last("LIMIT 1"));
            if (concurrent != null) {
                concurrent.applySeenAgain(fileRecordId);
                invoiceReimbLinkMapper.updateById(concurrent);
            }
        }
    }

    /**
     * 发票是否被【其他】报销单占用（R3 按票查重的权威判据）。
     *
     * <p>用关联表而非 {@code invoice_record.reimb_id}：后者只记录最近一次归属，
     * 一票多单时会丢失归属关系（R3 联调踩过的架构缺陷）。</p>
     *
     * @param invoiceRecordId 发票投影ID
     * @param currentReimbId  当前报销单ID（排除自身）
     * @return 占用该票的其他报销单ID列表（不含自身；无则空列表）
     */
    public List<Long> findOtherReimbIds(Long invoiceRecordId, Long currentReimbId) {
        if (invoiceRecordId == null) {
            return List.of();
        }
        return invoiceReimbLinkMapper.selectList(new LambdaQueryWrapper<InvoiceReimbLink>()
                        .eq(InvoiceReimbLink::getInvoiceRecordId, invoiceRecordId)
                        .ne(currentReimbId != null, InvoiceReimbLink::getReimbId, currentReimbId)
                        .orderByAsc(InvoiceReimbLink::getId))
                .stream()
                .map(InvoiceReimbLink::getReimbId)
                .distinct()
                .toList();
    }

    /**
     * 按票号硬命中查询（供 R3 按票查重使用）。
     * <p>不按租户显式过滤——多租户拦截器自动附加 {@code tenant_id} 条件。</p>
     *
     * @param invoiceCode 发票代码（null 视为空串）
     * @param invoiceNum  发票号码
     * @return 命中的投影行，未命中返回 null
     */
    public InvoiceRecord findByInvoiceKey(String invoiceCode, String invoiceNum) {
        if (invoiceNum == null || invoiceNum.isBlank()) {
            return null;
        }
        return invoiceRecordMapper.selectOne(new LambdaQueryWrapper<InvoiceRecord>()
                .eq(InvoiceRecord::getInvoiceCode, invoiceCode == null ? "" : invoiceCode.trim())
                .eq(InvoiceRecord::getInvoiceNum, invoiceNum.trim())
                .last("LIMIT 1"));
    }

    /**
     * 批量按票号查询（供 R3 一次查重的多张票命中判定）。
     *
     * @param invoiceNums 发票号码列表；空集合直接返回空列表（避免生成非法 IN ()）
     * @return 命中的投影行
     */
    public List<InvoiceRecord> findByInvoiceNums(List<String> invoiceNums) {
        if (invoiceNums == null || invoiceNums.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = invoiceNums.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return invoiceRecordMapper.selectList(new LambdaQueryWrapper<InvoiceRecord>()
                .in(InvoiceRecord::getInvoiceNum, cleaned));
    }

    /** 按报销单列出投影行（供详情/追溯展示）。空集合防御见 AGENTS.md §5.11 */
    public List<InvoiceRecord> listByReimbId(Long reimbId) {
        if (reimbId == null) {
            return List.of();
        }
        return invoiceRecordMapper.selectList(new LambdaQueryWrapper<InvoiceRecord>()
                .eq(InvoiceRecord::getReimbId, reimbId)
                .orderByAsc(InvoiceRecord::getId));
    }

    /**
     * 票据-明细交叉核验 + 离线规则验真（P3.8 R3-3 / R3-5）。
     *
     * <p><b>为什么要做交叉核验</b>：{@code amount_verify} 只校验「明细合计 == 申报总额」，
     * 那是同一份数据内部的自洽；{@code rule_check} 校验的是限额与标准。
     * 两者都<b>没有把「票面」与「明细」对上</b>——虚报（明细金额远大于票面）走不到任何检查。
     * 本方法补上这一环。</p>
     *
     * <p><b>离线验真（不引入付费查真接口）</b>：校验发票代码位数、开票日期合理性。
     * 这些是免费的形态类判据，能拦住明显异常的票号；权威真伪仍以税务平台为准（R8 可选增强）。</p>
     *
     * @param reimbId      报销单ID
     * @param items        申报明细（元素为含 name/amount 的 Map）
     * @param claimedTotal 申报明细合计（调用方已算好；为 null 时由本方法按 items 求和）
     * @return 核验结果（无发票且无明细时返回「一致」的空结果）
     */
    public InvoiceMatchVO matchInvoices(Long reimbId, List<Map<String, Object>> items, BigDecimal claimedTotal) {
        List<InvoiceRecord> invoices = listByReimbId(reimbId);
        List<Map<String, Object>> safeItems = items == null ? List.of() : items;

        // 明细合计：优先用调用方传入值，缺失时自行求和
        BigDecimal claimSum = claimedTotal;
        if (claimSum == null) {
            claimSum = BigDecimal.ZERO;
            for (Map<String, Object> it : safeItems) {
                BigDecimal a = asAmount(it.get("amount"));
                if (a != null) {
                    claimSum = claimSum.add(a);
                }
            }
        }
        // 票面合计：无金额的票不计入（OCR 未识别出金额时无从比对）
        BigDecimal invoiceSum = BigDecimal.ZERO;
        int withAmount = 0;
        for (InvoiceRecord r : invoices) {
            if (r.getAmount() != null) {
                invoiceSum = invoiceSum.add(r.getAmount());
                withAmount++;
            }
        }

        List<InvoiceMatchVO.Flag> flags = new ArrayList<>();

        // ---------- 交叉核验 ①：有明细但无任何发票 ----------
        if (invoices.isEmpty() && !safeItems.isEmpty()) {
            flags.add(new InvoiceMatchVO.Flag("NO_INVOICE",
                    "申报了 " + safeItems.size() + " 条明细但未识别到任何发票，票据与明细无法交叉核验"));
        }

        // ---------- 交叉核验 ②：票面合计 vs 申报合计 ----------
        BigDecimal gap = claimSum.subtract(invoiceSum);
        if (withAmount > 0 && gap.compareTo(TOLERANCE) > 0) {
            flags.add(new InvoiceMatchVO.Flag("AMOUNT_MISMATCH",
                    "申报合计 " + claimSum + " 大于票面合计 " + invoiceSum + "，差额 " + gap + "，明细金额可能虚报"));
        }

        // ---------- 交叉核验 ③：单笔明细超过票面合计 ----------
        // 明细是票面金额的拆分，单项不可能大于全部票面之和
        if (withAmount > 0) {
            for (Map<String, Object> it : safeItems) {
                BigDecimal a = asAmount(it.get("amount"));
                if (a != null && a.subtract(invoiceSum).compareTo(TOLERANCE) > 0) {
                    flags.add(new InvoiceMatchVO.Flag("ITEM_EXCEEDS_INVOICE",
                            "明细「" + asText(it, "name") + "」金额 " + a + " 超过票面合计 " + invoiceSum));
                }
            }
        }

        // ---------- 离线验真（R3-5） ----------
        for (InvoiceRecord r : invoices) {
            String label = "发票[" + r.getInvoiceCode() + "/" + r.getInvoiceNum() + "]";
            // 发票代码：缺失允许（2018 年起电子发票代码并入号码），有值则必须是合法位数且纯数字
            String code = r.getInvoiceCode();
            if (code != null && !code.isBlank()) {
                if (!code.chars().allMatch(Character::isDigit)) {
                    flags.add(new InvoiceMatchVO.Flag("INVOICE_CODE_FORMAT", label + " 代码含非数字字符"));
                } else if (!INVOICE_CODE_LENGTHS.contains(code.length())) {
                    flags.add(new InvoiceMatchVO.Flag("INVOICE_CODE_FORMAT",
                            label + " 代码位数异常（" + code.length() + " 位，常见为 10/12 位）"));
                }
            }
            // 开具日期：不得晚于当前日期（未来票），且不应早得离谱（视为 OCR 误识别）
            LocalDate invDate = r.getInvDate();
            if (invDate != null) {
                LocalDate today = LocalDate.now();
                if (invDate.isAfter(today)) {
                    flags.add(new InvoiceMatchVO.Flag("INVOICE_DATE_FUTURE",
                            label + " 开票日期 " + invDate + " 晚于当前日期，疑似识别错误或伪造"));
                } else if (invDate.isBefore(today.minusYears(INVOICE_MAX_AGE_YEARS))) {
                    flags.add(new InvoiceMatchVO.Flag("INVOICE_DATE_TOO_OLD",
                            label + " 开票日期 " + invDate + " 距今超过 " + INVOICE_MAX_AGE_YEARS + " 年"));
                }
            }
        }

        boolean consistent = flags.stream().noneMatch(f ->
                "AMOUNT_MISMATCH".equals(f.code()) || "ITEM_EXCEEDS_INVOICE".equals(f.code())
                        || "NO_INVOICE".equals(f.code()));
        return new InvoiceMatchVO(invoices.size(), safeItems.size(), invoiceSum, claimSum, gap, consistent, flags);
    }

    /** Map 取值并规整为空 null（空串/纯空白归一为 null，保证唯一键取值确定） */
    private static String asText(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Map 取金额：兼容 BigDecimal / Number / 字符串（千分位逗号清洗），解析失败返回 null */
    private static BigDecimal asAmount(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("金额解析失败: {}", v);
            return null;
        }
    }

    /** Map 取日期（yyyy-MM-dd），解析失败返回 null */
    private static LocalDate asDate(Map<String, Object> map, String key) {
        String s = asText(map, key);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            log.warn("投影日期解析失败: {}", s);
            return null;
        }
    }
}
