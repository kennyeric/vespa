/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.service;

/**
 * GVL TODO: Implement di.Provider
 *
 * @author gjoranv
 */
public class SystemPollerProvider {

    private final SystemPoller poller;

    /**
     * @param services   The list of VespaService instances to monitor for System metrics
     * @param monitoringConfig   The interval in seconds between each polling.
     */
    public SystemPollerProvider (VespaServices services, MonitoringConfig monitoringConfig) {
        poller = new SystemPoller(services.getVespaServices(), 60 * monitoringConfig.intervalMinutes());
        poller.poll();
    }

    public void deconstruct() {
        poller.stop();
    }

    public SystemPoller get() {
        return poller;
    }
}
