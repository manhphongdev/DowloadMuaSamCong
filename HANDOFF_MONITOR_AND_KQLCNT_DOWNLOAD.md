# Handoff: Monitor + tải file sau Sheet + QĐ KQLCNT không Selenium

Tài liệu tóm tắt để mở **tab chat mới** (tránh lag tab dài). Cập nhật: 2026-05-24.

---

## 1. Mục tiêu dự án (DownloadMuaSamCong)

- **Monitor (phase 1):** discover folder → API sync → ghi `bid_sheet_rows.json` → Google Sheets (chỉ publish khi sheet đổi).
- **Phase 2 (tùy chọn):** Selenium tải PDF/đính kèm vào `{folder}/auto-download/` sau khi sheet đã sync.
- **CSV/Excel API** (không Selenium): `Nhà thầu trúng thầu - {TBMT}.csv`, `Bảng dự thầu hàng hoá - {TBMT}.xlsx` — export trong phase API, skip nếu file đã tồn tại.

---

## 2. Đã implement — Phase 2 Monitor (Selenium sau sheet)

### Luồng

```text
MonitorScheduler.runOnce(concurrency)
  tryBeginMonitor()
    MonitorCycleService.run()     // API + sheet
    endMonitor()
    tryBeginDownload()
      PackageDownloadPlanner      // smart filter
      DownloadCoordinator.runTargetsDownloadOnly()
      MonitorDownloadPhaseService.applyResults()
    endDownload()
```

### File chính

| File | Vai trò |
|------|---------|
| [`MonitorScheduler.java`](src/main/java/vn/muasamcong/downloader/application/monitor/MonitorScheduler.java) | Orchestration phase 1 + 2 |
| [`MonitorCycleService.java`](src/main/java/vn/muasamcong/downloader/application/monitor/MonitorCycleService.java) | Discover → API sync → aggregate sheet |
| [`PackageDownloadPlanner.java`](src/main/java/vn/muasamcong/downloader/application/monitor/PackageDownloadPlanner.java) | Lọc gói cần Selenium |
| [`MonitorDownloadPhaseService.java`](src/main/java/vn/muasamcong/downloader/application/monitor/MonitorDownloadPhaseService.java) | Plan / execute / cập nhật `BidPackage` |
| [`DownloadCoordinator.runTargetsDownloadOnly`](src/main/java/vn/muasamcong/downloader/core/DownloadCoordinator.java) | Selenium only, không `publishBidSheet`, không `RunStateStore.filterPending` |
| [`MonitorSettings`](src/main/java/vn/muasamcong/downloader/domain/monitor/MonitorSettings.java) | `downloadFilesAfterSheet` |
| [`AppFeatures.MONITOR_SELENIUM_AFTER_SHEET`](src/main/java/vn/muasamcong/downloader/core/AppFeatures.java) | `true` — bật phase 2 |
| [`DownloaderFxApp`](src/main/java/vn/muasamcong/downloader/app/DownloaderFxApp.java) | Checkbox **"Tải file Selenium sau khi cập nhật sheet"** |

### Smart filter (PackageDownloadPlanner)

Enqueue Selenium khi:

- `syncStatus == SYNCED`
- `downloadHints.hasSeleniumDownloads()`
- **Và một trong:** chưa `lastDownloadStatus == SUCCESS`, hints đổi (`downloadHintsHash` ≠ `lastDownloadedHintsHash`), hoặc FAILED/PARTIAL

### Cấu hình UI

- **Save monitor** → bật `downloadFilesAfterSheet`
- Spinner **Concurrent browsers** dùng cho phase 2
- `SELENIUM_DOWNLOAD_ENABLED = false` → RUN vẫn = Monitor; phase 2 phụ thuộc checkbox + `MONITOR_SELENIUM_AFTER_SHEET`

### BidPackage fields mới

- `lastDownloadStatus`, `lastDownloadError`, `lastDownloadedHintsHash`

---

## 3. API metadata — Quyết định phê duyệt KQLCNT (đã có, chưa tải file HTTP)

### Endpoint (guest, `token=fake`)

```http
POST https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/contractor-input-result/get?token=fake
Content-Type: application/json

{"id":"<inputResultId>"}
```

**Quan trọng:** body dùng `inputResultId`, **không** dùng `notifyId`. Chi tiết: [`API_CURLS.md`](API_CURLS.md).

### Response (ví dụ IB2600094230)

| Field | Ví dụ |
|-------|--------|
| `inputResultId` | `b736df91-0534-4dff-8e92-ba87dd9f054f` |
| `decisionFileId` | `9743a2ae-d115-4d30-ba32-50cc0464967b` |
| `decisionFileName` | `IB2600094230_QuyetDinhPheDuyetKQLCNT_27_03_2026.pdf` |

Code: [`BidSheetApiSyncService.resolveDownloadHints`](src/main/java/vn/muasamcong/downloader/export/BidSheetApiSyncService.java) → lưu vào `BidPackage.downloadHints` + `ArtifactFingerprintStore`.

### URL trang chi tiết (chỉ UI, không phải API tải file)

```
https://muasamcong.mpi.gov.vn/web/guest/contractor-selection?...&stepCode=notify-contractor-step-4-kqlcnt
&notifyNo=IB2600094230
&inputResultId=b736df91-0534-4dff-8e92-ba87dd9f054f
&id=23dc3b74-336a-474a-9f0a-94ba9ea48de5
```

---

## 4. Cách portal tải PDF (không cần Selenium *trên web*, nhưng cần agent local)

### JS portal (đã bắt từ trang)

Khi click file trong tab **Kết quả lựa chọn nhà thầu**:

```http
GET http://localhost:1234/api/download/file/browser/public?fileId={decisionFileId}
```

- `downloadFilePublic = true` → endpoint public (không Bearer trong code guest).
- `localhost:1234` = **VNeGP Client Agent** (app desktop), **không** phải API công khai trên `muasamcong.mpi.gov.vn`.

### Selenium trong app hiện tại

[`DownloadWorker.downloadKqlcntDecision`](src/main/java/vn/muasamcong/downloader/core/DownloadWorker.java) — click icon download trên tab KQLCNT (không dùng `decisionFileId` trực tiếp).

---

## 5. VNeGP Client Agent — câu hỏi “lần đầu phải mở browser?”

| Việc | Cần browser? |
|------|----------------|
| Cài + **chạy nền** VNeGP Client Agent | Không (cài app, service cổng 1234) |
| Tải thủ công trên **muasamcong** lần đầu | Có thể có popup cài/mở agent |
| **Java/Postman** gọi `127.0.0.1:1234` | Không cần browser **nếu** agent chấp nhận request |

Kiểm tra agent:

```powershell
curl.exe -I "http://127.0.0.1:1234/api/download/file/browser/public?fileId=<decisionFileId>"
```

- `Connection refused` → agent chưa chạy.
- `200` + `application/octet-stream` → OK.
- `403 Forbidden` → agent chạy nhưng **từ chối** client (xem mục 6).

---

## 6. 403 Forbidden từ Postman (ảnh user)

Request:

```http
GET http://127.0.0.1:1234/api/download/file/browser/public?fileId=9743a2ae-d115-4d30-ba32-50cc0464967b
```

Response JSON kiểu Spring:

```json
{
  "status": 403,
  "error": "Forbidden",
  "path": "/api/download/file/browser/public"
}
```

**Ý nghĩa:** Agent **đang chạy** (không phải connection refused), nhưng từ chối request từ Postman/API client.

**Quan sát phiên trước:** Click file trên trang muasamcong (cùng máy, agent bật) → `200`, ~306 KB PDF. `fetch` từ context trang cũng `200`.

**Hướng xử lý khi debug tiếp:**

1. Trên Chrome: F12 → Network → click tải QĐ PDF → copy **full request headers** (so với Postman).
2. Thử Postman thêm:
   - `Origin: https://muasamcong.mpi.gov.vn`
   - `Referer: https://muasamcong.mpi.gov.vn/web/guest/contractor-selection`
   - `User-Agent` giống Chrome
3. Agent có thể yêu cầu **session/token** chỉ sinh sau khi mở trang portal (chưa reverse đủ).
4. Gọi `https://muasamcong.mpi.gov.vn/api/download/...` từ máy dev → **404** (không expose trực tiếp).

---

## 7. Đề xuất implement tiếp — tải QĐ PDF không Selenium

### Option A: HTTP qua agent (máy có VNeGP)

Trong `PackageApiSyncService` / `BidSheetApiSyncService` sau khi có `decisionFileId`:

```java
GET http://127.0.0.1:1234/api/download/file/browser/public?fileId={id}
→ lưu {auto-download}/{decisionFileName}
```

- Skip nếu file đã tồn tại (giống CSV/Excel).
- Set `needKqlcntDecisionPdf = false` khi file đã có → giảm Selenium phase 2.
- Cần xử lý **403** (retry với headers, log “cần mở portal/agent”).

### Option B: Giữ Selenium (server không có agent)

Phase 2 hiện tại — phù hợp VPS/server không cài VNeGP.

### Option C: Chỉ API metadata + CSV/Excel

Không tải PDF QĐ nếu không có agent và không muốn Selenium.

---

## 8. Phân tách loại file

| Nguồn | File | Cách |
|-------|------|------|
| API | CSV nhà thầu, Excel hàng hóa | `BidSheetApiSyncService` — phase 1 |
| Agent `localhost:1234` | PDF QĐ KQLCNT, báo cáo đánh giá (`reportFileId`) | **Chưa có trong Java** |
| Selenium | PDF BBMT, đính kèm TBMT/HSMT, QĐ (fallback) | `DownloadWorker` — phase 2 |

`DownloadHints.hasSeleniumDownloads()` **không** gồm CSV/Excel API.

---

## 9. ExecutionGate & STOP

- Monitor và Download Selenium không chồng: `endMonitor()` → `tryBeginDownload()`.
- STOP monitor: `DownloadCoordinator.requestStop()` + cancel task (`DownloaderFxApp`).

---

## 10. Dữ liệu / vấn đề đã biết (chưa fix)

- Trùng folder `01. EVN&NPT` vs `Copy` → sheet tab trùng.
- `bid_packages.json` một số bản ghi `sheetRow: null` nhưng SYNCED (legacy).
- Xóa PDF trong `auto-download` nhưng hints không đổi → **không** tự tải lại (v1, ngoài scope).

---

## 11. Prompt gợi ý cho tab chat mới

Copy đoạn sau:

```text
Đọc HANDOFF_MONITOR_AND_KQLCNT_DOWNLOAD.md trong repo DownloadMuaSamCong.
Tiếp tục: implement EgpPublicFileDownloadService — tải PDF QĐ KQLCNT qua 
GET http://127.0.0.1:1234/api/download/file/browser/public?fileId={decisionFileId}
trong phase API Monitor; xử lý 403; skip nếu file đã có; giảm Selenium needKqlcntDecisionPdf.
Tham chiếu API_CURLS.md và BidSheetApiSyncService.resolveDownloadHints.
```

---

## 12. Build

```powershell
cd D:\AT\DownloadMuaSamCong
.\mvnw.cmd compile
```

Chạy app: bật checkbox monitor **Tải file Selenium sau khi cập nhật sheet**, Save monitor, Run monitor once.
