package com.enterprise.llm.cluster.discovery;

import com.enterprise.llm.cluster.UpstreamNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * DNS-based discovery provider for headless Kubernetes services or DNS round-robin clusters.
 * Resolves target in format `<hostname-or-domain>:<port>` or `<hostname-or-domain>` (default port 8000).
 */
public class DnsClusterDiscoveryProvider implements ClusterDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(DnsClusterDiscoveryProvider.class);
    public static final String PROVIDER_TYPE = "DNS";
    public static final int DEFAULT_PORT = 8000;

    private final int defaultPort;
    private final Function<String, List<InetAddress>> dnsResolver;

    public DnsClusterDiscoveryProvider() {
        this(DEFAULT_PORT, defaultResolver());
    }

    public DnsClusterDiscoveryProvider(int defaultPort) {
        this(defaultPort, defaultResolver());
    }

    public DnsClusterDiscoveryProvider(Function<String, List<InetAddress>> dnsResolver) {
        this(DEFAULT_PORT, dnsResolver);
    }

    public DnsClusterDiscoveryProvider(int defaultPort, Function<String, List<InetAddress>> dnsResolver) {
        this.defaultPort = defaultPort > 0 ? defaultPort : DEFAULT_PORT;
        this.dnsResolver = dnsResolver != null ? dnsResolver : defaultResolver();
    }

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public List<UpstreamNode> discoverNodes(String target) {
        if (target == null || target.isBlank()) {
            return Collections.emptyList();
        }

        String cleaned = target.trim();
        if (cleaned.startsWith("http://")) {
            cleaned = cleaned.substring("http://".length());
        } else if (cleaned.startsWith("https://")) {
            cleaned = cleaned.substring("https://".length());
        }
        int slashIdx = cleaned.indexOf('/');
        if (slashIdx != -1) {
            cleaned = cleaned.substring(0, slashIdx);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        String host;
        int port = this.defaultPort;

        int colonIdx = cleaned.lastIndexOf(':');
        if (colonIdx != -1) {
            host = cleaned.substring(0, colonIdx).trim();
            String portStr = cleaned.substring(colonIdx + 1).trim();
            try {
                port = Integer.parseInt(portStr);
                if (port <= 0 || port > 65535) {
                    log.warn("Invalid port in target '{}': {}", target, port);
                    return Collections.emptyList();
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid port number format in target '{}': {}", target, portStr);
                return Collections.emptyList();
            }
        } else {
            host = cleaned;
        }

        if (host.isBlank()) {
            return Collections.emptyList();
        }

        List<InetAddress> addresses;
        try {
            addresses = dnsResolver.apply(host);
        } catch (Exception e) {
            log.warn("DNS resolution failed for host '{}': {}", host, e.getMessage());
            return Collections.emptyList();
        }

        if (addresses == null || addresses.isEmpty()) {
            return Collections.emptyList();
        }

        List<UpstreamNode> nodes = new ArrayList<>(addresses.size());
        for (InetAddress address : addresses) {
            if (address == null) {
                continue;
            }
            String ip = address.getHostAddress();
            String id = "dns-" + ip + ":" + port;
            String baseUrl = "http://" + ip + ":" + port + "/v1";
            nodes.add(new UpstreamNode(id, baseUrl));
        }

        return Collections.unmodifiableList(nodes);
    }

    private static Function<String, List<InetAddress>> defaultResolver() {
        return host -> {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses == null || addresses.length == 0) {
                    return Collections.emptyList();
                }
                return Arrays.asList(addresses);
            } catch (UnknownHostException e) {
                log.warn("DNS lookup failed for {}: {}", host, e.getMessage());
                return Collections.emptyList();
            }
        };
    }
}
