package com.finaudit.starter.trace;

/**
 * 当前线程 traceId 持有者。
 * <p>日志配置中可用 {@code %X{traceId}} 输出链路 ID。</p>
 */
public final class TraceContextHolder {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
