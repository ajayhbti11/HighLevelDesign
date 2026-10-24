package com.urlshortner.codedev.exception;

import com.urlshortner.codedev.config.SecurityConfig;
import com.urlshortner.codedev.controller.RedirectController;
import com.urlshortner.codedev.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RedirectController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlService urlService;

    @Test
    void urlNotFoundReturns404() throws Exception {
        when(urlService.resolve("abc")).thenThrow(new UrlNotFoundException());

        mockMvc.perform(get("/abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Short URL not found"));
    }

    @Test
    void urlGoneReturns410() throws Exception {
        when(urlService.resolve("abc")).thenThrow(new UrlGoneException());

        mockMvc.perform(get("/abc"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("Short URL has expired or been disabled"));
    }
}
