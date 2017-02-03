package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.framework.util.StringMap;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader 
 *
 * The handler adds the bundle jar and all assocaited Bundle-Claspath jars into the {@link ClasspathFinder} scan classpath.
 * 
 * @author eelrufaie
 */
public class FelixClassLoaderHandler implements ClassLoaderHandler {

	private final String JAR_FILE_PREFIX = "jar:";
	
	private final String JAR_FILE_DELIM = "!/";

	private static final String BY_COMMA = ",";

	@Override
	public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder, final LogNode log) throws Exception {

		for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
			if ("org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5".equals(c.getName())) {
				// type: BundleImpl
				final Object m_wiring = ReflectionUtils.getFieldVal(classloader, "m_wiring");
				// type: Bundle
				Object bundle = ReflectionUtils.invokeMethod(m_wiring, "getBundle");

				@SuppressWarnings("unchecked")
				Map<String, StringMap> bundleHeaders = (Map<String, StringMap>) ReflectionUtils.getFieldVal(bundle, "m_cachedHeaders");
				
				Object bundlefile = ReflectionUtils.getFieldVal(bundle, "m_archive");
				
				final Object bundlefileLocation = ReflectionUtils.getFieldVal(bundlefile, "m_originalLocation");
				//is valid jar (ends with .jar). "file:/"

				if (MapUtils.isNotEmpty(bundleHeaders)) {
					//add bundleFile 
					String bundleFile = (String) bundlefileLocation;
					classpathFinder.addClasspathElement(bundleFile, log);
					
					// bundleHeaders.values().stream()
					Iterator<Entry<String, StringMap>> it = bundleHeaders.entrySet().iterator();
					//should find one element only.
					if (it.hasNext()) {
						Entry<String, StringMap> pair = (Entry<String, StringMap>) it.next();

						Map stringMap = (Map) pair.getValue();
						// type String
						String classpath = (String) stringMap.get("Bundle-Classpath");
						//if we have additional jars in classpath, lets add them all...
						if (StringUtils.isNoneBlank(classpath)) { 

							String[] splittedJars = classpath.split(BY_COMMA);
							for (int i = 0; i < splittedJars.length; i++) { 
								//should be something like this: jar:file:/path/myBundleFile.jar!/v4-sdk-schema-1.4.0-develop.v12.jar
								String jarPath = JAR_FILE_PREFIX + bundleFile + JAR_FILE_DELIM + splittedJars[i];
								classpathFinder.addClasspathElement(jarPath, log);
							}
						}
					}

					return true;
				}
			}
		}
		return false;
	}
}