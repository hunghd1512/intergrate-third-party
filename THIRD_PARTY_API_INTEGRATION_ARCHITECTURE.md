# Third-Party API Integration & API Sharing Architecture
## Production-Grade Technical Reference Document

---

> **Tài liệu này được viết từ góc nhìn Senior Backend Engineer / Solution Architect với 10+ năm kinh nghiệm xây dựng hệ thống enterprise, high-traffic, distributed systems. Tập trung thực chiến, không lý thuyết sách giáo khoa. Mọi mô hình, pattern, và khuyến nghị đều xuất phát từ bài học thực tế ở production.**

---

# PHẦN 1: TÍCH HỢP API/SDK BÊN THỨ 3

## 1. Business Context — Tại Sao Chuyện Này Phức Tạp Hơn Bạn Tưởng

Hầu hết dev khi tích hợp third-party API nghĩ: "Gọi HTTP request, parse JSON, xong." Nhưng thực tế production dạy ta những bài học hoàn toàn khác:

- **Payment gateway downtime 30 phút** → toàn bộ transaction fail, tiền khách bị treo, support flooded
- **Logistics API latency spike** → warehouse system deadlock vì chờ đợi
- **ERP SDK upgrade breaking change** → 50 partner integration cùng chết một lúc
- **Third-party rate limit không documented** → production incident lúc 2 giờ sáng
- **Webhook không idempotent** → duplicate order processing, hàng xuất 2 lần

### Các loại Third-Party Integration thường gặp

| Loại | Ví dụ | Rủi ro đặc trưng |
|------|--------|-----------------|
| Payment Gateway | VNPay, MoMo, Zalopay, Stripe | Tiền, compliance, PCI-DSS |
| Logistics | GHN, GHTK, Ninja Van | Tracking sync, webhook reliability |
| ERP/CRM | SAP, Salesforce, Hubspot | Data consistency, sync windows |
| SMS/OTP | Viettel, Vinaphone, Twilio | Rate limit, carrier blocking |
| Email/Marketing | SendGrid, Mailchimp | Bounce rate, spam scoring |
| Cloud Infra | AWS S3, Google Cloud Storage | Cost explosion, regional availability |
| Identity/Auth | Auth0, Firebase Auth, Okta | Session management, token refresh |
| Map/Location | Google Maps, Mapbox | Quota, latency |
| AI/ML | OpenAI, Google Vertex AI | Cost per token, timeout |

---

## 2. Integration Architecture Patterns

### 2.1. Sync vs Async — Lựa Chọn Kiến Trúc Đầu Tiên

**Câu hỏi cần trả lời trước khi code:**

```
Request có cần response ngay không?
  ├── Có → Sync (REST/gRPC)
  └── Không → Async (Message Queue / Event-Driven)
```

**Sync Integration — Khi nào dùng:**
- User đang chờ kết quả (checkout flow, OTP verification)
- Business logic cần kết quả ngay lập tức
- Đơn giản, không cần distributed transaction

**Async Integration — Khi nào dùng:**
- Heavy batch job (sync 10,000 records → timeout chắc)
- Third-party có rate limit thấp, cần queue
- Không cần real-time (report generation, email campaign)
- Có distributed transaction cần saga pattern

```java
// Ví dụ thực tế: Sync integration cho payment verification
@Service
public class PaymentIntegrationService {

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentIntegrationService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
            .baseUrl("https://api.vnpay.vn/v2")
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("Accept", "application/json")
            .build();

        this.circuitBreakerRegistry = circuitBreakerRegistry();
    }

    public PaymentResult verifyPayment(String transactionId) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("vnpay");

        return Checked Suppliers.uncheckedSupplier(() -> {
            PaymentResponse response = webClient.post()
                .uri("/payment/verify")
                .bodyValue(PaymentVerifyRequest.builder()
                    .transactionId(transactionId)
                    .timestamp(Instant.now().toEpochMilli())
                    .build())
                .retrieve()
                .bodyToMono(PaymentResponse.class)
                .timeout(Duration.ofSeconds(10))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
                    .filter(this::isRetryable)
                    .onRetryExhaustedThrow((retry, throwable) -> throwable))
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .block();

            return validateAndMap(response);
        }).get();
    }
}
```

### 2.2. Anti-Corruption Layer (ACL) — Không Để Vendor Lock-in Giết Bạn

**Vấn đề thực tế:** Khi tích hợp nhiều third-party, code của bạn bị污染 bởi vendor-specific models, exceptions, naming conventions. Ngày vendor đổi API, bạn phải sửa khắp nơi.

**Giải pháp:** Tạo một lớp adapter trung gian giữa domain logic và external API.

```
┌─────────────────────────────────────────────┐
│          Domain Layer (Core Business)       │
│  - PaymentService, OrderService, UserService│
└──────────────────────┬──────────────────────┘
                       │ calls via interfaces
                       ▼
┌─────────────────────────────────────────────┐
│     Anti-Corruption Layer (Adapter)         │
│  - VnpayAdapter implements PaymentGateway   │
│  - GhnAdapter implements ShippingGateway    │
│  - Transform vendor DTO → internal domain  │
└──────────────────────┬──────────────────────┘
                       │ HTTP/SDK calls
                       ▼
┌─────────────────────────────────────────────┐
│          External Third-Party APIs          │
│  - VNPay, GHN, MoMo, SendGrid...           │
└─────────────────────────────────────────────┘
```

```java
// Domain interface - what WE need
public interface PaymentGateway {
    PaymentVerifyResult verify(String transactionId);
    RefundResult refund(String paymentId, Money amount);
    WebhookEvent parseWebhook(Map<String, Object> payload);
}

// Adapter cho từng vendor
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "vnpay")
public class VnpayAdapter implements PaymentGateway {

    private final VnpayClient vnpayClient;
    private final VnpayWebhookValidator webhookValidator;

    @Override
    public PaymentVerifyResult verify(String transactionId) {
        VnpayResponse raw = vnpayClient.callVerifyApi(transactionId);
        // Transform vendor model → internal domain
        return PaymentVerifyResult.builder()
            .transactionId(raw.getVnpTransactionNo())
            .status(mapStatus(raw.getVnpResponseCode()))
            .amount(Money.of(raw.getVnpAmount(), "VND"))
            .paidAt(raw.getVnpPayDate())
            .build();
    }

    @Override
    public WebhookEvent parseWebhook(Map<String, Object> payload) {
        if (!webhookValidator.isValidSignature(payload)) {
            throw new SecurityException("Invalid webhook signature from VNPay");
        }
        // ... map
    }

    private PaymentStatus mapStatus(String vnpResponseCode) {
        return switch (vnpResponseCode) {
            case "00" -> PaymentStatus.SUCCESS;
            case "01", "02" -> PaymentStatus.PENDING;
            case "24", "65" -> PaymentStatus.FAILED;
            default -> PaymentStatus.UNKNOWN;
        };
    }
}
```

**Lợi ích thực tế:**
- Swap payment provider từ VNPay sang MoMo chỉ cần deploy adapter mới, không sửa domain layer
- Test domain logic không cần gọi real API (mock adapter)
- Vendor breaking change isolated ở một class duy nhất

---

## 3. Resiliency Patterns — Sống Sót Khi Third-Party Chết

### 3.1. Circuit Breaker — Phòng Thủ Tuyến Đầu

**Bài học production:** Không có circuit breaker, khi payment gateway chết, thread pool exhaustion lan sang toàn bộ service → cascading failure → outage toàn hệ thống.

```java
@Configuration
public class ResiliencyConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                // Sliding window: trong 10 giây, nếu 50% request fail → OPEN
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                // OPEN trong 30 giây rồi thử half-open
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                // Slow call: >5s là slow → count như fail
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(80)
                // Ignore certain exceptions (client timeout không phải lỗi gateway)
                .ignoreExceptions(BusinessException.class, ValidationException.class)
                .build()
        );
    }
}
```

**Ba trạng thái thực tế:**

```
CLOSED (bình thường)
  │
  │  50% fail trong 10 request
  ▼
OPEN (gateway có vấn đề)
  │  → Reject ngay, không gọi nữa (fail fast)
  │  → Return cached response hoặc fallback
  │
  │  30 giây trôi qua
  ▼
HALF-OPEN (thử phục hồi)
  │  → Cho 3 request đi thử
  │
  ├── 3/3 success → CLOSED (hồi phục)
  └── 1/3 fail → OPEN lại (tiếp tục chờ)
```

**Fallback strategy thực tế:**

```java
public PaymentStatus checkPayment(String orderId) {
    return circuitBreaker
        .executeSupplier(() -> vnpayClient.checkPayment(orderId))
        .fallback()  // Resilience4j fallback
            .recoverFrom(Exception.class, e -> {
                log.warn("Payment gateway unavailable for order {}, using fallback", orderId);
                return paymentCache.getIfPresent(orderId)  // Redis cache
                    .orElse(PaymentStatus.UNKNOWN);
            })
        .get();
}
```

### 3.2. Retry — Khi Nào Thử Lại, Khi Nào Dừng Ngay

**Sai lầm phổ biến nhất ở production:** Retry mù quáng tất cả exceptions → retry storm → làm nặng thêm hệ thống đang có vấn đề.

```java
// Retry chỉ với: network timeout, 5xx, 429 (rate limit)
// KHÔNG BAO GIỜ retry: 4xx client errors, validation errors, auth failures
public Retry retrySpec() {
    return Retry.of("vnpay", RetryConfig.<ClientResponse>custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        // Exponential backoff: 500ms → 1s → 2s (tránh thundering herd)
        .intervalFunction(IntervalFunction.ofExponentialBackoff(500, 2))
        .retryConditions(retryContext -> {
            // Chỉ retry network/server errors
            ClientResponse response = retryContext.getLastThrowable()
                .filter(t -> t instanceof WebClientResponseException)
                .map(t -> ((WebClientResponseException) t).getStatusCode().value())
                .map(status -> status >= 500 || status == 429)
                .orElse(false);
            return response;
        })
        .retryOnException(e -> e instanceof TimeoutException
            || e instanceof SocketTimeoutException
            || e instanceof ConnectException)
        // Event listener để track retry attempts
        .eventPublisher(retry -> retry.onRetry(
            event -> metrics.counter("payment.retry",
                Tags.of("provider", "vnpay", "reason",
                    event.getLastThrowable().getClass().getSimpleName())).increment()))
        .build());
}
```

### 3.3. Rate Limiting — Không Bị Vendor Ban

**Thực tế:** Hầu hết third-party APIs có rate limit không documented rõ. VNPay có thể limit theo IP + merchant. GHN theo account tier. SMS gateway theo template type.

```java
@Service
public class RateLimitedClient {

    private final Bucket tokenBucket; // Token bucket per merchant
    private final WebClient webClient;

    public RateLimitedClient(
            @Value("${ghn.api.key}") String apiKey,
            GhnProperties properties) {
        this.tokenBucket = Bucket.builder()
            .addLimit(Bandwidth.classic(
                properties.getRateLimit(),
                Refill.intervally(properties.getRateLimit(),
                    Duration.ofSeconds(1))))
            .build();

        this.webClient = WebClient.builder()
            .baseUrl(properties.getBaseUrl())
            .defaultHeader("Token", apiKey)
            .build();
    }

    public <T> T execute(Request<T> request) {
        if (!tokenBucket.tryConsume(1)) {
            throw new RateLimitExceededException(
                "GHN API rate limit exceeded. Retry after: "
                + tokenBucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() + "ns");
        }
        return executeRequest(request);
    }
}
```

### 3.4. Timeout — Luôn Có Deadline

**Một trong những nguyên nhân phổ biến nhất gây production incident:** Không set timeout → request treo vĩnh viễn → thread pool exhausted.

```java
// Timeout strategy theo operation type
@Configuration
public class TimeoutStrategy {

    @Bean
    public WebClient timeoutWebClient(WebClient.Builder builder) {
        return builder
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(10))
            ))
            .build();
    }
}

// Timeout cấp application
@Bean
public WebClient webClientWithDefaults(WebClient.Builder builder) {
    return builder
        .baseUrl("https://api.example.com")
        .defaultRequest(spec -> spec
            .attribute("timeout",
                Duration.ofSeconds(10)))
        .build();
}
```

**Timeout budget allocation thực tế:**
```
Tổng end-to-end latency budget: 30 giây
  ├── DNS + TCP connection: 5 giây
  ├── TLS handshake: 3 giây
  ├── Server processing (vendor): 10 giây
  ├── Response transmission: 5 giây
  └── Buffer: 7 giây
```

---

## 4. Security — Khi Third-Party Access Hệ Thống Của Bạn

### 4.1. Secrets Management — Không Bao Giờ Hardcode

**Thực tế từ incident:** Một developer đẩy code lên GitHub có API key của SMS gateway → 3 triệu SMS bị gửi trong 1 giờ → mất 200 triệu.

```java
// KHÔNG BAO GIỜ
// private static final String API_KEY = "sk_live_xxxx";

// LUÔN LUÔN
@Component
@ConfigurationProperties(prefix = "thirdparty")
public class ThirdPartyProperties {
    private String vnpayUrl;
    private String vnpayTmnCode;
    private String vnpayHashSecret;  // từ Vault / AWS SM / Azure Key Vault

    @Value("${vnpay.hash-secret}")  // externalized
    private String hashSecret;
}

// Production: fetch from HashiCorp Vault
@Configuration
public class VaultConfig {
    @Bean
    public ThirdPartySecrets thirdPartySecrets(VaultTemplate vaultTemplate) {
        VaultResponse response = vaultTemplate.read("secret/data/payment/vnpay");
        return new ThirdPartySecrets(
            response.getData().get("api_key"),
            response.getData().get("hash_secret")
        );
    }
}
```

**Multi-environment secrets management:**
```yaml
# development.yaml
thirdparty:
  vnpay:
    url: https://sandbox.vnpay.vn
    hash-secret: ${VNPAY_HASH_SECRET:dev-secret-not-for-prod}

# production.yaml
thirdparty:
  vnpay:
    url: https://gen.vnpay.vn
    hash-secret: ${VNPAY_HASH_SECRET}  # Must be provided, no default
```

### 4.2. Webhook Security — Bảo Vệ Endpoint Của Bạn

**Các cuộc tấn công phổ biến vào webhook:**
- Fake webhook: attacker gửi dữ liệu giả mạo đã thanh toán
- Replay attack: gửi lại webhook cũ để trigger duplicate action
- Timing attack: brute force signature

```java
@Component
public class WebhookSecurityValidator {

    private final Map<String, List<WebhookRequest>> processedEvents = new ConcurrentHashMap<>();
    private final Duration replayWindow = Duration.ofMinutes(5);

    public boolean validateVnpayWebhook(Map<String, String> headers,
                                        Map<String, String> payload,
                                        String providedSignature) {
        // 1. Verify signature
        String computedSignature = computeHmacSha256(
            serializePayload(payload),
            vnpaySecret
        );
        if (!secureCompare(computedSignature, providedSignature)) {
            throw new SecurityException("Webhook signature mismatch");
        }

        // 2. Prevent replay attack
        String eventId = payload.get("vnpTransactionId") + "_" + payload.get("vnpPayDate");
        if (processedEvents.containsKey(eventId)) {
            log.warn("Duplicate webhook detected: {}", eventId);
            return false;  // Idempotent: return OK nhưng không xử lý lại
        }

        // 3. Check timestamp (webhook cũ hơn 5 phút có thể là replay)
        Instant eventTime = Instant.ofEpochMilli(Long.parseLong(payload.get("vnpPayDate")));
        if (Duration.between(eventTime, Instant.now()).abs().compareTo(replayWindow) > 0) {
            log.warn("Stale webhook event, ignoring: {}", eventId);
            return false;
        }

        processedEvents.put(eventId, List.of(payload));
        return true;
    }

    // Constant-time comparison để chống timing attack
    private boolean secureCompare(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
```

---

## 5. Observability — Thấy Được Những Gì Đang Xảy Ra

### 5.1. Distributed Tracing — Biết Request Đi Đâu

**Vấn đề thực tế:** Payment request đi qua 5 service → fail ở đâu? Không có tracing = debug 2 ngày.

```java
// Propagate trace context sang third-party calls
@Configuration
public class TracingWebClient {

    @Bean
    public WebClient tracingWebClient(WebClient.Builder builder,
                                       Tracer tracer,
                                       Propagation.Factory propagationFactory) {
        return builder
            .filter((request, next) -> {
                Span span = tracer.nextSpan().name("http:" + request.url())
                    .tag("http.method", "POST")
                    .tag("http.url", request.url().toString())
                    .start();

                try (Tracer.SpanInScope scope = tracer.withSpanInScope(span)) {
                    // Inject trace context vào headers
                    Map<String, String> headers = new HashMap<>();
                    propagationFactory.injector(Request::setHeader)
                        .inject(span.context(), headers);
                    request.headers(httpHeaders -> headers.forEach(httpHeaders::set));

                    return next.exchange(request)
                        .doOnSuccess(resp -> span.tag("http.status_code", resp.statusCode()))
                        .doOnError(e -> span.error(e))
                        .doFinally(signal -> span.end());
                }
            })
            .build();
    }
}
```

### 5.2. Metrics — Biết Trước Khi Khách Kêu

```java
@Component
@RequiredArgsConstructor
public class ThirdPartyMetrics {

    private final MeterRegistry meterRegistry;

    public void recordPaymentLatency(String provider, Duration duration, String status) {
        Timer.builder("payment.integration.latency")
            .tag("provider", provider)
            .tag("status", status)
            .description("Payment API response time")
            .register(meterRegistry)
            .record(duration);
    }

    public void recordFailure(String provider, String errorType) {
        Counter.builder("payment.integration.failures")
            .tag("provider", provider)
            .tag("error_type", errorType)
            .description("Payment integration failures")
            .register(meterRegistry)
            .increment();
    }

    // Dashboard alerts cần setup:
    // - Error rate > 1% trong 5 phút
    // - P99 latency > 10 giây
    // - Circuit breaker OPEN
    // - Rate limit hits tăng đột ngột
}
```

### 5.3. Health Check — Biết Khi Nào Vendor Có Vấn Đề

```java
@Component
public class ThirdPartyHealthIndicator implements ReactiveHealthIndicator {

    private final Map<String, HealthCheckableService> services;

    @Override
    public Mono<Health> health() {
        return Flux.fromIterable(services.entrySet())
            .flatMap(entry -> checkService(entry.getKey(), entry.getValue()))
            .collectList()
            .map(results -> {
                boolean allHealthy = results.stream()
                    .allMatch(r -> r.getStatus().equals(Status.UP));

                Map<String, Detail> details = results.stream()
                    .collect(Collectors.toMap(HealthCheckResult::getName,
                        HealthCheckResult::getDetail));

                return allHealthy
                    ? Health.up().withDetails(details).build()
                    : Health.down().withDetails(details).build();
            });
    }

    private Mono<HealthCheckResult> checkService(String name,
                                                   HealthCheckableService service) {
        long start = System.currentTimeMillis();
        return service.ping()
            .map(healthy -> HealthCheckResult.healthy(name,
                Map.of("latency_ms", System.currentTimeMillis() - start)))
            .onErrorResume(e -> Mono.just(HealthCheckResult.unhealthy(name,
                Map.of("error", e.getMessage(),
                    "latency_ms", System.currentTimeMillis() - start))));
    }
}
```

---

## 6. SDK Integration — Khi Vendor Cung Cấp SDK

### 6.1. Đánh Giá SDK Trước Khi Dùng

**Checklist đánh giá SDK (thực tế từ các project đã làm):**

```
□ License — có commercial license không? có hidden fee không?
□ Maintenance — commit gần nhất bao giờ? issue có được respond?
□ Async support — có hỗ trợ reactive không? hay chỉ blocking?
□ Thread-safety — singleton hay instance per request?
□ Serialization — dùng Jackson/Gson gì? conflict với project không?
□ Dependency tree — có pull vào version conflict không?
□ Logging — có log sensitive data không? (API key, token)
□ Connection pooling — có quản lý connection không hay mỗi call 1 connection mới?
```

### 6.2. Wrapper Pattern —封装 SDK Để Kiểm Soát

```java
// KHÔNG BAO GIỜ dùng SDK trực tiếp trong business logic
@Service
public class GhnShippingService {

    // ❌ Bad: tight coupling với SDK
    private final GhnClient ghnClient;

    // ✅ Good: wrap trong adapter
    private final ShippingGateway gateway;
}

// GhnSdkAdapter wraps GHN SDK
@Component
@ConditionalOnProperty("shipping.provider", value = "ghn")
public class GhnSdkAdapter implements ShippingGateway {

    private final GHN ghnSdk;  // official SDK

    public GhnSdkAdapter(GhnProperties properties) {
        this.ghnSdk = GHN.builder()
            .token(properties.getApiToken())
            .production(properties.isProduction())
            .build();
    }

    @Override
    public ShippingRate calculateRate(ShipmentRequest request) {
        // Map internal model → GHN SDK model
        ghnSdk.setConnectTimeout(5000);
        ghnSdk.setReadTimeout(10000);
        // ... call SDK
    }
}
```

---

## 7. API Versioning — Quản Lý Breaking Changes

### 7.1. Versioning Strategy

**Ba chiến lược phổ biến, ưu/nhược điểm thực tế:**

| Strategy | URL Example | Ưu điểm | Nhược điểm |
|----------|------------|---------|-----------|
| URL Path | `/v1/orders` | Dễ route, dễ cache | Strict, phải maintain nhiều route |
| Header | `Accept: application/vnd.api.v1+json` | URL sạch | Khó debug, cache phức tạp |
| Query Param | `/orders?version=1` | Đơn giản | Khó track, easy to forget |

**Khuyến nghị:** URL Path versioning cho public API. Đây là thực tế hầu hết enterprise systems dùng (Stripe, Twilio, VNPay đều dùng cách này).

### 7.2. Deprecation Policy

```java
@Configuration
public class ApiDeprecationPolicy {

    // Thông báo deprecation khi client dùng version cũ
    @Component
    public class DeprecationHeaderFilter implements HandlerFilterFunction<ServerResponse> {

        @Override
        public Mono<ServerResponse> filter(ServerRequest request,
                                            HandlerFunction<ServerResponse> next) {
            String version = extractVersion(request);

            if (version.equals("v1")) {
                return ServerResponse.ok()
                    .header("Deprecation", "true")
                    .header("Sunset", "Sat, 31 Dec 2025 23:59:59 GMT")
                    .header("Link", "</v2/orders>; rel=\"successor-version\"")
                    .header("X-API-Deprecation-Reason",
                        "v1 will be sunset on 2025-12-31. Migrate to v2.")
                    .build();
            }

            return next.handle(request);
        }
    }
}
```

---

## 8. Testing — Không Test Là Để Prod Thành Test Env

### 8.1. Contract Testing — Đảm Bảo Integration Không Break

**Problem thực tế:** Vendor update API ở phía họ → không ai biết → production break silent.

**Giải pháp:** Contract testing với Pact hoặc Spring Cloud Contract.

```java
@ExtendWith(PactConsumerTestExt.class)
class PaymentContractTest {

    @Pact(consumer = "order-service", provider = "vnpay")
    V4V3Interaction paymentSuccessful PactFragment.createFragment(
        builder -> builder
            .given("payment is successful")
            .uponReceiving("a payment verification request")
                .path("/v2/payment/verify")
                .method("POST")
                .body(newTypeMatcher(PaymentVerifyRequest.class,
                    m -> m.getTransactionId() != null))
            .willRespondWith()
                .status(200)
                .body(newTypeMatcher(PaymentVerifyResponse.class, m -> {
                    m.setCode("00");
                    m.setMessage("Success");
                    m.setTransactionId("12345");
                }))
    )

    @Test
    void runTest(PactVerificationResult result) {
        assertThat(result).isEqualTo(PactVerificationResult.Ok.INSTANCE);
    }
}
```

### 8.2. Consumer-Driven Contract Testing Flow

```
┌──────────────────┐         ┌──────────────────┐
│  Consumer Team   │ ──pact──▶│  Broker (Pact)   │
│  (Order Service) │         │                  │
└──────────────────┘         └────────┬─────────┘
                                       │ pact
                                       ▼
┌──────────────────┐         ┌──────────────────┐
│  Provider Team   │◀────────│  CI/CD Pipeline  │
│  (Payment Team)  │  verify │                  │
└──────────────────┘         └──────────────────┘
```

---

## 9. Failure Scenarios & Anti-Patterns

### Anti-Patterns thường gặp (và cách tránh):

**1. Trusting third-party responses without validation**
```java
// ❌ Bad
public Order updateFromGhn(Map<String, Object> webhookPayload) {
    String orderId = (String) webhookPayload.get("order_id");
    // KHÔNG verify signature → attacker gửi webhook giả
    return orderService.updateStatus(orderId, "DELIVERED");
}

// ✅ Good
public Order updateFromGhn(Map<String, Object> webhookPayload, String signature) {
    if (!webhookValidator.validate(webhookPayload, signature)) {
        throw new SecurityException("Invalid webhook signature");
    }
    String orderId = (String) webhookPayload.get("order_id");
    return orderService.updateStatus(orderId, "DELIVERED");
}
```

**2. Synchronous call to slow third-party in request path**
```java
// ❌ Bad: user chờ 10 giây cho logistics API
@GetMapping("/orders/{id}")
public OrderResponse getOrder(@PathVariable String id) {
    Order order = orderRepo.findById(id);
    LogisticsInfo logistics = logisticsClient.getTracking(order.getTrackingId());
    // User đang chờ logistics API 10 giây
    return OrderResponse.builder()
        .order(order)
        .logistics(logistics)
        .build();
}

// ✅ Good: load logistics async, user nhận order ngay
@GetMapping("/orders/{id}")
public Mono<OrderResponse> getOrder(@PathVariable String id) {
    return Mono.fromCallable(() -> orderRepo.findById(id))
        .flatMap(order -> logisticsClient.getTracking(order.getTrackingId())
            .map(logistics -> OrderResponse.builder()
                .order(order)
                .logistics(logistics)
                .build())
            .defaultIfEmpty(OrderResponse.builder()
                .order(order)
                .logistics(null)  // graceful degradation
                .build()));
}
```

**3. No idempotency on webhook/retry handling**
```java
// ❌ Bad: mỗi retry gửi email 1 lần → customer nhận 5 email
public void handlePaymentWebhook(PaymentWebhook webhook) {
    emailService.sendReceipt(webhook.getOrderId());
    orderService.markPaid(webhook.getOrderId());
    // Nếu webhook được retry 5 lần → 5 email
}

// ✅ Good: idempotent
public void handlePaymentWebhook(PaymentWebhook webhook) {
    String idempotencyKey = webhook.getEventId();
    if (idempotentStore.hasProcessed(idempotencyKey)) {
        log.info("Webhook {} already processed, skipping", idempotencyKey);
        return;
    }

    emailService.sendReceipt(webhook.getOrderId());
    orderService.markPaid(webhook.getOrderId());
    idempotentStore.markProcessed(idempotencyKey, Duration.ofDays(30));
}
```

---

# PHẦN 2: CHIA SẺ API CHO BÊN THỨ 3

## 10. API as a Product — Nghĩ Như Product Manager

**Tư duy quan trọng nhất:** Khi share API cho partner, API đó là **product** của bạn. Partner là **customer**. Nếu API khó dùng, partner sẽ đi tìm vendor khác hoặc bypass qua cách khác.

### Checklist khi design public/integration API:

```
□ Đã có API design review chưa?
□ Đã có consumer (partner) feedback chưa?
□ Contract có backward compatible không?
□ Error codes có đủ descriptive không?
□ Latency SLA đã define chưa?
□ Rate limit documented chưa?
□ Sandbox có sẵn không?
□ SDK có support không?
□ Documentation có đầy đủ không?
□ Onboarding flow partner đã clear chưa?
```

---

## 11. Authentication & Authorization

### 11.1. OAuth2 vs API Key vs JWT — Chọn Cái Nào?

| Mechanism | Use Case | Ưu điểm | Nhược điểm |
|-----------|----------|---------|-----------|
| **API Key** | Simple service-to-service | Dễ implement, low overhead | Không có expiration, khó revoke per-key |
| **OAuth2 (Client Credentials)** | Server-to-server, multi-tenant | Token expiration, scopes, standard | Complex hơn, cần token endpoint |
| **JWT (signed)** | Token cần verify offline | Stateless, có claims | Không revoke được dễ dàng |
| **mTLS** | High-security, financial | Không thể fake identity | Cert management phức tạp |

**Khuyến nghị theo use case:**

```
Internal microservices → mTLS hoặc JWT
Partner integration (simple) → API Key
Partner integration (complex, multi-tenant) → OAuth2 Client Credentials
Mobile apps → OAuth2 Authorization Code + PKCE
```

### 11.2. OAuth2 Client Credentials Implementation

```java
@Configuration
public class PartnerOAuth2Config {

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
            .tokenEndpoint("/oauth2/token")
            .tokenIntrospectionEndpoint("/oauth2/introspect")
            .build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        // Store trong database, không hardcode
        return new JdbcRegisteredClientRepository(
            jdbcTemplate,
            new Swagger2RegisteredClientRowMapper()
        );
    }
}

// Partner registration endpoint
@RestController
@RequiredArgsConstructor
public class PartnerOnboardingController {

    private final RegisteredClientRepository clientRepository;
    private final PartnerService partnerService;

    @PostMapping("/api/v1/partners/register")
    public ResponseEntity<PartnerRegistrationResponse> registerPartner(
            @Valid @RequestBody PartnerRegistrationRequest request,
            @RequestHeader("X-Admin-Key") String adminKey) {

        // Validate admin key (internal only)
        if (!adminKeyValidator.isValid(adminKey)) {
            return ResponseEntity.status(401).build();
        }

        // Generate client credentials
        String clientId = UUID.randomUUID().toString();
        String clientSecret = secureRandomGenerator.generate(32);
        String hashedSecret = passwordEncoder.encode(clientSecret);

        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientSecret(hashedSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("read:orders")
            .scope("write:orders")
            .scope("read:shipments")
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofHours(1))
                .reuseRefreshTokens(false)
                .build())
            .build();

        clientRepository.save(client);
        partnerService.createPartner(clientId, request);

        // Return secret ONE TIME only — never stored plaintext
        return ResponseEntity.ok(PartnerRegistrationResponse.builder()
            .clientId(clientId)
            .clientSecret(clientSecret)  // Show 1 lần duy nhất
            .scopes(request.getRequestedScopes())
            .sandboxEndpoint("https://sandbox-api.example.com")
            .productionEndpoint("https://api.example.com")
            .documentationUrl("https://docs.example.com")
            .build());
    }
}
```

### 11.3. API Key Management

```java
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final MetricsFacade metrics;

    public ApiKey createApiKey(String partnerId, Set<ApiScope> scopes) {
        String keyId = "ak_" + generateSecureId(8);
        String rawKey = generateSecureKey(32);
        String hashedKey = hashKey(rawKey);

        ApiKey apiKey = ApiKey.builder()
            .id(keyId)
            .hashedKey(hashedKey)
            .partnerId(partnerId)
            .scopes(scopes)
            .rateLimitTier(determineRateLimitTier(partnerId))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofDays(365)))
            .status(ACTIVE)
            .build();

        apiKeyRepository.save(apiKey);

        // Audit log
        auditService.log(ApiKeyCreatedEvent.builder()
            .keyId(keyId)
            .partnerId(partnerId)
            .scopes(scopes)
            .createdBy(getCurrentUser())
            .build());

        return ApiKey.builder()
            .id(keyId)
            .key(rawKey)  // Return raw key ONCE
            .partnerId(partnerId)
            .scopes(scopes)
            .build();
    }

    public boolean validateApiKey(String rawKey) {
        String hashedKey = hashKey(rawKey);
        ApiKey apiKey = apiKeyRepository.findByHashedKey(hashedKey)
            .orElse(null);

        if (apiKey == null) return false;
        if (apiKey.getStatus() != ACTIVE) return false;
        if (apiKey.getExpiresAt().isBefore(Instant.now())) return false;

        return true;
    }

    // Revoke key immediately (security incident response)
    public void revokeApiKey(String keyId, String reason) {
        apiKeyRepository.findById(keyId).ifPresent(key -> {
            key.setStatus(REVOKED);
            key.setRevokedAt(Instant.now());
            key.setRevocationReason(reason);
            apiKeyRepository.save(key);

            // Invalidate in cache immediately
            cacheManager.evict("apikey:" + keyId);

            auditService.log(ApiKeyRevokedEvent.builder()
                .keyId(keyId)
                .partnerId(key.getPartnerId())
                .reason(reason)
                .revokedBy(getCurrentUser())
                .build());
        });
    }
}
```

---

## 12. Multi-Tenant Isolation — Partner Không Được Thấy Data Của Nhau

### 12.1. Data Isolation Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    API Gateway Layer                         │
│  - Authenticate request                                      │
│  - Extract tenant_id from API key / JWT                     │
│  - Inject tenant context into request headers               │
└──────────────────────┬───────────────────────────────────────┘
                       │ X-Tenant-ID: partner_123
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                    Business Logic Layer                      │
│  - All queries MUST include tenant_id filter                │
│  - JPA: @Where("tenantId = :tenantId")                      │
│  - SQL: WHERE tenant_id = ?  (never trust tenant_id from     │
│    request body, always use extracted from auth context)     │
└──────────────────────────────────────────────────────────────┘
```

```java
// Tenant context holder
@Component
public class TenantContext {

    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
    private static final ThreadLocal<String> currentApiKey = new ThreadLocal<>();

    public void setTenantId(String tenantId) {
        currentTenant.set(tenantId);
    }

    public String getTenantId() {
        return currentTenant.get();
    }

    public void clear() {
        currentTenant.remove();
        currentApiKey.remove();
    }
}

// Gateway filter: extract tenant từ API key, inject vào context
@Component
public class TenantExtractionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws Exception {
        String apiKey = extractApiKey(request);

        if (apiKey != null) {
            ApiKeyMetadata metadata = apiKeyService.getMetadata(apiKey);
            tenantContext.setTenantId(metadata.getPartnerId());
            tenantContext.setApiKey(metadata.getKeyId());

            // Log for audit
            MDC.put("partner_id", metadata.getPartnerId());
            MDC.put("api_key_id", metadata.getKeyId());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            tenantContext.clear();
            MDC.clear();
        }
    }
}

// Repository: LUÔN filter theo tenant_id
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    // ❌ NEVER: có thể leak data nếu caller quên set tenant
    // List<Order> findByStatus(OrderStatus status);

    // ✅ ALWAYS: tenant_id là mandatory
    List<Order> findByTenantIdAndStatus(String tenantId, OrderStatus status);

    // Scoped query
    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId AND o.id = :orderId")
    Optional<Order> findByIdAndTenantId(@Param("tenantId") String tenantId,
                                         @Param("orderId") String orderId);
}

// JPA automatic filter
@Configuration
public class TenantJpaConfig {

    @Bean
    public JpaDiagnostics jpaDiagnostics(TenantContext tenantContext) {
        return new JpaDiagnostics() {
            @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
            public Object enforceTenantFilter(ProceedingJoinPoint pjp) throws Throwable {
                // Automatically inject tenant_id vào save operations
                if (tenantContext.getTenantId() == null) {
                    throw new SecurityException("No tenant context for repository operation");
                }
                Object[] args = pjp.getArgs();
                // Validate tenant_id consistency
                return pjp.proceed(args);
            }
        };
    }
}
```

---

## 13. Rate Limiting & Throttling

### 13.1. Tiered Rate Limiting Strategy

```yaml
# Rate limit tiers theo partner subscription
rate_limits:
  free:
    requests_per_second: 10
    requests_per_day: 1000
    burst: 20

  standard:
    requests_per_second: 100
    requests_per_day: 50000
    burst: 200

  enterprise:
    requests_per_second: 1000
    requests_per_day: 1000000
    burst: 2000
```

```java
@Service
public class RateLimitingService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitResult checkRateLimit(String apiKeyId, String tenantId,
                                           RateLimitTier tier,
                                           EndpointType endpoint) {
        String bucketKey = apiKeyId + ":" + endpoint;
        Bucket bucket = buckets.computeIfAbsent(bucketKey,
            k -> createBucket(tier, endpoint));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return RateLimitResult.builder()
                .allowed(true)
                .remaining(probe.getRemainingTokens())
                .resetAt(Instant.now().plusSeconds(probe.getSecondsToWaitForRefill()))
                .build();
        }

        // Return Retry-After header
        return RateLimitResult.builder()
            .allowed(false)
            .remaining(0)
            .retryAfter(Duration.ofSeconds(probe.getSecondsToWaitForRefill()))
            .build();
    }

    private Bucket createBucket(RateLimitTier tier, EndpointType endpoint) {
        long capacity = tier.getRequestsPerSecond();
        if (endpoint == EndpointType.WEBHOOK) {
            capacity = capacity * 2;  // Webhook calls get higher allowance
        }

        return Bucket.builder()
            .addLimit(Bandwidth.classic(capacity,
                Refill.intervally(capacity, Duration.ofSeconds(1))))
            .addLimit(Bandwidth.classic(
                tier.getRequestsPerDay(),
                Refill.intervally(tier.getRequestsPerDay(), Duration.ofDays(1))))
            .build();
    }
}

// API response khi bị limit
@RestControllerAdvice
public class RateLimitExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex,
                                                          HttpServletRequest request,
                                                          @RequestHeader("X-Request-Id")
                                                          String requestId) {
        log.warn("Rate limit exceeded for request {} by tenant {}",
            requestId, tenantContext.getTenantId());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("X-RateLimit-Limit", String.valueOf(ex.getLimit()))
            .header("X-RateLimit-Remaining", "0")
            .header("X-RateLimit-Reset", String.valueOf(ex.getResetAt().toEpochMilli()))
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(ErrorResponse.builder()
                .code("RATE_LIMIT_EXCEEDED")
                .message("API rate limit exceeded. Retry after "
                    + ex.getRetryAfterSeconds() + " seconds.")
                .requestId(requestId)
                .build());
    }
}
```

---

## 14. Webhook (Outbound) — Khi Hệ Thống Của Bạn Push Data Sang Partner

### 14.1. Reliable Webhook Delivery

**Vấn đề thực tế:** Webhook gửi không đến → partner miss event → data không sync → complaint.

```java
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final WebClient webClient;
    private final OutboxRepository outboxRepository;
    private final ScheduledExecutorService scheduler;

    public void emitEvent(PartnerWebhook webhook) {
        // 1. Write to outbox FIRST (transactional outbox pattern)
        OutboxEntry entry = OutboxEntry.builder()
            .id(UUID.randomUUID().toString())
            .partnerId(webhook.getPartnerId())
            .eventType(webhook.getEventType())
            .payload(webhook.getPayload())
            .status(PENDING)
            .createdAt(Instant.now())
            .nextRetryAt(Instant.now())
            .retryCount(0)
            .build();

        outboxRepository.save(entry);  // Same transaction as business logic

        // 2. Async process from outbox
        scheduleWebhookDelivery(entry.getId());
    }

    private void scheduleWebhookDelivery(String entryId) {
        scheduler.execute(() -> {
            OutboxEntry entry = outboxRepository.findById(entryId).orElse(null);
            if (entry == null) return;

            WebhookDeliveryResult result = deliverToPartner(entry);

            if (result.isSuccess()) {
                entry.setStatus(DELIVERED);
                entry.setDeliveredAt(Instant.now());
            } else if (entry.getRetryCount() < MAX_RETRIES) {
                entry.setRetryCount(entry.getRetryCount() + 1);
                entry.setNextRetryAt(calculateNextRetry(entry.getRetryCount()));
                entry.setStatus(RETRY_SCHEDULED);
                scheduleWebhookDelivery(entryId);  // Retry
            } else {
                entry.setStatus(FAILED);
                alertService.alert(AlertType.WEBHOOK_DELIVERY_FAILED, entry);
            }

            outboxRepository.save(entry);
        });
    }

    private WebhookDeliveryResult deliverToPartner(OutboxEntry entry) {
        PartnerConfig config = partnerConfigService.get(entry.getPartnerId());

        String signature = computeSignature(entry.getPayload(), config.getWebhookSecret());

        try {
            HttpResponse response = webClient.post()
                .uri(config.getWebhookUrl())
                .header("Content-Type", "application/json")
                .header("X-Webhook-Signature", signature)
                .header("X-Webhook-Event", entry.getEventType())
                .header("X-Webhook-Delivery-Id", entry.getId())
                .header("X-Webhook-Timestamp", String.valueOf(
                    entry.getCreatedAt().toEpochMilli()))
                .bodyValue(entry.getPayload())
                .timeout(Duration.ofSeconds(30))
                .retrieve()
                .toBodilessEntity()
                .block();

            return WebhookDeliveryResult.success(response.getStatusCode().value());
        } catch (Exception e) {
            return WebhookDeliveryResult.failure(e.getMessage());
        }
    }

    // Exponential backoff: 1m → 5m → 30m → 2h → 8h (max 24h)
    private Instant calculateNextRetry(int retryCount) {
        Duration delay = Duration.ofMinutes(
            (long) Math.min(480, Math.pow(3, retryCount)));
        return Instant.now().plus(delay);
    }
}
```

---

## 15. API Gateway — Điểm Đầu Tiên Của Mọi Request

### 15.1. Gateway Responsibilities

```
Incoming Request
       │
       ▼
┌──────────────────────┐
│  TLS Termination     │ ← Offload SSL, protect backend
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Rate Limiting       │ ← Per-tenant, per-endpoint
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Authentication      │ ← API Key / JWT / OAuth2 validation
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Authorization       │ ← Scope-based access control
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Tenant Extraction   │ ← Inject tenant context
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Request Validation  │ ← Schema validation
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  Backend Routing     │ ← Path → service mapping
└──────────┬───────────┘
           ▼
     Backend Service
```

### 15.2. Gateway Configuration (Spring Cloud Gateway)

```java
@Configuration
public class ApiGatewayRoutes {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder,
                                            GatewayFilterFactories factories) {
        return builder.routes()
            .route("partner-api", r -> r
                .path("/api/v1/**")
                .filters(f -> f
                    // Rate limiting
                    .filter(rateLimitFilter(100, Duration.ofSeconds(1)))
                    // Request ID generation
                    .addRequestHeader("X-Request-Id", UUID.randomUUID().toString())
                    // Response caching (optional endpoints only)
                    .cacheResponseBody(new PathMatcher("/api/v1/public/**"))
                    // Circuit breaker
                    .hystrix(config -> config
                        .setName("partner-api")
                        .setFallbackUri("forward:/fallback/partner-api"))
                    // Transformations
                    .stripPrefix(1))  // Remove /api/v1 prefix
                .uri("lb://partner-service"))
            .build();
    }

    @Bean
    public GlobalFilter authenticationFilter(ApiKeyService apiKeyService,
                                              TenantContext tenantContext) {
        return (exchange, chain) -> {
            String apiKey = exchange.getRequest().getHeaders()
                .getFirst("X-API-Key");

            if (apiKey == null) {
                apiKey = exchange.getRequest().getQueryParams()
                    .getFirst("api_key");
            }

            if (apiKey == null) {
                return unauthorized(exchange, "Missing API key");
            }

            ApiKeyMetadata metadata = apiKeyService.validateAndGetMetadata(apiKey);
            if (metadata == null) {
                return unauthorized(exchange, "Invalid API key");
            }

            // Inject tenant context
            exchange.getRequest().mutate()
                .header("X-Tenant-ID", metadata.getPartnerId())
                .header("X-API-Key-ID", metadata.getKeyId())
                .build();

            tenantContext.setTenantId(metadata.getPartnerId());

            return chain.filter(exchange);
        };
    }
}
```

---

## 16. Documentation & Developer Experience

### 16.1. OpenAPI/Swagger Setup

```yaml
# openapi.yaml structure
openapi: 3.0.3
info:
  title: Partner Integration API
  version: "2.1"
  description: |
    API for partner integrations. Version 2.x introduces breaking changes
    to the order lifecycle endpoints. See migration guide.
  contact:
    name: Partner Support
    email: partner-support@example.com
  x-sandbox-url: https://sandbox-api.example.com
  x-production-url: https://api.example.com
  x-deprecation-date: "2025-12-31"

servers:
  - url: https://sandbox-api.example.com
    description: Sandbox environment (rate limits relaxed)
    variables:
      version:
        default: v2
  - url: https://api.example.com
    description: Production environment
    x-sla: "99.9%"
    x-rate-limit-tier: standard

components:
  securitySchemes:
    ApiKeyAuth:
      type: apiKey
      in: header
      name: X-API-Key
      description: |
        API key provided during partner onboarding. Each key is scoped
        to specific endpoints. Store securely — we cannot retrieve it again.

    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: OAuth2 access token (Client Credentials flow)

  responses:
    RateLimited:
      description: Rate limit exceeded
      headers:
        X-RateLimit-Limit:
          schema:
            type: integer
        X-RateLimit-Remaining:
          schema:
            type: integer
        Retry-After:
          schema:
            type: integer
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'

    Unauthorized:
      description: Missing or invalid authentication

  schemas:
    Error:
      type: object
      properties:
        code:
          type: string
          example: VALIDATION_ERROR
          description: Machine-readable error code
        message:
          type: string
          example: "order_id must not be blank"
          description: Human-readable description
        request_id:
          type: string
          description: For support escalation
        details:
          type: array
          items:
            $ref: '#/components/schemas/FieldError'

    Order:
      type: object
      properties:
        id:
          type: string
          readOnly: true
        status:
          type: string
          enum: [PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED]
        total_amount:
          type: number
          format: decimal
        currency:
          type: string
          default: VND
        created_at:
          type: string
          format: date-time
          readOnly: true
```

---

## 17. Onboarding Flow — Từ Zero Đến First Successful Call

### 17.1. Partner Onboarding Journey

```
┌─────────────────────────────────────────────────────────────┐
│                    PARTNER ONBOARDING FLOW                  │
│                                                             │
│  1. Partner Registration                                    │
│     POST /api/v1/partners/register                          │
│     ├── Company info, use case, expected volume            │
│     └── Submit → pending approval                          │
│                                                             │
│  2. Approval (internal review, 1-2 business days)         │
│     ├── Security review                                     │
│     ├── Business validation                                 │
│     └── Activate sandbox access                            │
│                                                             │
│  3. Sandbox Access                                          │
│     ├── Receive sandbox API key                            │
│     ├── Full API access (isolated sandbox data)            │
│     ├── No real money/orders                               │
│     └── Integration testing                                │
│                                                             │
│  4. UAT Certification (optional for enterprise)           │
│     ├── Partner runs test scenarios                        │
│     ├── Our team reviews integration                       │
│     └── Fix issues, re-test                                │
│                                                             │
│  5. Production Access                                       │
│     ├── Sign SLA agreement                                 │
│     ├── Receive production API key                         │
│     ├── Rate limit tier assigned                           │
│     └── Go live!                                           │
└─────────────────────────────────────────────────────────────┘
```

### 17.2. Self-Service Portal Features

```
Partner Portal cần có:
□ Dashboard: API usage, quota, errors
□ API Key Management: create, rotate, revoke
□ Webhook Configuration: URL, secret, event types
□ Sandbox Playground: interactive API tester
□ Logs & Debugging: view recent API calls, responses
□ Support Tickets: technical questions
□ Billing (nếu có): usage-based pricing
□ Documentation: search, changelog, migration guides
```

---

## 18. Monitoring, Abuse Detection & SLA

### 18.1. SLA/SLO Definition

```yaml
# SLA commitments (public-facing)
sla:
  availability:
    standard_tier: 99.5%
    enterprise_tier: 99.9%
    measurement_period: monthly

  latency:
    p50: 200ms
    p95: 500ms
    p99: 2000ms
    measured_at: gateway (before backend)

  rate_limits:
    free: 10 req/s
    standard: 100 req/s
    enterprise: 1000 req/s

  support:
    standard: email, 2 business days
    enterprise: dedicated Slack, 4 hours

  maintenance_windows:
    frequency: monthly
    notice: 72 hours
    duration: max 4 hours
    timezone: Asia/Ho_Chi_Minh
```

### 18.2. Abuse Detection

```java
@Service
@RequiredArgsConstructor
public class AbuseDetectionService {

    private final MetricsFacade metrics;
    private final AlertService alertService;

    // Phát hiện anomalous usage patterns
    public void analyzeRequestPattern(String tenantId,
                                      RequestMetadata metadata,
                                      ResponseMetadata response) {
        // Pattern 1: Spike in error rate
        double errorRate = metrics.getErrorRate(tenantId);
        if (errorRate > 0.5) {  // 50% errors
            alertService.send(AlertType.HIGH_ERROR_RATE, tenantId, errorRate);
        }

        // Pattern 2: Unusual endpoint combination
        Set<String> calledEndpoints = metrics.getCalledEndpoints(tenantId);
        if (calledEndpoints.contains("/admin/**") || calledEndpoints.contains("/internal/**")) {
            alertService.send(AlertType.PRIVILEGED_ENDPOINT_ACCESS, tenantId);
        }

        // Pattern 3: Data exfiltration attempt
        if (metadata.getResponseSize() > 10 * 1024 * 1024) {  // >10MB response
            alertService.send(AlertType.LARGE_RESPONSE, tenantId, metadata.getResponseSize());
        }

        // Pattern 4: Brute force / credential stuffing
        long failedAuths = metrics.getFailedAuthCount(tenantId);
        if (failedAuths > 100) {
            alertService.send(AlertType.AUTH_ABUSE, tenantId);
        }
    }
}
```

---

## 19. API Lifecycle Management

### 19.1. Lifecycle Phases

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  ALPHA  │───▶│  BETA   │───▶│GENERAL  │───▶│DEPRECATED│───▶│RETIRED  │
│         │    │         │    │ AVAIL   │    │         │    │         │
└─────────┘    └─────────┘    └─────────┘    └─────────┘    └─────────┘
 Internal    Partners can   GA, stable,    Sunset date      Removed
 testing     opt-in beta    full SLA       announced
```

### 19.2. Breaking vs Non-Breaking Changes

| Breaking Changes (cần version bump) | Non-Breaking Changes |
|-----------------------------------|---------------------|
| Xóa field | Thêm field mới (optional) |
| Đổi data type | Thêm endpoint mới |
| Đổi required/optional | Thêm enum value mới |
| Đổi validation rules | Thêm response field |
| Đổi URL path | Thêm optional header |
| Đổi HTTP method | Thêm query param mới |

---

# PHẦN 3: TỔNG HỢP — PRODUCTION LESSONS LEARNED

## Những Sai Lầm Thường Gặp Nhất (Và Cách Tránh)

### 1. Không có Outbox Pattern cho webhook
```java
// ❌ Bad: webhook gửi ngay trong transaction
@Transactional
public void processOrder(Order order) {
    order.setStatus(SHIPPED);
    orderRepo.save(order);
    webhookService.send("order.shipped", order);  // Nếu gửi fail ở đây?
}
```
```java
// ✅ Good: transactional outbox
@Transactional
public void processOrder(Order order) {
    order.setStatus(SHIPPED);
    orderRepo.save(order);
    outboxRepo.save(OutboxEntry.pending("order.shipped", order.getId()));  // Cùng transaction
}
```

### 2. Không test với real latency
```java
// ❌ Bad: test local, không simulate network delay
@Test
void testPaymentFlow() {
    when(vnpayClient.verify(any())).thenReturn(successResponse);
    // Chạy OK → deploy lên production → timeout vì network 100ms
}
```
```java
// ✅ Good: simulate real conditions
@Test
void testPaymentFlow() {
    when(vnpayClient.verify(any()))
        .thenAnswer(inv -> {
            Thread.sleep(500);  // Simulate real latency
            return successResponse;
        });
}
```

### 3. Không có graceful degradation
```java
// ❌ Bad: logistics API fail → entire order response fail
public OrderResponse getOrder(String id) {
    Order order = orderRepo.findById(id);
    LogisticsInfo logistics = logisticsClient.getTracking(order.getTrackingNo());
    // Logistics down → customer nhìn thấy 500 error
}
```
```java
// ✅ Good: có fallback
public OrderResponse getOrder(String id) {
    Order order = orderRepo.findById(id);
    LogisticsInfo logistics = logisticsClient.getTracking(order.getTrackingNo())
        .orElse(LogisticsInfo.unknown());  // Graceful degradation
    return OrderResponse.of(order, logistics);
}
```

### 4. Không có API key rotation
```java
// ❌ Bad: API key không bao giờ rotate → nếu leak thì không revoke được
```
```java
// ✅ Good: mandatory rotation
@Service
public class ApiKeyRotationScheduler {

    @Scheduled(cron = "0 0 3 1 * *")  // 3 AM ngày 1 mỗi tháng
    public void notifyExpiringKeys() {
        List<ApiKey> expiringKeys = apiKeyRepo
            .findExpiringWithin(Duration.ofDays(30));

        for (ApiKey key : expiringKeys) {
            partnerService.sendRotationReminder(
                key.getPartnerId(),
                key.getExpiresAt()
            );
        }
    }
}
```

### 5. Logging không đủ context
```java
// ❌ Bad
log.info("Payment failed");
```
```java
// ✅ Good
log.warn("Payment verification failed for tenant={}, orderId={}, error={}, "
         + "provider={}, duration_ms={}, attempt={}",
    tenantId, orderId, error.getMessage(), provider, durationMs, attempt,
    exception);
```

---

## Decision Matrix — Khi Nào Dùng Gì

### Integration Pattern
```
Need immediate response?
├── Yes
│   ├── Latency sensitive?
│   │   ├── Yes → Async (return 202, process in background)
│   │   └── No → Sync (REST/gRPC, circuit breaker mandatory)
│   └── High volume?
│       ├── Yes → Async (message queue, batching)
│       └── No → Sync
└── No
    ├── Scheduled job → Batch processing + async
    └── Event-driven → Message queue + webhook
```

### Authentication
```
Who is calling?
├── Internal service → mTLS
├── Partner (simple) → API Key
├── Partner (complex, multi-tenant) → OAuth2 Client Credentials
├── User → OAuth2 Authorization Code + PKCE
└── Third-party system (machine) → mTLS or API Key
```

### Rate Limiting
```
Volume?
├── <100 req/s → Token bucket (in-memory OK)
├── 100-1000 req/s → Redis-based rate limiter
└── >1000 req/s → Distributed rate limiter + CDN edge
```

---

## Tooling Recommendations

| Concern | Recommended Tools |
|---------|-------------------|
| API Gateway | Kong, AWS API Gateway, Apigee, Spring Cloud Gateway |
| Service Mesh | Istio, Linkerd, Consul Connect |
| Circuit Breaker | Resilience4j, Hystrix (deprecated) |
| Rate Limiting | Bucket4j, Redis + Lua scripts |
| Message Queue | Kafka, RabbitMQ, AWS SQS |
| Secrets | HashiCorp Vault, AWS Secrets Manager, Azure Key Vault |
| Observability | OpenTelemetry + Jaeger/Prometheus/Grafana |
| Contract Testing | Pact, Spring Cloud Contract |
| API Documentation | OpenAPI 3.0 + Redoc/Scalar |
| SDK Generation | OpenAPI Generator, Fern |

---

## 20. HMAC Key Security cho API Sharing

### 20.1. Tại Sao Cần HMAC Key Riêng cho Partner Integration

**Phân biệt rõ ba loại credentials:**

| Credential | Mục đích | Ai dùng |
|-----------|---------|---------|
| API Key | Identify partner, authenticate request | Mọi request từ partner |
| HMAC Secret | Sign request body hoặc webhook payload | Sensitive operations |
| OAuth Token | Short-lived access token (nếu dùng OAuth2) | User-delegated access |

**Sai lầm phổ biến nhất:** Dùng API Key secret làm HMAC signing key. Nếu HMAC bị leak trong log hoặc transmission, attacker có full control.

```
Partner có 2 credentials riêng biệt:
  API_KEY    → Xác định partner, authenticate (gửi qua header X-API-Key)
  HMAC_SECRET → Sign payload, verify integrity (không bao giờ gửi qua network cùng request)
```

### 20.2. Key Generation — Sinh Key Đúng Cách

```java
@Service
public class HmacKeyService {

    public String generateHmacSecret(int byteLength) {
        // ✅ SecureRandom — CSPRNG, không dùng Math.random() hay UUID
        SecureRandom secureRandom = new SecureRandom();
        byte[] keyBytes = new byte[byteLength];
        secureRandom.nextBytes(keyBytes);
        // URL-safe Base64
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
    }

    // SHA256 → 32 bytes (256-bit)
    public String generateSha256Key() {
        return generateHmacSecret(32);  // ✅ Khuyến nghị
    }

    // SHA384 → 48 bytes (384-bit) — cho financial/high-security
    public String generateSha384Key() {
        return generateHmacSecret(48);
    }
}
```

**Key size theo use case:**

| Algorithm | Key Size | Use Case | Production Status |
|-----------|---------|---------|-----------------|
| HMAC-SHA1 | 160 bits | ❌ Không dùng | Deprecated |
| HMAC-SHA256 | 256 bits | Standard partner API | ✅ **Khuyến nghị** |
| HMAC-SHA384 | 384 bits | Financial, payment | ✅ |
| HMAC-SHA512 | 512 bits | High-security | ✅ |

**Benchmark thực tế (trên CPU hiện đại):**
```
HMAC-SHA256: ~200-400 MB/s  → sweet spot bảo mật + performance
HMAC-SHA512: ~150-300 MB/s  → overhead 64-bit ops trên 32-bit systems
SHA3-256:    ~100-250 MB/s  → an toàn hơn nhưng chậm hơn
→ SHA256 là điểm ngọt: đủ bảo mật, performance tốt, vendor support rộng
```

### 20.3. Key Storage — Zero Plaintext

```java
@Service
@RequiredArgsConstructor
public class PartnerHmacSecretStorage {

    private final PartnerRepository partnerRepo;
    private final PasswordEncoder passwordEncoder;

    // KHÔNG BAO GIỜ lưu plaintext
    public void storeHmacSecret(String partnerId, String rawSecret) {
        // Argon2id: cost factor 14, memory 64MB, parallelism 4
        // Chống brute force offline attack
        String hashed = argon2.hash(
            Argon2Parameters.ARGON2ID,
            14,           // iterations
            65536,        // memory KB
            4,            // parallelism
            rawSecret.toCharArray(),
            partnerId.getBytes(UTF_8)  // salt = partner_id
        );

        partnerRepo.updateHmacSecretHash(partnerId, hashed);
    }

    // Verify: dùng constant-time comparison
    public boolean verifySecret(String partnerId, String rawSecret) {
        String storedHash = partnerRepo.getHmacSecretHash(partnerId);
        return argon2.verify(storedHash, rawSecret.toCharArray());
    }
}
```

**Multi-layer storage cho enterprise (HSM/Vault):**

```
┌─────────────────────────────────────────────────────────┐
│                    Production Setup                      │
│                                                          │
│  API Request                                             │
│    │                                                    │
│    ▼                                                    │
│  ┌─────────────┐    ┌────────────────┐                  │
│  │ Spring App  │───▶│ HashiCorp Vault │                  │
│  │             │    │  (AES-256-GCM) │                  │
│  └─────────────┘    └────────────────┘                  │
│                            │                             │
│                            ▼                             │
│                    ┌────────────────┐                   │
│                    │     HSM        │  ← Hardware        │
│                    │ (Thales Luna /  │    Security Module│
│                    │  AWS CloudHSM)  │  ← Key không     │
│                    └────────────────┘    bao giờ rời HSM│
└─────────────────────────────────────────────────────────┘

Vault policy: App chỉ được ENCRYPT/DECRYPT, không bao giờ export raw key
```

### 20.4. Webhook Signature Computation

```java
@Service
@RequiredArgsConstructor
public class WebhookSignatureService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration MAX_TIMESTAMP_DRIFT = Duration.ofMinutes(5);

    // Format signature: HMAC-SHA256(timestamp + "." + payload)
    // Format header: Stripe-style "t=timestamp,v1=signature"
    public String computeWebhookSignature(String payload,
                                          String timestamp,
                                          String secret) {
        String signedPayload = timestamp + "." + payload;

        Mac mac;
        try {
            mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }

        byte[] hmacBytes = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    // Header format reference:
    // Stripe:   "t=1716864000,v1=signature"
    // GitHub:    "sha256=signature"
    // Twilio:    Base64(HMAC-SHA1)
    // VNPay:     Base64(HMAC-SHA2)
    // AWS SNS:   "sig-key: SHA256#signature"
}
```

### 20.5. Full Webhook Verification Flow

```java
@Service
@RequiredArgsConstructor
public class WebhookVerificationService {

    private final IdempotencyStore idempotencyStore;
    private final PartnerSecretRepository secretRepo;
    private final AuditService auditService;

    public VerificationResult verifyIncomingWebhook(String partnerId,
                                                    Map<String, String> headers,
                                                    String rawBody) {
        String providedSig = headers.get("X-Webhook-Signature");
        String timestamp = headers.get("X-Webhook-Timestamp");
        String deliveryId = headers.get("X-Webhook-Delivery-Id");

        // 1. Missing headers
        if (providedSig == null || timestamp == null) {
            return VerificationResult.failure("Missing signature or timestamp header");
        }

        // 2. Timestamp validation — chống replay attack
        Instant eventTime;
        try {
            eventTime = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid timestamp format");
        }

        if (Duration.between(eventTime, Instant.now()).abs()
                .compareTo(MAX_TIMESTAMP_DRIFT) > 0) {
            log.warn("Webhook timestamp outside window: {} (drift: {}s)",
                timestamp,
                Duration.between(eventTime, Instant.now()).abs().getSeconds());
            return VerificationResult.failure("Timestamp outside acceptable window");
        }

        // 3. Deduplication — chống replay (7 ngày)
        if (idempotencyStore.exists(deliveryId)) {
            log.info("Duplicate webhook delivery detected: {}", deliveryId);
            return VerificationResult.duplicate(deliveryId);
        }

        // 4. Signature verification — hỗ trợ multiple secrets (key rotation)
        List<String> activeSecrets = secretRepo.getActiveSecrets(partnerId);

        boolean valid = activeSecrets.stream()
            .anyMatch(secret -> {
                String expected = computeSignature(rawBody, timestamp, secret);
                // Constant-time comparison — chống timing attack
                return MessageDigest.isEqual(
                    expected.getBytes(UTF_8),
                    providedSig.getBytes(UTF_8)
                );
            });

        if (!valid) {
            auditService.log(InvalidSignatureEvent.builder()
                .partnerId(partnerId)
                .deliveryId(deliveryId)
                .attemptedAt(Instant.now())
                .build());
            return VerificationResult.failure("Signature mismatch");
        }

        // 5. Mark processed
        idempotencyStore.put(deliveryId,
            IdempotencyRecord.builder()
                .processedAt(Instant.now())
                .partnerId(partnerId)
                .eventType(headers.get("X-Webhook-Event"))
                .build(),
            Duration.ofDays(7));

        return VerificationResult.success(deliveryId);
    }
}
```

### 20.6. Key Rotation — Rolling Không Downtime

**Critical requirement:** Partner phải có thời gian chuyển đổi key mà không bị miss webhook.

```
Timeline:
t=0        → Partner dùng Secret-A (active)
t=-30d     → Generate Secret-B, đánh dấu pending, gửi notification
t=-30d+7d  → Partner update webhook config với Secret-B
t=0         → Secret-A expire, chỉ còn Secret-B active
```

```java
@Service
@RequiredArgsConstructor
public class HmacSecretRotationService {

    private final PartnerRepository partnerRepo;
    private final NotificationService notificationService;

    // Chạy 30 ngày trước khi secret hết hạn
    @Scheduled(cron = "0 0 4 1 * *")
    public void initiateRotationForExpiringSecrets() {
        List<Partner> expiring = partnerRepo
            .findBySecretExpiresWithin(Duration.ofDays(30));

        for (Partner partner : expiring) {
            rotateWebhookSecret(partner);
        }
    }

    @Transactional
    public RotationResult rotateWebhookSecret(Partner partner) {
        // 1. Generate secret mới
        String newSecret = hmacKeyService.generateSha256Key();
        String pendingHash = hashSecret(newSecret);

        partner.setHmacSecretPending(pendingHash);
        partner.setSecretPendingAt(Instant.now());
        partnerRepo.save(partner);

        // 2. Gửi secret mới cho partner qua secure channel
        notificationService.sendSecretRotationNotice(
            partner.getEmail(),
            partner.getWebhookRotationUrl(),
            newSecret,
            partner.getWebhookSecretExpiresAt()
        );

        auditService.log(SecretRotationInitiatedEvent.builder()
            .partnerId(partner.getId())
            .rotatedBy("scheduler")
            .expiresAt(partner.getWebhookSecretExpiresAt())
            .build());

        return RotationResult.builder()
            .newSecret(newSecret)
            .validFrom(Instant.now())
            .validUntil(partner.getWebhookSecretExpiresAt())
            .instructions(buildRotationInstructions(partner))
            .build();
    }

    // Partner confirm: đã update config với secret mới → promote pending → active
    @PostMapping("/api/v1/webhooks/rotate/confirm")
    public ResponseEntity<RotationConfirmResponse> confirmRotation(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody ConfirmRotationRequest request) {

        Partner partner = authService.getPartnerByApiKey(apiKey);
        PartnerMetrics metrics = metricsService.getMetrics(partner.getId());

        // Partner phải verify thành công ít nhất 1 webhook với secret mới
        if (!metrics.hasRecentWebhookWithPendingSecret()) {
            return ResponseEntity.badRequest()
                .body(RotationConfirmResponse.builder()
                    .status("PENDING_VERIFICATION")
                    .message("Verify at least one webhook with the new secret first")
                    .build());
        }

        partner.setHmacSecret(partner.getHmacSecretPending());
        partner.setHmacSecretPending(null);
        partner.setWebhookSecretExpiresAt(
            Instant.now().plus(Duration.ofDays(partner.getSecretValidityDays())));
        partnerRepo.save(partner);

        return ResponseEntity.ok(RotationConfirmResponse.builder()
            .status("SUCCESS")
            .newSecretActiveAt(Instant.now())
            .nextRotationAt(partner.getWebhookSecretExpiresAt()
                .minus(Duration.ofDays(30)))
            .build());
    }
}
```

### 20.7. HMAC Request Signing (Partner → Your API)

Khi partner gửi sensitive request (thay vì chỉ dùng API Key header):

```java
@Service
public class RequestSignatureService {

    // String to sign: HTTP_METHOD + PATH + TIMESTAMP + BODY_HASH
    // Signature: HMAC-SHA256(stringToSign, partnerHmacSecret)
    public String signRequest(String httpMethod,
                               String path,
                               String timestamp,
                               String bodyHash,
                               String hmacSecret) {
        String stringToSign = httpMethod.toUpperCase() + "\n"
                            + path + "\n"
                            + timestamp + "\n"
                            + bodyHash;

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
            hmacSecret.getBytes(UTF_8), "HmacSHA256");
        mac.init(keySpec);

        byte[] rawHmac = mac.doFinal(stringToSign.getBytes(UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }

    public boolean verifyPartnerRequest(HttpServletRequest request,
                                         String providedSignature) {
        String timestamp = request.getHeader("X-Request-Timestamp");
        String bodyHash = request.getHeader("X-Body-Hash");  // SHA256(body)
        String path = request.getRequestURI();
        String partnerId = request.getHeader("X-Tenant-ID");

        // 1. Timestamp check (±5 phút)
        if (isTimestampStale(timestamp)) {
            log.warn("Stale request timestamp from partner {}: {}", partnerId, timestamp);
            return false;
        }

        // 2. Body hash verification
        String cachedBody = (String) request.getAttribute("cachedBody");
        String computedHash = hashBody(cachedBody);
        if (!MessageDigest.isEqual(computedHash.getBytes(), bodyHash.getBytes())) {
            log.warn("Body hash mismatch for partner {}: expected={}, got={}",
                partnerId, computedHash, bodyHash);
            return false;
        }

        // 3. Signature verification
        String secret = partnerService.getHmacSecret(partnerId);
        String expected = signRequest(
            request.getMethod(), path, timestamp, bodyHash, secret);

        return MessageDigest.isEqual(
            expected.getBytes(UTF_8),
            providedSignature.getBytes(UTF_8)
        );
    }

    // Headers partner cần gửi:
    // X-Request-Timestamp:  1716864000       (Unix epoch seconds)
    // X-Body-Hash:          SHA256(body)      (Base64)
    // X-Request-Signature:  HMAC-SHA256(stringToSign, secret)
    // X-API-Key:            partner_identifier
}
```

### 20.8. Security Checklist — HMAC Key

```
Generation
□ Dùng SecureRandom (CSPRNG) — KHÔNG dùng UUID, timestamp, Math.random()
□ Key size ≥ 256-bit (SHA256) cho standard, ≥ 384-bit cho financial
□ Secret được trả về cho partner ĐÚNG 1 LẦN duy nhất khi tạo/rotate
□ Partner phải lưu secret vào password manager ngay

Storage
□ KHÔNG BAO GIỜ lưu plaintext trong DB
□ Hash với Argon2id (cost 14, memory 64MB+, parallelism 4)
□ Mã hóa layer trên (AES-256-GCM) nếu không dùng Vault
□ Không log secret (chỉ log key_id + hash prefix 8 ký tự)

Transmission
□ Chỉ truyền qua HTTPS (TLS 1.3, không chấp nhận TLS 1.1)
□ Secret mới gửi qua portal HTTPS (không qua email plaintext)
□ Không bao giờ gửi secret cùng request body

Verification
□ Constant-time comparison: MessageDigest.isEqual() hoặc custom secureCompare
□ Timestamp validation: chấp nhận ±5 phút, reject stale request
□ Delivery ID deduplication: lưu 7-30 ngày tùy business
□ Support multiple active secrets: grace period key rotation
□ Không verify trong same transaction với business logic (timing leak)

Rotation
□ Automatic reminder 30 ngày trước expiry
□ Dual-secret window: secret cũ + mới cùng valid trong transition (7 ngày)
□ Partner phải confirm đã dùng secret mới mới expire secret cũ
□ Rotation không làm mất webhook event nào
□ Audit log mọi rotation event (who, when, what)

Monitoring
□ Alert: signature verification fail rate > 5% trong 5 phút
□ Alert: partner chưa rotate secret sau 60 ngày
□ Alert: multiple failed signature attempts từ 1 IP/partner
□ Counter: total signature verifications, success rate per partner
```

### 20.9. Quick Reference

```
┌─────────────────────┬──────────────┬──────────────┬───────────────────────┐
│ Use Case            │ Algorithm    │ Key Size     │ Rotation Period       │
├─────────────────────┼──────────────┼──────────────┼───────────────────────┤
│ Webhook signing     │ HMAC-SHA256  │ 256-bit      │ 90-365 ngày           │
│ Request signing     │ HMAC-SHA256  │ 256-bit      │ 90-365 ngày           │
│ High-security       │ HMAC-SHA384  │ 384-bit      │ 30-90 ngày            │
│ Financial/Payment   │ HMAC-SHA384  │ 384-bit +    │ 30 ngày + audit       │
│                     │              │ key escrow   │                       │
└─────────────────────┴──────────────┴──────────────┴───────────────────────┘

Common mistakes trong thực tế:
❌ Dùng API secret làm HMAC key → leak HMAC = leak API secret
❌ Lưu plaintext trong DB → developer có thể đọc production secrets
❌ Không rotate key → càng lâu càng nguy hiểm
❌ Verify signature rồi xử lý business logic trong 1 transaction → timing side-channel
❌ Không support multiple secrets → rotation gây downtime
❌ Log full signature → attacker đọc log có signature → replay được

Best practices:
✅ Separate credentials: API Key (identify) + HMAC Secret (sign)
✅ Hash trước khi lưu: Argon2id
✅ Dual-secret rotation window
✅ Constant-time verification
✅ Full audit trail cho mọi secret operation
```

---

## Checklist Cuối Cùng — Trước Khi Go Live

### Integration (Inbound)
```
□ Circuit breaker configured và tested
□ Retry policy tested (với real latency simulation)
□ Rate limit documented và communicated
□ Timeout properly set (và tested với slow response)
□ Webhook signature validation tested
□ Idempotency implemented
□ Fallback strategy defined và tested
□ Health check endpoint exists
□ Metrics dashboard setup
□ Alerting configured cho failure patterns
```

### API Sharing (Outbound)
```
□ API Key rotation policy defined
□ Tenant isolation tested (can partner A access partner B's data?)
□ Rate limiting tested
□ Webhook delivery tested (retry, signature, replay protection)
□ Documentation reviewed by 1 partner representative
□ Sandbox environment fully functional
□ SLA documented và agreed
□ Monitoring dashboard public (partner self-service)
□ Onboarding flow tested end-to-end
□ Security review completed
```

---

*Document created by Senior Backend Engineer / Solution Architect perspective*
*Based on 10+ years of enterprise production experience*
*Last updated: 2026-05-28*
