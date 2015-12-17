package io.github.lukehutch.fastclasspathscanner.test.whitelisted;

import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedAnnotation;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedInterface;
import io.github.lukehutch.fastclasspathscanner.test.blacklisted.BlacklistedSuperclass;

@BlacklistedAnnotation
public class Whitelisted extends BlacklistedSuperclass implements BlacklistedInterface {
}
