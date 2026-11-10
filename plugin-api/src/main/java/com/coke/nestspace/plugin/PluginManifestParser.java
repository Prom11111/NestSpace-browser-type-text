package com.coke.nestspace.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates a Chrome-style plugin manifest.json.
 */
public final class PluginManifestParser {

    private static final int MAX_NAME_CHARS = 80;
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final int MAX_VERSION_CHARS = 64;
    private static final int MAX_HOST_PERMISSIONS = 64;
    private static final Pattern PLUGIN_ID =
        Pattern.compile("[a-z0-9][a-z0-9._-]{2,127}");
    private static final Pattern JAVA_CLASS =
        Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
        );
    private static final Pattern HOST =
        Pattern.compile(
            "(\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"
        );

    private PluginManifestParser() {
    }

    public static PluginManifest parse(String json) {
        try {
            return parseObject(new JSONObject(json));
        } catch (JSONException error) {
            throw new IllegalArgumentException(
                "invalid plugin manifest JSON: " + error.getMessage(),
                error
            );
        }
    }

    private static PluginManifest parseObject(JSONObject json)
        throws JSONException {
        int schemaVersion = json.getInt("manifest_version");
        if (schemaVersion != PluginManifest.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "unsupported manifest_version: " + schemaVersion
            );
        }
        int apiVersion = json.getInt("api_version");
        if (apiVersion != PluginDescriptor.API_VERSION) {
            throw new IllegalArgumentException(
                "unsupported api_version: " + apiVersion
            );
        }

        String id = requiredString(json, "id", 128);
        if (!PLUGIN_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                "id must be 3-128 lowercase letters, digits, '.', '_' or '-'"
            );
        }
        String name = requiredString(json, "name", MAX_NAME_CHARS);
        String version = requiredString(json, "version", MAX_VERSION_CHARS);
        String description = optionalString(
            json,
            "description",
            MAX_DESCRIPTION_CHARS
        );
        String publisher = optionalString(json, "publisher", MAX_NAME_CHARS);
        String entryClass = requiredString(json, "entry_class", 512);
        if (!JAVA_CLASS.matcher(entryClass).matches()) {
            throw new IllegalArgumentException(
                "entry_class must be a Java binary class name"
            );
        }
        String minimumHostVersion = optionalString(
            json,
            "min_host_version",
            MAX_VERSION_CHARS
        );
        String homepageUrl = optionalHttpUrl(json, "homepage_url");

        int priority = json.optInt("priority", 0);
        Set<PluginCapability> capabilities = parseCapabilities(
            json.optJSONArray("permissions"),
            "permissions"
        );
        Set<PluginCapability> optionalCapabilities = parseCapabilities(
            json.optJSONArray("optional_permissions"),
            "optional_permissions"
        );
        for (PluginCapability capability : optionalCapabilities) {
            if (capabilities.contains(capability)) {
                throw new IllegalArgumentException(
                    "optional permission is already required: " + capability
                );
            }
        }

        HostPermissions hosts = parseHostPermissions(
            json.optJSONArray("host_permissions")
        );
        if (!hosts.http.isEmpty() &&
            !capabilities.contains(PluginCapability.NETWORK)
        ) {
            throw new IllegalArgumentException(
                "HTTP host permissions require NETWORK"
            );
        }
        if (!hosts.webSocket.isEmpty() &&
            !capabilities.contains(PluginCapability.WEBSOCKET)
        ) {
            throw new IllegalArgumentException(
                "WebSocket host permissions require WEBSOCKET"
            );
        }

        JSONObject icons = json.optJSONObject("icons");
        String iconPath = icons == null
            ? ""
            : validateResourcePath(
                icons.optString("128", icons.optString("48", "")),
                "icons.128"
            );
        JSONObject ui = json.optJSONObject("ui");
        String optionsPage = ui == null
            ? ""
            : validateResourcePath(
                ui.optString("options_page", ""),
                "ui.options_page"
            );
        String htmlPage = ui == null
            ? ""
            : validateResourcePath(
                ui.optString("html_page", ""),
                "ui.html_page"
            );
        if (!htmlPage.isEmpty() &&
            !htmlPage.toLowerCase(Locale.ROOT).endsWith(".html")
        ) {
            throw new IllegalArgumentException(
                "ui.html_page must point to an HTML file"
            );
        }
        PluginContributions contributions = parseContributions(
            json.optJSONObject("contributes")
        );
        requireContributionCapability(
            !contributions.getAgentToolsPath().isEmpty(),
            capabilities,
            PluginCapability.AGENT_TOOLS,
            "contributes.agent_tools"
        );
        requireContributionCapability(
            !contributions.getCommandsPath().isEmpty(),
            capabilities,
            PluginCapability.COMMANDS,
            "contributes.commands"
        );
        requireContributionCapability(
            !contributions.getSettingsPath().isEmpty(),
            capabilities,
            PluginCapability.SETTINGS,
            "contributes.settings"
        );
        requireContributionCapability(
            !contributions.getLlmProvidersPath().isEmpty(),
            capabilities,
            PluginCapability.LLM_PROVIDERS,
            "contributes.llm_providers"
        );
        requireContributionCapability(
            !contributions.getSpeechProvidersPath().isEmpty(),
            capabilities,
            PluginCapability.SPEECH_PROVIDERS,
            "contributes.speech_providers"
        );
        requireContributionCapability(
            !contributions.getPages().isEmpty(),
            capabilities,
            PluginCapability.UI_PAGES,
            "contributes.pages"
        );
        requireContributionCapability(
            !contributions.getRenderers().isEmpty(),
            capabilities,
            PluginCapability.UI_RENDERERS,
            "contributes.result_renderers"
        );

        PluginDescriptor descriptor = new PluginDescriptor(
            id,
            name,
            version,
            apiVersion,
            priority,
            capabilities
        );
        return new PluginManifest(
            descriptor,
            publisher,
            description,
            entryClass,
            minimumHostVersion,
            homepageUrl,
            iconPath,
            optionsPage,
            htmlPage,
            contributions,
            optionalCapabilities,
            hosts.http,
            hosts.webSocket
        );
    }

    private static PluginContributions parseContributions(JSONObject json) {
        if (json == null) {
            return new PluginContributions(
                "",
                "",
                "",
                "",
                "",
                Collections.emptyList(),
                Collections.emptyList()
            );
        }
        return new PluginContributions(
            contributionPath(json, "agent_tools"),
            contributionPath(json, "commands"),
            contributionPath(json, "settings"),
            contributionPath(json, "llm_providers"),
            contributionPath(json, "speech_providers"),
            parsePages(json.optJSONArray("pages")),
            parseRenderers(json.optJSONArray("result_renderers"))
        );
    }

    private static void requireContributionCapability(
        boolean present,
        Set<PluginCapability> capabilities,
        PluginCapability capability,
        String field
    ) {
        if (present && !capabilities.contains(capability)) {
            throw new IllegalArgumentException(
                field + " requires " + capability
            );
        }
    }

    private static String contributionPath(JSONObject json, String name) {
        String value = json.optString(name, "");
        return value.isBlank()
            ? ""
            : validateResourcePath(value, "contributes." + name);
    }

    private static List<PluginPageSpec> parsePages(JSONArray array) {
        if (array == null) return Collections.emptyList();
        if (array.length() > 64) {
            throw new IllegalArgumentException("too many plugin pages");
        }
        List<PluginPageSpec> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject page = array.optJSONObject(index);
            if (page == null) {
                throw new IllegalArgumentException(
                    "contributes.pages entries must be objects"
                );
            }
            result.add(
                new PluginPageSpec(
                    page.optString("id"),
                    page.optString("title"),
                    page.optString("description"),
                    page.optString("entry")
                )
            );
        }
        return result;
    }

    private static List<PluginRendererSpec> parseRenderers(JSONArray array) {
        if (array == null) return Collections.emptyList();
        if (array.length() > 128) {
            throw new IllegalArgumentException(
                "too many plugin result renderers"
            );
        }
        List<PluginRendererSpec> result = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject renderer = array.optJSONObject(index);
            if (renderer == null) {
                throw new IllegalArgumentException(
                    "contributes.result_renderers entries must be objects"
                );
            }
            List<String> types = new ArrayList<>();
            JSONArray typeArray = renderer.optJSONArray("types");
            if (typeArray != null) {
                for (int typeIndex = 0;
                     typeIndex < typeArray.length();
                     typeIndex++) {
                    types.add(typeArray.optString(typeIndex));
                }
            }
            result.add(
                new PluginRendererSpec(
                    renderer.optString("id"),
                    renderer.optString("title"),
                    types
                )
            );
        }
        return result;
    }

    private static Set<PluginCapability> parseCapabilities(
        JSONArray array,
        String field
    ) {
        Set<PluginCapability> result = EnumSet.noneOf(PluginCapability.class);
        if (array == null) {
            return result;
        }
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            PluginCapability capability;
            try {
                capability = PluginCapability.valueOf(
                    value.toUpperCase(Locale.ROOT).replace('-', '_')
                );
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                    field + " contains unknown permission: " + value
                );
            }
            if (!result.add(capability)) {
                throw new IllegalArgumentException(
                    field + " contains duplicate permission: " + value
                );
            }
        }
        return result;
    }

    private static HostPermissions parseHostPermissions(JSONArray array) {
        if (array == null || array.length() == 0) {
            return new HostPermissions(
                new LinkedHashSet<>(),
                new LinkedHashSet<>()
            );
        }
        if (array.length() > MAX_HOST_PERMISSIONS) {
            throw new IllegalArgumentException(
                "too many host_permissions"
            );
        }
        Set<String> http = new LinkedHashSet<>();
        Set<String> webSocket = new LinkedHashSet<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                    "host_permissions contains an empty value"
                );
            }
            String scheme = "*";
            String remainder = value;
            int separator = value.indexOf("://");
            if (separator >= 0) {
                scheme = value.substring(0, separator).toLowerCase(Locale.ROOT);
                remainder = value.substring(separator + 3);
            }
            String host = parseHost(remainder);
            switch (scheme) {
                case "http":
                case "https":
                    http.add(host);
                    break;
                case "ws":
                case "wss":
                    webSocket.add(host);
                    break;
                case "*":
                    http.add(host);
                    webSocket.add(host);
                    break;
                default:
                    throw new IllegalArgumentException(
                        "unsupported host permission scheme: " + scheme
                    );
            }
        }
        return new HostPermissions(http, webSocket);
    }

    private static String parseHost(String value) {
        int pathStart = value.indexOf('/');
        String hostPort = pathStart < 0 ? value : value.substring(0, pathStart);
        String path = pathStart < 0 ? "" : value.substring(pathStart);
        if (!path.isEmpty() && !path.equals("/") && !path.equals("/*")) {
            throw new IllegalArgumentException(
                "host permission path restrictions are not supported"
            );
        }
        if (hostPort.contains("@")) {
            throw new IllegalArgumentException(
                "userinfo is not allowed in host_permissions"
            );
        }
        String host;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("invalid IPv6 host");
            }
            host = hostPort.substring(1, close);
            String port = hostPort.substring(close + 1);
            if (!port.isEmpty() && !port.matches(":\\d{1,5}")) {
                throw new IllegalArgumentException("invalid host port");
            }
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            if (colon >= 0 && !hostPort.substring(colon + 1).matches("\\d{1,5}")) {
                throw new IllegalArgumentException("invalid host port");
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException(
                "invalid host_permission: " + host
            );
        }
        return host;
    }

    private static String requiredString(
        JSONObject json,
        String name,
        int maxChars
    ) {
        String value = optionalString(json, name, maxChars);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String optionalString(
        JSONObject json,
        String name,
        int maxChars
    ) {
        String value = json.optString(name, "").trim();
        if (value.length() > maxChars) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    private static String optionalHttpUrl(JSONObject json, String name) {
        String value = optionalString(json, name, 2_048);
        if (value.isEmpty()) {
            return "";
        }
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null ||
            (!scheme.equalsIgnoreCase("http") &&
                !scheme.equalsIgnoreCase("https")) ||
            uri.getHost() == null
        ) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) URL");
        }
        return value;
    }

    private static String validateResourcePath(String value, String field) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return "";
        }
        if (path.startsWith("/") ||
            path.contains("\u0000") ||
            java.util.Arrays.asList(path.split("/")).contains("..")
        ) {
            throw new IllegalArgumentException(
                field + " must be a relative package path"
            );
        }
        return path;
    }

    private static final class HostPermissions {
        private final Set<String> http;
        private final Set<String> webSocket;

        private HostPermissions(
            Set<String> http,
            Set<String> webSocket
        ) {
            this.http = http;
            this.webSocket = webSocket;
        }
    }
}
