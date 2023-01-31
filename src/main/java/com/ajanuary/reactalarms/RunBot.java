package com.ajanuary.reactalarms;

import com.ajanuary.reactalarms.bot.Alarm;
import com.ajanuary.reactalarms.bot.Bot;
import com.ajanuary.reactalarms.bot.Config;
import com.ajanuary.reactalarms.bot.ScheduledDM;
import com.ajanuary.reactalarms.bot.Scheduler;
import com.ajanuary.reactalarms.bot.WithId;
import com.ajanuary.reactalarms.db.Database;
import com.ajanuary.reactalarms.db.SqliteDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RunBot {
  private static final Logger LOGGER = LogManager.getLogger();

  public static void main(String[] args) throws InterruptedException {
    ArgumentParser parser = ArgumentParsers.newFor("run-bot").build()
        .defaultHelp(true)
        .description("Run the bot.");
    parser.addArgument("config").help("Path of the config file").type(
        Arguments.fileType().acceptSystemIn().verifyCanRead()).required(true);
    Namespace ns;
    try {
      ns = parser.parseArgs(args);
    } catch (ArgumentParserException e) {
      parser.handleError(e);
      System.exit(1);
      return;
    }

    Config config;
    try {
      File configFile = ns.get("config");
      config = Config.parse(configFile);
    } catch (IOException e) {
      LOGGER.error("Error reading config file", e);
      System.exit(1);
      return;
    }

    Database database;
    try {
      database = new SqliteDatabase(config.database());
    } catch (SQLException e) {
      LOGGER.error("Error initializing database", e);
      System.exit(1);
      return;
    }

    Dotenv dotenv = Dotenv.load();
    JDA jda = JDABuilder.createLight(dotenv.get("BOT_TOKEN"), Collections.emptyList())
        .enableIntents(GatewayIntent.MESSAGE_CONTENT)
        .setActivity(Activity.playing("with time"))
        .enableCache(CacheFlag.FORUM_TAGS)
        .build();

    LOGGER.info("Connecting to discord...");
    jda.awaitReady();
    LOGGER.info("Connected to discord");

    Scheduler<WithId<Alarm>> alarmsScheduler = new Scheduler<>(jda, Duration.ZERO, database::getNextAlarmTime, database::getEventsBefore);
    Scheduler<WithId<ScheduledDM>> dmScheduler = new Scheduler<>(jda, config.minTimeBetweenDMs(), database::getNextDMTime, database::getDMsScheduledBefore);
    Bot bot = new Bot(jda, config, database, alarmsScheduler, dmScheduler);

    jda.addEventListener(bot);
  }
}
