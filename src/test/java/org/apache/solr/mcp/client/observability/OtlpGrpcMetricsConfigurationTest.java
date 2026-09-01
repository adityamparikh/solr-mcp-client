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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The configuration must be driven entirely by {@code otel.exporter.otlp.protocol}: present as
 * {@code grpc} it contributes both beans, in every other state it contributes nothing. The
 * collaborators Spring Boot would auto-configure ({@code Resource}, {@code OpenTelemetry},
 * {@code Clock}) are supplied directly so the runner exercises only this class's conditions.
 */
class OtlpGrpcMetricsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OtlpGrpcMetricsConfiguration.class)
            .withBean(Resource.class, Resource::getDefault)
            .withBean(OpenTelemetry.class, OpenTelemetry::noop)
            .withBean(Clock.class, () -> Clock.SYSTEM);

    @Test
    void grpcProtocolContributesMeterProviderAndBridgeRegistry() {
        contextRunner
                .withPropertyValues(
                        "otel.exporter.otlp.protocol=grpc",
                        "otel.exporter.otlp.endpoint=http://localhost:4317",
                        "otel.metric.export.interval=1000")
                .run(context -> {
                    assertThat(context).hasSingleBean(SdkMeterProvider.class);
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                });
    }

    @Test
    void absentProtocolContributesNothing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SdkMeterProvider.class);
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
        });
    }

    @Test
    void httpProtocolContributesNothing() {
        contextRunner
                .withPropertyValues("otel.exporter.otlp.protocol=http/protobuf")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SdkMeterProvider.class);
                    assertThat(context).doesNotHaveBean(MeterRegistry.class);
                });
    }

    /**
     * Meter values are not asserted: against the no-op {@code OpenTelemetry} supplied above, the
     * bridge hands out counters that discard increments by design. Registration is this
     * configuration's responsibility; value flow is the SDK's.
     */
    @Test
    void bridgeRegistryAcceptsMicrometerInstrumentation() {
        contextRunner
                .withPropertyValues("otel.exporter.otlp.protocol=grpc")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    registry.counter("bridge.smoke").increment();
                    assertThat(registry.get("bridge.smoke").counter()).isNotNull();
                });
    }

}
