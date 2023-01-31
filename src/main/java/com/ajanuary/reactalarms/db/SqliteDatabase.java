package com.ajanuary.reactalarms.db;

import com.ajanuary.reactalarms.bot.Alarm;
import com.ajanuary.reactalarms.bot.ScheduledDM;
import com.ajanuary.reactalarms.bot.WithId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteDatabase implements Database {

  private final Connection connection;

  public SqliteDatabase(String path) throws SQLException {
    connection = DriverManager.getConnection("jdbc:sqlite:" + path);
  }

  public void createSchema() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
        create table alarm
        (
          id integer primary key autoincrement,
          forum_id string not null,
          thread_id string not null,
          time integer not null,
          timezone string not null
        )
        """);
      statement.executeUpdate("create unique index idx_alarm_thread_id on alarm(thread_id)");
      statement.executeUpdate("create index idx_alarm_time on alarm(time)");

      // We're deliberately denormalizing the data here. It makes the code a lot simpler, and the
      // size of the data isn't going to break the bank.
      statement.executeUpdate("""
        create table scheduled_dm
        (
          id integer primary key autoincrement,
          forum_id string not null,
          thread_id string not null,
          user_id string not null,
          time integer not null,
          timezone string not null,
          title string not null,
          url string not null,
          description string not null,
          tags string
        )
        """);
      statement.executeUpdate("create index idx_scheduled_dm_time on scheduled_dm(time)");
    }
  }

  @Override
  public void addAlarm(Alarm alarm) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into alarm (forum_id, thread_id, time, timezone)
        values (?, ?, ?, ?)
        """)) {
      statement.setString(1, alarm.forumId());
      statement.setString(2, alarm.threadId());
      statement.setLong(3, alarm.time().toInstant().toEpochMilli());
      statement.setString(4, alarm.time().getZone().getId());

      int rowsAffected = statement.executeUpdate();
      if (rowsAffected != 1) {
        throw new SQLException("Error inserting alarm. Expected to insert 1 row but got " + rowsAffected);
      }
    }
  }

  @Override
  public Optional<Integer> getAlarmIdForThread(String threadId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select
          id
        from
          alarm
        where
          thread_id = ?
        """)) {
      statement.setString(1, threadId);
      ResultSet rs = statement.executeQuery();
      if (!rs.next()) {
        return Optional.empty();
      }
      int id = rs.getInt(1);
      return Optional.of(id);
    }
  }

  @Override
  public void updateAlarm(WithId<Alarm> alarmWithId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        update
          alarm
        set
          time = ?,
          timezone = ?
        where
          id = ?""")) {
      statement.setLong(1, alarmWithId.item().time().toInstant().toEpochMilli());
      statement.setString(2, alarmWithId.item().time().getZone().getId());
      statement.setInt(3, alarmWithId.id());

      int rowsAffected = statement.executeUpdate();
      if (rowsAffected != 1) {
        throw new SQLException("Error updating alarm. Expected to update 1 row but got " + rowsAffected);
      }
    }
  }

  @Override
  public boolean deleteAlarm(int alarmId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        delete from
          alarm
        where
          id = ?""")) {
      statement.setInt(1, alarmId);

      int rowsAffected = statement.executeUpdate();
      if (rowsAffected > 1) {
        throw new SQLException("Error deleting alarm. Expected to delete 1 row but got " + rowsAffected);
      }
      return rowsAffected == 1;
    }
  }

  @Override
  public Optional<ZonedDateTime> getNextAlarmTime() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          select
            time,
            timezone
          from
            alarm
          order by time asc
          limit 1
          """);
      ResultSet rs = statement.getResultSet();
      if (!rs.next()) {
        return Optional.empty();
      }
      long millisSinceEpoch = rs.getLong(1);
      ZoneId zoneId = ZoneId.of(rs.getString(2));
      ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millisSinceEpoch), zoneId);
      return Optional.of(start);
    }
  }

  @Override
  public List<WithId<Alarm>> getEventsBefore(ZonedDateTime time) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select
          id,
          forum_id,
          thread_id,
          time,
          timezone
        from
          alarm
        where
          time <= ?
        """)) {
      statement.setLong(1, time.toInstant().toEpochMilli());
      ResultSet rs = statement.executeQuery();
      List<WithId<Alarm>> results = new ArrayList<>();
      while (rs.next()) {
        int id = rs.getInt(1);
        String forumId = rs.getString(2);
        String threadId = rs.getString(3);
        long millisSinceEpoch = rs.getLong(4);
        ZoneId zoneId = ZoneId.of(rs.getString(5));
        ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millisSinceEpoch), zoneId);
        results.add(new WithId<>(id, new Alarm(forumId, threadId, start)));
      }
      return results;
    }
  }

  @Override
  public void addScheduledDM(ScheduledDM scheduledDM) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        insert into scheduled_dm (forum_id, thread_id, user_id, time, timezone, title, url, description, tags)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, scheduledDM.forumId());
      statement.setString(2, scheduledDM.threadId());
      statement.setString(3, scheduledDM.userId());
      statement.setLong(4, scheduledDM.time().toInstant().toEpochMilli());
      statement.setString(5, scheduledDM.time().getZone().getId());
      statement.setString(6, scheduledDM.title());
      statement.setString(7, scheduledDM.url());
      statement.setString(8, scheduledDM.description());
      if (scheduledDM.tags().isPresent()) {
        statement.setString(9, scheduledDM.tags().get());
      } else {
        statement.setNull(9, Types.VARCHAR);
      }

      int rowsAffected = statement.executeUpdate();
      if (rowsAffected != 1) {
        throw new SQLException("Error inserting event. Expected to insert 1 row but got " + rowsAffected);
      }
    }
  }

  @Override
  public Optional<ZonedDateTime> getNextDMTime() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          select
            time,
            timezone
          from
            scheduled_dm
          order by time asc
          limit 1
          """);
      ResultSet rs = statement.getResultSet();
      if (!rs.next()) {
        return Optional.empty();
      }
      long millisSinceEpoch = rs.getLong(1);
      ZoneId zoneId = ZoneId.of(rs.getString(2));
      ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millisSinceEpoch), zoneId);
      return Optional.of(start);
    }
  }

  @Override
  public List<WithId<ScheduledDM>> getDMsScheduledBefore(ZonedDateTime time) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        select
          id,
          forum_id,
          thread_id,
          user_id,
          time,
          timezone,
          title,
          url,
          description,
          tags
        from
          scheduled_dm
        where
          time <= ?
        """)) {
      statement.setLong(1, time.toInstant().toEpochMilli());
      ResultSet rs = statement.executeQuery();
      List<WithId<ScheduledDM>> results = new ArrayList<>();
      while (rs.next()) {
        int id = rs.getInt(1);
        String forumId = rs.getString(2);
        String threadId = rs.getString(3);
        String userId = rs.getString(4);
        long millisSinceEpoch = rs.getLong(5);
        ZoneId zoneId = ZoneId.of(rs.getString(6));
        String title = rs.getString(7);
        String url = rs.getString(8);
        String description = rs.getString(9);
        Optional<String> tags = Optional.ofNullable(rs.getString(10));
        ZonedDateTime start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millisSinceEpoch), zoneId);
        results.add(new WithId<>(id, new ScheduledDM(forumId, threadId, userId, start, title, url, description, tags)));
      }
      return results;
    }
  }

  @Override
  public boolean deleteScheduledDM(int dmId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        delete from
          scheduled_dm
        where
          id = ?""")) {
      statement.setInt(1, dmId);

      int rowsAffected = statement.executeUpdate();
      if (rowsAffected > 1) {
        throw new SQLException("Error deleting scheduled dm. Expected to delete 1 row but got " + rowsAffected);
      }
      return rowsAffected == 1;
    }
  }
}
