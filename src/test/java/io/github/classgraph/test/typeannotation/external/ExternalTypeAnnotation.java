package io.github.classgraph.test.typeannotation.external;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A type annotation that is deliberately declared outside the package accepted by the scan that reads it, so that
 * no {@link io.github.classgraph.ClassInfo} object is created for it. Unlike a declaration annotation, a type
 * annotation does not cause a placeholder {@code ClassInfo} to be created for the annotation class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface ExternalTypeAnnotation {
}
