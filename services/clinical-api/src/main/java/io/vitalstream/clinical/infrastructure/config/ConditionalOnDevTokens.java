package io.vitalstream.clinical.infrastructure.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Registers a bean only when the dev token endpoint may exist: the explicit opt-in {@code
 * CLINICAL_DEV_TOKENS_ENABLED=true} (no default, so a missing flag means off), {@code APP_ENV=dev}
 * and no external identity provider (JWKS). Otherwise the endpoint and its HMAC issuer are not
 * beans at all (CONTRACTS 4.2), so nothing can answer. {@link DevTokensStartupGuard} fails the boot
 * when the flag is on but the environment forbids it.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
    "'${vitalstream.dev-tokens-enabled:false}' == 'true'"
        + " && '${vitalstream.app-env:dev}' == 'dev'"
        + " && '${vitalstream.jwt.jwk-set-uri:}'.trim().isEmpty()")
public @interface ConditionalOnDevTokens {}
