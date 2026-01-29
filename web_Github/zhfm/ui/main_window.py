# File: zhfm/ui/main_window.py
from __future__ import annotations

from PySide6.QtCore import Qt, QThreadPool
from PySide6.QtGui import QAction, QKeySequence, QPixmap
from PySide6.QtWidgets import (
    QMainWindow, QSplitter, QStatusBar, QToolBar, QMessageBox, QTextEdit, QFileDialog, QInputDialog, QProgressDialog,
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton, QApplication, QStackedWidget, QSizePolicy
)
from github import Github, BadCredentialsException, GithubException

from zhfm.ui import AuthDialog, RepoPanel, TreePanel
from zhfm.ui.code_editor import CodeEditor
from zhfm.ui.syntax import MultiLangHighlighter, guess_lang_from_path
from zhfm.utils.workers import Worker

import io, zipfile, re, os, urllib.parse


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("中文 GitHub 檔案管理工具 (M3)")
        self.resize(1300, 820)

        self.github: Github | None = None
        self.current_user = None
        self._last_selected_path: str | None = None
        self._current_file_sha: str | None = None  # To store SHA for file updates
        self._clipboard: list[tuple[str, str]] = []  # (path, type) for copy/paste
        self._active_workers = [] # Keep references to prevent GC
        
        self.threadpool = QThreadPool()

        self._init_ui()
        self._init_menu()
        self._init_toolbar()
        self._init_statusbar()

        self._auto_login()

    # ---------- UI ----------
    def _init_ui(self) -> None:
        self.splitter = QSplitter(Qt.Horizontal)

        self.repo_panel = RepoPanel()
        self.repo_panel.repoChanged.connect(self._on_repo_changed)
        self.repo_panel.branchChanged.connect(self._on_branch_changed)

        self.tree_panel = TreePanel()
        self.tree_panel.fileSelected.connect(self._on_file_selected)
        # 右鍵選單請求
        self.tree_panel.newFolderRequested.connect(self._on_new_folder)
        self.tree_panel.newFileRequested.connect(self._on_new_file)
        self.tree_panel.deleteRequested.connect(self._on_delete)
        self.tree_panel.renameRequested.connect(self._on_rename)
        self.tree_panel.copyRequested.connect(self._on_copy)
        self.tree_panel.pasteRequested.connect(self._on_paste)
        self.tree_panel.filesDropped.connect(self._on_files_dropped)

        # 預覽區域 (Stack: 0=CodeEditor, 1=ImageLabel)
        self.preview_stack = QStackedWidget()
        
        # Page 0: Code Editor
        self.preview = CodeEditor()
        self.preview.setReadOnly(True)
        self.preview.setPlaceholderText("選擇檔案即可預覽內容（文字/Markdown）")
        self.highlighter = MultiLangHighlighter(self.preview.document())
        self.preview_stack.addWidget(self.preview)

        # Page 1: Image Viewer
        self.image_label = QLabel()
        self.image_label.setAlignment(Qt.AlignCenter)
        self.image_label.setStyleSheet("background-color: #f0f0f0;") # 灰色背景區分
        self.preview_stack.addWidget(self.image_label)

        # 左側面板容器 (RepoPanel + TreePanel)
        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        
        # 搜尋框
        self.search_bar = QLineEdit()
        self.search_bar.setPlaceholderText("輸入關鍵字過濾檔案...")
        self.search_bar.textChanged.connect(self.tree_panel.filter_items)
        
        left_layout.addWidget(self.repo_panel)  # Repo 在上
        left_layout.addWidget(self.search_bar)  # Search
        left_layout.addWidget(self.tree_panel)  # Tree 在下

        # 右側面板容器 (URL Bar + Preview)
        right_widget = QWidget()
        right_layout = QVBoxLayout(right_widget)
        right_layout.setContentsMargins(0, 0, 0, 0)

        # URL 顯示列
        url_layout = QHBoxLayout()
        url_label = QLabel("檔案網址：")
        self.url_edit = QLineEdit()
        self.url_edit.setReadOnly(True)
        self.url_edit.setPlaceholderText("選取檔案後顯示 GitHub 網址")
        self.btn_copy_url = QPushButton("複製網址")
        self.btn_copy_url.clicked.connect(self._copy_file_url)

        self.btn_edit = QPushButton("編輯")
        self.btn_edit.setCheckable(True)
        self.btn_edit.setEnabled(False) # 尚未選取檔案不可編輯
        self.btn_edit.clicked.connect(self._toggle_edit_mode)

        self.btn_save = QPushButton("儲存")
        self.btn_save.setEnabled(False)
        self.btn_save.clicked.connect(self._save_current_file)

        url_layout.addWidget(url_label)
        url_layout.addWidget(self.url_edit)
        url_layout.addWidget(self.btn_copy_url)
        url_layout.addWidget(self.btn_edit)
        url_layout.addWidget(self.btn_save)

        right_layout.addLayout(url_layout)
        right_layout.addWidget(self.preview_stack)

        self.splitter.addWidget(left_widget)
        self.splitter.addWidget(right_widget)
        self.splitter.setStretchFactor(0, 1)
        self.splitter.setStretchFactor(1, 2)

        self.setCentralWidget(self.splitter)

    def _init_menu(self) -> None:
        menubar = self.menuBar()

        m_file = menubar.addMenu("檔案(&F)")
        act_quit = QAction("離開", self)
        act_quit.setShortcut(QKeySequence.Quit)
        act_quit.triggered.connect(self.close)
        m_file.addAction(act_quit)

        m_acc = menubar.addMenu("帳號(&A)")
        act_login = QAction("登入 / 修改 Token", self)
        act_login.triggered.connect(self._show_auth_dialog)
        m_acc.addAction(act_login)

        act_logout = QAction("登出", self)
        act_logout.triggered.connect(self._logout)
        m_acc.addAction(act_logout)

        m_help = menubar.addMenu("說明(&H)")
        act_about = QAction("關於", self)
        act_about.triggered.connect(self._show_about)
        m_help.addAction(act_about)

    def _init_toolbar(self) -> None:
        tb = QToolBar("主要工具")
        tb.setMovable(False)
        self.addToolBar(Qt.TopToolBarArea, tb)

        act_refresh = QAction("重新整理", self)
        act_refresh.setStatusTip("重新整理目前視圖")
        act_refresh.triggered.connect(self._refresh_all)
        tb.addAction(act_refresh)

        act_download = QAction("下載檔案", self)
        act_download.setStatusTip("下載目前選取的檔案到本機")
        act_download.triggered.connect(self._download_current_file)
        tb.addAction(act_download)

        act_download_zip = QAction("下載資料夾(zip)", self)
        act_download_zip.setStatusTip("將目前選取的資料夾打包成 zip 下載")
        act_download_zip.triggered.connect(self._download_selected_folder_zip)
        tb.addAction(act_download_zip)

        act_upload = QAction("上傳檔案", self)
        act_upload.setStatusTip("上傳本機檔案到目前 Repo/Branch")
        act_upload.triggered.connect(self._upload_file)
        tb.addAction(act_upload)

    def _init_statusbar(self) -> None:
        self.sb = QStatusBar()
        self.sb.showMessage("未登入 GitHub")
        self.setStatusBar(self.sb)

    # ---------- 帳號處理 ----------
    def _auto_login(self):
        token = AuthDialog.get_saved_token()
        if not token:
            return
        try:
            gh = Github(token)
            user = gh.get_user()
            _ = user.login
            self.github = gh
            self.current_user = user
            self.sb.showMessage(f"已登入：{user.login}")
            self.repo_panel.set_github(self.github)
        except BadCredentialsException:
            self.sb.showMessage("Token 無效，請重新登入。")
        except Exception:
            self.sb.showMessage("自動登入失敗。")

    def _show_auth_dialog(self):
        dlg = AuthDialog(self)
        if dlg.exec():
            self._auto_login()

    def _logout(self):
        AuthDialog.clear_token()
        self.github = None
        self.current_user = None
        self.repo_panel.clear()
        self.tree_panel.clear_all()
        self.preview.clear()
        self.sb.showMessage("已登出")

    # ---------- 事件 ----------
    def _on_repo_changed(self, repo):
        self.tree_panel.clear_all()
        self.preview.clear()
        if repo:
            self.sb.showMessage(f"已選擇 Repo：{repo.full_name}", 3000)

    def _on_branch_changed(self, branch_name: str):
        repo = self.repo_panel.current_repo()
        if repo and branch_name:
            self.tree_panel.load_root(repo, branch_name)
            self.preview.clear()
            self.sb.showMessage(f"載入檔案樹：{repo.full_name}@{branch_name}", 3000)

    def _on_file_selected(self, path: str):
        self._last_selected_path = path
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return
        
        # 設定檔案網址
        # 格式: https://github.com/{user}/{repo}/blob/{branch}/{path}
        # repo.html_url 通常是 https://github.com/{user}/{repo}
        encoded_path = urllib.parse.quote(path)
        encoded_branch = urllib.parse.quote(branch)
        file_url = f"{repo.html_url}/blob/{encoded_branch}/{encoded_path}"
        self.url_edit.setText(file_url)

        self.preview.setPlainText("正在載入檔案內容...")
        
        # 啟動背景執行緒載入檔案
        worker = Worker(repo.get_contents, path, ref=branch)
        
        # 防止 Worker 被 GC 導致訊號斷開
        self._active_workers.append(worker)
        worker.signals.finished.connect(lambda: self._active_workers.remove(worker) if worker in self._active_workers else None)
        
        worker.signals.result.connect(lambda res: self._on_file_loaded(path, res))
        worker.signals.error.connect(self._on_file_load_error)
        self.threadpool.start(worker)

    def _on_file_loaded(self, path: str, content):
        # 若使用者在載入過程中切換了檔案，則忽略舊的結果
        if self._last_selected_path != path:
            return

        try:
            if getattr(content, "size", 0) > 5_000_000: # 5MB limit
                self.preview.setPlainText(
                    f"[檔案過大（{content.size} bytes）] 之後可支援下載/分段預覽。"
                )
                self.preview_stack.setCurrentIndex(0)
                return

            ext = os.path.splitext(path)[1].lower()
            if ext in ('.png', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.webp'):
                # 圖片處理
                data = content.decoded_content
                pixmap = QPixmap()
                if pixmap.loadFromData(data):
                    # 縮放圖片以適應視窗，保持比例
                    w = self.preview_stack.width() - 20
                    h = self.preview_stack.height() - 20
                    if pixmap.width() > w or pixmap.height() > h:
                        pixmap = pixmap.scaled(w, h, Qt.KeepAspectRatio, Qt.SmoothTransformation)
                    
                    self.image_label.setPixmap(pixmap)
                    self.image_label.setText("")
                else:
                    self.image_label.setText("[無法載入圖片]")
                    self.image_label.setPixmap(QPixmap())
                
                self.preview_stack.setCurrentIndex(1) # 切換到圖片頁
                self.btn_edit.setEnabled(False) # 圖片不可編輯
                self.btn_save.setEnabled(False)
                # 儲存 SHA 供參考（雖然不能直接存檔）
                self._current_file_sha = content.sha
            else:
                # 文字處理
                try:
                    text = content.decoded_content.decode("utf-8")
                except UnicodeDecodeError:
                    text = content.decoded_content.decode("utf-8", errors="replace")
                
                self.preview.setPlainText(text)
                
                # 儲存 SHA 並啟用編輯功能
                self._current_file_sha = content.sha
                self.btn_edit.setEnabled(True)
                if self.btn_edit.isChecked():
                    self.preview.setReadOnly(False)
                    self.btn_save.setEnabled(True)
                else:
                    self.preview.setReadOnly(True)
                    self.btn_save.setEnabled(False)

                # 設定語法高亮
                lang = guess_lang_from_path(path)
                self.highlighter.set_language(lang)
                
                self.preview_stack.setCurrentIndex(0) # 切換到文字頁
            
        except Exception as e:
            self.preview.setPlainText(f"[解析失敗] {e}")
            self.preview_stack.setCurrentIndex(0)
            
        except Exception as e:
            self.preview.setPlainText(f"[解析失敗] {e}")

    def _on_file_load_error(self, err):
        exctype, value, tb = err
        self.preview.setPlainText(f"[讀取失敗] {value}")

    def _copy_file_url(self) -> None:
        url = self.url_edit.text()
        if url:
            QApplication.clipboard().setText(url)
            self.sb.showMessage(f"網址已複製到剪貼簿：{url}", 3000)
        else:
            self.sb.showMessage("目前沒有可複製的網址", 2000)

    def _toggle_edit_mode(self) -> None:
        is_edit = self.btn_edit.isChecked()
        self.preview.setReadOnly(not is_edit)
        self.btn_save.setEnabled(is_edit)
        if is_edit:
            self.sb.showMessage("已進入編輯模式", 2000)
        else:
            self.sb.showMessage("已切換為唯讀模式", 2000)

    def _save_current_file(self) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        path = self._last_selected_path
        sha = self._current_file_sha
        
        if not (repo and branch and path and sha):
            QMessageBox.warning(self, "錯誤", "無法儲存：缺少 Repo 資訊或檔案 SHA。")
            return

        commit_msg, ok = QInputDialog.getText(self, "儲存檔案", "請輸入 Commit Message：", text=f"Update {os.path.basename(path)}")
        if not ok:
            return

        content = self.preview.toPlainText()
        # GitHub API 需要 bytes 或 str
        # update_file(path, message, content, sha, branch=...)

        self.sb.showMessage("正在儲存...", 0)
        self.btn_save.setEnabled(False)
        self.preview.setReadOnly(True) # 存檔中鎖定

        worker = Worker(
            repo.update_file,
            path,
            commit_msg,
            content,
            sha,
            branch=branch
        )
        self._active_workers.append(worker)
        worker.signals.finished.connect(lambda: self._active_workers.remove(worker) if worker in self._active_workers else None)
        worker.signals.result.connect(self._on_file_saved)
        worker.signals.error.connect(self._on_save_error)
        self.threadpool.start(worker)

    def _on_file_saved(self, result):
        # result is a dict: {'content': ContentFile, 'commit': Commit}
        self.sb.showMessage("儲存成功！", 3000)
        self.btn_save.setEnabled(True)
        self.preview.setReadOnly(False) # 解鎖
        
        # 更新 SHA，避免下次儲存衝突
        if 'content' in result:
            self._current_file_sha = result['content'].sha
            self.sb.showMessage(f"儲存成功！新的 SHA: {self._current_file_sha[:7]}", 3000)

    def _on_save_error(self, err):
        exctype, value, tb = err
        self.btn_save.setEnabled(True)
        self.preview.setReadOnly(False)
        QMessageBox.critical(self, "儲存失敗", f"無法儲存檔案：\n{value}")
        self.sb.showMessage("儲存失敗", 3000)

    # ---------- 檔案操作（下載/zip/上傳） ----------
    def _download_current_file(self) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        items = self.tree_panel.selected_items_info()
        
        if not (repo and branch and items):
            QMessageBox.information(self, "提示", "請先在左側選取要下載的檔案。")
            return

        # 過濾出檔案
        files_to_download = [p for p, t in items if t == 'file']
        if not files_to_download:
            QMessageBox.warning(self, "提示", "選取的項目中沒有檔案（不支援直接下載資料夾，請使用 zip 功能）。")
            return

        if len(files_to_download) == 1:
            # 單檔下載保持原有邏輯：彈出另存新檔對話框
            path = files_to_download[0]
            try:
                content = repo.get_contents(path, ref=branch)
                data = content.decoded_content
                fname, _ = QFileDialog.getSaveFileName(self, "另存檔案", path)
                if fname:
                    with open(fname, "wb") as f: f.write(data)
                    self.sb.showMessage(f"下載成功：{fname}", 3000)
            except Exception as e:
                QMessageBox.critical(self, "錯誤", f"下載失敗：{e}")
        else:
            # 多檔下載：選擇資料夾
            target_dir = QFileDialog.getExistingDirectory(self, "選擇下載目的地資料夾")
            if not target_dir:
                return
            
            prog = QProgressDialog(f"正在下載 {len(files_to_download)} 個檔案...", "取消", 0, len(files_to_download), self)
            prog.setWindowModality(Qt.WindowModal)
            prog.show()

            for i, fpath in enumerate(files_to_download, 1):
                if prog.wasCanceled(): break
                try:
                    content = repo.get_contents(fpath, ref=branch)
                    local_path = os.path.join(target_dir, os.path.basename(fpath))
                    with open(local_path, "wb") as f:
                        f.write(content.decoded_content)
                except Exception as e:
                    print(f"下載失敗: {fpath}, {e}")
                prog.setValue(i)
            prog.close()
            QMessageBox.information(self, "成功", f"已將 {len(files_to_download)} 個檔案下載至：\n{target_dir}")

    def _gather_files_recursive(self, repo, branch: str, base_dir: str) -> list[str]:
        stack = [base_dir]
        files: list[str] = []
        while stack:
            cur = stack.pop()
            try:
                contents = repo.get_contents(cur or '', ref=branch)
            except GithubException:
                continue
            for item in contents:
                if item.type == 'dir':
                    stack.append(item.path)
                else:
                    files.append(item.path)
        return files

    def _download_selected_folder_zip(self) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            QMessageBox.information(self, "提示", "請先選擇 Repo 與分支。")
            return

        base_dir = self.tree_panel.selected_dir_path()
        files = self._gather_files_recursive(repo, branch, base_dir)
        if not files:
            QMessageBox.information(self, "提示", "此資料夾沒有檔案可下載。")
            return

        default_zip = (base_dir.replace('/', '_') or 'root') + '.zip'
        save_path, _ = QFileDialog.getSaveFileName(self, "另存 zip", default_zip)
        if not save_path:
            return

        prog = QProgressDialog("正在建立壓縮檔...", "取消", 0, len(files), self)
        prog.setWindowModality(Qt.WindowModal)
        prog.show()

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
            for i, path in enumerate(files, 1):
                if prog.wasCanceled():
                    QMessageBox.information(self, "已取消", "已取消壓縮下載。")
                    return
                try:
                    content = repo.get_contents(path, ref=branch)
                    data = content.decoded_content
                except GithubException:
                    data = None
                if data is not None:
                    arcname = path[len(base_dir)+1:] if base_dir and path.startswith(base_dir + '/') else (path if base_dir == '' else path)
                    zf.writestr(arcname, data)
                prog.setValue(i)
        prog.close()

        try:
            with open(save_path, 'wb') as f:
                f.write(buf.getvalue())
            QMessageBox.information(self, "成功", "zip 下載完成。")
        except Exception as e:
            QMessageBox.critical(self, "錯誤", f"寫入 zip 檔失敗：{e}")

    def _upload_local_files(self, upload_items: list[tuple[str, str]]) -> None:
        """背景批次上傳本機檔案
        :param upload_items: List of (local_path, remote_full_path)
        """
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return

        # 定義背景上傳任務
        def upload_task(items, repo_obj, branch_name, progress_callback):
            results = []
            for i, (local_path, repo_path) in enumerate(items, 1):
                try:
                    with open(local_path, 'rb') as f:
                        data = f.read()
                    
                    try:
                        old = repo_obj.get_contents(repo_path, ref=branch_name)
                        repo_obj.update_file(repo_path, f"update {repo_path}", data, old.sha, branch=branch_name)
                        results.append(f"[更新] {repo_path}")
                    except GithubException:
                        repo_obj.create_file(repo_path, f"create {repo_path}", data, branch=branch_name)
                        results.append(f"[建立] {repo_path}")
                except Exception as e:
                    results.append(f"[失敗] {repo_path}: {e}")
                
                # 回報進度
                progress_callback.emit(i)
                
            return results

        prog = QProgressDialog(f"正在上傳 0/{len(upload_items)}...", "取消", 0, len(upload_items), self)
        prog.setWindowModality(Qt.WindowModal)
        prog.show()

        worker = Worker(upload_task, upload_items, repo, branch, pass_progress=True)
        self._active_workers.append(worker)
        
        def on_progress(val):
            prog.setValue(val)
            prog.setLabelText(f"正在上傳 {val}/{len(upload_items)}...")

        def on_finished():
            if worker in self._active_workers: self._active_workers.remove(worker)
            prog.close()
            self.tree_panel.load_root(repo, branch)

        def on_result(results):
            msg = "\n".join(results)
            if len(msg) > 500: msg = msg[:500] + "\n... (更多略過)"
            self.sb.showMessage(f"上傳完成：{len(results)} 個檔案", 3000)

        def on_error(err):
            prog.close()
            exctype, value, tb = err
            QMessageBox.critical(self, "錯誤", f"上傳過程發生錯誤：{value}")

        worker.signals.result.connect(on_result)
        worker.signals.error.connect(on_error)
        worker.signals.finished.connect(on_finished)
        worker.signals.progress.connect(on_progress)
        
        self.threadpool.start(worker)

    def _upload_file(self) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            QMessageBox.information(self, "提示", "請先選擇 Repo 與分支。")
            return

        local_paths, _ = QFileDialog.getOpenFileNames(self, "選擇要上傳的檔案 (可多選)")
        if not local_paths:
            return

        base_dir = self.tree_panel.selected_dir_path()
        
        # 建構上傳清單 (單檔/多檔皆為平鋪)
        upload_items = []
        for local in local_paths:
            fname = os.path.basename(local)
            remote = f"{base_dir}/{fname}" if base_dir else fname
            upload_items.append((local, remote))
            
        self._upload_local_files(upload_items)

    # ---------- 右鍵新建/刪除/重新命名/複製貼上 ----------
    def _sanitize_name(self, name: str) -> str:
        # 修正：反斜線需跳脫，將 \ 轉為 /，避免路徑分隔符混淆
        name = name.strip().replace('\\', '/')
        if name.startswith('/'):
            name = name[1:]
        # 濾掉不允許字元（反斜線要跳脫）
        if re.search(r'[\\*?:"<>|]', name):
            raise ValueError('名稱包含不允許的字元。')
        return name

    def _on_new_folder(self, base_dir: str) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            QMessageBox.information(self, '提示', '請先選擇 Repo 與分支。')
            return
        name, ok = QInputDialog.getText(self, '新增資料夾', '輸入資料夾名稱：')
        if not ok or not name.strip():
            return
        try:
            name = self._sanitize_name(name)
        except ValueError as e:
            QMessageBox.warning(self, '提示', str(e))
            return
        dir_path = f'{base_dir}/{name}'.strip('/') if base_dir else name
        placeholder = f'{dir_path}/.gitkeep'
        try:
            try:
                repo.get_contents(placeholder, ref=branch)
                QMessageBox.warning(self, '提示', '資料夾已存在（.gitkeep 已存在）。')
                return
            except GithubException:
                pass
            repo.create_file(placeholder, f'create {placeholder}', b'', branch=branch)
        except GithubException as e:
            QMessageBox.critical(self, '錯誤', f'建立資料夾失敗：{e}')
            return
        self.tree_panel.load_root(repo, branch)
        QMessageBox.information(self, '成功', f'已建立資料夾：{dir_path}')

    def _on_new_file(self, base_dir: str) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            QMessageBox.information(self, '提示', '請先選擇 Repo 與分支。')
            return
        name, ok = QInputDialog.getText(self, '新增檔案', '輸入檔名（可含子資料夾，如 docs/readme.md）：')
        if not ok or not name.strip():
            return
        try:
            name = self._sanitize_name(name)
        except ValueError as e:
            QMessageBox.warning(self, '提示', str(e))
            return
        repo_path = f'{base_dir}/{name}'.strip('/') if base_dir else name
        init_text, ok2 = QInputDialog.getMultiLineText(self, '初始內容', '（可留空）')
        if not ok2:
            return
        data = init_text.encode('utf-8')
        try:
            repo.create_file(repo_path, f'create {repo_path}', data, branch=branch)
        except GithubException as e:
            QMessageBox.critical(self, '錯誤', f'建立檔案失敗：{e}')
            return
        self.tree_panel.load_root(repo, branch)
        QMessageBox.information(self, '成功', f'已建立檔案：{repo_path}')

    def _confirm(self, title: str, text: str) -> bool:
        ret = QMessageBox.question(self, title, text, QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        return ret == QMessageBox.Yes

    def _on_delete(self, path: str, typ: str) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return

        items = self.tree_panel.selected_items_info()
        if not items:
            return

        if len(items) == 1:
            msg = f"確定要刪除 {typ}？\n{path}"
        else:
            msg = f"確定要刪除選取的 {len(items)} 個項目及其內容？"

        if not self._confirm('刪除確認', msg):
            return

        # 收集所有需要刪除的檔案
        all_files_to_delete = []
        for p, t in items:
            if t == 'file':
                all_files_to_delete.append(p)
            else:
                all_files_to_delete.extend(self._gather_files_recursive(repo, branch, p))
                # 資料夾內的 .gitkeep 也要考慮
                # (gather_files_recursive 已經包含檔案，但如果是空資料夾的 .gitkeep 可能需要手動補)
        
        # 移除重複項
        all_files_to_delete = list(set(all_files_to_delete))

        prog = QProgressDialog('正在刪除...', '取消', 0, len(all_files_to_delete), self)
        prog.setWindowModality(Qt.WindowModal)
        prog.show()

        for i, fpath in enumerate(all_files_to_delete, 1):
            if prog.wasCanceled():
                break
            try:
                fobj = repo.get_contents(fpath, ref=branch)
                repo.delete_file(fpath, f"delete {fpath}", fobj.sha, branch=branch)
            except Exception as e:
                print(f"刪除失敗: {fpath}, {e}")
            prog.setValue(i)
        
        prog.close()
        self.tree_panel.load_root(repo, branch)

    def _on_rename(self, path: str, typ: str) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return
        base_dir = path.rsplit('/', 1)[0] if '/' in path else ''
        new_name, ok = QInputDialog.getText(self, '重新命名', '輸入新名稱：', text=os.path.basename(path))
        if not ok or not new_name.strip():
            return
        try:
            new_name = self._sanitize_name(new_name)
        except ValueError as e:
            QMessageBox.warning(self, '提示', str(e))
            return
        new_path = f'{base_dir}/{new_name}'.strip('/') if base_dir else new_name
        if typ == 'file':
            try:
                fobj = repo.get_contents(path, ref=branch)
                # 透過 create 新檔 + delete 舊檔達成 rename
                repo.create_file(new_path, f'move {path} -> {new_path}', fobj.decoded_content, branch=branch)
                repo.delete_file(path, f'delete {path}', fobj.sha, branch=branch)
            except GithubException as e:
                QMessageBox.critical(self, '錯誤', f'重新命名失敗：{e}')
                return
        else:  # 目錄：遞迴搬移（新建 -> 刪舊）
            files = self._gather_files_recursive(repo, branch, path)
            prog = QProgressDialog('正在重新命名/搬移...', '取消', 0, len(files), self)
            prog.setWindowModality(Qt.WindowModal)
            prog.show()
            for i, fpath in enumerate(files, 1):
                if prog.wasCanceled():
                    QMessageBox.information(self, '已取消', '已取消重新命名。')
                    return
                rel = fpath[len(path):].lstrip('/')
                dst = f'{new_path}/{rel}' if rel else f'{new_path}/{os.path.basename(fpath)}'
                try:
                    fobj = repo.get_contents(fpath, ref=branch)
                    repo.create_file(dst, f'move {fpath} -> {dst}', fobj.decoded_content, branch=branch)
                    repo.delete_file(fpath, f'delete {fpath}', fobj.sha, branch=branch)
                except GithubException as e:
                    QMessageBox.warning(self, '警告', f'搬移失敗（略過）：{fpath}\n{e}')
                prog.setValue(i)
            prog.close()
        self.tree_panel.load_root(repo, branch)

    def _on_copy(self, path: str, typ: str) -> None:
        # 獲取所有選取項目
        items = self.tree_panel.selected_items_info()
        if not items:
            return
        self._clipboard = items
        if len(items) == 1:
            self.sb.showMessage(f'已複製：{items[0][0]}', 2000)
        else:
            self.sb.showMessage(f'已複製 {len(items)} 個項目', 2000)

    def _on_paste(self, target_dir: str) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return

        # 1. 優先處理內部剪貼簿 (GitHub 檔案複製)
        if self._clipboard:
            for (path, typ) in self._clipboard:
                if typ == 'file':
                    name = os.path.basename(path)
                    dst = f'{target_dir}/{name}'.strip('/') if target_dir else name
                    try:
                        fobj = repo.get_contents(path, ref=branch)
                        repo.create_file(dst, f'copy {path} -> {dst}', fobj.decoded_content, branch=branch)
                    except GithubException as e:
                        QMessageBox.warning(self, '警告', f'複製失敗（略過）：{path}\n{e}')
                else:
                    files = self._gather_files_recursive(repo, branch, path)
                    prog = QProgressDialog('正在貼上資料夾...', '取消', 0, len(files), self)
                    prog.setWindowModality(Qt.WindowModal)
                    prog.show()
                    for i, fpath in enumerate(files, 1):
                        if prog.wasCanceled():
                            QMessageBox.information(self, '已取消', '已取消貼上。')
                            return
                        rel = fpath[len(path):].lstrip('/')
                        dst = f'{target_dir}/{os.path.basename(path)}/{rel}'.strip('/') if rel else f'{target_dir}/{os.path.basename(path)}'
                        try:
                            fobj = repo.get_contents(fpath, ref=branch)
                            repo.create_file(dst, f'copy {fpath} -> {dst}', fobj.decoded_content, branch=branch)
                        except GithubException as e:
                            QMessageBox.warning(self, '警告', f'複製失敗（略過）：{fpath}\n{e}')
                        prog.setValue(i)
                    prog.close()
            self.tree_panel.load_root(repo, branch)
            return

        # 2. 處理系統剪貼簿 (檔案上傳)
        md = QApplication.clipboard().mimeData()
        if md.hasUrls():
            paths = []
            for url in md.urls():
                if url.isLocalFile():
                    paths.append(url.toLocalFile())
            
            if paths:
                if self._confirm("貼上上傳", f"偵測到 {len(paths)} 個檔案/資料夾，確定要上傳到目前目錄？"):
                    upload_items = []
                    for p in paths:
                        if os.path.isdir(p):
                            # 資料夾：遞迴並保留結構
                            root_name = os.path.basename(p)
                            for root, _, fnames in os.walk(p):
                                for fn in fnames:
                                    local_file = os.path.join(root, fn)
                                    # 計算相對路徑
                                    rel = os.path.relpath(local_file, p)
                                    # 遠端路徑 = 目標目錄/資料夾名/相對路徑
                                    remote_part = f"{root_name}/{rel}".replace('\\', '/')
                                    remote_full = f"{target_dir}/{remote_part}".strip('/') if target_dir else remote_part
                                    upload_items.append((local_file, remote_full))
                        else:
                            # 單檔
                            fname = os.path.basename(p)
                            remote = f"{target_dir}/{fname}".strip('/') if target_dir else fname
                            upload_items.append((p, remote))
                    
                    self._upload_local_files(upload_items)

        elif md.hasImage():
            # 支援貼上截圖
            if self._confirm("貼上圖片", "偵測到圖片，確定要上傳為 pasted_image.png？"):
                img = QApplication.clipboard().image()
                # 暫存圖片
                import tempfile
                tmp = os.path.join(tempfile.gettempdir(), f"pasted_image_{int(os.times().system)}.png")
                img.save(tmp, "PNG")
                
                remote = f"{target_dir}/pasted_image.png".strip('/') if target_dir else "pasted_image.png"
                self._upload_local_files([(tmp, remote)])

    # ---------- 拖曳上傳（多檔） ----------
    def _on_files_dropped(self, target_dir: str, paths: list[str]) -> None:
        repo = self.repo_panel.current_repo()
        branch = self.repo_panel.current_branch()
        if not (repo and branch):
            return
            
        upload_items = []
        for p in paths:
            if os.path.isdir(p):
                # 資料夾：遞迴並保留結構
                root_name = os.path.basename(p)
                for root, _, fnames in os.walk(p):
                    for fn in fnames:
                        local_file = os.path.join(root, fn)
                        # 計算相對路徑
                        rel = os.path.relpath(local_file, p)
                        # 遠端路徑 = 目標目錄/資料夾名/相對路徑
                        remote_part = f"{root_name}/{rel}".replace('\\', '/')
                        remote_full = f"{target_dir}/{remote_part}".strip('/') if target_dir else remote_part
                        upload_items.append((local_file, remote_full))
            else:
                # 單檔
                fname = os.path.basename(p)
                remote = f"{target_dir}/{fname}".strip('/') if target_dir else fname
                upload_items.append((p, remote))
        
        if upload_items:
            self._upload_local_files(upload_items)

    # ---------- 其它 ----------
    def _refresh_all(self):
        if self.github:
            self.repo_panel.set_github(self.github)
            repo = self.repo_panel.current_repo()
            branch = self.repo_panel.current_branch()
            if repo and branch:
                self.tree_panel.load_root(repo, branch)
        else:
            self.sb.showMessage('尚未登入，無法重新整理。', 2000)

    def _show_about(self) -> None:
        QMessageBox.about(
            self,
            '關於',
            (
                '<b>中文 GitHub 檔案管理工具</b><br>'
                'M3：加入 Repo / 分支 / 檔案樹瀏覽與文字預覽。'
            ),
        )
