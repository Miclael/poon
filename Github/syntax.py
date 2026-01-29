# File: zhfm/ui/syntax.py
from __future__ import annotations
from PySide6.QtGui import QSyntaxHighlighter, QTextCharFormat, QColor, QFont
from PySide6.QtCore import QRegularExpression

THEMES = {
 'light': {'kw':'#7c4dff','str':'#43a047','num':'#ff7043','com':'#9e9e9e','tag':'#1e88e5','attr':'#00897b','text':'#222'},
 'dark':  {'kw':'#b39ddb','str':'#a5d6a7','num':'#ffab91','com':'#bdbdbd','tag':'#81d4fa','attr':'#80cbc4','text':'#eee'}
}

def _fmt(c,b=False,i=False):
    f=QTextCharFormat(); f.setForeground(QColor(c))
    if b: f.setFontWeight(QFont.Bold)
    if i: f.setFontItalic(True)
    return f

class MultiLangHighlighter(QSyntaxHighlighter):
    S_NONE=0; S_CODE=1
    def __init__(self, doc, theme='light'):
        super().__init__(doc)
        self.theme=theme if theme in THEMES else 'light'
        self.fmts=self._build(self.theme)
        self.lang='text'; self.rules=[]
        self.set_language('text')
    def _build(self,t):
        c=THEMES[t]
        return {'kw':_fmt(c['kw'],True),'str':_fmt(c['str']),'num':_fmt(c['num']),'com':_fmt(c['com'],True),'tag':_fmt(c['tag'],True),'attr':_fmt(c['attr']),'text':_fmt(c['text'])}
    def set_theme(self,t):
        t=t if t in THEMES else 'light'
        if t!=self.theme:
            self.theme=t; self.fmts=self._build(t); self.rehighlight()
    def set_language(self,lang):
        self.lang=(lang or 'text').lower(); self.rules=[]
        add=lambda p,k:self.rules.append((QRegularExpression(p),k))
        if self.lang in ('markdown','md'):
            add(r'^#{1,6} .*$', 'kw'); add(r'`[^`]+`','attr'); add(r'!\[[^\]]*\]\([^\)]*\)','tag'); add(r'\[[^\]]*\]\([^\)]*\)','tag'); add(r'^>.*$','com'); add(r'^(?:- |\* ).*$','num')
        else:
            add(r'#.*$','com'); add(r'//.*$','com'); add(r'"[^"\n]*"','str'); add(r"'[^'\n]*'",'str'); add(r'\b[0-9]+(\.[0-9]+)?\b','num')
        self.rehighlight()
    def highlightBlock(self, text):
        if self.lang in ('markdown','md'):
            self._hl_md(text); return
        for rx,key in self.rules:
            it=rx.globalMatch(text)
            while it.hasNext():
                m=it.next(); self.setFormat(m.capturedStart(),m.capturedLength(),self.fmts[key])
    def _hl_md(self,text):
        fence_start=QRegularExpression(r'^```')
        fence_end=QRegularExpression(r'^```\s*$')
        prev=self.previousBlockState()
        if prev==self.S_CODE and not fence_end.match(text).hasMatch():
            self.setFormat(0,len(text),self.fmts['attr']); self.setCurrentBlockState(self.S_CODE); return
        for rx,key in self.rules:
            it=rx.globalMatch(text)
            while it.hasNext():
                m=it.next(); self.setFormat(m.capturedStart(),m.capturedLength(),self.fmts[key])
        if fence_start.match(text).hasMatch():
            self.setFormat(0,len(text),self.fmts['com']); self.setCurrentBlockState(self.S_CODE)
        else:
            self.setCurrentBlockState(self.S_NONE)

def guess_lang_from_path(p):
    p=(p or '').lower()
    if p.endswith('.md') or p.endswith('.markdown'): return 'markdown'
    if p.endswith('.py'): return 'python'
    if p.endswith('.js') or p.endswith('.jsx'): return 'javascript'
    if p.endswith('.ts') or p.endswith('.tsx'): return 'typescript'
    if p.endswith('.json'): return 'json'
    if p.endswith('.html') or p.endswith('.htm'): return 'html'
    if p.endswith('.css') or p.endswith('.scss'): return 'css'
    return 'text'
