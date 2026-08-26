package io.github.classgraph.test.paramannotation.external;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A meta-annotation on {@link ExternalParamAnnotation}, which is only visible if that annotation is scanned. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ParamMetaAnnotation {
}
