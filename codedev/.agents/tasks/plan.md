# Implementation Plan

## Context (discovered from codebase exploration)

- **Project**: Spring Boot 4.1.1, Java 21, Maven, package `com.urlshortner.codedev`
- **Build command**: `./mvnw compile` (currently succeeds cleanly)
- **Test command**: `./mvnw test`
- **Key source paths**: `src/main/java/com/urlshortner/codedev/`, `src/test/java/com/urlshortner/codedev/`
- **Test frameworks**: JUnit Jupiter 6.0.3, Mockito 5.23.0, AssertJ 3.27.7, Spring Security Test 7.1.1
- **Spring Security**: on compile classpath — `SecurityConfig` permits all requests (`anyRequest().permitAll()`), CSRF disabled. `@WebMvcTest` slices must import `SecurityConfig` explicitly to avoid the default deny-all behaviour.
- **`UrlMapping`**: plain JPA `@Entity` class (not a Java record) with accessor methods named `shortCode()`, `longUrl()`, `createdAt()`, `expiresAt()`, `status()`. Constructor: `UrlMapping(String shortCode, String longUrl, Instant createdAt, Instant expiresAt, UrlStatus status)`.
- **`isActiveAt(Instant now)`**: returns `status == ACTIVE && !isExpired(now)`.
- **`UrlCache`**: interface with `get(String)→Optional<UrlMapping>`, `put(String,UrlMapping)`, `evict(String)`.
- **`ShortenRequest`**: inner record in `ShortenController`, currently has `@NotBlank String longUrl` but is missing `@Size(max=2048)`.
- **`validateUrl`** in `UrlServiceImpl`: checks blank, scheme prefix, length — needs URI parsing added.
- **No `GlobalExceptionHandler` exists yet** — file must be created from scratch.
- **Existing `CodedevApplicationTests`** uses `@SpringBootTest` and fails because the inner `UrlJpaRepository` interface isn't picked up by scanning (package-private). That test must be fixed to use a slice annotation.
- **`application.yaml`** uses H2 in-memory for datasource — usable for JPA slices in tests.

---

## Plan

- [ ] 1. Add `ErrorResponse` record and `GlobalExceptionHandler` class in the exception package.
      Create `ErrorResponse` as a top-level `public record ErrorResponse(String error) {}` in the exception package (not nested) so it is reusable from handler and tests.
      Create `GlobalExceptionHandler` annotated `@RestControllerAdvice`. Add five `@ExceptionHandler` methods:
      - `UrlNotFoundException` → `ResponseEntity` 404 with `new ErrorResponse("Short URL not found")`
      - `UrlGoneException` → `ResponseEntity` 410 with `new ErrorResponse("Short URL has expired or been disabled")`
      - `DuplicateShortCodeException` → `ResponseEntity` 409 with `new ErrorResponse("Short code already exists")`
      - `MethodArgumentNotValidException` → `ResponseEntity` 400 with `new ErrorResponse("Validation failed: " + ex.getBindingResult().getFieldErrors() stream first message, fallback to ex.getMessage())` — use `ex.getBindingResult().getFieldErrors().stream().findFirst().map(fe -> fe.getField() + " " + fe.getDefaultMessage()).orElse("Validation failed")` as the error string.
      - `IllegalArgumentException` → `ResponseEntity` 400 with `new ErrorResponse(ex.getMessage())`
      - `Exception` (catch-all) → `ResponseEntity` 500 with `new ErrorResponse("An unexpected error occurred")`
      
      Imports needed:
      ```
      import org.springframework.http.HttpStatus;
      import org.springframework.http.ResponseEntity;
      import org.springframework.web.bind.MethodArgumentNotValidException;
      import org.springframework.web.bind.annotation.ExceptionHandler;
      import org.springframework.web.bind.annotation.RestControllerAdvice;
      ```

      Files to create:
      - `src/main/java/com/urlshortner/codedev/exception/ErrorResponse.java`
      - `src/main/java/com/urlshortner/codedev/exception/GlobalExceptionHandler.java`

      Verify: `./mvnw compile -q` — BUILD SUCCESS, zero errors.

- [ ] 2. Add `@Size(max = 2048)` to `ShortenRequest.longUrl`.
      In `ShortenController`, `ShortenRequest` already has `@NotBlank String longUrl`. Add `@Size(max = 2048)` from `jakarta.validation.constraints.Size`. The field declaration becomes:
      ```java
      @NotBlank @Size(max = 2048) String longUrl,
      ```
      The `@Valid` annotation on the controller method parameter is already present — no other change needed in the controller.

      Files to modify:
      - `src/main/java/com/urlshortner/codedev/controller/ShortenController.java`

      Verify: `./mvnw compile -q` — BUILD SUCCESS.

- [ ] 3. Improve `validateUrl` in `UrlServiceImpl` to also parse via `java.net.URI`.
      After the existing three checks (blank, scheme, length), add a fourth block that attempts `new URI(url).toURL()` inside a try/catch for `URISyntaxException | MalformedURLException | IllegalArgumentException`. On any of those exceptions throw `new IllegalArgumentException("Invalid URL format")`. This keeps the existing error messages for blank/scheme/length unchanged while adding structural validation.
      
      The check order must remain:
      1. blank → "URL must not be blank"
      2. length > 2048 → "URL exceeds maximum length of 2048"  (reorder: move length check before scheme check to match task spec; currently scheme is checked before length — swap to: blank → length → scheme → URI parse)
      3. non-http/https → "Only http and https URLs are allowed"  (task spec wording — update message to match spec exactly)
      4. URI parse failure → "Invalid URL format"

      **Note on message wording**: The task spec says `"URL exceeds maximum length"` and `"Only http and https URLs are allowed"`. The existing code has `"URL exceeds maximum length of 2048"` and `"URL must use http or https scheme"`. Update both messages to match the task spec so tests can rely on them.

      Imports to add: `java.net.URI`, `java.net.MalformedURLException`, `java.net.URISyntaxException`

      Files to modify:
      - `src/main/java/com/urlshortner/codedev/service/UrlServiceImpl.java`

      Verify: `./mvnw compile -q` — BUILD SUCCESS.

- [ ] 4. Write unit tests for `Base62ShortCodeGenerator`.
      Create `Base62ShortCodeGeneratorTest` with four `@Test` methods using JUnit Jupiter:
      - `encode0ReturnsZero`: `assertThat(generator.encode(0)).isEqualTo("0")`
      - `encode62Returns10`: `assertThat(generator.encode(62)).isEqualTo("10")` — 62 in base 62 is "10" (1×62 + 0)
      - `negativeIdThrows`: `assertThrows(IllegalArgumentException.class, () -> generator.encode(-1))`
      - `encodedStringContainsOnlyBase62Chars`: encode a large Snowflake-style id (e.g., `Long.MAX_VALUE / 2`) and assert every character matches `[0-9a-zA-Z]` using a regex — `assertThat(result).matches("[0-9a-zA-Z]+")`

      No mocks needed — `Base62ShortCodeGenerator` has no dependencies.

      Files to create:
      - `src/test/java/com/urlshortner/codedev/generator/Base62ShortCodeGeneratorTest.java`

      Verify: `./mvnw test -pl . -Dtest=Base62ShortCodeGeneratorTest -q` — all 4 tests pass.

- [ ] 5. Write unit tests for `UrlServiceImpl`.
      Create `UrlServiceImplTest` as a plain JUnit + Mockito test (no Spring context — use `@ExtendWith(MockitoExtension.class)`).
      
      Mock fields (all `@Mock`): `UrlRepository repository`, `UrlCache cache`, `ShortCodeGenerator codeGenerator`, `IdGenerator idGenerator`, `ClickEventPublisher eventPublisher`, `Clock clock`.
      
      `@InjectMocks UrlServiceImpl service;`
      
      Tests to include:

      **validateUrl tests** (tested indirectly via `service.create(...)`):
      - `createWithBlankUrlThrows`: `create("", null)` → `IllegalArgumentException` with message `"URL must not be blank"`
      - `createWithTooLongUrlThrows`: `create("https://" + "a".repeat(2042), null)` → `IllegalArgumentException` with message containing `"exceeds maximum length"`
      - `createWithNonHttpUrlThrows`: `create("ftp://example.com", null)` → `IllegalArgumentException` with message `"Only http and https URLs are allowed"`
      - `createWithMalformedUrlThrows`: `create("http://[invalid", null)` → `IllegalArgumentException` with message `"Invalid URL format"`

      **resolve() tests**:
      - `resolveReturnsCachedMappingOnCacheHit`: stub `cache.get("abc")` to return `Optional.of(activeMapping)` where `activeMapping` is a real `UrlMapping("abc", "https://example.com", Instant.now(), null, UrlStatus.ACTIVE)`. Stub `clock.instant()` to return a fixed `Instant`. Assert returned mapping equals the stubbed one. Verify `repository.findByShortCode` is **never** called.
      - `resolveThrowsUrlNotFoundWhenNotInCacheOrDb`: stub `cache.get("xyz")` → `Optional.empty()`, stub `repository.findByShortCode("xyz")` → `Optional.empty()`. Assert `UrlNotFoundException` is thrown.
      - `resolveThrowsUrlGoneForExpiredMapping`: create `UrlMapping("abc", "https://example.com", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), UrlStatus.ACTIVE)` (expired). Stub `clock.instant()` → `Instant.now()` (well after epoch+1). Stub `cache.get("abc")` → `Optional.of(expiredMapping)`. Assert `UrlGoneException` is thrown.
      - `resolveThrowsUrlGoneForDisabledMapping`: create `UrlMapping("abc", "https://example.com", Instant.EPOCH, null, UrlStatus.DISABLED)`. Stub `cache.get` and `clock.instant()`. Assert `UrlGoneException` is thrown.

      **Important**: To construct `UrlMapping` in tests, use the 5-arg constructor directly: `new UrlMapping(shortCode, longUrl, createdAt, expiresAt, status)`.

      Files to create:
      - `src/test/java/com/urlshortner/codedev/service/UrlServiceImplTest.java`

      Verify: `./mvnw test -Dtest=UrlServiceImplTest -q` — all tests pass.

- [ ] 6. Write `@WebMvcTest`-based tests for `GlobalExceptionHandler`.
      Create `GlobalExceptionHandlerTest` using `@WebMvcTest(RedirectController.class)`.

      **Spring Security handling**: `SecurityConfig` is `@EnableWebSecurity` and will be excluded by `@WebMvcTest`'s default component filter, which means Spring Security's default form-login/401 kicks in and blocks requests. Fix this by adding `@Import(SecurityConfig.class)` to the test class — since `SecurityConfig` permits all requests, this makes all test requests pass without authentication.

      Add `@MockitoBean UrlService urlService;` (Spring Boot 4.x uses `@MockitoBean` instead of deprecated `@MockBean`).
      Autowire `MockMvc mockMvc`.

      Tests:
      - `urlNotFoundReturns404`: stub `urlService.resolve("abc")` to throw `new UrlNotFoundException()`. Perform `GET /abc` via `mockMvc`. Assert HTTP status 404 and JSON body `{"error":"Short URL not found"}`.
      - `urlGoneReturns410`: stub `urlService.resolve("abc")` to throw `new UrlGoneException()`. Perform `GET /abc`. Assert HTTP status 410 and JSON body `{"error":"Short URL has expired or been disabled"}`.

      Use `MockMvcResultMatchers.status().isNotFound()`, `.status().isGone()`, and `.jsonPath("$.error").value(...)` for assertions.

      **Import nuances**:
      ```java
      import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
      import org.springframework.context.annotation.Import;
      import org.springframework.test.web.servlet.MockMvc;
      import org.springframework.beans.factory.annotation.Autowired;
      import org.springframework.test.context.bean.override.mockito.MockitoBean;
      import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
      import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
      import com.urlshortner.codedev.config.SecurityConfig;
      import com.urlshortner.codedev.controller.RedirectController;
      import com.urlshortner.codedev.exception.UrlGoneException;
      import com.urlshortner.codedev.exception.UrlNotFoundException;
      import com.urlshortner.codedev.service.UrlService;
      ```

      `@MockitoBean` is the Spring Boot 3.4+ replacement for `@MockBean`; with Spring Boot 4.1.1 always use `@MockitoBean`.

      Files to create:
      - `src/test/java/com/urlshortner/codedev/exception/GlobalExceptionHandlerTest.java`

      Verify: `./mvnw test -Dtest=GlobalExceptionHandlerTest -q` — both tests pass.

- [ ] 7. Fix the broken `CodedevApplicationTests`.
      The existing `@SpringBootTest` test fails because:
      (a) the inner `UrlJpaRepository` interface in `JpaUrlRepository` is package-private and not discovered as a Spring Data JPA repository by component scan, and
      (b) Redis and Kafka beans cannot be created without live infrastructure.
      
      Replace the `@SpringBootTest` full-context test with a minimal smoke test that validates only what can load in isolation. The simplest fix: convert `CodedevApplicationTests` to use `@SpringBootTest` with `webEnvironment = NONE` and add properties to disable Redis session, autoconfigure test datasource with H2, and exclude Kafka/Redis auto-configuration:
      
      ```java
      @SpringBootTest(
          webEnvironment = SpringBootTest.WebEnvironment.NONE,
          properties = {
              "spring.autoconfigure.exclude=" +
                  "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                  "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                  "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                  "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                  "org.springframework.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration"
          }
      )
      ```
      Also add `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)` so H2 is always used.

      **Alternative simpler approach** (preferred — avoids fragile exclusion lists): Delete the body of `contextLoads()` and replace the full `@SpringBootTest` with `@DataJpaTest` — but that only loads JPA, not the full context. Given the test is named `contextLoads`, the intent is a smoke test. Use the exclusion approach above to keep the spirit of the test while making it pass in CI without Redis/Kafka.

      Files to modify:
      - `src/test/java/com/urlshortner/codedev/CodedevApplicationTests.java`

      Verify: `./mvnw test -Dtest=CodedevApplicationTests -q` — test passes (or is skipped without error).

- [ ] 8. Run the full test suite and confirm all new tests pass.
      After completing items 1–7, run all tests together to confirm there are no cross-test interference issues (e.g., `@WebMvcTest` slice not accidentally loading full context).

      Verify: `./mvnw test -q` — `Tests run: N, Failures: 0, Errors: 0` where N ≥ 9 (4 Base62 + 4+ UrlServiceImpl + 2 GlobalExceptionHandler + 1 CodedevApplicationTests).

---

## Import and annotation nuances summary

| Concern | Detail |
|---------|--------|
| `@MockitoBean` | Spring Boot 4.x — use `org.springframework.test.context.bean.override.mockito.MockitoBean`, NOT the old `@MockBean` from `spring-boot-test` |
| `@WebMvcTest` + Security | Without `@Import(SecurityConfig.class)`, default Spring Security blocks all requests with 401/302. Import the real `SecurityConfig` (permits all) to avoid needing `@WithMockUser` on every test |
| `UrlMapping` constructor | 5-arg: `(String shortCode, String longUrl, Instant createdAt, Instant expiresAt, UrlStatus status)` — it is NOT a Java record, has a protected no-arg constructor for JPA |
| `URI` validation | Use `new URI(url).toURL()` — catch `URISyntaxException`, `MalformedURLException`, and `IllegalArgumentException` (the last is thrown by `URI.create` for some inputs but `new URI(url)` throws `URISyntaxException`) |
| `MethodArgumentNotValidException` | Import from `org.springframework.web.bind.MethodArgumentNotValidException` |
| `ErrorResponse` | Must be a top-level public type (not nested), so Spring's `HttpMessageConverter` serialises it as `{"error":"..."}` |
| `@Size` import | `jakarta.validation.constraints.Size` (Jakarta EE namespace, not `javax`) |
