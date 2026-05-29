# BBMT

Portal renders BBMT PDF client-side (tab Biên bản mở thầu → download icon).

App integration:

- Monitor API sync sets `needBbmtPdf` when `bbmtSeleniumDownload` is on, package has `bidOpenId`, and no BBMT PDF in `auto-download/`.
- Monitor BBMT phase uses Selenium (`DownloadWorker`) to click the portal export.
- No server-side / OpenPDF generation in this repo.
