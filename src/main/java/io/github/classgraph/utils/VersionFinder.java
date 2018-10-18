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
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;

import io.github.classgraph.ClassGraph;

/** Finds the version number of ClassGraph, and the version of the JDK. */
public class VersionFinder {

    /** Java version string */
    public static final String JAVA_VERSION = System.getProperty("java.version");

    /** Java major version -- 7 for "1.7", 8 for "1.8.0_244", 9 for "9", 11 for "11-ea", etc. */
    public static final int JAVA_MAJOR_VERSION;

    static {
        int javaMajorVersion = 0;
        if (JAVA_VERSION != null) {
            for (final String versionPart : JAVA_VERSION.split("[^0-9]+")) {
                if (!versionPart.isEmpty() && !versionPart.equals("1")) {
                    javaMajorVersion = Integer.parseInt(versionPart);
                    break;
                }
            }
        }
        JAVA_MAJOR_VERSION = javaMajorVersion;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The operating system type. */
    public enum OperatingSystem {
        /** Windows */
        Windows,
        /** Mac OS X */
        MacOSX,
        /** Linux */
        Linux,
        /** Unknown */
        Unknown
    };

    /** The operating system type. */
    public static final OperatingSystem OS;

    static {
        final String osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ENGLISH);
        if ((osName.indexOf("mac") >= 0) || (osName.indexOf("darwin") >= 0)) {
            OS = OperatingSystem.MacOSX;
        } else if (osName.indexOf("win") >= 0) {
            OS = OperatingSystem.Windows;
        } else if (osName.indexOf("nux") >= 0) {
            OS = OperatingSystem.Linux;
        } else {
            OS = OperatingSystem.Unknown;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    private static final String MAVEN_PACKAGE = "io.github.classgraph";
    private static final String MAVEN_ARTIFACT = "classgraph";

    /**
     * @return the version number of ClassGraph.
     */
    public static final synchronized String getVersion() {
        // Try to get version number from pom.xml (available when running in Eclipse)
        final Class<?> cls = ClassGraph.class;
        try {
            final String className = cls.getName();
            final URL classpathResource = cls.getResource("/" + JarUtils.classNameToClassfilePath(className));
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
