-- =====================================================================
-- P3.5a 增量迁移（仅一次；本机已有 P3b 数据时执行，勿与 finaudit-schema.sql 重灌混用）
-- 目标库: finaudit（MySQL 5.7 / utf8mb4 / InnoDB）
-- 执行: mysql -uroot -p < docs/database/migration-P3.5a.sql
-- 内容: 轻量资源级 RBAC——sys_permission 权限目录（平台级全局表，无 tenant_id，
--       需在 common-mybatisplus-starter 多租户 ignore 名单注册）+ sys_role_permission 映射
--       + 权限码种子（系统管理操作级 16 码 + 业务资源级 7 码）+ 三内置角色默认映射
-- 幂等: INSERT 采用固定主键 + INSERT IGNORE，可重复执行（重复行静默跳过）
-- =====================================================================

USE finaudit;

-- 1. 权限目录表（平台级：权限码由迁移脚本种子定义，运行期不增删——代码即目录）
--    ⚠️ 无 tenant_id：所有租户共用同一套权限标识；查询须走多租户拦截器 ignore 名单
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    perm_code   VARCHAR(64)  NOT NULL COMMENT '权限标识符: 资源:操作（系统管理操作级）/ 资源级（业务）',
    perm_name   VARCHAR(64)  NOT NULL COMMENT '权限名称（分配界面展示）',
    perm_type   VARCHAR(8)   NOT NULL DEFAULT 'API' COMMENT '类型: MENU 菜单+接口 / API 仅接口',
    group_name  VARCHAR(32)  NOT NULL DEFAULT '业务' COMMENT '分组（分配界面分区展示）: 系统管理/财务业务/预留',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限目录表（平台级全局，多租户拦截器须忽略）';

-- 2. 角色-权限映射表（租户内：替换式分配，角色是权限的分配单位）
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT   NOT NULL DEFAULT 1 COMMENT '租户ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID（sys_role.id）',
    perm_id     BIGINT   NOT NULL COMMENT '权限ID（sys_permission.id）',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (tenant_id, role_id, perm_id),
    KEY idx_role (role_id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色权限映射表';

-- 3. 权限码种子（固定主键，便于 sys_role_permission 种子按 id 引用；INSERT IGNORE 幂等）
INSERT IGNORE INTO sys_permission (id, perm_code, perm_name, perm_type, group_name) VALUES
    -- 系统管理 · 用户（操作级）
    (1,  'user:list',        '用户查询',   'MENU', '系统管理'),
    (2,  'user:create',      '用户新增',   'API',  '系统管理'),
    (3,  'user:update',      '用户编辑',   'API',  '系统管理'),
    (4,  'user:delete',      '用户删除',   'API',  '系统管理'),
    (5,  'user:assign-role', '用户角色绑定', 'API', '系统管理'),
    -- 系统管理 · 角色（操作级）
    (6,  'role:list',        '角色查询',   'MENU', '系统管理'),
    (7,  'role:create',      '角色新增',   'API',  '系统管理'),
    (8,  'role:update',      '角色编辑',   'API',  '系统管理'),
    (9,  'role:delete',      '角色删除',   'API',  '系统管理'),
    (10, 'role:assign-perm', '角色权限分配', 'API', '系统管理'),
    -- 系统管理 · 部门（操作级；树查询不挂码——报销选择器公用，读开写收）
    (11, 'dept:manage',      '部门管理页', 'MENU', '系统管理'),
    (12, 'dept:create',      '部门新增',   'API',  '系统管理'),
    (13, 'dept:update',      '部门编辑',   'API',  '系统管理'),
    (14, 'dept:delete',      '部门删除',   'API',  '系统管理'),
    -- 系统管理 · 租户
    (15, 'tenant:manage',    '租户管理',   'API',  '系统管理'),
    -- 财务业务 · 资源级
    (20, 'rule:manage',      '财务规则配置', 'MENU', '财务业务'),
    (21, 'reimb:viewAll',    '报销单全量可见', 'API', '财务业务'),
    (22, 'task:viewAll',     '任务全量可见',   'API', '财务业务'),
    (23, 'audit:viewAll',    '审批工单全量可见', 'API', '财务业务'),
    (24, 'audit:approve',    '审批动作',   'API',  '财务业务'),
    (25, 'budget:viewAll',   '预算全部门查询', 'API', '财务业务'),
    -- P4 预留
    (30, 'dashboard:admin',  '管理员风控大盘', 'MENU', '预留');

-- 4. 内置角色默认映射（沿用 finaudit-schema.sql 种子角色 id：1=admin / 2=auditor）
--    普通用户（无内置角色或自建 user 角）不授任何码——登录即基础权限
--    admin: 系统管理全量 + 业务全量
INSERT IGNORE INTO sys_role_permission (tenant_id, role_id, perm_id) VALUES
    (1, 1, 1), (1, 1, 2), (1, 1, 3), (1, 1, 4), (1, 1, 5),
    (1, 1, 6), (1, 1, 7), (1, 1, 8), (1, 1, 9), (1, 1, 10),
    (1, 1, 11), (1, 1, 12), (1, 1, 13), (1, 1, 14), (1, 1, 15),
    (1, 1, 20), (1, 1, 21), (1, 1, 22), (1, 1, 23), (1, 1, 24),
    (1, 1, 25), (1, 1, 30);
--    auditor: 财务业务资源级（不含 budget:viewAll / dashboard:admin / 系统管理）
INSERT IGNORE INTO sys_role_permission (tenant_id, role_id, perm_id) VALUES
    (1, 2, 20), (1, 2, 21), (1, 2, 22), (1, 2, 23), (1, 2, 24);

-- 5. sys_user_role 历史逻辑删行清理：P3.5a 起 sys_user_role 改物理删除语义
--    （实体去掉 @TableLogic；uk_user_role 不含 deleted，逻辑删+重绑同角色会撞唯一键）。
--    不清理的话旧 deleted=1 行会在切换后被查询捞回（重复角色）。
DELETE FROM sys_user_role WHERE deleted = 1;
