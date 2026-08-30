package com.solidus.analytics.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.solidus.analytics.dashboard.DashboardManager;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.license.LicenseVerifier;
import com.solidus.analytics.premium.EconomyHealthScore;
import com.solidus.analytics.premium.FraudDetector;
import com.solidus.analytics.premium.WeeklyReportGenerator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public class PremiumCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        var root = Commands.literal("analytics");
        root.then(Commands.literal("health")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeHealth(context, engine)));
        root.then(Commands.literal("fraud")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeFraudList(context, engine))
            .then(Commands.literal("list").executes(context -> executeFraudList(context, engine)))
            .then(Commands.literal("scan")
                .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
                .executes(context -> executeFraudScan(context, engine))));
        root.then(Commands.literal("license")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .executes(context -> executeLicenseStatus(context, engine)));
        root.then(Commands.literal("fingerprint")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .executes(context -> executeFingerprint(context, engine)));
        root.then(Commands.literal("report").then(Commands.literal("weekly")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
            .executes(context -> executeWeeklyReport(context, engine))));
        root.then(Commands.literal("dashboard")
            .requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.ADMINS)))
            .executes(context -> executeDashboardStatus(context, engine))
            .then(Commands.literal("setup").then(Commands.argument("password", MessageArgument.message())
                .executes(context -> executeDashboardSetup(context, engine))))
            .then(Commands.literal("unlock").then(Commands.argument("password", MessageArgument.message())
                .executes(context -> executeDashboardUnlock(context, engine))))
            .then(Commands.literal("github").then(Commands.argument("owner", StringArgumentType.word())
                .then(Commands.argument("repo", StringArgumentType.word())
                    .executes(context -> executeDashboardGitHub(context, engine)))))
            .then(Commands.literal("publish").executes(context -> executeDashboardPublish(context, engine))));
        dispatcher.register(root);
    }

    private static int executeHealth(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Premium feature \u2014 license required. Use /analytics license to check status.", ChatFormatting.RED));
            return 0;
        }
        EconomyHealthScore healthScorer = engine.getHealthScore();
        if (healthScorer == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Health score calculator not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Economy Health \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        engine.getDatabase().getExecutor().submit(() -> {
            EconomyHealthScore.HealthReport report = healthScorer.compute();
            source.getServer().execute(() -> {
                ChatFormatting scoreColor = PremiumCommand.getScoreChatColor(report.overallScore);
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Overall Score: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styledBold(String.format("%.1f", report.overallScore), scoreColor)).append((Component)PremiumCommand.styled(" / 100 (" + report.getGrade() + ")", ChatFormatting.DARK_GRAY)));
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  " + report.summary, ChatFormatting.WHITE));
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("  \u2500\u2500 Factor Breakdown \u2500\u2500", ChatFormatting.DARK_AQUA));
                PremiumCommand.sendFeedback(source, PremiumCommand.formatFactorLine("Gini Inequality", report.giniScore, 25));
                PremiumCommand.sendFeedback(source, PremiumCommand.formatFactorLine("Inflation Rate", report.inflationScore, 25));
                PremiumCommand.sendFeedback(source, PremiumCommand.formatFactorLine("Money Growth", report.moneyGrowthScore, 20));
                PremiumCommand.sendFeedback(source, PremiumCommand.formatFactorLine("Activity Level", report.activityScore, 15));
                PremiumCommand.sendFeedback(source, PremiumCommand.formatFactorLine("Market Liquidity", report.liquidityScore, 15));
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
            });
        });
        return 1;
    }

    private static int executeFraudList(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Premium feature \u2014 license required.", ChatFormatting.RED));
            return 0;
        }
        FraudDetector detector = engine.getFraudDetector();
        if (detector == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Fraud detector not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Fraud Alerts \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        List<FraudDetector.FraudAlert> alerts = detector.getRecentAlerts(10);
        if (alerts.isEmpty()) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  No recent fraud alerts.", ChatFormatting.GREEN));
        } else {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Recent alerts (last 10):", ChatFormatting.YELLOW));
            for (FraudDetector.FraudAlert alert : alerts) {
                ChatFormatting severityColor = switch (alert.severity) {
                    case FraudDetector.FraudAlert.Severity.HIGH -> ChatFormatting.RED;
                    case FraudDetector.FraudAlert.Severity.MEDIUM -> ChatFormatting.YELLOW;
                    case FraudDetector.FraudAlert.Severity.LOW -> ChatFormatting.GRAY;
                };
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  [" + String.valueOf((Object)alert.severity) + "] ", severityColor).append((Component)PremiumCommand.styled(String.valueOf((Object)alert.type) + " \u2014 " + alert.playerName, ChatFormatting.WHITE)));
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("    " + alert.description, ChatFormatting.DARK_GRAY));
            }
        }
        int highCount = detector.getHighSeverityCount();
        if (highCount > 0) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  High severity alerts: " + highCount, ChatFormatting.RED));
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeFraudScan(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        if (!engine.isPremiumEnabled()) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Premium feature \u2014 license required.", ChatFormatting.RED));
            return 0;
        }
        FraudDetector detector = engine.getFraudDetector();
        if (detector == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Fraud detector not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Running fraud scan...", ChatFormatting.YELLOW));
        engine.getDatabase().getExecutor().submit(() -> {
            List<FraudDetector.FraudAlert> newAlerts = detector.runAllChecks();
            source.getServer().execute(() -> {
                if (newAlerts.isEmpty()) {
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  No suspicious patterns detected.", ChatFormatting.GREEN));
                } else {
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Found " + newAlerts.size() + " suspicious pattern(s)!", ChatFormatting.RED));
                    for (FraudDetector.FraudAlert alert : newAlerts) {
                        ChatFormatting color = alert.severity == FraudDetector.FraudAlert.Severity.HIGH ? ChatFormatting.RED : ChatFormatting.YELLOW;
                        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  [" + String.valueOf((Object)alert.severity) + "] " + String.valueOf((Object)alert.type) + " \u2014 " + alert.playerName, color));
                    }
                }
            });
        });
        return 1;
    }

    private static int executeWeeklyReport(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        WeeklyReportGenerator reportGen = engine.getWeeklyReportGenerator();
        if (reportGen == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Weekly report generator not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Generating weekly report...", ChatFormatting.YELLOW));
        engine.getDatabase().getExecutor().submit(() -> {
            Path reportPath = reportGen.forceGenerate();
            source.getServer().execute(() -> {
                if (reportPath != null) {
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Weekly report generated successfully!", ChatFormatting.GREEN));
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Saved to: " + String.valueOf(reportPath.getFileName()), ChatFormatting.GRAY));
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Full path: " + String.valueOf(reportPath.toAbsolutePath()), ChatFormatting.DARK_GRAY));
                } else {
                    PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Failed to generate weekly report. Check server logs.", ChatFormatting.RED));
                }
            });
        });
        return 1;
    }

    private static int executeLicenseStatus(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        LicenseVerifier verifier = engine.getLicenseVerifier();
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 License Status \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        if (verifier == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  License verifier not loaded.", ChatFormatting.RED));
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Premium features are disabled.", ChatFormatting.GRAY));
        } else {
            LicenseVerifier.VerificationState state = verifier.getState();
            ChatFormatting stateColor = switch (state) {
                case LicenseVerifier.VerificationState.VERIFIED -> ChatFormatting.GREEN;
                case LicenseVerifier.VerificationState.EXPIRED -> ChatFormatting.GOLD;
                case LicenseVerifier.VerificationState.FINGERPRINT_MISMATCH -> ChatFormatting.LIGHT_PURPLE;
                case LicenseVerifier.VerificationState.UNVERIFIED -> ChatFormatting.GRAY;
                case LicenseVerifier.VerificationState.INVALID -> ChatFormatting.RED;
            };
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Status: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styledBold(state.name(), stateColor)));
            if (verifier.getLicenseeName() != null) {
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Licensed to: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(verifier.getLicenseeName(), ChatFormatting.WHITE)));
            }
            if (verifier.getExpiryDate() != null) {
                LocalDate expiry = verifier.getExpiryDate();
                long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
                ChatFormatting expiryColor = daysLeft > 30L ? ChatFormatting.GREEN : (daysLeft > 7L ? ChatFormatting.YELLOW : ChatFormatting.RED);
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Expires: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(expiry.format(DateTimeFormatter.ISO_LOCAL_DATE), ChatFormatting.WHITE)).append((Component)PremiumCommand.styled(" (" + daysLeft + " days remaining)", expiryColor)));
            }
            if (verifier.getFingerprint() != null) {
                String fp = verifier.getFingerprint();
                Object fpDisplay = "ANY".equals(fp) ? "Universal (any server)" : fp + " (server-specific)";
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Fingerprint: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled((String)fpDisplay, ChatFormatting.WHITE)));
            }
            if (verifier.getErrorMessage() != null) {
                PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Error: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(verifier.getErrorMessage(), ChatFormatting.RED)));
            }
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Premium features: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(verifier.isPremiumEnabled() ? "ENABLED" : "DISABLED", verifier.isPremiumEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeFingerprint(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        String fingerprint = LicenseVerifier.computeServerFingerprint();
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Server Fingerprint \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Your server fingerprint:", ChatFormatting.GRAY));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  " + fingerprint, ChatFormatting.AQUA));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("", ChatFormatting.GRAY));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Send this fingerprint to the license seller", ChatFormatting.YELLOW));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  so they can generate a server-specific key.", ChatFormatting.YELLOW));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("", ChatFormatting.GRAY));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  If you have a universal key (fingerprint: ANY),", ChatFormatting.GRAY));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  you do not need to provide this fingerprint.", ChatFormatting.DARK_GRAY));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeDashboardStatus(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        DashboardManager dm = engine.getDashboardManager();
        if (dm == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Dashboard Status \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Encryption: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(dm.getEncryptionStatus(), ChatFormatting.WHITE)));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  GitHub Pages: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(dm.getGitHubStatus(), ChatFormatting.WHITE)));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Web Server: ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(dm.getWebServerStatus(), ChatFormatting.WHITE)));
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeDashboardSetup(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        String password;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        DashboardManager dm = engine.getDashboardManager();
        if (dm == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }
        try {
            password = MessageArgument.getMessage(context, (String)"password").getString();
        }
        catch (CommandSyntaxException e) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Invalid password argument.", ChatFormatting.RED));
            return 0;
        }
        String result = dm.setupEncryption(password);
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  " + result, ChatFormatting.GREEN));
        return 1;
    }

    private static int executeDashboardUnlock(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        String password;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        DashboardManager dm = engine.getDashboardManager();
        if (dm == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }
        try {
            password = MessageArgument.getMessage(context, (String)"password").getString();
        }
        catch (CommandSyntaxException e) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Invalid password argument.", ChatFormatting.RED));
            return 0;
        }
        String result = dm.unlockEncryption(password);
        if (result.contains("unlocked")) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  " + result, ChatFormatting.GREEN));
        } else {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  " + result, ChatFormatting.RED));
        }
        return 1;
    }

    private static int executeDashboardGitHub(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = context.getSource();
        DashboardManager dm = engine.getDashboardManager();
        if (dm == null) {
            sendFeedback(source, styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }
        String owner = StringArgumentType.getString(context, "owner");
        String repo = StringArgumentType.getString(context, "repo");
        String result = dm.setupGitHub(owner, repo);
        sendFeedback(source, styled("  " + result, result.startsWith("GitHub Pages publishing configured") ? ChatFormatting.GREEN : ChatFormatting.RED));
        return result.startsWith("GitHub Pages publishing configured") ? 1 : 0;
    }

    private static int executeDashboardPublish(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        DashboardManager dm = engine.getDashboardManager();
        if (dm == null) {
            PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Dashboard system not available.", ChatFormatting.RED));
            return 0;
        }
        PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Publishing dashboard data...", ChatFormatting.YELLOW));
        engine.getDatabase().getExecutor().submit(() -> {
            dm.publishData();
            source.getServer().execute(() -> PremiumCommand.sendFeedback(source, (Component)PremiumCommand.styled("  Dashboard data published successfully!", ChatFormatting.GREEN)));
        });
        return 1;
    }

    private static void sendFeedback(CommandSourceStack source, Component message) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            player.sendSystemMessage(message);
        }
        catch (CommandSyntaxException e) {
            source.sendSuccess(() -> message, false);
        }
    }

    private static MutableComponent styled(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(color);
    }

    private static MutableComponent styledBold(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(style -> style.withColor(color).withBold(Boolean.valueOf(true)));
    }

    private static ChatFormatting getScoreChatColor(double score) {
        if (score >= 80.0) {
            return ChatFormatting.GREEN;
        }
        if (score >= 60.0) {
            return ChatFormatting.YELLOW;
        }
        if (score >= 40.0) {
            return ChatFormatting.GOLD;
        }
        if (score >= 20.0) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.DARK_RED;
    }

    private static Component formatFactorLine(String name, double score, int weight) {
        ChatFormatting scoreColor = PremiumCommand.getScoreChatColor(score);
        return PremiumCommand.styled("    " + name + ": ", ChatFormatting.GRAY).append((Component)PremiumCommand.styled(String.format("%.0f", score), scoreColor)).append((Component)PremiumCommand.styled("/100 (weight: " + weight + "%)", ChatFormatting.DARK_GRAY));
    }
}
