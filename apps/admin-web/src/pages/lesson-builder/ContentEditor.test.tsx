import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { adminI18nResources } from '@lyreo/i18n';
import { AnnotatedTranscript, ClozePreview, contentSpan, defaultContentOptions, LessonNotes, SentenceContentEditor } from './ContentEditor';
import type { AuthoringSentence } from './lesson-builder.types';
afterEach(cleanup);
const sentence: AuthoringSentence = { id:'s1',text:'We don’t give up; don’t give up.',usage:'LEARN',words:[],issues:[],vocabulary:[],grammar:[],hints:[],notes:[],thoughtGroups:[],dictationEligible:true,shadowingEligible:true,dictationSelected:false,shadowingSelected:false,translation:'Đừng bỏ cuộc.',ipa:'reference',ipaNeedsReview:true,cloze:{easy:[2],medium:[2,5],version:'cloze-v1',text:'We don’t give up; don’t give up.'} };
async function provider(child: React.ReactNode) {const i18n=createInstance();await i18n.use(initReactI18next).init({resources:adminI18nResources,lng:'en',ns:['admin']});return render(<I18nextProvider i18n={i18n}>{child}</I18nextProvider>);}
it('maps Unicode and repeated occurrences without guessing incomplete or nonadjacent words',()=>{
 expect(contentSpan(sentence.text,"don't give up",2)).toEqual({tokenStart:4,tokenEnd:7});
 expect(contentSpan(sentence.text,'on’t',1)).toBeNull();expect(contentSpan(sentence.text,'We give up',1)).toBeNull();
 const span=contentSpan(sentence.text,'don’t give up',2)!;
 const {container}=render(<AnnotatedTranscript sentence={{...sentence,vocabulary:[{...span,surface:'don’t give up',explanation:'Keep trying',label:'LEXICAL',importance:'MEDIUM'}]}}/>);
 expect(container.querySelectorAll('mark')).toHaveLength(1);expect(container).toHaveTextContent(sentence.text);
});
it('shows translation and activities directly, emits edits without saving or review checkboxes',async()=>{
 const change=vi.fn();await provider(<SentenceContentEditor sentence={sentence} options={defaultContentOptions} disabled={false} showIpa onChange={change}/>);
 expect(screen.queryByText('Pronunciation checked')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:/save/i})).not.toBeInTheDocument();
 fireEvent.change(screen.getByRole('textbox',{name:'Translation'}),{target:{value:'Bản sửa'}});expect(change).toHaveBeenCalledWith({translation:'Bản sửa'});
 fireEvent.click(screen.getByRole('checkbox',{name:'Shadowing'}));expect(change).toHaveBeenCalledWith({shadowingSelected:true});
});
it('shows the exact IPA issue and lets the editor dismiss it after reviewing',async()=>{
 const change=vi.fn();
 await provider(<SentenceContentEditor sentence={{...sentence,ipaRaw:'original IPA',ipaAccent:'UK',
   ipaReviewIssues:[{start:3,end:8,surface:'don’t',code:'MULTIPLE_READINGS'}]}}
   options={defaultContentOptions} disabled={false} showIpa onChange={change}/>);
 expect(screen.getByText('Reference IPA · UK')).toBeVisible();
 expect(screen.getByText(/don’t ·/)).toBeVisible();
 fireEvent.click(screen.getByText('Raw eSpeak output'));
 expect(screen.getByText('original IPA')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'Mark IPA as reviewed'}));
 expect(change).toHaveBeenCalledWith({ipaNeedsReview:false});
});
it('counts saved AI and manual notes separately and opens the original sentence',async()=>{
 const jump=vi.fn();
 const note={surface:'give up',explanation:'Stop trying',label:'LEXICAL',importance:'MEDIUM' as const,tokenStart:2,tokenEnd:4};
 const draft={status:'CONTENT_REVIEW' as const,vocabularyBudget:9,grammarBudget:4,sentences:[
   {...sentence,vocabulary:[note]}, {...sentence,id:'s2',manualFields:['LEXICAL'],vocabulary:[note]},
 ]};
 await provider(<LessonNotes draft={draft} onJump={jump}/>);
 fireEvent.click(screen.getByRole('button',{name:'Lesson notes 2'}));
 expect(screen.getByText('AI: 1 of 9 · Edited manually: 1')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'Open sentence 2'}));
 await waitFor(()=>expect(jump).toHaveBeenCalledWith('s2'));
 cleanup();
 await provider(<LessonNotes draft={{...draft,status:'PUBLISHED'}} onJump={jump}/>);
 fireEvent.click(screen.getByRole('button',{name:'Lesson notes 2'}));
 expect(screen.getByText('AI: 1 · Edited manually: 1')).toBeVisible();
 expect(screen.queryByText('AI: 1 of 9 · Edited manually: 1')).not.toBeInTheDocument();
});
it('manual cloze additions keep the easy level within medium',async()=>{
 const change=vi.fn();await provider(<ClozePreview sentence={sentence} level="easy" editable onChange={change}/>);
 fireEvent.click(screen.getByRole('button',{name:'We'}));const value=change.mock.calls[0][0];expect(value.clozeManual).toBe(true);expect(value.cloze.easy).toContain(0);expect(value.cloze.medium).toEqual(expect.arrayContaining(value.cloze.easy));
});
it('lets the admin target a repeated phrase without moving it when editing its explanation',async()=>{
 const change=vi.fn(),note={surface:'don’t give up',explanation:'Keep trying',label:'LEXICAL',importance:'MEDIUM' as const,tokenStart:4,tokenEnd:7};
 await provider(<SentenceContentEditor sentence={{...sentence,vocabulary:[note]}} options={defaultContentOptions} disabled={false} showIpa={false} onChange={change}/>);
 expect(screen.getByRole('combobox',{name:'Occurrence'})).toHaveValue('2');
 fireEvent.change(screen.getByRole('textbox',{name:'Brief contextual explanation'}),{target:{value:'Continue trying'}});
 expect(change.mock.calls[0][0].vocabulary[0]).toMatchObject({tokenStart:4,tokenEnd:7});
 fireEvent.change(screen.getByRole('combobox',{name:'Occurrence'}),{target:{value:'1'}});
 expect(change.mock.calls[1][0].vocabulary[0]).toMatchObject({tokenStart:1,tokenEnd:4});
});
