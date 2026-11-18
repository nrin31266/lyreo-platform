import { expect, it } from 'vitest';
import { lexiconPageParams } from './lexicon-pagination';
it('jumps to the exact last partial page and its neighbours without a deep offset', () => {
  const total=1390449, last=Math.ceil(total/30)-1;
  expect(lexiconPageParams(last,0,total)).toMatchObject({tail:'true',skip:'0',take:'9'});
  expect(lexiconPageParams(last-1,last,total)).toMatchObject({tail:'true',skip:'9',take:'30'});
  expect(lexiconPageParams(last-2,last,total)).toMatchObject({tail:'true',skip:'39',take:'30'});
});
it('uses ranked cursor directions in the middle and clears old anchors', () => {
  expect(lexiconPageParams(501,500,1390449,'first','last')).toMatchObject({after:'last',before:null,tail:null,skip:null});
  expect(lexiconPageParams(499,500,1390449,'first','last')).toMatchObject({before:'first',after:null,tail:null,skip:null});
  expect(lexiconPageParams(0,500,1390449)).toMatchObject({after:null,before:null,tail:null,skip:null,page:null});
});
it('can open the first three pages while totals are loading and rejects unresolvable jumps', () => {
  expect(lexiconPageParams(2,0,undefined)).toMatchObject({skip:'60'});
  expect(()=>lexiconPageParams(400,0,undefined)).toThrow('cursor');
});
