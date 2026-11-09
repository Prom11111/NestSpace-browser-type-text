package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.Set;

/**
 * Validated registration data from a plugin's manifest.json.
 */
public final class PluginManifest {

    public static final int SCHEMA_VERSION = 1;

    private final PluginDescriptor descriptor;
    private final String publisher;
    private final String description;
    private final String entryClass;
    private final String minimumHostVersion;
    private final String homepageUrl;
    private final String iconPath;
    private final String optionsPage;
    private final String htmlPage;
    private final PluginContributions contributions;
    private final Set<PluginCapability> optionalCapabilities;
    private final Set<String> httpHosts;
    private final Set<String> webSocketHosts;

    PluginManifest(
        PluginDescriptor descriptor,
        String publisher,
        String description,
        String entryClass,
        String minimumHostVersion,
        String homepageUrl,
        String iconPath,
        String optionsPage,
        String htmlPage,
        PluginContributions contributions,
        Set<PluginCapability> optionalCapabilities,
        Set<String> httpHosts,
        Set<String> webSocketHosts
    ) {
        this.descriptor = descriptor;
        this.publisher = publisher;
        this.description = description;
        this.entryClass = entryClass;
        this.minimumHostVersion = minimumHostVersion;
        this.homepageUrl = homepageUrl;
        this.iconPath = iconPath;
        this.optionsPage = optionsPage;
        this.htmlPage = htmlPage;
        this.contributions = contributions;
        this.optionalCapabilities = immutable(optionalCapabilities);
        this.httpHosts = immutable(httpHosts);
        this.webSocketHosts = immutable(webSocketHosts);
    }

    public PluginDescriptor getDescriptor() {
        return descriptor;
    }

    public String getId() {
        return descriptor.getId();
    }

    public String getName() {
        return descriptor.getName();
    }

    public String getVersion() {
        return descriptor.getVersion();
    }

    public String getPublisher() {
        return publisher;
    }

    public String getDescription() {
        return description;
    }

    public String getEntryClass() {
        return entryClass;
    }

    public String getMinimumHostVersion() {
        return minimumHostVersion;
    }

    public String getHomepageUrl() {
        return homepageUrl;
    }

    public String getIconPath() {
        return iconPath;
    }

    public String getOptionsPage() {
        return optionsPage;
    }

    public String getHtmlPage() {
        return htmlPage;
    }

    public PluginContributions getContributions() {
        return contributions;
    }

    public Set<PluginCapability> getCapabilities() {
        return descriptor.getCapabilities();
    }

    public Set<PluginCapability> getOptionalCapabilities() {
        return optionalCapabilities;
    }

    public Set<String> getHttpHosts() {
        return httpHosts;
    }

    public Set<String> getWebSocketHosts() {
        return webSocketHosts;
    }

    private static <T> Set<T> immutable(Set<T> values) {
        return values == null || values.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(values);
    }
}
