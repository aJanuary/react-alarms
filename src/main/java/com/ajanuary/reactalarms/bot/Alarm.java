package com.ajanuary.reactalarms.bot;

import java.time.ZonedDateTime;

public record Alarm(String forumId, String threadId, ZonedDateTime time) {
}