package vn.muasamcong.downloader.app;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import vn.muasamcong.downloader.core.DownloadCoordinator;

public final class MainApp {

    private static final int DEFAULT_CONCURRENCY = 1;

    private MainApp() {
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Path rootFolder = resolveRootFolder(args, scanner);
        int concurrency = resolveConcurrency(args, scanner);

        DownloadCoordinator.run(rootFolder, concurrency);
    }

    private static Path resolveRootFolder(String[] args, Scanner scanner) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0].trim());
        }

        System.out.println("Enter folder path:");
        System.out.print("> ");

        String input = scanner.nextLine();
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Ban phai nhap duong dan folder.");
        }
        return Paths.get(input.trim());
    }

    private static int resolveConcurrency(String[] args, Scanner scanner) {
        if (args.length > 1 && !args[1].isBlank()) {
            return parseConcurrencyOrDefault(args[1].trim());
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
