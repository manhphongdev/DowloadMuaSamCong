# Monitor Download Flow

## Current Goal

`RUN MONITOR` is the main entrypoint. The app should avoid repeated browser work and avoid rewriting exported files when API data has not changed.

## Runtime State Files

- `monitor_tracking_keywords.json`
  - Canonical monitor keyword list.
  - Tracks `folderPath|keyword`, download status, attempts and last runtime messages.
- `bid_tracking_records.json`
  - Detail URL/API params cache.
  - Populated from Search API or from Selenium detail URL fallback.
- `monitor_artifact_fingerprints.json`
  - Output-oriented change detection state.
  - Stores only hashes, not full API responses.
- `bid_sheet_rows.json`
  - Sheet row output cache.

## API-First Resolution

For each keyword, the coordinator resolves data before starting Selenium:

1. Try `bid_tracking_records.json` for cached `apiParams`.
2. If missing, call Search API:
   - `/services/smart/search?token=fake`
   - match by `notifyNo`/`bidName`
   - build detail URL from returned params.
3. Resolve `DownloadHints` from APIs.
4. Decide whether Selenium is needed.

Worker should not search keyword manually when a detail URL was resolved by API. It opens the API-built detail URL directly.

## DownloadHints

`DownloadHints` decides which browser actions are needed:

- `needHsmtClarificationAttachments`
- `needPetitionAttachments`
- `needBbmtPdf`
- `needKqlcntDecisionPdf`
- `needKqlcntEvaluationReport`
- `needGoodsExcelExport`
- `needContractorCsvExport`

Important rules:

- No `Kiến nghị` click unless petition API has content.
- No `Làm rõ HSMT` click unless clarification API has content.
- No KQLCNT tab click unless decision/report PDF is needed.
- If `DownloadHints` has no Selenium downloads, skip worker entirely.

## Change Detection

Change detection hashes only output data, not whole API responses.

Stored hashes:

- `sheetRowHash`
  - Fields actually written to Google Sheets.
- `contractorCsvHash`
  - Rows/columns actually exported to contractor CSV.
- `goodsExcelHash`
  - Header + rows actually exported to goods Excel.
- `downloadHintsHash`
  - File IDs/names and boolean flags that decide Selenium downloads.

Hash state is stored in `monitor_artifact_fingerprints.json`.

## Export Behavior

- Contractor CSV is skipped if `contractorCsvHash` is unchanged.
- Goods Excel is skipped if `goodsExcelHash` is unchanged.
- Sheet row hash is updated during API refresh.
- Existing files are not deleted when API data is missing or unchanged.

## Selenium Queue Behavior

Before enqueueing a keyword:

1. Build `DownloadHints`.
2. Compute `downloadHintsHash`.
3. If hash is unchanged, skip Selenium and mark processed success with message `Download hints unchanged`.
4. If hints say no Selenium downloads, skip Selenium and update the hints hash.
5. Otherwise enqueue only that keyword with the resolved detail URL and hints.

After a successful Selenium worker run, the coordinator stores the new `downloadHintsHash`.

## Clear State

`Clear run state` must clear related state consistently:

- `run_state.json`
- `run_history`
- `bid_tracking_records.json`
- `monitor_tracking_keywords.json`
- `monitor_artifact_fingerprints.json`
- `bid_sheet_rows.json`

Selected clear removes matching `folderPath|keyword` from all relevant stores.
