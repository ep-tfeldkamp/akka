/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.japi.pf;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.PartialFunction;

import static org.junit.Assert.*;

public class PFBuilderTest extends JUnitSuite {

  @Test
  public void pfbuilder_matchAny_should_infer_declared_input_type_for_lambda() {
    PartialFunction<String,Integer> pf = new PFBuilder<String,Integer>()
      .matchEquals("hello", new FI.Apply<String, Integer>() {
        @Override
        public Integer apply(String s) throws Exception {
          return 1;
        }
      })
      .matchAny(new FI.Apply<String, Integer>() {
        @Override
        public Integer apply(String s) throws Exception {
          return Integer.valueOf(s);
        }
      })
      .build();
      
    assertTrue(pf.isDefinedAt("hello"));
    assertTrue(pf.isDefinedAt("42"));
    assertEquals(42, pf.apply("42").intValue());
  }
}
