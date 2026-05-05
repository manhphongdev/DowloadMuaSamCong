package vn.muasamcong.downloader.model;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public record BidSheetRow(
    String tmbtNumber, //so tmbt
    String packageName, //ten goi thau
    String procuringEntity, // co dau tu
    String contractPerformanceFolder, // thu muc thhd
    String parentFolderName, // folder cha
    BigInteger estimatedBudget, // du toan
    BidStatus status,
    LocalDateTime bidClosingTime,
    Duration remainingTimeToClosing,
    String folderLink
) {

    public static List<String> headers() {
        return List.of(
            "TMBT Number",
            "Package Name",
            "Procuring Entity",
            "Contract Performance Folder",
            "Parent Folder Name",
            "Estimated Budget",
            "Status",
            "Bid Closing Time",
            "Remaining Time To Closing",
            "Folder Link"
        );
    }
}
