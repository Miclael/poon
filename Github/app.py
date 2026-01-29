# File: app.py
from __future__ import annotations
import os
import sys

# 高 DPI & 字型渲染優化（可選）
os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "1")
os.environ.setdefault("QT_FONT_DPI", "120")

from PySide6.QtWidgets import QApplication
from zhfm.ui.main_window import MainWindow


def main() -> int:
    """應用程式進入點"""
    app = QApplication(sys.argv)
    app.setApplicationName("中文 GitHub 檔案管理工具")
    app.setOrganizationName("zhfm")

    win = MainWindow()
    win.show()

    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
