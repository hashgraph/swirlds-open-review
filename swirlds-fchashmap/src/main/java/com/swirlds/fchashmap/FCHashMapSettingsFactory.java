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

package com.swirlds.fchashmap;

import java.time.Duration;

/**
 * This object is used to configure general FCHashMap settings.
 */
public final class FCHashMapSettingsFactory {

	private static FCHashMapSettings settings;

	private FCHashMapSettingsFactory() {

	}

	/**
	 * Specify the settings that should be used for the FCHashMap.
	 */
	public static void configure(FCHashMapSettings settings) {
		FCHashMapSettingsFactory.settings = settings;
	}

	/**
	 * Get the settings for FCHashMap.
	 */
	public static FCHashMapSettings get() {
		if (settings == null) {
			settings = getDefaultSettings();
		}
		return settings;
	}

	/**
	 * Get default FCHashMap settings. Useful for testing.
	 */
	private static FCHashMapSettings getDefaultSettings() {
		return new FCHashMapSettings() {
			@Override
			public int getMaximumGCQueueSize() {
				return 100;
			}

			@Override
			public Duration getGCQueueThresholdPeriod() {
				return Duration.ofMinutes(1);
			}
		};
	}
}
