package com.pmkt.gateway.base;

/**
 * Root checked exception thrown when a third-party vendor call fails
 * at the gateway layer — after circuit breaker, rate limit, and license checks pass.
 */
public class ExternalGatewayException extends RuntimeException {

    private final String vendorName;
    private final int vendorStatusCode;

    public ExternalGatewayException(String vendorName, String message) {
        super(message);
        this.vendorName = vendorName;
        this.vendorStatusCode = -1;
    }

    public ExternalGatewayException(String vendorName, int statusCode, String message) {
        super(message);
        this.vendorName = vendorName;
        this.vendorStatusCode = statusCode;
    }

    public ExternalGatewayException(String vendorName, Throwable cause) {
        super(cause);
        this.vendorName = vendorName;
        this.vendorStatusCode = -1;
    }

    public String getVendorName() {
        return vendorName;
    }

    public int getVendorStatusCode() {
        return vendorStatusCode;
    }
}
