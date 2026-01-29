# File: zhfm/ui/auth_dialog.py
from __future__ import annotations
import keyring
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton, QMessageBox
)
from github import Github, BadCredentialsException

SERVICE_NAME = "zhfm-github"


class AuthDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("登入 GitHub")
        self.resize(400, 160)

        self.token_input = QLineEdit(); self.token_input.setEchoMode(QLineEdit.Password)
        self.token_input.setPlaceholderText("請輸入 GitHub 個人存取權杖 (PAT)")

        # 載入已存的 Token
        saved = keyring.get_password(SERVICE_NAME, "default")
        if saved: self.token_input.setText(saved)

        # 按鈕
        self.btn_test = QPushButton("測試連線")
        self.btn_save = QPushButton("儲存")
        self.btn_cancel = QPushButton("取消")
        self.btn_test.clicked.connect(self.test_token)
        self.btn_save.clicked.connect(self.save_token)
        self.btn_cancel.clicked.connect(self.reject)

        layout = QVBoxLayout(); layout.addWidget(QLabel("GitHub Token：")); layout.addWidget(self.token_input)
        btn_row = QHBoxLayout(); [btn_row.addWidget(b) for b in (self.btn_test, self.btn_save, self.btn_cancel)]
        layout.addLayout(btn_row); self.setLayout(layout)

    def test_token(self):
        """測試成功就自動登入：
        1) 先驗證 token
        2) 驗證成功 -> 直接儲存到 keyring -> 關閉對話框 (accept)
        3) MainWindow 會在 .exec() 返回後自動呼叫 _auto_login() 載入 repo
        """
        token = self.token_input.text().strip()
        if not token:
            QMessageBox.warning(self, "提示", "請先輸入 Token")
            return
        try:
            gh = Github(token)
            user = gh.get_user(); _ = user.login
        except BadCredentialsException:
            QMessageBox.critical(self, "失敗", "Token 無效，請重新輸入。")
            return
        except Exception as e:
            QMessageBox.critical(self, "錯誤", f"測試失敗：{e}")
            return

        # 驗證成功 -> 直接儲存 + 關閉
        keyring.set_password(SERVICE_NAME, "default", token)
        QMessageBox.information(self, "成功", f"驗證成功並已登入！使用者：{user.login}")
        self.accept()

    def save_token(self):
        token = self.token_input.text().strip()
        if not token:
            QMessageBox.warning(self, "提示", "請先輸入 Token")
            return
        keyring.set_password(SERVICE_NAME, "default", token)
        QMessageBox.information(self, "成功", "Token 已儲存。")
        self.accept()

    @staticmethod
    def get_saved_token() -> str | None:
        return keyring.get_password(SERVICE_NAME, "default")

    @staticmethod
    def clear_token():
        try:
            keyring.delete_password(SERVICE_NAME, "default")
        except keyring.errors.PasswordDeleteError:  # type: ignore[attr-defined]
            pass
