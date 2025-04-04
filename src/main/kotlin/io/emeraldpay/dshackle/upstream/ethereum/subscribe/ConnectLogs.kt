/**
 * Copyright (c) 2021 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.ethereum.subscribe

import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.hex.Hex32
import io.emeraldpay.dshackle.upstream.ethereum.hex.HexDataComparator
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import java.util.function.Function

open class ConnectLogs(
    upstream: Multistream,
    private val connectBlockUpdates: ConnectBlockUpdates,
) {

    companion object {
        private val ADDR_COMPARATOR = HexDataComparator()
        private val TOPIC_COMPARATOR = HexDataComparator()
    }

    constructor(upstream: Multistream, scheduler: Scheduler) : this(upstream, ConnectBlockUpdates(upstream, scheduler))

    private val produceLogs = ProduceLogs(upstream)

    fun start(matcher: Selector.Matcher): Flux<LogMessage> {
        return produceLogs.produce(connectBlockUpdates.connect(matcher))
    }

    open fun create(addresses: List<Address>, topics: List<Hex32>): SubscriptionConnect<LogMessage> {
        return object : SubscriptionConnect<LogMessage> {
            override fun connect(matcher: Selector.Matcher): Flux<LogMessage> {
                // shortcut to the whole output if we don't have any filters
                if (addresses.isEmpty() && topics.isEmpty()) {
                    return start(matcher)
                }
                // filtered output
                return start(matcher)
                    .transform(filtered(addresses, topics))
            }
        }
    }

    fun filtered(addresses: List<Address>, topics: List<Hex32>): Function<Flux<LogMessage>, Flux<LogMessage>> {
        // sort search criteria to use binary search later
        val sortedAddresses: List<Address> = addresses.sortedWith(ADDR_COMPARATOR)
        val sortedTopics: List<Hex32> = topics.sortedWith(TOPIC_COMPARATOR)
        return Function { logs ->
            logs.filter {
                val goodAddress =
                    sortedAddresses.isEmpty() || sortedAddresses.binarySearch(it.address, ADDR_COMPARATOR) >= 0
                val goodTopic = when {
                    sortedTopics.isEmpty() -> true
                    it.topics.size < sortedTopics.size -> false
                    else -> sortedTopics.indices.all { index ->
                        it.topics[index].let { logTopic ->
                            sortedTopics.binarySearch(logTopic, TOPIC_COMPARATOR) >= 0
                        }
                    }
                }
                goodAddress && goodTopic
            }
        }
    }
}
