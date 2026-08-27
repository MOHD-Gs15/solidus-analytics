/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  com.mojang.brigadier.arguments.ArgumentType
 *  com.mojang.brigadier.arguments.IntegerArgumentType
 *  com.mojang.brigadier.builder.LiteralArgumentBuilder
 *  com.mojang.brigadier.context.CommandContext
 *  com.mojang.brigadier.exceptions.CommandSyntaxException
 *  net.minecraft.ChatFormatting
 *  net.minecraft.commands.CommandSourceStack
 *  net.minecraft.commands.Commands
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.server.permissions.Permission
 *  net.minecraft.server.permissions.Permission$HasCommandLevel
 *  net.minecraft.server.permissions.PermissionLevel
 */
package com.solidus.analytics.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import com.solidus.analytics.engine.LiveMetricsTracker;
import com.solidus.analytics.storage.AnalyticsDatabase;
import com.solidus.analytics.util.GiniCoefficient;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public class AnalyticsCommand {
    private static final int DEFAULT_HISTORY_DAYS = 7;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal((String)"analytics").requires(source -> source.permissions().hasPermission((Permission)new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))).executes(context -> AnalyticsCommand.executeDashboard((CommandContext<CommandSourceStack>)context, engine))).then(Commands.literal((String)"wealth").executes(context -> AnalyticsCommand.executeWealth((CommandContext<CommandSourceStack>)context, engine)))).then(Commands.literal((String)"inflation").executes(context -> AnalyticsCommand.executeInflation((CommandContext<CommandSourceStack>)context, engine)))).then(((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal((String)"top").then(Commands.literal((String)"items").executes(context -> AnalyticsCommand.executeTopItems((CommandContext<CommandSourceStack>)context, engine)))).then(Commands.literal((String)"buyers").executes(context -> AnalyticsCommand.executeTopBuyers((CommandContext<CommandSourceStack>)context, engine)))).then(Commands.literal((String)"sellers").executes(context -> AnalyticsCommand.executeTopSellers((CommandContext<CommandSourceStack>)context, engine))))).then(((LiteralArgumentBuilder)Commands.literal((String)"snapshot").requires(source -> source.permissions().hasPermission((Permission)new Permission.HasCommandLevel(PermissionLevel.ADMINS)))).executes(context -> AnalyticsCommand.executeSnapshot((CommandContext<CommandSourceStack>)context, engine)))).then(((LiteralArgumentBuilder)Commands.literal((String)"export").requires(source -> source.permissions().hasPermission((Permission)new Permission.HasCommandLevel(PermissionLevel.ADMINS)))).executes(context -> AnalyticsCommand.executeExport((CommandContext<CommandSourceStack>)context, engine)))).then(((LiteralArgumentBuilder)Commands.literal((String)"history").then(Commands.argument((String)"days", (ArgumentType)IntegerArgumentType.integer((int)1, (int)90)).executes(context -> AnalyticsCommand.executeHistory((CommandContext<CommandSourceStack>)context, engine, IntegerArgumentType.getInteger((CommandContext)context, (String)"days"))))).executes(context -> AnalyticsCommand.executeHistory((CommandContext<CommandSourceStack>)context, engine, 7))));
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

    private static int executeDashboard(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        Map<String, Long> topBought;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        LiveMetricsTracker metrics = engine.getLiveMetrics();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Solidus Analytics \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Live Economy Dashboard", ChatFormatting.YELLOW));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Daily Volume: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(AnalyticsCommand.formatCents(metrics.getDailyVolumeCents()))));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Transactions Today: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(String.valueOf(metrics.getDailyTransactionCount()), ChatFormatting.WHITE)));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Active Players: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(String.valueOf(metrics.getActivePlayerCount()), ChatFormatting.WHITE)));
        Map<String, Long> typeCounts = metrics.getTransactionsByType();
        if (!typeCounts.isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("  \u2500\u2500 Transaction Breakdown \u2500\u2500", ChatFormatting.DARK_AQUA));
            typeCounts.entrySet().stream().sorted(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed()).limit(6).forEach(entry -> AnalyticsCommand.sendFeedback(source, AnalyticsCommand.styled("    " + entry.getKey() + ": ", ChatFormatting.GRAY).append(AnalyticsCommand.styled(String.valueOf(entry.getValue()), ChatFormatting.WHITE))));
        }
        if (!(topBought = metrics.getTopBoughtItems(5)).isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("  \u2500\u2500 Top Bought Items \u2500\u2500", ChatFormatting.DARK_AQUA));
            topBought.forEach((material, qty) -> AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    " + material + ": ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(qty + " units", ChatFormatting.WHITE))));
        }
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeWealth(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsDatabase db = engine.getDatabase();
        AnalyticsDatabase.Snapshot latest = db.getLatestSnapshot();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Wealth Distribution \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        if (latest == null) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  No snapshot data available yet.", ChatFormatting.GRAY));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Snapshots are taken every 30 minutes.", ChatFormatting.GRAY));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
            return 1;
        }
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Gini Coefficient: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(String.format("%.4f", latest.giniCoefficient()), ChatFormatting.WHITE)).append((Component)AnalyticsCommand.styled(" (" + GiniCoefficient.interpret(latest.giniCoefficient()) + ")", ChatFormatting.YELLOW)));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Total Wealth: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(AnalyticsCommand.formatCents(latest.totalWealth()))));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Player Count: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(String.valueOf(latest.playerCount()), ChatFormatting.WHITE)));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Average Balance: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(AnalyticsCommand.formatCents(latest.avgBalance()))));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Median Balance: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(AnalyticsCommand.formatCents(latest.medianBalance()))));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Top 1% Wealth Share: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(String.format("%.1f%%", latest.top1PercentShare() * 100.0), latest.top1PercentShare() > 0.3 ? ChatFormatting.RED : ChatFormatting.GREEN)));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Active Auctions: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(latest.auctionActiveListings() + " listings ", ChatFormatting.WHITE)).append((Component)AnalyticsCommand.currency("(" + AnalyticsCommand.formatCents(latest.auctionTotalValue()) + ")")));
        long ageSeconds = (System.currentTimeMillis() - latest.timestamp()) / 1000L;
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Snapshot age: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(AnalyticsCommand.formatDuration(ageSeconds), ChatFormatting.DARK_GRAY)));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeInflation(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        InflationCalculator calculator = engine.getInflationCalculator();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Inflation Report \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        calculator.calculateAsync().thenAccept(report -> source.getServer().execute(() -> {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Money Supply: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(report.formatMoneySupply())));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Goods Value: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.currency(report.formatGoodsValue())));
            ChatFormatting ratioColor = AnalyticsCommand.getRatioColor(report.moneyToGoodsRatio);
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Money:Goods Ratio: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(report.formatRatio(), ratioColor)));
            ChatFormatting statusColor = AnalyticsCommand.getStatusColor(report.status);
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Status: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styledBold(report.status, statusColor)));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("  \u2500\u2500 Inflation Rates \u2500\u2500", ChatFormatting.DARK_AQUA));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    24h: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(report.formatRate(report.inflationRate24h), AnalyticsCommand.getRateColor(report.inflationRate24h))));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    7d:  ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(report.formatRate(report.inflationRate7d), AnalyticsCommand.getRateColor(report.inflationRate7d))));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    30d: ", ChatFormatting.GRAY).append((Component)AnalyticsCommand.styled(report.formatRate(report.inflationRate30d), AnalyticsCommand.getRateColor(report.inflationRate30d))));
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        }));
        return 1;
    }

    private static int executeTopItems(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        Map<String, Long> sold;
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        LiveMetricsTracker metrics = engine.getLiveMetrics();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Top Items \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        Map<String, Long> bought = metrics.getTopBoughtItems(10);
        if (!bought.isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("  \u2500\u2500 Most Bought \u2500\u2500", ChatFormatting.GREEN));
            int rank = 1;
            for (Map.Entry<String, Long> entry : bought.entrySet()) {
                AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    #" + rank + " ", ChatFormatting.YELLOW).append((Component)AnalyticsCommand.styled(entry.getKey() + ": ", ChatFormatting.WHITE)).append((Component)AnalyticsCommand.styled(String.valueOf(entry.getValue()) + " units", ChatFormatting.GRAY)));
                ++rank;
            }
        }
        if (!(sold = metrics.getTopSoldItems(10)).isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("  \u2500\u2500 Most Sold \u2500\u2500", ChatFormatting.RED));
            int rank = 1;
            for (Map.Entry<String, Long> entry : sold.entrySet()) {
                AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("    #" + rank + " ", ChatFormatting.YELLOW).append((Component)AnalyticsCommand.styled(entry.getKey() + ": ", ChatFormatting.WHITE)).append((Component)AnalyticsCommand.styled(String.valueOf(entry.getValue()) + " units", ChatFormatting.GRAY)));
                ++rank;
            }
        }
        if (bought.isEmpty() && sold.isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  No item data available yet.", ChatFormatting.GRAY));
        }
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeTopBuyers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Top Buyers \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Feature coming soon \u2014 requires transaction volume tracking per player.", ChatFormatting.GRAY));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeTopSellers(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Top Sellers \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Feature coming soon \u2014 requires transaction volume tracking per player.", ChatFormatting.GRAY));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeSnapshot(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("Taking analytics snapshot...", ChatFormatting.YELLOW));
        engine.getSnapshotScheduler().forceSnapshot("MANUAL");
        source.getServer().execute(() -> AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("Snapshot submitted. Check /analytics wealth for results in a few seconds.", ChatFormatting.GREEN)));
        return 1;
    }

    private static int executeExport(CommandContext<CommandSourceStack> context, AnalyticsEngine engine) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Data Export \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  CSV export feature coming in a future update.", ChatFormatting.GRAY));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  Current data can be queried directly from analytics.db", ChatFormatting.GRAY));
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static int executeHistory(CommandContext<CommandSourceStack> context, AnalyticsEngine engine, int days) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        AnalyticsDatabase db = engine.getDatabase();
        List<AnalyticsDatabase.DailyMetrics> metrics = db.getRecentDailyMetrics(days);
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Daily History (" + days + "d) \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        if (metrics.isEmpty()) {
            AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  No daily metrics recorded yet.", ChatFormatting.GRAY));
        } else {
            for (AnalyticsDatabase.DailyMetrics day : metrics) {
                String inflationStr;
                String string = inflationStr = day.inflationRate() != null ? String.format("%+.2f%%", day.inflationRate()) : "N/A";
                ChatFormatting inflationColor = day.inflationRate() != null ? (day.inflationRate() > 0.0 ? ChatFormatting.RED : ChatFormatting.GREEN) : ChatFormatting.GRAY;
                AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styled("  " + day.date() + " ", ChatFormatting.WHITE).append((Component)AnalyticsCommand.styled(day.transactionCount() + " tx ", ChatFormatting.GRAY)).append((Component)AnalyticsCommand.currency(AnalyticsCommand.formatCents(day.transactionVolume()))).append((Component)AnalyticsCommand.styled(" | Inflation: ", ChatFormatting.GRAY)).append((Component)AnalyticsCommand.styled(inflationStr, inflationColor)));
            }
        }
        AnalyticsCommand.sendFeedback(source, (Component)AnalyticsCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        return 1;
    }

    private static MutableComponent styled(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(color);
    }

    private static MutableComponent styledBold(String text, ChatFormatting color) {
        return Component.literal((String)text).withStyle(style -> style.withColor(color).withBold(Boolean.valueOf(true)));
    }

    private static MutableComponent currency(String text) {
        return Component.literal((String)text).withStyle(ChatFormatting.GOLD);
    }

    private static String formatCents(long cents) {
        double dollars = (double)cents / 100.0;
        if (dollars == (double)((long)dollars)) {
            return String.format("%,d", (long)dollars) + " S$";
        }
        return String.format("%,.2f", dollars) + " S$";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        if (seconds < 3600L) {
            return seconds / 60L + "m ago";
        }
        if (seconds < 86400L) {
            return seconds / 3600L + "h ago";
        }
        return seconds / 86400L + "d ago";
    }

    private static ChatFormatting getRatioColor(double ratio) {
        if (ratio < 0.0) {
            return ChatFormatting.GRAY;
        }
        if (ratio < 2.0) {
            return ChatFormatting.AQUA;
        }
        if (ratio < 5.0) {
            return ChatFormatting.GREEN;
        }
        if (ratio < 10.0) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.RED;
    }

    private static ChatFormatting getStatusColor(String status) {
        if (status == null) {
            return ChatFormatting.GRAY;
        }
        return switch (status) {
            case "HEALTHY" -> ChatFormatting.GREEN;
            case "DEFLATION" -> ChatFormatting.AQUA;
            case "MODERATE INFLATION" -> ChatFormatting.YELLOW;
            case "INFLATION WARNING" -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }

    private static ChatFormatting getRateColor(Double rate) {
        if (rate == null) {
            return ChatFormatting.GRAY;
        }
        if (rate > 5.0) {
            return ChatFormatting.RED;
        }
        if (rate > 2.0) {
            return ChatFormatting.YELLOW;
        }
        if (rate > 0.0) {
            return ChatFormatting.GREEN;
        }
        if (rate > -2.0) {
            return ChatFormatting.AQUA;
        }
        return ChatFormatting.BLUE;
    }
}
