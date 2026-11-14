package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.UUID;

public interface YoutubeChannelPort {
  boolean configured();

  UUID synchronize(List<String> channelIds);
}
