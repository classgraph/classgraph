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
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph.issues.issue368;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.json.JSONDeserializer;
import nonapi.io.github.classgraph.json.JSONSerializer;

/**
 * Issue368Test.
 */
public class Issue368Test {

    /**
     * InnerClass.
     */
    public static class InnerClass {
        @SuppressWarnings("javadoc")
        public Class<Issue368Test> innerClassField = Issue368Test.class;
    }

    /**
     * Issue 368 test.
     */
    @Test
    public void issue368Test() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(Issue368Test.class.getPackage().getName())
                .enableAllInfo().scan()) {
            final String json = JSONSerializer.serializeObject(new InnerClass());
            assertThat(json)
                    .isEqualTo("{\"innerClassField\":\"io.github.classgraph.issues.issue368.Issue368Test\"}");
            final InnerClass deserialized = JSONDeserializer.deserializeObject(InnerClass.class, json);
            assertThat(deserialized.innerClassField == Issue368Test.class);
        }
    }
}
