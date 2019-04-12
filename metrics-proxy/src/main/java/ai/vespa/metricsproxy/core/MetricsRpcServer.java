/*
 * Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
 */

package ai.vespa.metricsproxy.core;

import ai.vespa.metricsproxy.metric.model.ConsumerId;
import ai.vespa.metricsproxy.metric.model.MetricsPacket;
import ai.vespa.metricsproxy.service.VespaService;
import ai.vespa.metricsproxy.service.VespaServices;
import com.yahoo.jrt.ErrorCode;
import com.yahoo.jrt.Method;
import com.yahoo.jrt.Request;
import com.yahoo.jrt.Spec;
import com.yahoo.jrt.StringValue;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Transport;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ai.vespa.metricsproxy.metric.model.ConsumerId.toConsumerId;
import static ai.vespa.metricsproxy.metric.model.json.JsonUtil.toMetricsPackets;
import static ai.vespa.metricsproxy.metric.model.json.JsonUtil.toYamasArray;
import static com.yahoo.collections.CollectionUtil.mkString;
import static com.yahoo.log.LogLevel.DEBUG;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

/**
 * Rpc server for the metrics proxy.
 *
 * @author gjoranv
 */
public class MetricsRpcServer {

    private static final Logger log = Logger.getLogger(MetricsRpcServer.class.getName());

    private static int LOG_SPENT_TIME_LIMIT = 10 * 1000; // ms. same as default client RPC timeout used in rpc_invoke

    private final Supervisor supervisor = new Supervisor(new Transport());
    private final AtomicBoolean signalCaught = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final Spec spec;
    private final VespaServices vespaServices;
    private final MetricsManager metricsManager;

    public MetricsRpcServer(int port, VespaServices vespaServices, MetricsManager metricsManager) {
        this.spec = new Spec(port);
        this.vespaServices = vespaServices;
        this.metricsManager = metricsManager;
        addMethods();
        log.log(DEBUG, "RPC Server configured");
    }

    private void addMethods() {
        supervisor.addMethod(
                new Method("getMetricsById", "s", "s", this, "getMetricsById")
                        .methodDesc("Get Vespa metrics for the service with the given Id")
                        .paramDesc(0, "id", "The id of the service")
                        .returnDesc(0, "ret", "Vespa metrics"));

        supervisor.addMethod(
                new Method("getServices", "", "s", this, "getServices")
                        .methodDesc("Get Vespa services monitored by this metrics proxy")
                        .returnDesc(0, "ret", "Vespa metrics"));

        supervisor.addMethod(
                new Method("getMetricsForYamas", "s", "s", this, "getMetricsForYamas")
                        .methodDesc("Get JSON formatted Vespa metrics for a given service name, 'all' or 'system'")
                        .paramDesc(0, "service", "The vespa service name, special restricted names 'all' and 'system'")
                        .returnDesc(0, "ret", "Vespa metrics"));

        supervisor.addMethod(
                new Method("getHealthMetricsForYamas", "s", "s", this, "getHealthMetricsForYamas")
                        .methodDesc("Get JSON formatted Health check for a given service name, 'all' or 'system'")
                        .paramDesc(0, "service", "The vespa service name")
                        .returnDesc(0, "ret", "Vespa metrics"));

        supervisor.addMethod(
                new Method("getAllMetricNamesForService", "ss", "s", this, "getAllMetricNamesForService")
                        .methodDesc("Get metric names known for service ")
                        .paramDesc(0, "service", "The vespa service name'")
                        .paramDesc(1, "consumer", "The consumer'")
                        .returnDesc(0, "ret", "Metric names, one metric name per line"));


        supervisor.addMethod(
                new Method("setExtraMetrics", "s", "",
                           this, "setExtraMetrics")
                        .methodDesc("Set extra metrics that will be added to output from getMetricsForYamas.")
                        .paramDesc(0, "metricsJson", "The metrics in json format"));
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void getAllMetricNamesForService(Request req) {
        String service = req.parameters().get(0).asString();
        ConsumerId consumer = toConsumerId(req.parameters().get(1).asString());
        withExceptionHandling(req, () -> {
            String metricNames = metricsManager.getMetricNamesForServiceAndConsumer(service, consumer);
            req.returnValues().add(new StringValue(metricNames));
        });
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void getMetricsById(Request req) {
        String id = req.parameters().get(0).asString();
        withExceptionHandling(req, () -> {
            String metricsString = metricsManager.getMetricsByConfigId(id);
            req.returnValues().add(new StringValue(metricsString));
        });
    }


    @SuppressWarnings({"UnusedDeclaration"})
    public void getServices(Request req) {
        withExceptionHandling(req, () -> {
            String servicesString = metricsManager.getAllVespaServices();
            req.returnValues().add(new StringValue(servicesString));
        });
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void getMetricsForYamas(Request req) {
        Instant startTime = Instant.now();
        req.detach();
        String service = req.parameters().get(0).asString();
        log.log(DEBUG, () -> "getMetricsForYamas called at " + startTime + " with argument: " + service);
        List<VespaService> services = vespaServices.getMonitoringServices(service);
        log.log(DEBUG, () -> "Getting metrics for services: " + mkString(services, "[", ", ", "]"));
        if (services.isEmpty()) setNoServiceError(req, service);
        else withExceptionHandling(req, () -> {
            List<MetricsPacket> packets = metricsManager.getMetrics(services, startTime);
            log.log(DEBUG,() -> "Returning metrics packets:\n" + mkString(packets, "\n"));
            req.returnValues().add(new StringValue(toYamasArray(packets).serialize()));
        });
        req.returnRequest();
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void getHealthMetricsForYamas(Request req) {
        req.detach();
        String service = req.parameters().get(0).asString();
        List<VespaService> services = vespaServices.getMonitoringServices(service);
        if (services.isEmpty()) setNoServiceError(req, service);
        else withExceptionHandling(req, () -> {
            List<MetricsPacket> packets = metricsManager.getHealthMetrics(services);
            req.returnValues().add(new StringValue(toYamasArray(packets, true).serialize()));
        });
        req.returnRequest();
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setExtraMetrics(Request req) {
        String metricsJson = req.parameters().get(0).asString();
        log.log(DEBUG, "setExtraMetrics called with argument: " + metricsJson);
        withExceptionHandling(req, () -> metricsManager.setExtraMetrics(toMetricsPackets(metricsJson)));
    }

    private static void withExceptionHandling(Request req, ThrowingRunnable runnable) {
        try {
            TimeTracker timeTracker = new TimeTracker(req);
            runnable.run();
            timeTracker.logSpentTime();
        } catch (Exception e) {
            log.log(WARNING, "Got exception when running RPC command " + req.methodName(), e);
            setMethodFailedError(req, e);
        } catch (Error e) {
            log.log(WARNING, "Got error when running RPC command " + req.methodName(), e);
            setMethodFailedError(req, e);
        } catch (Throwable t) {
            log.log(WARNING, "Got throwable (non-error, non-exception) when running RPC command " + req.methodName(), t);
            setMethodFailedError(req, t);
        }
    }

    private static void setMethodFailedError(Request req, Throwable t) {
        String msg = "Request failed due to internal error: " + t.getClass().getName() + ": " + t.getMessage();
        req.setError(ErrorCode.METHOD_FAILED, msg);
        req.returnValues().add(new StringValue(""));
    }

    private static void setNoServiceError(Request req, String serviceName) {
        String msg = "No service with name '" + serviceName + "'";
        req.setError(ErrorCode.BAD_REQUEST, msg);
        req.returnValues().add(new StringValue(""));
    }


    private static class TimeTracker {
        private final long startTime = System.currentTimeMillis();
        private final Request request;

        private TimeTracker(Request request) {
            this.request = request;
        }

        public long spentTime() {
            return System.currentTimeMillis() - startTime;
        }

        private void logSpentTime() {
            Level logLevel = DEBUG;
            if (spentTime() > LOG_SPENT_TIME_LIMIT) {
                logLevel = INFO;
            }
            if (log.isLoggable(logLevel)) {
                log.log(logLevel, "RPC request '" + request.methodName() + "' with parameters '" +
                        request.parameters() + "' took " + spentTime() + " ms");
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
