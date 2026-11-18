package com.lyreo.toeic.application.port;
public interface PassageCleaner {
    String clean(String html);
    boolean hasContent(String html);
}
