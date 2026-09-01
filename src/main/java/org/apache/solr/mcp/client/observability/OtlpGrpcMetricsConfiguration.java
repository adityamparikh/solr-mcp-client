/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.client.observability;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exports metrics over OTLP/gRPC when the environment announces a gRPC-only collector — see the
 * package Javadoc for why Spring Boot cannot do this on its own.
 *
 * <p>The condition reads {@code otel.exporter.otlp.protocol}, which is how
 * {@code OTEL_EXPORTER_OTLP_PROTOCOL} arrives through relaxed binding. Endpoint and interval come
 * from the matching {@code OTEL_*} variables with the OpenTelemetry SDK's own defaults as
 * fallbacks, so a collector that moves its port between runs — IntelliJ's receiver picks a random
 * one per IDE session — is followed without any configuration edit.
 *
 * <p>Neither bean names the collector: everything flows from the injected variables.
 *
 * <ul>
 *   <li>The {@link SdkMeterProvider} carries a periodic gRPC exporter. Contributing it as a bean is
 *       enough: Spring Boot's {@code OpenTelemetrySdkAutoConfiguration} folds any
 *       {@code SdkMeterProvider} bean into the {@link OpenTelemetry} instance it builds, and the
 *       auto-configured {@code Resource} keeps {@code service.name} identical to what traces and
 *       logs already report. As an {@code AutoCloseable} bean it is flushed and shut down with the
 *       context.</li>
 *   <li>The {@link OpenTelemetryMeterRegistry} is the bridge that copies every Micrometer meter —
 *       JVM, HTTP server, Spring AI — into that provider. Spring Boot treats it like any other
 *       {@link MeterRegistry} bean: filters and customizers apply, and it joins the composite
 *       registry.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "otel.exporter.otlp.protocol", havingValue = "grpc")
class OtlpGrpcMetricsConfiguration {

    /**
     * The metrics half of the OpenTelemetry SDK, absent from Spring Boot's own wiring. The interval
     * default of one minute is the OpenTelemetry specification's; IntelliJ injects one second.
     */
    @Bean
    SdkMeterProvider sdkMeterProvider(
            Resource resource,
            @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}") String endpoint,
            @Value("${otel.metric.export.interval:60000}") long exportIntervalMillis) {
        return SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader
                        .builder(OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build())
                        .setInterval(Duration.ofMillis(exportIntervalMillis))
                        .build())
                .build();
    }

    /**
     * Micrometer-to-OpenTelemetry bridge. Injecting the {@link OpenTelemetry} bean rather than the
     * provider above keeps this registry pointed at whatever the application-wide instance holds —
     * the two meet only through Spring Boot's auto-configuration, never directly.
     */
    @Bean
    MeterRegistry openTelemetryMeterRegistry(OpenTelemetry openTelemetry, Clock clock) {
        return OpenTelemetryMeterRegistry.builder(openTelemetry).setClock(clock).build();
    }

}
