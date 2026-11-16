package com.lyreo.lexicon;

import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.lexicon.application.port.LexiconRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LexiconLinkServiceTest {
    @Test void linksOnlyOneExactIdentityAndNormalizesForms() {
        var repository = mock(LexiconRepository.class);
        var id = UUID.randomUUID();
        var service = new LexiconLinkService(repository);
        when(repository.exactHeadwordIds("strasse")).thenReturn(List.of(id));
        assertThat(service.resolve(" Straße ")).contains(id);
        when(repository.exactHeadwordIds("run")).thenReturn(List.of(id, UUID.randomUUID()));
        assertThat(service.resolve("run")).isEmpty();
        when(repository.exactHeadwordIds("unknown")).thenReturn(List.of());
        assertThat(service.resolve("unknown")).isEmpty();
    }
}
