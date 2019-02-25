package io.github.test.classgraph.issues.issue321;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

public class ClassInfoListTest
{

   @Test
   public void testNames()
   {
      try (ScanResult result = new ClassGraph()
         .removeTemporaryFilesAfterScan()
         .disableModuleScanning()
         .whitelistPackages(this.getClass().getPackage().getName())
         .scan())
      {
         List<String> names = Optional.ofNullable(result.getClassInfo(IFoo.class.getName()))
            .map(ClassInfo::getClassesImplementing)
            .map(ClassInfoList::getNames).orElse(Collections.emptyList());
         assertThat(names).hasSize(1).containsExactly(FooImpl.class.getName());
      }
   }

}
