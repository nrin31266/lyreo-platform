package com.lyreo.ai.application.port;

import com.lyreo.ai.application.execution.AiExecutionCommand;
import com.lyreo.ai.application.execution.AiExecutionResult;

public interface AiExecutionGateway {
    AiExecutionResult execute(AiExecutionCommand command);
}
