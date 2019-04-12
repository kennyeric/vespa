/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.service;

import ai.vespa.metricsproxy.metric.Metric;
import ai.vespa.metricsproxy.metric.Metrics;

public class DummyService extends VespaService {
    static final String NAME = "dummy";
    static final String METRIC_1 = "c.test";

    private final int num;

    DummyService(int num, String configid) {
        super(NAME, NAME + num, configid);
        this.num = num;
    }

    @Override
    public Metrics getMetrics() {
        Metrics m = new Metrics();

        long timestamp = System.currentTimeMillis() / 1000;
        m.add(new Metric(METRIC_1, 5 * num + 1, timestamp));
        m.add(new Metric("val", 1.3 * num + 1.05, timestamp));

        return m;
    }

}
