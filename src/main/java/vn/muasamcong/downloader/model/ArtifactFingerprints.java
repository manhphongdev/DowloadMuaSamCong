package vn.muasamcong.downloader.model;

public record ArtifactFingerprints(
    String key,
    String sheetRowHash,
    String contractorCsvHash,
    String goodsExcelHash,
    String downloadHintsHash,
    String lastArtifactCheckAt,
    String lastArtifactChangedAt
) {
}
