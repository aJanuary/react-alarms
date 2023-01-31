package com.ajanuary.reactalarms;

import com.ajanuary.reactalarms.bot.Config;
import com.ajanuary.reactalarms.db.SqliteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.impl.action.StoreTrueArgumentAction;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class CreateDatabase {
  public static void main(String[] args) {
    ArgumentParser parser = ArgumentParsers.newFor("create-db").build()
        .defaultHelp(true)
        .description("Create the database for the bot.");
    parser.addArgument("config").help("Path of the config file").type(
        Arguments.fileType().acceptSystemIn().verifyCanRead()).required(true);
    parser.addArgument("--overwrite").help("Whether to overwrite an existing database").setDefault(false).action(
        new StoreTrueArgumentAction());
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
      System.err.println("Error reading config file");
      e.printStackTrace();
      System.exit(1);
      return;
    }

    Path databasePath = Paths.get(config.database());

    if (Files.exists(databasePath) && !ns.getBoolean("overwrite")) {
      System.err.println("Database " + databasePath.toAbsolutePath() + " already exists. Use --overwrite to overwrite it.");
      System.exit(1);
      return;
    }

    try {
      Files.deleteIfExists(Paths.get(ns.getString("database")));
    } catch (IOException e) {
      System.err.println("Error deleting database");
      e.printStackTrace();
      System.exit(1);
      return;
    }

    SqliteDatabase database;
    try {
      database = new SqliteDatabase(ns.getString("database"));
    } catch (SQLException e) {
      System.err.println("Error initializing database");
      e.printStackTrace();
      System.exit(1);
      return;
    }

    try {
      database.createSchema();
    } catch (SQLException e) {
      System.err.println("Error creating schema");
      e.printStackTrace();
      System.exit(1);
    }
  }
}
