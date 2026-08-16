package com.scivicslab.gpubroker.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.smallrye.config.ConfigMapping;

/**
 * {@code broker.*} configuration: which physical nodes to probe, and
 * per-host:port overrides/declarations of capability ({@code
 * CapabilityConfig_260810_oo01}).
 *
 * <p>{@code capabilities} is a repeating, multi-attribute-per-key structure
 * (host:port -> several fields), which is why this uses {@code
 * @ConfigMapping} rather than individual {@code @ConfigProperty} fields —
 * see {@code CapabilityConfig_260810_oo01} "なぜ @ConfigMapping へ切り替えたか".
 */
@ConfigMapping(prefix = "broker")
public interface BrokerConfig {

    /** Physical compute node IPs/CIDR blocks to probe. Empty if unset. */
    Optional<List<String>> nodes();

    /** Per host:port capability overrides/declarations, keyed by "host:port". Empty if unset. */
    Map<String, EndpointCapability> capabilities();

    interface EndpointCapability {

        /** Overrides {@code EndpointProbe.defaultMaxConcurrency()} for this one instance. Used by {@code Builder}. */
        OptionalInt maxConcurrency();

        /** Operator-declared, display-only — not verified against the real service. */
        OptionalInt maxContextLength();

        /** Operator-declared, display-only — not verified against the real service. */
        Optional<Boolean> thinkingModeSupported();

        /** Operator-declared, display-only — not verified against the real service. */
        Optional<Boolean> toolCallingSupported();
    }
}
