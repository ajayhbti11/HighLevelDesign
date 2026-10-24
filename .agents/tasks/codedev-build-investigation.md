# Codedev Build Investigation Report

## Summary

The build fails with a **compilation error** caused by two distinct problems in `RedirectController.java`:

1. **Missing `package` declaration and all `import` statements** — the file has no `package` line and no imports at all, so the compiler cannot resolve `@RestController`, `@GetMapping`, `@PathVariable`, `ResponseEntity`, `HttpStatus`, and `URI`.
2. **`UrlService` and `UrlMapping` classes do not exist** — they are referenced in `RedirectController.java` but there are no source files for them anywhere in the project.

There is also a secondary concern in `pom.xml`: several test-scoped dependencies use non-standard artifact IDs (e.g., `spring-boot-starter-actuator-test`, `spring-boot-starter-webmvc-test`). These are real Spring Boot 4.x artifacts that resolved successfully — they are **not** a cause of the current build failure — but they are unusual and worth a review.

---

## Evidence

### 1. Actual build error output

Running `./mvnw compile` from the project root produced:

```
[ERROR] COMPILATION ERROR :
[ERROR] /…/controller/RedirectController.java:[3,2] cannot find symbol
         symbol: class RestController
[ERROR] /…/controller/RedirectController.java:[6,19] cannot find symbol
         symbol: class UrlService
[ERROR] /…/controller/RedirectController.java:[8,31] cannot find symbol
         symbol: class UrlService
[ERROR] /…/controller/RedirectController.java:[13,12] cannot find symbol
         symbol: class ResponseEntity
[ERROR] /…/controller/RedirectController.java:[14,14] cannot find symbol
         symbol: class PathVariable
[ERROR] /…/controller/RedirectController.java:[12,6] cannot find symbol
         symbol: class GetMapping
[ERROR] BUILD FAILURE
```

All errors are in `src/main/java/com/urlshortner/codedev/controller/RedirectController.java`.

### 2. RedirectController.java — missing package and imports

The file as it stands on disk:

```java
// (file starts here — no package, no imports)

@RestController
public class RedirectController {

    private final UrlService urlService;

    public RedirectController(UrlService urlService) {
        this.urlService = urlService;
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortCode) {

        UrlMapping mapping = urlService.resolve(shortCode);

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(mapping.longUrl()))
                .build();
    }
}
```

A hex dump of the file's first bytes confirms the file starts with two newline characters (`0x0a 0x0a`) followed immediately by `@RestController` — no `package` statement, no `import` statements. The Java compiler requires all used types to either be in `java.lang` (auto-imported) or explicitly imported. Spring annotations (`@RestController`, `@GetMapping`, `@PathVariable`) and framework types (`ResponseEntity`, `HttpStatus`, `URI`) are none of those.

**File:** `src/main/java/com/urlshortner/codedev/controller/RedirectController.java`

### 3. UrlService and UrlMapping do not exist

A complete enumeration of all `.java` files in the project:

```
src/main/java/com/urlshortner/codedev/CodedevApplication.java
src/main/java/com/urlshortner/codedev/controller/RedirectController.java
src/test/java/com/urlshortner/codedev/CodedevApplicationTests.java
```

`UrlService` (a service class/interface) and `UrlMapping` (a model/record) are used in `RedirectController` but never defined. They are missing from the source tree entirely.

### 4. Spring Boot version 4.1.1 and dependency observations

**pom.xml `<parent>`:** `spring-boot-starter-parent:4.1.1` — this is a real, GA release (August 2026). The Spring Boot 4.x line targets Spring Framework 7.x and Java 21+.

**`spring-boot-starter-webmvc`:** This is the renamed successor to the old `spring-boot-starter-web` in Spring Boot 4.x. It correctly pulls in `spring-web:7.0.9` and `spring-webmvc:7.0.9`, which contain `@RestController`, `@GetMapping`, `@PathVariable`, and `ResponseEntity`. So the classpath dependency is **present and correct** — the sole reason compilation fails is the missing imports in the Java source file.

**Test-scope starters (e.g., `spring-boot-starter-actuator-test`, `spring-boot-starter-webmvc-test`):** These are legitimate Spring Boot 4 artifacts (each pulls in the corresponding main starter + `spring-boot-starter-test`). They resolved successfully from Maven Central. They are not a cause of the build error.

**`spring-boot-starter-kafka`** and **`kafka-streams`:** Present and resolved; not a build issue.

---

## Root Cause

**`RedirectController.java` is an incomplete stub.** It was written without a `package` declaration and without any `import` statements. Additionally, two types it depends on — `UrlService` and `UrlMapping` — do not exist anywhere in the project source tree.

---

## What Needs to Be Fixed

### Fix 1 — Add `package` declaration and `import` statements to `RedirectController.java`

The file must begin with:

```java
package com.urlshortner.codedev.controller;

import com.urlshortner.codedev.model.UrlMapping;
import com.urlshortner.codedev.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
```

(Exact package paths for `UrlMapping` and `UrlService` should match wherever you create those classes — see Fix 2.)

### Fix 2 — Create the missing `UrlService` and `UrlMapping` types

The controller references:
- `UrlService` — an interface/class with a `resolve(String shortCode)` method that returns a `UrlMapping`.
- `UrlMapping` — a model with a `longUrl()` accessor (likely a Java `record` or a class).

At minimum, placeholder implementations are needed before the project will compile:

**`src/main/java/com/urlshortner/codedev/model/UrlMapping.java`**
```java
package com.urlshortner.codedev.model;

public record UrlMapping(String shortCode, String longUrl) {}
```

**`src/main/java/com/urlshortner/codedev/service/UrlService.java`**
```java
package com.urlshortner.codedev.service;

import com.urlshortner.codedev.model.UrlMapping;

public interface UrlService {
    UrlMapping resolve(String shortCode);
}
```

A concrete implementation of `UrlService` (backed by a database, Redis cache, etc.) will also be needed before the Spring context can start, but the `interface` alone is sufficient to make `./mvnw compile` pass.

### Fix 3 (Optional) — Review `pom.xml` dependencies for completeness

The current `pom.xml` includes a broad range of infrastructure dependencies (Kafka, Redis, JPA, OAuth2, Security, Sessions) but the project source contains only two files. Confirm these are all intentionally included, and add the corresponding domain/service/repository layers (entities, repositories, service implementations) to make the application runnable.

---

## Verification

After applying the fixes above, run:

```
./mvnw compile
```

Expected outcome: `BUILD SUCCESS` with no compilation errors.
