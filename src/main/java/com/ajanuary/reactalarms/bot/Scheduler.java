package com.ajanuary.reactalarms.bot;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.dv8tion.jda.api.JDA;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Scheduler<T> {
  private static final Logger LOGGER = LogManager.getLogger();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition waiting = lock.newCondition();

  @FunctionalInterface
  public interface NextEventTimeGetter {
    Optional<ZonedDateTime> get() throws Exception;
  }

  @FunctionalInterface
  public interface EventsGetter<T> {
    List<T> getEventsBefore(ZonedDateTime time) throws Exception;
  }

  private Consumer<T> onEvent;

  public Scheduler(JDA jda, TemporalAmount minTimeBetweenEvents, NextEventTimeGetter getNextEventTime, EventsGetter<T> eventsGetter) {
    Thread thread = new Thread(() -> {
      try {
        Instant timeCanRaiseNextEvent = Instant.now();
        while (true) {
          try {
            lock.lock();
            boolean hadError;
            Optional<ZonedDateTime> nextEventTime = Optional.empty();
            try {
              nextEventTime = getNextEventTime.get();
              hadError = false;
            } catch (Exception e) {
              LOGGER.error("Error getting next event", e);
              hadError = true;
            }
            while (nextEventTime.isEmpty() || nextEventTime.get().compareTo(ZonedDateTime.now()) >= 0) {
              if (nextEventTime.isEmpty()) {
                if (hadError) {
                  LOGGER.info("Waiting 1 minute");
                  // If we had an SQL error, hope that it was temporary and wait a minute.
                  waiting.await(1, TimeUnit.MINUTES);
                } else {
                  LOGGER.info("Waiting for a db notification");
                  waiting.await();
                }
              } else {
                long millisToSleep = Math.max(0, ChronoUnit.MILLIS.between(ZonedDateTime.now(), nextEventTime.get()));
                LOGGER.info("Waiting for " + millisToSleep + " ms until " + nextEventTime.get());
                waiting.await(millisToSleep, TimeUnit.MILLISECONDS);
              }
              try {
                nextEventTime = getNextEventTime.get();
                hadError = false;
              } catch (Exception e) {
                LOGGER.error("Error getting next event", e);
                hadError = true;
              }
            }
            if (this.onEvent != null) {
              jda.awaitReady();
              List<T> events = eventsGetter.getEventsBefore(ZonedDateTime.now());
              for (T event : events) {
                Thread.sleep(Math.max(0, ChronoUnit.MILLIS.between(Instant.now(), timeCanRaiseNextEvent)));
                LOGGER.info("Triggering event");
                this.onEvent.accept(event);
                timeCanRaiseNextEvent = Instant.now().plus(minTimeBetweenEvents);
              }
            }
          } catch (InterruptedException e) {
            // Rethrow so it can terminate the while loop.
            throw e;
          } catch (Exception e) {
            LOGGER.error("Error in scheduler", e);
          } finally {
            lock.unlock();
          }
        }
      } catch (InterruptedException e) {
        // Allow the thread to die
      }
    });
    thread.start();
    // TODO: Add mechanism to stop thread
  }

  public void setOnEvent(Consumer<T> onEvent) {
    this.onEvent = onEvent;
  }

  public void notifyOfDbChange() {
    try {
      LOGGER.info("Notified of db change");
      lock.lock();
      waiting.signal();
    } finally {
      lock.unlock();
    }
  }
}
