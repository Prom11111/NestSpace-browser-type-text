package com.example.runtimedemo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.coke.nestspace.plugin.AgentHookPlugin;
import com.coke.nestspace.plugin.AgentPromptContext;
import com.coke.nestspace.plugin.AgentPromptContribution;
import com.coke.nestspace.plugin.AgentQuestionContext;
import com.coke.nestspace.plugin.AgentQuestionResult;
import com.coke.nestspace.plugin.AgentToolCallContext;
import com.coke.nestspace.plugin.AgentToolCallDecision;
import com.coke.nestspace.plugin.AgentToolHookPlugin;
import com.coke.nestspace.plugin.AgentToolPresentation;
import com.coke.nestspace.plugin.AgentToolPresentationContext;
import com.coke.nestspace.plugin.AgentToolPresentationPlugin;
import com.coke.nestspace.plugin.AgentToolProvider;
import com.coke.nestspace.plugin.AgentToolResultContext;
import com.coke.nestspace.plugin.AgentToolPlugins;
import com.coke.nestspace.plugin.AgentToolViewFactory;
import com.coke.nestspace.plugin.PluginCapability;
import com.coke.nestspace.plugin.PluginCommandCall;
import com.coke.nestspace.plugin.PluginCommandProvider;
import com.coke.nestspace.plugin.PluginCommandResult;
import com.coke.nestspace.plugin.PluginCommandSpec;
import com.coke.nestspace.plugin.PluginContext;
import com.coke.nestspace.plugin.PluginDescriptor;
import com.coke.nestspace.plugin.PluginEvent;
import com.coke.nestspace.plugin.PluginEventApi;
import com.coke.nestspace.plugin.PluginEventListener;
import com.coke.nestspace.plugin.PluginFileExportRequest;
import com.coke.nestspace.plugin.PluginFilePickerRequest;
import com.coke.nestspace.plugin.PluginHttpMethod;
import com.coke.nestspace.plugin.PluginHttpRequest;
import com.coke.nestspace.plugin.PluginLifecycle;
import com.coke.nestspace.plugin.PluginLlmModel;
import com.coke.nestspace.plugin.PluginLlmProvider;
import com.coke.nestspace.plugin.PluginLlmRequest;
import com.coke.nestspace.plugin.PluginLlmResponse;
import com.coke.nestspace.plugin.PluginNotification;
import com.coke.nestspace.plugin.PluginNotificationAction;
import com.coke.nestspace.plugin.PluginPageSpec;
import com.coke.nestspace.plugin.PluginPickedFile;
import com.coke.nestspace.plugin.PluginRendererSpec;
import com.coke.nestspace.plugin.PluginShareRequest;
import com.coke.nestspace.plugin.PluginSettingsViewContext;
import com.coke.nestspace.plugin.PluginSettingsViewProvider;
import com.coke.nestspace.plugin.PluginSseConnection;
import com.coke.nestspace.plugin.PluginSseEvent;
import com.coke.nestspace.plugin.PluginSseListener;
import com.coke.nestspace.plugin.PluginSseRequest;
import com.coke.nestspace.plugin.PluginSubscription;
import com.coke.nestspace.plugin.PluginTaskBody;
import com.coke.nestspace.plugin.PluginTaskContext;
import com.coke.nestspace.plugin.PluginTaskHandle;
import com.coke.nestspace.plugin.PluginTaskRequest;
import com.coke.nestspace.plugin.PluginToolCall;
import com.coke.nestspace.plugin.PluginToolResult;
import com.coke.nestspace.plugin.PluginToolSpec;
import com.coke.nestspace.plugin.PluginToolViewContext;
import com.coke.nestspace.plugin.PluginUiProvider;
import com.coke.nestspace.plugin.PluginWebSocket;
import com.coke.nestspace.plugin.PluginWebSocketListener;
import com.coke.nestspace.plugin.PluginWebSocketRequest;
import com.coke.nestspace.plugin.RuntimeLaunchMode;
import com.coke.nestspace.plugin.RuntimeLaunchPolicy;
import com.coke.nestspace.plugin.RuntimeProxyPlugin;
import com.coke.nestspace.plugin.RuntimeVersionMatcher;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Broad, non-hardening plugin example.
 */
public final class RuntimeDemoPlugin implements
    PluginLifecycle,
    AgentHookPlugin,
    AgentToolProvider,
    AgentToolHookPlugin,
    AgentToolPresentationPlugin,
    PluginCommandProvider,
    PluginUiProvider,
    PluginLlmProvider,
    PluginSettingsViewProvider,
    RuntimeProxyPlugin {

    private static final String PLUGIN_ID = "com.example.runtime.demo";
    private static final String TOOL_ID = "runtime_demo";
    private static final String TOOL_NAME =
        AgentToolPlugins.qualify(PLUGIN_ID, TOOL_ID);
    private static final String DEMO_PACKAGE_NAME = "com.example.target";
    private static final String DEMO_VERSION_GLOB = "1*";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
        PLUGIN_ID,
        "Runtime Demo Plugin",
        "1.0.0",
        PluginDescriptor.API_VERSION,
        100,
        EnumSet.of(
            PluginCapability.AGENT_PROMPT,
            PluginCapability.AGENT_QUESTION,
            PluginCapability.AGENT_TOOLS,
            PluginCapability.AGENT_TOOL_HOOKS,
            PluginCapability.AGENT_TOOL_PRESENTATION,
            PluginCapability.LLM_PROVIDERS,
            PluginCapability.COMMANDS,
            PluginCapability.TASKS,
            PluginCapability.EVENTS,
            PluginCapability.SETTINGS,
            PluginCapability.SECRETS,
            PluginCapability.CACHE,
            PluginCapability.DATABASE,
            PluginCapability.FILE_PICKER,
            PluginCapability.FILE_EXPORT,
            PluginCapability.SHARE,
            PluginCapability.CLIPBOARD,
            PluginCapability.UI_PAGES,
            PluginCapability.UI_RENDERERS,
            PluginCapability.NOTIFICATIONS,
            PluginCapability.NETWORK,
            PluginCapability.WEBSOCKET,
            PluginCapability.RUNTIME_LAUNCH_POLICY,
            PluginCapability.RUNTIME_PROXY
        )
    );

    private volatile PluginContext context;
    private volatile PluginSubscription eventSubscription;

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onAttach(PluginContext context) {
        this.context = context;
    }

    @Override
    public void onDetach() {
        PluginSubscription subscription = eventSubscription;
        eventSubscription = null;
        if (subscription != null) subscription.close();
        context = null;
    }

    @Override
    public View createSettingsView(PluginSettingsViewContext settingsContext) {
        Context ui = settingsContext.getContext();
        int padding = Math.round(20 * ui.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(ui);
        title.setText("Runtime Demo settings");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title);

        TextView description = new TextView(ui);
        description.setText(
            "This screen is a plugin-owned Android View. Replace it with any " +
                "layout, controls or custom drawing you need."
        );
        description.setPadding(0, padding / 2, 0, padding);
        layout.addView(description);

        Switch enabled = new Switch(ui);
        enabled.setText("Enable demo tool");
        layout.addView(enabled);

        Button save = new Button(ui);
        save.setText("Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        saveParams.topMargin = padding;
        layout.addView(save, saveParams);

        TextView status = new TextView(ui);
        status.setPadding(0, padding / 2, 0, 0);
        layout.addView(status);

        settingsContext.getHost().settings()
            .getBoolean("enabled", true)
            .thenAccept(value -> layout.post(() -> enabled.setChecked(value)));
        save.setOnClickListener(view -> {
            save.setEnabled(false);
            status.setText("Saving…");
            settingsContext.getHost().settings()
                .putBoolean("enabled", enabled.isChecked())
                .whenComplete((ignored, error) -> layout.post(() -> {
                    save.setEnabled(true);
                    status.setText(
                        error == null ? "Saved" : error.getMessage()
                    );
                }));
        });
        return layout;
    }

    @Override
    public List<RuntimeLaunchPolicy> launchPolicies() {
        return Collections.singletonList(
            new RuntimeLaunchPolicy(
                "demo-container-policy",
                DEMO_PACKAGE_NAME,
                RuntimeVersionMatcher.glob(DEMO_VERSION_GLOB),
                RuntimeLaunchMode.CONTAINER,
                "Runtime demo: keep the normal container launch path"
            )
        );
    }

    @Override
    public List<AgentPromptContribution> promptContributions(
        AgentPromptContext promptContext
    ) {
        return Collections.singletonList(
            new AgentPromptContribution(
                "runtime-demo",
                "Runtime Demo",
                "The runtime_demo tool is a broad API example. Use it only when " +
                    "the user explicitly asks to test plugin capabilities.",
                100
            )
        );
    }

    @Override
    public CompletableFuture<AgentQuestionResult> answerAsync(
        AgentQuestionContext question
    ) {
        String message = question.getUserMessage().toLowerCase(Locale.ROOT);
        if (message.contains("runtime demo") ||
            message.contains("运行时示例")) {
            return CompletableFuture.completedFuture(
                AgentQuestionResult.handled(
                    "Runtime Demo Plugin 可测试工具、View、通知、剪贴板、任务、" +
                        "文件、HTTP、SSE、WebSocket、事件和 LLM Provider。"
                )
            );
        }
        return CompletableFuture.completedFuture(
            AgentQuestionResult.notHandled()
        );
    }

    @Override
    public CompletableFuture<List<PluginToolSpec>> listToolsAsync() {
        return CompletableFuture.completedFuture(
            Collections.singletonList(
                new PluginToolSpec(
                    TOOL_ID,
                    "Runtime demo",
                    "Exercises the NestSpace plugin APIs without hardening logic.",
                    "{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{\"action\":{\"type\":\"string\"}}," +
                        "\"required\":[\"action\"]," +
                        "\"additionalProperties\":false" +
                        "}",
                    false,
                    60_000
                )
            )
        );
    }

    @Override
    public CompletableFuture<PluginToolResult> invokeAsync(
        PluginToolCall call
    ) {
        PluginContext host = context;
        if (host == null) {
            return failed("plugin is not attached", "PLUGIN_DETACHED");
        }
        try {
            String action = new JSONObject(call.getArgumentsJson())
                .optString("action", "info");
            switch (action) {
                case "settings":
                    return host.settings()
                        .putString("last_action", action)
                        .thenCompose(ignored ->
                            host.settings().getString("last_action", "")
                        )
                        .thenApply(value -> buildResult(
                            json -> json.put("setting", value)
                        ));
                case "secret":
                    return host.secrets()
                        .put("demo_secret", "example-secret")
                        .thenCompose(ignored ->
                            host.secrets().get("demo_secret", "")
                        )
                        .thenApply(value -> buildResult(
                            json -> json.put("stored", !value.isEmpty())
                        ));
                case "cache":
                    return host.cache()
                        .putText("runtime-demo", "cached-value", 60)
                        .thenCompose(ignored ->
                            host.cache().getText("runtime-demo")
                        )
                        .thenApply(value -> {
                            try {
                                return success(
                                    new JSONObject()
                                        .put("cached", value)
                                        .put("ttl_seconds", 60)
                                );
                            } catch (Throwable error) {
                                return PluginToolResult.failure(
                                    error.getMessage(),
                                    "CACHE_ERROR"
                                );
                            }
                        });
                case "sqlite":
                    return host.databases().open("runtime-demo")
                        .thenApply(database -> {
                            database.execSQL(
                                "CREATE TABLE IF NOT EXISTS notes " +
                                    "(id INTEGER PRIMARY KEY, body TEXT)"
                            );
                            database.execSQL(
                                "INSERT INTO notes(body) VALUES (?)",
                                new Object[]{"SQLite works"}
                            );
                            int count = 0;
                            try (android.database.Cursor cursor =
                                     database.rawQuery(
                                         "SELECT COUNT(*) FROM notes",
                                         null
                                     )) {
                                if (cursor.moveToFirst()) {
                                    count = cursor.getInt(0);
                                }
                            }
                            try {
                                return success(
                                    new JSONObject()
                                        .put("rows", count)
                                        .put("path_scope", "plugin-private")
                                );
                            } catch (Throwable error) {
                                return PluginToolResult.failure(
                                    error.getMessage(),
                                    "SQLITE_ERROR"
                                );
                            }
                        });
                case "notify":
                    return host.notifications().post(
                        new PluginNotification(
                            "runtime-demo",
                            "Runtime Demo",
                            "Plugin notification with an action",
                            Collections.singletonList(
                                new PluginNotificationAction(
                                    "open",
                                    "Open"
                                )
                            )
                        )
                    ).thenApply(ignored -> buildResult(
                        json -> json.put("notification", "posted")
                    ));
                case "clipboard":
                    return host.clipboard()
                        .writeText("Runtime Demo clipboard", false)
                        .thenCompose(ignored -> host.clipboard().readText())
                        .thenApply(value -> buildResult(
                            json -> json.put("clipboard", value)
                        ));
                case "share":
                    return host.share().share(
                        new PluginShareRequest(
                            "Runtime Demo",
                            "Shared by a NestSpace plugin",
                            "",
                            "text/plain",
                            null
                        )
                    ).thenApply(ignored -> buildResult(
                        json -> json.put("shared", true)
                    ));
                case "task":
                    PluginTaskHandle task = host.tasks().submit(
                        new PluginTaskRequest("runtime demo task", 10_000),
                        new DemoTaskBody()
                    );
                    return task.getResult()
                        .thenApply(result -> buildResult(json -> json
                            .put("successful", result.isSuccessful())
                            .put("progress", task.getProgressPercent())
                            .put("message", result.getMessage())
                        ));
                case "export":
                    return host.files().export(
                        new PluginFileExportRequest(
                            "runtime-demo.json",
                            "application/json",
                            new JSONObject()
                                .put("plugin", PLUGIN_ID)
                                .put("demo", true)
                                .toString()
                                .getBytes(StandardCharsets.UTF_8)
                        )
                    ).thenApply(file -> buildResult(json -> json
                        .put("name", file.getDisplayName())
                        .put("size", file.getSizeBytes())
                    ));
                case "pick":
                    return host.files().pickFiles(
                        new PluginFilePickerRequest(
                            Collections.singletonList("*/*"),
                            false
                        )
                    ).thenApply(files -> buildResult(json -> json
                        .put("count", files.size())
                        .put(
                            "first",
                            files.isEmpty()
                                ? ""
                                : files.get(0).getDisplayName()
                        )
                    ));
                case "http":
                    return host.network().execute(
                        new PluginHttpRequest(
                            PluginHttpMethod.GET,
                            "https://example.com/",
                            Collections.emptyMap(),
                            "",
                            15_000
                        )
                    ).thenApply(response -> buildResult(json -> json
                        .put("status", response.getStatusCode())
                        .put("length", response.getBody().length())
                    ));
                case "sse":
                    PluginSseConnection sse = host.network().openSse(
                        new PluginSseRequest(
                            "https://example.com/events"
                        ),
                        new NoOpSseListener()
                    );
                    return CompletableFuture.completedFuture(
                        success(
                            new JSONObject()
                                .put("url", sse.getUrl())
                                .put("opened", true)
                        )
                    );
                case "websocket":
                    PluginWebSocket socket = host.webSockets().connect(
                        new PluginWebSocketRequest(
                            "wss://echo.websocket.events"
                        ),
                        new NoOpWebSocketListener()
                    );
                    return CompletableFuture.completedFuture(
                        success(
                            new JSONObject()
                                .put("url", socket.getUrl())
                                .put("opened", true)
                        )
                    );
                case "events":
                    PluginEventApi events = host.events();
                    PluginSubscription previous = eventSubscription;
                    if (previous != null) previous.close();
                    eventSubscription = events.subscribe(
                        Collections.singleton("demo.ping"),
                        new PluginEventListener() {
                            @Override
                            public void onEvent(PluginEvent event) {
                                host.settings().putString(
                                    "last_event",
                                    event.getType()
                                );
                            }
                        }
                    );
                    events.publish(
                        new PluginEvent(
                            "demo.ping",
                            PLUGIN_ID,
                            "{}",
                            System.currentTimeMillis()
                        )
                    );
                    return CompletableFuture.completedFuture(
                        success(
                            new JSONObject().put("event", "demo.ping")
                        )
                    );
                case "info":
                default:
                    return CompletableFuture.completedFuture(
                        success(
                            new JSONObject()
                                .put("plugin", PLUGIN_ID)
                                .put("host", host.getHostVersion())
                                .put("locale", host.getLocale().toLanguageTag())
                                .put(
                                    "capabilities",
                                    DESCRIPTOR.getCapabilities().size()
                                )
                        )
                    );
            }
        } catch (Throwable error) {
            return failed(
                error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage(),
                "RUNTIME_DEMO_ERROR"
            );
        }
    }

    @Override
    public CompletableFuture<AgentToolCallDecision> beforeToolCallAsync(
        AgentToolCallContext call
    ) {
        if (TOOL_NAME.equals(call.getToolName()) &&
            call.getArgumentsJson().contains("\"blocked\"")) {
            return CompletableFuture.completedFuture(
                AgentToolCallDecision.block(
                    "Runtime Demo blocked this call."
                )
            );
        }
        return CompletableFuture.completedFuture(
            AgentToolCallDecision.proceed()
        );
    }

    @Override
    public CompletableFuture<Void> afterToolCallAsync(
        AgentToolResultContext resultContext
    ) {
        PluginContext host = context;
        if (host != null && TOOL_NAME.equals(resultContext.getToolName())) {
            host.settings().putString(
                "last_demo_successful",
                Boolean.toString(resultContext.isSuccessful())
            );
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<AgentToolPresentation> presentAsync(
        AgentToolPresentationContext presentationContext
    ) {
        if (!TOOL_NAME.equals(presentationContext.getToolName())) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(
            new AgentToolPresentation(
                "Runtime Demo",
                presentationContext.getResultText(),
                presentationContext.getResultText(),
                presentationContext.isSuccessful() ? "Completed" : "Failed",
                "example.runtime.card",
                new DemoViewFactory()
            )
        );
    }

    @Override
    public CompletableFuture<List<PluginCommandSpec>> listCommandsAsync() {
        return CompletableFuture.completedFuture(
            Collections.singletonList(
                new PluginCommandSpec(
                    "diagnostics",
                    "Plugin diagnostics",
                    "Returns host and capability information.",
                    "{\"type\":\"object\",\"properties\":{}}",
                    false,
                    30_000
                )
            )
        );
    }

    @Override
    public CompletableFuture<PluginCommandResult> invokeCommandAsync(
        PluginCommandCall call
    ) {
        PluginContext host = context;
        if (host == null) {
            return CompletableFuture.completedFuture(
                PluginCommandResult.failure(
                    "plugin is not attached",
                    "PLUGIN_DETACHED"
                )
            );
        }
        try {
        return CompletableFuture.completedFuture(
            PluginCommandResult.success(
                "Runtime Demo is loaded",
                new JSONObject()
                    .put("plugin", PLUGIN_ID)
                    .put("host", host.getHostVersion())
                    .put("capabilities", DESCRIPTOR.getCapabilities().size())
                    .toString()
            )
        );
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(
                PluginCommandResult.failure(
                    error.getMessage(),
                    "COMMAND_ERROR"
                )
            );
        }
    }

    @Override
    public CompletableFuture<List<PluginPageSpec>> listPagesAsync() {
        return CompletableFuture.completedFuture(
            Collections.singletonList(
                new PluginPageSpec(
                    "overview",
                    "Runtime Demo",
                    "Broad plugin API example",
                    "pages/overview.json"
                )
            )
        );
    }

    @Override
    public CompletableFuture<List<PluginRendererSpec>> listRenderersAsync() {
        return CompletableFuture.completedFuture(
            Collections.singletonList(
                new PluginRendererSpec(
                    "example.runtime.card",
                    "Runtime demo result",
                    Collections.singletonList("runtime.demo.v1")
                )
            )
        );
    }

    @Override
    public CompletableFuture<String> readPageAsync(String pageId) {
        return CompletableFuture.completedFuture(
            "{" +
                "\"version\":1," +
                "\"title\":\"Runtime Demo\"," +
                "\"blocks\":[" +
                    "{\"type\":\"text\",\"text\":\"This plugin demonstrates " +
                        "the full extension surface without hardening code.\"}," +
                    "{\"type\":\"button\",\"label\":\"Diagnostics\"," +
                        "\"action\":\"com.example.runtime.demo/diagnostics\"}" +
                "]" +
                "}"
        );
    }

    @Override
    public String getProviderId() {
        return PLUGIN_ID;
    }

    @Override
    public String getDisplayName() {
        return "Runtime Demo Provider";
    }

    @Override
    public List<PluginLlmModel> getModels() {
        return Collections.singletonList(
            new PluginLlmModel("runtime-demo", "Runtime Demo", 32_000, false)
        );
    }

    @Override
    public CompletableFuture<PluginLlmResponse> executeAsync(
        PluginLlmRequest request,
        Consumer<String> onTextDelta
    ) {
        return CompletableFuture.supplyAsync(() -> {
            if (request.isStreamEnabled()) {
                onTextDelta.accept("Runtime ");
                onTextDelta.accept("Demo");
            }
            return demoResponse(request);
        });
    }

    private PluginLlmResponse demoResponse(PluginLlmRequest request) {
        return new PluginLlmResponse(
            "Runtime Demo provider received model=" + request.getModelId(),
            "[]",
            "stop",
            0,
            0
        );
    }

    private static PluginToolResult success(JSONObject value) {
        return PluginToolResult.success(value.toString());
    }

    private static PluginToolResult buildResult(JsonBuilder builder) {
        try {
            JSONObject value = new JSONObject();
            builder.build(value);
            return success(value);
        } catch (Throwable error) {
            return PluginToolResult.failure(
                error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage(),
                "JSON_RESULT_ERROR"
            );
        }
    }

    private interface JsonBuilder {
        void build(JSONObject value) throws Exception;
    }

    private static CompletableFuture<PluginToolResult> failed(
        String message,
        String errorCode
    ) {
        return CompletableFuture.completedFuture(
            PluginToolResult.failure(message, errorCode)
        );
    }

    private static final class DemoTaskBody implements PluginTaskBody {
        @Override
        public void run(PluginTaskContext context) throws Exception {
            context.reportProgress(25, "Starting");
            context.checkCancelled();
            TimeUnit.MILLISECONDS.sleep(80);
            context.reportProgress(100, "Complete");
        }
    }

    private static final class NoOpSseListener implements PluginSseListener {
        @Override
        public void onOpen(
            PluginSseConnection connection,
            int statusCode,
            String contentType
        ) {
            connection.close();
        }

        @Override
        public void onEvent(PluginSseConnection connection, PluginSseEvent event) {
        }
    }

    private static final class NoOpWebSocketListener
        implements PluginWebSocketListener {
        @Override
        public void onOpen(PluginWebSocket socket) {
            socket.sendText("runtime-demo");
            socket.close(1000, "demo complete");
        }
    }

    private static final class DemoViewFactory
        implements AgentToolViewFactory {
        @Override
        public View createView(PluginToolViewContext context) {
            LinearLayout root = new LinearLayout(context.getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(30, 24, 30, 24);

            TextView title = new TextView(context.getContext());
            title.setText("Runtime Demo");
            title.setTextColor(Color.rgb(32, 52, 88));
            title.setTextSize(18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(title);

            TextView body = new TextView(context.getContext());
            body.setText(context.getResultText());
            body.setTextColor(Color.rgb(65, 82, 112));
            body.setTextSize(14);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = 10;
            root.addView(body, params);
            return root;
        }
    }
}
