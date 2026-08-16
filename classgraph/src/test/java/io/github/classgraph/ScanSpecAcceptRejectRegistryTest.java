/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.utils.AcceptReject;

/**
 * {@link ScanSpec#sortPrefixes()} sorts the accept/reject criteria that registered themselves in the spec's
 * constructor. This checks that every criterion the spec declares did register, since one that did not would never
 * have its prefixes sorted, and would then match the wrong paths.
 */
class ScanSpecAcceptRejectRegistryTest {
    /**
     * Read the accept/reject criteria that a spec declares as fields.
     *
     * @param spec
     *            the spec.
     * @return the criteria.
     * @throws ReflectiveOperationException
     *             if a field could not be read.
     */
    private static List<AcceptReject> declaredCriteria(final ScanSpec spec) throws ReflectiveOperationException {
        final List<AcceptReject> declared = new ArrayList<>();
        for (final Field field : spec.getClass().getDeclaredFields()) {
            if (AcceptReject.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                declared.add((AcceptReject) field.get(spec));
            }
        }
        return declared;
    }

    /**
     * Read the accept/reject criteria that registered themselves with a spec.
     *
     * @param spec
     *            the spec.
     * @return the criteria.
     * @throws ReflectiveOperationException
     *             if the registry could not be read.
     */
    @SuppressWarnings("unchecked")
    private static List<AcceptReject> registeredCriteria(final ScanSpec spec) throws ReflectiveOperationException {
        final var field = spec.getClass().getDeclaredField("acceptRejects");
        field.setAccessible(true);
        return (List<AcceptReject>) field.get(spec);
    }

    /** Every accept/reject criterion that {@link ScanSpec} declares registered itself. */
    @Test
    void everyScanSpecCriterionIsRegistered() throws ReflectiveOperationException {
        final var scanSpec = new ScanSpec();
        assertThat(registeredCriteria(scanSpec)).containsExactlyInAnyOrderElementsOf(declaredCriteria(scanSpec));
    }
}
