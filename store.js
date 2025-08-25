/* ===========================================================
   共用資料層與時間工具（index.html / manage.html 共用）
   - 資料 Key：fault_report_store_v3
   - 提供：getStore() / saveStore()
   - 時間工具：toLocalISO / nowISO / addMinutesISO / formatHHmm
   =========================================================== */

// ===== 常數 =====
const STORE_KEY = "fault_report_store_v3";

// ===== 取得 / 初始化資料 =====
function getStore() {
  try{
    const raw = localStorage.getItem(STORE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    // 預設資料
    if(!parsed.machines) parsed.machines = ["鑽孔-1","AOI-1"];
    if(!parsed.reasonsByMachine) parsed.reasonsByMachine = {
      "鑽孔-1":["主軸異常"],
      "AOI-1":["鏡頭異常"]
    };
    if(!parsed.statuses) parsed.statuses = ["已派修","已修復"];
    if(!parsed.records) parsed.records = [];
    return parsed;
  }catch(e){
    // 壞資料就重置
    const fallback = {
      machines:["鑽孔-1","AOI-1"],
      reasonsByMachine:{"鑽孔-1":["主軸異常"],"AOI-1":["鏡頭異常"]},
      statuses:["已派修","已修復"],
      records:[]
    };
    localStorage.setItem(STORE_KEY, JSON.stringify(fallback));
    return fallback;
  }
}

// ===== 儲存資料 =====
function saveStore(storeObj){
  try{
    localStorage.setItem(STORE_KEY, JSON.stringify(storeObj));
  }catch(e){
    // 可擴充錯誤處理（容量滿等）
    console.warn("saveStore 失敗：", e);
  }
}

// ===== 時間工具 =====
function toLocalISO(datetime){
  const t = (datetime instanceof Date) ? datetime : new Date(datetime);
  const pad = n=>String(n).padStart(2,'0');
  const y=t.getFullYear(), m=pad(t.getMonth()+1), d=pad(t.getDate()), h=pad(t.getHours()), min=pad(t.getMinutes());
  return `${y}-${m}-${d}T${h}:${min}`;
}
function nowISO(){ return toLocalISO(new Date()); }
function addMinutesISO(baseISO, minutes){
  const dt = new Date(baseISO);
  if (isNaN(dt.getTime())) return nowISO();
  dt.setMinutes(dt.getMinutes()+minutes);
  return toLocalISO(dt);
}
function formatHHmm(value){
  try{
    const d=new Date(value);
    if(isNaN(d.getTime())) return "--:--";
    const hh=String(d.getHours()).padStart(2,"0");
    const mm=String(d.getMinutes()).padStart(2,"0");
    return `${hh}:${mm}`;
  }catch(e){return "--:--";}
}