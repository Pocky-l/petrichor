package com.pockyl.petrichor.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.StormData;

import java.util.Arrays;
import java.util.Optional;

/**
 * {@code /petrichor} for operators: force a rain type, set the wetness, show the state, call a strike.
 * Only vanilla argument types are used, so clients without the mod can use the command too.
 */
@EventBusSubscriber(modid = Petrichor.MOD_ID)
public final class StormCommands {
    private static final int DEFAULT_SECONDS = 600;
    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Component.translatableWithFallback("commands.petrichor.unknown_type", "Unknown rain type: %s", id));
    private static final SimpleCommandExceptionType NO_WEATHER = new SimpleCommandExceptionType(
            Component.translatableWithFallback("commands.petrichor.no_weather", "This dimension has no weather"));
    private static final SimpleCommandExceptionType NO_TARGET = new SimpleCommandExceptionType(
            Component.translatableWithFallback("commands.petrichor.strike.failed", "No spot under open rain sky nearby"));

    private StormCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Petrichor.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("weather")
                        .then(Commands.literal("clear").executes(StormCommands::clear))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(RainType.values()).map(RainType::id), builder))
                                .executes(context -> setType(context, DEFAULT_SECONDS))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                                        .executes(context -> setType(context, IntegerArgumentType.getInteger(context, "seconds"))))))
                .then(Commands.literal("wetness")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
                                .executes(StormCommands::setWetness)))
                .then(Commands.literal("status").executes(StormCommands::status))
                .then(Commands.literal("strike").executes(StormCommands::strike)));
    }

    private static ServerLevel weatherLevel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        if (!StormData.hasWeather(level)) {
            throw NO_WEATHER.create();
        }
        return level;
    }

    private static int setType(CommandContext<CommandSourceStack> context, int seconds) throws CommandSyntaxException {
        String id = StringArgumentType.getString(context, "type");
        RainType type = RainType.byId(id);
        if (type == null) {
            throw UNKNOWN_TYPE.create(id);
        }
        ServerLevel level = weatherLevel(context);
        int ticks = seconds * 20;
        level.setWeatherParameters(0, ticks, true, type == RainType.THUNDERSTORM);
        StormData.get(level).forceType(type, level.getGameTime() + ticks);
        WeatherEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.petrichor.weather.set",
                "Weather set to %s for %s seconds", Component.translatableWithFallback(type.translationKey(), type.id()), seconds), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        level.setWeatherParameters(DEFAULT_SECONDS * 20, 0, false, false);
        StormData.get(level).forceType(null, 0L);
        WeatherEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.petrichor.weather.clear",
                "Weather cleared"), true);
        return 1;
    }

    private static int setWetness(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        float value = FloatArgumentType.getFloat(context, "value");
        StormData.get(level).setWetness(value);
        WeatherEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.petrichor.wetness.set",
                "Ground wetness set to %s", String.format("%.2f", value)), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        StormData data = StormData.get(level);
        RainType type = data.currentType(level);
        Component typeName = type == null
                ? Component.translatableWithFallback("petrichor.rain_type.none", "none")
                : Component.translatableWithFallback(type.translationKey(), type.id());
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.petrichor.status",
                "Rain: %s, ground wetness: %s", typeName, String.format("%.2f", data.wetness())), false);
        return 1;
    }

    private static int strike(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        Optional<BlockPos> target = WeatherEvents.strikeNear(level, BlockPos.containing(context.getSource().getPosition()));
        if (target.isEmpty()) {
            throw NO_TARGET.create();
        }
        BlockPos pos = target.get();
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.petrichor.strike",
                "Lightning struck at %s %s %s", pos.getX(), pos.getY(), pos.getZ()), true);
        return 1;
    }
}
