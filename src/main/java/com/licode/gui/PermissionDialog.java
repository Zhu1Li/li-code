package com.licode.gui;

import com.licode.permission.PermissionResponse;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

public class PermissionDialog {

    public static void show(Window owner, String toolName, String description,
                            Consumer<PermissionResponse> onResult) {
        Stage dialogStage = new Stage();
        dialogStage.initOwner(owner);
        dialogStage.setTitle("Permission Required");

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: " + GUIConstants.BG + ";");

        Label titleLabel = new Label("LiCode wants to run: " + toolName);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + GUIConstants.TEXT_PRIMARY + ";");

        Label descLabel = new Label(description);
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + GUIConstants.TEXT_SECONDARY + ";");

        Label hintLabel = new Label("This tool may modify your system. Do you allow it?");
        hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + GUIConstants.TEXT_MUTED + ";");

        HBox buttons = new HBox(8);

        Button allowBtn = new Button("Allow");
        allowBtn.getStyleClass().add("allow-button");
        allowBtn.setOnAction(e -> {
            dialogStage.close();
            onResult.accept(PermissionResponse.ALLOW);
        });

        Button allowAlwaysBtn = new Button("Allow Always");
        allowAlwaysBtn.getStyleClass().add("allow-always-button");
        allowAlwaysBtn.setOnAction(e -> {
            dialogStage.close();
            onResult.accept(PermissionResponse.ALLOW_ALWAYS);
        });

        Button denyBtn = new Button("Deny");
        denyBtn.getStyleClass().add("deny-button");
        denyBtn.setOnAction(e -> {
            dialogStage.close();
            onResult.accept(PermissionResponse.DENY);
        });

        buttons.getChildren().addAll(allowBtn, allowAlwaysBtn, denyBtn);

        content.getChildren().addAll(titleLabel, descLabel, hintLabel, buttons);

        javafx.scene.Scene scene = new javafx.scene.Scene(content, 420, 180);
        dialogStage.setScene(scene);
        dialogStage.show();
    }
}
