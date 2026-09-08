# Optional MCP / NotebookLM developer tooling

Lyreo **không phụ thuộc NotebookLM/MCP ở runtime**. Thư mục này chỉ dành cho developer/AI agent nếu team dùng NotebookLM làm knowledge base cho SRS, architecture decisions, Qwen docs, research papers hoặc dataset notes.

`notebooklm.example.json` là file minh họa, không chứa cookie/token. NotebookLM MCP implementations hiện chủ yếu là community tooling; team phải review security và cách authentication trước khi dùng.

Nguyên tắc:
- Không commit browser cookie/session/token.
- MCP không được quyền tự thay đổi production secrets.
- Repo docs + source code vẫn là source of truth; NotebookLM là search/research aid.
