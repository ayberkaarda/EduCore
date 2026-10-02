package com.educore.ipaccess;

import com.educore.authz.AuthzIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** {@code educore.ipaccess.ipv6-policy=DENY}: native IPv6 clients are refused, IPv4-mapped ones are IPv4. */
@TestPropertySource(properties = "educore.ipaccess.ipv6-policy=DENY")
class Ipv6PolicyIT extends AuthzIntegrationSupport {

    private MvcResult request(String peer) throws Exception {
        return mockMvc.perform(get("/api/v1/public/site-facts").with(from(peer))).andReturn();
    }

    @Test
    void nativeIpv6IsRefusedMappedIpv4IsNot() throws Exception {
        MvcResult ipv6 = request("2001:db8::6");
        assertThat(ipv6.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(ipv6).get("code").asText()).isEqualTo("ipaccess/ipv6-unsupported");

        assertThat(request("::ffff:203.0.113.66").getResponse().getStatus()).isEqualTo(200);
        assertThat(request("203.0.113.66").getResponse().getStatus()).isEqualTo(200);
    }
}
