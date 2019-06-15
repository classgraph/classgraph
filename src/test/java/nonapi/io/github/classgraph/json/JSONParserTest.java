package nonapi.io.github.classgraph.json;

import nonapi.io.github.classgraph.types.ParseException;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class JSONParserTest {
    @Test
    public void test1() throws ParseException {
        JSONParser.parseJSON("{\"doubleValue\":-2.147483648}");
    }

    @Test
    public void test2() throws ParseException {
        JSONParser.parseJSON("{\"doubleValue\":-2.147483648E9}");
    }

}