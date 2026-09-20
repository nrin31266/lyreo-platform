package com.lyreo.speechassessment.application.port;
import com.lyreo.speechassessment.domain.SpeechAttempt;
public interface SpeechAssessmentRepository { SpeechAttempt save(SpeechAttempt attempt,Integer deepJudgeScore); }
