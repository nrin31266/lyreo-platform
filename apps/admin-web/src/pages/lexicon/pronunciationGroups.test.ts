import {expect,it} from "vitest";
import {pronunciationGroups, pronunciationRegions} from "./pronunciationGroups";
import type {Sound} from "../library/library.types";
const sound=(id:string,patch:Partial<Sound>={}):Sound=>({id,itemId:'noun',accent:'US',ipa:null,audioUrl:null,assetId:null,tags:[],...patch});
it('keeps separate IPA and audio records apart even when their accents match',()=>{
 expect(pronunciationGroups('read',[sound('ipa',{ipa:'/riːd/'}),sound('audio',{audioUrl:'https://example.test/read.ogg'})])).toHaveLength(2);
});
it('distinguishes read and lead variants and deduplicates only proven recordings',()=>{
 const context={scope_status:'UNSPECIFIED',notation_system:'IPA',regions:['US'],source_record_key:'same-record'};
 const records=[sound('a',{ipa:'/riːd/',pronunciationContext:context}),sound('b',{ipa:'/riːd/',itemId:'verb',pronunciationContext:context}),sound('c',{ipa:'/rɛd/',pronunciationContext:{...context,source_record_key:'past'}})];
 expect(pronunciationGroups('read',records).map(group=>group.length)).toEqual([1,1]);
 expect(pronunciationGroups('lead',[sound('metal',{ipa:'/lɛd/'}),sound('guide',{ipa:'/liːd/'})])).toHaveLength(2);
});
it('keeps distinct audio URLs from the same source record',()=>{
 const context={scope_status:'EXPLICIT',notation_system:'IPA',source_record_key:'one-record'};
 const records=[sound('a',{ipa:'/riːd/',audioUrl:'https://example.test/a.ogg',pronunciationContext:context}),sound('b',{ipa:'/riːd/',audioUrl:'https://example.test/b.ogg',pronunciationContext:context})];
 expect(pronunciationGroups('read',records)[0]).toHaveLength(2);
});

it('collapses the same notation across accents and keeps a labelled source recording separate', () => {
 const parts = [{ id: 'adj', partOfSpeech: 'adj', posTitle: null, etymologyText: null }];
 const rows = [
   sound('uk', { ipa: '/kəmˈpækt/', accent: 'UK' }),
   sound('us', { ipa: '/kəmˈpækt/' }),
   sound('wav', { itemId: 'adj', audioUrl: 'https://example.test/adjective.ogg',
     audioFile: 'LL-Q1860 (eng)-compact (adjective).wav', pronunciationContext: { source: 'enwiktionary', labels: ['Southern-England'] } }),
 ];
 expect(pronunciationGroups('compact', rows, parts).map(group => group.map(row => row.id))).toEqual([['uk', 'us'], ['wav']]);
});

it('recovers normalized regions from unchanged source labels without guessing unknown tags', () => {
  const context = { labels: ['General-American', 'Received_Pronunciation', 'miền Nam nước Anh', 'Úc', 'unusual'] };
  expect(pronunciationRegions(sound('old', { accent: 'unspecified', pronunciationContext: context })))
    .toEqual(['US', 'UK', 'Southern-England', 'AU']);
});
