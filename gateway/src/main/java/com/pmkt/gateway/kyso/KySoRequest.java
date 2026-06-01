package com.pmkt.gateway.kyso;

import com.pmkt.gateway.domain.GatewayRequest;
import java.nio.charset.StandardCharsets;

/**
 * Request object for the Viettel CA digital signing operation.
 *
 * <p>Contains raw document bytes and the USB token serial number.
 * The adapter serializes this to the format required by Viettel CA API.
 */
public class KySoRequest implements GatewayRequest {

    private final byte[] documentBytes;
    private final String documentName;
    private final String serialNumber;

    public KySoRequest(byte[] documentBytes, String serialNumber) {
        this(documentBytes, "document.pdf", serialNumber);
    }

    public KySoRequest(byte[] documentBytes, String documentName, String serialNumber) {
        this.documentBytes = documentBytes;
        this.documentName = documentName;
        this.serialNumber = serialNumber;
    }

    public byte[] getDocumentBytes() {
        return documentBytes;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public byte[] toBytes() {
        return documentBytes;
    }
}
