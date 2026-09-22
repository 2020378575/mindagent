package com.mindbridge.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MindBridge 后端启动入口。
 *
 * <p>应用启动后会加载配置、按显式凭据初始化账号和知识库，并开放研究工作区接口。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(MindBridgeProperties.class)
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
