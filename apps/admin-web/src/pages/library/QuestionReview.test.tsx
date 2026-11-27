import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { QuestionContext, QuestionReview } from './QuestionEditor';
import type { Question } from './library.types';

vi.mock('./ContentMedia',()=>({MediaPreview:()=>null,MediaEditor:()=>null}));
beforeEach(async()=>{await i18n.changeLanguage('en');});
afterEach(cleanup);
it('shows the complete admin review without a learner simulator or answer exercise', () => {
  const question:Question={title:'',kind:'MCQ_SINGLE',options:[{key:'A',text:'Spoken first option'},{key:'B',text:'Spoken second option'}],correctOption:'B',difficultyLevel:1,explanationVi:'Reviewed explanation',transcriptEn:'Private listening transcript',combinedTranslationVi:'Bản dịch nguyên khối từ nguồn',translationVi:null,answerTranslationVi:null,vocabularyNote:null,media:[]};
  render(<I18nextProvider i18n={i18n}><QuestionReview value={question} /></I18nextProvider>);
  expect(screen.getByRole('region',{name:'Admin review'})).toBeVisible();
  expect(screen.getByText('Spoken first option')).toBeVisible();
  expect(screen.getByText('Private listening transcript')).toBeVisible();
  expect(screen.getByText('Reviewed explanation')).toBeVisible();
  expect(screen.getByText('Bản dịch nguyên khối từ nguồn')).toBeVisible();
  expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  expect(screen.queryByRole('tab')).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'Check answer'})).not.toBeInTheDocument();
});

it('shows the shared passage and links to the exact question for a TOEIC cloze item', () => {
  const question: Question = {
    title: '', kind: 'CLOZE', options: [{ key: 'A', text: 'One' }], correctOption: 'A',
    difficultyLevel: 5, explanationVi: null, transcriptEn: null, translationVi: null,
    answerTranslationVi: null, vocabularyNote: null, media: [],
  };
  render(<I18nextProvider i18n={i18n}><MemoryRouter><QuestionContext value={question} source={{
    test_id: 'mock-1', test_title: 'Test One', part: 6, question_number: 131, gap_number: 131,
    passage_html: '<p>Read gap (131).</p>', transcript_en: null, media: [],
  }} /></MemoryRouter></I18nextProvider>);
  expect(screen.getByText(/Part 6.*131/)).toBeVisible();
  expect(screen.getByTitle('Passage preview')).toHaveAttribute('srcdoc', expect.stringContaining('Read gap (131).'));
  expect(screen.getByRole('link', { name: 'Open in Test One' }))
    .toHaveAttribute('href', '/toeic/mock-1?tab=content&part=6&question=131');
});
