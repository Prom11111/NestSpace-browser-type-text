package com.coke.nestspace.plugin;

public final class PluginUiBlock {

    private final String type;
    private final String text;
    private final String label;
    private final String action;
    private final String dataJson;

    PluginUiBlock(
        String type,
        String text,
        String label,
        String action,
        String dataJson
    ) {
        this.type = type;
        this.text = text;
        this.label = label;
        this.action = action;
        this.dataJson = dataJson;
    }

    public String getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public String getLabel() {
        return label;
    }

    public String getAction() {
        return action;
    }

    public String getDataJson() {
        return dataJson;
    }
}
