package com.pmkt.gateway.kyso;

import com.pmkt.gateway.domain.GatewayResponse;
import java.time.Instant;

/**
 * Response from Viettel CA digital signing operation.
 */
public class KySoResult implements GatewayResponse {

    private final String transactionId;
    private final String signedHash;
    private final byte[] signedDocumentBytes;
    private final String signerInfo;
    private final Instant signedAt;

    public KySoResult(String transactionId,
                      String signedHash,
                      byte[] signedDocumentBytes,
                      String signerInfo,
                      Instant signedAt) {
        this.transactionId = transactionId;
        this.signedHash = signedHash;
        this.signedDocumentBytes = signedDocumentBytes;
        this.signerInfo = signerInfo;
        this.signedAt = signedAt;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getSignedHash() {
        return signedHash;
    }

    public byte[] getSignedDocumentBytes() {
        return signedDocumentBytes;
    }

    public String getSignerInfo() {
        return signerInfo;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public static KySoResult fromBytes(byte[] rawResponse) {
        // TODO: Parse Viettel CA SOAP/XML or JSON response format
        // Placeholder — replace with actual parsing based on Viettel API spec
        return new KySoResult(
            "pending-tx-" + System.currentTimeMillis(),
            "",
            rawResponse,
            "",
            Instant.now()
        );
    }

    public static KySoResult fromVendorResponse(VendorKySoResponse vendorResponse) {
        return new KySoResult(
            vendorResponse.getTransactionId(),
            vendorResponse.getSignature(),
            vendorResponse.getSignedDocument(),
            vendorResponse.getSignerSubject(),
            Instant.now()
        );
    }
}
