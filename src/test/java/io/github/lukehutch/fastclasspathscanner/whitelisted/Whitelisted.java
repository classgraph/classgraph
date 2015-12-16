package io.github.lukehutch.fastclasspathscanner.whitelisted;

import io.github.lukehutch.fastclasspathscanner.blacklisted.BlacklistedAnnotation;
import io.github.lukehutch.fastclasspathscanner.blacklisted.BlacklistedInterface;
import io.github.lukehutch.fastclasspathscanner.blacklisted.BlacklistedSuperclass;

@BlacklistedAnnotation
public class Whitelisted extends BlacklistedSuperclass implements BlacklistedInterface {
}
