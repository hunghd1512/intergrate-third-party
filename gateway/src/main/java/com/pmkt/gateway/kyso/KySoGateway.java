package com.pmkt.gateway.kyso;

import com.pmkt.gateway.base.BaseExternalGateway;
import com.pmkt.gateway.config.GatewayConfig;
import io.github.resilience4j.httpclient.Resilience4jConfig;
import org.springframework.stereotype.Component;

/**
 * Gateway for Viettel CA digital signing.
 *
 * <p>Wires the ViettelKySoAdapter into the base gateway infrastructure:
 * all circuit breaker, rate limit, audit log, and license checks are inherited
 * from {@link BaseExternalGateway}.
 *
 * <p>CRITICAL: This gateway intentionally disables retry.
 * Signing the same document twice produces a duplicate signature — a business error.
 */
@Component
public class KySoGateway extends BaseExternalGateway<KySoRequest, KySoResult> {

    private final ViettelKySoAdapter adapter;

    public KySoGateway(ViettelKySoAdapter adapter, KySoConfig config) {
        super(config);
        this.adapter = adapter;
    }

    @Override
    protected KySoResult callVendor(KySoRequest request) {
        return adapter.kyTaiLieu(request);
    }

    @Override
    protected String vendorName() {
        return "ViettelKySo";
    }

    @Override
    protected void applyAuth(Resilience4jConfig.HttpRequest.Builder builder) {
        // Certificate-based auth is handled at the HTTP client level in ViettelKySoAdapter
        // The SSLContext with PKCS#12 keystore provides client-cert authentication
    }
}
