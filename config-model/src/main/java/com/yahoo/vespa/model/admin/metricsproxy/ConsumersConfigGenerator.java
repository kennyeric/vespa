package com.yahoo.vespa.model.admin.metricsproxy;

import ai.vespa.metricsproxy.core.ConsumersConfig.Consumer;
import com.yahoo.vespa.model.admin.monitoring.Metric;
import com.yahoo.vespa.model.admin.monitoring.MetricSet;
import com.yahoo.vespa.model.admin.monitoring.MetricsConsumer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.yahoo.vespa.model.admin.monitoring.DefaultMetricsConsumer.VESPA_CONSUMER_ID;
import static com.yahoo.vespa.model.admin.monitoring.DefaultMetricsConsumer.getDefaultMetricsConsumer;

/**
 * Helper class to generate config for metrics consumers.
 *
 * @author gjoranv
 */
class ConsumersConfigGenerator {

    /**
     * @param userConsumers The consumers set up by the user in services.xml
     * @return A list of consumer builders (a mapping from consumer to its metrics)
     */
    static List<Consumer.Builder> generate(Map<String, MetricsConsumer> userConsumers) {
        // Normally, the user given consumers should not contain VESPA_CONSUMER_ID, but it's allowed for some internally used applications.
        var allConsumers = new LinkedHashMap<>(userConsumers);
        allConsumers.put(VESPA_CONSUMER_ID, combineConsumers(getDefaultMetricsConsumer(), allConsumers.get(VESPA_CONSUMER_ID)));

        return allConsumers.values().stream()
                .map(ConsumersConfigGenerator::toConsumerBuilder)
                .collect(Collectors.toList());
    }

    /*
     * Returns a new consumer that is a combination of the two given consumers
     * (ignoring the id of the consumers' metric sets).
     * If a metric with the same id exists in both consumers, output name and
     * dimensions from the 'overriding' consumer is used, but dimensions from 'original'
     * are added if they don't exist in 'overriding'.
     */
    private static MetricsConsumer combineConsumers(MetricsConsumer original, MetricsConsumer overriding) {
        if (overriding == null) return original;
        return addMetrics(original, overriding.getMetrics());
    }

    private static MetricsConsumer addMetrics(MetricsConsumer original, Map<String, Metric> metrics) {
        Map<String, Metric> combinedMetrics = new LinkedHashMap<>(original.getMetrics());
        metrics.forEach((name, newMetric) ->
                                combinedMetrics.put(name, combineMetrics(original.getMetrics().get(name), newMetric)));

        return new MetricsConsumer(original.getId(),
                                   new MetricSet(original.getMetricSet().getId(), combinedMetrics.values()));
    }

    private static Metric combineMetrics(@Nullable Metric original, Metric newMetric) {
        return original != null ? newMetric.addDimensionsFrom(original) : newMetric;
    }

    private static Consumer.Builder toConsumerBuilder(MetricsConsumer consumer) {
        Consumer.Builder builder = new Consumer.Builder().name(consumer.getId());
        consumer.getMetrics().values().forEach(metric -> builder.metric(toConsumerMetricBuilder(metric)));
        return builder;
    }

    private static Consumer.Metric.Builder toConsumerMetricBuilder(Metric metric) {
        Consumer.Metric.Builder builder = new Consumer.Metric.Builder().name(metric.name)
                .outputname(metric.outputName)
                .description(metric.description);
        metric.dimensions.forEach((name, value) -> builder.dimension(toMetricDimensionBuilder(name, value)));
        return builder;
    }

    private static Consumer.Metric.Dimension.Builder toMetricDimensionBuilder(String name, String value) {
        return new Consumer.Metric.Dimension.Builder()
                .key(name)
                .value(value);
    }

}
