package com.coke.nestspace.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses declarative Agent tool and command contribution files.
 */
public final class PluginContributionParser {

    private PluginContributionParser() {
    }

    public static List<PluginToolSpec> parseTools(String json) {
        try {
            JSONArray array = asArray(json);
            if (array.length() > 256) {
                throw new IllegalArgumentException("too many plugin tools");
            }
            List<PluginToolSpec> result = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = requiredObject(array, index, "tool");
                result.add(
                    new PluginToolSpec(
                        item.getString("id"),
                        item.optString("title", item.getString("id")),
                        item.optString("description", ""),
                        item.optJSONObject("input_schema") == null
                            ? "{\"type\":\"object\",\"properties\":{}}"
                            : item.getJSONObject("input_schema").toString(),
                        item.optBoolean("mutating", true),
                        item.optInt(
                            "timeout_ms",
                            PluginToolSpec.DEFAULT_TIMEOUT_MS
                        )
                    )
                );
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException error) {
            throw invalid("tools", error);
        }
    }

    public static List<PluginCommandSpec> parseCommands(String json) {
        try {
            JSONArray array = asArray(json);
            if (array.length() > 256) {
                throw new IllegalArgumentException("too many plugin commands");
            }
            List<PluginCommandSpec> result = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = requiredObject(array, index, "command");
                result.add(
                    new PluginCommandSpec(
                        item.getString("id"),
                        item.optString("title", item.getString("id")),
                        item.optString("description", ""),
                        item.optJSONObject("arguments_schema") == null
                            ? "{\"type\":\"object\",\"properties\":{}}"
                            : item.getJSONObject("arguments_schema").toString(),
                        item.optBoolean("mutating", true),
                        item.optInt(
                            "timeout_ms",
                            PluginCommandSpec.DEFAULT_TIMEOUT_MS
                        )
                    )
                );
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException error) {
            throw invalid("commands", error);
        }
    }

    private static JSONArray asArray(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("items");
        if (array == null) {
            throw new IllegalArgumentException(
                "contribution document requires an items array"
            );
        }
        return array;
    }

    private static JSONObject requiredObject(
        JSONArray array,
        int index,
        String name
    ) throws JSONException {
        JSONObject item = array.optJSONObject(index);
        if (item == null) {
            throw new IllegalArgumentException(
                name + " entries must be objects"
            );
        }
        return item;
    }

    private static IllegalArgumentException invalid(
        String field,
        JSONException error
    ) {
        return new IllegalArgumentException(
            "invalid plugin " + field + " JSON: " + error.getMessage(),
            error
        );
    }
}
