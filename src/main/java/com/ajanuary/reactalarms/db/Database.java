package com.ajanuary.reactalarms.db;

import com.ajanuary.reactalarms.bot.Alarm;
import com.ajanuary.reactalarms.bot.ScheduledDM;
import com.ajanuary.reactalarms.bot.WithId;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface Database {
  void addAlarm(Alarm alarm) throws SQLException;
  Optional<Integer> getAlarmIdForThread(String threadId) throws SQLException;
  void updateAlarm(WithId<Alarm> alarmWithId) throws SQLException;
  boolean deleteAlarm(int id) throws SQLException;
  Optional<ZonedDateTime> getNextAlarmTime() throws SQLException;
  List<WithId<Alarm>> getEventsBefore(ZonedDateTime time) throws SQLException;
  void addScheduledDM(ScheduledDM event) throws SQLException;
  Optional<ZonedDateTime> getNextDMTime() throws SQLException;
  List<WithId<ScheduledDM>> getDMsScheduledBefore(ZonedDateTime zonedDateTime) throws SQLException;
  boolean deleteScheduledDM(int dmId) throws SQLException;
}
