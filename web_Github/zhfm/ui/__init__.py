# File: zhfm/ui/__init__.py
from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

__all__ = ["AuthDialog", "RepoPanel", "TreePanel"]

def __getattr__(name: str) -> Any:
    mapping = {
        "AuthDialog": (".auth_dialog", "AuthDialog"),
        "RepoPanel": (".repo_panel", "RepoPanel"),
        "TreePanel": (".tree_panel", "TreePanel"),
    }
    if name not in mapping:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

    mod_name, attr = mapping[name]
    mod: ModuleType = importlib.import_module(mod_name, __name__)
    obj = getattr(mod, attr)        # 類別未定義時，這裡才會拋清楚的錯
    globals()[name] = obj           # 快取，下次取用不再重新 import
    return obj
