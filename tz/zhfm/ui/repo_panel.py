# File: zhfm/ui/repo_panel.py
# 功能：左側 Repo / Branch 選擇面板

from __future__ import annotations
import traceback

try:
    from typing import Optional, List
    from PySide6.QtCore import Qt, Signal
    from PySide6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QComboBox, QPushButton
    from github import Github, GithubException
    from github.Repository import Repository as GhRepository
except Exception as e:
    print("=== repo_panel.py 匯入失敗 ===")
    traceback.print_exc()
    RepoPanel = None
else:
    class RepoPanel(QWidget):
        """左側：帳號可見的 Repo 下拉 + 分支下拉"""
        repoChanged = Signal(object)   # emits GhRepository
        branchChanged = Signal(str)    # emits branch name

        def __init__(self, parent: Optional[QWidget] = None) -> None:
            super().__init__(parent)
            self._github: Optional[Github] = None
            self._repos: List[GhRepository] = []

            layout = QVBoxLayout(self)
            row = QHBoxLayout()

            self.repo_label = QLabel("Repo：")
            self.repo_combo = QComboBox()
            self.repo_combo.setMinimumWidth(200) # 給予最小寬度
            self.repo_combo.setSizeAdjustPolicy(QComboBox.AdjustToContents)
            # 防止下拉選單內容被截斷
            self.repo_combo.view().setTextElideMode(Qt.ElideNone)
            self.repo_combo.currentIndexChanged.connect(self._on_repo_changed)

            self.branch_label = QLabel("分支：")
            self.branch_combo = QComboBox()
            self.branch_combo.currentIndexChanged.connect(self._on_branch_changed)

            self.refresh_btn = QPushButton("刷新")
            self.refresh_btn.clicked.connect(self.refresh)

            row.addWidget(self.repo_label)
            row.addWidget(self.repo_combo)
            row.addWidget(self.branch_label)
            row.addWidget(self.branch_combo)
            row.addWidget(self.refresh_btn)
            layout.addLayout(row)
            self.setLayout(layout)

        def set_github(self, gh: Github) -> None:
            self._github = gh
            self.refresh()

        # 兼容舊版呼叫名稱
        def attach_github(self, gh: Github) -> None:
            self.set_github(gh)

        def clear(self) -> None:
            """清空下拉與快取"""
            self._repos = []
            self.repo_combo.clear()
            self.branch_combo.clear()

        def current_repo(self) -> Optional[GhRepository]:
            idx = self.repo_combo.currentIndex()
            if 0 <= idx < len(self._repos):
                return self._repos[idx]
            return None

        def current_branch(self) -> str:
            return self.branch_combo.currentText() or ""

        def refresh(self) -> None:
            if not self._github:
                print("[RepoPanel] 尚未設定 Github")
                return
            try:
                self.repo_combo.clear()
                self.branch_combo.clear()
                self._repos = list(self._github.get_user().get_repos())
                for r in self._repos:
                    self.repo_combo.addItem(r.full_name, r)
            except GithubException as e:
                print(f"[RepoPanel] 無法載入 Repo：{e}")

        def _on_repo_changed(self, idx: int) -> None:
            if 0 <= idx < len(self._repos):
                repo = self._repos[idx]
                self.repoChanged.emit(repo)
                self._load_branches(repo)

        def _load_branches(self, repo: GhRepository) -> None:
            self.branch_combo.clear()
            try:
                for br in repo.get_branches():
                    self.branch_combo.addItem(br.name)
            except GithubException as e:
                print(f"[RepoPanel] 無法載入分支：{e}")

        def _on_branch_changed(self, idx: int) -> None:
            branch = self.branch_combo.currentText()
            if branch:
                self.branchChanged.emit(branch)
