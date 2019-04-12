/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.metric;

import ai.vespa.metricsproxy.core.MetricsConsumers;
import ai.vespa.metricsproxy.core.ConsumersConfig.Consumer;
import ai.vespa.metricsproxy.metric.model.DimensionId;
import ai.vespa.metricsproxy.metric.model.MetricId;
import ai.vespa.metricsproxy.metric.model.MetricsPacket;
import ai.vespa.metricsproxy.metric.model.ServiceId;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static ai.vespa.metricsproxy.metric.model.DimensionId.toDimensionId;
import static ai.vespa.metricsproxy.metric.model.MetricId.toMetricId;
import static ai.vespa.metricsproxy.metric.model.ServiceId.toServiceId;
import static com.yahoo.log.LogLevel.DEBUG;
import static java.util.stream.Collectors.toCollection;

/**
 * This class is responsible for handling metrics received from external processes.
 *
 * @author gjoranv
 */
public class ExternalMetrics {
    private static final Logger log = Logger.getLogger(ExternalMetrics.class.getName());

    public static final DimensionId ROLE_DIMENSION = toDimensionId("role");
    public static final DimensionId STATE_DIMENSION = toDimensionId("state");
    public static final DimensionId ORCHESTRATOR_STATE_DIMENSION = toDimensionId("orchestratorState");

    static final ServiceId VESPA_NODE_SERVICE_ID = toServiceId("vespa.node");

    private volatile List<MetricsPacket.Builder> metrics = new ArrayList<>();
    private final MetricsConsumers consumers;

    public ExternalMetrics(MetricsConsumers consumers) {
        this.consumers = consumers;
    }

    public List<MetricsPacket.Builder> getMetrics() {
        return metrics;
    }

    public void setExtraMetrics(List<MetricsPacket.Builder> externalPackets) {
        log.log(DEBUG, () -> "Setting new external metrics with " + externalPackets.size() + " metrics packets.");
        externalPackets.forEach(packet -> {
            packet.addConsumers(consumers.getAllConsumers())
                    .service(VESPA_NODE_SERVICE_ID)
                    .retainMetrics(metricsToRetain())
                    .applyOutputNames(outputNamesById());
        });
        metrics = List.copyOf(externalPackets);
    }

    // TODO: Move to MetricsConsumers and rename to whitelistedMetrics
    private Set<MetricId> metricsToRetain() {
        return consumers.getConsumersByMetric().keySet().stream()
                .map(configuredMetric -> toMetricId(configuredMetric.name()))
                .collect(toCollection(LinkedHashSet::new));
    }

    /**
     * Returns a mapping from metric id to a list of the metric's output names.
     * Metrics that only have their id as output name are included in the output.
     */
    private Map<MetricId, List<String>> outputNamesById() {
        Map<MetricId, List<String>> outputNamesById = new LinkedHashMap<>();
        for (Consumer.Metric metric : consumers.getConsumersByMetric().keySet()) {
            MetricId id = toMetricId(metric.name());
            outputNamesById.computeIfAbsent(id, unused -> new ArrayList<>())
                    .add(metric.outputname());
        }
        return outputNamesById;
    }

    /**
     * Extracts the node repository dimensions (role, state etc.) from the given packets.
     * If the same dimension exists in multiple packets, this implementation gives no guarantees
     * about which value is returned.
     */
    public static Map<DimensionId, String> extractConfigserverDimensions(Collection<MetricsPacket.Builder> packets) {
        Map<DimensionId, String> dimensions = new HashMap<>();
        for (MetricsPacket.Builder packet : packets) {
            dimensions.putAll(packet.build().dimensions());
        }
        dimensions.keySet().retainAll(ImmutableSet.of(ROLE_DIMENSION, STATE_DIMENSION, ORCHESTRATOR_STATE_DIMENSION));
        return dimensions;
    }
}
