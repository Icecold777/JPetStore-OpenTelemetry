# OpenTelemetry Tracing Implementation Overview

## Overview
This document describes the implementation of OpenTelemetry tracing in the JPetStore application, including different tracing levels and their performance implications.

## Components Added

### 1. OpenTelemetry Collector
- Added `otel-collector` service in `docker-compose.yaml`
- Configured to receive telemetry data via OTLP
- Exports traces to Jaeger
- Exports metrics to Prometheus
- Ports exposed:
  - 4317: OTLP gRPC receiver
  - 8889: Prometheus metrics

### 2. Jaeger
- Added `jaeger` service in `docker-compose.yaml`
- All-in-one Jaeger instance for trace visualization
- Persistent storage using Badger DB
- Configuration:
  - SPAN_STORAGE_TYPE=badger
  - BADGER_EPHEMERAL=false
  - Data stored in jaeger-data volume
- Ports exposed:
  - 16686: Web UI
  - 4318: OTLP HTTP
  - 14250: Model
  - 9411: Zipkin

### 3. Prometheus
- Added `prometheus` service in `docker-compose.yaml`
- Configured to scrape metrics from the collector
- Persistent storage for metrics data
- Data stored in prometheus-data volume
- Port exposed: 9090

### 4. Grafana
- Added `grafana` service in `docker-compose.yaml`
- Persistent storage for dashboards and configurations
- Data stored in grafana-storage volume
- Port exposed: 3000

### 5. Database (MySQL)
- Persistent storage for application data
- Data stored in mysql-data volume
- Port exposed: 3306

## Storage Configuration

### Persistent Volumes
1. **MySQL Data**
   - Volume: mysql-data
   - Path: /var/lib/mysql
   - Purpose: Database files and data

2. **Jaeger Data**
   - Volume: jaeger-data
   - Path: /badger
   - Purpose: Trace data storage
   - Storage Type: Badger DB

3. **Prometheus Data**
   - Volume: prometheus-data
   - Path: /prometheus
   - Purpose: Metrics data storage
   - Retention: Configurable in prometheus.yml

4. **Grafana Data**
   - Volume: grafana-storage
   - Path: /var/lib/grafana
   - Purpose: Dashboards and configurations

### Benefits of Persistent Storage
1. **Data Persistence**
   - Data survives container restarts
   - No loss of historical data
   - Maintains application state

2. **Performance**
   - Better I/O performance
   - Reduced container overhead
   - Optimized storage access

3. **Maintenance**
   - Easier backup and restore
   - Simplified data migration
   - Better resource management

4. **Monitoring**
   - Historical data available
   - Long-term trend analysis
   - Better debugging capabilities

## Tracing Implementation

### Tracing Levels
Three tracing levels have been implemented with different performance impacts:

1. **BASIC** (1-5% overhead)
   - Minimal span creation
   - No additional attributes
   - No exception details
   - Environment variable: `OTEL_TRACING_LEVEL=BASIC`

2. **DETAILED** (5-15% overhead)
   - Operation name and timestamp
   - Basic attributes
   - Exception recording
   - Operation results
   - Environment variable: `OTEL_TRACING_LEVEL=DETAILED`

3. **VERBOSE** (15-30% overhead)
   - All detailed information
   - Thread information
   - Memory usage
   - Component details
   - Full exception stack traces
   - Performance metrics
   - Environment variable: `OTEL_TRACING_LEVEL=VERBOSE`

### Key Files Modified

1. **docker-compose.yaml**
   ```yaml
   environment:
     - OTEL_SERVICE_NAME=jpetstore
     - OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
     - OTEL_EXPORTER_OTLP_INSECURE=true
     - OTEL_TRACING_LEVEL=VERBOSE  # Configurable: BASIC, DETAILED, VERBOSE
   ```

2. **Tracing.java**
   - Added tracing level enum
   - Implemented different span creation methods
   - Added attribute recording methods
   - Added exception recording
   - Added performance metrics collection

### Usage Examples

1. **Basic Tracing**
   ```java
   Span span = Tracing.createBasicSpan("operation-name");
   try {
       // Your code here
   } finally {
       span.end();
   }
   ```

2. **Detailed Tracing**
   ```java
   Span span = Tracing.createDetailedSpan("operation-name", "specific-operation");
   try {
       // Your code here
       Tracing.addDetailedAttributes(span, "operation", "success");
   } catch (Exception e) {
       Tracing.recordException(span, e);
   } finally {
       span.end();
   }
   ```

3. **Verbose Tracing**
   ```java
   Span span = Tracing.createVerboseSpan("operation-name", "specific-operation", "component-name");
   try {
       // Your code here
       Tracing.addVerboseAttributes(span, "operation", "success", "detailed information");
   } catch (Exception e) {
       Tracing.recordException(span, e);
   } finally {
       span.end();
   }
   ```

## Monitoring

### Jaeger UI
- Access at: http://localhost:16686
- View traces, spans, and attributes
- Filter by service name: "jpetstore"
- Search by operation name or attributes

### Prometheus
- Access at: http://localhost:9090
- View metrics and create dashboards
- Query metrics using PromQL

### Grafana
- Access at: http://localhost:3000
- Default credentials:
  - Username: admin
  - Password: admin
- Features:
  - Rich visualization of Prometheus metrics
  - Pre-built dashboards for JVM metrics
  - Real-time monitoring
  - Customizable alerts
  - Multiple visualization types:
    - Graphs
    - Gauges
    - Heatmaps
    - Tables
    - Stat panels
  - Time range selection
  - Dashboard sharing
  - Export/Import dashboards

#### Recommended Grafana Dashboards
1. **JVM Overview**
   - Memory usage
   - GC metrics
   - Thread counts
   - Class loading

2. **Application Performance**
   - HTTP request rates
   - Error rates
   - Response times
   - Database metrics

3. **System Resources**
   - CPU usage
   - Memory usage
   - Network I/O
   - Disk I/O

#### Setting up Grafana
1. Access Grafana at http://localhost:3000
2. Login with default credentials
3. Add Prometheus data source:
   - URL: http://prometheus:9090
   - Access: Server (default)
4. Import dashboards:
   - JVM dashboard ID: 4701
   - Node Exporter dashboard ID: 1860
   - Custom application dashboard (to be created)

## Performance Considerations

1. **Memory Usage**
   - Basic: Minimal memory overhead
   - Detailed: Moderate memory usage
   - Verbose: Significant memory usage

2. **CPU Impact**
   - Basic: 1-5% overhead
   - Detailed: 5-15% overhead
   - Verbose: 15-30% overhead

3. **Network Traffic**
   - Basic: Minimal data transfer
   - Detailed: Moderate data transfer
   - Verbose: Significant data transfer

## Best Practices

1. **Tracing Level Selection**
   - Use BASIC for production environments
   - Use DETAILED for staging/testing
   - Use VERBOSE for debugging specific issues

2. **Attribute Management**
   - Keep attribute values small
   - Avoid sensitive information
   - Use meaningful names

3. **Error Handling**
   - Always use try-catch blocks
   - Record exceptions appropriately
   - Set proper span status

## Future Improvements

1. **Sampling Configuration**
   - Implement sampling strategies
   - Add rate limiting
   - Configure retention policies

2. **Additional Metrics**
   - Add business metrics
   - Implement custom dashboards
   - Add alerting rules

3. **Integration**
   - Add more service integrations
   - Implement distributed tracing
   - Add correlation IDs 