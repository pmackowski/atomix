/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.test;

import net.jodah.concurrentunit.ConcurrentTestCase;
import net.kuujo.copycat.cluster.Cluster;
import net.kuujo.copycat.cluster.MembershipEvent;
import net.kuujo.copycat.resource.Resource;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base resource test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public abstract class ResourceTest<T extends Resource<T>> extends ConcurrentTestCase {

  /**
   * Creates a test cluster.
   */
  protected abstract TestCluster<T> createCluster();

  /**
   * Tests that an active member receives notification of a passive member joining the cluster.
   */
  public void testPassiveMemberJoinActiveMember() throws Throwable {
    TestCluster<T> test = createCluster();
    T active = test.activeResources().iterator().next();
    T passive = test.passiveResources().iterator().next();

    expectResume();
    Cluster cluster = active.cluster();
    cluster.addMembershipListener(event -> {
      if (event.member().uri().equals(passive.cluster().member().uri())) {
        threadAssertTrue(event.type() == MembershipEvent.Type.JOIN);
        resume();
      }
    });

    test.open();
    await();
  }

  /**
   * Tests that a passive member receives notification of a passive member joining the cluster.
   */
  public void testPassiveMemberJoinPassiveMember() throws Throwable {
    TestCluster<T> test = createCluster();
    Iterator<T> iterator = test.passiveResources().iterator();
    T passive1 = iterator.next();
    T passive2 = iterator.next();

    expectResume();
    Cluster cluster = passive1.cluster();
    cluster.addMembershipListener(event -> {
      if (event.member().uri().equals(passive2.cluster().member().uri())) {
        threadAssertTrue(event.type() == MembershipEvent.Type.JOIN);
        resume();
      }
    });

    test.open();
    await();
  }

  /**
   * Tests that an active member receives notification of a passive member leaving the cluster.
   */
  public void testPassiveMemberLeaveActiveMember() throws Throwable {
    TestCluster<T> test = createCluster();
    T active = test.activeResources().iterator().next();
    T passive = test.passiveResources().iterator().next();

    AtomicBoolean joined = new AtomicBoolean();
    expectResume();
    Cluster cluster = active.cluster();
    cluster.addMembershipListener(event -> {
      if (event.type() == MembershipEvent.Type.JOIN && event.member().uri().equals(passive.cluster().member().uri())) {
        threadAssertTrue(joined.compareAndSet(false, true));
      } else if (event.type() == MembershipEvent.Type.LEAVE && event.member().uri().equals(passive.cluster().member().uri())) {
        threadAssertTrue(joined.get());
        resume();
      }
    });

    test.open().thenRun(passive::close);
    await(10000);
  }

  /**
   * Tests that a passive member receives notification of a passive member leaving the cluster.
   */
  public void testPassiveMemberLeavePassiveMember() throws Throwable {
    TestCluster<T> test = createCluster();
    Iterator<T> iterator = test.passiveResources().iterator();
    T passive1 = iterator.next();
    T passive2 = iterator.next();

    AtomicBoolean joined = new AtomicBoolean();
    expectResume();
    Cluster cluster = passive1.cluster();
    cluster.addMembershipListener(event -> {
      System.out.println("EVENT " + event.type() + " " + event.member().uri() + " " + passive2.cluster().member().uri());
      if (event.type() == MembershipEvent.Type.JOIN && event.member().uri().equals(passive2.cluster().member().uri())) {
        threadAssertTrue(joined.compareAndSet(false, true));
      } else if (event.type() == MembershipEvent.Type.LEAVE && event.member().uri().equals(passive2.cluster().member().uri())) {
        threadAssertTrue(joined.get());
        resume();
      }
    });

    test.open().thenRun(passive2::close);
    await(10000);
  }

}
