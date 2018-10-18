/*
 * Copyright Diffblue Limited
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.classgraph.json;

import io.github.classgraph.json.ReferenceEqualityKey;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ReferenceEqualityKeyTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void equalsInputNotNullOutputFalse() {

    // Arrange
    final ReferenceEqualityKey objectUnderTest = new ReferenceEqualityKey(0);
    final ReferenceEqualityKey other = new ReferenceEqualityKey(null);

    // Act
    final boolean retval = objectUnderTest.equals(other);

    // Assert result
    Assert.assertEquals(false, retval);
  }

  @Test
  public void equalsInputNullOutputFalse() {

    // Arrange
    final ReferenceEqualityKey objectUnderTest = new ReferenceEqualityKey(null);
    final Object other = null;

    // Act
    final boolean retval = objectUnderTest.equals(other);

    // Assert result
    boolean expected = false;
	Assert.assertEquals(expected, retval);
  }

  @Test
  public void equalsInputNotNullOutputTrue() {

    // Arrange
    final ReferenceEqualityKey objectUnderTest = new ReferenceEqualityKey(null);
    final ReferenceEqualityKey other = new ReferenceEqualityKey(null);

    // Act
    final boolean retval = objectUnderTest.equals(other);

    // Assert result
    Assert.assertEquals(true, retval);
  }
}
