package vn.muasamcong.downloader.model;

public record DownloadHints(
    boolean resolvedFromApi,
    boolean needTenderNoticeAttachments,
    boolean needHsmtClarificationAttachments,
    boolean needPetitionAttachments,
    boolean needBbmtPdf,
    boolean needKqlcntDecisionPdf,
    boolean needKqlcntEvaluationReport,
    boolean needGoodsExcelExport,
    boolean needContractorCsvExport,
    String decisionFileId,
    String decisionFileName,
    String reportFileId,
    String reportFileName,
    String goodFileId,
    String goodFileName
) {

    public DownloadHints(
        boolean needTenderNoticeAttachments,
        boolean needHsmtClarificationAttachments,
        boolean needPetitionAttachments,
        boolean needBbmtPdf,
        boolean needKqlcntDecisionPdf,
        boolean needKqlcntEvaluationReport,
        boolean needGoodsExcelExport,
        boolean needContractorCsvExport
    ) {
        this(
            true,
            needTenderNoticeAttachments,
            needHsmtClarificationAttachments,
            needPetitionAttachments,
            needBbmtPdf,
            needKqlcntDecisionPdf,
            needKqlcntEvaluationReport,
            needGoodsExcelExport,
            needContractorCsvExport,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public DownloadHints(
        boolean needTenderNoticeAttachments,
        boolean needBbmtPdf,
        boolean needKqlcntDecisionPdf,
        boolean needKqlcntEvaluationReport,
        boolean needGoodsExcelExport,
        boolean needContractorCsvExport
    ) {
        this(
            needTenderNoticeAttachments,
            needTenderNoticeAttachments,
            needTenderNoticeAttachments,
            needBbmtPdf,
            needKqlcntDecisionPdf,
            needKqlcntEvaluationReport,
            needGoodsExcelExport,
            needContractorCsvExport
        );
    }

    public static DownloadHints unknown() {
        return new DownloadHints(
            false,
            false,
            false,
            false,
            true,
            true,
            true,
            true,
            true,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static DownloadHints fullRun() {
        return unknown();
    }

    public boolean hasSeleniumDownloads() {
        return needTenderNoticeAttachments
            || needHsmtClarificationAttachments
            || needPetitionAttachments
            || needBbmtPdf
            || needKqlcntDecisionPdf
            || needKqlcntEvaluationReport;
    }
}
