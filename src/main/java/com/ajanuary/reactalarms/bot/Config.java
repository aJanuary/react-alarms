package com.ajanuary.reactalarms.bot;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAmount;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public record Config(String database, ZoneId zoneId, Emoji emoji, TemporalAmount timeBeforeToNotify, TemporalAmount maxTimeAfterToNotify, TemporalAmount minTimeBetweenDMs, Map<String, Channel> channels) {
  public record Channel(String id, String name, LocalDate date) { }

  public static Config parse(File configFile) throws IOException {
    TomlParseResult result = Toml.parse(configFile.toPath());
    String database = result.getString("database", () -> "ttt.db");
    ZoneId zone = ZoneId.of(result.getString("zone", () -> "UTC"));
    Emoji emoji = Emoji.fromUnicode(result.getString("emoji", () -> "U+23F0"));
    long minsBeforeToNotify = result.getLong("mins_before_to_notify", () -> 5);
    long maxMinsAfterToNotify = result.getLong("max_mins_after_to_notify", () -> 5);
    long minMillisBetweenDMs = result.getLong("min_ms_between_dms", () -> 500);
    TomlTable channelsTable = result.getTable("channel");
    Map<String, Channel> channels;
    if (channelsTable == null) {
      channels = new HashMap<>();
    } else {
      channels = channelsTable.keySet().stream().map(key -> {
        TomlTable channelTable = channelsTable.getTable(key);
        String id = channelTable.getString("id");
        LocalDate date = channelTable.getLocalDate("date");
        return new Channel(id, key, date);
      }).collect(Collectors.toMap(Channel::id, Function.identity()));
    }
    return new Config(database, zone, emoji, Duration.ofMinutes(minsBeforeToNotify) , Duration.ofMinutes(maxMinsAfterToNotify), Duration.ofMillis(minMillisBetweenDMs), channels);
  }
}
