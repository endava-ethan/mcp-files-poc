package dev.poc.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple helper for logging framed traffic in a consistent format so that client and server logs
 * look identical.
 */
public final class Wire {

    private static final Logger LOGGER = LoggerFactory.getLogger("WIRE");

    private Wire() {
    }

    public static void rx(String connectionId, Envelope envelope) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("RX conn={} type={} req={} corr={} final={} seq={} json={}",
                connectionId,
                envelope.messageType(),
                envelope.requestId(),
                envelope.correlationId(),
                envelope.fin(),
                envelope.seq(),
                truncate(envelope.jsonrpc(), 200));
        }
    }

    public static void tx(String connectionId, Envelope envelope) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("TX conn={} type={} req={} corr={} final={} seq={} json={}",
                connectionId,
                envelope.messageType(),
                envelope.requestId(),
                envelope.correlationId(),
                envelope.fin(),
                envelope.seq(),
                truncate(envelope.jsonrpc(), 200));
        }
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
