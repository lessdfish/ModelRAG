package com.modelrag.server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialAdminBootstrapTest {
    @Test
    void failsClosedWhenNoAdministratorAndNoExternalBootstrapSecretExists() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        PasswordEncoder passwords = org.mockito.Mockito.mock(PasswordEncoder.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> new InitialAdminBootstrap(jdbc, passwords, "", "").run(null));
        verify(passwords, never()).encode(any());
    }

    @Test
    void createsOnlyTheFirstAdministratorFromExplicitCredentials() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        PasswordEncoder passwords = org.mockito.Mockito.mock(PasswordEncoder.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);
        when(passwords.encode("external-bootstrap-password")).thenReturn("$argon2id$encoded");

        new InitialAdminBootstrap(jdbc, passwords, "initial-admin", "external-bootstrap-password").run(null);

        verify(passwords).encode("external-bootstrap-password");
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void leavesExternalBootstrapCredentialsUnusedAfterAnAdministratorExists() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        PasswordEncoder passwords = org.mockito.Mockito.mock(PasswordEncoder.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(1);

        new InitialAdminBootstrap(jdbc, passwords, "initial-admin", "external-bootstrap-password").run(null);

        verify(passwords, never()).encode(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
