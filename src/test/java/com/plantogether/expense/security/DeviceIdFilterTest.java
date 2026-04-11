package com.plantogether.expense.security;

import com.plantogether.common.security.DeviceIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Security regression test — verifies DeviceIdFilter is active in expense-service (R01).
 */
@ExtendWith(MockitoExtension.class)
class DeviceIdFilterTest {

    private DeviceIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new DeviceIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    void validUuidHeader_setsSecurityContext() throws Exception {
        UUID id = UUID.randomUUID();
        request.addHeader("X-Device-Id", id.toString());
        filter.doFilter(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(id.toString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingHeader_leavesContextUnauthenticated() throws Exception {
        filter.doFilter(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void invalidUuidFormat_leavesContextUnauthenticated() throws Exception {
        request.addHeader("X-Device-Id", "not-a-uuid");
        filter.doFilter(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void uuidWithWhitespace_trimmedAndAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        request.addHeader("X-Device-Id", "  " + id + "  ");
        filter.doFilter(request, response, filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(id.toString());
        verify(filterChain).doFilter(request, response);
    }
}
