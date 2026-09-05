import { FormEvent, useMemo, useState } from 'react';
import { api } from '../api';

const activityOptions = [
  'DICTATION',
  'SHADOWING',
  'VOCABULARY_PRACTICE',
  'GRAMMAR_PRACTICE',
] as const;

const annotationOptions = [
  'TRANSLATION',
  'LEXICAL',
  'GRAMMAR',
  'ENTITY_HINTS',
  'DICTATION_HINTS',
  'THOUGHT_GROUPS',
  'SENTENCE_IPA',
  'LEARNING_TIPS',
] as const;

const presets = {
  STANDARD: {
    activities: ['DICTATION', 'SHADOWING'],
    annotations: [
      'TRANSLATION',
      'LEXICAL',
      'GRAMMAR',
      'ENTITY_HINTS',
      'DICTATION_HINTS',
      'THOUGHT_GROUPS',
    ],
  },
  SPEAKING: {
    activities: ['SHADOWING'],
    annotations: ['TRANSLATION', 'LEXICAL', 'THOUGHT_GROUPS', 'SENTENCE_IPA'],
  },
  LISTENING: {
    activities: ['DICTATION'],
    annotations: ['TRANSLATION', 'LEXICAL', 'ENTITY_HINTS', 'DICTATION_HINTS'],
  },
  RICH: {
    activities: [...activityOptions],
    annotations: [...annotationOptions],
  },
} as const;

type SourceType = 'TEXT' | 'AUDIO' | 'YOUTUBE';
type BuildAccepted = { lessonId: string; jobId: string };
type PresetName = keyof typeof presets;

export function LessonBuilder() {
  const [preset, setPreset] = useState<PresetName>('STANDARD');
  const [activities, setActivities] = useState<string[]>([
    ...presets.STANDARD.activities,
  ]);
  const [annotations, setAnnotations] = useState<string[]>([
    ...presets.STANDARD.annotations,
  ]);
  const [source, setSource] = useState<SourceType>('TEXT');
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [sourceReference, setSourceReference] = useState('');
  const [accent, setAccent] = useState('US');
  const [pronunciationStrategy, setPronunciationStrategy] = useState('ON_DEMAND');
  const [submitting, setSubmitting] = useState(false);
  const [accepted, setAccepted] = useState<BuildAccepted | null>(null);
  const [error, setError] = useState('');

  const dependencyPreview = useMemo(() => ({
    needsAudio: activities.some(item => ['DICTATION', 'SHADOWING'].includes(item)),
    needsAlignment: activities.some(item => ['DICTATION', 'SHADOWING'].includes(item)),
    needsNlp: annotations.some(item => [
      'LEXICAL',
      'GRAMMAR',
      'ENTITY_HINTS',
      'DICTATION_HINTS',
      'THOUGHT_GROUPS',
    ].includes(item)),
    needsLlm: annotations.some(item => ['SENTENCE_IPA', 'LEARNING_TIPS'].includes(item)),
  }), [activities, annotations]);

  function applyPreset(nextPreset: PresetName) {
    setPreset(nextPreset);
    setActivities([...presets[nextPreset].activities]);
    setAnnotations([...presets[nextPreset].annotations]);
  }

  function toggle(
    setter: (values: string[]) => void,
    current: string[],
    item: string,
  ) {
    setter(current.includes(item)
      ? current.filter(value => value !== item)
      : [...current, item]);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError('');
    setAccepted(null);

    try {
      if (!title.trim()) throw new Error('Title is required.');
      if (source === 'TEXT' && !text.trim()) {
        throw new Error('Text source cannot be empty.');
      }
      if (source !== 'TEXT' && !sourceReference.trim()) {
        throw new Error('Audio/YouTube source reference is required.');
      }

      const response = await api<BuildAccepted>('/api/v1/admin/lessons/build', {
        method: 'POST',
        body: JSON.stringify({
          title: title.trim(),
          sourceType: source,
          sourceText: source === 'TEXT' ? text.trim() : null,
          sourceReference: source === 'TEXT' ? null : sourceReference.trim(),
          activities,
          annotations,
          accent,
          pronunciationStrategy,
        }),
      });
      setAccepted(response);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section>
      <div className="eyebrow">Lesson / Create</div>
      <h1>Lesson Builder</h1>
      <p className="lead">
        Content, annotations and activities are separate. The exact build selection is snapshotted
        with the job for audit/debug instead of silently following future admin defaults.
      </p>

      <div className="two">
        <form className="card form" onSubmit={submit}>
          <label>
            Title
            <input
              value={title}
              onChange={event => setTitle(event.target.value)}
              placeholder="Business small talk"
            />
          </label>

          <label>
            Source
            <select
              value={source}
              onChange={event => setSource(event.target.value as SourceType)}
            >
              <option value="TEXT">Text</option>
              <option value="AUDIO">Audio</option>
              <option value="YOUTUBE">YouTube</option>
            </select>
          </label>

          <label>
            Preset
            <select
              value={preset}
              onChange={event => applyPreset(event.target.value as PresetName)}
            >
              {Object.keys(presets).map(name => <option key={name}>{name}</option>)}
            </select>
          </label>

          {source === 'TEXT' ? (
            <label>
              Source text
              <textarea
                value={text}
                onChange={event => setText(event.target.value)}
                placeholder="Paste curated English text here…"
              />
            </label>
          ) : (
            <label>
              {source === 'YOUTUBE' ? 'YouTube URL' : 'Audio object key / URL'}
              <input
                value={sourceReference}
                onChange={event => setSourceReference(event.target.value)}
                placeholder={source === 'YOUTUBE'
                  ? 'https://youtube.com/watch?v=…'
                  : 'uploads/admin/example.m4a'}
              />
            </label>
          )}

          <div className="row">
            <label>
              Accent
              <select value={accent} onChange={event => setAccent(event.target.value)}>
                <option value="US">US</option>
                <option value="UK">UK</option>
              </select>
            </label>

            <label>
              Sentence IPA
              <select
                value={pronunciationStrategy}
                onChange={event => setPronunciationStrategy(event.target.value)}
              >
                <option value="DISABLED">Disabled</option>
                <option value="ON_DEMAND">On demand</option>
                <option value="PREGENERATE">Pre-generate</option>
              </select>
            </label>
          </div>

          <h3>Study activities</h3>
          <div className="checks">
            {activityOptions.map(item => (
              <label key={item}>
                <input
                  type="checkbox"
                  checked={activities.includes(item)}
                  onChange={() => toggle(setActivities, activities, item)}
                />
                {item.replaceAll('_', ' ')}
              </label>
            ))}
          </div>

          <h3>Annotations / enrichment</h3>
          <div className="checks">
            {annotationOptions.map(item => (
              <label key={item}>
                <input
                  type="checkbox"
                  checked={annotations.includes(item)}
                  onChange={() => toggle(setAnnotations, annotations, item)}
                />
                {item.replaceAll('_', ' ')}
              </label>
            ))}
          </div>

          <button disabled={submitting}>
            {submitting ? 'Queuing build…' : 'Create draft & queue build'}
          </button>

          {error ? <p className="danger">{error}</p> : null}
          {accepted ? (
            <div className="success">
              <strong>Accepted</strong>
              <div>Lesson: {accepted.lessonId}</div>
              <div>Job: {accepted.jobId}</div>
              <a href={`/jobs?job=${accepted.jobId}`}>Inspect job →</a>
            </div>
          ) : null}
        </form>

        <div className="card">
          <h2>Build plan preview</h2>
          <pre>{JSON.stringify({
            source,
            activities,
            annotations,
            accent,
            pronunciationStrategy,
            dependencyPreview,
          }, null, 2)}</pre>
          <p className="muted">
            The authoritative Java planner runs only the expensive steps implied by the final
            selection and current admin policy. This browser preview is explanatory only.
          </p>
        </div>
      </div>
    </section>
  );
}
