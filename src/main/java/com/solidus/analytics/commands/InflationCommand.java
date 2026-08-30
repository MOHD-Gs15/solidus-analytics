package com.solidus.analytics.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.solidus.analytics.engine.AnalyticsEngine;
import com.solidus.analytics.engine.InflationCalculator;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public class InflationCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AnalyticsEngine engine) {
        dispatcher.register((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal((String)"inflation").requires(source -> source.permissions().hasPermission((Permission)new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))).executes(context -> InflationCommand.executeInflation((CommandContext<CommandSourceStack>)context, engine, "24h"))).then(Commands.literal((String)"day").executes(context -> InflationCommand.executeInflation((CommandContext<CommandSourceStack>)context, engine, "24h")))).then(Commands.literal((String)"week").executes(context -> InflationCommand.executeInflation((CommandContext<CommandSourceStack>)context, engine, "7d")))).then(Commands.literal((String)"month").executes(context -> InflationCommand.executeInflation((CommandContext<CommandSourceStack>)context, engine, "30d"))));
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

    private static int executeInflation(CommandContext<CommandSourceStack> context, AnalyticsEngine engine, String period) {
        CommandSourceStack source = (CommandSourceStack)context.getSource();
        InflationCalculator calculator = engine.getInflationCalculator();
        InflationCommand.sendFeedback(source, (Component)InflationCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Inflation Report \u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        calculator.calculateAsync().thenAccept(report -> source.getServer().execute(() -> {
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("  Money Supply: ", ChatFormatting.GRAY).append((Component)InflationCommand.currency(report.formatMoneySupply())));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("  Goods Value: ", ChatFormatting.GRAY).append((Component)InflationCommand.currency(report.formatGoodsValue())));
            ChatFormatting ratioColor = InflationCommand.getRatioColor(report.moneyToGoodsRatio);
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("  Money:Goods Ratio: ", ChatFormatting.GRAY).append((Component)InflationCommand.styled(report.formatRatio(), ratioColor)));
            ChatFormatting statusColor = InflationCommand.getStatusColor(report.status);
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("  Economic Status: ", ChatFormatting.GRAY).append((Component)InflationCommand.styledBold(report.status, statusColor)));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styledBold("  \u2500\u2500 Inflation Rate \u2500\u2500", ChatFormatting.DARK_AQUA));
            Double rate = switch (period) {
                case "7d" -> report.inflationRate7d;
                case "30d" -> report.inflationRate30d;
                default -> report.inflationRate24h;
            };
            String periodLabel = switch (period) {
                case "7d" -> "7-Day";
                case "30d" -> "30-Day";
                default -> "24-Hour";
            };
            ChatFormatting rateColor = InflationCommand.getRateColor(rate);
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    " + periodLabel + ": ", ChatFormatting.GRAY).append((Component)InflationCommand.styled(report.formatRate(rate), rateColor)));
            if (!period.equals("24h")) {
                InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    24h: ", ChatFormatting.DARK_GRAY).append((Component)InflationCommand.styled(report.formatRate(report.inflationRate24h), ChatFormatting.DARK_GRAY)));
            }
            if (!period.equals("7d")) {
                InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    7d:  ", ChatFormatting.DARK_GRAY).append((Component)InflationCommand.styled(report.formatRate(report.inflationRate7d), ChatFormatting.DARK_GRAY)));
            }
            if (!period.equals("30d")) {
                InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    30d: ", ChatFormatting.DARK_GRAY).append((Component)InflationCommand.styled(report.formatRate(report.inflationRate30d), ChatFormatting.DARK_GRAY)));
            }
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styledBold("  \u2500\u2500 Reference \u2500\u2500", ChatFormatting.DARK_AQUA));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    Ratio < 2:1 = Deflation", ChatFormatting.AQUA));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    Ratio 2-5:1 = Healthy", ChatFormatting.GREEN));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    Ratio 5-10:1 = Moderate Inflation", ChatFormatting.YELLOW));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styled("    Ratio > 10:1 = Inflation Warning", ChatFormatting.RED));
            InflationCommand.sendFeedback(source, (Component)InflationCommand.styledBold("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550", ChatFormatting.GOLD));
        }));
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
