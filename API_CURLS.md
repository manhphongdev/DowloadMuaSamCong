# Mua Sam Cong API Curls

Ghi chu nhanh cac API dung de lay metadata goi thau theo `notifyNo` va cac ID trong detail/search result. Cac vi du ben duoi dung `token=fake` theo request da bat duoc tu browser.

## 1. Search Goi Thau

Tac dung: tim goi theo `notifyNo`, lay cac ID moi nhat nhu `notifyId`, `inputResultId`, `bidOpenId`, `stepCode`, `statusForNotify`.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/smart/search?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '[
    {
      "pageSize": 1,
      "pageNumber": 0,
      "query": [
        {
          "index": "es-contractor-selection",
          "keyWord": "IB2600026487",
          "matchType": "exact",
          "matchFields": ["notifyNo", "bidName"],
          "filters": [
            {
              "fieldName": "type",
              "searchType": "in",
              "fieldValues": ["es-notify-contractor"]
            }
          ]
        }
      ]
    }
  ]'
```

## 2. Thong Bao Moi Thau

Tac dung: lay thong tin chinh cua tab Thong bao moi thau: ten goi, chu dau tu, gia goi, thoi diem dong/mo thau, trang thai tong, version, lich gia han.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/lcnt_tbmt_ttc_ldt?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"id":"20107d78-5c96-4771-9d83-8c92c160839d"}'
```

## 3. Ho So Moi Thau

Tac dung: lay sub-tab Ho so moi thau: cau truc chuong/mau HSMT, file quyet dinh HSMT, file phu luc/dinh kem neu co.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/lcnt_tbmt_hsmt?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"id":"20107d78-5c96-4771-9d83-8c92c160839d","processApply":"LDT"}'
```

## 4. Yeu Cau Lam Ro

Tac dung: lay sub-tab Yeu cau lam ro: danh sach cau hoi lam ro HSMT, noi dung cau hoi, phan hoi, file dinh kem neu co.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/lcnt_tbmt_yclr?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487","processApply":"LDT"}'
```

## 5. Kien Nghi

Tac dung: lay sub-tab Kien nghi: danh sach kien nghi cua nha thau va phan hoi neu co. Neu khong co kien nghi se tra rong.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/lcnt_tbmt_kn?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487","processApply":"LDT"}'
```

## 6. Hoi Nghi Tien Dau Thau

Tac dung: lay sub-tab Hoi nghi tien dau thau: thong tin hoi nghi, bien ban/noi dung/file neu co. Neu khong co se tra rong.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/lcnt_tbmt_hntdt?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487","processApply":"LDT"}'
```

## 7. Bien Ban Mo Thau - Danh Sach Gia

Tac dung: lay tab Bien ban mo thau: danh sach nha thau tham du, gia du thau, gia sau giam, bao dam du thau.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/ldtkqmt/bid-notification-p/lot-open?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487","type":"TBMT","packType":1,"viewType":0,"notifyId":"20107d78-5c96-4771-9d83-8c92c160839d"}'
```

## 8. Bien Ban Mo Thau - Chi Tiet

Tac dung: lay chi tiet mo thau theo tung nha thau/lo: `contractorCode`, `contractorName`, `lotPrice`, `lotFinalPrice`, `discountPercent`, `bidGuaranteeAmount`, `bidGuaranteeEff`.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/ldtkqmt/bid-notification-p/lotOpenDetail?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487","type":"TBMT","packType":1,"viewType":0,"notifyId":"20107d78-5c96-4771-9d83-8c92c160839d"}'
```

## 9. Ket Qua Lua Chon Nha Thau

Tac dung: lay tab Ket qua lua chon nha thau: quyet dinh phe duyet, ngay cong khai, nha thau trung, gia trung, file quyet dinh, file bao cao danh gia.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/contractor-input-result/get?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"id":"c2e19043-2500-40af-b79d-b4f7abc797ba"}'
```

## 10. Thong Tin Chu Yeu Hop Dong

Tac dung: lay tab Thong tin chu yeu cua hop dong: ma hop dong, so hop dong, ten hop dong, ngay ky, ngay hieu luc, nha thau, gia truoc/sau VAT, ngay cong khai. Neu chua co hop dong se tra `Count: 0`.

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/econsign/contract-info/list-contract-for-po?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"notifyNo":"IB2600026487"}'
```

## Mapping Params

- `notifyNo`: ma TBMT, vi du `IB2600026487`.
- `id` / `notifyId`: ID thong bao moi thau, dung cho API 2, 3, 7, 8.
- `inputResultId`: ID ket qua lua chon nha thau, dung cho API 9.
- `processApply`: thuong la `LDT`, dung cho API 3, 4, 5, 6.
- `stepCode`: dung de suy luan trang thai vong doi goi thau.
- `bidOpenId`: nen luu lai, du hien tai chua thay dung trong 10 curl tren.

## Luu Y Khi Build Detail URL

Khong duoc chi thay moi `id` / `notifyId` trong detail URL de check cac tab khac. Moi tab co the dung ID rieng:

- `id` / `notifyId`: dinh danh TBMT, dung cho tab Thong bao moi thau va HSMT.
- `bidOpenId`: dinh danh Bien ban mo thau / Ket qua mo thau.
- `inputResultId`: dinh danh Ket qua lua chon nha thau.
- `notifyNo`: dung cho search, lam ro HSMT, kien nghi, hoi nghi tien dau thau, thong tin hop dong.

Neu chi thay `id` / `notifyId` ma giu nguyen `inputResultId` cua goi cu thi tab Ket qua lua chon nha thau se sai hoac khong tra du lieu. Cach dung la goi API Search theo `notifyNo`, lay lai tron bo params moi nhat roi build lai URL/API request.

Vi du KQLCNT phai goi bang `inputResultId`, khong phai `notifyId`:

```bash
curl 'https://muasamcong.mpi.gov.vn/o/egp-portal-contractor-selection-v2/services/expose/contractor-input-result/get?token=fake' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -X POST \
  --data-raw '{"id":"7acae524-da71-41b1-9502-735d6d8f917a"}'
```

Voi link mau:

```text
notifyNo=IB2600058203
id=9d025c2f-b203-4114-91d3-e74a1bbaa8b8
notifyId=9d025c2f-b203-4114-91d3-e74a1bbaa8b8
inputResultId=7acae524-da71-41b1-9502-735d6d8f917a
bidOpenId=592d4030-8c5c-4fb3-a127-d54e34b54c8a
stepCode=notify-contractor-step-4-kqlcnt
```

Neu muon check KQLCNT cho goi khac, phai thay `inputResultId` theo goi do. Chi thay `id` / `notifyId` la chua du.

## Sheet Metadata

Voi sheet hien tai, API bat buoc nhat la API 1 va API 2. Nen goi them API 7, 8, 9, 10 de tinh trang thai chinh xac theo vong doi goi thau. API 3, 4, 5, 6 dung de enrich them HSMT, lam ro, kien nghi, hoi nghi tien dau thau.

## Mapping Tam Thoi Ra Sheet

Khong fallback metadata. Tam thoi khong thay doi logic trang thai.

- `So TBMT`: API KQLCNT `bideContractorInputResultDTO.notifyNo`.
- `Ten goi thau`: API KQLCNT `bideContractorInputResultDTO.bidName`.
- `CDT`: API KQLCNT `bideContractorInputResultDTO.investorName`.
- `Thu muc THHD`: ten folder cuoi tu tracking `folderPath`.
- `Du toan`: API KQLCNT `bideContractorInputResultDTO.bidEstimatePrice`.
- `Don vi du toan`: API KQLCNT `bideContractorInputResultDTO.bidEstimatePriceUnit`.
- `Ngay dang tai`: API KQLCNT `bideContractorInputResultDTO.publicDate`.
- `Thoi gian thuc hien goi thau`: API KQLCNT `lotResultDTO[].contractorList[].cperiodText` tai contractor co `bidResult = 1`; neu gia tri co nhung chua co don vi ngay thi them ` ngay`.
- `Gia trung thau`: API KQLCNT `lotResultDTO[].contractorList[].bidWiningPrice` tai contractor co `bidResult = 1`.
- `Trang thai`: tam thoi khong thay doi logic status.
- `Thoi diem dong thau`: API TBMT `bidoNotifyContractorM.bidCloseDate`.
- `Con lai`: tu tinh `max(0, bidoNotifyContractorM.bidCloseDate - now)`.
- `Link thu muc THHD`: tracking `folderPath`.

Voi API KQLCNT, body phai dung `inputResultId`:

```json
{"id":"<inputResultId>"}
```

Voi API TBMT, body dung `notifyId`:

```json
{"id":"<notifyId>"}
```

## CSV Bao Cao Nha Thau

File xuat rieng trong thu muc `auto-download` cua tung goi thau:

```text
Nhà thầu trúng thầu - {notifyNo}.csv
```

File nay lay tu API KQLCNT `bideContractorInputResultDTO.lotResultDTO[].contractorList[]`.

- `STT`: so thu tu dong trong CSV.
- `Ma dinh danh`: `orgCode`.
- `Ma so thue`: `taxCode`.
- `Ten nha thau`: `orgFullname`.
- `Gia du thau (VND)`: `lotPrice`, format ngan cach hang nghin bang dau `.`.
- `Diem ky thuat (neu co)`: `techScore`.
- `Gia danh gia (neu co)`: `evalBidPrice`, format tien.
- `Gia du thau sau hieu chinh sai lech thua (neu co), giam gia (neu co)`: `lotFinalPrice`, format tien.
- `Gia trung thau (VND)`: `bidWiningPrice`, format tien.
- `Thoi gian thuc hien goi thau`: `cperiod` + don vi; `D` duoc hien thi thanh `ngay`.
- `Thoi gian thuc hien goi thau chi tiet`: `bidExecutionTime`.
- `Thoi gian thuc hien hop dong`: `cperiodText`.
- `Cac noi dung khac (neu co)`: `otherContent`.

## Excel Bang Du Thau Hang Hoa

File xuat vao thu muc `auto-download` cua tung goi thau:

```text
Bảng dự thầu hàng hoá - {notifyNo}.xlsx
```

Nguon du lieu: API KQLCNT `bideContractorInputResultDTO.lotResultDTO[].goodsList`. `goodsList` la chuoi JSON, lay entry co `contractorCode` bang `winningCode`, sau do lay bang `formValue.lotContent.Table`.

- `Ma TBMT`: `bideContractorInputResultDTO.notifyNo`.
- `Ten goi thau`: `bideContractorInputResultDTO.bidName`.
- `Danh muc hang hoa`: `name`.
- `Ky ma hieu`: `codeGood`.
- `Nhan hieu`: `labelGood` hoac `lableGood`.
- `Xuat xu`: `origin`.
- `Hang san xuat`: `manufacturer`.
- `Cau hinh, tinh nang ky thuat co ban`: `feature` hoac `technique`.
- `Don vi tinh`: `uom`.
- `Khoi luong`: `qty`.
- `Ma HS`: `hsCode` / `maHs` / `maHS` neu co.
- `Don gia trung thau`: `bidPrice`.
- `Thanh tien da bao gom thue, phi, le phi`: `amount`.
- `Thoi gian giao hang / Tien do cung cap`: `cPeriod` / `cperiod` / `deliveryTime`.

## Rule Phan Loai Status (Search-first)

Nguyen tac:

- Uu tien du lieu tu API Search Goi Thau de xep trang thai.
- Khong dung `stepCode` de phan loai status.
- Chi dung ID + API chi tiet + thoi gian dong thau.

Thu tu uu tien (cao -> thap):

1. `Co thong tin hop dong`
   - API `econsign/contract-info/list-contract-for-po` tra danh sach khong rong.

2. `Co KQLCNT`
   - Co `inputResultId` hop le va goi API KQLCNT (`expose/contractor-input-result/get`) tra du lieu `bideContractorInputResultDTO`.

3. `Da mo thau`
   - Co `bidOpenId` hoac API BBMT (`lot-open` / `lotOpenDetail`) co du lieu.

4. `Dang moi thau`
   - Chua vao 3 muc tren va `now < bidCloseDate`.

5. `Da dong thau`
   - Chua vao 3 muc tren va `now >= bidCloseDate`.

Bang mapping nhanh:

- Co du lieu contract-info -> `Co thong tin hop dong`.
- `inputResultId` hop le + KQLCNT API co du lieu -> `Co KQLCNT`.
- `bidOpenId` hoac BBMT API co du lieu -> `Da mo thau`.
- Neu chua vao 3 muc tren -> xet `bidCloseDate`:
  - `now < bidCloseDate` -> `Dang moi thau`.
  - `now >= bidCloseDate` -> `Da dong thau`.
