package com.pmkt.gateway.kyso;

/**
 * Raw vendor response from Viettel CA API.
 * Maps directly to the external API payload — kept separate from domain model.
 */
public class VendorKySoResponse {

    private String code;
    private String message;
    private String transactionId;
    private String signature;
    private byte[] signedDocument;
    private String signerSubject;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public byte[] getSignedDocument() {
        return signedDocument;
    }

    public void setSignedDocument(byte[] signedDocument) {
        this.signedDocument = signedDocument;
    }

    public String getSignerSubject() {
        return signerSubject;
    }

    public void setSignerSubject(String signerSubject) {
        this.signerSubject = signerSubject;
    }

    public boolean isSuccess() {
        return "00".equals(code) || "01".equals(code);
    }
}
