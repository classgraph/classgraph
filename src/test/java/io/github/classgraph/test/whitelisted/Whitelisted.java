package io.github.classgraph.test.whitelisted;

import io.github.classgraph.test.blacklisted.BlacklistedAnnotation;
import io.github.classgraph.test.blacklisted.BlacklistedInterface;
import io.github.classgraph.test.blacklisted.BlacklistedSuperclass;

/**
 * Whitelisted.
 */
@BlacklistedAnnotation
public class Whitelisted extends BlacklistedSuperclass implements BlacklistedInterface {
}
