/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
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
package io.emeraldpay.dshackle.upstream

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.upstream.MatchesResponse.AvailabilityResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.CapabilityResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.ExistsResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.GrpcResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.HeightResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.LowerHeightResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.NotMatchedResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.SameNodeResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.SlotHeightResponse
import io.emeraldpay.dshackle.upstream.MatchesResponse.Success
import io.emeraldpay.dshackle.upstream.finalization.FinalizationType
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.fromProtoType
import org.apache.commons.lang3.StringUtils
import java.util.Collections

class Selector {

    companion object {

        @JvmStatic
        val empty = EmptyMatcher()

        @JvmStatic
        val anyLabel = AnyLabelMatcher()

        sealed class HeightNumberOrTag {

            companion object {
                fun fromHeightSelector(selector: BlockchainOuterClass.HeightSelector): HeightNumberOrTag? {
                    return when (selector.heightOrNumberCase) {
                        BlockchainOuterClass.HeightSelector.HeightOrNumberCase.HEIGHTORNUMBER_NOT_SET ->
                            return if (selector.height == -1L) {
                                Latest
                            } else {
                                Number(selector.height)
                            }
                        BlockchainOuterClass.HeightSelector.HeightOrNumberCase.NUMBER -> Number(selector.number)
                        BlockchainOuterClass.HeightSelector.HeightOrNumberCase.TAG -> when (selector.tag) {
                            BlockchainOuterClass.BlockTag.SAFE -> Safe
                            BlockchainOuterClass.BlockTag.LATEST -> Latest
                            BlockchainOuterClass.BlockTag.PENDING -> Pending
                            BlockchainOuterClass.BlockTag.FINALIZED -> Finalized
                            else -> null
                        }
                        else -> null
                    }
                }
            }
            class Number(val num: Long) : HeightNumberOrTag()
            object Pending : HeightNumberOrTag()
            object Latest : HeightNumberOrTag()
            object Safe : HeightNumberOrTag()
            object Finalized : HeightNumberOrTag()

            fun getSort(): Sort {
                return when (this) {
                    is Latest -> Sort(
                        compareByDescending {
                            it.getHead().getCurrentHeight()
                        },
                    )

                    is Safe -> Sort.safe
                    is Finalized -> Sort.finalized

                    else -> Sort.default
                }
            }
        }

        @JvmStatic
        fun convertToUpstreamFilter(selectors: List<BlockchainOuterClass.Selector>): UpstreamFilter {
            val matcher = selectors
                .map {
                    when {
                        it.hasSlotHeightSelector() -> {
                            SlotMatcher(it.slotHeightSelector.slotHeight)
                        }
                        it.hasHeightSelector() -> {
                            when (val selector = HeightNumberOrTag.fromHeightSelector(it.heightSelector)) {
                                is HeightNumberOrTag.Number -> HeightMatcher(selector.num)
                                else -> empty
                            }
                        }
                        it.hasLowerHeightSelector() -> {
                            if (it.lowerHeightSelector.height > 0) {
                                LowerHeightMatcher(
                                    it.lowerHeightSelector.height,
                                    it.lowerHeightSelector.lowerBoundType.fromProtoType(),
                                )
                            } else {
                                empty
                            }
                        }
                        else -> empty
                    }
                }.run {
                    MultiMatcher(this)
                }
            return UpstreamFilter(getSort(selectors), matcher)
        }

        private fun getSort(selectors: List<BlockchainOuterClass.Selector>): Sort {
            selectors.forEach { selector ->
                if (selector.hasHeightSelector()) {
                    val heightSort = HeightNumberOrTag.fromHeightSelector(selector.heightSelector)?.getSort() ?: Sort.default
                    if (heightSort != Sort.default) {
                        return heightSort
                    }
                } else if (selector.hasLowerHeightSelector() && selector.lowerHeightSelector.height == 0L) {
                    return Sort(
                        compareBy(nullsLast()) {
                            it.getLowerBound(selector.lowerHeightSelector.lowerBoundType.fromProtoType())?.lowerBound
                        },
                    )
                }
            }
            return Sort.default
        }

        @JvmStatic
        fun convertToMatcher(req: BlockchainOuterClass.Selector?): LabelSelectorMatcher {
            return when {
                req == null -> anyLabel
                req.hasLabelSelector() -> req.labelSelector.let { selector ->
                    if (StringUtils.isNotEmpty(selector.name)) {
                        val values = selector.valueList
                            .map { it?.trim() ?: "" }
                            .filter { StringUtils.isNotEmpty(it) }
                        if (values.isEmpty()) {
                            ExistsMatcher(selector.name)
                        } else {
                            LabelMatcher(selector.name, selector.valueList)
                        }
                    } else {
                        anyLabel
                    }
                }
                req.hasAndSelector() -> AndMatcher(
                    Collections.unmodifiableCollection(
                        req.andSelector.selectorsList.map {
                            convertToMatcher(
                                it,
                            )
                        },
                    ),
                )
                req.hasOrSelector() -> OrMatcher(
                    Collections.unmodifiableCollection(
                        req.orSelector.selectorsList.map {
                            convertToMatcher(
                                it,
                            )
                        },
                    ),
                )
                req.hasNotSelector() -> NotMatcher(convertToMatcher(req.notSelector.selector))
                req.hasExistsSelector() -> ExistsMatcher(req.existsSelector.name)
                else -> anyLabel
            }
        }

        @JvmStatic
        fun extractLabels(matcher: Matcher): LabelSelectorMatcher? {
            if (matcher is LabelSelectorMatcher) {
                return matcher
            }
            if (matcher is MultiMatcher) {
                return matcher.getMatcher(LabelSelectorMatcher::class.java)
            }
            return null
        }

        @JvmStatic
        fun extractMethod(matcher: Matcher): MethodMatcher? {
            if (matcher is MethodMatcher) {
                return matcher
            }
            if (matcher is MultiMatcher) {
                return matcher.getMatcher(MethodMatcher::class.java)
            }
            return null
        }
    }

    class Builder {
        private val matchers = ArrayList<Matcher>()

        fun forMethod(name: String): Builder {
            matchers.add(MethodMatcher(name))
            return this
        }

        fun forLabels(matcher: LabelSelectorMatcher): Builder {
            matchers.add(matcher)
            return this
        }

        fun withMatcher(matcher: Matcher?): Builder {
            matcher?.let {
                matchers.add(it)
            }
            return this
        }

        fun build(): Matcher {
            return MultiMatcher(matchers)
        }
    }

    data class Sort(
        val comparator: Comparator<Upstream>,
    ) {
        companion object {
            @JvmStatic
            val default = Sort(compareBy { null })
            val safe = Sort(
                compareByDescending { up ->
                    up.getFinalizations().find { it.type == FinalizationType.SAFE_BLOCK }?.height ?: 0L
                },
            )
            val finalized = Sort(
                compareByDescending { up ->
                    up.getFinalizations().find { it.type == FinalizationType.FINALIZED_BLOCK }?.height ?: 0L
                },
            )
        }
    }

    abstract class Matcher {
        fun matches(up: Upstream): Boolean = matchesWithCause(up).matched()

        abstract fun matchesWithCause(up: Upstream): MatchesResponse

        abstract fun describeInternal(): String
    }

    data class UpstreamFilter(
        val sort: Sort,
        val matcher: Matcher,
    ) {
        constructor(matcher: Matcher) : this(Sort.default, matcher)

        companion object {
            @JvmStatic
            val default = UpstreamFilter(empty)
        }
    }

    data class MultiMatcher(
        private val matchers: Collection<Matcher>,
    ) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse {
            val responses = matchers.map { it.matchesWithCause(up) }
            return if (responses.all { it is Success }) {
                Success
            } else {
                MatchesResponse.MultiResponse(
                    responses.filter { it !is Success }.toSet(),
                )
            }
        }

        fun getMatchers(): Collection<Matcher> {
            return Collections.unmodifiableCollection(matchers)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Matcher> getMatcher(type: Class<T>): T? {
            return matchers.find { type.isAssignableFrom(it.javaClass) } as T?
        }

        override fun describeInternal(): String {
            return if (matchers.size == 1) {
                matchers.first().describeInternal()
            } else {
                "ALLOF[" + matchers.joinToString(",") { it.describeInternal() } + "]"
            }
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    data class MethodMatcher(
        val method: String,
    ) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse =
            if (up.getMethods().isCallable(method)) {
                Success
            } else {
                MatchesResponse.MethodResponse(method)
            }

        override fun describeInternal(): String {
            return "allow method $method"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    abstract class LabelSelectorMatcher : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse {
            val labelsResponses = up.getLabels().map { matchesWithCause(it) }
            return if (labelsResponses.any { it is Success }) {
                Success
            } else {
                MatchesResponse.MultiResponse(
                    labelsResponses.filter { it !is Success }.toSet(),
                )
            }
        }

        abstract fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse
        abstract fun asProto(): BlockchainOuterClass.Selector?
    }

    class EmptyMatcher : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse = Success

        override fun describeInternal(): String {
            return "empty"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class AnyLabelMatcher : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse = Success

        override fun asProto(): BlockchainOuterClass.Selector? {
            return null
        }

        override fun describeInternal(): String {
            return "any label"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class LabelMatcher(
        val name: String,
        val values: Collection<String>,
    ) : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse {
            val response = labels[name]?.let { labelValue ->
                values.any { it == labelValue }
            } ?: false
            return if (response) {
                Success
            } else {
                MatchesResponse.LabelResponse(
                    name,
                    values,
                )
            }
        }

        override fun asProto(): BlockchainOuterClass.Selector {
            return BlockchainOuterClass.Selector.newBuilder().setLabelSelector(
                BlockchainOuterClass.LabelSelector.newBuilder()
                    .setName(name)
                    .addAllValue(values),
            ).build()
        }

        override fun describeInternal(): String {
            return "label '$name'=" + values.joinToString(",")
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class OrMatcher(
        val matchers: Collection<LabelSelectorMatcher>,
    ) : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse {
            val responses = matchers.map { it.matchesWithCause(labels) }
            return if (responses.any { it is Success }) {
                Success
            } else {
                MatchesResponse.MultiResponse(
                    responses.filter { it !is Success }.toSet(),
                )
            }
        }

        override fun asProto(): BlockchainOuterClass.Selector {
            return BlockchainOuterClass.Selector.newBuilder().setOrSelector(
                BlockchainOuterClass.OrSelector.newBuilder()
                    .addAllSelectors(matchers.map { it.asProto() })
                    .build(),
            ).build()
        }

        override fun describeInternal(): String {
            return "ALLOF[" + matchers.joinToString(",") { it.describeInternal() } + "]"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class AndMatcher(
        val matchers: Collection<LabelSelectorMatcher>,
    ) : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse {
            val responses = matchers.map { it.matchesWithCause(labels) }
            return if (responses.all { it is Success }) {
                Success
            } else {
                MatchesResponse.MultiResponse(
                    responses.filter { it !is Success }.toSet(),
                )
            }
        }

        override fun asProto(): BlockchainOuterClass.Selector {
            return BlockchainOuterClass.Selector.newBuilder().setAndSelector(
                BlockchainOuterClass.AndSelector.newBuilder()
                    .addAllSelectors(matchers.map { it.asProto() })
                    .build(),
            ).build()
        }

        override fun describeInternal(): String {
            return "ALLOF[" + matchers.joinToString(",") { it.describeInternal() } + "]"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class NotMatcher(
        val matcher: LabelSelectorMatcher,
    ) : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse {
            val response = matcher.matchesWithCause(labels)
            return if (response !is Success) {
                Success
            } else {
                NotMatchedResponse(response)
            }
        }

        override fun asProto(): BlockchainOuterClass.Selector {
            return BlockchainOuterClass.Selector.newBuilder().setNotSelector(
                BlockchainOuterClass.NotSelector.newBuilder()
                    .setSelector(matcher.asProto())
                    .build(),
            ).build()
        }

        override fun describeInternal(): String {
            return "NOT[${matcher.describeInternal()}]"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class ExistsMatcher(
        val name: String,
    ) : LabelSelectorMatcher() {

        override fun matchesWithCause(labels: UpstreamsConfig.Labels): MatchesResponse =
            if (labels.containsKey(name)) {
                Success
            } else {
                ExistsResponse(name)
            }

        override fun asProto(): BlockchainOuterClass.Selector {
            return BlockchainOuterClass.Selector.newBuilder().setExistsSelector(
                BlockchainOuterClass.ExistsSelector.newBuilder()
                    .setName(name)
                    .build(),
            ).build()
        }

        override fun describeInternal(): String {
            return "label '$name' exists"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class CapabilityMatcher(val capability: Capability) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse =
            if (up.getCapabilities().contains(capability)) {
                Success
            } else {
                CapabilityResponse(capability)
            }

        override fun describeInternal(): String {
            return "provides $capability API"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class GrpcMatcher : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse =
            if (up.isGrpc()) {
                Success
            } else {
                GrpcResponse
            }

        override fun describeInternal(): String {
            return "is gRPC"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    data class LowerHeightMatcher(
        private val lowerHeight: Long,
        private val boundType: LowerBoundType,
    ) : Matcher() {
        override fun matchesWithCause(up: Upstream): MatchesResponse {
            val predictedLowerBound = up.predictLowerBound(boundType)
            return if (lowerHeight >= predictedLowerBound && predictedLowerBound != 0L) {
                Success
            } else {
                LowerHeightResponse(lowerHeight, predictedLowerBound, boundType)
            }
        }

        override fun describeInternal(): String {
            return "lower height $lowerHeight"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class HeightMatcher(val height: Long) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse {
            val currentHeight = up.getHead().getCurrentHeight() ?: 0
            return if (currentHeight >= height) {
                Success
            } else {
                HeightResponse(height, currentHeight)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HeightMatcher) return false

            if (height != other.height) return false

            return true
        }

        override fun hashCode(): Int {
            return height.hashCode()
        }

        override fun describeInternal(): String {
            return "height $height"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class SlotMatcher(val slotHeight: Long) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse {
            val currentHeight = up.getHead().getCurrentSlotHeight() ?: 0
            return if (currentHeight >= slotHeight) {
                Success
            } else {
                SlotHeightResponse(slotHeight, currentHeight)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SlotMatcher) return false

            if (slotHeight != other.slotHeight) return false

            return true
        }

        override fun hashCode(): Int {
            return slotHeight.hashCode()
        }

        override fun describeInternal(): String {
            return "slot height $slotHeight"
        }

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }
    }

    class SameNodeMatcher(private val upstreamHash: Short) : Matcher() {

        override fun matchesWithCause(up: Upstream): MatchesResponse =
            if (up.nodeId() == upstreamHash) {
                Success
            } else {
                SameNodeResponse(upstreamHash)
            }

        override fun describeInternal(): String =
            "upstream node-id=${upstreamHash.toUByte()}"

        override fun toString(): String {
            return "Matcher: ${describeInternal()}"
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is SameNodeMatcher) return false
            return other.upstreamHash == upstreamHash
        }
    }

    class AvailabilityMatcher : Matcher() {
        override fun matchesWithCause(up: Upstream): MatchesResponse =
            if (up.isAvailable()) {
                Success
            } else {
                AvailabilityResponse
            }

        override fun describeInternal(): String = "availability"

        override fun toString(): String = "Matcher: ${describeInternal()}"
    }
}
