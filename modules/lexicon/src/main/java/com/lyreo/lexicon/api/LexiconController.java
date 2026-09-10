package com.lyreo.lexicon.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lexicon.application.LexiconRepository;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global learner dictionary API. Contextual lesson notes are separate lesson annotations. */
@RestController
@RequestMapping("/api/v1/lexicon")
public class LexiconController {
    private final LexiconSearchService search;
    private final LexiconRepository repository;

    public LexiconController(LexiconSearchService search, LexiconRepository repository) {
        this.search = search;
        this.repository = repository;
    }

    @GetMapping("/search")
    public List<LexiconEntry> search(@RequestParam("q") String query, @RequestParam(defaultValue = "20") int limit) {
        return search.search(query, limit);
    }

    @GetMapping("/{id}")
    public LexiconEntry get(@PathVariable UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Lexicon entry not found: " + id));
    }
}
