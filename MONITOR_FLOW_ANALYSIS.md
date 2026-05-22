# Monitor And Download Flow Analysis

## Tổng Quan

`RUN MONITOR` là luồng chính để theo dõi keyword trong các folder đã chọn, quyết định keyword nào cần tải, tải file bằng Selenium khi cần, sau đó refresh dữ liệu sheet/export từ API.

Luồng tổng quát:

```text
RUN MONITOR
 -> đọc folder đang chọn
 -> đọc keyword trong folder
 -> sync vào monitor_tracking_keywords.json
 -> chọn keyword cần download
 -> chạy DownloadCoordinator.runTargets()
 -> resolve API params/detail URL/download hints
 -> quyết định có cần Selenium không
 -> nếu cần thì mở browser tải file
 -> refresh bid_sheet_rows.json từ API
 -> sync Google Sheets nếu bật
 -> cập nhật monitor_tracking_keywords.json
```

## 1. Entry Point

Code chính nằm ở:

```text
src/main/java/vn/muasamcong/downloader/app/DownloaderFxApp.java
```

Method:

```java
runAutoMonitorCycle(boolean manual)
```

Khi bấm `RUN MONITOR`, app làm các bước đầu:

- Kiểm tra không có monitor task khác đang chạy.
- Kiểm tra không có download task khác đang chạy.
- Lấy danh sách folder đang được tick.
- Đọc keyword bằng `FolderKeywordReader.readKeywords(selectedRoots)`.
- Sync keyword vào `monitor_tracking_keywords.json`.

## 2. Monitor Tracking Store

File state:

```text
monitor_tracking_keywords.json
```

Code:

```text
src/main/java/vn/muasamcong/downloader/store/MonitorKeywordStore.java
```

Mỗi record dùng key:

```text
folderPath|keyword
```

Các field quan trọng:

- `downloadStatus`: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `PARTIAL`
- `downloadAttempts`
- `lastDownloadAt`
- `lastDownloadedFiles`
- `lastDownloadMessage`
- `lastDownloadSuccessAt`

Khi monitor bắt đầu, app gọi:

```java
MonitorKeywordStore.resetStuckRunningRecords();
```

Mục đích là đổi record đang `RUNNING` từ lần chạy bị crash/interrupted sang `FAILED`.

## 3. Chọn Keyword Cần Download

Code:

```java
selectMonitorDownloadTargets(List<KeywordTarget> targets)
```

Keyword được đưa vào download nếu `downloadStatus` là:

```text
null
PENDING
FAILED
PARTIAL
```

Keyword đã `SUCCESS` sẽ không được đưa vào download lại trong monitor thường.

Trước khi chạy download, app đánh dấu:

```java
MonitorKeywordStore.markDownloadRunning(targetKeys(downloadTargets));
```

Kết quả:

```text
downloadStatus = RUNNING
lastDownloadMessage = "Downloading"
downloadAttempts += 1
```

## 4. DownloadCoordinator.runTargets

Code:

```text
src/main/java/vn/muasamcong/downloader/core/DownloadCoordinator.java
```

Method monitor dùng:

```java
DownloadCoordinator.runTargets(downloadTargets, concurrency)
```

Các bước chính:

- Cleanup Chrome/ChromeDriver cũ.
- Chuẩn bị `bid_sheet_rows.json`.
- Resolve `DownloadHints` cho từng target.
- Skip Selenium nếu API hints nói không cần tải.
- Skip Selenium nếu `downloadHintsHash` không đổi.
- Chạy `DownloadWorker` cho các target còn lại.
- Sau worker success, lưu lại `downloadHintsHash`.
- Refresh bid sheet rows từ API.
- Sync Google Sheets nếu bật.
- Ghi run history.

## 5. Resolve Detail URL Và API Params

Code:

```text
src/main/java/vn/muasamcong/downloader/export/BidSearchApiResolver.java
```

Trước hết app đọc cache:

```text
bid_tracking_records.json
```

Nếu record đã có `apiParams`, app dùng lại.

Nếu chưa có, app gọi Search API:

```text
/services/smart/search?token=fake
```

Payload search dùng:

```text
index = es-contractor-selection
matchType = exact
matchFields = notifyNo,bidName
type = es-notify-contractor
```

Từ kết quả search, app lấy các field:

- `notifyNo`
- `id` / `notifyId`
- `inputResultId`
- `bidOpenId`
- `techReqId`
- `bidPreNotifyResultId`
- `bidPreOpenId`
- `processApply`
- `bidMode`
- `bidForm`
- `planNo`
- `stepCode`
- `isInternet`

Sau đó build detail URL dạng:

```text
/web/guest/contractor-selection?...render=detail-v2...
```

Và lưu lại bằng:

```java
BidTrackingRecordStore.upsertFromDetailUrl(target, resolved.detailUrl());
```

## 6. Resolve DownloadHints

Code:

```text
src/main/java/vn/muasamcong/downloader/export/BidSheetApiSyncService.java
```

Method:

```java
resolveDownloadHints(BidApiParams params)
```

Các API được gọi:

```text
KQLCNT:
/services/expose/contractor-input-result/get?token=fake

Làm rõ HSMT:
/services/lcnt_tbmt_yclr?token=fake

Kiến nghị:
/services/lcnt_tbmt_kn?token=fake
```

Kết quả được chuyển thành `DownloadHints`:

- `needTenderNoticeAttachments`
- `needHsmtClarificationAttachments`
- `needPetitionAttachments`
- `needBbmtPdf`
- `needKqlcntDecisionPdf`
- `needKqlcntEvaluationReport`
- `needGoodsExcelExport`
- `needContractorCsvExport`
- `decisionFileId`
- `decisionFileName`
- `reportFileId`
- `reportFileName`
- `goodFileId`
- `goodFileName`

Ý nghĩa chính:

- Có dữ liệu Làm rõ HSMT thì tải attachment Làm rõ HSMT.
- Có dữ liệu Kiến nghị thì tải attachment Kiến nghị.
- Có `bidOpenId` thì cần BBMT PDF.
- Có `decisionFileId` thì cần quyết định KQLCNT.
- Có `reportFileId` thì cần báo cáo đánh giá.
- Có `goodFileId` hoặc `goodsList` thì export Excel hàng hóa.
- Có nhà thầu trúng thì export contractor CSV.

## 7. Cơ Chế Skip Selenium

Code:

```java
ArtifactFingerprintService.hashDownloadHints(hints)
ArtifactFingerprintStore.isDownloadHintsUnchanged(key, hintsHash)
```

File hash:

```text
monitor_artifact_fingerprints.json
```

Có 2 trường hợp skip Selenium.

Trường hợp 1: `DownloadHints` không đổi so với lần trước.

```text
Download hints unchanged. Skipping Selenium
```

Trường hợp 2: API hints nói không có file nào cần Selenium tải.

```text
API hints skip Selenium
```

`hasSeleniumDownloads()` chỉ tính các nhóm cần browser:

- TBMT attachments
- Làm rõ HSMT attachments
- Kiến nghị attachments
- BBMT PDF
- KQLCNT decision PDF
- KQLCNT evaluation report PDF

Các export như goods Excel và contractor CSV không nằm trong Selenium downloads vì được xử lý qua API khi refresh sheet rows.

## 8. DownloadWorker

Code:

```text
src/main/java/vn/muasamcong/downloader/core/DownloadWorker.java
```

Worker chạy:

```java
performDownloadFlow2(driver, downloadDir, target)
```

Mỗi keyword có tối đa 3 attempts vì:

```text
MAX_RETRIES = 2
maxAttempts = maxRetries + 1
```

Nếu browser session bị lỗi, worker recreate driver cho lần retry sau.

## 9. Mở Detail Page

Nếu có detail URL từ API/cache:

```java
navigateDirectToDetailPage(driver, directDetailUrl, keyword)
```

Nếu chưa có detail URL:

```java
navigateToDetailPage(driver, keyword)
```

Fallback Selenium search làm:

```text
open trang chủ
nhập keyword
click search
đợi result
click detail link đầu tiên
switch window/tab nếu cần
```

Sau khi vào detail page, app lưu stable tracking record:

```java
BidTrackingRecordStore.upsertFromDetailUrl(target, detailUrl)
```

## 10. Tải File Bằng Selenium

Trong `performDownloadFlow2()`, app tải theo `DownloadHints`.

### 10.1. TBMT Attachments

Nếu:

```java
hints.needTenderNoticeAttachments()
```

App gọi:

```java
downloadTenderNoticeAttachments()
```

Nó xử lý 2 sub-tab:

- `Làm rõ HSMT`, output folder `lam-ro-hsmt`
- `Kiến nghị`, output folder `kien-nghi`

Nếu API hints nói không cần thì app không click tab này.

### 10.2. BBMT PDF

Nếu:

```java
hints.needBbmtPdf()
```

và có tab:

```text
Biên bản mở thầu
```

App click tab, chờ nội dung, tải PDF BBMT.

### 10.3. KQLCNT PDFs

Nếu cần decision hoặc report:

```java
hints.needKqlcntDecisionPdf()
hints.needKqlcntEvaluationReport()
```

và có tab:

```text
Kết quả lựa chọn nhà thầu
```

App click tab và tải:

- Quyết định phê duyệt KQLCNT
- Báo cáo đánh giá E-HSDT/HSDT

Cuối flow, app kiểm tra:

```java
downloadedCount < expectedDownloadCount
```

Nếu thiếu file thì throw exception để retry.

## 11. Cập Nhật Trạng Thái Download

Sau khi worker chạy xong, UI gọi:

```java
MonitorKeywordStore.applyDownloadRunStats(downloadStats)
```

Mapping trạng thái:

- `SUCCESS` -> `MonitorDownloadStatus.SUCCESS`
- Không success nhưng `downloadedFiles > 0` -> `PARTIAL`
- Còn lại -> `FAILED`

Nếu success, coordinator còn lưu lại `downloadHintsHash` để lần sau skip Selenium nếu hints không đổi.

## 12. Refresh Bid Sheet Rows Và Export API

Sau download, hoặc khi không có target cần Selenium, app gọi:

```java
BidSheetApiSyncService.refreshBidSheetRowsFromTracking()
```

Nó đọc:

```text
bid_tracking_records.json
```

Với mỗi record có `apiParams`, app gọi:

```text
TBMT:
/services/lcnt_tbmt_ttc_ldt?token=fake

KQLCNT:
/services/expose/contractor-input-result/get?token=fake
```

Sau đó build `BidSheetRow` và ghi:

```text
bid_sheet_rows.json
```

Đồng thời export thêm nếu có dữ liệu:

- Contractor CSV
- Goods Excel

## 13. Artifact Fingerprints

File:

```text
monitor_artifact_fingerprints.json
```

Các hash chính:

- `sheetRowHash`
- `contractorCsvHash`
- `goodsExcelHash`
- `downloadHintsHash`

Mục đích:

- Không rewrite CSV nếu dữ liệu contractor không đổi.
- Không rewrite Excel nếu dữ liệu goods không đổi.
- Không chạy Selenium nếu download hints không đổi.
- Chỉ hash dữ liệu output quan trọng, không hash toàn bộ API response.

## 14. Sync Google Sheets

Sau khi refresh `bid_sheet_rows.json`, app gọi:

```java
GoogleSheetsSyncService.syncFromJsonIfEnabled(BID_ROWS_JSON_FILE)
```

Nếu config Google Sheets bật, dữ liệu sẽ được đẩy lên sheet.

## 15. Vai Trò Của Các File State

```text
monitor_tracking_keywords.json
```

Theo dõi keyword monitor, trạng thái download, số lần chạy, lỗi gần nhất.

```text
bid_tracking_records.json
```

Cache detail URL và API params để không cần search lại.

```text
monitor_artifact_fingerprints.json
```

Lưu hash để skip Selenium/export khi dữ liệu không đổi.

```text
bid_sheet_rows.json
```

Output rows dùng để sync Google Sheets.

```text
run_state.json
```

State download thông thường, được worker mark success/failure.

```text
run_history
```

Lịch sử các lần chạy.

## 16. Điểm Yếu Với Anti-Bot

Monitor hiện tại là API-first, phụ thuộc nhiều vào các API:

```text
smart/search
contractor-input-result/get
lcnt_tbmt_yclr
lcnt_tbmt_kn
lcnt_tbmt_ttc_ldt
```

Nếu API bị anti-bot/WAF chặn, các biểu hiện thường gặp:

- API trả HTML thay vì JSON.
- Request timeout.
- HTTP connect timeout.
- TLS handshake bị server đóng.
- Có keyword gọi được, có keyword fail ngẫu nhiên.

Ảnh hưởng:

- Không resolve được detail URL bằng Search API.
- Không resolve được `DownloadHints` chính xác.
- Không skip Selenium thông minh được.
- Refresh sheet/export CSV/Excel có thể thiếu dữ liệu.

Fallback hiện tại:

- Nếu không có API hints, app dùng `DownloadHints.unknown()`.
- Khi unknown, Selenium chạy rộng hơn thay vì skip.
- Tuy nhiên phần refresh/export sau cùng vẫn phụ thuộc API nên vẫn có thể fail nếu API bị chặn.

## 17. Tóm Tắt Ngắn

Monitor hiện tại hoạt động theo mô hình:

```text
Folder keywords
 -> monitor_tracking_keywords
 -> chọn PENDING/FAILED/PARTIAL
 -> Search API/cache detail URL
 -> API hints quyết định có cần Selenium
 -> skip nếu hints không đổi
 -> Selenium chỉ tải file cần thiết
 -> API refresh rows/export CSV Excel
 -> sync Google Sheets
 -> cập nhật monitor status/hash
```

Điểm mạnh:

- Có cache detail URL/API params.
- Có hash để tránh tải/export lại khi dữ liệu không đổi.
- Selenium chỉ chạy khi API hints báo cần tải file.

Điểm yếu:

- API bị anti-bot sẽ làm monitor mất khả năng tối ưu.
- Refresh sheet và export API vẫn phụ thuộc API trực tiếp.
- Search API fail có thể làm phải fallback sang browser search, chậm hơn và dễ lỗi hơn.
