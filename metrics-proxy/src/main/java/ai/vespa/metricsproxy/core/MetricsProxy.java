/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.core;

import ai.vespa.metricsproxy.metric.VespaMetrics;
import ai.vespa.metricsproxy.metric.ExternalMetrics;

import java.util.logging.Logger;

/**
 * GVL TODO: replace this class
 */
public class MetricsProxy {
    private static final Logger log = Logger.getLogger(MetricsProxy.class.getName());

    public MetricsProxy() {

        int port = metricsproxyConfig.rpcport();
        MetricsRpcServer server = new MetricsRpcServer(port, services,
                                         new MetricsManager(services,
                                                            new VespaMetrics(),
                                                            new ExternalMetrics()));
        server.setupSignalHandler();
        Thread serverThread = new Thread(server);
        serverThread.start();

        server.waitForShutdown();
    }

}
