package com.educore.config;

import com.educore.security.TrustedProxies;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@code server.forward-headers-strategy=native} installs Tomcat's {@code RemoteIpValve}, which by default
 * trusts every private, loopback and link-local peer. This customizer narrows it to exactly
 * {@code educore.ipaccess.trusted-proxies}: only such a peer can change the request's remote address
 * ({@code X-Forwarded-For}) or scheme ({@code X-Forwarded-Proto}, which {@code requiresSecure} relies on).
 * With no trusted proxy configured, the valve trusts no peer. It runs after Spring Boot's own Tomcat
 * customizer, which adds the valve.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeadersConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> trustedProxyRemoteIpValve(TrustedProxies trustedProxies) {
        return new TrustedProxyValveCustomizer(trustedProxies.toTomcatRegex());
    }

    static final class TrustedProxyValveCustomizer
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        private final String internalProxies;

        TrustedProxyValveCustomizer(String internalProxies) {
            this.internalProxies = internalProxies;
        }

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            for (Valve valve : factory.getEngineValves()) {
                if (valve instanceof RemoteIpValve remoteIp) {
                    remoteIp.setInternalProxies(internalProxies);
                    remoteIp.setTrustedProxies(null);
                }
            }
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
