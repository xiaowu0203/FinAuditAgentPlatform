package com.finaudit.starter.mybatisplus.handler;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计时间戳填充器单测（P3.8 R2）。
 *
 * <p>核心回归点：{@code updateFill} <b>必须覆盖</b>实体里已存在的旧 {@code updatedAt}。
 * {@code updateById(entity)} 传进来的实体是从库里读出来的、{@code updatedAt} 早已有值；
 * 若用 {@code strictUpdateFill}（语义为"字段为 null 才填"）会被静默跳过，
 * 旧时间戳照样进 SET 子句、继续抑制 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP}——
 * 缺陷原样保留。本测试即锁定该行为。</p>
 */
class AuditTimestampMetaObjectHandlerTest {

    private final AuditTimestampMetaObjectHandler handler = new AuditTimestampMetaObjectHandler();

    /** 本地夹具实体：与真实实体（如 InvoiceRecord）标注方式一致 */
    @TableName("fixture_entity")
    static class FixtureEntity {
        @TableId(type = IdType.AUTO)
        private Long id;

        @TableField(fill = FieldFill.INSERT)
        private LocalDateTime createdAt;

        @TableField(fill = FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;

        @TableField
        private Integer seenCount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
        public Integer getSeenCount() { return seenCount; }
        public void setSeenCount(Integer v) { this.seenCount = v; }
    }

    /** 未标注 fill 的实体：填充器必须完全不碰它 */
    @TableName("plain_entity")
    static class PlainEntity {
        @TableId(type = IdType.AUTO)
        private Long id;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    }

    @BeforeEach
    void initTableInfo() {
        // TableInfo 承载字段的 fill 策略，strictFill 依赖它；必须用 MapperBuilderAssistant 初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FixtureEntity.class);
        TableInfoHelper.initTableInfo(assistant, PlainEntity.class);
    }

    @Test
    void insertFillSetsCreatedAtAndUpdatedAt() {
        FixtureEntity e = new FixtureEntity();
        assertNull(e.getCreatedAt());
        assertNull(e.getUpdatedAt());

        handler.insertFill(SystemMetaObject.forObject(e));

        assertNotNull(e.getCreatedAt(), "INSERT 应填充 createdAt");
        assertNotNull(e.getUpdatedAt(), "INSERT 应填充 updatedAt");
    }

    @Test
    void updateFillOverwritesStaleUpdatedAt() {
        // 模拟「从库里读出来的旧值」——这正是 updateById 会把旧时间戳写回 SET 的根源
        LocalDateTime stale = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        FixtureEntity e = new FixtureEntity();
        e.setUpdatedAt(stale);

        handler.updateFill(SystemMetaObject.forObject(e));

        assertNotNull(e.getUpdatedAt());
        assertEquals(true, e.getUpdatedAt().isAfter(stale),
                "UPDATE 必须用当前时间覆盖旧 updatedAt（strictUpdateFill 会漏填），实际=" + e.getUpdatedAt());
    }

    @Test
    void updateFillDoesNotTouchCreatedAt() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        FixtureEntity e = new FixtureEntity();
        e.setCreatedAt(created);

        handler.updateFill(SystemMetaObject.forObject(e));

        assertEquals(created, e.getCreatedAt(), "UPDATE 不得改写 createdAt");
    }

    @Test
    void updateFillLeavesUnannotatedEntityUntouched() {
        // 未标注 FieldFill 的实体不应被填充——保证改动对全仓其他实体零影响
        PlainEntity p = new PlainEntity();

        handler.updateFill(SystemMetaObject.forObject(p));

        assertNull(p.getUpdatedAt(), "未标注 fill 的字段不得被填充");
    }
}
