package com.lyreo.lexicon.infrastructure.persistence;
import static org.assertj.core.api.Assertions.*;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LexiconCursorTest {
    @Test void roundTripsRankAndIdentityAndRejectsUntrustedCursors() {
        var cursor=new JdbcLexiconEditorSource.Cursor(3,UUID.randomUUID());
        assertThat(JdbcLexiconEditorSource.Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(JdbcLexiconEditorSource.Cursor.decode(null).rank()).isEqualTo(-1);
        assertThatThrownBy(()->JdbcLexiconEditorSource.Cursor.decode("not-a-cursor")).isInstanceOf(RequestValidationException.class);
    }
}
