package com.licode.gui;

import com.licode.config.ProviderConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;
import java.util.function.Consumer;

public class ProviderSelectView extends VBox {

    private static final String LOGO =
            """
             _     _  ____          _     \s
            | |   (_)/ ___|___   __| | ___\s
            | |   | | |   / _ \\ / _` |/ _ \\
            | |___| | |__| (_) | (_| |  __/
            |_____|_|\\____\\___/ \\__,_|\\___|
            """;

    public ProviderSelectView(List<ProviderConfig> providers, Consumer<ProviderConfig> onSelect) {
        setAlignment(Pos.CENTER);
        setSpacing(16);
        setPadding(new Insets(40));
        setStyle("-fx-background-color: " + GUIConstants.BG + ";");

        // Logo
        Text logoText = new Text(LOGO);
        logoText.getStyleClass().add("logo-text");
        TextFlow logoFlow = new TextFlow(logoText);
        logoFlow.setStyle("-fx-text-alignment: center;");

        Text versionText = new Text("  v1.0.0 — AI Coding Assistant");
        versionText.getStyleClass().add("logo-subtitle");

        getChildren().addAll(logoFlow, versionText);

        // Spacer
        VBox spacer = new VBox();
        spacer.setMinHeight(16);
        getChildren().add(spacer);

        // Title
        Label title = new Label("Select a Provider");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + GUIConstants.TEXT_PRIMARY + ";");

        Label subtitle = new Label("Choose which AI model to use for this session");
        subtitle.getStyleClass().add("provider-subtitle");

        getChildren().addAll(title, subtitle);

        for (ProviderConfig provider : providers) {
            VBox card = new VBox(4);
            card.getStyleClass().add("provider-card");
            card.setMaxWidth(400);

            Label nameLabel = new Label(provider.getName());
            nameLabel.getStyleClass().add("provider-name");

            Label modelLabel = new Label(provider.getModel());
            modelLabel.getStyleClass().add("provider-model");

            String protocolLabel = provider.getProtocol() != null ? provider.getProtocol() : "";
            Label protoLabel = new Label(protocolLabel);
            protoLabel.getStyleClass().add("provider-protocol");

            card.getChildren().addAll(nameLabel, modelLabel);
            if (!protocolLabel.isEmpty()) {
                card.getChildren().add(protoLabel);
            }

            card.setOnMouseClicked(e -> onSelect.accept(provider));

            getChildren().add(card);
        }
    }
}
