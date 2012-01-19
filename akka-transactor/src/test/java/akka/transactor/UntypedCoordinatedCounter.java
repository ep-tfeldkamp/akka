/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import akka.actor.ActorRef;
import akka.actor.Actors;
import akka.actor.UntypedActor;
import scala.concurrent.stm.Ref;
import scala.concurrent.stm.japi.Stm;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCoordinatedCounter extends UntypedActor {
    private String name;
    private Ref.View<Integer> count = Stm.newRef(0);

    public UntypedCoordinatedCounter(String name) {
        this.name = name;
    }

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                List<ActorRef> friends = increment.getFriends();
                final CountDownLatch latch = increment.getLatch();
                final Runnable countDown = new Runnable() {
                    public void run() {
                        latch.countDown();
                    }
                };
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).tell(coordinated.coordinate(coordMessage));
                }
                coordinated.atomic(new Runnable() {
                    public void run() {
                        Stm.increment(count, 1);
                        Stm.afterCompletion(countDown);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.get());
        }
    }
}
