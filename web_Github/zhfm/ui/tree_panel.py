# File: zhfm/ui/tree_panel.py
# 功能：右側檔案樹狀瀏覽面板（支援右鍵選單與拖曳上傳）

from __future__ import annotations
import traceback

try:
    from typing import Optional, Tuple, List
    from PySide6.QtCore import Qt, Signal, QPoint
    from PySide6.QtGui import QKeySequence
    from PySide6.QtWidgets import QWidget, QVBoxLayout, QTreeWidget, QTreeWidgetItem, QLabel, QMenu, QApplication, QStyle, QTreeWidgetItemIterator
    from github.Repository import Repository as GhRepository
except Exception as e:
    print('=== tree_panel.py 匯入失敗 ===')
    traceback.print_exc()
    TreePanel = None
else:
    class TreePanel(QWidget):
        """右側：檔案樹狀結構顯示
        - 雙擊/Enter 檔案節點 -> 發出 fileSelected(path)
        - 右鍵功能：新增資料夾/檔案、刪除、重新命名、複製/貼上
        - 支援拖曳上傳（發出 filesDropped(target_dir, file_paths)）
        """

        # MainWindow 連線用
        fileSelected = Signal(str)
        newFolderRequested = Signal(str)   # 目標目錄
        newFileRequested = Signal(str)     # 目標目錄
        deleteRequested = Signal(str, str) # (path, type)
        renameRequested = Signal(str, str) # (path, type)
        copyRequested = Signal(str, str)   # (path, type)
        pasteRequested = Signal(str)       # 目標目錄
        filesDropped = Signal(str, list)   # (target_dir, [local_files])

        def __init__(self, parent: Optional[QWidget] = None) -> None:
            super().__init__(parent)
            layout = QVBoxLayout(self)
            self.tree = QTreeWidget()
            self.tree.setHeaderLabels(['檔案', '類型', '大小'])
            self.tree.setSelectionMode(QTreeWidget.ExtendedSelection) # 啟用多選
            layout.addWidget(QLabel('檔案樹'))
            layout.addWidget(self.tree)
            self.setLayout(layout)

            # 檔案開啟
            self.tree.itemActivated.connect(self._on_item_activated)

            # 右鍵選單
            self.tree.setContextMenuPolicy(Qt.CustomContextMenu)
            self.tree.customContextMenuRequested.connect(self._on_context_menu)

            # 拖曳上傳
            self.setAcceptDrops(True)

        # ---- 公用方法（供 MainWindow 使用）----
        def clear_all(self) -> None:
            self.tree.clear()

        def selected_info(self) -> Optional[Tuple[str, str]]:
            items = self.tree.selectedItems()
            if not items:
                return None
            # 回傳最後選取的項目資訊 (相容舊版單選邏輯)
            item = items[-1]
            data = item.data(0, Qt.UserRole) or {}
            path = data.get('path')
            typ = data.get('type')
            if isinstance(path, str) and isinstance(typ, str):
                return path, typ
            return None

        def selected_items_info(self) -> List[Tuple[str, str]]:
            """回傳所有選取項目的 (path, type) 列表"""
            results = []
            for item in self.tree.selectedItems():
                data = item.data(0, Qt.UserRole) or {}
                path = data.get('path')
                typ = data.get('type')
                if isinstance(path, str) and isinstance(typ, str):
                    results.append((path, typ))
            return results

        def selected_dir_path(self) -> str:
            info = self.selected_info()
            if not info:
                return ''
            path, typ = info
            if typ == 'dir':
                return path
            return path.rsplit('/', 1)[0] if '/' in path else ''

        # ---- 載入資料 ----
        def load_root(self, repo: GhRepository, branch: str) -> None:
            self.tree.clear()
            try:
                contents = repo.get_contents('', ref=branch)
                # 排序：目錄優先，其次檔名
                contents.sort(key=lambda x: (x.type != 'dir', x.name.lower()))
                
                for item in contents:
                    self._add_item(None, item, repo, branch)
            except Exception as e:
                print(f'[TreePanel] 無法載入檔案樹：{e}')

        def _add_item(self, parent: Optional[QTreeWidgetItem], gh_content, repo: GhRepository, branch: str) -> None:
            node = QTreeWidgetItem([gh_content.name, gh_content.type, str(getattr(gh_content, 'size', 0))])
            node.setData(0, Qt.UserRole, {'path': gh_content.path, 'type': gh_content.type})
            
            # 設定圖示
            style = QApplication.style()
            if gh_content.type == 'dir':
                icon = style.standardIcon(QStyle.SP_DirIcon)
            else:
                icon = style.standardIcon(QStyle.SP_FileIcon)
            node.setIcon(0, icon)

            if parent:
                parent.addChild(node)
            else:
                self.tree.addTopLevelItem(node)

            if gh_content.type == 'dir':
                try:
                    subs = repo.get_contents(gh_content.path, ref=branch)
                    # 排序：目錄優先，其次檔名
                    subs.sort(key=lambda x: (x.type != 'dir', x.name.lower()))
                    
                    for sub in subs:
                        self._add_item(node, sub, repo, branch)
                except Exception as e:
                    print(f'[TreePanel] 無法載入子資料夾：{e}')

        # ---- 事件 ----
        def _on_item_activated(self, item: QTreeWidgetItem, _col: int) -> None:
            data = item.data(0, Qt.UserRole) or {}
            if data.get('type') == 'file':
                path = data.get('path')
                if isinstance(path, str):
                    self.fileSelected.emit(path)

        def _on_context_menu(self, pos: QPoint) -> None:
            info = self.selected_info()
            dir_path = self.selected_dir_path()
            menu = QMenu(self)
            act_new_folder = menu.addAction('新增資料夾...')
            act_new_file = menu.addAction('新增檔案...')
            if info:
                menu.addSeparator()
                act_rename = menu.addAction('重新命名...')
                act_delete = menu.addAction('刪除...')
                menu.addSeparator()
                act_copy = menu.addAction('複製')
            else:
                act_rename = act_delete = act_copy = None
            act_paste = menu.addAction('貼上')

            action = menu.exec(self.tree.viewport().mapToGlobal(pos))
            if not action:
                return
            if action == act_new_folder:
                self.newFolderRequested.emit(dir_path)
            elif action == act_new_file:
                self.newFileRequested.emit(dir_path)
            elif info and action == act_delete:
                self.deleteRequested.emit(info[0], info[1])
            elif info and action == act_rename:
                self.renameRequested.emit(info[0], info[1])
            elif info and action == act_copy:
                self.copyRequested.emit(info[0], info[1])
            elif action == act_paste:
                self.pasteRequested.emit(dir_path)

        # ---- 拖曳上傳 ----
        def dragEnterEvent(self, event):
            md = event.mimeData()
            if md and md.hasUrls():
                event.acceptProposedAction()
            else:
                event.ignore()

        def dropEvent(self, event):
            md = event.mimeData()
            if not (md and md.hasUrls()):
                event.ignore()
                return
            paths: List[str] = []
            for url in md.urls():
                if url.isLocalFile():
                    paths.append(url.toLocalFile())
            if not paths:
                event.ignore()
                return
            target_dir = self.selected_dir_path()
            self.filesDropped.emit(target_dir, paths)
            event.acceptProposedAction()

        # ---- 狀態保存與恢復 ----
        def get_expanded_paths(self) -> set[str]:
            expanded = set()
            it = QTreeWidgetItemIterator(self.tree)
            while it.value():
                item = it.value()
                if item.isExpanded():
                    data = item.data(0, Qt.UserRole) or {}
                    path = data.get('path')
                    if path:
                        expanded.add(path)
                it += 1
            return expanded

        def restore_expanded_paths(self, paths: set[str]) -> None:
            it = QTreeWidgetItemIterator(self.tree)
            while it.value():
                item = it.value()
                data = item.data(0, Qt.UserRole) or {}
                path = data.get('path')
                if path and path in paths:
                    item.setExpanded(True)
                it += 1

        # ---- 鍵盤事件 ----
        def keyPressEvent(self, event):
            if event.matches(QKeySequence.Paste):
                target_dir = self.selected_dir_path()
                self.pasteRequested.emit(target_dir)
                event.accept()
            else:
                super().keyPressEvent(event)

        # ---- 過濾功能 ----
        def filter_items(self, text: str) -> None:
            text = text.lower().strip()
            
            # 若無關鍵字，全部顯示
            if not text:
                it = QTreeWidgetItemIterator(self.tree)
                while it.value():
                    item = it.value()
                    item.setHidden(False)
                    it += 1
                return

            # 遞迴過濾函式
            def check_item(item: QTreeWidgetItem) -> bool:
                # 檢查自身
                name = item.text(0).lower()
                matches = text in name
                
                # 檢查子項目
                child_matches = False
                for i in range(item.childCount()):
                    child = item.child(i)
                    if check_item(child):
                        child_matches = True
                
                # 若自身符合或子項目有符合，則顯示
                visible = matches or child_matches
                item.setHidden(not visible)
                
                # 若顯示且有關鍵字，自動展開以便查看
                if visible:
                    item.setExpanded(True)
                
                return visible

            # 從頂層開始遍歷
            for i in range(self.tree.topLevelItemCount()):
                item = self.tree.topLevelItem(i)
                check_item(item)
