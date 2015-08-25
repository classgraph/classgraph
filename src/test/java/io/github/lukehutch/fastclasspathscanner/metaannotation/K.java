package io.github.lukehutch.fastclasspathscanner.metaannotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@M
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface K {
}
