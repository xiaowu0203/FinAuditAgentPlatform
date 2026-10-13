package com.finaudit.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.file.pojo.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件元数据 Mapper（仅被 FileService 持有，见 docs/architecture/conventions.md §1）。
 */
@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}
