/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch;

import sun.tools.tree.FinallyStatement;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

abstract class AbstractPromise {
    private volatile Object _ref = FState.apply();
    protected final static AtomicReferenceFieldUpdater<AbstractPromise, Object> updater =
            AtomicReferenceFieldUpdater.newUpdater(AbstractPromise.class, Object.class, "_ref");
}
