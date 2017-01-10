package io.github.lukehutch.fastclasspathscanner.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ScanSpecTest {

	@Test
	public void testConcreteJars() {
		ScanSpec scanSpec = new ScanSpec(new String[]{"jar:abc.jar"}, null);
		assertThat(scanSpec.scanDirs).as("specifying concrete jar not excludes dirs").isTrue(); 
	}
	
	@Test
	public void testOnlyJars() {
		ScanSpec scanSpec = new ScanSpec(new String[]{"jar:"}, null);
		assertThat(scanSpec.scanDirs).as("jar only exclude dirs").isFalse();
	}
	
	@Test
	public void testConcreteJarsAndNoDirs() {
		ScanSpec scanSpec = new ScanSpec(new String[]{"jar:abc.jar","-dir:"}, null);
		assertThat(scanSpec.scanDirs).as("no dirs exclude dirs").isFalse(); 
	}



}
