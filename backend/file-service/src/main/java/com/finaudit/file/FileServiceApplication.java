package com.finaudit.file;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * file-service 启动类：纯二进制资源服务（上传 / 下载 / 预览）。
 * <p>仅处理文件元数据 + 对象存储；不感知财务业务 / Agent 任务 / 审核流程。
 * 业务服务读附件一律经 common-code {@code FileServiceFeign} 远程调用，禁止直连 OSS。</p>
 */
@SpringBootApplication
@MapperScan("com.finaudit.file.mapper")
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}
