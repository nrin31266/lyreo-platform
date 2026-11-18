import {expect,it} from "vitest";
import {pronunciationGroups} from "./pronunciationGroups";
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
