from __future__ import annotations
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path




_DEF_LOG_DIR = Path.home() / ".zhfm" / "logs"
_DEF_LOG_DIR.mkdir(parents=True, exist_ok=True)
_LOG_FILE = _DEF_LOG_DIR / "app.log"




def get_logger(name: str = "zhfm") -> logging.Logger:
logger = logging.getLogger(name)
if logger.handlers:
return logger


logger.setLevel(logging.INFO)


# 檔案輪替 Handler（最多 ~5MB x 3 份）
fh = RotatingFileHandler(_LOG_FILE, maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8")
fmt = logging.Formatter(
fmt="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
datefmt="%Y-%m-%d %H:%M:%S",
)
fh.setFormatter(fmt)
logger.addHandler(fh)


# 也輸出到主控台（方便開發）
ch = logging.StreamHandler()
ch.setFormatter(fmt)
logger.addHandler(ch)


logger.info("Logger 初始化完成：%s", _LOG_FILE)
return logger