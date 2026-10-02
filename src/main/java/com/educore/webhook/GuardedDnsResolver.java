package com.educore.webhook;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * DNS resolver of the webhook HTTP client: resolves the host and rejects it when the name or any of its
 * addresses is blocked by the {@link WebhookAddressPolicy}. Because the client connects only to addresses
 * returned here, the check applies to the very address that is used (no DNS-rebinding window).
 */
public class GuardedDnsResolver implements DnsResolver {

    /** Name resolution (the system resolver in production; replaceable in tests). */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final WebhookAddressPolicy policy;
    private final Lookup lookup;

    public GuardedDnsResolver(WebhookAddressPolicy policy) {
        this(policy, InetAddress::getAllByName);
    }

    public GuardedDnsResolver(WebhookAddressPolicy policy, Lookup lookup) {
        this.policy = policy;
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (policy.isBlockedHostName(host)) {
            throw new BlockedAddressException();
        }
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses.length == 0 || !Arrays.stream(addresses).allMatch(policy::isAllowed)) {
            throw new BlockedAddressException();
        }
        return addresses;
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        // No reverse lookup: the checked name is the canonical one for TLS and logging purposes.
        resolve(host);
        return host;
    }
}
