package com.finaudit.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rag-service 启动类：RAG 专用基础设施（企业文档 → 向量 → 语义查询）。
 * <p>P4 填充 Milvus 向量检索能力；不碰文件上传（file-service）、不做报销审核（agent-core）、
 * 不参与单据 OCR。当前为空骨架，保留端口 9204 与注册。</p>
 */
@SpringBootApplication
public class RagServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagServiceApplication.class, args);
    }
}
