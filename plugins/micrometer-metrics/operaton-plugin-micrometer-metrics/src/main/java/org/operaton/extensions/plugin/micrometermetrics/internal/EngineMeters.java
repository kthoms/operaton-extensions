/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.operaton.extensions.plugin.micrometermetrics.MetricNames;

/**
 * Registers the plugin's meters on a {@link MeterRegistry}, adds the engine tag to each and
 * remembers them so they can be removed when the plugin is closed. Thread-safe.
 *
 * <p>The engine name must be unique per registry: Micrometer returns the existing meter for an
 * identical id.
 */
public final class EngineMeters {

    private final MeterRegistry registry;
    private final Tags commonTags;
    private final Set<Meter.Id> registered = ConcurrentHashMap.newKeySet();

    public EngineMeters(MeterRegistry registry, String engineName) {
        this.registry = registry;
        this.commonTags = Tags.of(MetricNames.TAG_ENGINE, engineName);
    }

    public Counter counter(String name, String description, Tag... tags) {
        Counter counter = Counter.builder(name)
                .description(description)
                .tags(commonTags)
                .tags(Tags.of(tags))
                .register(registry);
        registered.add(counter.getId());
        return counter;
    }

    public <T> void functionCounter(String name, String description, T source, ToDoubleFunction<T> value) {
        FunctionCounter counter = FunctionCounter.builder(name, source, value)
                .description(description)
                .tags(commonTags)
                .register(registry);
        registered.add(counter.getId());
    }

    public void gauge(String name, String description, Supplier<Number> value, Tag... tags) {
        Gauge gauge = Gauge.builder(name, value)
                .description(description)
                .tags(commonTags)
                .tags(Tags.of(tags))
                .strongReference(true)
                .register(registry);
        registered.add(gauge.getId());
    }

    /** Removes every meter this instance registered. */
    public void removeAll() {
        for (Meter.Id id : registered) {
            registry.remove(id);
        }
        registered.clear();
    }
}
