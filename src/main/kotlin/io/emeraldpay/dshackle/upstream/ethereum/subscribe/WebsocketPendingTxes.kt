/**
 * Copyright (c) 2022 EmeraldPay, Inc
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

import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.SubscriptionConnect
import io.emeraldpay.dshackle.upstream.ethereum.EthereumEgressSubscription
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.ethereum.domain.TransactionId
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

class WebsocketPendingTxes(
    private val wsSubscriptions: WsSubscriptions,
) : DefaultPendingTxesSource(), SubscriptionConnect<TransactionId> {

    companion object {
        private val log = LoggerFactory.getLogger(WebsocketPendingTxes::class.java)
    }

    override fun createConnection(): Flux<TransactionId> {
        return wsSubscriptions.subscribe(ChainRequest("eth_subscribe", ListParams(EthereumEgressSubscription.METHOD_PENDING_TXES)))
            .data
            .timeout(Duration.ofSeconds(85), Mono.empty())
            .map {
                // comes as a JS string, i.e., within quotes
                val value = ByteArray(it.size - 2)
                System.arraycopy(it, 1, value, 0, value.size)
                TransactionId.from(String(value))
            }
            .doOnError { t -> log.warn("Invalid pending transaction", t) }
            .onErrorResume { Mono.empty() }
    }
}
