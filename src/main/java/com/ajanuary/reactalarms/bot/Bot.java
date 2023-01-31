package com.ajanuary.reactalarms.bot;

import com.ajanuary.reactalarms.db.Database;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.GenericChannelEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Bot extends ListenerAdapter {

  private static final Logger LOGGER = LogManager.getLogger();
  private static final Pattern TIME_PATTERN = Pattern.compile("^\\W*(\\d{1,2})(?:[:. ](\\d{2}))?(?:\\s*(am|a\\.m\\.?|pm|p\\.m\\.?))?(?:\\W+|$)", Pattern.CASE_INSENSITIVE);

  private final JDA jda;
  private final Config config;
  private final Database database;
  private final Scheduler<WithId<Alarm>> alarmsScheduler;
  private final Scheduler<WithId<ScheduledDM>> dmScheduler;

  public Bot(JDA jda, Config config, Database database, Scheduler<WithId<Alarm>> alarmsScheduler, Scheduler<WithId<ScheduledDM>> dmScheduler) {
    this.jda = jda;
    this.config = config;
    this.database = database;
    this.alarmsScheduler = alarmsScheduler;
    this.dmScheduler = dmScheduler;

    alarmsScheduler.setOnEvent(this::handleOnAlarm);
    dmScheduler.setOnEvent(this::handleOnScheduledDM);

    LOGGER.info("Reconciling...");
    for (Config.Channel channelConfig : config.channels().values()) {
      List<ThreadChannel> threads = jda.getForumChannelById(channelConfig.id()).getThreadChannels();
      for (int i = 0; i < threads.size(); i++) {
        LOGGER.info("Reconciling " + (i + 1) + "/" + threads.size() + " on " + channelConfig.name());
        handleThreadCreateOrUpdate(threads.get(i));
      }
    }
  }

  @Override
  public void onChannelCreate(ChannelCreateEvent event) {
    handleChannelCreateOrUpdate(event);
  }

  @Override
  public void onChannelUpdateName(ChannelUpdateNameEvent event) {
    handleChannelCreateOrUpdate(event);
  }

  private void handleChannelCreateOrUpdate(GenericChannelEvent event) {
    if (event.getChannel().getType() != ChannelType.GUILD_PUBLIC_THREAD) {
      // Not a thread. Ignore.
      return;
    }
    ThreadChannel thread = event.getChannel().asThreadChannel();
    handleThreadCreateOrUpdate(thread);
}

private void handleThreadCreateOrUpdate(ThreadChannel thread) {
    LOGGER.info("Updating alarm for " + thread.getId());

    Optional<Integer> existing;
    try {
      existing = database.getAlarmIdForThread(thread.getId());
    } catch (SQLException e) {
      LOGGER.error("Error getting alarm for thread " + thread.getId(), e);
      return;
    }

    String forumId = thread.getParentChannel().getId();
    Config.Channel channel = config.channels().get(forumId);
    if (channel == null) {
      // Nothing configured for this channel
      existing.ifPresent(integer -> deleteAlarm(integer, Optional.of(thread)));
      return;
    }

    Optional<LocalTime> timeM = parseTime(thread.getName());
    if (timeM.isEmpty()) {
      LOGGER.warn("Could not parse time in '" + thread.getName() + "'. Ignoring thread.");
      existing.ifPresent(integer -> deleteAlarm(integer, Optional.of(thread)));
      return;
    }

    LocalTime time = timeM.get();
    ZonedDateTime dateTime = ZonedDateTime.of(channel.date(), time, config.zoneId()).minus(config.timeBeforeToNotify());
    if (dateTime.isBefore(ZonedDateTime.now())) {
      LOGGER.info("Alarm is in the past. Removing");
      existing.ifPresent(integer -> deleteAlarm(integer, Optional.of(thread)));
      return;
    }

    try {
      if (existing.isEmpty()) {
        database.addAlarm(new Alarm(forumId, thread.getId(), dateTime));
      } else {
        database.updateAlarm(new WithId<>(existing.get(), new Alarm(forumId, thread.getId(), dateTime)));
      }
    } catch (SQLException e) {
      LOGGER.error("Error adding alarm to database", e);
      return;
    }

    if (existing.isEmpty()) {
      thread.addReactionById(thread.getId(), config.emoji()).queue(success -> {
        alarmsScheduler.notifyOfDbChange();
        LOGGER.info("Added alarm at " + dateTime + " for " + thread.getId());
      }, err -> {
        LOGGER.error("Error adding emoji", err);
      });
    }
  }

  @Override
  public void onChannelDelete(ChannelDeleteEvent event) {
    if (event.getChannel().getType() != ChannelType.GUILD_PUBLIC_THREAD) {
      // Not a thread. Ignore.
      return;
    }
    ThreadChannel thread = event.getChannel().asThreadChannel();

    LOGGER.info("Deleting alarm for " + thread.getId());

    Optional<Integer> existing;
    try {
      existing = database.getAlarmIdForThread(thread.getId());
    } catch (SQLException e) {
      LOGGER.error("Error getting alarm for thread " + thread.getId(), e);
      return;
    }

    if (existing.isEmpty()) {
      LOGGER.warn("No alarm for " + thread.getId() + ". Ignoring");
      return;
    } else {
      deleteAlarm(existing.get(), Optional.empty());
    }
  }

  private void handleOnAlarm(WithId<Alarm> alarmWithId) {
    deleteAlarm(alarmWithId.id(), Optional.empty());

    Optional<ThreadChannel> threadM = jda.getForumChannelById(alarmWithId.item().forumId()).getThreadChannels().stream().filter(t -> t.getId().equals(alarmWithId.item().threadId())).findFirst();
    if (threadM.isEmpty()) {
      LOGGER.warn("Could not find thread " + alarmWithId.item().threadId() + ". Ignoring alarm.");
      return;
    }
    ThreadChannel thread = threadM.get();
    thread.retrieveMessageById(thread.getId()).queue(message -> {
      MessageReaction reaction = message.getReaction(config.emoji());
      if (reaction == null) {
        LOGGER.warn("Couldn't get the reaction on the message. This is usually because the time was set too soon.");
        return;
      }

      Optional<String> tags;
      if (!thread.getAppliedTags().isEmpty()) {
        tags = Optional.of(thread.getAppliedTags().stream().map(this::formatTag).collect(Collectors.joining(", ")));
      } else {
        tags = Optional.empty();
      }

      reaction.retrieveUsers().queue(users -> {
        message.clearReactions(config.emoji()).queue(success -> { }, err -> {
          LOGGER.error("Error clearing reactions for thread " + alarmWithId.item().threadId(), err);
        });

        // Don't need to wait for clear reactions to complete to start working on adding the events.
        for (User user : users) {
          if (user.isBot()) {
            continue;
          }
          ScheduledDM scheduledDM = new ScheduledDM(alarmWithId.item().forumId(), alarmWithId.item().threadId(), user.getId(), alarmWithId.item().time(), thread.getName(), thread.getJumpUrl(), message.getContentRaw(), tags);
          try {
            database.addScheduledDM(scheduledDM);
          } catch (SQLException e) {
            LOGGER.error("Error adding event for alarm " + alarmWithId.id() + " for user " + user.getId(), e);
          }
        }

        dmScheduler.notifyOfDbChange();
      }, error -> {
        LOGGER.error("Error getting reactions for alarm " + alarmWithId.id(), error);
      });
    }, err -> {
      LOGGER.error("Error getting message for thread " + thread.getId(), err);
    });
  }

  private void handleOnScheduledDM(WithId<ScheduledDM> dmWithId) {
    deleteScheduledDM(dmWithId.id());

    if (dmWithId.item().time().plus(config.maxTimeAfterToNotify()).compareTo(ZonedDateTime.now()) <= 0) {
      LOGGER.warn("DM " + dmWithId.id() + " is being processed too late after it's scheduled time of " + dmWithId.item().time() + ". Ignoring");
      return;
    }

    jda.retrieveUserById(dmWithId.item().userId()).queue(user -> {
      user.openPrivateChannel().queue(privateChannel -> {
        EmbedBuilder embedBuilder = new EmbedBuilder()
            .setTitle(dmWithId.item().title(), dmWithId.item().url())
            .addField("Description", dmWithId.item().description(), false)
            .setFooter("For all time, always.");
        dmWithId.item().tags().ifPresent(tags -> embedBuilder.addField("Tags", tags, false));

        privateChannel.sendMessage(new MessageCreateBuilder()
                .addContent("Hey y'all. You asked me to remind you about this event:")
                .addEmbeds(embedBuilder.build()).build())
            .queue(
                success -> { },
                error -> {
                  LOGGER.error("Error sending message to user " + user.getName() + " when handling DM " + dmWithId.id(), error);
                });
      }, error -> {
        LOGGER.error("Error getting user " + user.getName() + " when handling DM " + dmWithId.id(), error);
      });
    }, err -> {
      LOGGER.error("Error getting user " + dmWithId.item().userId() + " for dm " + dmWithId.id(), err);
    });
  }

  private String formatTag(ForumTag tag) {
    if (tag.getEmoji() != null) {
      return tag.getEmoji().getFormatted() + " " + tag.getName();
    } else {
      return tag.getName();
    }
  }

  private void deleteAlarm(int alarmId, Optional<ThreadChannel> threadM) {
    try {
      database.deleteAlarm(alarmId);
    } catch (SQLException e) {
      LOGGER.error("Error deleting alarm " + alarmId, e);
    }
    alarmsScheduler.notifyOfDbChange();

    threadM.ifPresent(thread -> {
      thread.retrieveMessageById(thread.getId()).queue(message -> {
        message.clearReactions(config.emoji()).queue(success -> { }, err -> {
          LOGGER.error("Error clearing reactions for thread " + thread.getId(), err);
        });
      });
    });
  }

  private void deleteScheduledDM(int dmId) {
    try {
      database.deleteScheduledDM(dmId);
    } catch (SQLException e) {
      LOGGER.error("Error deleting scheduled DM " + dmId, e);
    }
    alarmsScheduler.notifyOfDbChange();
  }

  private Optional<LocalTime> parseTime(String name) {
    Matcher matcher = TIME_PATTERN.matcher(name);
    if (!matcher.find()) {
      return Optional.empty();
    }

    int hour = Integer.parseInt(matcher.group(1));
    String minsStr = matcher.group(2);
    int mins = minsStr == null ? 0 : Integer.parseInt(minsStr);
    String amPm = matcher.group(3);

    if (minsStr == null && amPm == null) {
      // If we have neither minutes nor an am/pm indicator, then it just starts with a number.
      // These aren't really times.
      return Optional.empty();
    }

    int offset = (amPm != null && amPm.toLowerCase().startsWith("p")) ? 12 : 0;
    return Optional.of(LocalTime.of(hour + offset, mins));
  }
}