package com.urlshortner.codedev;

import com.urlshortner.codedev.cache.UrlCache;
import com.urlshortner.codedev.event.ClickEventPublisher;
import com.urlshortner.codedev.repository.UrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"
        }
)
class CodedevApplicationTests {

    @MockitoBean
    UrlRepository urlRepository;

    @MockitoBean
    UrlCache urlCache;

    @MockitoBean
    ClickEventPublisher clickEventPublisher;

    @Test
    void contextLoads() {
    }

}
