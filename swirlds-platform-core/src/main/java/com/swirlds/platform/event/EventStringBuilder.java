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

package com.swirlds.platform.event;

import com.swirlds.common.CommonUtils;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.events.BaseEventHashedData;
import com.swirlds.common.events.BaseEventUnhashedData;
import com.swirlds.platform.EventImpl;

/**
 * A class used to convert an event into a string
 */
public final class EventStringBuilder {
	/** number of bytes of a hash to write */
	private static final int NUM_BYTES_HASH = 4;

	/** used for building the event string */
	private final StringBuilder sb = new StringBuilder();
	/** hashed data of an event */
	private final BaseEventHashedData hashedData;
	/** unhashed data of an event */
	private final BaseEventUnhashedData unhashedData;


	private EventStringBuilder(
			final BaseEventHashedData hashedData,
			final BaseEventUnhashedData unhashedData) {
		this.hashedData = hashedData;
		this.unhashedData = unhashedData;
	}

	private EventStringBuilder(final String errorString) {
		this(null, null);
		sb.append(errorString);
	}

	public static EventStringBuilder builder(final EventImpl event) {
		if (event == null) {
			return new EventStringBuilder("(EventImpl=null)");
		}
		return builder(event.getBaseEventHashedData(), event.getBaseEventUnhashedData());
	}

	public static EventStringBuilder builder(final ValidateEventTask event) {
		if (event == null) {
			return new EventStringBuilder("(ValidateEventTask=null)");
		}
		return builder(event.getHashedData(), event.getUnhashedData());
	}

	public static EventStringBuilder builder(BaseEventHashedData hashedData, BaseEventUnhashedData unhashedData) {
		if (hashedData == null) {
			return new EventStringBuilder("(HashedData=null)");
		}
		if (unhashedData == null) {
			return new EventStringBuilder("(UnhashedData=null)");
		}

		return new EventStringBuilder(hashedData, unhashedData);
	}

	private boolean isNull() {
		return hashedData == null || unhashedData == null;
	}

	public EventStringBuilder appendEvent() {
		if (isNull()) {
			return this;
		}
		appendShortEvent(
				hashedData.getCreatorId(),
				hashedData.getGeneration(),
				hashedData.getHash());
		return this;
	}

	public EventStringBuilder appendSelfParent() {
		if (isNull()) {
			return this;
		}
		sb.append(" sp");
		appendShortEvent(
				hashedData.getCreatorId(),
				hashedData.getSelfParentGen(),
				hashedData.getSelfParentHash());
		return this;
	}

	public EventStringBuilder appendOtherParent() {
		if (isNull()) {
			return this;
		}
		sb.append(" op");
		appendShortEvent(
				unhashedData.getOtherId(),
				hashedData.getOtherParentGen(),
				hashedData.getOtherParentHash());
		return this;
	}

	/**
	 * Append a short string representation of an event with the supplied information
	 *
	 * @param creatorId
	 * 		creator ID of the event
	 * @param generation
	 * 		generation of the event
	 * @param hash
	 * 		the hash of the event
	 */
	private void appendShortEvent(
			long creatorId,
			long generation,
			Hash hash) {
		sb.append('(');
		if (creatorId == EventImpl.NO_EVENT_ID) {
			if (generation == EventImpl.NO_EVENT_GEN && hash == null) {
				sb.append("none)");
				return;
			} else {
				sb.append("MALFORMED ");
			}
		}
		sb.append(creatorId)
				.append(',')
				.append(generation)
				.append(',');
		appendHash(hash);
		sb.append(')');
	}

	/**
	 * Append the shortened hash value to the StringBuilder
	 *
	 * @param hash
	 * 		the hash to append
	 */
	private void appendHash(Hash hash) {
		if (hash == null) {
			sb.append("null");
		} else {
			sb.append(CommonUtils.hex(hash.getValue(), NUM_BYTES_HASH));
		}
	}

	public String build() {
		return sb.toString();
	}
}
