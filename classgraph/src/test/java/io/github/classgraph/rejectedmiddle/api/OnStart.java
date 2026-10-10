package io.github.classgraph.rejectedmiddle.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method annotation, on a public method that an accepted class inherits from a superclass in a rejected package.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnStart {
}
