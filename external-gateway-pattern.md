# External Gateway Interface Pattern

> Tư duy thiết kế lớp tích hợp API bên thứ 3 trong hệ thống nghiệp vụ.

---

## 1. Vấn đề cần giải quyết

Mỗi điểm tích hợp bên thứ 3 đều có các concern giống nhau:

- **Circuit Breaker** — tự ngắt khi vendor liên tục lỗi
- **Retry** — thử lại khi lỗi tạm thời
- **Rate Limit** — không vượt ngưỡng vendor cho phép
- **Auth** — xác thực với vendor (certificate, API key, OAuth2...)
- **Audit / Logging** — ghi lại mọi request/response
- **License** — mỗi nghiệp vụ có hạn mức, gói dịch vụ riêng

Nếu không tổ chức tốt, những concern này bị lặp lại ở mọi chỗ và khó thay thế vendor.

---

## 2. Phân tách trách nhiệm

```
Business Logic (Handler)
        │
        ▼
  Gateway (protected layer)        ← mình kiểm soát
        │  - auth
        │  - rate limit
        │  - circuit breaker
        │  - retry
        │  - license check
        │  - audit logging
        ▼
  Adapter (vendor-specific)        ← mình viết, vendor thay đổi
        │  - map request/response
        │  - xử lý lỗi HTTP
        │  - timeout config
        ▼
  Vendor API                       ← không kiểm soát được
```

**Nguyên tắc:**
- Gateway lo **cross-cutting concerns** — không biết vendor cụ thể
- Adapter lo **chi tiết vendor** — không biết circuit breaker hay rate limit
- Handler chỉ **gọi Gateway** — không biết gì về vendor hay infrastructure

---

## 3. Base Gateway — phần dùng chung

```java
/**
 * Abstract base cho mọi tích hợp bên thứ 3.
 * Subclass chỉ cần implement callVendor() và cấu hình riêng.
 */
public abstract class BaseExternalGateway<Req, Res> {

    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final AuditLogger auditLogger;
    private final LicenseValidator licenseValidator;

    protected BaseExternalGateway(GatewayConfig config) {
        this.circuitBreaker  = buildCircuitBreaker(config);
        this.rateLimiter     = buildRateLimiter(config);
        this.auditLogger     = new AuditLogger(config.getServiceName());
        this.licenseValidator = new LicenseValidator(config.getLicenseKey());
    }

    // Template method — flow cố định, chi tiết thay đổi
    public final Res execute(Req request) {
        licenseValidator.validate();          // kiểm tra license trước
        rateLimiter.acquire();                // rate limit
        auditLogger.logRequest(request);      // ghi log request

        return circuitBreaker.executeSupplier(() -> {
            try {
                Res result = callVendor(request);
                auditLogger.logSuccess(result);
                return result;
            } catch (Exception e) {
                auditLogger.logFailure(e);
                throw new ExternalGatewayException(vendorName(), e);
            }
        });
    }

    // Mỗi subclass implement phần này
    protected abstract Res callVendor(Req request);
    protected abstract String vendorName();
}
```

---

## 4. Những gì KHÔNG dùng chung

Mỗi nghiệp vụ có đặc thù riêng — cấu hình độc lập, không hard-code chung.

### 4.1 Auth mechanism

| Nghiệp vụ | Cơ chế auth |
|---|---|
| Ký số | Certificate + private key (PKCS#12) |
| SMS | API key trong header |
| Email | OAuth2 Bearer token |
| Payment | HMAC signature theo request |
| CCCD / eKYC | Mutual TLS (mTLS) |

```java
// Mỗi adapter tự xử lý auth của mình
public class ViettelKySoAdapter {
    private final KeyStore keyStore;          // certificate

    private HttpRequest buildRequest(KySoRequest req) {
        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("X-Certificate", extractCert(keyStore))
            .POST(HttpRequest.BodyPublishers.ofByteArray(req.toBytes()))
            .build();
    }
}

public class ViettelSmsAdapter {
    private final String apiKey;              // API key đơn giản hơn

    private HttpRequest buildRequest(SmsRequest req) {
        return HttpRequest.newBuilder()
            .header("X-API-Key", apiKey)
            .build();
    }
}
```

### 4.2 Retry strategy

> **Quan trọng:** Không phải nghiệp vụ nào cũng nên retry.

| Nghiệp vụ | Retry | Lý do |
|---|---|---|
| Ký số | **Không** | Ký 2 lần = lỗi nghiệp vụ, file bị duplicate signature |
| SMS OTP | Tối đa 2 lần | OTP có TTL, retry quá nhiều gây nhầm lẫn |
| Email thông báo | 3 lần, exponential backoff | Idempotent, an toàn retry |
| Query dữ liệu | 3 lần | Idempotent hoàn toàn |
| Payment | **Không** | Có thể tạo giao dịch trùng |

```java
// Ký số — tuyệt đối không retry
@Bean
RetryConfig kySoRetryConfig() {
    return RetryConfig.custom()
        .maxAttempts(1)   // chỉ 1 lần
        .build();
}

// Email — retry an toàn
@Bean
RetryConfig emailRetryConfig() {
    return RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofSeconds(2))
        .retryOnException(e -> e instanceof NetworkException)
        .build();
}
```

### 4.3 Circuit Breaker config

| Nghiệp vụ | Failure threshold | Wait duration | Lý do |
|---|---|---|---|
| Ký số | 30% | 60s | Nghiệp vụ quan trọng, ngắt sớm |
| SMS | 50% | 30s | Chịu lỗi cao hơn |
| Email | 80% | 15s | Không critical |

```java
@Bean
CircuitBreaker kySoCircuitBreaker() {
    return CircuitBreaker.of("ky-so",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(30)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .slidingWindowSize(10)
            .build());
}

@Bean
CircuitBreaker smsCircuitBreaker() {
    return CircuitBreaker.of("sms",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());
}
```

### 4.4 License / quota management

Đây là concern hay bị bỏ quên — mỗi hợp đồng với vendor có hạn mức riêng.

```java
public class LicenseValidator {
    private final LicenseRepository licenseRepo;
    private final String serviceCode;

    public void validate() {
        License license = licenseRepo.findActive(serviceCode)
            .orElseThrow(() -> new LicenseExpiredException(serviceCode));

        if (license.isExpired()) {
            throw new LicenseExpiredException(serviceCode);
        }

        if (license.isQuotaExceeded()) {
            throw new QuotaExceededException(
                serviceCode,
                license.getUsed(),
                license.getLimit()
            );
        }

        license.incrementUsage();    // tăng counter
        licenseRepo.save(license);
    }
}

// License entity — quản lý hạn mức
public class License {
    private String serviceCode;
    private LocalDate expiredAt;
    private long limit;       // tổng số lượt được dùng
    private long used;        // đã dùng bao nhiêu
    private LicenseType type; // MONTHLY, ANNUAL, PER_REQUEST

    public boolean isExpired() {
        return LocalDate.now().isAfter(expiredAt);
    }

    public boolean isQuotaExceeded() {
        return type != LicenseType.UNLIMITED && used >= limit;
    }

    public void incrementUsage() {
        this.used++;
    }
}
```

---

## 5. Ví dụ hoàn chỉnh — KySoGateway

```java
// Config riêng cho ký số
@ConfigurationProperties(prefix = "gateway.ky-so")
public record KySoGatewayConfig(
    String endpoint,
    String certificatePath,
    String certificatePassword,
    int timeoutSeconds,
    String licenseKey
) implements GatewayConfig {}

// Gateway — kế thừa base, chỉ implement callVendor
@Component
public class KySoGateway extends BaseExternalGateway<KySoRequest, KySoResult> {

    private final KySoAdapter adapter;

    public KySoGateway(KySoAdapter adapter, KySoGatewayConfig config) {
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
}

// Adapter — chỉ biết HTTP và Viettel API
@Component
public class ViettelKySoAdapter {

    public KySoResult kyTaiLieu(KySoRequest request) {
        HttpResponse<byte[]> response = httpClient.send(
            buildRequest(request),
            HttpResponse.BodyHandlers.ofByteArray()
        );

        if (response.statusCode() != 200) {
            throw new VendorApiException("Viettel KySo", response.statusCode());
        }

        return KySoResult.fromBytes(response.body());
    }
}

// Handler — chỉ gọi Gateway, không biết gì khác
@Transactional
public void handle(KyHopDongCommand cmd) {
    var hopDong = hopDongRepo.findById(cmd.hopDongId());
    hopDong.chuanBiKy();
    hopDongRepo.save(hopDong);

    // Gọi ngoài transaction
    try {
        var result = kySoGateway.execute(
            new KySoRequest(hopDong.toBytes(), cmd.serialNumber())
        );
        hopDong.xacNhanKy(result.getSignedHash());

    } catch (LicenseExpiredException e) {
        hopDong.kyThatBai("License hết hạn: " + e.getServiceCode());

    } catch (ExternalGatewayException e) {
        hopDong.kyThatBai("Lỗi kết nối: " + e.getMessage());
    }

    hopDongRepo.save(hopDong);
}
```

---

## 6. Khi đổi vendor

Chỉ cần viết Adapter mới, không đụng gì khác:

```java
// Đổi từ Viettel sang VNPT — chỉ viết class này
@Component
@ConditionalOnProperty(name = "gateway.ky-so.vendor", havingValue = "vnpt")
public class VnptKySoAdapter implements KySoAdapter {
    public KySoResult kyTaiLieu(KySoRequest request) {
        // gọi API VNPT
    }
}
```

---

## 7. Tóm tắt

| Concern | Chung (Base) | Riêng (Config/Adapter) |
|---|---|---|
| Flow: validate → rate limit → call → log | ✅ | |
| Circuit Breaker logic | ✅ | Config threshold riêng |
| Rate Limit logic | ✅ | Config limit riêng |
| Audit / Logging | ✅ | |
| License check | ✅ | License key + quota riêng |
| Auth mechanism | | ✅ Mỗi vendor khác nhau |
| Retry strategy | | ✅ Tùy nghiệp vụ |
| HTTP call | | ✅ Mỗi vendor khác nhau |
| Error mapping | | ✅ Mỗi vendor khác nhau |

**Thêm vendor mới** = viết 1 Adapter + 1 Config. Toàn bộ circuit breaker, audit, license đã có sẵn.

**Thay vendor cũ** = viết Adapter mới, inject vào Gateway cũ. Handler không đổi gì.
