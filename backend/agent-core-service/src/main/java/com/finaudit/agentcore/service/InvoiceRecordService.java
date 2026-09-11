package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.InvoiceRecordMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

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

    private final InvoiceRecordMapper invoiceRecordMapper;

    public InvoiceRecordService(InvoiceRecordMapper invoiceRecordMapper) {
        this.invoiceRecordMapper = invoiceRecordMapper;
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
            log.info("发票投影命中既有记录，累加识别次数: invoiceNum={}, seenCount={}, reimbId={}",
                    invoiceNum, existing.getSeenCount(), existing.getReimbId());
            return;
        }

        InvoiceRecord record = InvoiceRecord.from(invoiceCode, invoiceNum,
                asText(ocrResult, "taxNo"), reimbId, fileRecordId, attachmentId,
                asAmount(ocrResult, "amount"), asDate(ocrResult, "ocrDate"));
        try {
            invoiceRecordMapper.insert(record);
            log.info("发票投影落库: invoiceCode={}, invoiceNum={}, reimbId={}, amount={}",
                    record.getInvoiceCode(), record.getInvoiceNum(), reimbId, record.getAmount());
        } catch (DuplicateKeyException e) {
            // 并发投影同一张票：唯一键兜底，转为累加既有行（不视为业务失败）
            log.warn("发票投影并发撞键，转为累加既有记录: invoiceCode={}, invoiceNum={}", invoiceCode, invoiceNum);
            InvoiceRecord concurrent = findByInvoiceKey(invoiceCode, invoiceNum);
            if (concurrent != null) {
                concurrent.applySeenAgain(attachmentId, fileRecordId, reimbId);
                invoiceRecordMapper.updateById(concurrent);
            }
        }
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
    private static BigDecimal asAmount(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
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
            log.warn("投影金额解析失败: {}", v);
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
