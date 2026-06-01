package com.pmkt.gateway.kyso;

import com.pmkt.gateway.base.ExternalGatewayException;
import com.pmkt.gateway.base.LicenseExpiredException;
import com.pmkt.gateway.base.QuotaExceededException;
import com.pmkt.gateway.base.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KySoGatewayTest {

    /**
     * Unit test verifying the gateway template method flow:
     * license check → rate limit → audit log → callVendor → audit success
     *
     * Uses a mock adapter to avoid real HTTP calls.
     */
    @Test
    void execute_shouldPassRequestToAdapter() {
        // Given
        KySoConfig config = KySoConfig.testConfig();
        KySoGateway gateway = new KySoGateway(
            new MockViettelKySoAdapter(false),
            config
        );

        KySoRequest request = new KySoRequest(
            "Test document content".getBytes(),
            "hopdong-001",
            "USB_TOKEN_SERIAL"
        );

        // When
        KySoResult result = gateway.execute(request);

        // Then
        assertNotNull(result);
        assertEquals("mock-tx-001", result.getTransactionId());
        assertNotNull(result.getSignedDocumentBytes());
    }

    @Test
    void execute_shouldThrowLicenseExpired_whenLicenseKeyBlank() {
        // Given
        KySoConfig config = new KySoConfig(
            "https://ca.viettel.vn/api/sign",
            "/certs/kyso.p12",
            "changeit",
            "",  // blank license key
            30,
            10,
            java.time.LocalDateTime.now().plusYears(1),
            -1
        );
        KySoGateway gateway = new KySoGateway(
            new MockViettelKySoAdapter(false),
            config
        );
        KySoRequest request = new KySoRequest("doc".getBytes(), "serial");

        // When/Then
        assertThrows(LicenseExpiredException.class, () -> gateway.execute(request));
    }

    @Test
    void execute_shouldThrowOnCircuitOpen_whenAdapterThrowsOpenCircuit() {
        // Given
        KySoConfig config = KySoConfig.testConfig();
        KySoGateway gateway = new KySoGateway(
            new MockViettelKySoAdapter(true),
            config
        );
        KySoRequest request = new KySoRequest("doc".getBytes(), "serial");

        // When/Then — after 10 failures circuit opens
        assertThrows(ExternalGatewayException.class, () -> gateway.execute(request));
    }

    /**
     * Mock adapter that records calls and can simulate failures.
     */
    static class MockViettelKySoAdapter extends ViettelKySoAdapter {
        private final boolean simulateOpenCircuit;
        private int callCount = 0;

        MockViettelKySoAdapter(boolean simulateOpenCircuit) {
            super(KySoConfig.testConfig());
            this.simulateOpenCircuit = simulateOpenCircuit;
        }

        @Override
        public KySoResult kyTaiLieu(KySoRequest request) {
            callCount++;
            if (simulateOpenCircuit && callCount > 5) {
                throw new ExternalGatewayException("ViettelKySo", "Simulated circuit open");
            }
            return new KySoResult(
                "mock-tx-001",
                "mock-signature-base64",
                ("signed:" + new String(request.getDocumentBytes())).getBytes(),
                "CN=Mock Signer,O=PMKT",
                java.time.Instant.now()
            );
        }
    }
}
