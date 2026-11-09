package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PluginFilePickerRequest {

    public static final int MAX_FILES = 20;

    private final List<String> mimeTypes;
    private final boolean multiple;

    public PluginFilePickerRequest(
        List<String> mimeTypes,
        boolean multiple
    ) {
        List<String> normalized = new ArrayList<>();
        if (mimeTypes != null) {
            for (String value : mimeTypes) {
                String mime = value == null ? "" : value.trim().toLowerCase();
                if (!mime.isEmpty() &&
                    !mime.matches("[a-z0-9.+*-]+/[a-z0-9.+*-]+")) {
                    throw new IllegalArgumentException(
                        "invalid MIME type: " + mime
                    );
                }
                if (!mime.isEmpty() && !normalized.contains(mime)) {
                    normalized.add(mime);
                }
            }
        }
        this.mimeTypes = normalized.isEmpty()
            ? Collections.singletonList("*/*")
            : Collections.unmodifiableList(normalized);
        this.multiple = multiple;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public boolean isMultiple() {
        return multiple;
    }
}
