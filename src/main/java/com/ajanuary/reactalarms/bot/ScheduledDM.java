package com.ajanuary.reactalarms.bot;

import java.time.ZonedDateTime;
import java.util.Optional;

public record ScheduledDM(String forumId, String threadId, String userId, ZonedDateTime time, String title, String url, String description, Optional<String> tags) {
}