package com.pmkt.gateway.base;

public class QuotaExceededException extends RuntimeException {

    private final String serviceCode;
    private final long used;
    private final long limit;

    public QuotaExceededException(String serviceCode, long used, long limit) {
        super(String.format("Quota exceeded for service %s: used=%d, limit=%d", serviceCode, used, limit));
        this.serviceCode = serviceCode;
        this.used = used;
        this.limit = limit;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public long getUsed() {
        return used;
    }

    public long getLimit() {
        return limit;
    }
}
