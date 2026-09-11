package com.finaudit.starter.mybatisplus.handler;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 审计时间戳自动填充（P3.8 R2 新增；修复全仓 {@code updated_at} 失真的框架级缺陷）。
 *
 * <p><b>要解决的问题</b>：DDL 里 {@code updated_at} 均为
 * {@code DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP}，
 * 看似无需应用层赋值。但 MyBatis-Plus 的 {@code updateById(entity)} 默认更新策略是
 * <b>NOT_NULL</b>，会把实体里<b>从库里读出来的旧 {@code updated_at}</b> 一并写进 SET 子句。
 * 一旦某个列被**显式赋值**，MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 就不再触发——
 * 于是"更新一行"反而把旧时间戳原样写回，{@code updated_at} 永远等于 {@code created_at}。</p>
 *
 * <p><b>实测证据</b>（R2 联调，invoice_record 同一行连续 3 次改写）：
 * <pre>
 *   BEFORE: seen_count=4 attachment_id=45 reimb_id=47 updated_at=01:37:22
 *   AFTER : seen_count=5 attachment_id=46 reimb_id=48 updated_at=01:37:22  ← 三个字段都变了，时间戳不动
 * </pre>
 * 对照实验：去掉 SET 里的 {@code updated_at} → 时间戳立刻刷新；
 * 显式带上旧值 → 时间戳冻结。可确认成因即"显式赋值抑制 ON UPDATE"。
 * 影响面：走 {@code LambdaUpdateWrapper.set(...)} 的更新（SET 里不含 updated_at）不受影响，
 * 故同一张表会呈现"部分行时间戳正确、部分行从未刷新"的混合状态。</p>
 *
 * <p><b>填充规则</b>：只填充<b>显式标注</b>了 {@code fill} 的字段，未标注的实体完全不受影响
 * （{@code strictInsertFill}/{@code strictUpdateFill} 仅匹配带对应 fill 策略的字段）。
 * 审计时间戳异常会直接污染财务系统的追溯性，故统一收敛到框架层处理，
 * 业务代码不再手动 {@code set updatedAt}。</p>
 */
public class AuditTimestampMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditTimestampMetaObjectHandler.class);

    /** 创建时间字段名 */
    private static final String CREATED_AT = "createdAt";
    /** 更新时间字段名 */
    private static final String UPDATED_AT = "updatedAt";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // strictInsertFill：仅当字段存在、类型匹配且带 FieldFill.INSERT/INSERT_UPDATE 时填充
        strictInsertFill(metaObject, CREATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, UPDATED_AT, LocalDateTime.class, now);
        if (log.isDebugEnabled()) {
            log.debug("审计时间戳填充（INSERT）: {}", metaObject.getOriginalObject().getClass().getSimpleName());
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // ⚠️ 必须用 setFieldValByName 而非 strictUpdateFill：
        //    strictUpdateFill 的语义是「字段为 null 才填」，而 updateById(entity) 传进来的实体
        //    恰恰是**从库里读出来的**——updatedAt 早已有旧值，于是填充被静默跳过，
        //    旧时间戳照样进 SET 子句，继续抑制 MySQL 的 ON UPDATE CURRENT_TIMESTAMP，
        //    缺陷原样保留。本方法的目的就是**覆盖**那个旧值，故不能用 strict 语义。
        //    （实测：用 strictUpdateFill 时单测断言失败「实际=2026-01-01T00:00」未变。）
        //
        // ⚠️ 但 setFieldValByName 不做策略判断，会连**未标注 fill** 的实体一起改
        //    （违反「未标注实体零影响」的承诺，单测已捕获）。故先自行校验 fill 策略。
        if (!filledByUpdate(metaObject.getOriginalObject().getClass(), UPDATED_AT)) {
            return;
        }
        setFieldValByName(UPDATED_AT, LocalDateTime.now(), metaObject);
        if (log.isDebugEnabled()) {
            log.debug("审计时间戳填充（UPDATE）: {}", metaObject.getOriginalObject().getClass().getSimpleName());
        }
    }

    /**
     * 判断实体的 {@code updatedAt} 字段是否声明了 UPDATE 侧的自动填充。
     * <p>取值来源是 MyBatis-Plus 的 {@code TableInfo} 缓存（即 {@code @TableField(fill=...)} 的解析结果），
     * 只有 {@code UPDATE} / {@code INSERT_UPDATE} 才应填充；
     * 未标注、或标注为 {@code INSERT}（如 {@code createdAt}）的一律跳过。</p>
     */
    private static boolean filledByUpdate(Class<?> entityClass, String fieldName) {
        TableInfo info = TableInfoHelper.getTableInfo(entityClass);
        if (info == null) {
            return false;
        }
        for (TableFieldInfo fi : info.getFieldList()) {
            if (fieldName.equals(fi.getProperty())) {
                FieldFill fill = fi.getFieldFill();
                return fill == FieldFill.UPDATE || fill == FieldFill.INSERT_UPDATE;
            }
        }
        return false;
    }
}
