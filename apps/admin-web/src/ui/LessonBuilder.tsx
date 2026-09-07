import { FormEvent, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';

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
  const { t } = useTranslation('admin');
  const [preset, setPreset] = useState<PresetName>('STANDARD');
  const [activities, setActivities] = useState<string[]>([...presets.STANDARD.activities]);
  const [annotations, setAnnotations] = useState<string[]>([...presets.STANDARD.annotations]);
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

  function toggle(setter: (values: string[]) => void, current: string[], item: string) {
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
      if (!title.trim()) throw new Error(t('lessonBuilder.validation.titleRequired'));
      if (source === 'TEXT' && !text.trim()) {
        throw new Error(t('lessonBuilder.validation.textRequired'));
      }
      if (source !== 'TEXT' && !sourceReference.trim()) {
        throw new Error(t('lessonBuilder.validation.referenceRequired'));
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
      <div className="eyebrow">{t('lessonBuilder.eyebrow')}</div>
      <h1>{t('lessonBuilder.title')}</h1>
      <p className="lead">{t('lessonBuilder.lead')}</p>

      <div className="two">
        <Card className="mt-6">
          <CardContent className="pt-6">
            <form className="form" onSubmit={submit}>
              <label>
                {t('lessonBuilder.fields.title')}
                <Input
                  value={title}
                  onChange={event => setTitle(event.target.value)}
                  placeholder={t('lessonBuilder.placeholders.title')}
                />
              </label>

              <label>
                {t('lessonBuilder.fields.source')}
                <select value={source} onChange={event => setSource(event.target.value as SourceType)}>
                  <option value="TEXT">{t('lessonBuilder.source.TEXT')}</option>
                  <option value="AUDIO">{t('lessonBuilder.source.AUDIO')}</option>
                  <option value="YOUTUBE">YouTube</option>
                </select>
              </label>

              <label>
                {t('lessonBuilder.fields.preset')}
                <select value={preset} onChange={event => applyPreset(event.target.value as PresetName)}>
                  {Object.keys(presets).map(name => <option key={name}>{name}</option>)}
                </select>
              </label>

              {source === 'TEXT' ? (
                <label>
                  {t('lessonBuilder.fields.sourceText')}
                  <Textarea
                    value={text}
                    onChange={event => setText(event.target.value)}
                    placeholder={t('lessonBuilder.placeholders.sourceText')}
                  />
                </label>
              ) : (
                <label>
                  {source === 'YOUTUBE'
                    ? t('lessonBuilder.fields.youtubeUrl')
                    : t('lessonBuilder.fields.audioReference')}
                  <Input
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
                  {t('lessonBuilder.fields.accent')}
                  <select value={accent} onChange={event => setAccent(event.target.value)}>
                    <option value="US">US</option>
                    <option value="UK">UK</option>
                  </select>
                </label>

                <label>
                  {t('lessonBuilder.fields.sentenceIpa')}
                  <select value={pronunciationStrategy} onChange={event => setPronunciationStrategy(event.target.value)}>
                    <option value="DISABLED">{t('lessonBuilder.pronunciation.DISABLED')}</option>
                    <option value="ON_DEMAND">{t('lessonBuilder.pronunciation.ON_DEMAND')}</option>
                    <option value="PREGENERATE">{t('lessonBuilder.pronunciation.PREGENERATE')}</option>
                  </select>
                </label>
              </div>

              <h3>{t('lessonBuilder.studyActivities')}</h3>
              <div className="checks">
                {activityOptions.map(item => (
                  <label key={item}>
                    <input
                      type="checkbox"
                      checked={activities.includes(item)}
                      onChange={() => toggle(setActivities, activities, item)}
                    />
                    {t(`lessonBuilder.activity.${item}`)}
                  </label>
                ))}
              </div>

              <h3>{t('lessonBuilder.annotations')}</h3>
              <div className="checks">
                {annotationOptions.map(item => (
                  <label key={item}>
                    <input
                      type="checkbox"
                      checked={annotations.includes(item)}
                      onChange={() => toggle(setAnnotations, annotations, item)}
                    />
                    {t(`lessonBuilder.annotation.${item}`)}
                  </label>
                ))}
              </div>

              <Button className="mt-[22px]" disabled={submitting} type="submit">
                {submitting ? t('lessonBuilder.queueing') : t('lessonBuilder.queue')}
              </Button>

              {error ? <p className="danger">{error}</p> : null}
              {accepted ? (
                <div className="success">
                  <strong>{t('lessonBuilder.accepted')}</strong>
                  <div>{t('lessonBuilder.lessonId')}: {accepted.lessonId}</div>
                  <div>{t('lessonBuilder.jobId')}: {accepted.jobId}</div>
                  <a href={`/jobs?job=${accepted.jobId}`}>{t('lessonBuilder.inspectJob')} →</a>
                </div>
              ) : null}
            </form>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardHeader>
            <CardTitle>{t('lessonBuilder.preview.title')}</CardTitle>
          </CardHeader>
          <CardContent>
            <pre>{JSON.stringify({
              source,
              activities,
              annotations,
              accent,
              pronunciationStrategy,
              dependencyPreview,
            }, null, 2)}</pre>
            <p className="muted">{t('lessonBuilder.preview.note')}</p>
          </CardContent>
        </Card>
      </div>
    </section>
  );
}
