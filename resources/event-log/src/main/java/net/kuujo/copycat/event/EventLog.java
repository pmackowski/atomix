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
package net.kuujo.copycat.event;

import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.resource.PartitionContext;
import net.kuujo.copycat.resource.PartitionedResourceConfig;
import net.kuujo.copycat.resource.internal.AbstractPartitionedResource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Copycat event log.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class EventLog<K, V> extends AbstractPartitionedResource<EventLog<K, V>, EventLogPartition<K, V>> {

  public EventLog(PartitionedResourceConfig<?> config, ClusterConfig cluster) {
    super(config, cluster);
  }

  public EventLog(PartitionedResourceConfig<?> config, ClusterConfig cluster, Executor executor) {
    super(config, cluster, executor);
  }

  @Override
  protected EventLogPartition<K, V> createPartition(PartitionContext context) {
    return new EventLogPartition<>(context);
  }

  /**
   * Registers a log entry consumer.
   *
   * @param consumer The log entry consumer.
   * @return The event log.
   */
  public EventLog<K, V> consumer(EventConsumer<K, V> consumer) {
    partitions.forEach(p -> p.consumer(consumer));
    return this;
  }

  /**
   * Commits an entry to the log.
   *
   * @param entry The entry key.
   * @return The entry to commit.
   */
  public CompletableFuture<Long> commit(V entry) {
    return commit(null, entry);
  }

  /**
   * Commits an entry to the log.
   *
   * @param key The entry key.
   * @param entry The entry to commit.
   * @return A completable future to be completed once the entry has been committed.
   */
  public CompletableFuture<Long> commit(K key, V entry) {
    return partition(key).commit(key, entry);
  }

}
