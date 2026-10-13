import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.finaudit.agentcore.mapper.ModelCallLogMapper;
import com.finaudit.agentcore.pojo.entity.ModelCallLog;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型调用台账「真实库」写入复现（P3.8 R9-1）。
 *
 * <p><b>它回答什么问题</b>：{@code ModelCallLogServiceTest} 用 mock Mapper 只能验证"传了什么"，
 * 验证不了"这行到底能不能落进 MySQL"——列名映射、NOT NULL 约束、VARCHAR 长度、JSON/布尔转换
 * 这些只有在真实库上才会暴露。本仓在 R5-9 上就吃过这个亏（mock 全绿、真实库必失败）。
 * 故在重启服务做端到端验证之前，先用真实 Mapper + 真实库把写入路径跑一遍。</p>
 *
 * <p>覆盖四种记录形态（成功/失败/走备用模型/超长错误信息），写入后回读校验，最后清理。</p>
 *
 * <h3>用法</h3>
 * <pre>
 * cd backend
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=%TEMP%\finaudit-cp.txt -pl agent-core-service
 * REM ⚠️ 必须先 clean compile：增量编译会因「class 比 source 新」而跳过重编，
 * REM    于是你测的是旧字节码——实测踩过，现象是"改好的截断逻辑不生效"，极易误判为产品缺陷
 * mvn -o -q clean compile -pl agent-core-service
 * set /p DEPS=&lt;%TEMP%\finaudit-cp.txt
 * set CP=%DEPS%;%CD%\agent-core-service\target\classes
 * javac -encoding UTF-8 -cp "%CP%" -d %TEMP%\repro-out ..\docs\test\repro\ModelCallLogInsertRepro.java
 * java -cp "%CP%;%TEMP%\repro-out" ModelCallLogInsertRepro
 * </pre>
 */
public class ModelCallLogInsertRepro {

    static final String URL = "jdbc:mysql://127.0.0.1:3306/finaudit?useSSL=false&allowPublicKeyRetrieval=true"
            + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    static final String USER = System.getenv().getOrDefault("FINAUDIT_DB_USER", "root");
    static final String PASSWORD = System.getenv().getOrDefault("FINAUDIT_DB_PASSWORD", "root");

    public static void main(String[] args) throws Exception {
        PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(ModelCallLogMapper.class);
        cfg.setEnvironment(new Environment("repro", new JdbcTransactionFactory(), ds));
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);

        List<Long> inserted = new ArrayList<>();

        // 1) 成功调用（含 task/step 关联）
        inserted.add(insert(factory, new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                1L, 900001L, 888001L, 120, 30, 4567L, true, false, null), "成功调用"));

        // 2) 失败调用（error_msg 需落库，且 VARCHAR(500) 内不报错）
        inserted.add(insert(factory, new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                1L, 900001L, 888002L, 0, 0, 30000L, false, false, "连接超时：read timeout after 30000ms"), "失败调用"));

        // 3) 走备用模型
        inserted.add(insert(factory, new ModelCallRecord(ModelType.QWEN, "qwen-plus", "task_plan",
                1L, 900001L, null, 10, 5, 1200L, true, true, null), "备用模型调用"));

        // 4) 极端情况：errorMsg 超出 VARCHAR(500) 的长度（starter 侧会截断到 480，这里直接给 900 字符验证不报错）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            sb.append('x');
        }
        inserted.add(insert(factory, new ModelCallRecord(ModelType.DEEPSEEK, "deepseek-chat", "llm_step",
                1L, null, null, 0, 0, 1L, false, false, sb.toString()), "超长错误信息"));

        // 5) 边界：modelName/scene 为 null（列可空）与 tenantId 为 null（DDL 有默认值 1，但显式 null 会怎样？）
        inserted.add(insert(factory, new ModelCallRecord(ModelType.DEEPSEEK, null, null,
                null, null, null, 0, 0, 0L, true, false, null), "空可选字段（tenantId=null 属越界用例，见输出）"));

        // ---- 回读校验 ----
        System.out.println("--- 回读校验 ---");
        for (Long id : inserted) {
            if (id == null) {
                continue;
            }
            try (SqlSession s = factory.openSession(true)) {
                ModelCallLog row = s.getMapper(ModelCallLogMapper.class).selectById(id);
                if (row == null) {
                    System.out.printf("[回读] id=%d 未找到%n", id);
                    continue;
                }
                System.out.printf("[回读] id=%d tenant=%s type=%s name=%s scene=%s task=%s step=%s "
                                + "tokens=%d/%d/%d latency=%d success=%d fallback=%d errLen=%s%n",
                        row.getId(), row.getTenantId(), row.getModelType(), row.getModelName(), row.getScene(),
                        row.getTaskId(), row.getStepId(), row.getPromptTokens(), row.getCompletionTokens(),
                        row.getTotalTokens(), row.getLatencyMs(), row.getSuccess(), row.getFallbackUsed(),
                        row.getErrorMsg() == null ? "null" : row.getErrorMsg().length());
            }
        }

        // ---- 清理（本表是 append-only 台账，无 deleted 列） ----
        // ⚠️ 只按本次插入的主键删除：不要把条件写成 task_id IS NULL 之类的宽条件，
        //    否则在已有真实台账的库上会误删别人的数据（复现程序必须对生产数据绝对安全）。
        StringBuilder ids = new StringBuilder();
        for (Long id : inserted) {
            if (id != null) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(id);
            }
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            int n = ids.length() == 0 ? 0
                    : st.executeUpdate("DELETE FROM model_call_log WHERE id IN (" + ids + ")");
            System.out.println("[cleanup] 已清理 scratch 行: " + n);
        }
        System.out.println("[done] 真实库写入路径验证结束（无异常即表示列映射/约束/长度均可用）");
        System.out.flush();
        System.exit(0);
    }

    /** 用真实 Mapper 插入一条台账并返回主键（失败打印异常但不中断后续用例） */
    private static Long insert(SqlSessionFactory factory, ModelCallRecord record, String label) {
        try (SqlSession s = factory.openSession(true)) {
            ModelCallLog entity = ModelCallLog.from(record);
            int n = s.getMapper(ModelCallLogMapper.class).insert(entity);
            System.out.printf("[写入] %s → 影响行数=%d 主键=%s%n", label, n, entity.getId());
            return entity.getId();
        } catch (Throwable t) {
            System.out.printf("[写入] %s → 抛异常 %s: %s%n", label, t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
    }
}
