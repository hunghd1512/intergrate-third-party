package com.pmkt.gateway.kyso;

import com.pmkt.gateway.base.ExternalGatewayException;
import com.pmkt.gateway.base.LicenseExpiredException;
import com.pmkt.gateway.base.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Domain handler for the digital-signing use case.
 *
 * <p>Orchestrates the business workflow around the gateway call:
 * <ol>
 *   <li>Load the contract entity</li>
 *   <li>Mark contract as "preparing to sign"</li>
 *   <li>Call KySoGateway (outside the DB transaction)</li>
 *   <li>Mark contract as "signed" or "failed" based on result</li>
 * </ol>
 *
 * <p>The handler knows nothing about HTTP, certificates, or circuit breakers.
 */
@Service
public class KySoService {

    private static final Logger log = LoggerFactory.getLogger(KySoService.class);

    private final KySoGateway kySoGateway;

    public KySoService(KySoGateway kySoGateway) {
        this.kySoGateway = kySoGateway;
    }

    /**
     * Signs a contract document using Viettel CA.
     *
     * @param command contains contract ID and USB token serial number
     * @return result with transaction ID and signed document
     */
    public KySoResult signHopDong(KySoCommand command) {
        log.info("Starting digital signature for hopDongId={}, serial={}",
                 command.hopDongId(), command.serialNumber());

        // TODO: Load HopDong entity
        // HopDong hopDong = hopDongRepo.findById(command.hopDongId());
        // hopDong.chuanBiKy();

        try {
            KySoResult result = kySoGateway.execute(
                new KySoRequest(
                    loadDocumentBytes(command.hopDongId()),
                    command.documentName(),
                    command.serialNumber()
                )
            );

            // TODO: Persist result
            // hopDong.xacNhanKy(result.getSignedHash());
            // hopDongRepo.save(hopDong);

            log.info("Digital signature SUCCESS: hopDongId={}, txId={}",
                     command.hopDongId(), result.getTransactionId());
            return result;

        } catch (LicenseExpiredException e) {
            log.error("License expired for KySo service: {}", e.getServiceCode());
            // TODO: hopDong.kyThatBai("License hết hạn");
            throw e;

        } catch (QuotaExceededException e) {
            log.error("KySo quota exceeded: used={}, limit={}",
                      e.getUsed(), e.getLimit());
            // TODO: hopDong.kyThatBai("Đã hết hạn mức ký số");
            throw e;

        } catch (ExternalGatewayException e) {
            log.error("Gateway error from {}: statusCode={}, message={}",
                      e.getVendorName(), e.getVendorStatusCode(), e.getMessage());
            // TODO: hopDong.kyThatBai("Lỗi kết nối: " + e.getMessage());
            throw e;
        }
    }

    private byte[] loadDocumentBytes(String hopDongId) {
        // TODO: Load actual document bytes from storage
        // return hopDong.getDocumentBytes();
        return ("Contract " + hopDongId + " content").getBytes();
    }
}
