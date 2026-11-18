import { useEditor, useEditorState, EditorContent, Node, Mark, Extension, mergeAttributes } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import { TableKit } from "@tiptap/extension-table";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { useEffect, useRef, useState } from "react";
import { RiArrowGoBackLine, RiArrowGoForwardLine, RiListOrdered2, RiBold, RiItalic, RiHeading, RiListUnordered, RiTableLine, RiInsertRowBottom, RiInsertColumnRight, RiDeleteBinLine } from "@remixicon/react";

const SourceBlock = Node.create({ name: 'sourceBlock', group: 'block', content: 'block+',
  parseHTML: () => [{tag:'div'}, {tag:'section'}, {tag:'article'}], renderHTML: ({HTMLAttributes}) => ['div', HTMLAttributes, 0] });
const SourcePre = Node.create({ name: 'sourcePre', group: 'block', content: 'inline*', whitespace: 'pre',
  parseHTML: () => [{tag:'pre', preserveWhitespace:'full'}], renderHTML: ({HTMLAttributes}) => ['pre', mergeAttributes(HTMLAttributes, {style:'white-space:pre-wrap'}), 0] });
const Alignment = Extension.create({name:'sourceAlignment', addGlobalAttributes() {return [{types:['paragraph','heading','tableCell','tableHeader','sourceBlock'],attributes:{textAlign:{default:null,
  parseHTML: element => ['left','right','center','justify'].includes(element.style.textAlign) ? element.style.textAlign : null,
  renderHTML: attributes => attributes.textAlign ? {style:`text-align:${attributes.textAlign}`} : {}}}}];}});
const SourceInline = Mark.create({name:'sourceInline', addAttributes:()=>({tag:{default:'span',parseHTML:element=>element.tagName.toLowerCase()}}),parseHTML:()=>[{tag:'sup'},{tag:'sub'},{tag:'span'}],renderHTML:({HTMLAttributes})=>{const {tag,...attributes}=HTMLAttributes;return [tag,attributes,0];}});
function contentSignature(html: string) {
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  body.querySelectorAll('script,style').forEach(node=>node.remove());
  body.querySelectorAll('br,p,div,section,article,li,tr,td,th,h1,h2,h3,h4,pre').forEach(node=>node.append(' '));
  return {text:(body.textContent ?? '').replace(/\s+/g,' ').trim(), cells:[...body.querySelectorAll('td,th')].map(node=>`${node.tagName}:${node.getAttribute('colspan') ?? '1'}:${node.getAttribute('rowspan') ?? '1'}`).join('|')};
}
export function PassageEditor({ value, onChange }: { value: string; onChange: (html: string) => void }) {
  const { t } = useTranslation('admin');
  const editor = useEditor({extensions:[StarterKit.configure({link:false,code:false,codeBlock:false,strike:false,heading:{levels:[1,2,3,4]}}),TableKit,SourceBlock,SourcePre,SourceInline,Alignment],content:value,
    editorProps:{attributes:{class:'min-h-40 p-3 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring [&_p]:my-2 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:list-decimal [&_ol]:pl-6 [&_table]:w-full [&_td]:border [&_td]:p-2 [&_th]:border [&_th]:p-2 [&_pre]:whitespace-pre-wrap',role:'textbox','aria-multiline':'true','aria-label':t('library.passage')}},
    onUpdate:({editor:current})=>onChange(current.getHTML())});
  const active = useEditorState({editor,selector:({editor:current})=>({table:current?.isActive('table'),undo:current?.can().undo(),redo:current?.can().redo(),bold:current?.isActive('bold'),italic:current?.isActive('italic')})});
  useEffect(()=>{if(editor && editor.getHTML()!==value) editor.commands.setContent(value,{emitUpdate:false});},[editor,value]);
  const before=contentSignature(value), after=contentSignature(editor?.getHTML() ?? value);
  const loss=before.text!==after.text || before.cells!==after.cells;
  useEffect(()=>{editor?.setEditable(!loss,false);},[editor,loss]);
  const tools = [
    {label:'bold',Icon:RiBold,run:()=>editor?.chain().focus().toggleBold().run(),pressed:active?.bold},
    {label:'italic',Icon:RiItalic,run:()=>editor?.chain().focus().toggleItalic().run(),pressed:active?.italic},
    {label:'heading',Icon:RiHeading,run:()=>editor?.chain().focus().toggleHeading({level:2}).run()},
    {label:'list',Icon:RiListUnordered,run:()=>editor?.chain().focus().toggleBulletList().run()},
    {label:'orderedList',Icon:RiListOrdered2,run:()=>editor?.chain().focus().toggleOrderedList().run()},
    {label:'table',Icon:RiTableLine,run:()=>editor?.chain().focus().insertTable({rows:3,cols:2,withHeaderRow:true}).run()},
    ...(active?.table ? [
      {label:'addRow',Icon:RiInsertRowBottom,run:()=>editor?.chain().focus().addRowAfter().run()},
      {label:'addColumn',Icon:RiInsertColumnRight,run:()=>editor?.chain().focus().addColumnAfter().run()},
      {label:'removeTable',Icon:RiDeleteBinLine,run:()=>editor?.chain().focus().deleteTable().run()}] : []),
    {label:'undo',Icon:RiArrowGoBackLine,run:()=>editor?.chain().focus().undo().run(),disabled:!active?.undo},
    {label:'redo',Icon:RiArrowGoForwardLine,run:()=>editor?.chain().focus().redo().run(),disabled:!active?.redo}];
  return <div className="overflow-hidden rounded-lg border">{loss && <div role="alert" className="border-b p-3 text-sm text-destructive">{t('library.passageContentLoss')}<PassagePreview html={value} /></div>}
    <div role="toolbar" aria-label={t('library.passage')} className="flex flex-wrap gap-1 border-b bg-muted/30 p-1.5">{tools.map(({label,Icon,run,pressed,disabled})=><Button key={label} type="button" size="icon-sm" variant={pressed?'secondary':'ghost'} tooltip={t(`library.${label}`)} aria-label={t(`library.${label}`)} aria-pressed={pressed} disabled={loss || disabled} onClick={run}><Icon aria-hidden="true" /></Button>)}</div><EditorContent editor={editor} /></div>;
}

export function PassagePreview({ html }: { html: string }) {
  const { t } = useTranslation("admin");
  const [height, setHeight] = useState(140);
  const observer = useRef<ResizeObserver | null>(null);
  useEffect(() => () => observer.current?.disconnect(), []);
  const document = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><style>html{color-scheme:light dark}body{font:14px/1.55 system-ui;padding:12px;background:Canvas;color:CanvasText}table{border-collapse:collapse;width:100%}td,th{border:1px solid;padding:8px}</style>${html}`;
  // Same-origin allows height measurement; scripts stay disabled by both sandbox and CSP.
  return <iframe className="w-full rounded-lg border bg-card" style={{height}} title={t("library.passagePreview")} sandbox="allow-same-origin" referrerPolicy="no-referrer" srcDoc={document} onLoad={event => {
    const body=event.currentTarget.contentDocument?.body;
    if (!body) return;
    observer.current?.disconnect();
    const resize=() => setHeight(Math.max(120,Math.min(640,Math.ceil(body.getBoundingClientRect().height+32))));
    observer.current=new ResizeObserver(resize);observer.current.observe(body);resize();
  }} />;
}
