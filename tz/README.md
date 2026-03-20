# 中文 GitHub 檔案管理工具（M1）


本專案使用 PySide6 建立中文 GUI，這是第一個可執行的骨架：
- 主視窗 + 選單 + 狀態列
- 三分欄版面（儲存庫 / 檔案樹 / 預覽編輯）


## 安裝與執行


```bash
python -m venv .venv
source .venv/bin/activate # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
python app.py