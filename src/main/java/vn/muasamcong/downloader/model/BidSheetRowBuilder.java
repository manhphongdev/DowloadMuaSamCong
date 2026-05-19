package vn.muasamcong.downloader.model;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDateTime;

public final class BidSheetRowBuilder {

    private String tmbtNumber;
    private String packageName;
    private String procuringEntity;
    private String contractPerformanceFolder;
    private String parentFolderName;
    private BigInteger estimatedBudget;
    private LocalDateTime postedDate;
    private String packageExecutionTime;
    private BidStatus status;
    private LocalDateTime bidClosingTime;
    private Duration remainingTimeToClosing;
    private String folderLink;
    private String tenderLink;

    private BidSheetRowBuilder() {
        // use factory methods
    }

    public static BidSheetRowBuilder create() {
        return new BidSheetRowBuilder();
    }

    public static BidSheetRowBuilder from(BidSheetRow source) {
        return BidSheetRowBuilder.create()
            .tmbtNumber(source.tmbtNumber())
            .packageName(source.packageName())
            .procuringEntity(source.procuringEntity())
            .contractPerformanceFolder(source.contractPerformanceFolder())
            .parentFolderName(source.parentFolderName())
            .estimatedBudget(source.estimatedBudget())
            .postedDate(source.postedDate())
            .packageExecutionTime(source.packageExecutionTime())
            .status(source.status())
            .bidClosingTime(source.bidClosingTime())
            .remainingTimeToClosing(source.remainingTimeToClosing())
            .folderLink(source.folderLink())
            .tenderLink(source.tenderLink());
    }

    public BidSheetRowBuilder tmbtNumber(String tmbtNumber) {
        this.tmbtNumber = tmbtNumber;
        return this;
    }

    public BidSheetRowBuilder packageName(String packageName) {
        this.packageName = packageName;
        return this;
    }

    public BidSheetRowBuilder procuringEntity(String procuringEntity) {
        this.procuringEntity = procuringEntity;
        return this;
    }

    public BidSheetRowBuilder contractPerformanceFolder(String contractPerformanceFolder) {
        this.contractPerformanceFolder = contractPerformanceFolder;
        return this;
    }

    public BidSheetRowBuilder parentFolderName(String parentFolderName) {
        this.parentFolderName = parentFolderName;
        return this;
    }

    public BidSheetRowBuilder estimatedBudget(BigInteger estimatedBudget) {
        this.estimatedBudget = estimatedBudget;
        return this;
    }

    public BidSheetRowBuilder postedDate(LocalDateTime postedDate) {
        this.postedDate = postedDate;
        return this;
    }

    public BidSheetRowBuilder packageExecutionTime(String packageExecutionTime) {
        this.packageExecutionTime = packageExecutionTime;
        return this;
    }

    public BidSheetRowBuilder status(BidStatus status) {
        this.status = status;
        return this;
    }

    public BidSheetRowBuilder bidClosingTime(LocalDateTime bidClosingTime) {
        this.bidClosingTime = bidClosingTime;
        return this;
    }

    public BidSheetRowBuilder remainingTimeToClosing(Duration remainingTimeToClosing) {
        this.remainingTimeToClosing = remainingTimeToClosing;
        return this;
    }

    public BidSheetRowBuilder folderLink(String folderLink) {
        this.folderLink = folderLink;
        return this;
    }

    public BidSheetRowBuilder tenderLink(String tenderLink) {
        this.tenderLink = tenderLink;
        return this;
    }

    public BidSheetRow build() {
        return new BidSheetRow(
            tmbtNumber,
            packageName,
            procuringEntity,
            contractPerformanceFolder,
            parentFolderName,
            estimatedBudget,
            postedDate,
            packageExecutionTime,
            status,
            bidClosingTime,
            remainingTimeToClosing,
            folderLink,
            tenderLink
        );
    }
}
