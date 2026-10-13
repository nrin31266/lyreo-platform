package com.lyreo.lexicon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lyreo.lexicon.application.port.LexiconRepository;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import org.junit.jupiter.api.Test;

class LexiconSearchServiceTest {
    @Test
    void usesTheReleaseBuildersNfkcAndFullCasefoldLookupContract() {
        LexiconRepository repository = mock(LexiconRepository.class);
        when(repository.search("Bergstraße", "bergstrasse", 20))
            .thenReturn(LexiconSearchResult.empty());
        when(repository.search("ʔayʔaǰuθəm", "ʔayʔaǰuθəm", 20))
            .thenReturn(LexiconSearchResult.empty());
        LexiconSearchService service = new LexiconSearchService(repository);

        service.search("  Ｂｅｒｇｓｔｒａßｅ  ", 20);
        service.search("ʔayʔaǰuθəm", 20);
        service.search("\u0085\u001cBergstraße\u0085", 20);

        verify(repository, org.mockito.Mockito.times(2)).search("Bergstraße", "bergstrasse", 20);
        verify(repository).search("ʔayʔaǰuθəm", "ʔayʔaǰuθəm", 20);
    }

    @Test
    void boundsLimitAndSkipsEmptyQueries() {
        LexiconRepository repository = mock(LexiconRepository.class);
        LexiconSearchService service = new LexiconSearchService(repository);

        assertThat(service.search(" \t ", 20)).isEqualTo(LexiconSearchResult.empty());
        verifyNoInteractions(repository);

        service.search("apple", 0);
        service.search("apple", 500);
        verify(repository).search("apple", "apple", 1);
        verify(repository).search("apple", "apple", 50);
    }
}
