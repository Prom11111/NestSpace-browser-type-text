package com.coke.nestspace.container;

/**
 * In-process UI control for one container process, exposed to the main
 * process so MCP/Agent tools can observe, screenshot and tap the virtual app
 * without an AccessibilityService.
 */
interface IContainerUi {
    boolean isAvailable();
    String observeJson();
    byte[] screenshotJpeg(int maxWidth, int quality, boolean rejectBlank);
    boolean tapNode(String id);
    boolean tapText(String text);
    boolean tapAt(int x, int y);
    boolean setText(String id, String text);
    boolean waitForText(String text, long timeoutMs);
    boolean pressBack();
    boolean bringToFront();
    boolean isMirrorMode();
}
