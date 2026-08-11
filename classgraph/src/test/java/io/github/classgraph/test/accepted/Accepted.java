package io.github.classgraph.test.accepted;

import io.github.classgraph.test.rejected.RejectedAnnotation;
import io.github.classgraph.test.rejected.RejectedInterface;
import io.github.classgraph.test.rejected.RejectedSuperclass;

/**
 * Accepted.
 */
@RejectedAnnotation
public class Accepted extends RejectedSuperclass implements RejectedInterface {
}
