/*
 * Copyright 2015 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataArtisans.flinkCascading.runtime.stats;


import org.apache.flink.api.common.JobID;
import org.apache.flink.client.program.Client;
import org.apache.flink.runtime.instance.ActorGateway;
import org.apache.flink.runtime.messages.accumulators.AccumulatorResultsFound;
import org.apache.flink.runtime.messages.accumulators.RequestAccumulatorResults;
import org.apache.flink.runtime.util.SerializedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.concurrent.duration.FiniteDuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AccumulatorCache {

	private static final Logger LOG = LoggerFactory.getLogger(AccumulatorCache.class);


	private ActorGateway localJobManager;

	private JobID jobID;
	// TODO
	private Client client;

	private volatile Map<String, Object> currentAccumulators = Collections.emptyMap();

	private FiniteDuration defaultTimeout;

	private final int updateIntervalMillis;
	private long lastUpdateTime;

	public AccumulatorCache(int updateIntervalSecs, FiniteDuration defaultTimeout) {
		this.updateIntervalMillis = updateIntervalSecs * 1000;
		this.defaultTimeout = defaultTimeout;
	}

	public void update() {
		update(false);
	}

	public void update(boolean force) {

		long currentTime = System.currentTimeMillis();
		if (!force && currentTime - lastUpdateTime <= updateIntervalMillis) {
			return;
		}

		if (jobID == null) {
			return;
		}

		if (localJobManager != null) {

			scala.concurrent.Future<Object> response =
					localJobManager.ask(new RequestAccumulatorResults(jobID), defaultTimeout);

			try {

				Object result = Await.result(response, defaultTimeout);

				if (result instanceof AccumulatorResultsFound) {
					Map<String, SerializedValue<Object>> serializedAccumulators =
							((AccumulatorResultsFound) result).result();

					updateAccumulatorMap(serializedAccumulators);

				} else {
					LOG.warn("Failed to fetch accumulators for job {}", jobID);
				}

			} catch (Exception e) {
				LOG.error("Error occurred while fetching accumulators for {}", jobID, e);
			}


		} else if (client != null) {

			//TODO
//			client.getAccumulators(jobID);

		} else {
			throw new IllegalStateException("The accumulator cache has no valid target.");
		}

		lastUpdateTime = currentTime;
	}

	private void updateAccumulatorMap(Map<String, SerializedValue<Object>> serializedAccumulators) throws Exception {

		if (serializedAccumulators != null) {

			Map<String, Object> newAccumulators;
			if (serializedAccumulators.isEmpty()) {
				newAccumulators = Collections.emptyMap();
			} else {
				newAccumulators = new HashMap<String, Object>(serializedAccumulators.size());
			}

			for (Map.Entry<String, SerializedValue<Object>> entry : serializedAccumulators.entrySet()) {

				Object value = null;
				if (entry.getValue() != null) {
					value = entry.getValue().deserializeValue(ClassLoader.getSystemClassLoader());
				}

				newAccumulators.put(entry.getKey(), value);
			}

			currentAccumulators = newAccumulators;
			LOG.debug("Updated accumulators: {}", newAccumulators);
		}
	}

	public Map<String, Object> getCurrentAccumulators() {
		return currentAccumulators;
	}

	public void setJobID(JobID jobID) {
		this.jobID = jobID;
	}

	public void setLocalJobManager(ActorGateway localJobManager) {
		this.localJobManager = localJobManager;
	}

	public void setClient(Client client) {
		this.client = client;
	}

	public long getLastUpdateTime() {
		return lastUpdateTime;
	}
}
