package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finaudit.agentcore.mapper.ExpenseAttachmentMapper;
import com.finaudit.agentcore.pojo.entity.ExpenseAttachment;
import com.finaudit.agentcore.pojo.vo.AttachmentVO;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.FileServiceFeign;
import com.finaudit.starter.web.feign.dto.FileRecordVO;
import com.finaudit.starter.web.result.R;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报销业务附件服务（expense_attachment 实体数据访问仅允许在本类，见 CLAUDE.md §5.8）。
 * <p>业务附件仅存 file_record 引用 + 业务字段；文件元数据/预签名 URL 一律经 FileServiceFeign 远程联取，
 * 禁止直连 OSS。</p>
 */
@Service
public class AttachmentService {

    private final ExpenseAttachmentMapper attachmentMapper;
    private final FileServiceFeign fileServiceFeign;

    public AttachmentService(ExpenseAttachmentMapper attachmentMapper, FileServiceFeign fileServiceFeign) {
        this.attachmentMapper = attachmentMapper;
        this.fileServiceFeign = fileServiceFeign;
    }

    /**
     * 绑定附件到报销单：重复绑定校验 → 缺失行补建（fileType=OTHER/ocrStatus=PENDING）→ 单条 UPDATE 回填 reimb_id。
     */
    @Transactional
    public void attachToReimb(List<Long> fileRecordIds, Long reimbId, Long tenantId) {
        List<Long> distinct = fileRecordIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        // 已存在绑定记录（重复绑定校验：同一 file_record 不可挂两个报销单）
        List<ExpenseAttachment> existing = attachmentMapper.selectList(new LambdaQueryWrapper<ExpenseAttachment>()
                .eq(ExpenseAttachment::getTenantId, tenantId)
                .in(ExpenseAttachment::getFileRecordId, distinct));
        for (ExpenseAttachment e : existing) {
            if (e.getReimbId() != null && !e.getReimbId().equals(reimbId)) {
                throw new BizException("文件已关联其他报销单: " + e.getFileRecordId());
            }
        }
        // 缺失行补建（批量新增单条多行 INSERT，CLAUDE.md §5.9）
        Set<Long> existingIds = existing.stream().map(ExpenseAttachment::getFileRecordId).collect(Collectors.toSet());
        List<ExpenseAttachment> toCreate = distinct.stream()
                .filter(id -> !existingIds.contains(id))
                .map(id -> ExpenseAttachment.from(id, reimbId, tenantId))
                .toList();
        if (!toCreate.isEmpty()) {
            attachmentMapper.insertBatch(toCreate);
        }
        // 回填 reimb_id（单条 UPDATE，幂等：仅未绑定行）
        attachmentMapper.update(ExpenseAttachment.forBindReimb(reimbId),
                new LambdaUpdateWrapper<ExpenseAttachment>()
                        .in(ExpenseAttachment::getFileRecordId, distinct)
                        .isNull(ExpenseAttachment::getReimbId));
    }

    /**
     * 按报销单查附件（详情用，按 id 升序）。
     */
    public List<ExpenseAttachment> listByReimbId(Long reimbId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<ExpenseAttachment>()
                .eq(ExpenseAttachment::getReimbId, reimbId)
                .orderByAsc(ExpenseAttachment::getId));
    }

    /**
     * 回写 OCR 结果（P2b ocr_extract 工具按 file_record_id 定位附件）。
     *
     * @param fileRecordId file_record id（附件引用）
     * @param ocrStatus    OCR 状态（PENDING/SUCCESS/FAILED）
     * @param fileType     票据分类映射后的附件类型（可空：分类未产生时保留原值）
     * @param ocrResult    OCR 抽取结果（JSON）
     * @throws com.finaudit.starter.web.exception.BizException 附件不存在时抛出
     */
    @Transactional
    public void updateOcrResult(Long fileRecordId, String ocrStatus, String fileType, Map<String, Object> ocrResult) {
        // 根据附件ID查询出附件信息
        ExpenseAttachment attachment = attachmentMapper.selectOne(new LambdaQueryWrapper<ExpenseAttachment>()
                .eq(ExpenseAttachment::getFileRecordId, fileRecordId)
                .last("LIMIT 1"));
        if (attachment == null) {
            throw new BizException("附件不存在: fileRecordId=" + fileRecordId);
        }
        // 回写 OCR 结果
        attachment.applyOcrResult(ocrStatus, fileType, ocrResult);
        // 更新附件信息
        attachmentMapper.updateById(attachment);
    }

    /**
     * 报销单附件 → VO 列表（1 次 Feign 批量取元数据 + 逐个取预览预签名 URL）。
     */
    public List<AttachmentVO> listVOsByReimbId(Long reimbId) {
        List<ExpenseAttachment> attachments = listByReimbId(reimbId);
        if (attachments.isEmpty()) {
            return List.of();
        }
        Long tenantId = attachments.get(0).getTenantId();
        List<Long> fileRecordIds = attachments.stream().map(ExpenseAttachment::getFileRecordId).toList();
        Map<Long, FileRecordVO> fileMap = fetchFiles(tenantId, fileRecordIds);
        List<AttachmentVO> vos = new ArrayList<>(attachments.size());
        for (ExpenseAttachment a : attachments) {
            FileRecordVO file = fileMap.get(a.getFileRecordId());
            vos.add(AttachmentVO.from(a, file, presignPreview(tenantId, a.getFileRecordId())));
        }
        return vos;
    }

    /** 批量取文件元数据（Feign，1 次）；失败返回空 Map，不阻塞详情渲染 */
    private Map<Long, FileRecordVO> fetchFiles(Long tenantId, List<Long> fileRecordIds) {
        R<List<FileRecordVO>> resp = fileServiceFeign.getFiles(tenantId, fileRecordIds);
        if (resp.getCode() != 0 || resp.getData() == null) {
            return Collections.emptyMap();
        }
        Map<Long, FileRecordVO> map = new HashMap<>();
        for (FileRecordVO f : resp.getData()) {
            map.put(f.id(), f);
        }
        return map;
    }

    /** 预览预签名 URL（Feign）；失败返回 null */
    private String presignPreview(Long tenantId, Long fileRecordId) {
        R<String> resp = fileServiceFeign.presignPreview(tenantId, fileRecordId);
        return resp.getCode() == 0 ? resp.getData() : null;
    }
}
