/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

/** Finds the version number of FastClasspathScanner. */
public class VersionFinder {
    private static final String MAVEN_PACKAGE = "io.github.lukehutch";
    private static final String MAVEN_ARTIFACT = "fast-classpath-scanner";

    /** Finds the version number of FastClasspathScanner. */
    public synchronized static final String getVersion() {
        // Try to get version number from pom.xml (available when running in Eclipse)
        final Class<?> cls = FastClasspathScanner.class;
        try {
            final String className = cls.getName();
            final String classfileName = "/" + className.replace('.', '/') + ".class";
            final URL classpathResource = cls.getResource(classfileName);
            if (classpathResource != null) {
                final Path absolutePackagePath = Paths.get(classpathResource.toURI()).getParent();
                final int packagePathSegments = className.length() - className.replace(".", "").length();
                // Remove package segments from path
                Path path = absolutePackagePath;
                for (int i = 0, segmentsToRemove = packagePathSegments; i < segmentsToRemove && path != null; i++) {
                    path = path.getParent();
                }
                // Remove up to two more levels for "bin" or "target/classes"
                for (int i = 0; i < 3 && path != null; i++, path = path.getParent()) {
                    final Path pom = path.resolve("pom.xml");
                    try (InputStream is = Files.newInputStream(pom)) {
                        final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
                        doc.getDocumentElement().normalize();
                        String version = (String) XPathFactory.newInstance().newXPath().compile("/project/version")
                                .evaluate(doc, XPathConstants.STRING);
                        if (version != null) {
                            version = version.trim();
                            if (!version.isEmpty()) {
                                return version;
                            }
                        }
                    } catch (final IOException e) {
                        // Not found
                    }
                }
            }
        } catch (final Exception e) {
            // Ignore
        }

        // Try to get version number from maven properties in jar's META-INF directory
        try (InputStream is = cls.getResourceAsStream(
                "/META-INF/maven/" + MAVEN_PACKAGE + "/" + MAVEN_ARTIFACT + "/pom.properties")) {
            if (is != null) {
                final Properties p = new Properties();
                p.load(is);
                final String version = p.getProperty("version", "").trim();
                if (!version.isEmpty()) {
                    return version;
                }
            }
        } catch (final Exception e) {
            // Ignore
        }

        // Fallback to using Java API (version number is obtained from MANIFEST.MF)
        final Package pkg = cls.getPackage();
        if (pkg != null) {
            String version = pkg.getImplementationVersion();
            if (version == null) {
                version = "";
            }
            version = version.trim();
            if (version.isEmpty()) {
                version = pkg.getSpecificationVersion();
                if (version == null) {
                    version = "";
                }
                version = version.trim();
            }
            if (!version.isEmpty()) {
                return version;
            }
        }
        return "unknown";
    }
}
