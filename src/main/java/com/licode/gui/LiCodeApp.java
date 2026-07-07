package com.licode.gui;

import atlantafx.base.theme.PrimerDark;
import com.licode.config.AppConfig;
import com.licode.config.ConfigLoader;
import com.licode.config.McpServerConfig;
import com.licode.config.ProviderConfig;
import com.licode.runtime.LiRuntime;
import com.licode.tool.ToolRegistry;
import com.licode.tui.ChatMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class LiCodeApp extends Application {

    // Static state bridge: set by LiCode.main() before Application.launch()
    private static AppConfig staticConfig;
    private static String staticResumeId;

    private AppConfig config;
    private String resumeId;
    private ProviderConfig selectedProvider;
    private LiRuntime runtime;
    private ScheduledExecutorService cleanupExecutor;
    private Stage primaryStage;

    public static void setConfig(AppConfig config) {
        staticConfig = config;
    }

    public static void setResumeId(String resumeId) {
        staticResumeId = resumeId;
    }

    @Override
    public void init() {
        this.config = staticConfig;
        this.resumeId = staticResumeId;
        staticConfig = null;
        staticResumeId = null;
    }

    @Override
    public void start(Stage stage) {
        // Apply AtlantaFX modern dark theme
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        this.primaryStage = stage;
        stage.setTitle("LiCode");
        stage.setMinWidth(GUIConstants.MIN_WINDOW_WIDTH);
        stage.setMinHeight(GUIConstants.MIN_WINDOW_HEIGHT);

        if (config == null) {
            try {
                config = ConfigLoader.load();
            } catch (Exception e) {
                showError("Failed to load config: " + e.getMessage());
                return;
            }
        }

        List<ProviderConfig> providers = config.getProviders();
        if (providers == null || providers.isEmpty()) {
            showError("No providers configured. Add a provider to ~/.licode/config.yaml");
            return;
        }

        if (providers.size() == 1) {
            selectProvider(providers.get(0));
        } else {
            showProviderSelect(providers);
        }
    }

    private void showProviderSelect(List<ProviderConfig> providers) {
        ProviderSelectView selectView = new ProviderSelectView(providers, this::selectProvider);
        Scene scene = new Scene(selectView, GUIConstants.DEFAULT_WIDTH, GUIConstants.DEFAULT_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/css/licode.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setWidth(GUIConstants.DEFAULT_WIDTH);
        primaryStage.setHeight(GUIConstants.DEFAULT_HEIGHT);
        primaryStage.show();
    }

    void selectProvider(ProviderConfig provider) {
        this.selectedProvider = provider;
        initializeRuntime();
        showChatView();
    }

    private void initializeRuntime() {
        var registry = ToolRegistry.createDefault();
        this.runtime = LiRuntime.create(selectedProvider, registry);

        List<McpServerConfig> mcpServers = config.getMcpServers();
        Path workDir = Path.of(System.getProperty("user.dir"));

        if (mcpServers != null && !mcpServers.isEmpty()) {
            runtime.connectMcpServers(mcpServers, status -> {});
        }

        runtime.initSubAgentSystem(workDir, config.getProviders());

        if (config.getHooks() != null && !config.getHooks().isEmpty()) {
            runtime.setHookConfigs(config.getHooks());
        }

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "licode-stale-cleanup");
            t.setDaemon(true);
            return t;
        });
        runtime.startStaleCleanup(cleanupExecutor, 3600, 24);
    }

    private void showChatView() {
        ChatView chatView = new ChatView(runtime, selectedProvider, primaryStage);
        if (resumeId != null) {
            String result = runtime.resumeSession(resumeId);
            if (result != null) {
                chatView.addMessage(ChatMessage.system(result));
            }
        }
        Scene scene = new Scene(chatView, GUIConstants.DEFAULT_WIDTH, GUIConstants.DEFAULT_HEIGHT);
        scene.getStylesheets().add(getClass().getResource("/css/licode.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setWidth(GUIConstants.DEFAULT_WIDTH);
        primaryStage.setHeight(GUIConstants.DEFAULT_HEIGHT);
        primaryStage.show();
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("LiCode Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }
}
