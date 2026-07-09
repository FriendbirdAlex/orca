package com.orca.workflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置(供 HttpNodeExecutor 调外部接口)。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(WorkflowProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getHttpConnectTimeoutMs());
        factory.setReadTimeout((int) props.getHttpReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
