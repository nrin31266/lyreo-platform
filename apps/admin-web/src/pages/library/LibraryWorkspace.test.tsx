import { act, cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { I18nextProvider } from "react-i18next";
import { i18n } from "@/i18n";
import { api, ApiError } from "@/lib/api";
import { DraftWorkspace, TextField } from "./LibraryWorkspace";
import { PassageEditor } from "./PassageEditor";

vi.mock("@/lib/api",async original=>({...await original<typeof import("@/lib/api")>(),api:vi.fn()}));
let cache: QueryClient;
beforeEach(async()=>{vi.clearAllMocks();await i18n.changeLanguage("en");cache=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});});
afterEach(()=>{cleanup();cache.clear();});
const document={id:"test",revision:3,hasUnpublishedChanges:false,status:"PUBLISHED",publicationId:"old",draft:{title:"Original"},updatedAt:null,updatedBy:null};
function show(current=document) {render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={current} endpoint="/api/library" route="/library">{(value,change)=><TextField label="Title" value={value.title} onChange={title=>change({title})} />}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);}
it("offers applying only real changes and publishes without a reason dialog",async()=>{
  show();
  expect(within(screen.getByRole('contentinfo',{name:'Content actions'})).getByRole('button',{name:'Save draft'})).toBeDisabled();
  expect(screen.queryByRole('button',{name:'Archive'})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Publish"})).not.toBeInTheDocument();
  expect(screen.getByRole("button",{name:"Save draft"})).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Title"),{target:{value:"Updated"}});
  fireEvent.change(screen.getByLabelText("Title"),{target:{value:"Original"}});
  expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Title"),{target:{value:"Updated"}});
  const saved={...document,revision:4,hasUnpublishedChanges:true,draft:{title:"Updated"}};
  vi.mocked(api).mockResolvedValueOnce(saved).mockResolvedValueOnce({...saved,revision:5,publicationId:"new",hasUnpublishedChanges:false});
  await userEvent.click(screen.getByRole("button",{name:"Apply changes"}));
  await waitFor(()=>expect(api).toHaveBeenCalledTimes(2));
  expect(JSON.parse(vi.mocked(api).mock.calls[1][1]!.body as string)).toEqual({revision:4,status:"publish"});
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  await waitFor(()=>expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument());
});
it("remembers saved changes after reload and keeps visibility separate",()=>{
  show({...document,status:"HIDDEN",hasUnpublishedChanges:true});
  expect(screen.getByRole("button",{name:"Apply changes"})).toBeEnabled();
  expect(screen.getByRole("button",{name:"Show published content"})).toBeEnabled();
  expect(screen.getByText(/Changes not applied/)).toBeVisible();
});
it("keeps local edits after a conflicting save",async()=>{
  vi.mocked(api).mockRejectedValue(new ApiError(409,"Stale","CONFLICT","New revision exists"));show();
  fireEvent.change(screen.getByLabelText("Title"),{target:{value:"My edit"}});
  await userEvent.click(screen.getByRole("button",{name:"Save draft"}));
  await screen.findByText("New revision exists");
  expect(screen.getByLabelText("Title")).toHaveValue("My edit");
  expect(screen.getByText(/Your edits are still here/)).toBeInTheDocument();
});
it("keeps the saved revision when publication fails so a retry does not save against a stale revision",async()=>{
  const saved={...document,revision:4,hasUnpublishedChanges:true,draft:{title:"Edited"}};
  vi.mocked(api).mockResolvedValueOnce(saved).mockRejectedValueOnce(new ApiError(409,"Blocked","CONFLICT","Resolve blockers"));
  show();fireEvent.change(screen.getByLabelText("Title"),{target:{value:"Edited"}});
  await userEvent.click(screen.getByRole("button",{name:"Apply changes"}));
  await screen.findByText("Resolve blockers");
  expect(vi.mocked(api).mock.calls[1][1]?.body).toContain('"revision":4');
  vi.mocked(api).mockResolvedValueOnce({...saved,revision:5,publicationId:"new",hasUnpublishedChanges:false});
  await userEvent.click(screen.getByRole("button",{name:"Apply changes"}));
  await waitFor(()=>expect(api).toHaveBeenCalledTimes(3));
  expect(vi.mocked(api).mock.calls[2][0]).toBe("/api/library/test/publish");
});
it("does not mark an untouched new form dirty when its caller regenerates initial IDs",()=>{
  const initial={...document,id:"new",revision:0,draft:{title:"",fieldId:"one"}};
  const view=(fieldId:string)=><QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={{...initial,draft:{title:"",fieldId}}} endpoint="/api/library" route="/library">{(value,change)=><TextField label="Title" value={value.title} onChange={title=>change({...value,title})} />}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>;
  const screenView=render(view("one"));screenView.rerender(view("two"));
  const clean=new Event("beforeunload",{cancelable:true});window.dispatchEvent(clean);
  expect(clean.defaultPrevented).toBe(false);
  fireEvent.change(screen.getByLabelText("Title"),{target:{value:"Actual edit"}});
  const dirty=new Event("beforeunload",{cancelable:true});window.dispatchEvent(dirty);
  expect(dirty.defaultPrevented).toBe(true);
});
it('discards without reload and rejects an assistant response from the discarded editor',async()=>{
  let late!: (result:typeof document)=>void;
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={document} endpoint="/api/library" route="/library" assistant={(_document,_dirty,adopt)=><button onClick={()=>{late=adopt;}}>Start assistant</button>}>{(value,change)=><TextField label="Title" value={value.title} onChange={title=>change({title})} />}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByRole('button',{name:'Start assistant'}));
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Unsaved'}});
  await userEvent.click(screen.getByRole('button',{name:'Discard changes'}));
  expect(screen.getByLabelText('Title')).toHaveValue('Original');
  late({...document,revision:4,draft:{title:'Late response'}});
  expect(screen.getByLabelText('Title')).toHaveValue('Original');expect(api).not.toHaveBeenCalled();
});
it('blocks publishing an older test while a nested question has unsaved changes',()=>{
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={{...document,hasUnpublishedChanges:true}} endpoint="/api/library" route="/library" pendingEditorChanges>{(value,change)=><TextField label="Title" value={value.title} onChange={title=>change({title})} />}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  expect(screen.getByRole('button',{name:'Apply changes'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Save draft'})).toBeDisabled();
  expect(screen.getByRole('button',{name:'Discard changes'})).toBeEnabled();
  const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);
  expect(event.defaultPrevented).toBe(true);
});
it('resets Tiptap content and its undo history when changes are discarded',async()=>{
  const saved={...document,draft:{title:'Passage',html:'<p>Saved passage</p>'}};
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={saved} endpoint="/api/library" route="/library">{(value,change)=><PassageEditor value={value.html} onChange={html=>change({...value,html})} />}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(await screen.findByRole('button',{name:'Table'}));
  expect(screen.getByRole('textbox',{name:'Passage editor'}).querySelector('table')).not.toBeNull();
  expect(screen.getByRole('button',{name:'Undo edit'})).toBeEnabled();
  await userEvent.click(screen.getByRole('button',{name:'Discard changes'}));
  expect(screen.getByRole('textbox',{name:'Passage editor'})).toHaveTextContent('Saved passage');
  expect(screen.getByRole('textbox',{name:'Passage editor'}).querySelector('table')).toBeNull();
  expect(screen.getByRole('button',{name:'Undo edit'})).toBeDisabled();
  expect(screen.queryByRole('button',{name:'Apply changes'})).not.toBeInTheDocument();
});
it('ignores a child update callback retained before discarding its editor',async()=>{
  let late!: (value:{title:string})=>void;
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={document} endpoint="/api/library" route="/library">{(value,change)=><><TextField label="Title" value={value.title} onChange={title=>change({title})} /><button onClick={()=>{late=change;}}>Start selection</button></>}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByRole('button',{name:'Start selection'}));
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Unsaved'}});
  await userEvent.click(screen.getByRole('button',{name:'Discard changes'}));
  late({title:'Selection from discarded editor'});
  await waitFor(()=>expect(screen.getByLabelText('Title')).toHaveValue('Original'));
  expect(screen.queryByRole('button',{name:'Apply changes'})).not.toBeInTheDocument();
});
it('rejects callbacks from the editor that existed before saving a new snapshot',async()=>{
  let lateChange!: (value:{title:string})=>void;
  let lateAdopt!: (result:typeof document)=>void;
  const saved={...document,revision:4,hasUnpublishedChanges:true,draft:{title:'Saved edit'}};
  vi.mocked(api).mockResolvedValue(saved);
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={document} endpoint="/api/library" route="/library">{(value,change,_dirty,_revision,_document,adopt)=><><TextField label="Title" value={value.title} onChange={title=>change({title})} /><button onClick={()=>{lateChange=change;lateAdopt=adopt;}}>Start request</button></>}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByRole('button',{name:'Start request'}));
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Saved edit'}});
  await userEvent.click(screen.getByRole('button',{name:'Save draft'}));
  await waitFor(()=>expect(screen.getByRole('button',{name:'Save draft'})).toBeDisabled());
  act(()=>{lateAdopt({...saved,draft:{title:'Old AI response'}});lateChange({title:'Old upload response'});});
  expect(screen.getByLabelText('Title')).toHaveValue('Saved edit');
});
it('keeps assistant and editor context after adopting a snapshot while rejecting old callbacks',async()=>{
  let lateChange!: (value:{title:string})=>void;
  let lateAdopt!: (result:typeof document)=>void;
  function Assistant({onAccept}:{onAccept:()=>void}) {
    const [open,setOpen]=useState(false),[context,setContext]=useState('');
    return <><button onClick={()=>setOpen(!open)}>Toggle assistant</button>{open && <><input aria-label="AI context" value={context} onChange={event=>setContext(event.target.value)} /><button onClick={onAccept}>Accept assistant</button></>}</>;
  }
  function EditorContext() {const [context,setContext]=useState('');return <input aria-label="Editor context" value={context} onChange={event=>setContext(event.target.value)} />;}
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><DraftWorkspace document={document} endpoint="/api/library" route="/library" assistant={(current,_dirty,adopt)=><Assistant onAccept={()=>adopt({...document,revision:current.revision+1,hasUnpublishedChanges:true,draft:{title:`Accepted revision ${current.revision+1}`}})} />}>{(value,change,_dirty,_revision,_document,adopt)=><><EditorContext /><TextField label="Title" value={value.title} onChange={title=>change({title})} /><button onClick={()=>{lateChange=change;lateAdopt=adopt;}}>Start request</button></>}</DraftWorkspace></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByRole('button',{name:'Toggle assistant'}));
  await userEvent.type(screen.getByLabelText('AI context'),'Selected meaning');
  await userEvent.type(screen.getByLabelText('Editor context'),'Second meaning');
  await userEvent.click(screen.getByRole('button',{name:'Start request'}));
  await userEvent.click(screen.getByRole('button',{name:'Accept assistant'}));
  expect(screen.getByLabelText('Title')).toHaveValue('Accepted revision 4');
  expect(screen.getByLabelText('AI context')).toHaveValue('Selected meaning');
  expect(screen.getByLabelText('Editor context')).toHaveValue('Second meaning');
  act(()=>{lateChange({title:'Old upload response'});lateAdopt({...document,revision:4,draft:{title:'Old AI response'}});});
  expect(screen.getByLabelText('Title')).toHaveValue('Accepted revision 4');
  await userEvent.click(screen.getByRole('button',{name:'Accept assistant'}));
  expect(screen.getByLabelText('Title')).toHaveValue('Accepted revision 5');
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Manual edit after AI'}});
  expect(screen.getByLabelText('Title')).toHaveValue('Manual edit after AI');
});
it('preserves unsaved edits when viewing older publications',async()=>{
  vi.mocked(api).mockImplementation(async url=>url.endsWith('/history')?[{id:'previous',revision:1,publishedAt:'2026-11-01T00:00:00Z',publishedBy:'Admin'}]:[]);
  show();
  fireEvent.change(screen.getByLabelText('Title'),{target:{value:'Unsaved edit'}});
  await userEvent.click(screen.getByRole('button',{name:'Update history'}));
  expect(await screen.findByRole('button',{name:'Restore as new publication'})).toBeDisabled();
  expect(screen.getByLabelText('Title')).toHaveValue('Unsaved edit');
});
