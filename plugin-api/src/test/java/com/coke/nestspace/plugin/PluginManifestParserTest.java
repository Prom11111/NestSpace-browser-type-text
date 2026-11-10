package com.coke.nestspace.plugin;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginManifestParserTest {

    @Test
    public void parsesChromeStyleRegistration() {
        PluginManifest manifest = PluginManifestParser.parse(
            "{"
                + "\"manifest_version\":1,"
                + "\"id\":\"com.example.tools\","
                + "\"name\":\"Example tools\","
                + "\"version\":\"1.2.0\","
                + "\"description\":\"Example plugin\","
                + "\"publisher\":\"Example\","
                + "\"api_version\":1,"
                + "\"min_host_version\":\"1.5.0\","
                + "\"entry_class\":\"com.example.tools.Plugin\","
                + "\"priority\":20,"
                + "\"permissions\":[\"agent\",\"automation\","
                + "\"storage\",\"ui\",\"network\"],"
                + "\"optional_permissions\":[\"notifications\"],"
                + "\"host_permissions\":["
                + "\"https://api.example.com/*\","
                + "\"http://192.168.1.8:8080/*\","
                + "\"wss://events.example.com/*\","
                + "\"*.socket.example.com\"],"
                + "\"icons\":{\"128\":\"icons/plugin.png\"},"
                + "\"ui\":{\"options_page\":\"pages/options.json\","
                + "\"html_page\":\"pages/detail.html\"},"
                + "\"contributes\":{"
                + "\"agent_tools\":\"tools.json\","
                + "\"commands\":\"commands.json\","
                + "\"settings\":\"settings.schema.json\","
                + "\"llm_providers\":\"llm.json\","
                + "\"speech_providers\":\"speech.json\","
                + "\"pages\":[{\"id\":\"dashboard\",\"title\":\"Dashboard\","
                + "\"entry\":\"pages/dashboard.json\"}],"
                + "\"result_renderers\":[{\"id\":\"chart\","
                + "\"title\":\"Chart\",\"types\":[\"chart.v1\"]}]"
                + "}"
                + "}"
        );

        assertEquals("com.example.tools", manifest.getId());
        assertEquals("com.example.tools.Plugin", manifest.getEntryClass());
        assertEquals(20, manifest.getDescriptor().getPriority());
        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.NETWORK)
        );
        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.WEBSOCKET)
        );
        assertTrue(
            manifest.getOptionalCapabilities().contains(
                PluginCapability.NOTIFICATIONS
            )
        );
        assertTrue(
            manifest.getHttpHosts().contains("api.example.com")
        );
        assertTrue(
            manifest.getWebSocketHosts().contains("events.example.com")
        );
        assertTrue(
            manifest.getHttpHosts().contains("*.socket.example.com")
        );
        assertTrue(
            manifest.getWebSocketHosts().contains("*.socket.example.com")
        );
        assertEquals(
            EnumSet.of(
                PluginPermission.AGENT,
                PluginPermission.AUTOMATION,
                PluginPermission.STORAGE,
                PluginPermission.UI,
                PluginPermission.NETWORK
            ),
            PluginPermission.groupsFor(manifest.getCapabilities())
        );
        assertEquals("icons/plugin.png", manifest.getIconPath());
        assertEquals("pages/options.json", manifest.getOptionsPage());
        assertEquals("pages/detail.html", manifest.getHtmlPage());
        assertEquals(
            "tools.json",
            manifest.getContributions().getAgentToolsPath()
        );
        assertEquals(
            "speech.json",
            manifest.getContributions().getSpeechProvidersPath()
        );
        assertEquals(
            "dashboard",
            manifest.getContributions().getPages().get(0).getId()
        );
        assertEquals(
            "chart",
            manifest.getContributions().getRenderers().get(0).getId()
        );
    }

    @Test
    public void acceptsLegacyPermissionsAndRuntimeProxyAlias() {
        PluginManifest manifest = PluginManifestParser.parse(
            "{"
                + "\"manifest_version\":1,"
                + "\"id\":\"com.example.legacy\","
                + "\"name\":\"Legacy plugin\","
                + "\"version\":\"1.0.0\","
                + "\"api_version\":1,"
                + "\"entry_class\":\"com.example.legacy.Plugin\","
                + "\"permissions\":[\"agent_tools\",\"settings\","
                + "\"runtime_proxy\"]"
                + "}"
        );

        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.AGENT_TOOLS)
        );
        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.SETTINGS)
        );
        assertTrue(
            manifest.getCapabilities().contains(
                PluginCapability.RUNTIME_LAUNCH_POLICY
            )
        );
    }

    @Test
    public void deduplicatesOverlappingExpandedPermissions() {
        PluginManifest manifest = PluginManifestParser.parse(
            "{"
                + "\"manifest_version\":1,"
                + "\"id\":\"com.example.overlap\","
                + "\"name\":\"Overlap plugin\","
                + "\"version\":\"1.0.0\","
                + "\"api_version\":1,"
                + "\"entry_class\":\"com.example.overlap.Plugin\","
                + "\"permissions\":[\"agent\",\"agent_tools\","
                + "\"network\",\"websocket\"]"
                + "}"
        );

        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.AGENT_TOOLS)
        );
        assertTrue(
            manifest.getCapabilities().contains(PluginCapability.WEBSOCKET)
        );
    }

    @Test
    public void rejectsUnknownOrDuplicatePermissions() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"permissions\":[\"shell\"]"
            ))
        );
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"permissions\":[\"storage\",\"storage\"]"
            ))
        );
    }

    @Test
    public void hostPermissionRequiresMatchingCapability() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"permissions\":[\"websocket\"],"
                    + "\"host_permissions\":[\"https://example.com/*\"]"
            ))
        );
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"permissions\":[],"
                    + "\"host_permissions\":[\"wss://example.com/*\"]"
            ))
        );
    }

    @Test
    public void speechContributionRequiresCapability() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"contributes\":{\"speech_providers\":\"speech.json\"}"
            ))
        );
    }

    @Test
    public void rejectsHostPathRestrictions() {
        assertThrows(IllegalArgumentException.class, () ->
            PluginManifestParser.parse(manifest(
                "\"permissions\":[\"network\"],"
                    + "\"host_permissions\":[\"https://example.com/api/*\"]"
            ))
        );
    }

    private static String manifest(String extra) {
        return "{"
            + "\"manifest_version\":1,"
            + "\"id\":\"com.example.tools\","
            + "\"name\":\"Example tools\","
            + "\"version\":\"1.2.0\","
            + "\"api_version\":1,"
            + "\"entry_class\":\"com.example.tools.Plugin\""
            + (extra.isEmpty() ? "" : "," + extra)
            + "}";
    }
}
