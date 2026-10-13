import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mapper.AgentTaskStepMapper;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 列写入方式的最小复现程序（P3.8 R5-9）。
 *
 * <p><b>它回答的问题</b>：MyBatis-Plus 的 {@code LambdaUpdateWrapper.set(列, 值)} 与
 * 「实体补丁更新」在写 <b>JSON 列</b>时行为是否一致？答案是<b>不一致</b>：
 * wrapper 参数不带 typeHandler，驱动按 binary 字符集发送字符串，MySQL 5.7 报</p>
 * <pre>
 * Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'.
 * </pre>
 * <p>而 {@code wrapper.getSqlSet()} 完全正常——报错发生在驱动绑定参数时，
 * 所以 <b>mock 单测永远抓不到</b>。R5 的自纠错链路正是被这个异常静默打断的
 * （异常被自校验兜底 catch 吞掉，见计划文档 §11 R5-9）。</p>
 *
 * <p><b>为什么值得保留这个脚本</b>：它是本仓唯一能在<b>不启动微服务</b>的前提下、
 * 用真实 Mapper + 真实库验证「某段 DB 操作到底能不能跑」的手段。
 * 定位共用了一个数量级的时间——不必加日志、重启、复跑业务链路。</p>
 *
 * <h3>用法</h3>
 * <pre>
 * # 1) 生成依赖 classpath（离线可用；输出到临时目录，别在仓库里留垃圾文件）
 * cd backend
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=%TEMP%\finaudit-cp.txt -pl agent-core-service
 *
 * # 2) 编译（把 agent-core 的 target/classes 一并放进 classpath）
 * set /p DEPS=<%TEMP%\finaudit-cp.txt
 * set CP=%DEPS%;%CD%\agent-core-service\target\classes
 * javac -encoding UTF-8 -cp "%CP%" -d %TEMP%\repro-out ..\docs\test\repro\JsonColumnWriteRepro.java
 *
 * # 3) 运行（脚本会自建 scratch 行、结束后清理，不动业务数据）
 * java -cp "%CP%;%TEMP%\repro-out" JsonColumnWriteRepro
 * </pre>
 *
 * <p><b>预期输出</b>（[3]/[5]/[8] 抛异常 = 缺陷存在；[6]/[7] 成功 = 修复方案有效）：</p>
 * <pre>
 * [1] resetForSelfCorrection 原样: OK, affected=1
 * [2] 去掉 output=null: OK, affected=1
 * [3] updateInputParams(Map): 抛异常 -> MysqlDataTruncation ...
 * [4] incrementCorrectionCount: OK, correction_count=1
 * [5] applySelfCheckResult(Map->JSON): 抛异常 -> MysqlDataTruncation ...
 * [6] 实体 patch 写 self_check_result: OK, affected=1
 * [7] 实体 patch 写 step.input_params: OK, affected=1
 * [8] prepareRerun 原样: 抛异常 -> MysqlDataTruncation ...
 * </pre>
 */
public class JsonColumnWriteRepro {

    /** 本机开发库；如与本地不同请自行修改（不要提交真实密码：开源前替换为环境变量读取）。 */
    static final String URL = "jdbc:mysql://127.0.0.1:3306/finaudit?useSSL=false&allowPublicKeyRetrieval=true"
            + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    static final String USER = System.getenv().getOrDefault("FINAUDIT_DB_USER", "root");
    static final String PASSWORD = System.getenv().getOrDefault("FINAUDIT_DB_PASSWORD", "root");

    public static void main(String[] args) throws Exception {
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AgentTaskStepMapper.class);
        cfg.addMapper(AgentTaskMapper.class);
        cfg.setEnvironment(new Environment("repro", new JdbcTransactionFactory(), ds));
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);

        long taskId;
        long stepId;
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // 注意：input_params 在两张表上都是 NOT NULL 且无默认值，必须显式给值
            st.executeUpdate("INSERT INTO agent_task (tenant_id, task_no, task_type, title, input_params, status, total_steps, finished_steps, deleted)"
                    + " VALUES (1, 'REPRO-JSON-COLUMN', 'REIMBURSEMENT', 'JSON 列写入复现', '{}', 'RUNNING', 1, 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = st.getGeneratedKeys()) {
                rs.next();
                taskId = rs.getLong(1);
            }
            st.executeUpdate("INSERT INTO agent_task_step (tenant_id, task_id, step_no, step_name, step_type, tool_name, agent_role, input_params, `output`, status, retry_count, deleted)"
                    + " VALUES (1, " + taskId + ", 1, 'repro', 'TOOL', 'duplicate_check', 'RISK_AUDITOR', '{}', '{\"k\":\"v\"}', 'SUCCESS', 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = st.getGeneratedKeys()) {
                rs.next();
                stepId = rs.getLong(1);
            }
        }
        System.out.println("[setup] scratch taskId=" + taskId + " stepId=" + stepId);

        // ---------- 1) resetForSelfCorrection 原样 SQL（含 set(output, null)） ----------
        try (SqlSession s = factory.openSession(true)) {
            int n = s.getMapper(AgentTaskStepMapper.class).update(null, new LambdaUpdateWrapper<AgentTaskStep>()
                    .eq(AgentTaskStep::getId, stepId)
                    .in(AgentTaskStep::getStatus, "SUCCESS", "FAILED")
                    .set(AgentTaskStep::getStatus, "PENDING")
                    .set(AgentTaskStep::getOutput, null)
                    .set(AgentTaskStep::getErrorMsg, null)
                    .set(AgentTaskStep::getRetryCount, 0));
            System.out.println("[1] resetForSelfCorrection 原样: OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[1] resetForSelfCorrection 原样: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 2) 去掉 .set(output, null) 做对照 ----------
        try (SqlSession s = factory.openSession(true)) {
            int n = s.getMapper(AgentTaskStepMapper.class).update(null, new LambdaUpdateWrapper<AgentTaskStep>()
                    .eq(AgentTaskStep::getId, stepId)
                    .in(AgentTaskStep::getStatus, "SUCCESS", "FAILED", "PENDING")
                    .set(AgentTaskStep::getStatus, "PENDING")
                    .set(AgentTaskStep::getErrorMsg, null)
                    .set(AgentTaskStep::getRetryCount, 0));
            System.out.println("[2] 去掉 output=null: OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[2] 去掉 output=null: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 3) updateInputParams（Map 值无 typeHandler）——缺陷 ----------
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("reimbId", 73L);
            params.put("selfCheckHint", "hint");
            int n = s.getMapper(AgentTaskStepMapper.class).update(null, new LambdaUpdateWrapper<AgentTaskStep>()
                    .eq(AgentTaskStep::getId, stepId)
                    .set(AgentTaskStep::getInputParams, params));
            System.out.println("[3] updateInputParams(Map): OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[3] updateInputParams(Map): 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 4) incrementCorrectionCount（setSql 自增，无 JSON 参数） ----------
        try (SqlSession s = factory.openSession(true)) {
            AgentTaskMapper m = s.getMapper(AgentTaskMapper.class);
            m.update(null, new LambdaUpdateWrapper<AgentTask>()
                    .eq(AgentTask::getId, taskId)
                    .setSql("correction_count = IFNULL(correction_count, 0) + 1"));
            AgentTask fresh = m.selectById(taskId);
            System.out.println("[4] incrementCorrectionCount: OK, correction_count=" + fresh.getCorrectionCount());
        } catch (Throwable t) {
            System.out.println("[4] incrementCorrectionCount: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 5) applySelfCheckResult（缺陷：R5 自纠错被静默打断的位置） ----------
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("coherent", false);
            r.put("checkedCount", 5);
            int n = s.getMapper(AgentTaskMapper.class).update(null, new LambdaUpdateWrapper<AgentTask>()
                    .eq(AgentTask::getId, taskId)
                    .set(AgentTask::getSelfCheckResult, r));
            System.out.println("[5] applySelfCheckResult(Map->JSON): OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[5] applySelfCheckResult(Map->JSON): 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 6) 修复方案：实体补丁更新写 agent_task.self_check_result ----------
        try (SqlSession s = factory.openSession(true)) {
            AgentTask patch = AgentTask.selfCheckResultPatch(taskId, Map.of("coherent", false, "checkedCount", 5));
            int n = s.getMapper(AgentTaskMapper.class).update(patch,
                    new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getId, taskId));
            System.out.println("[6] 实体 patch 写 self_check_result: OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[6] 实体 patch 写 self_check_result: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 7) 修复方案：实体补丁更新写 agent_task_step.input_params ----------
        try (SqlSession s = factory.openSession(true)) {
            AgentTaskStep patch = AgentTaskStep.inputParamsPatch(stepId, Map.of("selfCheckHint", "hint"));
            int n = s.getMapper(AgentTaskStepMapper.class).update(patch,
                    new LambdaUpdateWrapper<AgentTaskStep>().eq(AgentTaskStep::getId, stepId));
            System.out.println("[7] 实体 patch 写 step.input_params: OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[7] 实体 patch 写 step.input_params: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ---------- 8) prepareRerun 原样 SQL（amend 重跑链路，R4 遗留缺陷） ----------
        try (SqlSession s = factory.openSession(true)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("reimbId", 73L);
            int n = s.getMapper(AgentTaskMapper.class).update(null, new LambdaUpdateWrapper<AgentTask>()
                    .eq(AgentTask::getId, taskId)
                    .in(AgentTask::getStatus, "APPROVAL_PENDING", "REJECTED", "RUNNING")
                    .set(AgentTask::getInputParams, params)
                    .set(AgentTask::getResult, null)
                    .set(AgentTask::getErrorMsg, null)
                    .set(AgentTask::getStatus, "RUNNING")
                    .set(AgentTask::getFinishedSteps, 0));
            System.out.println("[8] prepareRerun 原样: OK, affected=" + n);
        } catch (Throwable t) {
            System.out.println("[8] prepareRerun 原样: 抛异常 -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM agent_task_step WHERE task_id=" + taskId);
            st.executeUpdate("DELETE FROM agent_task WHERE id=" + taskId);
        }
        System.out.println("[cleanup] 已清理 scratch 数据");
        System.out.flush();
        System.exit(0);
    }
}
