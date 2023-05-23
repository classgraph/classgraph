package io.github.classgraph.issues.issue772;

import java.io.Closeable;

/**
 * An interface overriding another, its methods should always be 'preferred' before methods from Closeable
 */
public interface MyCloseable extends Closeable {

    //override close without the exception, good candidate for a default method in JDK8+
    @Override
    void close();
}
