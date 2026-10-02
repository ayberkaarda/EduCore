package com.educore.webhook;

import java.net.UnknownHostException;

/**
 * The webhook host resolves to an address the {@link WebhookAddressPolicy} rejects (or is a blocked name).
 * An {@link UnknownHostException} so the HTTP client's DNS resolver can raise it; the message names no
 * address.
 */
public class BlockedAddressException extends UnknownHostException {

    public BlockedAddressException() {
        super("Webhook target address is not allowed");
    }
}
