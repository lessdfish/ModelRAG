package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.common.security.RequestUser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class SecurityAcceptanceTest {
    @Test
    void queryStringTokensAreRejected() {
        LocalAuthTokenService tokens = new LocalAuthTokenService("security-test-secret", 3600);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("access_token", tokens.issue("query-user", java.util.Set.of("USER"), java.util.Set.of()));

        AccessControlService access = new AccessControlService(request, tokens, mock(JdbcTemplate.class));

        assertThrows(BusinessException.class, access::currentUser);
    }

    @Test
    void bearerTokenIsAcceptedWithoutDebugHeaders() {
        LocalAuthTokenService tokens = new LocalAuthTokenService("security-test-secret", 3600);
        String bearer = tokens.issue("bearer-user", java.util.Set.of("USER"), java.util.Set.of());
        assertEquals("bearer-user", tokens.parse(bearer).id());
    }

    @Test
    void datasetReadGrantDoesNotImplyWritePermission() {
        RequestUser reader = new RequestUser("reader", Set.of("USER"), Set.of(7L), Map.of(7L, "READ"));
        RequestUser writer = new RequestUser("writer", Set.of("USER"), Set.of(7L), Map.of(7L, "WRITE"));
        RequestUser datasetAdmin = new RequestUser("dataset-admin", Set.of("USER"), Set.of(7L), Map.of(7L, "ADMIN"));
        RequestUser admin = new RequestUser("admin", Set.of("ADMIN"), Set.of(), Map.of());

        assertEquals(true, reader.canAccess(7L));
        assertEquals(false, reader.canWrite(7L));
        assertEquals(true, writer.canWrite(7L));
        assertEquals(false, writer.canAdminister(7L));
        assertEquals(true, datasetAdmin.canWrite(7L));
        assertEquals(true, datasetAdmin.canAdminister(7L));
        assertEquals(true, admin.canWrite(7L));
        assertEquals(true, admin.canAdminister(7L));
    }
}
