package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;

public final class PluginUiDocument {

    private final String title;
    private final List<PluginUiBlock> blocks;

    PluginUiDocument(String title, List<PluginUiBlock> blocks) {
        this.title = title;
        this.blocks = Collections.unmodifiableList(blocks);
    }

    public String getTitle() {
        return title;
    }

    public List<PluginUiBlock> getBlocks() {
        return blocks;
    }
}
