package com.pmkt.gateway.base;

public class LicenseExpiredException extends RuntimeException {

    private final String serviceCode;

    public LicenseExpiredException(String serviceCode) {
        super("License expired for service: " + serviceCode);
        this.serviceCode = serviceCode;
    }

    public String getServiceCode() {
        return serviceCode;
    }
}
