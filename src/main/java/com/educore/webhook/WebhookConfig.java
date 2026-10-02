package com.educore.webhook;

import com.educore.config.EduCoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/** The webhook HTTP transport, trusting the JVM's default CA certificates. */
@Configuration(proxyBeanMethods = false)
public class WebhookConfig {

    /** TLS settings of outgoing webhook requests (the JVM default trust store). */
    public record WebhookTls(SSLContext sslContext) {
    }

    @Bean
    public WebhookTls webhookTls() throws NoSuchAlgorithmException {
        return new WebhookTls(SSLContext.getDefault());
    }

    @Bean(destroyMethod = "close")
    public HttpClientWebhookTransport webhookTransport(WebhookAddressPolicy policy, WebhookTls tls,
                                                       EduCoreProperties properties) {
        EduCoreProperties.Webhook webhook = properties.webhook();
        return new HttpClientWebhookTransport(policy, tls.sslContext(), webhook.connectTimeout(), webhook.readTimeout(),
                webhook.requestDeadline());
    }
}
