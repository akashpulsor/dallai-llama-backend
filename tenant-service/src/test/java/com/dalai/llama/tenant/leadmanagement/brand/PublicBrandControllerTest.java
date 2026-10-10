package com.dalai.llama.tenant.leadmanagement.brand;

import com.dalai.llama.tenant.domain.exception.GlobalExceptionHandler;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The brand session lives only in an HttpOnly cookie: set on sign-in, never in the body, cleared
 * by sign-out, and the only thing brand endpoints accept. */
class PublicBrandControllerTest {

    private final BrandAccountService accounts = mock(BrandAccountService.class);
    private final BrandSessionService sessions = new BrandSessionService(
            new BrandProperties("secret", 30, true, 30, 5, "", "", ""), Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC));
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PublicBrandController(accounts, sessions, mock(BrandInquiryService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void signInSetsAnHttpOnlySecureLaxCookieAndKeepsTheTokenOutOfTheBody() throws Exception {
        UUID brand = UUID.randomUUID();
        BrandSessionService.Session session = sessions.issue(brand);
        when(accounts.completeSignIn("link-token")).thenReturn(new BrandAccountService.SignInResult(session,
                new BrandDtos.SignedIn(session.expiresAt(), "follow:riya-motion", null)));

        MvcResult result = mvc.perform(post("/api/v1/public/brands/sign-in/link-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAction").value("follow:riya-motion"))
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("dl_brand_session=" + session.token())
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/public", "Max-Age=2592000");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(session.token());
    }

    @Test
    void signOutClearsTheCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/public/brands/sign-out")).andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("dl_brand_session=;").contains("Max-Age=0");
    }

    @Test
    void brandHomeReadsTheCookieAndRefusesWithoutIt() throws Exception {
        UUID brand = UUID.randomUUID();
        when(accounts.me(brand)).thenReturn(new BrandDtos.BrandMeView("a@x.example", null, null, null, null, null, false, 7, List.of()));

        mvc.perform(get("/api/v1/public/brands/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/brands/me").header("X-Brand-Session", sessions.issue(brand).token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/brands/me").cookie(new Cookie(BrandSessionService.COOKIE, sessions.issue(brand).token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@x.example"));
    }
}
