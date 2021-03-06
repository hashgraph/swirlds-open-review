/*
 * (c) 2016-2021 Swirlds, Inc.
 *
 * This software is owned by Swirlds, Inc., which retains title to the software. This software is protected by various
 * intellectual property laws throughout the world, including copyright and patent laws. This software is licensed and
 * not sold. You must use this software only in accordance with the terms of the Hashgraph Open Review license at
 *
 * https://github.com/hashgraph/swirlds-open-review/raw/master/LICENSE.md
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF THIS SOFTWARE, EITHER EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE,
 * OR NON-INFRINGEMENT.
 */
package com.swirlds.platform.sync;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.events.Event;
import com.swirlds.platform.EventImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.swirlds.logging.LogMarker.EXPIRE_EVENT;
import static com.swirlds.logging.LogMarker.RECONNECT;

/**
 * This type implements the following behaviors:
 * <pre>
 *  Coordinates a shadow graph with a hashgraph (insertion (addEvent) and deletion (expire))
 *  Implements the phases of the event exchange protocol for gossiping (See platform docs.)
 * </pre>
 * This implementation therefore constitutes two interfaces: one for event intake, and one for
 * gossip. At a high level, this type coordinates hashgraph/consensus on this node and
 * other-nodes.
 */
public final class SyncShadowGraphManager {
	private static final Logger LOG = LogManager.getLogger();

	// The shadow graph
	public final SyncShadowGraph shadowGraph;

	// The set of all tips for the shadow graph. A tip is an event with no self-child (could have other-child)
	private final HashSet<SyncShadowEvent> tips;

	//the latest generation that is expired
	private long expiredGen;

	/**
	 * Production constructor.
	 *
	 * Default-construct a shadow graph manager. Used only by SwirldsPlatform
	 */
	public SyncShadowGraphManager() {
		this.shadowGraph = new SyncShadowGraph();
		this.expiredGen = Long.MIN_VALUE;

		this.tips = new HashSet<>();
	}

	/**
	 * Test constructor.
	 *
	 * Construct a shadow graph manager instance for a given shadow graph. Used only for testing.
	 *
	 * @param shadowGraph
	 * 		The shadow graph for which mutating operations are to be executed
	 */
	public SyncShadowGraphManager(final SyncShadowGraph shadowGraph) {
		this(shadowGraph, -1);
	}

	/**
	 * Test constructor.
	 *
	 * Construct a shadow graph manager instance for a given shadow graph, with a given most recent expired generation.
	 * Used only for testing.
	 *
	 * @param shadowGraph
	 * 		The shadow graph for which mutating operations are to be executed
	 * @param expiredGen
	 * 		The initial value of the most recent expired generation to be used
	 */
	public SyncShadowGraphManager(final SyncShadowGraph shadowGraph, final long expiredGen) {
		this.shadowGraph = shadowGraph;
		this.expiredGen = expiredGen;

		this.tips = new HashSet<>();
		identifyTips();
	}

	/**
	 * Reset the shadow graph to its constructed state. Remove all tips and shadow events, and reset the expired
	 * generation to {@link Long#MIN_VALUE}.
	 */
	public void clear() {
		shadowGraph.clear();
		tips.clear();
		expiredGen = Long.MIN_VALUE;
	}

	/**
	 * Phase 1 (send): Get a list of tip hashes to send for the hashgraph running on this node
	 *
	 * @return list of tip hashes
	 */
	public synchronized List<Hash> getSendTipHashes() {
		return tips.stream().map(SyncShadowEvent::getEventBaseHash).collect(Collectors.toList());
	}

	/**
	 * Phase 1 (receive): Apply a list of tip hashes received from another node to this shadow graph, with optional
	 * logging. This begins the identification of Hashgraph events to send to the peer.
	 *
	 * @param syncData
	 * 		The instance that holds the received tip hashes.
	 * @param syncLogString
	 * 		The log string which identifies the local node and remote node for the current connection, and
	 * 		whether this node is the caller or listener.
	 */
	public synchronized void setReceivedTipHashes(final SyncData syncData, String syncLogString) {
		setReceivedTipHashes(syncData);
	}

	/**
	 * Phase 1 (receive): Apply a list of tip hashes received from another node to this shadow graph. This begins the
	 * identification of Hashgraph events to send to the peer.
	 *
	 * @param syncData
	 * 		The instance that holds the received tip hashes.
	 */
	public synchronized void setReceivedTipHashes(final SyncData syncData) {
		syncData.getWorkingTips().clear();
		syncData.getWorkingTips().addAll(this.tips);

		syncData.getReceivedTipHashes().forEach((Hash h) -> {
			final SyncShadowEvent receivedTip = shadowGraph.shadow(h);
			if (receivedTip != null) {
				syncData.markForSync(receivedTip);
				syncData.getWorkingTips().remove(receivedTip);
				processStrictSelfDescendants(syncData.getSendList(), syncData.getWorkingTips(), receivedTip);
			}
		});
	}


	/**
	 * Phase 2 (send): Get a list of booleans for the tips received in Phase 1.
	 *
	 * @param syncData
	 * 		The instance that holds the tip hashes received in Phase 1
	 * @return A list of booleans, exactly one boolean per received tip
	 */
	public synchronized List<Boolean> getSendTipBooleans(final SyncData syncData) {
		final List<Hash> receivedTipHashes = syncData.getReceivedTipHashes();
		return getSendTipBooleans(receivedTipHashes);
	}


	/**
	 * Get the list of tip booleans for this node to send to the peer. Used in phase 2.
	 *
	 * @param receivedTipHashes
	 * 		tip hashes received from the peer node
	 * @return the list of tip booleans for this node
	 */
	public synchronized List<Boolean> getSendTipBooleans(final List<Hash> receivedTipHashes) {
		final List<Boolean> sendFlags = new ArrayList<>();

		for (int i = 0; i < receivedTipHashes.size(); ++i) {
			final SyncShadowEvent receivedTip = shadowGraph.shadow(receivedTipHashes.get(i));
			if (receivedTip != null && receivedTip.getNumSelfChildren() > 0) {
				sendFlags.add(true);
			} else {
				sendFlags.add(false);
			}
		}

		return sendFlags;
	}

	/**
	 * Phase 2 (receive): Apply a list of tip booleans received from a communicating peer.
	 *
	 * @param syncData
	 * 		The instance that holds the tip mark fields set by this routine.
	 * @param receivedTipBooleans
	 * 		The tip received in from the peer.
	 * @param syncLogString
	 * 		The log string which identifies the local node and remote node for the current connection, and
	 * 		whether this node is the caller or listener.
	 */
	public synchronized void setReceivedTipBooleans(final SyncData syncData, final List<Boolean> receivedTipBooleans,
			String syncLogString) {
		final List<Hash> tipHashes = getSendTipHashes();

		for (int i = 0; i < receivedTipBooleans.size(); ++i) {
			final boolean b = receivedTipBooleans.get(i);
			if (b) {
				syncData.markForSync(tipHashes.get(i));
				syncData.getWorkingTips().remove(shadowGraph.shadow(tipHashes.get(i)));
			}
		}
	}

	/**
	 * Phase 3 (send): Get a list of Hashgraph events to send to the peer, with optional logging. This finishes the
	 * identification of Hashgraph events to send to the peer.
	 *
	 * @param syncData
	 * 		The instance that holds the tip mark fields set by this routine.
	 * @param syncLogString
	 * 		The log string which identifies the local node and remote node for the current connection, and
	 * 		whether this node is the caller or listener.
	 * @return A list of Hashgraph events to send to the communicating peer.
	 */
	public synchronized List<EventImpl> finishSendEventList(final SyncData syncData, String syncLogString) {
		final List<EventImpl> sendList = syncData.getSendList();

		finishSendEventList(syncData);

		return sendList;
	}


	/**
	 * Phase 3 (send): Get a list of Hashgraph events to send to the peer. This finishes the identification of Hashgraph
	 * events to send to the peer.
	 *
	 * @param syncData
	 * 		The instance that holds the tip mark fields set by this routine.
	 */
	public synchronized void finishSendEventList(final SyncData syncData) {
		final Set<SyncShadowEvent> workingTips = syncData.getWorkingTips();
		final List<EventImpl> sendList = syncData.getSendList();
		for (final SyncShadowEvent workingTip : workingTips) {
			SyncShadowEvent y = workingTip;

			while (y != null) {

				for (final SyncShadowEvent z : shadowGraph.graphDescendants(y)) {
					if (syncData.markedForSync(z)) {
						syncData.markForSearch(y);
						break;
					}
				}

				if (!syncData.markedForSearch(y)) {
					sendList.add((EventImpl) y.getEvent());
				} else {
					break;
				}

				y = y.getSelfParent();
			}
		}

		sort(sendList);
	}

	/**
	 * Predicate to determine if an event has expired.
	 *
	 * @param event
	 * 		The event.
	 * @return true iff the given event is expired
	 */
	public synchronized boolean expired(final Event event) {
		return event.getGeneration() <= expiredGen;
	}

	/**
	 * Remove events that have expired
	 *
	 * @param newExpiredGeneration
	 * 		Any event with a generation less than or equal to `newExpiredGeneration` is to be removed.
	 * @return number of events expunged from the shadow graph during this function call
	 */
	public synchronized int expire(final long newExpiredGeneration) {
		LOG.debug(EXPIRE_EVENT.getMarker(),
				"SG newExpiredGeneration {}", newExpiredGeneration);
		if (newExpiredGeneration == expiredGen) {
			return 0;
		}

		setExpiredGeneration(newExpiredGeneration);
		return expire();
	}

	/**
	 * Remove events that have expired. Any event with a generation less than or
	 * equal to `this.expiredGen` is to be removed.
	 *
	 * @return number of events expunged from the shadow graph
	 */
	public synchronized int expire() {
		final HashMap<SyncShadowEvent, Boolean> tipRemove = new HashMap<>();
		final AtomicInteger count = new AtomicInteger();

		for (final SyncShadowEvent tip : tips) {
			count.addAndGet(shadowGraph.removeStrictSelfAncestry(tip, this::expired));
			tipRemove.put(tip, expired(tip));
		}

		tipRemove.forEach((SyncShadowEvent t, Boolean remove) -> {
			if (remove) {
				count.addAndGet(shadowGraph.removeSelfAncestry(t, this::expired));
				tips.remove(t);
			}
		});

		return count.get();
	}

	/**
	 * Get the shadow event that references a hashgraph event instance.
	 *
	 * @param e
	 * 		The event.
	 * @return the shadow event that references an event
	 */
	public synchronized SyncShadowEvent shadow(final Event e) {
		return shadowGraph.shadow(e);
	}

	/**
	 * Get the shadow event that references a hashgraph event instance
	 * with a given hash.
	 *
	 * @param h
	 * 		The event hash
	 * @return the shadow event that references an event with the given hash
	 */
	public synchronized SyncShadowEvent shadow(final Hash h) {
		return shadowGraph.shadow(h);
	}

	/**
	 * Get a hashgraph event from a hash
	 *
	 * @param h
	 * 		the hash
	 * @return the hashgraph event, if there is one in {@code this} shadow graph, else `null`
	 */
	public synchronized EventImpl hashgraphEvent(final Hash h) {
		final SyncShadowEvent shadow = shadow(h);
		if (shadow == null) {
			return null;
		} else {
			return (EventImpl) shadow.getEvent();
		}
	}

	/**
	 * If Event `e` is insertable, then insert it and update the tip Event set, else do nothing.
	 *
	 * @param e
	 * 		The event reference to insert.
	 * @return true iff e was inserted
	 */
	public synchronized boolean addEvent(final Event e) {
		final InsertableStatus status = insertable(e);

		if (status == InsertableStatus.INSERTABLE) {
			final SyncShadowEvent s = shadowGraph.insert(e);
			tips.add(s);
			tips.remove(s.getSelfParent());
			return true;
		} else {
			if (status == InsertableStatus.EXPIRED_EVENT) {
				LOG.debug(
						RECONNECT.getMarker(),
						"`addEvent`: did not insert, status is {} for event {}, expiredGen = {}",
						insertable(e),
						SyncLogging.getSyncLogString(e),
						expiredGen);
			} else {
				LOG.debug(
						RECONNECT.getMarker(),
						"`addEvent`: did not insert, status is {} for event {}",
						insertable(e),
						SyncLogging.getSyncLogString(e));
			}

			return false;
		}
	}

	/**
	 * Get the number of tips in this node's shadow graph at time of call.
	 *
	 * @return The number of tips
	 */
	public synchronized int getNumTips() {
		return tips.size();
	}

	/**
	 * Get the total number of shadow events in this node's shadow graph at time of call.
	 *
	 * @return The number of events
	 */
	public synchronized int getNumShadowEvents() {
		return shadowGraph.getNumShadowEvents();
	}

	/**
	 * Get the most recent expired generation used by this shadow graph at time of call.
	 *
	 * @return The most recent expired generation
	 */
	public synchronized long getExpiredGeneration() {
		return expiredGen;
	}

	/**
	 * Update the expired generation this SGM instance will use to remove expired shadow events.
	 *
	 * @param expiredGen
	 * 		The most recent expired generation
	 */
	public synchronized void setExpiredGeneration(final long expiredGen) {
		// 28 May 2021
		// Disabling this check to stabilize initial release of reconnect.
		// This is paired with disabled checks for decreased expired generation in functions
		// ConsensusImpl.updateMaxRoundGeneration and ConsensusImpl.updateMinRoundGeneration.
//		if (expiredGen < this.expiredGen) {
//			final String msg = String.format(
//					"Shadow graph expired generation can not be decreased. Change %s -> %s disallowed.",
//					this.expiredGen,
//					expiredGen);
//			throw new IllegalArgumentException(msg);
//		}

		this.expiredGen = expiredGen;
	}

	/**
	 * Get a reference to the hash set of tips for this shadow graph at time of call.
	 *
	 * @return The current tip hash set
	 */
	public synchronized Set<SyncShadowEvent> getTips() {
		return tips;
	}

	/**
	 * Identify the set of tip events in this node's shadow graph at time of call.
	 *
	 * Used only for testing.
	 */
	private void identifyTips() {
		tips.clear();
		for (final SyncShadowEvent shadowEvent : shadowGraph.getShadowEvents()) {
			if (shadowEvent.isTip()) {
				tips.add(shadowEvent);
			}
		}
	}


	/**
	 * Predicate to determine if an event referenced by a shadow event has expired.
	 * A shadow is expired iff the event it references is expired.
	 *
	 * @param s
	 * 		The shadow event.
	 * @return true iff the given shadow is expired
	 */
	private synchronized boolean expired(final SyncShadowEvent s) {
		return s.getEvent().getGeneration() <= expiredGen;
	}


	/**
	 * Apply a DFS dependency sort to {@code sendList}.
	 *
	 * @param sendList
	 * 		The list of events to sort.
	 */
	private static void sort(final List<EventImpl> sendList) {
		sendList.sort((EventImpl e1, EventImpl e2) -> (int) (e1.getGeneration() - e2.getGeneration()));
	}


	/**
	 * Add strict self-descendants of x to send list and remove from working tips
	 *
	 * @param sendList
	 * 		the list of events to be sent (may be complete after this function executes)
	 * @param workingTips
	 * 		the set of working tips fo the shadow graph at time of call
	 * @param x
	 * 		the shadow event from which to start
	 */
	private static void processStrictSelfDescendants(
			final List<EventImpl> sendList,
			final Set<SyncShadowEvent> workingTips,
			final SyncShadowEvent x) {
		for (final SyncShadowEvent y : x.getSelfChildren()) {
			processSelfDescendants(sendList, workingTips, y);
		}
	}


	/**
	 * Add self-descendants of x to send list and remove from working tips
	 *
	 * @param sendList
	 * 		the list of events to be sent (may be complete after this function executes)
	 * @param workingTips
	 * 		the set of working tips fo the shadow graph at time of call
	 * @param y
	 * 		the shadow event from which to start
	 */
	private static void processSelfDescendants(
			final List<EventImpl> sendList,
			final Set<SyncShadowEvent> workingTips,
			final SyncShadowEvent y) {
		if (y == null) {
			return;
		}

		sendList.add((EventImpl) y.getEvent());
		workingTips.remove(y);

		for (final SyncShadowEvent y0 : y.getSelfChildren()) {
			processSelfDescendants(sendList, workingTips, y0);
		}
	}


	/**
	 * Determine whether an event is insertable at time of call.
	 *
	 * @param e
	 * 		The event to evaluate
	 * @return An insertable status, indicating whether the event can be inserted, and if not, the reason it can not be
	 * 		inserted.
	 */
	private InsertableStatus insertable(final Event e) {
		if (e == null) {
			return InsertableStatus.NULL_EVENT;
		}

		// No multiple insertions
		if (shadow(e) != null) {
			return InsertableStatus.DUPLICATE_SHADOW_EVENT;
		}

		// An expired event will not be referenced in the graph.
		if (expired(e)) {
			return InsertableStatus.EXPIRED_EVENT;
		}

		final boolean hasOP = e.getOtherParent() != null;
		final boolean hasSP = e.getSelfParent() != null;

		// If e has an unexpired parent that is not already referenced
		// by the shadow graph, then do not insert e.
		if (hasOP) {
			final boolean knownOP = shadowGraph.shadow(e.getOtherParent()) != null;
			final boolean expiredOP = expired(e.getOtherParent());
			if (!knownOP && !expiredOP) {
				return InsertableStatus.UNKNOWN_CURRENT_OTHER_PARENT;
			}
		}

		if (hasSP) {
			final boolean knownSP = shadowGraph.shadow(e.getSelfParent()) != null;
			final boolean expiredSP = expired(e.getSelfParent());
			if (!knownSP && !expiredSP) {
				return InsertableStatus.UNKNOWN_CURRENT_SELF_PARENT;
			}
		}

		// If both parents are null, then insertion is allowed. This will create
		// a new tree in the forest view of the graph.
		return InsertableStatus.INSERTABLE;
	}

	private enum InsertableStatus {
		INSERTABLE,
		NULL_EVENT,
		DUPLICATE_SHADOW_EVENT,
		EXPIRED_EVENT,
		UNKNOWN_CURRENT_SELF_PARENT,
		UNKNOWN_CURRENT_OTHER_PARENT
	}
}


