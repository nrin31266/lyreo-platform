package com.lyreo.lesson.domain.authoring;

import com.lyreo.contracts.errors.RequestValidationException;

/** Safe timing diagnostics carry indices and bounds, never transcript or provider payloads. */
public final class TimingValidationException extends RequestValidationException {
    private final String code;
    public TimingValidationException(String code, String detail) { super(detail); this.code=code; }
    public String code() { return code; }
}
