package vn.muasamcong.downloader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainApp {

    private static final String BASE_URL = "https://muasamcong.mpi.gov.vn/";
    private static final int DEFAULT_CONCURRENCY = 1;
    private static final int MAX_RETRIES = 2;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(90);

    private MainApp() {
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Path excelPath = resolveExcelPath(args, scanner);
        Path baseDownloadDir = resolveDownloadBaseDir(args, scanner);
        int concurrency = resolveConcurrency(args, scanner);

        if (ensureSampleExcel(excelPath)) {
            return;
        }

        List<String> keywords = ExcelReader.readKeywords(excelPath);
        if (keywords.isEmpty()) {
            System.out.println("No keywords found in file: " + excelPath.toAbsolutePath());
            return;
        }

        System.out.println("Loaded keywords: " + keywords.size());
        System.out.println("Concurrent browsers: " + concurrency);

        RunStats stats = new RunStats(keywords.size());
        int workerCount = Math.min(concurrency, keywords.size());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        Queue<String> keywordQueue = new ConcurrentLinkedQueue<>(keywords);
        List<Runnable> workers = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            workers.add(new DownloadWorker(keywordQueue, BASE_URL, baseDownloadDir, MAX_RETRIES, DOWNLOAD_TIMEOUT, stats));
        }

        workers.forEach(pool::submit);
        pool.shutdown();

        try {
            if (!pool.awaitTermination(45, TimeUnit.MINUTES)) {
                pool.shutdownNow();
                System.out.println("Execution timed out and unfinished workers were interrupted.");
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            System.out.println("Main thread interrupted while waiting for workers.");
        }

        Utils.logFinalStats(stats);
    }

    private static boolean ensureSampleExcel(Path excelPath) {
        if (!excelPath.toFile().exists()) {
            ExcelReader.createSampleExcel(excelPath);
            System.out.println("Created Excel template at: " + excelPath.toAbsolutePath());
            System.out.println("Please update column A with your keywords, then run again.");
            return true;
        }
        return false;
    }

    private static Path resolveExcelPath(String[] args, Scanner scanner) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0].trim());
        }

        Path defaultPath = Paths.get("sample", "keywords.xlsx");
        System.out.println("Nhap duong dan file Excel (.xlsx). De trong de dung mac dinh:");
        System.out.println(defaultPath.toAbsolutePath());
        System.out.print("> ");

        String input = scanner.nextLine();
        if (input == null || input.isBlank()) {
            return defaultPath;
        }
        return Paths.get(input.trim());
    }

    private static Path resolveDownloadBaseDir(String[] args, Scanner scanner) {
        if (args.length > 1 && !args[1].isBlank()) {
            return Paths.get(args[1].trim());
        }

        Path defaultPath = Paths.get("downloads");
        System.out.println("Nhap thu muc luu file download. De trong de dung mac dinh:");
        System.out.println(defaultPath.toAbsolutePath());
        System.out.print("> ");

        String input = scanner.nextLine();
        Path selected = (input == null || input.isBlank()) ? defaultPath : Paths.get(input.trim());
        Utils.ensureDirectory(selected);
        return selected;
    }

    private static int resolveConcurrency(String[] args, Scanner scanner) {
        if (args.length > 2 && !args[2].isBlank()) {
            return parseConcurrencyOrDefault(args[2].trim());
        }

        System.out.println("Nhap so trinh duyet mo dong thoi. De trong de dung mac dinh (1):");
        System.out.print("> ");

        String input = scanner.nextLine();
        if (input == null || input.isBlank()) {
            return DEFAULT_CONCURRENCY;
        }
        return parseConcurrencyOrDefault(input.trim());
    }

    private static int parseConcurrencyOrDefault(String rawValue) {
        try {
            int value = Integer.parseInt(rawValue);
            if (value < 1) {
                System.out.println("Gia tri khong hop le, dung mac dinh: " + DEFAULT_CONCURRENCY);
                return DEFAULT_CONCURRENCY;
            }
            return value;
        } catch (NumberFormatException ex) {
            System.out.println("Gia tri khong hop le, dung mac dinh: " + DEFAULT_CONCURRENCY);
            return DEFAULT_CONCURRENCY;
        }
    }
}
