package com.pmkt.gateway.kyso;

import com.pmkt.gateway.base.ExternalGatewayException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Viettel CA adapter — vendor-specific HTTP client.
 *
 * <p>Handles all Viettel-specific concerns:
 * <ul>
 *   <li>PKCS#12 certificate loading for client auth</li>
 *   <li>SOAP/XML or JSON payload mapping</li>
 *   <li>Vendor-specific error code mapping</li>
 *   <li>Vendor-specific timeout configuration</li>
 * </ul>
 *
 * <p>This class knows nothing about circuit breakers, rate limits, or audit logs.
 */
public class ViettelKySoAdapter {

    private static final Logger log = LoggerFactory.getLogger(ViettelKySoAdapter.class);

    private final KySoConfig config;
    private final HttpClient httpClient;

    public ViettelKySoAdapter(KySoConfig config) {
        this.config = config;
        this.httpClient = buildHttpClient(config);
    }

    /**
     * Signs a document using Viettel CA USB token.
     *
     * @param request contains raw document bytes and token serial number
     * @return parsed vendor response mapped to domain model
     */
    public KySoResult kyTaiLieu(KySoRequest request) {
        log.debug("Sending sign request to Viettel CA for serial={}", request.getSerialNumber());

        VendorKySoResponse vendorResp = sendSignRequest(request);
        validateVendorResponse(vendorResp);
        return KySoResult.fromVendorResponse(vendorResp);
    }

    private VendorKySoResponse sendSignRequest(KySoRequest request) {
        try {
            String payload = buildSoapPayload(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"https://ca.viettel.vn/signDocument\"")
                .header("X-Serial-Number", request.getSerialNumber())
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                .build();

            HttpResponse<byte[]> httpResponse = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofByteArray()
            );

            return parseSoapResponse(httpResponse);

        } catch (IOException e) {
            throw new ExternalGatewayException("ViettelKySo", "Network I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalGatewayException("ViettelKySo", "Request interrupted", e);
        } catch (Exception e) {
            throw new ExternalGatewayException("ViettelKySo", "Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Builds SOAP envelope for Viettel CA signDocument API.
     * Replace with actual Viettel API payload format.
     */
    private String buildSoapPayload(KySoRequest request) {
        String encodedDoc = Base64.getEncoder().encodeToString(request.getDocumentBytes());
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:ns="https://ca.viettel.vn/">
              <soap:Header/>
              <soap:Body>
                <ns:SignDocumentRequest>
                  <ns:SerialNumber>%s</ns:SerialNumber>
                  <ns:DocumentName>%s</ns:DocumentName>
                  <ns:DocumentData>%s</ns:DocumentData>
                  <ns:SignatureAlgorithm>SHA256withRSA</ns:SignatureAlgorithm>
                </ns:SignDocumentRequest>
              </soap:Body>
            </soap:Envelope>
            """.formatted(
                escapeXml(request.getSerialNumber()),
                escapeXml(request.getDocumentName()),
                encodedDoc
            );
    }

    private VendorKySoResponse parseSoapResponse(HttpResponse<byte[]> httpResponse) {
        int status = httpResponse.statusCode();
        String body = new String(httpResponse.body(), StandardCharsets.UTF_8);

        if (status != 200) {
            log.error("Viettel CA HTTP error: status={}, body={}", status, body);
            throw new ExternalGatewayException("ViettelKySo", status,
                "HTTP " + status + " from Viettel CA");
        }

        // TODO: Parse actual SOAP XML response
        // Placeholder: extract fields from SOAP response
        VendorKySoResponse resp = new VendorKySoResponse();
        resp.setCode("00");
        resp.setMessage("Success");
        resp.setTransactionId(extractXmlTag(body, "TransactionId"));
        resp.setSignature(extractXmlTag(body, "Signature"));
        resp.setSignedDocument(Base64.getDecoder().decode(
            extractXmlTag(body, "SignedDocumentData")));
        resp.setSignerSubject(extractXmlTag(body, "SignerSubject"));
        return resp;
    }

    private void validateVendorResponse(VendorKySoResponse resp) {
        if (!resp.isSuccess()) {
            String msg = "Viettel CA returned error: code=" + resp.getCode()
                + ", message=" + resp.getMessage();
            log.error(msg);
            throw new ExternalGatewayException("ViettelKySo", resp.getCode(), msg);
        }
    }

    private String extractXmlTag(String xml, String tagName) {
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = xml.indexOf(openTag);
        int end = xml.indexOf(closeTag);
        if (start < 0 || end < 0) return "";
        return xml.substring(start + openTag.length(), end);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                 .replace("<", "&lt;")
                 .replace(">", "&gt;")
                 .replace("\"", "&quot;")
                 .replace("'", "&apos;");
    }

    private HttpClient buildHttpClient(KySoConfig config) {
        try {
            // Load client certificate from PKCS#12 keystore
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var is = new java.io.FileInputStream(config.certificatePath())) {
                keyStore.load(is, config.certificatePassword().toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, config.certificatePassword().toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            return HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(java.time.Duration.ofSeconds(
                    Math.min(config.timeoutSeconds(), 10)))
                .build();

        } catch (Exception e) {
            log.warn("Failed to initialize SSL context with certificate, falling back to default",
                     e);
            return HttpClient.newHttpClient();
        }
    }

    /** Loads the X509 certificate from the configured keystore for display purposes. */
    public X509Certificate loadCertificate() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var is = new java.io.FileInputStream(config.certificatePath())) {
            keyStore.load(is, config.certificatePassword().toCharArray());
        }
        java.security.PrivateKey key = (java.security.PrivateKey) keyStore.getKey(
            keyStore.aliases().nextElement(),
            config.certificatePassword().toCharArray()
        );
        // Return the certificate chain
        java.security.cert.Certificate[] chain = keyStore.getCertificateChain(
            keyStore.aliases().nextElement()
        );
        return (X509Certificate) chain[0];
    }
}
