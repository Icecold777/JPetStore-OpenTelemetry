/*
 *    Copyright 2010-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.jpetstore.tracing;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.sun.management.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Tracing {
  private static Tracing instance;
  private OpenTelemetry openTelemetry;
  private Resource resource;
  private SdkTracerProvider sdkTracerProvider;
  private SdkMeterProvider meterProvider;
  private SdkLoggerProvider loggerProvider;
  private static Tracer tracer;
  private static Meter meter;
  private LongCounter counter;
  private Attributes attributes;
  private TracingLevel tracingLevel;

  public enum TracingLevel {
    BASIC,      // 1-5% overhead
    DETAILED,   // 5-15% overhead
    VERBOSE     // 15-30% overhead
  }

  private Tracing() {
    /*
     * Resource
     */
    // Create a default resource with a service name attribute
    Resource defaultResource = Resource.getDefault();
    String serviceName = System.getenv("OTEL_SERVICE_NAME");
    if (serviceName == null || serviceName.isEmpty()) {
        serviceName = "jpetstore"; // Default fallback
    }
    Resource serviceNameResource = Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, serviceName));
    resource = defaultResource.merge(serviceNameResource);

    /*
     * Traces
     */
    String otlpEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    if (otlpEndpoint == null || otlpEndpoint.isEmpty()) {
        otlpEndpoint = "http://localhost:4317"; // Default fallback
    }

    BatchSpanProcessor spanProcessor = BatchSpanProcessor
        .builder(OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .build())
        .build();
    sdkTracerProvider = SdkTracerProvider.builder().addSpanProcessor(spanProcessor).setResource(resource).build();

    // Create an OpenTelemetry instance with the given tracer provider and propagator
    W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();
    ContextPropagators propagators = ContextPropagators.create(propagator);

    /*
     * Metrics
     */
    meterProvider = SdkMeterProvider.builder()
        .setResource(resource)
        .registerMetricReader(
            PeriodicMetricReader.builder(
                OtlpGrpcMetricExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .build())
                .setInterval(5, TimeUnit.SECONDS).build())
        .build();

    // It is recommended that the API user keep a reference to Attributes they will record against
    attributes = Attributes.of(stringKey("Key"), "Test");

    /*
     * Logs
     */
    loggerProvider = SdkLoggerProvider.builder()
        .setResource(resource)
        .addLogRecordProcessor(BatchLogRecordProcessor
            .builder(OtlpGrpcLogRecordExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build())
            .build())
        .build();

    /*
     * Build OpenTelemetry instance
     */
    openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(sdkTracerProvider)
        .setMeterProvider(meterProvider)
        .setLoggerProvider(loggerProvider)
        .setPropagators(propagators)
        .buildAndRegisterGlobal();

    // OpenTelemetry Appender
    OpenTelemetryAppender.install(openTelemetry);

    /*
     * get from OpenTelemetry instance
     */
    String instrumentationName = resource.getAttributes().get(ResourceAttributes.SERVICE_NAME);
    tracer = openTelemetry.getTracer(instrumentationName, "1.0.0");
    meter = openTelemetry.getMeter(instrumentationName);
    counter = meter.counterBuilder("counter_test").setDescription("counter_test").setUnit("1").build();

    // Memory usage metric
    meter.gaugeBuilder("jvm.memory.total")
        .setDescription("Current Memory Usage.")
        .setUnit("byte")
        .buildWithCallback(result -> result
            .record((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()), Attributes.empty()));

    // CPU usage metric
    OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    meter.gaugeBuilder("jvm.total.cpu")
        .setDescription("Current CPU percentage.")
        .setUnit("1000000 * percentage")
        .buildWithCallback(measurement -> {
            measurement.record((int) (osBean.getProcessCpuLoad() * 1000000.0), Attributes.empty());
        });

    // JVM Memory Metrics
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    meter.gaugeBuilder("jvm.memory.heap.used")
        .setDescription("Current heap memory usage")
        .setUnit("byte")
        .buildWithCallback(measurement -> {
            measurement.record(memoryMXBean.getHeapMemoryUsage().getUsed(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.memory.heap.max")
        .setDescription("Maximum heap memory size")
        .setUnit("byte")
        .buildWithCallback(measurement -> {
            measurement.record(memoryMXBean.getHeapMemoryUsage().getMax(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.memory.nonheap.used")
        .setDescription("Current non-heap memory usage")
        .setUnit("byte")
        .buildWithCallback(measurement -> {
            measurement.record(memoryMXBean.getNonHeapMemoryUsage().getUsed(), Attributes.empty());
        });

    // Memory Pool Metrics
    List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean pool : memoryPoolMXBeans) {
        String poolName = pool.getName();
        meter.gaugeBuilder("jvm.memory.pool.used")
            .setDescription("Memory usage by memory pool")
            .setUnit("byte")
            .buildWithCallback(measurement -> {
                measurement.record(pool.getUsage().getUsed(),
                    Attributes.of(stringKey("pool"), poolName));
            });
    }

    // GC Metrics
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    for (GarbageCollectorMXBean gc : gcBeans) {
        String gcName = gc.getName();
        meter.gaugeBuilder("jvm.gc.count")
            .setDescription("Total number of garbage collections")
            .setUnit("1")
            .buildWithCallback(measurement -> {
                measurement.record(gc.getCollectionCount(),
                    Attributes.of(stringKey("gc"), gcName));
            });

        meter.gaugeBuilder("jvm.gc.time")
            .setDescription("Total time spent in garbage collection")
            .setUnit("ms")
            .buildWithCallback(measurement -> {
                measurement.record(gc.getCollectionTime(),
                    Attributes.of(stringKey("gc"), gcName));
            });
    }

    // Thread Metrics
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    meter.gaugeBuilder("jvm.threads.current")
        .setDescription("Current number of threads")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(threadMXBean.getThreadCount(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.threads.daemon")
        .setDescription("Number of daemon threads")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(threadMXBean.getDaemonThreadCount(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.threads.peak")
        .setDescription("Peak number of threads")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(threadMXBean.getPeakThreadCount(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.threads.deadlocked")
        .setDescription("Number of deadlocked threads")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(threadMXBean.findDeadlockedThreads() != null ?
                threadMXBean.findDeadlockedThreads().length : 0, Attributes.empty());
        });

    // Class Loading Metrics
    ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
    meter.gaugeBuilder("jvm.classes.loaded")
        .setDescription("Number of classes currently loaded")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(classLoadingMXBean.getLoadedClassCount(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.classes.total_loaded")
        .setDescription("Total number of classes loaded since JVM start")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(classLoadingMXBean.getTotalLoadedClassCount(), Attributes.empty());
        });

    meter.gaugeBuilder("jvm.classes.unloaded")
        .setDescription("Total number of classes unloaded since JVM start")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(classLoadingMXBean.getUnloadedClassCount(), Attributes.empty());
        });

    // System Metrics
    meter.gaugeBuilder("system.cpu.usage")
        .setDescription("System CPU usage")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(osBean.getSystemCpuLoad(), Attributes.empty());
        });

    meter.gaugeBuilder("system.memory.usage")
        .setDescription("System memory usage")
        .setUnit("byte")
        .buildWithCallback(measurement -> {
            measurement.record(osBean.getTotalPhysicalMemorySize() - osBean.getFreePhysicalMemorySize(),
                Attributes.empty());
        });

    meter.gaugeBuilder("system.load.average.1m")
        .setDescription("System load average (1 minute)")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(osBean.getSystemLoadAverage(), Attributes.empty());
        });

    // Application-specific Metrics
    meter.counterBuilder("http.server.requests")
        .setDescription("HTTP request count")
        .setUnit("1")
        .build();

    meter.counterBuilder("http.server.errors")
        .setDescription("HTTP error count")
        .setUnit("1")
        .build();

    meter.gaugeBuilder("http.server.latency")
        .setDescription("HTTP request latency")
        .setUnit("ms")
        .buildWithCallback(measurement -> {
            measurement.record(0, Attributes.empty());
        });

    meter.gaugeBuilder("db.connections.active")
        .setDescription("Active database connections")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(0, Attributes.empty());
        });

    meter.gaugeBuilder("db.connections.idle")
        .setDescription("Idle database connections")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(0, Attributes.empty());
        });

    meter.gaugeBuilder("db.query.time")
        .setDescription("Database query execution time")
        .setUnit("ms")
        .buildWithCallback(measurement -> {
            measurement.record(0, Attributes.empty());
        });

    // Initialize tracing level from environment variable
    String levelStr = System.getenv("OTEL_TRACING_LEVEL");
    try {
      tracingLevel = TracingLevel.valueOf(levelStr != null ? levelStr.toUpperCase() : "BASIC");
    } catch (IllegalArgumentException e) {
      tracingLevel = TracingLevel.BASIC;
    }
  }

  // Helper methods for different tracing levels
  public static Span createBasicSpan(String name) {
    return tracer.spanBuilder(name)
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();
  }

  public static Span createDetailedSpan(String name, String operation) {
    return tracer.spanBuilder(name)
        .setSpanKind(SpanKind.INTERNAL)
        .setAttribute("operation", operation)
        .setAttribute("timestamp", System.currentTimeMillis())
        .startSpan();
  }

  public static Span createVerboseSpan(String name, String operation, String component) {
    return tracer.spanBuilder(name)
        .setSpanKind(SpanKind.INTERNAL)
        .setAttribute("operation", operation)
        .setAttribute("component", component)
        .setAttribute("timestamp", System.currentTimeMillis())
        .setAttribute("thread", Thread.currentThread().getName())
        .setAttribute("thread_id", Thread.currentThread().getId())
        .startSpan();
  }

  public static void addBasicAttributes(Span span, String key, String value) {
    if (instance.tracingLevel != TracingLevel.BASIC) {
      span.setAttribute(key, value);
    }
  }

  public static void addDetailedAttributes(Span span, String operation, String result) {
    if (instance.tracingLevel == TracingLevel.DETAILED || instance.tracingLevel == TracingLevel.VERBOSE) {
      span.setAttribute("operation", operation);
      span.setAttribute("result", result);
      span.setAttribute("duration", System.currentTimeMillis());
    }
  }

  public static void addVerboseAttributes(Span span, String operation, String result, String details) {
    if (instance.tracingLevel == TracingLevel.VERBOSE) {
      span.setAttribute("operation", operation);
      span.setAttribute("result", result);
      span.setAttribute("details", details);
      span.setAttribute("duration", System.currentTimeMillis());
      span.setAttribute("memory_used", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      span.setAttribute("thread_count", Thread.activeCount());
    }
  }

  public static void recordException(Span span, Throwable throwable) {
    if (instance.tracingLevel != TracingLevel.BASIC) {
      span.recordException(throwable);
      span.setStatus(StatusCode.ERROR, throwable.getMessage());
    }
  }

  public static Tracer getTracer() {
    if (instance == null) {
      instance = new Tracing();
    }
    return instance.tracer;
  }

  public static Meter getMeter() {
    if (instance == null) {
      instance = new Tracing();
    }
    return instance.meter;
  }

  public static Attributes getAttributes() {
    if (instance == null) {
      instance = new Tracing();
    }
    return instance.attributes;
  }

  public static LongCounter getCounter() {
    if (instance == null) {
      instance = new Tracing();
    }
    return instance.counter;
  }

  public static TracingLevel getTracingLevel() {
    if (instance == null) {
      instance = new Tracing();
    }
    return instance.tracingLevel;
  }
}
