package vn.muasamcong.downloader.model;

public record BidApiParams(
    String notifyNo,
    String id,
    String notifyId,
    String inputResultId,
    String bidOpenId,
    String techReqId,
    String bidPreNotifyResultId,
    String bidPreOpenId,
    String processApply,
    String bidMode,
    String bidForm,
    String planNo,
    String stepCode,
    String isInternet
) {
}
