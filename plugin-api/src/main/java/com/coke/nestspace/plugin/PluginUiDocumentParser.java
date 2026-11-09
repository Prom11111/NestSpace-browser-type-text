package com.coke.nestspace.plugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PluginUiDocumentParser {

    private static final Set<String> TYPES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
        "text",
        "button",
        "image",
        "divider",
        "key_value",
        "progress",
        "list",
        "chart",
        "custom"
        ))
    );

    private PluginUiDocumentParser() {
    }

    public static PluginUiDocument parse(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("blocks");
            if (array == null) array = new JSONArray();
            if (array.length() > 200) {
                throw new IllegalArgumentException(
                    "plugin page contains too many blocks"
                );
            }
            List<PluginUiBlock> blocks = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    throw new IllegalArgumentException(
                        "plugin page blocks must be objects"
                    );
                }
                String type = item.optString("type").trim();
                if (!TYPES.contains(type)) {
                    throw new IllegalArgumentException(
                        "unsupported plugin page block: " + type
                    );
                }
                blocks.add(
                    new PluginUiBlock(
                        type,
                        item.optString("text"),
                        item.optString("label"),
                        item.optString("action"),
                        item.opt("data") == null
                            ? "{}"
                            : String.valueOf(item.opt("data"))
                    )
                );
            }
            return new PluginUiDocument(
                root.optString("title", ""),
                blocks
            );
        } catch (JSONException error) {
            throw new IllegalArgumentException(
                "invalid plugin page JSON: " + error.getMessage(),
                error
            );
        }
    }
}
