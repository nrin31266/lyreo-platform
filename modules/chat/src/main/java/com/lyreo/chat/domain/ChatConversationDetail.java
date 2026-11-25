package com.lyreo.chat.domain;

import java.util.List;

public record ChatConversationDetail(
    ChatConversation conversation,
    List<ChatMessage> messages
) {}
