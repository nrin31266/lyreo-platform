package com.lyreo.platform.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Maps Keycloak realm_access.roles to Spring ROLE_* authorities while preserving OAuth scopes. */
public final class KeycloakJwtAuthenticationConverter implements Converter<Jwt, JwtAuthenticationToken> {
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Collection<GrantedAuthority> scopeAuthorities = scopes.convert(jwt);
        if (scopeAuthorities != null) authorities.addAll(scopeAuthorities);

        Object rawRealmAccess = jwt.getClaims().get("realm_access");
        if (rawRealmAccess instanceof Map<?, ?> realmAccess) {
            Object rawRoles = realmAccess.get("roles");
            if (rawRoles instanceof Collection<?> roles) {
                roles.stream()
                    .map(String::valueOf)
                    .filter(role -> !role.isBlank())
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
            }
        }
        String principal = jwt.getClaimAsString("preferred_username");
        if (principal == null || principal.isBlank()) principal = jwt.getSubject();
        return new JwtAuthenticationToken(jwt, List.copyOf(authorities), principal);
    }
}
