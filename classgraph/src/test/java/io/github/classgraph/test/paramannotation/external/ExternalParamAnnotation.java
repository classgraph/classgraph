package io.github.classgraph.test.paramannotation.external;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method parameter annotation that is deliberately declared outside the package accepted by the scan that reads
 * it, so that the scan has to be extended upwards to read its classfile.
 */
@ParamMetaAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ExternalParamAnnotation {
}
