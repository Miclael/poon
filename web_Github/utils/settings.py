from __future__ import annotations
import json
from pathlib import Path
from typing import Any, Dict




class Settings:
"""簡易 JSON 設定（避免此階段直接耦合 Qt）。"""


def __init__(self, name: str = "zhfm") -> None:
self._dir = Path.home() / f".{name}"
self._dir.mkdir(parents=True, exist_ok=True)
self._file = self._dir / "settings.json"
self._data: Dict[str, Any] = {}
self.load()


def load(self) -> None:
if self._file.exists():
try:
self._data = json.loads(self._file.read_text(encoding="utf-8"))
except Exception:
# 讀取失敗時回退為空設定
self._data = {}
else:
self._data = {}


def save(self) -> None:
self._file.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")


def get(self, key: str, default: Any = None) -> Any:
return self._data.get(key, default)


def set(self, key: str, value: Any) -> None:
self._data[key] = value
self.save()