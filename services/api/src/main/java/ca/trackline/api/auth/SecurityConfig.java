package ca.trackline.api.auth;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Who may call what.
 *
 * <p>Everything that reads transit data stays open, as it was: a page that works without an account must keep working.
 * Two things need an access token: the account itself and the favourites. Access to favourites is by whose token it
 * is, never by an id in the path, so there is no address with another person's data at the end of it to guess.
 *
 * <p>The API holds no session and sets no cookie, so there is nothing for a forged request from another site to
 * ride on and cross-site request protection has nothing to protect; a request carries its credential in a header a
 * browser will not add by itself. The refresh token's cookie lives in the web application, which has its own checks.
 *
 * <p>A token sent to a public address is ignored rather than checked. Otherwise an expired token left in a client
 * would turn every public read into a 401 for as long as the client kept sending it.
 */
@Configuration
public class SecurityConfig {

    /** The addresses that need a token. Everything else is open. */
    static final List<String> PROTECTED = List.of("/api/v1/auth/me", "/api/v1/favourites", "/api/v1/favourites/**");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessTokens tokens, SecurityProblems problems) throws Exception {
        RequestMatcher protectedPaths = new OrRequestMatcher(PROTECTED.stream()
                .<RequestMatcher>map(pattern -> PathPatternRequestMatcher.withDefaults().matcher(pattern))
                .toList());
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        BearerTokenResolver onlyWhereNeeded =
                request -> protectedPaths.matches(request) ? resolver.resolve(request) : null;

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(protectedPaths).authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(onlyWhereNeeded)
                        .jwt(jwt -> jwt.decoder(tokens.decoder()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                // The framework adds no-cache headers to every response by default. The existing read endpoints set their
                // own caching and must not be changed by adding a login; the auth and favourites controllers say no-store.
                .headers(headers -> headers.cacheControl(cache -> cache.disable()));
        return http.build();
    }
}
