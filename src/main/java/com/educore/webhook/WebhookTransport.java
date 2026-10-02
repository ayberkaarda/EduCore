package com.educore.webhook;

import java.net.URI;
import java.util.Map;

/** Sends one signed webhook request. */
public interface WebhookTransport {

    /**
     * POSTs {@code body} to {@code url} with {@code headers}.
     *
     * @return the HTTP status of the response (redirects are not followed, so 3xx is returned as is)
     * @throws WebhookSendException when no response was received; its message is a fixed error code
     */
    int send(URI url, Map<String, String> headers, byte[] body) throws WebhookSendException;

    /** A request that got no HTTP response. {@link #getMessage()} is a fixed code such as {@code timeout}. */
    class WebhookSendException extends Exception {

        public WebhookSendException(String code) {
            super(code, null, false, false);
        }
    }
}
