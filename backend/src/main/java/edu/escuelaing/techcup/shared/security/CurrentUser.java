package edu.escuelaing.techcup.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the {@link AuthenticatedUser} of the current request into a controller parameter.
 * Resolved by {@link CurrentUserArgumentResolver}; fails with 401 when there is no principal,
 * unless {@link #required()} is false (public endpoints whose answer depends on who asks), in
 * which case an anonymous caller is resolved as {@code null}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {

    boolean required() default true;
}
