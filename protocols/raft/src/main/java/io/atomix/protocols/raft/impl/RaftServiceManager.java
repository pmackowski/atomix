/*
 * Copyright 2015-present Open Networking Foundation
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
package io.atomix.protocols.raft.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;
import io.atomix.cluster.MemberId;
import io.atomix.primitive.PrimitiveId;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.operation.OperationId;
import io.atomix.primitive.operation.PrimitiveOperation;
import io.atomix.primitive.service.PrimitiveService;
import io.atomix.primitive.session.SessionId;
import io.atomix.protocols.raft.RaftException;
import io.atomix.protocols.raft.RaftServer;
import io.atomix.protocols.raft.ReadConsistency;
import io.atomix.protocols.raft.protocol.SessionMetadata;
import io.atomix.protocols.raft.service.RaftServiceContext;
import io.atomix.protocols.raft.session.RaftSession;
import io.atomix.protocols.raft.storage.log.RaftLog;
import io.atomix.protocols.raft.storage.log.RaftLogEntry;
import io.atomix.protocols.raft.storage.log.RaftLogReader;
import io.atomix.protocols.raft.storage.snapshot.ServiceSnapshot;
import io.atomix.protocols.raft.storage.snapshot.Snapshot;
import io.atomix.storage.StorageLevel;
import io.atomix.storage.journal.Indexed;
import io.atomix.utils.concurrent.ComposableFuture;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.concurrent.OrderedFuture;
import io.atomix.utils.concurrent.ThreadContext;
import io.atomix.utils.concurrent.ThreadContextFactory;
import io.atomix.utils.config.ConfigurationException;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import io.atomix.utils.time.WallClockTimestamp;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Internal server state machine.
 * <p>
 * The internal state machine handles application of commands to the user provided {@link PrimitiveService} and keeps
 * track of internal state like sessions and the various indexes relevant to log compaction.
 */
public class RaftServiceManager implements AutoCloseable {
  private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(10);
  private static final Duration SNAPSHOT_COMPLETION_DELAY = Duration.ofSeconds(10);
  private static final Duration COMPACT_DELAY = Duration.ofSeconds(10);

  private static final int SEGMENT_BUFFER_FACTOR = 5;

  private final Logger logger;
  private final RaftContext raft;
  private final ThreadContext stateContext;
  private final ThreadContextFactory threadContextFactory;
  private final RaftLog log;
  private final RaftLogReader reader;
  private final Map<Long, CompletableFuture> futures = Maps.newHashMap();
  private volatile CompletableFuture<Void> compactFuture;
  private long lastEnqueued;
  private long lastCompacted;

  public RaftServiceManager(RaftContext raft, ThreadContext stateContext, ThreadContextFactory threadContextFactory) {
    this.raft = checkNotNull(raft, "state cannot be null");
    this.log = raft.getLog();
    this.reader = log.openReader(1, RaftLogReader.Mode.COMMITS);
    this.stateContext = stateContext;
    this.threadContextFactory = threadContextFactory;
    this.logger = ContextualLoggerFactory.getLogger(getClass(), LoggerContext.builder(RaftServer.class)
        .addValue(raft.getName())
        .build());
    this.lastEnqueued = reader.getFirstIndex() - 1;
    scheduleSnapshots();
  }

  /**
   * Returns the service thread context.
   *
   * @return the service thread context
   */
  public ThreadContext executor() {
    return stateContext;
  }

  /**
   * Returns a boolean indicating whether the node is running out of disk space.
   */
  private boolean isRunningOutOfDiskSpace() {
    // If there's not enough space left to allocate two log segments
    return raft.getStorage().statistics().getUsableSpace() < raft.getStorage().maxLogSegmentSize() * SEGMENT_BUFFER_FACTOR
        // Or the used disk percentage has surpassed the free disk buffer percentage
        || raft.getStorage().statistics().getUsableSpace() / (double) raft.getStorage().statistics().getTotalSpace() < raft.getStorage().freeDiskBuffer();
  }

  /**
   * Returns a boolean indicating whether the node is running out of memory.
   */
  private boolean isRunningOutOfMemory() {
    StorageLevel level = raft.getStorage().storageLevel();
    if (level == StorageLevel.MEMORY || level == StorageLevel.MAPPED) {
      long freeMemory = raft.getStorage().statistics().getFreeMemory();
      long totalMemory = raft.getStorage().statistics().getTotalMemory();
      if (freeMemory > 0 && totalMemory > 0) {
        return freeMemory / (double) totalMemory < raft.getStorage().freeMemoryBuffer();
      }
    }
    return false;
  }

  /**
   * Schedules a snapshot iteration.
   */
  private void scheduleSnapshots() {
    raft.getThreadContext().schedule(SNAPSHOT_INTERVAL, () -> takeSnapshots(true, false));
  }

  /**
   * Compacts Raft logs.
   *
   * @return a future to be completed once logs have been compacted
   */
  public CompletableFuture<Void> compact() {
    return takeSnapshots(false, true);
  }

  /**
   * Takes a snapshot of all services and compacts logs if the server is not under high load or disk needs to be freed.
   */
  private CompletableFuture<Void> takeSnapshots(boolean rescheduleAfterCompletion, boolean force) {
    // If compaction is already in progress, return the existing future and reschedule if this is a scheduled compaction.
    if (compactFuture != null) {
      if (rescheduleAfterCompletion) {
        compactFuture.whenComplete((r, e) -> scheduleSnapshots());
      }
      return compactFuture;
    }

    long lastApplied = raft.getLastApplied();

    // Only take snapshots if segments can be removed from the log below the lastApplied index.
    if (raft.getLog().isCompactable(lastApplied) && raft.getLog().getCompactableIndex(lastApplied) > lastCompacted) {

      // Determine whether the node is running out of disk space.
      boolean runningOutOfDiskSpace = isRunningOutOfDiskSpace();

      // Determine whether the node is running out of memory.
      boolean runningOutOfMemory = isRunningOutOfMemory();

      // If compaction is not already being forced...
      if (!force
          // And the node isn't running out of memory (we need to free up memory if it is)...
          && !runningOutOfMemory
          // And dynamic compaction is enabled (we need to compact immediately if it's disabled)...
          && raft.getStorage().dynamicCompaction()
          // And the node isn't running out of disk space (we need to compact immediately if it is)...
          && !runningOutOfDiskSpace
          // And the server is under high load (we can skip compaction at this point)...
          && raft.getLoadMonitor().isUnderHighLoad()) {
        // We can skip taking a snapshot for now.
        logger.debug("Skipping compaction due to high load");
        if (rescheduleAfterCompletion) {
          scheduleSnapshots();
        }
        return CompletableFuture.completedFuture(null);
      }

      logger.debug("Snapshotting services");

      // Update the index at which the log was last compacted.
      this.lastCompacted = lastApplied;

      // We need to ensure that callbacks added to the compaction future are completed in the order in which they
      // were added in order to preserve the order of retries when appending to the log.
      compactFuture = new OrderedFuture<>();

      // Wait for snapshots in all state machines to be completed before compacting the log at the last applied index.
      takeSnapshots().whenComplete((snapshot, error) -> {
        if (error == null) {
          scheduleCompletion(snapshot);
        }
      });

      // Reschedule snapshots after completion if necessary.
      if (rescheduleAfterCompletion) {
        compactFuture.whenComplete((r, e) -> scheduleSnapshots());
      }
      return compactFuture;
    }
    // Otherwise, if the log can't be compacted anyways, just reschedule snapshots.
    else {
      if (rescheduleAfterCompletion) {
        scheduleSnapshots();
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Takes and persists snapshots of provided services.
   *
   * @return future to be completed once all snapshots have been completed
   */
  private CompletableFuture<Snapshot> takeSnapshots() {
    ComposableFuture<Snapshot> future = new ComposableFuture<>();
    stateContext.execute(() -> {
      try {
        future.complete(snapshot());
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  /**
   * Schedules a completion check for the snapshot at the given index.
   *
   * @param snapshot the snapshot to complete
   */
  private void scheduleCompletion(Snapshot snapshot) {
    stateContext.schedule(SNAPSHOT_COMPLETION_DELAY, () -> {
      if (completeSnapshot(snapshot.index())) {
        logger.debug("Completing snapshot {}", snapshot.index());
        try {
          snapshot.complete();
        } catch (IOException e) {
          logger.error("Failed to persist snapshot", e);
        }
        // If log compaction is being forced, immediately compact the logs.
        if (!raft.getLoadMonitor().isUnderHighLoad() || isRunningOutOfDiskSpace() || isRunningOutOfMemory()) {
          compactLogs(snapshot.index());
        } else {
          scheduleCompaction(snapshot.index());
        }
      } else {
        scheduleCompletion(snapshot);
      }
    });
  }

  /**
   * Schedules a log compaction.
   *
   * @param lastApplied the last applied index at the start of snapshotting. This represents the highest index
   *                    before which segments can be safely removed from disk
   */
  private void scheduleCompaction(long lastApplied) {
    // Schedule compaction after a randomized delay to discourage snapshots on multiple nodes at the same time.
    logger.trace("Scheduling compaction in {}", COMPACT_DELAY);
    stateContext.schedule(COMPACT_DELAY, () -> compactLogs(lastApplied));
  }

  /**
   * Compacts logs up to the given index.
   *
   * @param compactIndex the index to which to compact logs
   */
  private void compactLogs(long compactIndex) {
    raft.getThreadContext().execute(() -> {
      logger.debug("Compacting logs up to index {}", compactIndex);
      try {
        raft.getLog().compact(compactIndex);
      } catch (Exception e) {
        logger.error("An exception occurred during log compaction: {}", e);
      } finally {
        this.compactFuture.complete(null);
        this.compactFuture = null;
        // Immediately attempt to take new snapshots since compaction is already run after a time interval.
        takeSnapshots(false, false);
      }
    });
  }

  /**
   * Applies all commits up to the given index.
   * <p>
   * Calls to this method are assumed not to expect a result. This allows some optimizations to be made internally since
   * linearizable events don't have to be waited to complete the command.
   *
   * @param index The index up to which to apply commits.
   */
  public void applyAll(long index) {
    enqueueBatch(index);
  }

  /**
   * Applies the entry at the given index to the state machine.
   * <p>
   * Calls to this method are assumed to expect a result. This means linearizable session events triggered by the
   * application of the command at the given index will be awaited before completing the returned future.
   *
   * @param index The index to apply.
   * @return A completable future to be completed once the commit has been applied.
   */
  @SuppressWarnings("unchecked")
  public <T> CompletableFuture<T> apply(long index) {
    CompletableFuture<T> future = futures.computeIfAbsent(index, i -> new CompletableFuture<T>());
    enqueueBatch(index);
    return future;
  }

  /**
   * Applies all entries up to the given index.
   *
   * @param index the index up to which to apply entries
   */
  private void enqueueBatch(long index) {
    while (lastEnqueued < index) {
      enqueueIndex(++lastEnqueued);
    }
  }

  /**
   * Enqueues an index to be applied to the state machine.
   *
   * @param index the index to be applied to the state machine
   */
  private void enqueueIndex(long index) {
    raft.getThreadContext().execute(() -> applyIndex(index));
  }

  /**
   * Applies the next entry in the log up to the given index.
   *
   * @param index the index up to which to apply the entry
   */
  @SuppressWarnings("unchecked")
  private void applyIndex(long index) {
    // Apply entries prior to this entry.
    if (reader.hasNext() && reader.getNextIndex() == index) {
      // Read the entry from the log. If the entry is non-null then apply it, otherwise
      // simply update the last applied index and return a null result.
      Indexed<RaftLogEntry> entry = reader.next();
      try {
        if (entry.index() != index) {
          throw new IllegalStateException("inconsistent index applying entry " + index + ": " + entry);
        }
        CompletableFuture future = futures.remove(index);
        apply(entry).whenComplete((r, e) -> {
          raft.setLastApplied(index);
          if (future != null) {
            if (e == null) {
              future.complete(r);
            } else {
              future.completeExceptionally(e);
            }
          }
        });
      } catch (Exception e) {
        logger.error("Failed to apply {}: {}", entry, e);
      }
    } else {
      CompletableFuture future = futures.remove(index);
      if (future != null) {
        logger.error("Cannot apply index " + index);
        future.completeExceptionally(new IndexOutOfBoundsException("Cannot apply index " + index));
      }
    }
  }

  /**
   * Applies an entry to the state machine.
   * <p>
   * Calls to this method are assumed to expect a result. This means linearizable session events triggered by the
   * application of the given entry will be awaited before completing the returned future.
   *
   * @param entry The entry to apply.
   * @return A completable future to be completed with the result.
   */
  @SuppressWarnings("unchecked")
  public <T> CompletableFuture<T> apply(Indexed<RaftLogEntry> entry) {
    CompletableFuture<T> future = new CompletableFuture<>();
    stateContext.execute(() -> {
      logger.trace("Applying {}", entry);
      try {
        if (entry.entry().hasQuery()) {
          applyQuery(entry).whenComplete((r, e) -> {
            if (e != null) {
              future.completeExceptionally(e);
            } else {
              future.complete((T) r);
            }
          });
        } else {
          // Get the current snapshot. If the snapshot is for a higher index then skip this operation.
          // If the snapshot is for the prior index, install it.
          Snapshot snapshot = raft.getSnapshotStore().getCurrentSnapshot();
          if (snapshot != null) {
            if (snapshot.index() >= entry.index()) {
              future.complete(null);
              return;
            } else if (snapshot.index() == entry.index() - 1) {
              install(snapshot);
            }
          }

          if (entry.entry().hasCommand()) {
            future.complete((T) applyCommand(entry));
          } else if (entry.entry().hasOpenSession()) {
            future.complete((T) (Long) applyOpenSession(entry));
          } else if (entry.entry().hasKeepAlive()) {
            future.complete((T) applyKeepAlive(entry));
          } else if (entry.entry().hasCloseSession()) {
            applyCloseSession(entry);
            future.complete(null);
          } else if (entry.entry().hasMetadata()) {
            future.complete((T) applyMetadata(entry));
          } else if (entry.entry().hasInitialize()) {
            future.complete((T) applyInitialize(entry));
          } else if (entry.entry().hasConfiguration()) {
            future.complete((T) applyConfiguration(entry));
          } else {
            future.completeExceptionally(new RaftException.ProtocolException("Unknown entry type"));
          }
        }
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  /**
   * Takes snapshots for the given index.
   */
  Snapshot snapshot() {
    Snapshot snapshot = raft.getSnapshotStore().newSnapshot(raft.getLastApplied(), new WallClockTimestamp());
    try (OutputStream output = snapshot.openOutputStream()) {
      for (RaftServiceContext service : raft.getServices()) {
        snapshotService(output, service);
      }
    } catch (IOException e) {
      snapshot.close();
      logger.error("Failed to snapshot services", e);
    }
    return snapshot;
  }

  /**
   * Takes a snapshot of the given service.
   *
   * @param output  the snapshot output
   * @param service the service to snapshot
   */
  private void snapshotService(OutputStream output, RaftServiceContext service) {
    try {
      service.takeSnapshot().writeDelimitedTo(output);
    } catch (IOException e) {
      logger.error("Failed to snapshot service {}", service.serviceName(), e);
    }
  }

  /**
   * Prepares sessions for the given index.
   *
   * @param snapshot the snapshot to install
   */
  void install(Snapshot snapshot) {
    logger.debug("Installing snapshot {}", snapshot);
    try (InputStream input = snapshot.openInputStream()) {
      while (input.available() > 0) {
        installService(input);
      }
    } catch (IOException e) {
      logger.error("Failed to read snapshot", e);
    }
  }

  /**
   * Restores the service associated with the given snapshot.
   *
   * @param input the snapshot input
   */
  private void installService(InputStream input) throws IOException {
    ServiceSnapshot snapshot = ServiceSnapshot.parseDelimitedFrom(input);
    try {
      RaftServiceContext service = initializeService(
          PrimitiveId.from(snapshot.getId()),
          raft.getPrimitiveTypes().getPrimitiveType(snapshot.getType()),
          snapshot.getName());
      if (service != null) {
        try {
          service.installSnapshot(snapshot);
        } catch (Exception e) {
          logger.error("Failed to install snapshot for service {}", snapshot.getName(), e);
        }
      }
    } catch (ConfigurationException e) {
      logger.error(e.getMessage(), e);
    }
  }

  /**
   * Determines whether to complete the snapshot at the given index.
   *
   * @param index the index of the snapshot to complete
   * @return whether to complete the snapshot at the given index
   */
  private boolean completeSnapshot(long index) {
    // Compute the lowest completed index for all sessions that belong to this state machine.
    long lastCompleted = index;
    for (RaftSession session : raft.getSessions().getSessions()) {
      lastCompleted = Math.min(lastCompleted, session.getLastCompleted());
    }
    return lastCompleted >= index;
  }

  /**
   * Applies an initialize entry.
   * <p>
   * Initialize entries are used only at the beginning of a new leader's term to force the commitment of entries from
   * prior terms, therefore no logic needs to take place.
   */
  private CompletableFuture<Void> applyInitialize(Indexed<RaftLogEntry> entry) {
    for (RaftServiceContext service : raft.getServices()) {
      service.keepAliveSessions(entry.index(), entry.entry().getTimestamp());
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies a configuration entry to the internal state machine.
   * <p>
   * Configuration entries are applied to internal server state when written to the log. Thus, no significant logic
   * needs to take place in the handling of configuration entries. We simply release the previous configuration entry
   * since it was overwritten by a more recent committed configuration entry.
   */
  private CompletableFuture<Void> applyConfiguration(Indexed<RaftLogEntry> entry) {
    for (RaftServiceContext service : raft.getServices()) {
      service.keepAliveSessions(entry.index(), entry.entry().getTimestamp());
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies a session keep alive entry to the state machine.
   * <p>
   * Keep alive entries are applied to the internal state machine to reset the timeout for a specific session. If the
   * session indicated by the KeepAliveEntry is still held in memory, we mark the session as trusted, indicating that
   * the client has committed a keep alive within the required timeout. Additionally, we check all other sessions for
   * expiration based on the timestamp provided by this KeepAliveEntry. Note that sessions are never completely expired
   * via this method. Leaders must explicitly commit an UnregisterEntry to expire a session.
   * <p>
   * When a KeepAliveEntry is committed to the internal state machine, two specific fields provided in the entry are
   * used to update server-side session state. The {@code commandSequence} indicates the highest command for which the
   * session has received a successful response in the proper sequence. By applying the {@code commandSequence} to the
   * server session, we clear command output held in memory up to that point. The {@code eventVersion} indicates the
   * index up to which the client has received event messages in sequence for the session. Applying the {@code
   * eventVersion} to the server-side session results in events up to that index being removed from memory as they were
   * acknowledged by the client. It's essential that both of these fields be applied via entries committed to the Raft
   * log to ensure they're applied on all servers in sequential order.
   * <p>
   * Keep alive entries are retained in the log until the next time the client sends a keep alive entry or until the
   * client's session is expired. This ensures for sessions that have long timeouts, keep alive entries cannot be
   * cleaned from the log before they're replicated to some servers.
   */
  private long[] applyKeepAlive(Indexed<RaftLogEntry> entry) {

    // Store the session/command/event sequence and event index instead of acquiring a reference to the entry.
    List<Long> sessionIds = entry.entry().getKeepAlive().getSessionIdsList();
    List<Long> commandSequences = entry.entry().getKeepAlive().getCommandSequencesList();
    List<Long> eventIndexes = entry.entry().getKeepAlive().getEventIndexesList();

    // Iterate through session identifiers and keep sessions alive.
    List<Long> successfulSessionIds = new ArrayList<>(sessionIds.size());
    Set<RaftServiceContext> services = new HashSet<>();
    for (int i = 0; i < sessionIds.size(); i++) {
      long sessionId = sessionIds.get(i);
      long commandSequence = commandSequences.get(i);
      long eventIndex = eventIndexes.get(i);

      RaftSession session = raft.getSessions().getSession(sessionId);
      if (session != null) {
        if (session.getService().keepAlive(entry.index(), entry.entry().getTimestamp(), session, commandSequence, eventIndex)) {
          successfulSessionIds.add(sessionId);
          services.add(session.getService());
        }
      }
    }

    // Iterate through services and complete keep-alives, causing sessions to be expired if necessary.
    for (RaftServiceContext service : services) {
      service.completeKeepAlive(entry.index(), entry.entry().getTimestamp());
    }

    expireOrphanSessions(entry.entry().getTimestamp());

    return Longs.toArray(successfulSessionIds);
  }

  /**
   * Expires sessions that have timed out.
   */
  private void expireOrphanSessions(long timestamp) {
    // Iterate through registered sessions.
    for (RaftSession session : raft.getSessions().getSessions()) {
      if (session.getService().deleted() && session.isTimedOut(timestamp)) {
        logger.debug("Orphaned session expired in {} milliseconds: {}", timestamp - session.getLastUpdated(), session);
        session = raft.getSessions().removeSession(session.sessionId());
        if (session != null) {
          session.expire();
        }
      }
    }
  }

  /**
   * Gets or initializes a service context.
   */
  private RaftServiceContext getOrInitializeService(PrimitiveId primitiveId, PrimitiveType primitiveType, String serviceName) {
    // Get the state machine executor or create one if it doesn't already exist.
    RaftServiceContext service = raft.getServices().getService(serviceName);
    if (service == null) {
      service = initializeService(primitiveId, primitiveType, serviceName);
    }
    return service;
  }

  /**
   * Initializes a new service.
   */
  @SuppressWarnings("unchecked")
  private RaftServiceContext initializeService(PrimitiveId primitiveId, PrimitiveType primitiveType, String serviceName) {
    RaftServiceContext oldService = raft.getServices().getService(serviceName);
    RaftServiceContext service = new RaftServiceContext(
        primitiveId,
        serviceName,
        primitiveType,
        primitiveType.newService(),
        raft,
        threadContextFactory);
    raft.getServices().registerService(service);

    // If a service with this name was already registered, remove all of its sessions.
    if (oldService != null) {
      raft.getSessions().removeSessions(oldService.serviceId());
    }
    return service;
  }

  /**
   * Applies an open session entry to the state machine.
   */
  private long applyOpenSession(Indexed<RaftLogEntry> entry) {
    PrimitiveType primitiveType = raft.getPrimitiveTypes().getPrimitiveType(entry.entry().getOpenSession().getServiceType());

    // Get the state machine executor or create one if it doesn't already exist.
    RaftServiceContext service = getOrInitializeService(
        PrimitiveId.from(entry.index()),
        primitiveType,
        entry.entry().getOpenSession().getServiceName());

    if (service == null) {
      throw new RaftException.UnknownService("Unknown service type " + entry.entry().getOpenSession().getServiceType());
    }

    SessionId sessionId = SessionId.from(entry.index());
    RaftSession session = raft.getSessions().addSession(new RaftSession(
        sessionId,
        MemberId.from(entry.entry().getOpenSession().getMemberId()),
        entry.entry().getOpenSession().getServiceName(),
        primitiveType,
        ReadConsistency.valueOf(entry.entry().getOpenSession().getReadConsistency().name()),
        entry.entry().getOpenSession().getTimeout(),
        entry.entry().getTimestamp(),
        service.serializer(),
        service,
        raft,
        threadContextFactory));
    return service.openSession(entry.index(), entry.entry().getTimestamp(), session);
  }

  /**
   * Applies a close session entry to the state machine.
   */
  private void applyCloseSession(Indexed<RaftLogEntry> entry) {
    RaftSession session = raft.getSessions().getSession(entry.entry().getCloseSession().getSessionId());

    // If the server session is null, the session either never existed or already expired.
    if (session == null) {
      throw new RaftException.UnknownSession("Unknown session: " + entry.entry().getCloseSession().getSessionId());
    }

    RaftServiceContext service = session.getService();
    service.closeSession(entry.index(), entry.entry().getTimestamp(), session, entry.entry().getCloseSession().getExpired());

    // If this is a delete, unregister the service.
    if (entry.entry().getCloseSession().getDeleted()) {
      raft.getServices().unregisterService(service);
      service.close();
    }
  }

  /**
   * Applies a metadata entry to the state machine.
   */
  private MetadataResult applyMetadata(Indexed<RaftLogEntry> entry) {
    // If the session ID is non-zero, read the metadata for the associated state machine.
    if (entry.entry().getMetadata().getSessionId() > 0) {
      RaftSession session = raft.getSessions().getSession(entry.entry().getMetadata().getSessionId());

      // If the session is null, return an UnknownSessionException.
      if (session == null) {
        logger.warn("Unknown session: " + entry.entry().getMetadata().getSessionId());
        throw new RaftException.UnknownSession("Unknown session: " + entry.entry().getMetadata().getSessionId());
      }

      Set<SessionMetadata> sessions = new HashSet<>();
      for (RaftSession s : raft.getSessions().getSessions()) {
        if (s.primitiveName().equals(session.primitiveName())) {
          sessions.add(SessionMetadata.newBuilder()
              .setSessionId(s.sessionId().id())
              .setServiceName(s.primitiveName())
              .setServiceType(s.primitiveType().name())
              .build());
        }
      }
      return new MetadataResult(sessions);
    } else {
      Set<SessionMetadata> sessions = new HashSet<>();
      for (RaftSession session : raft.getSessions().getSessions()) {
        sessions.add(SessionMetadata.newBuilder()
            .setSessionId(session.sessionId().id())
            .setServiceName(session.primitiveName())
            .setServiceType(session.primitiveType().name())
            .build());
      }
      return new MetadataResult(sessions);
    }
  }

  /**
   * Applies a command entry to the state machine.
   * <p>
   * Command entries result in commands being executed on the user provided {@link PrimitiveService} and a response
   * being sent back to the client by completing the returned future. All command responses are cached in the command's
   * {@link RaftSession} for fault tolerance. In the event that the same command is applied to the state machine more
   * than once, the original response will be returned.
   * <p>
   * Command entries are written with a sequence number. The sequence number is used to ensure that commands are applied
   * to the state machine in sequential order. If a command entry has a sequence number that is less than the next
   * sequence number for the session, that indicates that it is a duplicate of a command that was already applied.
   * Otherwise, commands are assumed to have been received in sequential order. The reason for this assumption is
   * because leaders always sequence commands as they're written to the log, so no sequence number will be skipped.
   */
  private OperationResult applyCommand(Indexed<RaftLogEntry> entry) {
    // First check to ensure that the session exists.
    RaftSession session = raft.getSessions().getSession(entry.entry().getCommand().getSessionId());

    // If the session is null, return an UnknownSessionException. Commands applied to the state machine must
    // have a session. We ensure that session register/unregister entries are not compacted from the log
    // until all associated commands have been cleaned.
    // Note that it's possible for a session to be unknown if a later snapshot has been taken, so we don't want
    // to log warnings here.
    if (session == null) {
      logger.debug("Unknown session: " + entry.entry().getCommand().getSessionId());
      throw new RaftException.UnknownSession("unknown session: " + entry.entry().getCommand().getSessionId());
    }

    // Increment the load counter to avoid snapshotting under high load.
    raft.getLoadMonitor().recordEvent();

    // Execute the command using the state machine associated with the session.
    return session.getService()
        .executeCommand(
            entry.index(),
            entry.entry().getCommand().getSequenceNumber(),
            entry.entry().getTimestamp(),
            session,
            new PrimitiveOperation(
                OperationId.command(entry.entry().getCommand().getOperation()),
                entry.entry().getCommand().getValue().toByteArray()));
  }

  /**
   * Applies a query entry to the state machine.
   * <p>
   * Query entries are applied to the user {@link PrimitiveService} for read-only operations. Because queries are
   * read-only, they may only be applied on a single server in the cluster, and query entries do not go through the Raft
   * log. Thus, it is critical that measures be taken to ensure clients see a consistent view of the cluster event when
   * switching servers. To do so, clients provide a sequence and version number for each query. The sequence number is
   * the order in which the query was sent by the client. Sequence numbers are shared across both commands and queries.
   * The version number indicates the last index for which the client saw a command or query response. In the event that
   * the lastApplied index of this state machine does not meet the provided version number, we wait for the state
   * machine to catch up before applying the query. This ensures clients see state progress monotonically even when
   * switching servers.
   * <p>
   * Because queries may only be applied on a single server in the cluster they cannot result in the publishing of
   * session events. Events require commands to be written to the Raft log to ensure fault-tolerance and consistency
   * across the cluster.
   */
  private CompletableFuture<OperationResult> applyQuery(Indexed<RaftLogEntry> entry) {
    RaftSession session = raft.getSessions().getSession(entry.entry().getQuery().getSessionId());

    // If the session is null then that indicates that the session already timed out or it never existed.
    // Return with an UnknownSessionException.
    if (session == null) {
      logger.warn("Unknown session: " + entry.entry().getQuery().getSessionId());
      return Futures.exceptionalFuture(new RaftException.UnknownSession("unknown session " + entry.entry().getQuery().getSessionId()));
    }

    // Execute the query using the state machine associated with the session.
    return session.getService()
        .executeQuery(
            entry.index(),
            entry.entry().getQuery().getSequenceNumber(),
            entry.entry().getTimestamp(),
            session,
            new PrimitiveOperation(
                OperationId.query(entry.entry().getQuery().getOperation()),
                entry.entry().getQuery().getValue().toByteArray()));
  }

  @Override
  public void close() {
    // Don't close the thread context here since state machines can be reused.
  }
}
