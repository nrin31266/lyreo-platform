package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.UUID;

public interface YoutubeChannelPort {
  UUID synchronize(List<String> channelIds);
}
