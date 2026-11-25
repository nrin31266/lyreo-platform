package com.lyreo.vocabulary.domain;

import com.lyreo.vocabulary.domain.VocabularyStudyModels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates deterministic, §6-compliant vocabulary practice questions across all 6 modes
 * and computes per-mode availability counts for a unit's entries.
 */
public final class VocabularyExerciseGenerator {

    private static final List<String> OPTION_KEYS = List.of("A", "B", "C", "D");
    private static final Map<String, List<String>> IRREGULAR_INFLECTIONS = buildIrregularMap();
    private final VocabularyAnswerGrader grader = new VocabularyAnswerGrader();

    /** Computes eligible question counts for each of the 6 practice modes in a unit. */
    public Map<PracticeMode, Integer> modeAvailability(List<StudyEntrySource> entries) {
        Map<PracticeMode, Integer> counts = new EnumMap<>(PracticeMode.class);
        long seed = 1L;
        for (PracticeMode mode : PracticeMode.values()) {
            counts.put(mode, generateQuestions(entries, mode, entries.size(), seed).size());
        }
        return counts;
    }

    /** Generates up to {@code limit} eligible questions for the requested mode. */
    public List<SnapshotQuestion> generateQuestions(
        List<StudyEntrySource> entries,
        PracticeMode mode,
        int limit,
        long seed
    ) {
        if (entries == null || entries.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<SnapshotQuestion> generated = new ArrayList<>();
        for (StudyEntrySource entry : entries) {
            if (generated.size() >= limit) {
                break;
            }
            buildQuestion(entry, entries, mode, seed).ifPresent(generated::add);
        }
        return List.copyOf(generated);
    }

    private Optional<SnapshotQuestion> buildQuestion(
        StudyEntrySource entry,
        List<StudyEntrySource> pool,
        PracticeMode mode,
        long seed
    ) {
        return switch (mode) {
            case CHOOSE_MEANING -> buildChooseMeaning(entry, pool, seed);
            case CHOOSE_WORD -> buildChooseWord(entry, pool, seed);
            case LETTER_SCRAMBLE -> buildLetterScramble(entry, seed);
            case MISSING_LETTERS -> buildMissingLetters(entry, seed);
            case LISTEN_WRITE -> buildListenWrite(entry);
            case CONTEXT_CLOZE -> buildContextCloze(entry);
        };
    }

    private Optional<SnapshotQuestion> buildChooseMeaning(
        StudyEntrySource target,
        List<StudyEntrySource> pool,
        long seed
    ) {
        if (target.term() == null || target.term().isBlank()) {
            return Optional.empty();
        }
        String correctMeaning = primaryMeaning(target);
        if (correctMeaning == null) {
            return Optional.empty();
        }
        Set<String> targetMeanings = allNormalizedMeanings(target);
        Set<String> usedMeanings = new HashSet<>(targetMeanings);

        List<StudyEntrySource> shuffledCandidates = new ArrayList<>(pool);
        Collections.shuffle(shuffledCandidates, new Random(seedFor(seed, target.id())));

        List<String> optionTexts = new ArrayList<>();
        optionTexts.add(correctMeaning);
        for (StudyEntrySource candidate : shuffledCandidates) {
            if (optionTexts.size() >= 4) {
                break;
            }
            if (candidate.id().equals(target.id())) {
                continue;
            }
            String candidateMeaning = primaryMeaning(candidate);
            if (candidateMeaning == null) {
                continue;
            }
            String norm = grader.normalize(candidateMeaning);
            if (norm.isEmpty() || usedMeanings.contains(norm)) {
                continue;
            }
            // Also skip if candidate has any sense overlapping with target's primary meaning
            if (sharesAnyMeaning(candidate, targetMeanings)) {
                continue;
            }
            usedMeanings.add(norm);
            optionTexts.add(candidateMeaning);
        }

        if (optionTexts.size() < 2) {
            return Optional.empty();
        }

        Random optRng = new Random(seedFor(seed ^ 0x9E3779B97F4A7C15L, target.id()));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < optionTexts.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, optRng);

        List<QuestionOption> options = new ArrayList<>();
        String correctKey = null;
        for (int i = 0; i < order.size(); i++) {
            int sourceIndex = order.get(i);
            String key = OPTION_KEYS.get(i);
            options.add(new QuestionOption(key, optionTexts.get(sourceIndex)));
            if (sourceIndex == 0) {
                correctKey = key;
            }
        }

        return Optional.of(new SnapshotQuestion(
            questionId(seed, target.id(), PracticeMode.CHOOSE_MEANING),
            target.id(),
            PracticeMode.CHOOSE_MEANING,
            target.term().strip(),
            secondaryDefinition(target, correctMeaning),
            primaryPos(target),
            target.ipaUk(),
            target.ipaUs(),
            primaryAudio(target),
            target.ukAudioId(),
            target.usAudioId(),
            target.imageId(),
            List.copyOf(options),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            correctKey,
            target.term().strip()
        ));
    }

    private Optional<SnapshotQuestion> buildChooseWord(
        StudyEntrySource target,
        List<StudyEntrySource> pool,
        long seed
    ) {
        if (target.term() == null || target.term().isBlank()) {
            return Optional.empty();
        }
        String correctMeaning = primaryMeaning(target);
        if (correctMeaning == null) {
            return Optional.empty();
        }
        String targetTerm = target.term().strip();
        String targetTermNorm = grader.normalize(targetTerm);
        Set<String> targetMeanings = allNormalizedMeanings(target);

        Set<String> usedTerms = new HashSet<>();
        usedTerms.add(targetTermNorm);

        List<StudyEntrySource> shuffledCandidates = new ArrayList<>(pool);
        Collections.shuffle(shuffledCandidates, new Random(seedFor(seed, target.id())));

        List<String> optionTerms = new ArrayList<>();
        optionTerms.add(targetTerm);
        for (StudyEntrySource candidate : shuffledCandidates) {
            if (optionTerms.size() >= 4) {
                break;
            }
            if (candidate.id().equals(target.id()) || candidate.term() == null || candidate.term().isBlank()) {
                continue;
            }
            String candidateTerm = candidate.term().strip();
            String candidateTermNorm = grader.normalize(candidateTerm);
            if (candidateTermNorm.isEmpty() || usedTerms.contains(candidateTermNorm)) {
                continue;
            }
            if (sharesAnyMeaning(candidate, targetMeanings)) {
                continue;
            }
            usedTerms.add(candidateTermNorm);
            optionTerms.add(candidateTerm);
        }

        if (optionTerms.size() < 2) {
            return Optional.empty();
        }

        Random optRng = new Random(seedFor(seed ^ 0x517CC1B727220A95L, target.id()));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < optionTerms.size(); i++) {
            order.add(i);
        }
        Collections.shuffle(order, optRng);

        List<QuestionOption> options = new ArrayList<>();
        String correctKey = null;
        for (int i = 0; i < order.size(); i++) {
            int sourceIndex = order.get(i);
            String key = OPTION_KEYS.get(i);
            options.add(new QuestionOption(key, optionTerms.get(sourceIndex)));
            if (sourceIndex == 0) {
                correctKey = key;
            }
        }

        return Optional.of(new SnapshotQuestion(
            questionId(seed, target.id(), PracticeMode.CHOOSE_WORD),
            target.id(),
            PracticeMode.CHOOSE_WORD,
            correctMeaning,
            secondaryDefinition(target, correctMeaning),
            primaryPos(target),
            null,
            null,
            null,
            null,
            null,
            target.imageId(),
            List.copyOf(options),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            correctKey,
            targetTerm
        ));
    }

    private Optional<SnapshotQuestion> buildLetterScramble(StudyEntrySource target, long seed) {
        if (target.term() == null || target.term().isBlank()) {
            return Optional.empty();
        }
        String term = target.term().strip().replaceAll("\\s+", " ");
        List<String> letters = new ArrayList<>();
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (!Character.isWhitespace(ch)) {
                letters.add(String.valueOf(Character.toLowerCase(ch)));
            }
        }
        if (letters.size() < 2 || new HashSet<>(letters).size() < 2) {
            return Optional.empty();
        }

        List<Integer> wordLengths = Arrays.stream(term.split(" "))
            .map(String::length)
            .toList();

        List<String> original = List.copyOf(letters);
        List<String> scrambled = new ArrayList<>(letters);
        Random rng = new Random(seedFor(seed, target.id()));
        Collections.shuffle(scrambled, rng);
        if (scrambled.equals(original)) {
            Collections.rotate(scrambled, 1);
            if (scrambled.equals(original)) {
                for (int i = 1; i < scrambled.size(); i++) {
                    if (!scrambled.get(i).equals(scrambled.get(0))) {
                        Collections.swap(scrambled, 0, i);
                        break;
                    }
                }
            }
        }

        String meaning = primaryMeaning(target);
        return Optional.of(new SnapshotQuestion(
            questionId(seed, target.id(), PracticeMode.LETTER_SCRAMBLE),
            target.id(),
            PracticeMode.LETTER_SCRAMBLE,
            meaning != null ? meaning : secondaryDefinition(target, null),
            secondaryDefinition(target, meaning),
            primaryPos(target),
            target.ipaUk(),
            target.ipaUs(),
            primaryAudio(target),
            target.ukAudioId(),
            target.usAudioId(),
            target.imageId(),
            null,
            List.copyOf(scrambled),
            wordLengths,
            null,
            null,
            null,
            null,
            null,
            null,
            term,
            term
        ));
    }

    private Optional<SnapshotQuestion> buildMissingLetters(StudyEntrySource target, long seed) {
        if (target.term() == null || target.term().isBlank()) {
            return Optional.empty();
        }
        String term = target.term().strip().replaceAll("\\s+", " ");
        List<Integer> letterIndices = new ArrayList<>();
        for (int i = 0; i < term.length(); i++) {
            if (Character.isLetterOrDigit(term.charAt(i))) {
                letterIndices.add(i);
            }
        }
        if (letterIndices.size() < 2) {
            return Optional.empty();
        }

        int blankCount = Math.max(1, (int) Math.round(letterIndices.size() * 0.35));
        blankCount = Math.min(blankCount, letterIndices.size() - 1);

        List<Integer> shuffled = new ArrayList<>(letterIndices);
        Collections.shuffle(shuffled, new Random(seedFor(seed, target.id())));
        List<Integer> blankPositions = shuffled.subList(0, blankCount).stream().sorted().toList();

        Set<Integer> blankSet = new HashSet<>(blankPositions);
        StringBuilder masked = new StringBuilder(term.length());
        for (int i = 0; i < term.length(); i++) {
            masked.append(blankSet.contains(i) ? '_' : term.charAt(i));
        }

        String meaning = primaryMeaning(target);
        return Optional.of(new SnapshotQuestion(
            questionId(seed, target.id(), PracticeMode.MISSING_LETTERS),
            target.id(),
            PracticeMode.MISSING_LETTERS,
            meaning != null ? meaning : secondaryDefinition(target, null),
            secondaryDefinition(target, meaning),
            primaryPos(target),
            target.ipaUk(),
            target.ipaUs(),
            primaryAudio(target),
            target.ukAudioId(),
            target.usAudioId(),
            target.imageId(),
            null,
            null,
            null,
            masked.toString(),
            blankPositions,
            null,
            null,
            null,
            null,
            term,
            term
        ));
    }

    private Optional<SnapshotQuestion> buildListenWrite(StudyEntrySource target) {
        if (target.term() == null || target.term().isBlank()) {
            return Optional.empty();
        }
        UUID audioId = primaryAudio(target);
        if (audioId == null) {
            return Optional.empty();
        }
        String term = target.term().strip();
        return Optional.of(new SnapshotQuestion(
            questionId(1L, target.id(), PracticeMode.LISTEN_WRITE),
            target.id(),
            PracticeMode.LISTEN_WRITE,
            "Listen and type the word you hear",
            primaryMeaning(target),
            primaryPos(target),
            null,
            null,
            audioId,
            target.ukAudioId(),
            target.usAudioId(),
            target.imageId(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            term,
            term
        ));
    }

    private Optional<SnapshotQuestion> buildContextCloze(StudyEntrySource target) {
        if (target.term() == null || target.term().isBlank() || target.examples() == null || target.examples().isEmpty()) {
            return Optional.empty();
        }
        String baseTerm = target.term().strip().replaceAll("\\s+", " ");
        Pattern pattern = buildInflectionPattern(baseTerm);
        if (pattern == null) {
            return Optional.empty();
        }

        for (int idx = 0; idx < target.examples().size(); idx++) {
            String sentence = target.examples().get(idx);
            if (sentence == null || sentence.isBlank()) {
                continue;
            }
            Matcher matcher = pattern.matcher(sentence);
            if (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String matched = sentence.substring(start, end);
                String cloze = sentence.substring(0, start) + "_____" + sentence.substring(end);
                String exampleVi = (target.examplesVi() != null && idx < target.examplesVi().size())
                    ? target.examplesVi().get(idx)
                    : null;
                if (exampleVi != null && exampleVi.isBlank()) {
                    exampleVi = null;
                }
                return Optional.of(new SnapshotQuestion(
                    questionId(1L, target.id(), PracticeMode.CONTEXT_CLOZE),
                    target.id(),
                    PracticeMode.CONTEXT_CLOZE,
                    cloze,
                    primaryMeaning(target),
                    primaryPos(target),
                    null,
                    null,
                    null,
                    null,
                    null,
                    target.imageId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    cloze,
                    start,
                    end,
                    exampleVi,
                    matched,
                    baseTerm
                ));
            }
        }
        return Optional.empty();
    }

    private Pattern buildInflectionPattern(String baseTerm) {
        String lower = baseTerm.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(lower);

        String[] parts = lower.split(" ");
        if (parts.length == 1) {
            candidates.addAll(singleWordInflections(parts[0]));
        } else {
            String rest = String.join("\\s+", Arrays.stream(parts, 1, parts.length).map(Pattern::quote).toList());
            List<String> regexCandidates = new ArrayList<>();
            for (String firstInflected : singleWordInflections(parts[0])) {
                regexCandidates.add(Pattern.quote(firstInflected) + "\\s+" + rest);
            }
            regexCandidates.add(Pattern.quote(lower));
            regexCandidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
            return Pattern.compile(
                "(?<!\\p{Alnum})(?:" + String.join("|", regexCandidates) + ")(?!\\p{Alnum})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            );
        }

        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        List<String> quoted = sorted.stream().map(Pattern::quote).toList();
        return Pattern.compile(
            "(?<!\\p{Alnum})(?:" + String.join("|", quoted) + ")(?!\\p{Alnum})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
    }

    private static List<String> singleWordInflections(String word) {
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        forms.add(word);
        if (IRREGULAR_INFLECTIONS.containsKey(word)) {
            forms.addAll(IRREGULAR_INFLECTIONS.get(word));
        }
        if (word.length() < 2) {
            return List.copyOf(forms);
        }
        forms.add(word + "s");
        forms.add(word + "es");
        forms.add(word + "ed");
        forms.add(word + "d");
        forms.add(word + "ing");
        forms.add(word + "er");
        forms.add(word + "est");

        char last = word.charAt(word.length() - 1);
        char prev = word.charAt(word.length() - 2);
        if (last == 'y' && !isVowel(prev)) {
            String stem = word.substring(0, word.length() - 1);
            forms.add(stem + "ies");
            forms.add(stem + "ied");
            forms.add(stem + "ier");
            forms.add(stem + "iest");
        }
        if (last == 'e') {
            String stem = word.substring(0, word.length() - 1);
            forms.add(stem + "ing");
            forms.add(word + "r");
            forms.add(word + "st");
            if (word.endsWith("ie")) {
                forms.add(word.substring(0, word.length() - 2) + "ying");
            }
        }
        if (word.length() >= 3 && !isVowel(last) && last != 'w' && last != 'x' && last != 'y'
            && isVowel(prev) && !isVowel(word.charAt(word.length() - 3))) {
            String doubled = word + last;
            forms.add(doubled + "ed");
            forms.add(doubled + "ing");
            forms.add(doubled + "er");
            forms.add(doubled + "est");
        }
        return List.copyOf(forms);
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private String primaryMeaning(StudyEntrySource entry) {
        if (entry.contextNotes() != null) {
            for (StudyContextNote note : entry.contextNotes()) {
                if (note != null && note.meaningVi() != null && !note.meaningVi().isBlank()) {
                    return note.meaningVi().strip();
                }
            }
        }
        if (entry.senses() != null) {
            for (StudySense sense : entry.senses()) {
                if (sense != null && sense.meaningVi() != null && !sense.meaningVi().isBlank()) {
                    return sense.meaningVi().strip();
                }
            }
            for (StudySense sense : entry.senses()) {
                if (sense != null && sense.definitionEn() != null && !sense.definitionEn().isBlank()) {
                    return sense.definitionEn().strip();
                }
            }
        }
        return null;
    }

    private String secondaryDefinition(StudyEntrySource entry, String primary) {
        if (entry.senses() != null && !entry.senses().isEmpty()) {
            String def = entry.senses().getFirst().definitionEn();
            if (def != null && !def.isBlank() && !grader.normalize(def).equals(grader.normalize(primary))) {
                return def.strip();
            }
        }
        return null;
    }

    private String primaryPos(StudyEntrySource entry) {
        if (entry.contextNotes() != null && !entry.contextNotes().isEmpty()
            && entry.contextNotes().getFirst().pos() != null && !entry.contextNotes().getFirst().pos().isBlank()) {
            return entry.contextNotes().getFirst().pos().strip();
        }
        if (entry.senses() != null && !entry.senses().isEmpty() && entry.senses().getFirst().pos() != null) {
            return entry.senses().getFirst().pos().strip();
        }
        return null;
    }

    private UUID primaryAudio(StudyEntrySource entry) {
        return entry.usAudioId() != null ? entry.usAudioId() : entry.ukAudioId();
    }

    private Set<String> allNormalizedMeanings(StudyEntrySource entry) {
        Set<String> meanings = new HashSet<>();
        if (entry.contextNotes() != null) {
            for (StudyContextNote note : entry.contextNotes()) {
                if (note != null && note.meaningVi() != null && !note.meaningVi().isBlank()) {
                    meanings.add(grader.normalize(note.meaningVi()));
                }
            }
        }
        if (entry.senses() != null) {
            for (StudySense sense : entry.senses()) {
                if (sense == null) {
                    continue;
                }
                if (sense.meaningVi() != null && !sense.meaningVi().isBlank()) {
                    meanings.add(grader.normalize(sense.meaningVi()));
                }
                if (sense.definitionEn() != null && !sense.definitionEn().isBlank()) {
                    meanings.add(grader.normalize(sense.definitionEn()));
                }
            }
        }
        return meanings;
    }

    private boolean sharesAnyMeaning(StudyEntrySource candidate, Set<String> targetMeanings) {
        for (String m : allNormalizedMeanings(candidate)) {
            if (targetMeanings.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static long seedFor(long seed, UUID entryId) {
        return seed ^ entryId.getMostSignificantBits() ^ entryId.getLeastSignificantBits();
    }

    private static UUID questionId(long seed, UUID entryId, PracticeMode mode) {
        String raw = seed + ":" + entryId + ":" + mode.name();
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, List<String>> buildIrregularMap() {
        Map<String, List<String>> m = new HashMap<>();
        m.put("be", List.of("am", "is", "are", "was", "were", "been", "being"));
        m.put("have", List.of("has", "had", "having"));
        m.put("do", List.of("does", "did", "done", "doing"));
        m.put("go", List.of("goes", "went", "gone", "going"));
        m.put("make", List.of("makes", "made", "making"));
        m.put("take", List.of("takes", "took", "taken", "taking"));
        m.put("see", List.of("sees", "saw", "seen", "seeing"));
        m.put("come", List.of("comes", "came", "coming"));
        m.put("run", List.of("runs", "ran", "running"));
        m.put("write", List.of("writes", "wrote", "written", "writing"));
        m.put("speak", List.of("speaks", "spoke", "spoken", "speaking"));
        m.put("find", List.of("finds", "found", "finding"));
        m.put("give", List.of("gives", "gave", "given", "giving"));
        m.put("think", List.of("thinks", "thought", "thinking"));
        m.put("know", List.of("knows", "knew", "known", "knowing"));
        m.put("buy", List.of("buys", "bought", "buying"));
        m.put("bring", List.of("brings", "brought", "bringing"));
        m.put("teach", List.of("teaches", "taught", "teaching"));
        m.put("catch", List.of("catches", "caught", "catching"));
        m.put("feel", List.of("feels", "felt", "feeling"));
        m.put("keep", List.of("keeps", "kept", "keeping"));
        m.put("leave", List.of("leaves", "left", "leaving"));
        m.put("meet", List.of("meets", "met", "meeting"));
        m.put("send", List.of("sends", "sent", "sending"));
        m.put("spend", List.of("spends", "spent", "spending"));
        m.put("build", List.of("builds", "built", "building"));
        m.put("lose", List.of("loses", "lost", "losing"));
        m.put("win", List.of("wins", "won", "winning"));
        m.put("begin", List.of("begins", "began", "begun", "beginning"));
        m.put("break", List.of("breaks", "broke", "broken", "breaking"));
        m.put("choose", List.of("chooses", "chose", "chosen", "choosing"));
        m.put("drive", List.of("drives", "drove", "driven", "driving"));
        m.put("eat", List.of("eats", "ate", "eaten", "eating"));
        m.put("fall", List.of("falls", "fell", "fallen", "falling"));
        m.put("fly", List.of("flies", "flew", "flown", "flying"));
        m.put("forget", List.of("forgets", "forgot", "forgotten", "forgetting"));
        m.put("get", List.of("gets", "got", "gotten", "getting"));
        m.put("grow", List.of("grows", "grew", "grown", "growing"));
        m.put("hear", List.of("hears", "heard", "hearing"));
        m.put("hold", List.of("holds", "held", "holding"));
        m.put("lead", List.of("leads", "led", "leading"));
        m.put("pay", List.of("pays", "paid", "paying"));
        m.put("read", List.of("reads", "reading"));
        m.put("ride", List.of("rides", "rode", "ridden", "riding"));
        m.put("rise", List.of("rises", "rose", "risen", "rising"));
        m.put("say", List.of("says", "said", "saying"));
        m.put("sell", List.of("sells", "sold", "selling"));
        m.put("show", List.of("shows", "showed", "shown", "showing"));
        m.put("sing", List.of("sings", "sang", "sung", "singing"));
        m.put("sit", List.of("sits", "sat", "sitting"));
        m.put("sleep", List.of("sleeps", "slept", "sleeping"));
        m.put("stand", List.of("stands", "stood", "standing"));
        m.put("swim", List.of("swims", "swam", "swum", "swimming"));
        m.put("tell", List.of("tells", "told", "telling"));
        m.put("understand", List.of("understands", "understood", "understanding"));
        m.put("wear", List.of("wears", "wore", "worn", "wearing"));
        m.put("child", List.of("children"));
        m.put("man", List.of("men"));
        m.put("woman", List.of("women"));
        m.put("person", List.of("people", "persons"));
        m.put("foot", List.of("feet"));
        m.put("tooth", List.of("teeth"));
        m.put("mouse", List.of("mice"));
        m.put("good", List.of("better", "best"));
        m.put("bad", List.of("worse", "worst"));
        return Map.copyOf(m);
    }
}
