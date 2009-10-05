/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.Config;
import se.scalablesolutions.akka.config.*;
import static se.scalablesolutions.akka.config.JavaConfig.*;
import se.scalablesolutions.akka.actor.*;
import se.scalablesolutions.akka.Kernel;
import junit.framework.TestCase;

public class InMemNestedStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectManager conf = new ActiveObjectManager();
  final private ActiveObjectFactory factory = new ActiveObjectFactory();

  protected void setUp() {
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[]{
            // FIXME: remove string-name, add ctor to only accept target class
            new Component(InMemStateful.class, new LifeCycle(new Permanent(), 1000), 10000000),
            new Component(InMemStatefulNested.class, new LifeCycle(new Permanent(), 1000), 10000000),
            new Component(InMemFailer.class, new LifeCycle(new Permanent(), 1000), 1000)
            //new Component("inmem-clasher", InMemClasher.class, InMemClasherImpl.class, new LifeCycle(new Permanent(), 1000), 100000)
        }).inject().supervise();
    Config.config();
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.init();
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.init();
  }

  protected void tearDown() {
    conf.stop();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    System.out.println("-- BACK --");
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
    assertEquals("new state", nested.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    assertEquals("new state", stateful.getVectorState());
    assertEquals("new state", nested.getVectorState());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setVectorState("init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getVectorState()); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getVectorState()); // check that state is == init state
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    stateful.setRefState("init"); // set init state
    nested.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    assertEquals("new state", stateful.getRefState());
    assertEquals("new state", nested.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    stateful.setRefState("init"); // set init state
    Thread.sleep(100);
    nested.setRefState("init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getRefState()); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getRefState()); // check that state is == init state
  }
}
