# File: zhfm/ui/code_editor.py
from __future__ import annotations

from typing import Optional

from PySide6 import QtCore, QtGui, QtWidgets
from PySide6.QtCore import QRect, QSize, Qt, QRegularExpression
from PySide6.QtGui import QColor, QPainter, QTextCursor
from PySide6.QtWidgets import QPlainTextEdit, QWidget


class _LineNumberArea(QWidget):
    def __init__(self, editor: 'CodeEditor') -> None:
        super().__init__(editor)
        self._editor = editor

    def sizeHint(self) -> QSize:  # type: ignore[override]
        return QSize(self._editor.line_number_area_width(), 0)

    def paintEvent(self, event):  # type: ignore[override]
        self._editor.line_number_area_paint_event(event)


class CodeEditor(QPlainTextEdit):
    """QPlainTextEdit with a line-number gutter, simple find/highlight,
    themeable gutter, optional word-wrap, and Ctrl+wheel font zoom.
    """

    # default colors (light theme)
    _LIGHT = {
        'gutter_bg': QColor(245, 245, 245),
        'gutter_fg': QColor(130, 130, 130),
        'gutter_rule': QColor(225, 225, 225),
        'find': QColor(255, 235, 59, 120),  # amber with alpha
    }
    _DARK = {
        'gutter_bg': QColor(40, 40, 40),
        'gutter_fg': QColor(160, 160, 160),
        'gutter_rule': QColor(70, 70, 70),
        'find': QColor(255, 235, 59, 120),
    }

    def __init__(self, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)

        # --- find state (define early) ---
        self._find_regex: Optional[QRegularExpression] = None

        # --- theme state ---
        self._theme: str = 'light'
        self._colors = dict(self._LIGHT)

        # --- wrap/font state ---
        self._wrap_enabled: bool = False
        self._font_point_size: int = 10

        # --- widgets & signals ---
        self._line_area = _LineNumberArea(self)
        self.blockCountChanged.connect(self._update_line_number_area_width)
        self.updateRequest.connect(self._update_line_number_area)
        self.cursorPositionChanged.connect(self._highlight_current_line)
        self.textChanged.connect(self._rehighlight_find_results)

        # defaults for code viewing
        self.setWordWrapMode(QtGui.QTextOption.NoWrap)
        font = QtGui.QFont('Consolas', self._font_point_size)
        font.setStyleHint(QtGui.QFont.Monospace)
        self.setFont(font)

        # initial layout + highlight
        self._update_line_number_area_width(0)
        self._highlight_current_line()

    # ---------------- theming / wrap / zoom ----------------
    def set_theme(self, theme: str) -> None:
        t = (theme or 'light').lower()
        if t not in ('light','dark'):
            t = 'light'
        self._theme = t
        self._colors = dict(self._LIGHT if t == 'light' else self._DARK)
        # update find highlight color in case themes differ in future
        self._rehighlight_find_results()
        # repaint gutter
        self._line_area.update()

    def set_wrap_enabled(self, enabled: bool) -> None:
        self._wrap_enabled = bool(enabled)
        mode = (QtGui.QTextOption.WrapAtWordBoundaryOrAnywhere
                if self._wrap_enabled else QtGui.QTextOption.NoWrap)
        self.setWordWrapMode(mode)

    def increase_font(self, step: int = 1) -> None:
        self._set_point_size(self._font_point_size + step)

    def decrease_font(self, step: int = 1) -> None:
        self._set_point_size(self._font_point_size - step)

    def reset_font(self, size: int = 10) -> None:
        self._set_point_size(size)

    def _set_point_size(self, size: int) -> None:
        size = max(6, min(36, size))
        self._font_point_size = size
        f = self.font(); f.setPointSize(size); self.setFont(f)
        self._update_line_number_area_width(0)

    def wheelEvent(self, ev):  # type: ignore[override]
        if ev.modifiers() & Qt.ControlModifier:
            delta = ev.angleDelta().y()
            if delta > 0:
                self.increase_font(1)
            elif delta < 0:
                self.decrease_font(1)
            ev.accept(); return
        super().wheelEvent(ev)

    # ---------------- line numbers ----------------
    def line_number_area_width(self) -> int:
        digits = len(str(max(1, self.blockCount())))
        return 10 + self.fontMetrics().horizontalAdvance('9') * digits

    def _update_line_number_area_width(self, _new_block_count: int) -> None:
        self.setViewportMargins(self.line_number_area_width(), 0, 0, 0)

    def _update_line_number_area(self, rect: QRect, dy: int) -> None:
        if dy:
            self._line_area.scroll(0, dy)
        else:
            self._line_area.update(0, rect.y(), self._line_area.width(), rect.height())
        if rect.contains(self.viewport().rect()):
            self._update_line_number_area_width(0)

    def resizeEvent(self, event):  # type: ignore[override]
        super().resizeEvent(event)
        cr = self.contentsRect()
        self._line_area.setGeometry(QRect(cr.left(), cr.top(), self.line_number_area_width(), cr.height()))

    def line_number_area_paint_event(self, event) -> None:
        p = QPainter(self._line_area)
        p.fillRect(event.rect(), self._colors['gutter_bg'])
        r = event.rect()
        p.setPen(self._colors['gutter_rule'])
        p.drawLine(r.right()-1, r.top(), r.right()-1, r.bottom())

        block = self.firstVisibleBlock()
        block_number = block.blockNumber()
        top = int(self.blockBoundingGeometry(block).translated(self.contentOffset()).top())
        bottom = top + int(self.blockBoundingRect(block).height())

        p.setPen(self._colors['gutter_fg'])
        fm = self.fontMetrics()
        while block.isValid() and top <= event.rect().bottom():
            if block.isVisible() and bottom >= event.rect().top():
                p.drawText(0, top, self._line_area.width()-6, fm.height(), Qt.AlignRight | Qt.AlignVCenter, str(block_number + 1))
            block = block.next()
            top = bottom
            bottom = top + int(self.blockBoundingRect(block).height())
            block_number += 1

    def _highlight_current_line(self) -> None:
        extra: list[QtWidgets.QTextEdit.ExtraSelection] = []
        sel = QtWidgets.QTextEdit.ExtraSelection()
        sel.format.setBackground(QtCore.Qt.transparent)
        sel.format.setProperty(QtGui.QTextFormat.FullWidthSelection, True)
        sel.cursor = self.textCursor()
        sel.cursor.clearSelection()
        extra.append(sel)
        extra.extend(self._build_find_selections())
        self.setExtraSelections(extra)

    # ---------------- find API ----------------
    def set_find_pattern(self, pattern: str, case_sensitive: bool = False) -> int:
        pattern = pattern or ''
        if not pattern:
            self._find_regex = None
            return self._rehighlight_find_results()
        rx = QRegularExpression(QRegularExpression.escape(pattern))
        if not case_sensitive:
            rx.setPatternOptions(QRegularExpression.CaseInsensitiveOption)
        self._find_regex = rx
        return self._rehighlight_find_results()

    def _build_find_selections(self) -> list[QtWidgets.QTextEdit.ExtraSelection]:
        out: list[QtWidgets.QTextEdit.ExtraSelection] = []
        rx = getattr(self, '_find_regex', None)
        if not rx:
            return out
        doc = self.document()
        cur = QTextCursor(doc)
        fmt = QtGui.QTextCharFormat()
        fmt.setBackground(self._colors['find'])
        while True:
            cur = doc.find(rx, cur)
            if cur.isNull():
                break
            ex = QtWidgets.QTextEdit.ExtraSelection()
            ex.cursor = cur
            ex.format = fmt
            out.append(ex)
        return out

    def _rehighlight_find_results(self) -> int:
        count = 0
        rx = getattr(self, '_find_regex', None)
        if rx:
            doc = self.document()
            cur = QTextCursor(doc)
            while True:
                cur = doc.find(rx, cur)
                if cur.isNull():
                    break
                count += 1
        self._highlight_current_line()
        return count

    def find_next(self) -> bool:
        rx = getattr(self, '_find_regex', None)
        if not rx:
            return False
        start = self.textCursor()
        cur = self.document().find(rx, start)
        if cur.isNull():
            cur = self.document().find(rx, QTextCursor(self.document()))
        if cur.isNull():
            return False
        self.setTextCursor(cur)
        return True

    def find_prev(self) -> bool:
        rx = getattr(self, '_find_regex', None)
        if not rx:
            return False
        start = self.textCursor()
        cur = self.document().find(rx, start, QtGui.QTextDocument.FindBackward)
        if cur.isNull():
            end = QTextCursor(self.document()); end.movePosition(QTextCursor.End)
            cur = self.document().find(rx, end, QtGui.QTextDocument.FindBackward)
        if cur.isNull():
            return False
        self.setTextCursor(cur)
        return True
