/**
 * Copyright (c) 2020 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.config

import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Global

class MainConfig {
    var host = "127.0.0.1"
    var port = 2449
    var tls: AuthConfig.ServerTlsAuth? = null
    var passthrough: Boolean = false
    var cache: CacheConfig? = null
    var proxy: ProxyConfig? = null
    var upstreams: UpstreamsConfig? = null
        set(value) {
            field = value
            value?.let { cfg ->
                // we need to store the initial config we've loaded from dshackle.yaml
                // because we can change the current config on the fly
                // during config reload we must compare the pure configs
                initialConfig = Global.objectMapper.readValue<UpstreamsConfig>(
                    Global.objectMapper.writeValueAsBytes(cfg),
                )
            }
        }
    var tokens: TokensConfig? = null
    var monitoring: MonitoringConfig = MonitoringConfig.default()
    var accessLogConfig: AccessLogConfig = AccessLogConfig.default()
    var health: HealthConfig = HealthConfig.default()
    var signature: SignatureConfig? = null
    var compression: CompressionConfig = CompressionConfig.default()
    var chains: ChainsConfig = ChainsConfig.default()
    var authorization: AuthorizationConfig = AuthorizationConfig.default()

    var initialConfig: UpstreamsConfig? = null
        private set
}
