package io.emeraldpay.dshackle.upstream

import com.fasterxml.jackson.databind.ObjectMapper
import com.salesforce.reactorgrpc.GrpcRetry
import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.Common
import io.emeraldpay.api.proto.ReactorBlockchainGrpc
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.grpc.Chain
import io.infinitape.etherjar.domain.BlockHash
import io.infinitape.etherjar.domain.TransactionId
import io.infinitape.etherjar.rpc.*
import io.infinitape.etherjar.rpc.json.BlockJson
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.TopicProcessor
import reactor.core.publisher.toMono
import java.math.BigInteger
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

open class GrpcUpstream(
        private val chain: Chain,
        private val client: ReactorBlockchainGrpc.ReactorBlockchainStub,
        private val objectMapper: ObjectMapper,
        private val options: UpstreamsConfig.Options
): Upstream {

    constructor(chain: Chain, client: ReactorBlockchainGrpc.ReactorBlockchainStub, objectMapper: ObjectMapper)
            : this(chain, client, objectMapper, UpstreamsConfig.Options.getDefaults())

    private val log = LoggerFactory.getLogger(GrpcUpstream::class.java)

    private val headBlock = AtomicReference<BlockJson<TransactionId>>(null)
    private val streamBlocks: TopicProcessor<BlockJson<TransactionId>> = TopicProcessor.create()
    private var status = AtomicReference<UpstreamAvailability>(UpstreamAvailability.UNAVAILABLE)
    private val head = Head(this)
    private val api: EthereumApi

    init {
        val grpcTransport = EthereumGrpcTransport(chain, client, objectMapper)
        val rpcClient = DefaultRpcClient(grpcTransport)
        api = EthereumApi(rpcClient, objectMapper, chain)
    }

    open fun connect() {
        val chainRef = Common.Chain.newBuilder()
                .setTypeValue(chain.id)
                .build()
                .toMono()

        val retry: Function<Flux<BlockchainOuterClass.ChainHead>, Flux<BlockchainOuterClass.ChainHead>> = Function {
            status.set(UpstreamAvailability.UNAVAILABLE)
            client.subscribeHead(chainRef)
        }

        val flux = client.subscribeHead(chainRef)
                .compose(GrpcRetry.ManyToMany.retryAfter(retry, Duration.ofSeconds(5)))
        subscribe(flux)
    }

    internal fun subscribe(flux: Flux<BlockchainOuterClass.ChainHead>) {
        flux.map { value ->
                    val block = BlockJson<TransactionId>()
                    block.number = value.height
                    block.totalDifficulty = BigInteger(1, value.weight.toByteArray())
                    block.hash = BlockHash.from("0x"+value.blockId)
                    block
                }
                .filter { block ->
                    val curr = headBlock.get()
                    curr == null || curr.totalDifficulty < block.totalDifficulty
                }
                .doOnError { err ->
                    log.error("Head subscription error", err)
                }
                .subscribe { block ->
                    log.debug("New block ${block.number} on ${chain}")
                    headBlock.set(block)
                    streamBlocks.onNext(block)
                    status.set(UpstreamAvailability.OK)
                }
    }

    fun init(conf: BlockchainOuterClass.DescribeChain) {
        val available = conf.available
        val quorum = conf.quorum
        status.set(
                if (available && quorum > 0) UpstreamAvailability.OK else UpstreamAvailability.UNAVAILABLE
        )
    }

    fun onStatus(value: BlockchainOuterClass.ChainStatus) {
        val available = value.available
        val quorum = value.quorum
        status.set(
                if (available && quorum > 0) UpstreamAvailability.OK else UpstreamAvailability.UNAVAILABLE
        )
    }

    // ------------------------------------------------------------------------------------------


    override fun isAvailable(): Boolean {
        return headBlock.get() != null
    }

    override fun getStatus(): UpstreamAvailability {
        return status.get()
    }

    override fun getHead(): EthereumHead {
        return head
    }

    override fun getApi(): EthereumApi {
        return api
    }

    override fun getOptions(): UpstreamsConfig.Options {
        return options
    }

    class Head(
            val upstream: GrpcUpstream
    ): EthereumHead {

        override fun getHead(): Mono<BlockJson<TransactionId>> {
            val current = upstream.headBlock.get()
            if (current != null) {
                return Mono.just(current)
            }
            return Mono.from(upstream.streamBlocks)
        }

        override fun getFlux(): Flux<BlockJson<TransactionId>> {
            return Flux.from(upstream.streamBlocks)
        }
    }

}