package com.lyreo.identity.infrastructure.keycloak;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import com.lyreo.identity.application.port.LearnerAccountProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public class KeycloakLearnerAccounts implements LearnerAccountProvider {
    private final RestClient http;
    private final String realmUrl;
    private final String adminUrl;
    private final String secret;

    public KeycloakLearnerAccounts(
        @Value("${lyreo.identity.admin-realm-url:}") String realmUrl,
        @Value("${lyreo.identity.admin-client-secret:}") String secret
    ) {
        this.realmUrl = realmUrl.strip().replaceFirst("/+$", "");
        this.secret = secret;
        if (configured()) {
            URI uri = URI.create(this.realmUrl);
            if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches(".*/realms/[^/]+")) {
                throw new IllegalArgumentException("Keycloak admin realm URL must be an HTTP(S) realm URL");
            }
            int realmPath = this.realmUrl.lastIndexOf("/realms/");
            this.adminUrl = this.realmUrl.substring(0, realmPath) + "/admin" + this.realmUrl.substring(realmPath);
        } else {
            this.adminUrl = "";
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean configured() {
        return !secret.isBlank() && !realmUrl.isBlank();
    }

    @Override
    public Optional<Connection> connect(UUID subject) {
        try {
            String token = token();
            String userPath = "/users/" + subject;
            JsonNode user = read(token, userPath);
            Set<String> roles = roles(token, userPath);
            if (!roles.contains("LEARNER") || roles.contains("ADMIN") || user.hasNonNull("serviceAccountClientId")
                || groupAdministrator(token, userPath)) {
                return Optional.empty();
            }
            return Optional.of(new Session(token, userPath, user.path("enabled").asBoolean(false)));
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return Optional.empty();
            throw unavailable();
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    private String token() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        JsonNode token = http.post().uri(realmUrl + "/protocol/openid-connect/token")
            .headers(headers -> headers.setBasicAuth("lyreo-core-service", secret))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
            .retrieve().body(JsonNode.class);
        if (token == null || token.path("access_token").asString("").isBlank()) throw unavailable();
        return token.path("access_token").asString();
    }

    private JsonNode read(String token, String path) {
        JsonNode body = http.get().uri(adminUrl + path)
            .headers(headers -> headers.setBearerAuth(token)).retrieve().body(JsonNode.class);
        if (body == null) throw unavailable();
        return body;
    }

    private Set<String> roles(String token, String path) {
        var result = new HashSet<String>();
        read(token, path + "/role-mappings/realm/composite").forEach(role -> result.add(role.path("name").asString()));
        return result;
    }

    private boolean groupAdministrator(String token, String userPath) {
        var checked = new HashSet<UUID>();
        for (int first = 0; ; first += 100) {
            JsonNode groups = read(token, userPath + "/groups?first=" + first + "&max=100");
            for (JsonNode group : groups) {
                String id = group.path("id").asString("");
                while (!id.isBlank() && checked.add(UUID.fromString(id))) {
                    String path = "/groups/" + id;
                    if (roles(token, path).contains("ADMIN")) return true;
                    id = read(token, path).path("parentId").asString("");
                }
            }
            if (groups.size() < 100) return false;
        }
    }

    private class Session implements Connection {
        private final String token;
        private final String path;
        private final boolean enabled;

        Session(String token, String path, boolean enabled) {
            this.token = token;
            this.path = path;
            this.enabled = enabled;
        }

        @Override public boolean enabled() { return enabled; }

        @Override public void enable(boolean enabled) {
            put(path, Map.of("enabled", enabled), false);
        }

        @Override public void endSessions() {
            try {
                http.post().uri(adminUrl + path + "/logout").headers(headers -> headers.setBearerAuth(token))
                    .retrieve().toBodilessEntity();
            } catch (RestClientException failure) {
                throw unavailable();
            }
        }

        @Override public void resetPassword(String password, boolean changeOnNextLogin) {
            put(path + "/reset-password",
                new Credential("password", password, changeOnNextLogin), true);
        }

        private void put(String target, Object data, boolean passwordReset) {
            try {
                http.put().uri(adminUrl + target).headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).body(data).retrieve().toBodilessEntity();
            } catch (RestClientResponseException failure) {
                if (passwordReset && failure.getStatusCode().value() == 400) {
                    throw new RequestValidationException("The password was rejected by the Keycloak password policy");
                }
                throw unavailable();
            } catch (RestClientException failure) {
                throw unavailable();
            }
        }
    }

    private record Credential(String type, String value, boolean temporary) {
        @Override public String toString() { return "Credential[redacted]"; }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("KEYCLOAK_ACCOUNT_UNAVAILABLE", "Keycloak account operation is unavailable", null);
    }
}
