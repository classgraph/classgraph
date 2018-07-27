package io.github.fastclasspathscanner.test.whitelisted;

import io.github.fastclasspathscanner.test.blacklisted.BlacklistedAnnotation;
import io.github.fastclasspathscanner.test.blacklisted.BlacklistedInterface;
import io.github.fastclasspathscanner.test.blacklisted.BlacklistedSuperclass;

@BlacklistedAnnotation
public class Whitelisted extends BlacklistedSuperclass implements BlacklistedInterface {
}
